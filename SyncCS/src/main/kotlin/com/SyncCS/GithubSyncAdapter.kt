package com.SyncCS

import android.content.Context
import android.util.Base64
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.utils.DataStore
import org.json.JSONObject
import java.io.File
import java.io.IOException

/** The coordinator owns reconciliation; the backend owns only persisted data. */
internal interface SyncStorage {
    fun userId(): String
    fun cipher(): SyncCipher
    fun pull(previousCursor: Long?): SyncBackend.PullResult
    fun upload(changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>)
    fun flush(cipher: SyncCipher)
    fun getEntry(key: String): RemoteEntry?
    fun uploadFile(hash: String, bytes: ByteArray)
    fun downloadFile(hash: String): ByteArray
}

/**
 * GitHub adapter; does not migrate contaminated Supabase data.
 * A new repository is initialized with a single complete commit, not
 * with separately published profile, plugin, and preference manifests.
 */
internal class GithubSyncAdapter(private val context: Context) : SyncStorage {
    private val config = SyncConfiguration(context)
    private val preferences = config.preferences
    private val token: String = preferences.getString("github_token", null)?.let {
        String(LocalVault.decrypt(it), Charsets.UTF_8)
    } ?: error("Sign in with GitHub before synchronizing.")
    private val login = preferences.getString("github_login", null)
        ?: error("Missing GitHub login")
    private val accountId = preferences.getString("github_account_id", null)
        ?: error("Missing GitHub account identifier")
    private val github = GithubBackupStore(token, login,
        deviceName = config.deviceName, deviceId = config.deviceId)
    private var latest: GithubBackupStore.Snapshot? = null
    var createdFreshBackup: Boolean = false
        private set

    fun verifiedRevision(): Pair<String, Int> {
        val snapshot = latest ?: error("GitHub backup not loaded")
        return snapshot.revision to snapshot.generation
    }
    private val archives = linkedMapOf<String, ByteArray>()
    private val stagedChanges = linkedMapOf<String, String?>()
    private var cachedCipher: SyncCipher? = null

    override fun userId() = accountId
    override fun cipher(): SyncCipher = cachedCipher ?: SyncCipher.saved(config).also {
        cachedCipher = it
    }

    private fun localState(): MutableMap<String, String> {
        val profiles = ProfileSync.snapshot(context)
        val keyed = ProfileIdentity.toRemote(SyncData.snapshot(context),
            ProfileSync.decode(profiles))
        keyed["x/profiles"] = profiles
        keyed["x/plugins"] = PluginSync.encode(
            PluginSync.decode(PluginInventory.snapshot(context)))
        return keyed
    }

    private fun uploadAvailableArchives(manifest: String) {
        val all = PluginSync.decode(manifest).items
        val online = PluginManager.getPluginsOnline().toList()
        val local = PluginManager.getPluginsLocal().toList()
        for ((_, entry) in all) {
            val hash = entry.optString("sha")
            check(hash.matches(Regex("[0-9a-f]{64}"))) {
                "Cannot start a verified backup: missing plugin hash."
            }
            val file = if (entry.optString("type") == "online") {
                online.firstOrNull {
                    it.internalName == entry.optString("name") &&
                        it.url == entry.optString("url")
                }?.filePath
            } else {
                local.firstOrNull {
                    it.internalName == entry.optString("name") &&
                        File(it.filePath).name == entry.optString("file")
                }?.filePath
            }?.let(::File)
            check(file != null && file.isFile) {
                "Cannot complete initial backup without plugin binary: " +
                    entry.optString("name")
            }
            val bytes = file.readBytes()
            check(SyncBackend.digestBytes(bytes) == hash) {
                "Plugin binary changed during snapshot: " + entry.optString("name")
            }
            archives[hash] = bytes
        }
    }

    private fun preserveUnlinkedDeviceState() {
        LocalOriginalBackup.capture(context, config)
    }

    private fun checkGeneration(snapshot: GithubBackupStore.Snapshot) {
        val expected = preferences.getInt("github_generation", 0)
        val enrolled = preferences.contains("github_base_revision") ||
            preferences.contains("base")
        if (!SyncSafety.acceptGeneration(expected, snapshot.generation, enrolled)) {
            throw IOException("Backup recovery generation changed. This device must be re-linked " +
                "before uploading changes; no local data was published.")
        }
    }

