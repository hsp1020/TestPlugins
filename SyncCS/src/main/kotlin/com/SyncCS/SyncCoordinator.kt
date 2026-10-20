package com.SyncCS

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object SyncCoordinator {
    private val lock = Mutex()
    @Volatile var applying = false
        private set
    @Volatile var lastStatus: String = "Not connected"
        private set

    private fun fingerprint(raw: String?): String =
        if (raw == null) "<deleted>" else SyncBackend.digest(raw)

    private fun baseline(context: Context): MutableMap<String, String> {
        val json = JSONObject(SyncConfiguration(context).preferences.getString("base", "{}") ?: "{}")
        return json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
    }

    fun markChanged(context: Context, path: String) {
        if (applying) return
        val config = SyncConfiguration(context)
        val key = "modified/" + path
        config.preferences.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    suspend fun synchronize(context: Context, activity: Activity? = null): String = lock.withLock {
        val config = SyncConfiguration(context)
        if (!config.active()) {
            lastStatus = "Sign in to enable sync"
            return@withLock lastStatus
        }
        if (config.preferences.getBoolean("account_switch_blocked", false)) {
            lastStatus = "Sync paused: account changed. Clear local CloudStream data before linking a different account."
            return@withLock lastStatus
        }
        // The server rejects stale expected_version writes. Re-fetch and re-merge
        // up to three times rather than dropping either side's pending changes.
        for (attempt in 0 until 3) {
            val result = synchronizePass(context, activity)
            if (!result.startsWith("Sync conflict:")) return@withLock result
            if (attempt < 2) delay(250L * (attempt + 1))
        }
        lastStatus = "Sync failed: repeated concurrent edits; changes retained for retry"
        lastStatus
    }

    private suspend fun synchronizePass(context: Context, activity: Activity?): String {
        val config = SyncConfiguration(context)
        val backend = SyncBackend(config)
        var stage = "loading cloud state"
        try {
            val startedUser = backend.userId()
            fun verifyAccount() {
                val stored = config.preferences.getString("session", null)
                    ?: error("Sync account removed during operation")
                val json = JSONObject(String(LocalVault.decrypt(stored), Charsets.UTF_8))
                check(json.getString("user_id") == startedUser &&
                    !config.preferences.getBoolean("account_switch_blocked", false)) {
                    "Sync account changed during operation"
                }
            }

            val cipher = backend.cipher()
            val remotes = backend.list()
            stage = "decrypting remote data"
            val remoteValues = remotes.associate { item ->
                item.key to if (item.deleted) null else cipher.decrypt(
                    item.key, item.cipher ?: error("Missing encrypted value for " + item.key)
                )
            }
            val remoteByKey = remotes.associateBy { it.key }
            stage = "reading local preferences"
            val local = SyncData.snapshot(context)
            val currentPlugins = PluginInventory.snapshot(context)
            val base = baseline(context)
            stage = "comparing settings changes"
            val apply = mutableMapOf<String, String?>()
            val upload = mutableMapOf<String, String?>()
            val next = base.toMutableMap()
            // x/plugins is reconciled separately, including deletion tombstones.
            next.remove("x/plugins")
            val all = (remoteValues.keys + local.keys + base.keys)
                .filter { it != "x/plugins" }.toSortedSet()

            for (key in all) {
                val current = local[key]
                val remotePresent = remoteValues.containsKey(key)
                val remote = remoteValues[key]
                val old = base[key]
                val currentHash = fingerprint(current)
                val remoteHash = fingerprint(remote)
                val modified = config.preferences.getLong("modified/" + key, 0L)
                if (old == null) {
                    if (remotePresent) {
                        if (remote != current) apply[key] = remote
                        next[key] = remoteHash
                    } else if (current != null) {
                        upload[key] = current
                        next[key] = currentHash
                    }
                    continue
                }
                val changedLocal = currentHash != old
                val changedRemote = remotePresent && remoteHash != old
                if (changedRemote && changedLocal) {
                    // No local timestamp means the key was not observed as modified:
                    // do not let the local system clock or a stale value win.
                    val remoteTime = try {
                        Instant.parse(remoteByKey[key]?.updatedAt).toEpochMilli()
                    } catch (_: Exception) { Long.MAX_VALUE }
                    if (modified > 0L && modified > remoteTime) {
                        upload[key] = current
                        next[key] = currentHash
                    } else {
                        apply[key] = remote
                        next[key] = remoteHash
                    }
                } else if (changedRemote) {
                    apply[key] = remote
                    next[key] = remoteHash
                } else if (changedLocal) {
                    upload[key] = current
                    next[key] = currentHash
                }
            }

            stage = "checking plugin installation and deletions"
            var inventoryBase = config.preferences.getString("plugin_baseline", null)
            var pluginsOnServer = remoteValues["x/plugins"]
            var inventoryVersion = remoteByKey["x/plugins"]?.version
            var merge = PluginSync.merge(currentPlugins, pluginsOnServer,
                inventoryBase, SyncRuntime.pluginsLoaded)
            var pluginTarget = PluginSync.encode(merge.target)

            // A manual local removal is only a deletion after the actual plugin
            // loader has completed; prevents empty snapshots during startup.
            // Retry a conflicting manifest with the latest server version.
            for (attempt in 0 until 3) {
                if (!merge.changedRemote) break
                verifyAccount()
                stage = "uploading plugin archives before manifest"
                PluginInventory.uploadFiles(context, backend, pluginTarget)
                stage = "publishing plugin manifest"
                try {
                    backend.upload(mapOf("x/plugins" to pluginTarget), cipher,
                        inventoryVersion?.let { mapOf("x/plugins" to it) } ?: emptyMap())
                    break
                } catch (error: IOException) {
                    if (!isConflict(error) || attempt == 2) throw error
                    val newest = backend.getEntry("x/plugins")
                    pluginsOnServer = if (newest?.deleted == false && newest.cipher != null) {
                        cipher.decrypt("x/plugins", newest.cipher)
                    } else null
                    inventoryVersion = newest?.version
                    merge = PluginSync.merge(currentPlugins, pluginsOnServer,
                        inventoryBase, SyncRuntime.pluginsLoaded)
                    pluginTarget = PluginSync.encode(merge.target)
                }
            }

            stage = "restoring repositories"
            // Repo data must be available before installing extension packages.
            val repositories = apply.remove("d/REPOSITORIES_KEY")
            if (repositories != null && activity != null) {
                verifyAccount()
                applying = true
                try {
                    SyncData.apply(context, mapOf("d/REPOSITORIES_KEY" to repositories))
                } finally {
                    applying = false
                }
            } else if (repositories != null) {
                apply["d/REPOSITORIES_KEY"] = repositories
            }

            stage = "restoring plugin binaries and removals"
            var installation = ""
            if (!PluginSync.matches(PluginSync.decode(currentPlugins), merge.target)) {
                check(config.preferences.edit()
                    .putString("pending_plugins", pluginTarget).commit())
            } else if (config.preferences.contains("pending_plugins")) {
                config.preferences.edit().remove("pending_plugins").commit()
            }
            if (activity != null && SyncRuntime.pluginsLoaded &&
                config.preferences.contains("pending_plugins")) {
                verifyAccount()
                applying = true
                try {
                    installation = installPending(context, activity, backend)
                } finally {
                    applying = false
                }
            }
            // A new baseline may only be recorded for installed, verified items.
            val installedPlugins = PluginInventory.snapshot(context)
            val installed = PluginSync.matches(PluginSync.decode(installedPlugins), merge.target)
            if (installed && !config.preferences.contains("pending_plugins")) {
                check(config.preferences.edit().putString("plugin_baseline", installedPlugins).commit())
            } else if (installation.isEmpty()) {
                installation = "; plugins pending: install/remove requires foreground activity"
            }
            if (!installed && !config.preferences.contains("pending_plugins")) {
                check(config.preferences.edit()
                    .putString("pending_plugins", pluginTarget).commit())
            }
            if (!installed && !installation.contains("plugins pending:")) {
                installation = "; plugins pending: verification mismatch after install/remove"
            }

            // Commit local changes with the server CAS first. If a concurrent
            // write rejects this batch, do not overwrite local state before retry.
            stage = "uploading settings changes"
            verifyAccount()
            backend.upload(upload, cipher, remoteByKey.mapValues { it.value.version })

            stage = "restoring preferences"
            verifyAccount()
            applying = true
            try {
                SyncData.apply(context, apply)
            } finally {
                applying = false
            }

            stage = "persisting verified settings baseline"
            verifyAccount()
            val baseJson = JSONObject()
            for ((key, hash) in next) baseJson.put(key, hash)
            check(config.preferences.edit().putString("base", baseJson.toString()).commit())
            val editor = config.preferences.edit()
            for (key in apply.keys + upload.keys) editor.remove("modified/" + key)
            editor.apply()
            if (apply.keys.any { it.startsWith("d/") || it.startsWith("p/") }) {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
            }
            var result = "Synced: downloaded " + apply.size + ", uploaded " +
                (upload.size + if (merge.changedRemote) 1 else 0)
            result += installation
            if (installation.contains("plugins pending:")) {
                result = result.replaceFirst("Synced:", "Sync incomplete:")
            }
            lastStatus = result
            return result
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: LinkageError) {
            val message = "Sync incompatible during " + stage + ": " + error.javaClass.simpleName
            Log.e("SyncCS", message, error)
            lastStatus = message
            return message
        } catch (error: Exception) {
            val detail = (error.message ?: error.javaClass.simpleName).take(160)
            val message = if (error is IOException && isConflict(error)) {
                "Sync conflict: " + detail
            } else "Sync failed during " + stage + ": " + detail
            Log.e("SyncCS", message, error)
            lastStatus = message
            return message
        }
    }

    private fun isConflict(error: IOException): Boolean {
        val detail = error.message.orEmpty()
        return detail.contains("Sync conflict", true) ||
            detail.contains("Concurrent sync conflict", true) ||
            detail.contains("40001")
    }

    private suspend fun installPending(context: Context, activity: Activity, backend: SyncBackend): String {
        val prefs = SyncConfiguration(context).preferences
        val pending = prefs.getString("pending_plugins", null) ?: return ""
        val failures = PluginInventory.restore(context, activity, backend, pending)
        if (failures.isEmpty()) {
            prefs.edit().remove("pending_plugins").apply()
            return "; plugins restored (restart may be required)"
        }
        return "; plugins pending: " + failures.joinToString(" | ").take(220)
    }
}
