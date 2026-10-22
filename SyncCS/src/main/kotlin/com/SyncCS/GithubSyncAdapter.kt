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
    private val github = GithubBackupStore(token, login)
    private var latest: GithubBackupStore.Snapshot? = null
    var createdFreshBackup: Boolean = false
        private set

    fun verifiedRevision(): Pair<String, Int> {
        val snapshot = latest ?: error("GitHub backup not loaded")
        return snapshot.revision to snapshot.generation
    }
    private val archives = linkedMapOf<String, ByteArray>()
    private var cachedCipher: SyncCipher? = null

    override fun userId() = accountId
    override fun cipher(): SyncCipher = cachedCipher ?: SyncCipher.saved(config).also {
        cachedCipher = it
    }

    private fun localState(): MutableMap<String, String> = SyncData.snapshot(context).apply {
        this["x/profiles"] = ProfileSync.snapshot(context)
        this["x/plugins"] = PluginSync.encode(PluginSync.decode(PluginInventory.snapshot(context)))
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
        if (preferences.getString("github_base_revision", null) != null) return
        if (preferences.getBoolean("github_local_recovery_saved", false)) return
        val snapshot = localState()
        val json = JSONObject(snapshot).toString()
        val protected = LocalVault.encrypt(json.toByteArray(Charsets.UTF_8))
        val folder = File(context.filesDir, "synccs-recovery")
        check(folder.exists() || folder.mkdirs())
        val name = "pre-github-link-" + config.deviceId.take(8) + ".enc"
        val destination = File(folder, name)
        check(!destination.exists()) {
            "A previous device recovery snapshot already exists; refusing overwrite."
        }
        val temporary = File(folder, "$name.tmp")
        temporary.writeText(protected, Charsets.UTF_8)
        check(temporary.renameTo(destination)) { "Could not persist local recovery snapshot." }
        check(preferences.edit().putBoolean("github_local_recovery_saved", true).commit())
    }

    private fun checkGeneration(snapshot: GithubBackupStore.Snapshot) {
        val expected = preferences.getInt("github_generation", 0)
        if (expected != 0 && expected != snapshot.generation) {
            throw IOException("Backup recovery generation changed. This device must be re-linked " +
                "before uploading changes; no local data was published.")
        }
    }

    override fun pull(previousCursor: Long?): SyncBackend.PullResult {
        val cipher = cipher()
        var snapshot = github.current(cipher)
        if (snapshot == null) {
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
        val rows = snapshot.values.keys.map { key ->
            RemoteEntry(key, snapshot.encrypted[key], false, "",
                "github", 1L)
        }
        // Always publish a coherent full snapshot, never a partial event page.
        return SyncBackend.PullResult(rows, null, true)
    }

    override fun upload(
        changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>
    ) {
        if (changes.isEmpty() && archives.isEmpty()) return
        val current = latest ?: github.current(cipher)
            ?: error("Cannot update a non-existent GitHub backup")
        checkGeneration(current)
        val target = current.values.toMutableMap()
        for ((key, value) in changes) {
            if (value == null) target.remove(key)
            else target[key] = value
        }
        val explicitDeletions = changes.keys.filter {
            changes[it] == null &&
                preferences.contains("modified/" + it)
        }.toSet()
        github.update(current, cipher, target, explicitDeletions, archives)
        archives.clear()
        latest = github.current(cipher)
            ?: error("Failed to verify the committed GitHub backup")
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