    override fun pull(previousCursor: Long?): SyncBackend.PullResult {
        val cipher = cipher()
        var snapshot = github.current(cipher)
        if (snapshot == null) {
            val alreadyEnrolled = preferences.contains("github_base_revision") ||
                preferences.contains("base")
            check(SyncSafety.canCreateFirstBackup(
                preferences.getBoolean("github_initial_backup_confirmed", false),
                alreadyEnrolled)) {
                "Automatic first backup blocked: enrolled device or missing source approval."
            }
            preserveUnlinkedDeviceState()
            check(SyncRuntime.pluginsLoaded) {
                "Waiting for CloudStream plugins before publishing initial backup."
            }
            val initial = localState()
            uploadAvailableArchives(initial.getValue("x/plugins"))
            github.initialize(cipher, initial, archives)
            createdFreshBackup = true
            archives.clear()
            snapshot = github.current(cipher)
                ?: error("GitHub commit did not produce a verifiable completed backup")
        } else {
            checkGeneration(snapshot)
            preserveUnlinkedDeviceState()
        }
        latest = snapshot
        // Verify that any desired plugin missing locally can be fetched BEFORE
        // ProfileSync applies profile changes or installation removes binaries.
        val localPluginIds = PluginSync.decode(PluginInventory.snapshot(context)).items
        val remotePluginIds = PluginSync.decode(snapshot.values["x/plugins"]).items
        for ((id, plugin) in remotePluginIds) {
            val sha = plugin.optString("sha")
            if (localPluginIds[id]?.optString("sha") == sha &&
                sha.matches(Regex("[0-9a-f]{64}"))) continue
            check(sha.matches(Regex("[0-9a-f]{64}"))) {
                "Remote plugin archive has no verifiable hash."
            }
            github.readArchive(snapshot, sha)
        }
        val rows = snapshot.values.keys.map { key ->
            RemoteEntry(key, snapshot.encrypted[key], false, "",
                "github", 1L)
        }
        // Always publish a coherent full snapshot, never a partial event page.
        return SyncBackend.PullResult(rows, null, true)
    }

    // All coordinator stages are assembled before publishing ONE Git commit.
    // A plugin/profile/settings failure must never publish a partial snapshot.
    override fun upload(
        changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>
    ) {
        check(latest != null) { "Pull must complete before staging any writes." }
        stagedChanges.putAll(changes)
    }

    override fun flush(cipher: SyncCipher) {
        if (stagedChanges.isEmpty() && archives.isEmpty()) return
        val current = latest ?: error("Cannot commit without a verified GitHub base.")
        checkGeneration(current)
        val target = current.values.toMutableMap()
        for ((key, value) in stagedChanges) {
            if (value == null) target.remove(key) else target[key] = value
        }
        val deletions = stagedChanges.filterValues { it == null }.keys
        // No deletion may pass solely because a SharedPreferences listener fired.
        val allowed = SyncDeletionGate.approved(config, deletions, current.revision)
        check(deletions.all { it in allowed }) {
            "Deletion approval missing; GitHub snapshot remains unchanged."
        }
        github.update(current, cipher, target, allowed, archives)
        latest = github.current(cipher)
            ?: error("Could not verify the newly committed GitHub snapshot.")
        stagedChanges.clear()
        archives.clear()
    }

    override fun getEntry(key: String): RemoteEntry? {
        val snapshot = github.current(cipher()) ?: return null
        latest = snapshot
        return snapshot.values[key]?.let {
            RemoteEntry(key, snapshot.encrypted[key], false, "", "github", 1L)
        }
    }

    override fun uploadFile(hash: String, bytes: ByteArray) {
        check(hash.matches(Regex("[0-9a-f]{64}")) &&
            SyncBackend.digestBytes(bytes) == hash)
        archives[hash] = bytes
    }

    override fun downloadFile(hash: String): ByteArray {
        val current = latest ?: github.current(cipher())
            ?: error("GitHub backup unavailable")
        return github.readArchive(current, hash)
    }
}
