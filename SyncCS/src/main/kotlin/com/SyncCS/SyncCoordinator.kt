package com.SyncCS

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

internal object SyncCoordinator {
    private val lock = Mutex()

    // A coroutine Mutex is not shared by separately loaded SyncCS classloaders.
    // Guard both the undo journal and plugin files across all instances in this process.
    private fun transactionLease(context: Context): Closeable? {
        val handle = RandomAccessFile(File(context.filesDir, "synccs-transaction.lock"), "rw")
        try {
            val exclusive = try {
                handle.channel.tryLock()
            } catch (_: OverlappingFileLockException) {
                null
            }
            if (exclusive == null) {
                handle.close()
                return null
            }
            return Closeable {
                try {
                    exclusive.release()
                } finally {
                    handle.close()
                }
            }
        } catch (error: Exception) {
            handle.close()
            throw error
        }
    }
    @Volatile var applying = false
        private set
    @Volatile var lastStatus: String = "Not connected"
        private set
    @Volatile var lastSuccessfulSyncAt: Long = 0L
        private set
    @Volatile private var lastCompleteLocalAuditAt = 0L
    private const val LOCAL_AUDIT_INTERVAL_MS = 60L * 60L * 1000L
    private fun needsLocalAudit(): Boolean = lastCompleteLocalAuditAt == 0L ||
        android.os.SystemClock.elapsedRealtime() - lastCompleteLocalAuditAt >= LOCAL_AUDIT_INTERVAL_MS

    private fun fingerprint(raw: String?): String =
        if (raw == null) "<deleted>" else SyncBackend.digest(raw)

    private fun isLocalLibraryKey(key: String): Boolean =
        key.startsWith("d/") && (
            "/result_watch_state/" in key ||
            "/result_watch_state_data/" in key ||
            "/result_favorites_state_data/" in key ||
            "/result_subscribed_state_data/" in key
        )

    private fun baseline(context: Context): MutableMap<String, String> {
        val json = JSONObject(SyncConfiguration(context).preferences.getString("base", "{}") ?: "{}")
        return json.keys().asSequence().associateWith { json.getString(it) }.toMutableMap()
    }

    fun markChanged(context: Context, path: String) {
        if (applying) return
        val config = SyncConfiguration(context)
        val remotePath = if (path.startsWith("d/")) {
            ProfileIdentity.remotePath(path, ProfileSync.decode(ProfileSync.snapshot(context)))
        } else path
        val key = "modified/" + remotePath
        config.preferences.edit().putLong(key, System.currentTimeMillis()).apply()
    }

