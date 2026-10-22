package com.SyncCS

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
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
        if (LocalSyncJournal.pending(context)) {
            val activityReady = activity != null && !activity.isFinishing && !activity.isDestroyed
            if (!activityReady) {
                lastStatus = "Sync paused: incomplete local restore requires a foreground activity."
                return@withLock lastStatus
            }
            applying = true
            try {
                LocalSyncJournal.recover(context, activity, GithubSyncAdapter(context))
            } catch (recovery: Exception) {
                lastStatus = "Sync blocked: local rollback incomplete: " +
                    (recovery.message ?: recovery.javaClass.simpleName).take(170)
                return@withLock lastStatus
            } finally {
                applying = false
            }
        }
        if (config.preferences.getBoolean("github_recovery_in_progress", false)) {
            lastStatus = "Sync paused: a GitHub backup revision is being restored."
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
        val backend: SyncStorage = GithubSyncAdapter(context)
        var stage = "loading cloud state"
        var undoStarted = false
        fun ensureUndo() {
            if (!undoStarted) {
                LocalSyncJournal.capture(context)
                undoStarted = true
            }
        }
        suspend fun undoIfNeeded(): String? {
            if (!undoStarted) return null
            applying = true
            return try {
                LocalSyncJournal.recover(context, activity, backend)
                null
            } catch (failure: Exception) {
                "Local rollback incomplete; sync blocked until recovery: " +
                    (failure.message ?: failure.javaClass.simpleName).take(170)
            } finally {
                applying = false
            }
        }
        try {
            val startedUser = backend.userId()
            fun verifyAccount() {
                check(config.active() &&
                    config.preferences.getString("github_account_id", null) == startedUser &&
                    !config.preferences.getBoolean("account_switch_blocked", false)) {
                    "Sync account changed during operation"
                }
            }

            val cipher = backend.cipher()
            // A full snapshot is required only for the first run, or when the
            // server has not yet installed the v340 change journal migration.
            val previousCursor = if (config.preferences.contains("remote_cursor"))
                config.preferences.getLong("remote_cursor", 0L) else null
            val pull = backend.pull(previousCursor)
            stage = "decrypting remote changes"
            val remoteValues = pull.entries.associate { item ->
                item.key to if (item.deleted) null else cipher.decrypt(
                    item.key, item.cipher ?: error("Missing encrypted value for " + item.key)
                )
            }
            // On first link, an existing cloud account takes precedence over
            // unrelated settings and watch history already stored on this device.
            val firstRemoteRestore = SyncSafety.firstRemoteRestore(
                config.preferences.contains("base"),
                (backend as GithubSyncAdapter).createdFreshBackup,
                config.preferences.getBoolean("github_initial_backup_confirmed", false),
                remoteValues.keys.any {
                    it == "x/profiles" || it == "x/plugins" ||
                        it.startsWith("d/") || it.startsWith("s/") || it.startsWith("p/")
                }
            )
            val remoteByKey = pull.entries.associateBy { it.key }
            val versions = mutableMapOf<String, Long>()
            if (!pull.full) {
                val old = JSONObject(config.preferences.getString("remote_versions", "{}") ?: "{}")
                for (key in old.keys()) versions[key] = old.getLong(key)
            }
            for (entry in pull.entries) versions[entry.key] = entry.version

            // Gather *all* destructive changes before touching local profiles or
            // publishing a Git commit. A preference listener only suggests a
            // candidate; the human must approve the exact removal set.
            val preflightBase = baseline(context)
            val preflightLocal = SyncData.snapshot(context)
            val preferenceRemovals = preflightBase.keys.filter { key ->
                key !in preflightLocal &&
                    config.preferences.contains("modified/" + key)
            }.toSet()
            val profileBaselineValue = config.preferences.getString("profile_baseline", null)
            val baseProfileState = ProfileSync.decode(profileBaselineValue?.let {
                String(LocalVault.decrypt(it), Charsets.UTF_8)
            })
            val localProfileState = ProfileSync.decode(ProfileSync.snapshot(context))
            val profileRemovals = (baseProfileState.items.keys -
                localProfileState.items.keys).filter { it > 0 }.toSet()
            val previousInventory = PluginSync.decode(
                config.preferences.getString("plugin_baseline", null))
            val localInventory = PluginSync.decode(PluginInventory.snapshot(context))
            val pluginRemovals = if (SyncRuntime.pluginsLoaded) {
                previousInventory.items.keys - localInventory.items.keys
            } else emptySet()
            val destructiveProposals = preferenceRemovals +
                profileRemovals.map { "x/profiles/$it" } +
                pluginRemovals.map { "x/plugins/$it" }
            SyncDeletionGate.requireApproval(config, destructiveProposals)
            val approvedProfiles = profileRemovals.filter {
                "x/profiles/$it" in destructiveProposals
            }.toSet()
            val approvedPlugins = pluginRemovals.filter {
                "x/plugins/$it" in destructiveProposals
            }.toSet()

            stage = "reconciling CloudStream profiles"
            fun protectedProfileValue(key: String): String? {
                val stored = config.preferences.getString(key, null) ?: return null
                // Accept a manifest briefly stored by the first v340 build.
                if (stored.startsWith("{")) return stored
                return String(LocalVault.decrypt(stored), Charsets.UTF_8)
            }
            val previousProfiles = protectedProfileValue("profile_baseline")
            val profileServer = if (pull.full || remoteValues.containsKey("x/profiles")) {
                remoteValues["x/profiles"]
            } else protectedProfileValue("profile_remote_snapshot")
            val profileLocal = ProfileSync.snapshot(context)
            val profileMerge = ProfileSync.merge(
                profileLocal, profileServer, previousProfiles, approvedProfiles)
            val targetProfiles = ProfileSync.encode(profileMerge.target)
            if (profileMerge.changedRemote) {
                verifyAccount()
                backend.upload(mapOf("x/profiles" to targetProfiles), cipher, versions)
                versions["x/profiles"] = (versions["x/profiles"] ?: 0L) + 1L
            }
            verifyAccount()
            if (ProfileSync.encode(ProfileSync.decode(profileLocal)) != targetProfiles) {
                ensureUndo()
            }
            applying = true
            try {
                ProfileSync.apply(context, profileMerge.target)
            } finally {
                applying = false
            }
            // Profile metadata is an independently committed unit. A later failure
            // in extension restoration cannot undo an already applied profile.
            val protectedProfiles = LocalVault.encrypt(
                targetProfiles.toByteArray(Charsets.UTF_8))
            check(config.preferences.edit()
                .putString("profile_baseline", protectedProfiles)
                .putString("profile_remote_snapshot", protectedProfiles).commit())
            val activeProfiles = profileMerge.target.items.keys

            stage = "reading local preferences"
            val local = SyncData.snapshot(context)
            val currentPlugins = PluginInventory.snapshot(context)
            val base = baseline(context)
            stage = "comparing changed settings"
            val apply = mutableMapOf<String, String?>()
            val upload = mutableMapOf<String, String?>()
            val next = base.toMutableMap()
            next.remove("x/plugins")
            next.remove("x/profiles")
            val all = (remoteValues.keys + local.keys + base.keys)
                .filter { it != "x/plugins" && it != "x/profiles" }.toSortedSet()
            val conflicts = mutableMapOf<String, String>()
            val savedLists = config.preferences.getString("list_baselines", null)
            val priorLists = if (savedLists == null) JSONObject() else JSONObject(
                String(LocalVault.decrypt(savedLists), Charsets.UTF_8)
            )

            for (key in all) {
                val current = local[key]
                val remotePresent = remoteValues.containsKey(key)
                val remote = remoteValues[key]
                val old = base[key]
                val currentHash = fingerprint(current)
                val remoteHash = fingerprint(remote)

                // A missing profile is inactive, not a request to delete its
                // stored history. Keep both server data and the last baseline
                // intact until that profile index is active again.
                val profileId = if (key.startsWith("d/")) {
                    key.removePrefix("d/").substringBefore('/').toIntOrNull()
                } else null
                if (profileId != null && profileId !in activeProfiles) {
                    continue
                }

                if (old == null) {
                    if (remotePresent) {
                        if (remote != current) apply[key] = remote
                        next[key] = remoteHash
                    } else if (current != null) {
                        if (firstRemoteRestore) {
                            apply[key] = null
                        } else {
                            upload[key] = current
                            next[key] = currentHash
                        }
                    }
                    continue
                }
                // A missing key is NOT evidence of user deletion. Plugin startup,
                // a failed preferences enumeration, or a filtered snapshot can omit it.
                val explicitlyChanged = config.preferences.contains("modified/" + key)
                if (current == null && !explicitlyChanged &&
                    remotePresent && remote != null) {
                    // A temporarily missing local preference must be repaired
                    // from the complete remote snapshot, never uploaded as delete.
                    apply[key] = remote
                    next[key] = remoteHash
                    continue
                }
                val changedLocal = SyncSafety.localHasRealChange(
                    old, currentHash, current != null, explicitlyChanged
                )
                val changedRemote = if (pull.full) remoteHash != old
                    else remotePresent && remoteHash != old
                when {
                    changedLocal && changedRemote && currentHash == remoteHash -> {
                        next[key] = remoteHash
                    }
                    changedLocal && changedRemote -> {
                        // Collection keys are merged by independent membership
                        // additions/removals, using the last applied raw ancestry.
                        val collectionMerge = SharedListSync.merge(
                            key, priorLists.optString(key).takeIf {
                                priorLists.has(key)
                            }, current, remote
                        )
                        if (collectionMerge != null && collectionMerge != remote) {
                            upload[key] = collectionMerge
                            apply[key] = collectionMerge
                            next[key] = fingerprint(collectionMerge)
                        } else {
                            // Single-valued keys: the committed server version
                            // wins; retain the losing local value for recovery.
                            if (collectionMerge == null && current != null) conflicts[key] = current
                            apply[key] = remote
                            next[key] = remoteHash
                        }
                    }
                    changedRemote -> {
                        apply[key] = remote
                        next[key] = remoteHash
                    }
                    changedLocal -> {
                        upload[key] = current
                        next[key] = currentHash
                    }
                }
            }

            if (conflicts.isNotEmpty()) {
                val saved = config.preferences.getString("pending_conflicts", null)
                val retained = if (saved == null) JSONObject() else JSONObject(
                    String(LocalVault.decrypt(saved), Charsets.UTF_8)
                )
                for ((key, raw) in conflicts) retained.put(key, raw)
                check(config.preferences.edit().putString("pending_conflicts",
                    LocalVault.encrypt(retained.toString().toByteArray(Charsets.UTF_8))).commit())
            }

            stage = "checking plugin installation and deletions"
            val inventoryBase = config.preferences.getString("plugin_baseline", null)
            var pluginsOnServer = if (pull.full || remoteValues.containsKey("x/plugins")) {
                remoteValues["x/plugins"]
            } else config.preferences.getString("plugin_remote_snapshot", null)
            var inventoryVersion = versions["x/plugins"]
            var merge = PluginSync.merge(currentPlugins, pluginsOnServer,
                inventoryBase, SyncRuntime.pluginsLoaded, approvedPlugins)
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
                    inventoryVersion = (inventoryVersion ?: 0L) + 1L
                    versions["x/plugins"] = inventoryVersion ?: 1L
                    pluginsOnServer = pluginTarget
                    break
                } catch (error: IOException) {
                    if (!isConflict(error) || attempt == 2) throw error
                    val newest = backend.getEntry("x/plugins")
                    pluginsOnServer = if (newest?.deleted == false && newest.cipher != null) {
                        cipher.decrypt("x/plugins", newest.cipher)
                    } else null
                    inventoryVersion = newest?.version
                    if (inventoryVersion != null) versions["x/plugins"] = inventoryVersion
                    merge = PluginSync.merge(currentPlugins, pluginsOnServer,
                        inventoryBase, SyncRuntime.pluginsLoaded, approvedPlugins)
                    pluginTarget = PluginSync.encode(merge.target)
                }
            }

            // Re-fetching a changed manifest is unnecessary on subsequent
            // incremental polls; cache only its current encrypted-account value.
            val serverPluginManifest = if (merge.changedRemote) pluginTarget else pluginsOnServer

            stage = "restoring repositories"
            // Repo data must be available before installing extension packages.
            val repositories = apply.remove("d/REPOSITORIES_KEY")
            if (repositories != null && activity != null) {
                verifyAccount()
                ensureUndo()
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
                check(activity != null && SyncRuntime.pluginsLoaded) {
                    "Plugin restore must complete in the foreground before settings are applied."
                }
                ensureUndo()
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
                    ensureUndo()
                    installation = installPending(context, activity, backend)
                    check(!installation.contains("plugins pending:")) {
                        "Plugin restore was incomplete."
                    }
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
            backend.upload(upload, cipher, versions)
            for (key in upload.keys) versions[key] = (versions[key] ?: 0L) + 1L

            stage = "restoring preferences"
            verifyAccount()
            if (apply.isNotEmpty()) ensureUndo()
            applying = true
            try {
                SyncData.apply(context, apply)
            } finally {
                applying = false
            }

            // A single Git commit publishes profile, plugin inventory and
            // preferences together, only after local apply has succeeded.
            stage = "publishing complete GitHub snapshot"
            backend.flush(cipher)
            stage = "persisting verified settings baseline"
            verifyAccount()
            val baseJson = JSONObject()
            for ((key, hash) in next) baseJson.put(key, hash)
            val revisionJson = JSONObject()
            versions.forEach { (key, version) -> revisionJson.put(key, version) }
            val listBaselineJson = JSONObject()
            for (key in local.keys + apply.keys) {
                val raw = if (apply.containsKey(key)) apply[key] else local[key]
                if (raw != null && SharedListSync.isCollection(key, raw)) {
                    listBaselineJson.put(key, raw)
                }
            }
            val persisted = config.preferences.edit()
                .putString("base", baseJson.toString())
                .putString("remote_versions", revisionJson.toString())
                .putString("list_baselines", LocalVault.encrypt(
                    listBaselineJson.toString().toByteArray(Charsets.UTF_8)))
            if (pull.cursor != null) persisted.putLong("remote_cursor", pull.cursor)
            else persisted.remove("remote_cursor")
            val (githubRevision, githubGeneration) =
                (backend as GithubSyncAdapter).verifiedRevision()
            persisted.putString("github_base_revision", githubRevision)
                .putInt("github_generation", githubGeneration)
            if (serverPluginManifest != null) {
                persisted.putString("plugin_remote_snapshot", serverPluginManifest)
            } else {
                persisted.remove("plugin_remote_snapshot")
            }
            check(persisted.commit())
            if ((backend as GithubSyncAdapter).createdFreshBackup) {
                check(config.preferences.edit()
                    .remove("github_initial_backup_confirmed").commit())
            }
            SyncDeletionGate.consumed(config)
            if (undoStarted) {
                LocalSyncJournal.finish(context)
                undoStarted = false
            }
            val editor = config.preferences.edit()
            for (key in apply.keys + upload.keys) editor.remove("modified/" + key)
            editor.apply()
            if (apply.keys.any { it.startsWith("d/") || it.startsWith("p/") }) {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
            }
            var result = "Synced: downloaded " + apply.size + ", uploaded " +
                (upload.size + (if (merge.changedRemote) 1 else 0) +
                    (if (profileMerge.changedRemote) 1 else 0))
            result += installation
            if (installation.contains("plugins pending:")) {
                result = result.replaceFirst("Synced:", "Sync incomplete:")
            }
            lastStatus = result
            return result
        } catch (cancel: CancellationException) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                undoIfNeeded()
            }
            throw cancel
        } catch (error: LinkageError) {
            val rollbackError = undoIfNeeded()
            val message = "Sync incompatible during " + stage + ": " +
                error.javaClass.simpleName + (rollbackError?.let { "; " + it } ?: "")
            Log.e("SyncCS", message, error)
            lastStatus = message
            return message
        } catch (error: Exception) {
            val rollbackError = undoIfNeeded()
            val detail = (error.message ?: error.javaClass.simpleName).take(160)
            val message = if (error is IOException && isConflict(error)) {
                "Sync conflict: " + detail
            } else "Sync failed during " + stage + ": " + detail +
                (rollbackError?.let { "; " + it } ?: "")
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

    private suspend fun installPending(context: Context, activity: Activity, backend: SyncStorage): String {
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
