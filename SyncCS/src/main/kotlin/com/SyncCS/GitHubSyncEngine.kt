package com.SyncCS

import android.content.Context
import android.app.Activity
import android.util.Log
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/**
 * Conservative GitHub sync protocol:
 * - New repositories require an explicit initial-backup command.
 * - A second device must explicitly restore; it NEVER publishes its pre-existing data.
 * - Missing local keys are NEVER interpreted as deletes without a recorded edit event.
 * - Remote history is immutable Git commit history. Every local restore has an encrypted rollback copy.
 * - No extension is uninstalled, installed or replaced until a separate verified installer is provided.
 */
internal object GitHubSyncEngine {
    private val mutex = Mutex()
    @Volatile var applying: Boolean = false
        private set
    @Volatile var status: String = "GitHub sync not connected"
        private set

    data class Preview(val generation: String, val head: String, val count: Int, val pluginCount: Int)
    data class Plan(val merged: GitHubSnapshot, val changed: Int, val deleted: Int, val conflicts: Int)

    fun markChanged(context: Context, key: String) {
        if (applying || !SyncData.isTracked(key)) return
        GitHubSyncConfig(context).preferences.edit()
            .putLong("modified/" + key, System.currentTimeMillis()).apply()
    }

    private fun local(context: Context, generation: String): GitHubSnapshot {
        val settings = SyncData.snapshot(context)
        require(settings.size <= 100000)
        return GitHubSnapshot(generation,
            GitHubSyncConfig(context).deviceId,
            ProfileSync.snapshot(context),
            PluginInventory.snapshot(context), settings)
    }
    private fun baselineFile(context: Context) =
        File(context.filesDir, "synccs-github-baseline-v1.enc")

    private fun securedStore(file: File, content: String) {
        val encrypted = LocalVault.encrypt(content.toByteArray(Charsets.UTF_8))
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(encrypted, Charsets.UTF_8)
        check(temp.renameTo(file)) { "Could not atomically save encrypted checkpoint" }
    }
    private fun securedRead(file: File): String {
        return String(LocalVault.decrypt(file.readText(Charsets.UTF_8)), Charsets.UTF_8)
    }
    private fun getBaseline(context: Context): GitHubSnapshot? {
        val file = baselineFile(context)
        return if (file.isFile) GitHubSnapshot.parse(securedRead(file)) else null
    }
    private fun saveBaseline(context: Context, state: GitHubSnapshot) =
        securedStore(baselineFile(context), state.json())

    private fun recoveryCheckpoint(context: Context, previous: GitHubSnapshot): File {
        val dir = File(context.filesDir, "synccs-github-recovery")
        check(dir.isDirectory || dir.mkdirs())
        val file = File(dir, "before-restore-" + System.currentTimeMillis() + "-" +
            UUID.randomUUID().toString().take(8) + ".enc")
        securedStore(file, previous.json())
        check(file.isFile && file.length() > 0L)
        return file
    }
    private fun invalidateMarkers(context: Context, keys: Collection<String>) {
        val prefs = GitHubSyncConfig(context).preferences
        val edit = prefs.edit()
        keys.forEach { edit.remove("modified/" + it) }
        check(edit.commit())
    }

    fun inspect(context: Context): Preview? {
        val config = GitHubSyncConfig(context)
        require(config.connected())
        val remote = GitHubSyncTransport(config).read() ?: return null
        return Preview(remote.snapshot.generation, remote.sha,
            remote.snapshot.settings.size,
            PluginSync.decode(remote.snapshot.plugins).items.size)
    }

    suspend fun createFirstBackup(context: Context): String = mutex.withLock {
        try {
            val config = GitHubSyncConfig(context)
            require(config.connected())
            val backend = GitHubSyncTransport(config)
            check(backend.read() == null) {
                "A GitHub backup already exists. Use Restore; do not initialize with this device."
            }
            check(SyncRuntime.refreshLoadedState()) {
                "CloudStream has not completed plugin loading; initial backup refused"
            }
            val state = local(context, UUID.randomUUID().toString())
            require(state.settings.isNotEmpty()) {
                "Local settings snapshot is empty. Refusing to initialize a backup."
            }
            val head = backend.save(state, null)
            saveBaseline(context, state)
            check(config.preferences.edit()
                .putString("generation", state.generation).putString("head", head).commit())
            invalidateMarkers(context, state.settings.keys)
            status = "GitHub initial backup created: " + state.settings.size + " settings"
            status
        } catch (error: Exception) {
            status = "GitHub initial backup failed: " + (error.message ?: "Unknown error")
            status
        }
    }

    private fun applyRemote(context: Context, target: GitHubSnapshot, knownLocal: GitHubSnapshot) {
        // Rollback checkpoint must exist before any SharedPreferences or profile changes.
        val checkpoint = recoveryCheckpoint(context, knownLocal)
        try {
            val desiredProfiles = ProfileSync.decode(target.profiles)
            require(desiredProfiles.items.containsKey(0)) {
                "Remote backup is missing CloudStream's default profile"
            }
            PluginSync.decode(target.plugins) // Check format before touching local state.
            val changes = mutableMapOf<String, String?>()
            for (key in knownLocal.settings.keys + target.settings.keys) {
                if (knownLocal.settings[key] != target.settings[key]) {
                    changes[key] = target.settings[key]
                }
            }
            applying = true
            ProfileSync.apply(context, desiredProfiles)
            SyncData.apply(context, changes)
        } catch (error: Exception) {
            // Restore preferences and profile roster if a later step fails.
            try {
                val actual = local(context, knownLocal.generation)
                val rollback = mutableMapOf<String, String?>()
                for (key in actual.settings.keys + knownLocal.settings.keys) {
                    if (actual.settings[key] != knownLocal.settings[key]) {
                        rollback[key] = knownLocal.settings[key]
                    }
                }
                ProfileSync.apply(context, ProfileSync.decode(knownLocal.profiles))
                SyncData.apply(context, rollback)
            } catch (rollbackError: Exception) {
                Log.e("SyncCS", "Restore failed; encrypted checkpoint at " + checkpoint.name,
                    rollbackError)
            }
            throw IOException("Restore failed; encrypted local rollback checkpoint preserved", error)
        } finally {
            applying = false
        }
    }