    suspend fun synchronize(context: Context, activity: Activity? = null): String = lock.withLock {
        val lease = transactionLease(context) ?: run {
            SyncTrace.info("transaction-busy", "operation=synchronize; no changes applied")
            return@withLock "Sync deferred: another SyncCS instance is updating local data."
        }
        try {
        val config = SyncConfiguration(context)
        val activityReady = activity != null && !activity.isFinishing && !activity.isDestroyed
        fun pause(reason: String, message: String): String {
            SyncTrace.info("sync-preflight-exit",
                "reason=$reason journalPending=${LocalSyncJournal.pending(context)} " +
                    "activityReady=$activityReady active=${config.active()} " +
                    "localRestorePaused=${config.preferences.getBoolean("github_local_restore_pause", false)} " +
                    "remoteRestoreActive=${config.preferences.getBoolean("github_recovery_in_progress", false)}")
            lastStatus = message
            return message
        }
        SyncTrace.info("sync-preflight",
            "journalPending=${LocalSyncJournal.pending(context)} activityReady=$activityReady " +
                "active=${config.active()} markers=${config.preferences.all.keys.count {
                    it.startsWith("modified/")
                }}")
        if (LocalSyncJournal.pending(context)) {
            applying = true
            try {
                SyncTrace.info("journal-recovery-start",
                    "activityReady=$activityReady")
                LocalSyncJournal.recover(context, activity, LocalOriginalBackup.offlineRecoverySource())
            } catch (recovery: Exception) {
                SyncTrace.info("journal-recovery-blocked",
                    "type=${recovery.javaClass.simpleName} " +
                        "requiresForeground=${recovery.message.orEmpty().contains("foreground", true)}")
                return@withLock pause("journal-recovery-incomplete",
                    "Sync blocked: local rollback incomplete: " +
                        (recovery.message ?: recovery.javaClass.simpleName).take(170))
            } finally {
                applying = false
            }
        }
        if (config.preferences.getBoolean("github_local_restore_pause", false)) {
            return@withLock pause("local-original-restore",
                "Sync paused after local original restore. Disconnect or relink explicitly.")
        }
        if (!config.active()) {
            return@withLock pause("not-authenticated", "Sign in to enable sync")
        }
        if (SyncAuthGuard.needsReauthentication(config)) {
            GithubAuthDiagnostics.snapshot(config, "sync-preflight-rejected")
            SyncTrace.info("github-401-preflight",
                "refreshAvailable=${config.preferences.contains("github_refresh_token")} " +
                    "recoveryEligible=${SyncAuthGuard.mayRecover(config)}")
            if (!GithubOAuthSession.resumeRejected(config))
                return@withLock pause("github-credential-rejected", SyncAuthGuard.MESSAGE)
        }
        if (config.preferences.getBoolean("github_recovery_in_progress", false)) {
            return@withLock pause("github-restore-in-progress",
                "Sync paused: a GitHub backup revision is being restored.")
        }
        if (config.preferences.getBoolean("account_switch_blocked", false)) {
            return@withLock pause("github-account-changed",
                "Sync paused: account changed. Clear local CloudStream data before linking a different account.")
        }
        // A committed baseline/revision persists across restarts; pending local
        // journals were handled above, so clean restarts need only a HEAD check.
        val pendingPlugins = config.preferences.contains("pending_plugins")
        val restoreActivity = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
        if (!needsLocalAudit() && config.preferences.contains("base") &&
            (!pendingPlugins || restoreActivity == null) &&
            config.preferences.all.keys.none { it.startsWith("modified/") }) {
            val known = config.preferences.getString("github_base_revision", null)
            if (known != null && SyncRuntime.refreshLoadedState()) {
                val localRoster = ProfileSync.encode(ProfileSync.decode(ProfileSync.snapshot(context)))
                val savedRoster = runCatching {
                    val encrypted = config.preferences.getString("profile_baseline", null)
                    ProfileSync.encode(ProfileSync.decode(
                        encrypted?.let { String(LocalVault.decrypt(it), Charsets.UTF_8) }))
                }.getOrNull()
                val savedPlugins = config.preferences.getString("plugin_baseline", null)
                val samePlugins = (pendingPlugins && restoreActivity == null) ||
                    (savedPlugins != null &&
                        PluginSync.matches(PluginSync.decode(PluginInventory.snapshot(context)),
                            PluginSync.decode(savedPlugins)))
                if (localRoster == savedRoster && samePlugins) {
                    val headAttempt = runCatching {
                        GithubSyncAdapter(context).remoteHead()
                    }
                    val headError = headAttempt.exceptionOrNull()
                    if (headError is GithubBackupStore.ApiException &&
                        headError.code == 401) {
                        SyncAuthGuard.reject(config, 401)
                        return@withLock pause("github-401-head-check",
                            SyncAuthGuard.MESSAGE)
                    }
                    if (headError != null) {
                        SyncTrace.info("head-check-error",
                            "type=${headError.javaClass.simpleName}")
                    }
                    val remoteRevision = headAttempt.getOrNull()
                    if (remoteRevision != null) {
                        SyncTrace.info("head-check", "known=${known.take(12)} " +
                            "remote=${remoteRevision.take(12)} localDirty=false")
                        if (remoteRevision == known) {
                            lastSuccessfulSyncAt = System.currentTimeMillis()
                            val status = if (pendingPlugins)
                                "Sync incomplete: plugins waiting for foreground; other data up to date"
                            else "Synced: verified current revision; no changes"
                            SyncTrace.info("sync-head-shortcut",
                                "remote=${remoteRevision.take(12)} " +
                                    "pendingPlugins=$pendingPlugins verified=true")
                            lastStatus = status
                            return@withLock status
                        }
                    }
                }
            }
        }
        // The Git commit uses CAS. Re-read and re-merge any conflicting batch.
        for (attempt in 0 until 3) {
            val result = synchronizePass(context, activity)
            if (!result.startsWith("Sync conflict:")) {
                SyncTrace.info("sync-pass-finished",
                    "attempt=${attempt + 1} " +
                        "result=${when {
                            result.startsWith("Synced:") -> "synced"
                            result.startsWith("Sync incomplete:") -> "partial"
                            result.startsWith("Sync blocked:") -> "blocked"
                            else -> "failed"
                        }}")
                if (result.startsWith("Synced:") ||
                    (result.startsWith("Sync incomplete:") &&
                        result.contains("plugins pending:") &&
                        !result.contains("screen refresh dispatch failed"))) {
                    // Verified independent data is current even while a plugin
                    // binary is waiting for a foreground Activity.
                    lastSuccessfulSyncAt = System.currentTimeMillis()
                }
                return@withLock result
            }
            if (attempt < 2) {
                val backoff = 250L * (attempt + 1)
                SyncTrace.info("conflict-retry", "attempt=${attempt + 1} backoffMs=$backoff")
                delay(backoff)
            }
        }
        SyncTrace.info("sync-preflight-exit",
            "reason=conflict-retries-exhausted activityReady=$activityReady")
        lastStatus = "Sync failed: repeated concurrent edits; changes retained for retry"
        lastStatus
        } finally {
            lease.close()
        }
    }

    suspend fun restoreOriginal(context: Context, activity: Activity,
                                entry: LocalOriginalBackup.Entry): String = lock.withLock {
        val lease = transactionLease(context)
            ?: error("Another SyncCS instance is using the recovery journal. Restore not started.")
        try {
        check(!activity.isFinishing && !activity.isDestroyed) {
            "Original backup restore requires a foreground CloudStream activity."
        }
        check(!LocalSyncJournal.pending(context)) {
            "Finish the previous local rollback before restoring an original backup."
        }
        check(SyncRuntime.pluginsLoaded) {
            "Wait until CloudStream has loaded installed plugins before restoring."
        }
        val saved = LocalOriginalBackup.read(context, entry)
        LocalOriginalBackup.preflight(context, saved)
        val roster = ProfileSync.decode(saved.profiles)
        val source = LocalOriginalBackup.storage(saved)
        val rollbackSource = LocalOriginalBackup.offlineRecoverySource()
        // A crash or failed installation must not erase the previous local state.
        LocalSyncJournal.capture(context)
        applying = true
        try {
            ProfileSync.apply(context, roster)
            fun restoreSettings() {
                val existing = SyncData.snapshot(context)
                val changes = linkedMapOf<String, String?>()
                for ((key, value) in saved.values) {
                    if (existing[key] != value) changes[key] = value
                }
                for (key in existing.keys - saved.values.keys) changes[key] = null
                SyncData.apply(context, changes, refreshNativeSnapshots = false)
            }
            restoreSettings()
            val installErrors = PluginInventory.restore(context, activity, source, saved.plugins)
            check(installErrors.isEmpty()) {
                "Original plugin restore failed: " + installErrors.joinToString("; ").take(220)
            }
            // Plugin activation may replay its own settings; restore their exact
            // pre-link values a second time and verify the final state.
            restoreSettings()
            LocalOriginalBackup.restoreNativeAddons(context, saved.nativeAddons)
            val observed = SyncData.snapshot(context)
            check(observed.keys == saved.values.keys &&
                saved.values.all { (key, value) -> observed[key] == value }) {
                "Original preferences were not fully restored; local rollback required."
            }
            check(ProfileSync.encode(ProfileSync.decode(ProfileSync.snapshot(context))) ==
                ProfileSync.encode(roster)) {
                "Restored profile identities do not match the original backup."
            }
            check(PluginSync.matches(PluginSync.decode(PluginInventory.snapshot(context)),
                PluginSync.decode(saved.plugins))) {
                "Restored plugin binaries do not match the original backup."
            }
            val config = SyncConfiguration(context)
            // Even if the app dies after the journal is retired but before
            // disconnect, this durable flag blocks every automatic upload.
            check(config.preferences.edit()
                .putBoolean("github_local_restore_pause", true).commit())
            // Restore is local only. Clear account while the rollback journal
            // still exists, so an interrupted disconnect can also be undone.
            config.clearAccount()
            LocalSyncJournal.finish(context)
            lastStatus = "Original device backup restored locally. GitHub backup unchanged; sync disconnected."
            lastStatus
        } catch (error: Throwable) {
            val recoveryError = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                    LocalSyncJournal.recover(context, activity, rollbackSource)
                }
                null
            } catch (rollback: Exception) {
                rollback.message ?: rollback.javaClass.simpleName
            }
            if (recoveryError != null) {
                throw IOException("Local original restore failed; rollback incomplete: " +
                    recoveryError, error)
            }
            throw error
        } finally {
            applying = false
        }
        } finally {
            lease.close()
        }
    }

    private suspend fun synchronizePass(context: Context, activity: Activity?): String {
        val config = SyncConfiguration(context)
        val backend: SyncStorage = GithubSyncAdapter(context)
        val traceId = SyncTrace.start()
        val elapsedStart = android.os.SystemClock.elapsedRealtime()
        val dirtyBarrier = System.currentTimeMillis()
        val lastAcknowledgedRevision = config.preferences.getString("github_base_revision", null)
        SyncTrace.memory("sync-start")
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
            // GitHub sign-in can finish before CloudStream's plugin loader.
            // Never capture or publish a partial inventory while it is loading.
            stage = "waiting for CloudStream plugins"
            for (attempt in 0 until 60) {
                if (SyncRuntime.refreshLoadedState()) break
                delay(500L)
                verifyAccount()
            }
            check(SyncRuntime.refreshLoadedState()) {
                "CloudStream plugins have not finished loading. No backup was published; retry Sync now after loading completes."
            }
            stage = "loading cloud state"
            SyncTrace.memory("before-github-pull")
            // Older commits without a sealed change journal fall back to full
            // authenticated recovery; Git revision is the durable delta cursor.
            val previousCursor = if (config.preferences.contains("remote_cursor"))
                config.preferences.getLong("remote_cursor", 0L) else null
            val pull = backend.pull(previousCursor)
            val receivedRevision = (backend as GithubSyncAdapter).verifiedRevision().first
            SyncTrace.info("receive-revision",
                "previous=${lastAcknowledgedRevision?.take(12) ?: "none"} " +
                    "incoming=${receivedRevision.take(12)} " +
                    "changed=${lastAcknowledgedRevision != receivedRevision}")
            SyncTrace.info("pull", "full=${pull.full} entries=${pull.entries.size}")
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
                        it.startsWith("d/") || it.startsWith("u/") ||
                        it.startsWith("s/") || it.startsWith("p/")
                }
            )
            SyncTrace.info("first-link-policy",
                "serverCanonical=$firstRemoteRestore " +
                    "existingBaseline=${config.preferences.contains("base")} " +
                    "freshBackup=${(backend as GithubSyncAdapter).createdFreshBackup}")
            SyncTrace.keys("remote-state", remoteValues.keys)
            SyncTrace.profiles("remote-profiles", remoteValues["x/profiles"])
            SyncTrace.plugins("remote-plugins", remoteValues["x/plugins"])
            val remoteByKey = pull.entries.associateBy { it.key }
            val versions = mutableMapOf<String, Long>()
            if (!pull.full) {
                val old = JSONObject(config.preferences.getString("remote_versions", "{}") ?: "{}")
                for (key in old.keys()) versions[key] = old.getLong(key)
            }
            for (entry in pull.entries) versions[entry.key] = entry.version

            // A synced device may propagate actual deletions automatically.
            // An unlinked device has no baseline and never emits deletions.
            val preflightBase = baseline(context)
            val preflightMarked = config.preferences.all.keys.asSequence()
                .filter { it.startsWith("modified/") }
                .map { it.removePrefix("modified/") }.toSet()
            val preflightProfiles = ProfileSync.decode(ProfileSync.snapshot(context))
            val preflightNative = preflightMarked.mapNotNull {
                ProfileIdentity.nativePath(it, preflightProfiles)
            }
            val preflightLocal = ProfileIdentity.toRemote(
                SyncData.snapshotSelected(context, preflightNative), preflightProfiles)
            val preferenceRemovals = preflightBase.keys.filter { key ->
                key in preflightMarked && key !in preflightLocal
            }.toSet()
            SyncTrace.info("deletion-preflight-scan",
                "marked=${preflightMarked.size} selected=${preflightLocal.size} " +
                    "baseline=${preflightBase.size}")
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
            val verifiedRemovals = preferenceRemovals +
                profileRemovals.map { "x/profiles/$it" } +
                pluginRemovals.map { "x/plugins/$it" }
            SyncTrace.keys("deletion-verified", verifiedRemovals)
            SyncTrace.info("deletion-proof",
                "preferences=${preferenceRemovals.size} profiles=${profileRemovals.size} " +
                    "plugins=${pluginRemovals.size} baseline=${config.preferences.contains("base")}")
            // The final Git commit checks membership against this verified set.
            (backend as GithubSyncAdapter).allowVerifiedDeletions(verifiedRemovals)

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
                profileLocal, profileServer, previousProfiles)
            val targetProfiles = ProfileSync.encode(profileMerge.target)
            SyncTrace.profiles("target-profiles", targetProfiles)
            SyncTrace.info("profile-merge", "upload=${profileMerge.changedRemote}")
            val nativeBefore = ProfileSync.decode(profileLocal)
            var retiredLibraryData = false
            val obsoleteSlots = nativeBefore.items.keys.filter { slot ->
                val old = nativeBefore.items.getValue(slot)
                val adopted = profileMerge.target.items[slot]
                adopted == null || ProfileIdentity.uuid(old) != ProfileIdentity.uuid(adopted)
            }.toSet()
            if (obsoleteSlots.isNotEmpty()) {
                // An old B profile can leave numeric-slot watch keys behind even
                // after the roster is replaced. Remove tracked native data
                // BEFORE adopting the server's UUID, under the undo journal.
                val obsolete = SyncData.snapshot(context).keys.filter { path ->
                    val slot = if (path.startsWith("d/")) {
                        path.removePrefix("d/").substringBefore('/').toIntOrNull()
                    } else null
                    slot in obsoleteSlots
                }
                if (obsolete.isNotEmpty()) {
                    retiredLibraryData = obsolete.any(::isLocalLibraryKey)
                    ensureUndo()
                    applying = true
                    try {
                        SyncData.apply(context, obsolete.associateWith { null },
                            refreshNativeSnapshots = false)
                    } finally {
                        applying = false
                    }
                }
            }
            // If another device replaced profile #1 (same number, new UUID),
            // all records on this stale device's old #1 are untrusted.
            // They must be restored from the new server UUID, never uploaded.
            val replacedProfileUuids = ProfileIdentity.replacedByServer(
                ProfileSync.decode(profileLocal), profileMerge.target)
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
            SyncTrace.memory("before-local-snapshot")
            val snapshotStarted = android.os.SystemClock.elapsedRealtime()
            val marked = config.preferences.all.keys.asSequence()
                .filter { it.startsWith("modified/") }
                .map { it.removePrefix("modified/") }.toSet()
            val profileRosterChanged = ProfileSync.encode(ProfileSync.decode(profileLocal)) !=
                targetProfiles
            val headerNeedsAudit = (remoteValues.keys + marked).any {
                it.startsWith("d/download_header_cache/")
            }
            val completeLocal = pull.full || firstRemoteRestore ||
                profileRosterChanged || headerNeedsAudit || needsLocalAudit()
            val local = if (completeLocal) {
                ProfileIdentity.toRemote(SyncData.snapshot(context), profileMerge.target)
            } else {
                val selectedRemote = (remoteValues.keys + marked)
                    .mapNotNull { ProfileIdentity.nativePath(it, profileMerge.target) }
                ProfileIdentity.toRemote(
                    SyncData.snapshotSelected(context, selectedRemote), profileMerge.target)
            }
            SyncTrace.info("local-snapshot-time",
                "mode=${if (completeLocal) "audit" else "changed-only"} " +
                    "elapsedMs=${android.os.SystemClock.elapsedRealtime() - snapshotStarted} " +
                    "keys=${local.size} remoteKeys=${remoteValues.size} dirty=${marked.size}")
            if (completeLocal) SyncTrace.keys("local-audit", local.keys)
            else SyncTrace.keys("local-changed", local.keys)
            val currentPlugins = PluginInventory.snapshot(context)
            val base = baseline(context)
            stage = "comparing changed settings"
            val savedLists = config.preferences.getString("list_baselines", null)
            val priorLists = if (savedLists == null) JSONObject() else JSONObject(
                String(LocalVault.decrypt(savedLists), Charsets.UTF_8)
            )
            val planStarted = android.os.SystemClock.elapsedRealtime()
            val planned = SyncPreferencePlanner.reconcile(
                remoteValues, local, base, profileMerge.target, activeProfiles,
                replacedProfileUuids, marked, priorLists, firstRemoteRestore,
                pull.full, completeLocal)
            SyncData.excludeUnreferencedHeaderRestores(local, planned.apply, planned.upload)
            SyncTrace.info("merge-duration",
                "elapsedMs=${android.os.SystemClock.elapsedRealtime() - planStarted} " +
                    "remote=${remoteValues.size} local=${local.size} " +
                    "marked=${marked.size}")
            val apply = planned.apply
            val upload = planned.upload
            val next = planned.next
            val conflicts = planned.conflicts

            SyncTrace.keys("planned-upload", upload.keys)
            SyncTrace.keys("planned-download", apply.keys)
            SyncTrace.actions("upload", upload)
            SyncTrace.actions("download", apply)
            SyncTrace.info("conflicts", "count=${conflicts.size}")
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
                inventoryBase, SyncRuntime.pluginsLoaded)
            var pluginTarget = PluginSync.encode(merge.target)
            SyncTrace.plugins("local-plugins", currentPlugins)
            SyncTrace.plugins("target-plugins", pluginTarget)
            SyncTrace.pluginDelta("plugin-install-remove-plan", currentPlugins, pluginTarget)

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
                        inventoryBase, SyncRuntime.pluginsLoaded)
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
            val pluginActivity = activity?.takeIf { !it.isFinishing && !it.isDestroyed }
            val pluginReady = SyncRuntime.refreshLoadedState()
            SyncTrace.info("plugin-activity",
                "present=${pluginActivity != null} pluginsLoaded=$pluginReady " +
                    "pending=${config.preferences.contains("pending_plugins")}")
            if (!PluginSync.matches(PluginSync.decode(currentPlugins), merge.target)) {
                // Missing foreground access cannot block independent library and
                // playback changes. Keep plugin recovery pending for a usable Activity.
                check(config.preferences.edit()
                    .putString("pending_plugins", pluginTarget).commit())
            } else if (config.preferences.contains("pending_plugins")) {
                check(config.preferences.edit().remove("pending_plugins").commit())
            }
            val pluginInstallAllowed = SyncPluginRestorePolicy.canRestoreNow(
                pluginActivity != null, pluginReady)
            if (pluginInstallAllowed &&
                config.preferences.contains("pending_plugins")) {
                verifyAccount()
                applying = true
                try {
                    ensureUndo()
                    installation = installPending(context, pluginActivity!!, backend)
                    if (installation.contains("plugins pending:")) {
                        SyncTrace.info("plugin-deferred",
                            "reason=restore-incomplete independent-data-continues")
                    }
                } finally {
                    applying = false
                }
            } else if (config.preferences.contains("pending_plugins")) {
                installation = "; plugins pending: foreground activity required"
                SyncTrace.info("plugin-deferred",
                    "reason=${if (pluginActivity == null) "no-activity" else "loader-not-ready"} " +
                        "independent-data-continues")
            }
            // A new baseline may only be recorded for installed, verified items.
            val installedPlugins = PluginInventory.snapshot(context)
            val installed = PluginSync.matches(PluginSync.decode(installedPlugins), merge.target)
            SyncTrace.info("plugin-verification",
                "originRevision=${receivedRevision.take(12)} matched=$installed " +
                    "pending=${config.preferences.contains("pending_plugins")} " +
                    "installed=${PluginSync.decode(installedPlugins).items.size} " +
                    "expected=${PluginSync.decode(pluginTarget).items.size}")
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
            val nativeApply = ProfileIdentity.toNative(apply, profileMerge.target)
            applying = true
            try {
                SyncData.apply(context, nativeApply)
            } finally {
                applying = false
            }
            if (nativeApply.isNotEmpty()) {
                check(SyncTrace.applied("local-apply-verified", nativeApply,
                    SyncData.snapshotSelected(context, nativeApply.keys))) {
                    "Local preference apply mismatch; transaction rolled back."
                }
                SyncTrace.info("cross-device-receipt",
                    "phase=local-verified originRevision=${receivedRevision.take(12)} " +
                        "keys=${nativeApply.size} remoteKeyNames=${apply.size}")
                // The server and local UUID mapping use the same stable key hash.
                // These entries can be paired with a remote uploader's log.
                SyncTrace.actions("received-verified", apply)
            }

            // A single Git commit publishes profile, plugin inventory and
            // preferences together, only after local apply has succeeded.
            SyncTrace.memory("before-github-commit")
            stage = "publishing complete GitHub snapshot"
            backend.flush(cipher)
            SyncTrace.info("publish-complete")
            SyncTrace.memory("after-github-commit")
            stage = "persisting verified settings baseline"
            verifyAccount()
            val baseJson = JSONObject()
            for ((key, hash) in next) baseJson.put(key, hash)
            val revisionJson = JSONObject()
            versions.forEach { (key, version) -> revisionJson.put(key, version) }
            val listBaselineJson = if (completeLocal) JSONObject()
                else JSONObject(priorLists.toString())
            val keysToRecord = if (completeLocal) local.keys + apply.keys
                else (marked + remoteValues.keys + apply.keys + upload.keys)
            for (key in keysToRecord) {
                val raw = if (apply.containsKey(key)) apply[key] else local[key]
                if (raw != null && SharedListSync.isCollection(key, raw)) {
                    listBaselineJson.put(key, raw)
                } else listBaselineJson.remove(key)
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
            SyncTrace.info("receive-revision-ack",
                "incoming=${receivedRevision.take(12)} committed=${githubRevision.take(12)} " +
                    "applied=${nativeApply.size} uploaded=${upload.size}")
            SyncTrace.info("cross-device-receipt",
                "phase=cloud-committed originRevision=${receivedRevision.take(12)} " +
                    "publishedRevision=${githubRevision.take(12)} " +
                    "downloads=${apply.size} uploads=${upload.size} " +
                    "pluginTargetCount=${PluginSync.decode(pluginTarget).items.size}")
            SyncTrace.actions("published-verified", upload)
            if ((backend as GithubSyncAdapter).createdFreshBackup) {
                check(config.preferences.edit()
                    .remove("github_initial_backup_confirmed").commit())
            }
            SyncTrace.info("deletion-commit", "accepted=${verifiedRemovals.size} revision=${githubRevision.take(12)}")
            if (undoStarted) {
                LocalSyncJournal.finish(context)
                undoStarted = false
            }
            if (completeLocal) {
                lastCompleteLocalAuditAt = android.os.SystemClock.elapsedRealtime()
                SyncTrace.info("local-audit-verified",
                    "keys=${local.size} nextDueMs=$LOCAL_AUDIT_INTERVAL_MS")
            }
            val cleanStats = runCatching {
                SyncDirtyMarkerCleaner.prune(
                    context, next, profileMerge.target, dirtyBarrier,
                    config.preferences.contains("pending_plugins"))
            }.onFailure {
                SyncTrace.info("dirty-cleanup-failure",
                    "type=${it.javaClass.simpleName}; cloud commit remains verified")
            }.getOrNull()
            if (cleanStats != null) {
                SyncTrace.info("dirty-cleanup-result",
                    "removed=${cleanStats.removed} pending=${cleanStats.pending}")
            }
            var uiRefreshFailed = false
            val libraryChanged = retiredLibraryData || nativeApply.keys.any(::isLocalLibraryKey)
            if (retiredLibraryData || nativeApply.keys.any { it.startsWith("d/") || it.startsWith("p/") }) {
                SyncTrace.info("ui-refresh-request",
                    "event=bookmarksUpdated incoming=${receivedRevision.take(12)} " +
                        "changed=${nativeApply.size} " +
                        "profileKeys=${apply.keys.count { it.startsWith("u/") }}")
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        MainActivity.bookmarksUpdatedEvent.invoke(true)
                    }
                    SyncTrace.info("ui-refresh-dispatched",
                        "event=bookmarksUpdated incoming=${receivedRevision.take(12)} " +
                            "verifiedNativeKeys=${nativeApply.size}")
                } catch (error: Exception) {
                    uiRefreshFailed = true
                    SyncTrace.info("ui-refresh-failed",
                        "type=${error.javaClass.simpleName} incoming=${receivedRevision.take(12)}")
                }
            } else {
                SyncTrace.info("ui-refresh-not-needed",
                    "incoming=${receivedRevision.take(12)} nativeChanges=0")
            }
            if (libraryChanged) {
                try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        MainActivity.reloadLibraryEvent.invoke(true)
                    }
                    SyncTrace.info("ui-refresh-dispatched",
                        "event=reloadLibrary incoming=${receivedRevision.take(12)}")
                } catch (error: Exception) {
                    uiRefreshFailed = true
                    SyncTrace.info("ui-refresh-failed",
                        "event=reloadLibrary type=${error.javaClass.simpleName} " +
                            "incoming=${receivedRevision.take(12)}")
                }
            }
            var result = "Synced: downloaded " + apply.size + ", uploaded " +
                (upload.size + (if (merge.changedRemote) 1 else 0) +
                    (if (profileMerge.changedRemote) 1 else 0))
            result += installation
             if (installation.contains("plugins pending:") || uiRefreshFailed) {
                result = result.replaceFirst("Synced:", "Sync incomplete:")
                if (uiRefreshFailed) result += "; screen refresh dispatch failed"
            }
            lastStatus = result
            SyncTrace.info("success", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - elapsedStart} " +
                "downloaded=${apply.size} uploaded=${upload.size} " +
                "deleted=${upload.values.count { it == null }} " +
                "pluginsChanged=${merge.changedRemote} profilesChanged=${profileMerge.changedRemote}")
            return result
        } catch (cancel: CancellationException) {
            SyncTrace.info("cancel", "step=$stage")
            kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
                undoIfNeeded()
            }
            throw cancel
        } catch (error: LinkageError) {
            val rollbackError = undoIfNeeded()
            SyncTrace.info("failure", "step=$stage type=LinkageError rollback=${rollbackError == null}")
            val message = "Sync incompatible during " + stage + ": " +
                error.javaClass.simpleName + (rollbackError?.let { "; " + it } ?: "")
            Log.e("SyncCS", message, error)
            lastStatus = message
            return message
        } catch (error: Exception) {
            val rollbackError = undoIfNeeded()
            SyncTrace.info("failure", "step=$stage type=${error.javaClass.simpleName} " +
                "rollback=${rollbackError == null}")
            if (error is GithubBackupStore.ApiException && error.code == 401) {
                SyncAuthGuard.reject(config, 401)
                lastStatus = SyncAuthGuard.MESSAGE +
                    (rollbackError?.let { "; $it" } ?: "")
                SyncTrace.info("sync-auth-paused",
                    "phase=$stage reason=github-http-401 " +
                        "rollbackSucceeded=${rollbackError == null}")
                return lastStatus
            }
            val detail = (error.message ?: error.javaClass.simpleName).take(160)
            val message = if (error is IOException && isConflict(error)) {
                "Sync conflict: " + detail
            } else "Sync failed during " + stage + ": " + detail +
                (rollbackError?.let { "; " + it } ?: "")
            Log.e("SyncCS", message, error)
            lastStatus = message
            return message
        } finally {
            SyncTrace.finish(traceId)
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