    suspend fun restoreExisting(context: Context): String = mutex.withLock {
        try {
            val config = GitHubSyncConfig(context)
            val backend = GitHubSyncTransport(config)
            val remote = backend.read() ?: error("No GitHub backup exists")
            val prior = getBaseline(context)
            check(prior == null || prior.generation == remote.snapshot.generation) {
                "Different backup generation; disconnect and inspect before relinking"
            }
            val before = local(context, remote.snapshot.generation)
            applyRemote(context, remote.snapshot, before)
            saveBaseline(context, remote.snapshot)
            check(config.preferences.edit().putString("generation", remote.snapshot.generation)
                .putString("head", remote.sha).commit())
            invalidateMarkers(context, before.settings.keys + remote.snapshot.settings.keys)
            status = "GitHub backup restored: " + remote.snapshot.settings.size +
                " settings. Plug-in binaries were not modified."
            status
        } catch (error: Exception) {
            status = "GitHub restore failed: " + (error.message ?: "Unknown error")
            status
        }
    }

    suspend fun restoreHistoricalCommit(context: Context, commitSha: String): String = mutex.withLock {
        try {
            val config = GitHubSyncConfig(context)
            val backend = GitHubSyncTransport(config)
            val head = backend.read() ?: error("No existing GitHub backup")
            val previous = backend.readAt(commitSha)
            require(head.sha != previous.sha) { "Already on the requested checkpoint" }
            // Increment the generation: old device baselines cannot publish into
            // the restored account until explicitly reinitialized from this epoch.
            val target = previous.snapshot.copy(
                generation = UUID.randomUUID().toString(), device = config.deviceId
            )
            val before = local(context, head.snapshot.generation)
            recoveryCheckpoint(context, before)
            val newHead = backend.save(target, head.sha)
            applyRemote(context, target, before)
            saveBaseline(context, target)
            check(config.preferences.edit().putString("generation", target.generation)
                .putString("head", newHead).commit())
            invalidateMarkers(context, before.settings.keys + target.settings.keys)
            status = "Historical backup restored as new generation: " + target.generation
            status
        } catch (error: Exception) {
            status = "Historical restore blocked: " + (error.message ?: "Unknown error")
            status
        }
    }

    internal fun reconcile(
        base: GitHubSnapshot,
        current: GitHubSnapshot,
        remote: GitHubSnapshot,
        explicitDeletes: Set<String>
    ): Plan {
        check(remote.generation == base.generation && current.generation == base.generation) {
            "Backup generation changed; old devices cannot upload"
        }
        val output = remote.settings.toMutableMap()
        var changed = 0
        var deleted = 0
        var conflicts = 0
        val keys = (current.settings.keys + base.settings.keys).toSet()
        for (key in keys) {
            val previous = base.settings[key]
            val local = current.settings[key]
            val server = remote.settings[key]
            if (local == previous) continue
            if (local == null && (previous == null || key !in explicitDeletes)) continue
            if (server != previous) {
                conflicts++
                continue
            }
            changed++
            if (local == null) {
                deleted++
                output.remove(key)
            } else output[key] = local
        }
        // Batch-wide guard: a transient, incomplete local snapshot must not generate
        // a large upload, even when change events were incorrectly recorded.
        check(changed <= maxOf(100, remote.settings.size / 50)) {
            "Bulk preference change blocked. Inspect the local state before syncing."
        }
        check(deleted <= maxOf(10, remote.settings.size / 100)) {
            "Bulk deletion blocked. Only verified individual removals are allowed."
        }
        return Plan(remote.copy(settings = output), changed, deleted, conflicts)
    }

    suspend fun syncExisting(context: Context): String = mutex.withLock {
        try {
            val config = GitHubSyncConfig(context)
            val base = getBaseline(context) ?: error(
                "Restore an existing backup or explicitly create the first backup."
            )
            val remote = GitHubSyncTransport(config).read() ?: error(
                "Existing GitHub backup vanished. Refusing to recreate it."
            )
            check(config.preferences.getString("generation", null) == remote.snapshot.generation)
            val current = local(context, remote.snapshot.generation)
            // Never infer deletion from a missing preference. The old coordinator
            // caused a mass deletion by treating an incomplete snapshot as intent.
            // A future explicit delete protocol will use durable per-item operations.
            val explicitDeletes = emptySet<String>()
            val plan = reconcile(base, current, remote.snapshot, explicitDeletes)
            // Never modify installed plugin binaries or profile roster automatically.
            // Their former device-local state is not authoritative.
            val updatedHead = if (plan.changed > 0) {
                GitHubSyncTransport(config).save(plan.merged, remote.sha)
            } else remote.sha
            val before = current
            applyRemote(context, plan.merged, before)
            saveBaseline(context, plan.merged)
            check(config.preferences.edit().putString("head", updatedHead).commit())
            invalidateMarkers(context, current.settings.keys + base.settings.keys)
            status = "GitHub sync complete: " + plan.changed + " settings uploaded, " +
                plan.conflicts + " conflicts preserved in local recovery checkpoint"
            status
        } catch (error: Exception) {
            status = "GitHub sync blocked: " + (error.message ?: "Unknown error")
            status
        }
    }
}
