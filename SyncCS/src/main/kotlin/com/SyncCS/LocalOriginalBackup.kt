package com.SyncCS

import android.content.Context
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.utils.DataStore
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * A first-link LOCAL original is distinct from the GitHub revision history.
 * Its manifest and packaged plugin binaries stay inside CloudStream's sandbox.
 */
internal object LocalOriginalBackup {
    data class Entry(val file: File, val created: Long, val complete: Boolean)
    data class Content(
        val values: Map<String, String>,
        val profiles: String,
        val plugins: String,
        val nativeAddons: JSONObject?,
        val archives: File?,
        val format: Int
    )

    private const val LIMIT_FILE = 20L * 1024 * 1024
    private const val LIMIT_TOTAL = 256L * 1024 * 1024
    private val SHA = Regex("[0-9a-f]{64}")
    private fun root(context: Context) = File(context.filesDir, "synccs-recovery")
    private val addonKeys = listOf("native_backup_stremioc", "native_backup_streamplay")

    fun list(context: Context): List<Entry> {
        val folder = root(context)
        return folder.listFiles()?.mapNotNull { candidate ->
            if (!candidate.name.startsWith("pre-github-link-")) return@mapNotNull null
            val manifest = when {
                candidate.isFile && candidate.extension == "enc" -> candidate
                candidate.isDirectory -> File(candidate, "snapshot.enc")
                else -> return@mapNotNull null
            }
            if (!manifest.isFile) return@mapNotNull null
            Entry(manifest, manifest.lastModified(), candidate.isDirectory)
        }?.sortedByDescending { it.created } ?: emptyList()
    }

    private fun addonSnapshot(context: Context): JSONObject {
        val preferences = with(DataStore) { context.getSharedPrefs() }
        val result = JSONObject()
        for (key in addonKeys) if (preferences.contains(key)) {
            result.put(key, preferences.getString(key, null) ?: JSONObject.NULL)
        }
        return result
    }

    fun restoreNativeAddons(context: Context, old: JSONObject?) {
        if (old == null) return // Original format-2 backups never captured these independently.
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val editor = prefs.edit()
        for (key in addonKeys) {
            if (old.has(key) && !old.isNull(key)) editor.putString(key, old.getString(key))
            else editor.remove(key)
        }
        check(editor.commit()) { "Could not restore original native add-on backups." }
        for (key in addonKeys) {
            val wanted = if (old.has(key) && !old.isNull(key)) old.getString(key) else null
            check(prefs.getString(key, null) == wanted) {
                "Native add-on backup did not verify: $key"
            }
        }
    }

    fun capture(context: Context, config: SyncConfiguration) {
        val existing = list(context)
        if (config.preferences.getBoolean("github_local_recovery_saved", false)) {
            check(existing.isNotEmpty()) {
                "An original backup was previously recorded but is missing; refusing initial sync."
            }
            return
        }
        if (existing.isNotEmpty()) {
            // Preserve an older copy; never overwrite user recovery material.
            check(config.preferences.edit().putBoolean("github_local_recovery_saved", true).commit())
            return
        }
        val parent = root(context)
        check(parent.exists() || parent.mkdirs()) { "Cannot create private recovery folder." }
        val name = "pre-github-link-" + config.deviceId.take(8) + "-" + System.currentTimeMillis()
        val staging = File(parent, name + ".staging")
        val destination = File(parent, name)
        check(!destination.exists() && !staging.exists()) { "Recovery destination already exists." }
        check(staging.mkdirs()) { "Cannot create recovery staging folder." }
        try {
            val plugins = PluginInventory.snapshot(context)
            val local = PluginManager.getPluginsLocal().toList()
            val online = PluginManager.getPluginsOnline().toList()
            var total = 0L
            val stored = mutableSetOf<String>()
            for (row in PluginSync.decode(plugins).items.values) {
                val sha = row.optString("sha")
                check(sha.matches(SHA)) {
                    "Cannot save full original backup without valid plugin SHA: " +
                        row.optString("name")
                }
                if (!stored.add(sha)) continue
                val matched = if (row.optString("type") == "local") {
                    local.firstOrNull {
                        it.internalName == row.optString("name") &&
                            File(it.filePath).name == row.optString("file")
                    }
                } else {
                    online.firstOrNull {
                        it.internalName == row.optString("name") && it.url == row.optString("url")
                    }
                }
                val binary = matched?.filePath?.let(::File)
                    ?: error("Original plugin file unavailable: " + row.optString("name"))
                check(binary.isFile && binary.length() in 1..LIMIT_FILE) {
                    "Original plugin file missing or too large: " + row.optString("name")
                }
                total += binary.length()
                check(total <= LIMIT_TOTAL) { "Original plugins exceed 256 MiB backup limit." }
                val bytes = binary.readBytes()
                check(SyncBackend.digestBytes(bytes) == sha) {
                    "Original plugin changed while snapshotting: " + row.optString("name")
                }
                File(staging, "$sha.cs3.enc").writeText(LocalVault.encrypt(bytes), Charsets.UTF_8)
            }
            val metadata = JSONObject()
                .put("format", 3)
                .put("deviceId", config.deviceId)
                .put("created", System.currentTimeMillis())
                .put("nativeValues", JSONObject(SyncData.snapshot(context)))
                .put("nativeProfiles", ProfileSync.snapshot(context))
                .put("pluginManifest", plugins)
                .put("nativeAddonBackups", addonSnapshot(context))
            File(staging, "snapshot.enc").writeText(
                LocalVault.encrypt(metadata.toString().toByteArray(Charsets.UTF_8)),
                Charsets.UTF_8)
            check(staging.renameTo(destination)) { "Could not publish original backup." }
            check(config.preferences.edit().putBoolean("github_local_recovery_saved", true).commit())
        } catch (error: Exception) {
            staging.deleteRecursively()
            throw error
        }
    }

    fun read(context: Context, entry: Entry): Content {
        check(list(context).any { it.file.canonicalFile == entry.file.canonicalFile }) {
            "Original backup is no longer present in this application's private folder."
        }
        val data = LocalVault.decrypt(entry.file.readText(Charsets.UTF_8))
        val parsed = JSONObject(String(data, Charsets.UTF_8))
        val format = parsed.getInt("format")
        check(format == 2 || format == 3) { "Unknown local original backup format." }
        val values = parsed.getJSONObject("nativeValues")
        val map = linkedMapOf<String, String>()
        for (key in values.keys()) {
            check(SyncData.isTracked(key)) { "Unsafe key in original backup: $key" }
            map[key] = values.getString(key)
        }
        val profiles = parsed.getString("nativeProfiles")
        val roster = ProfileSync.decode(profiles)
        check(roster.items.containsKey(0)) { "Original backup has no default profile." }
        val uuidValues = roster.items.values.map(ProfileIdentity::uuid)
        check(uuidValues.size == uuidValues.toSet().size) {
            "Original backup has duplicate profile identities."
        }
        val plugins = parsed.getString("pluginManifest")
        PluginSync.decode(plugins)
        val extras = if (format >= 3) parsed.getJSONObject("nativeAddonBackups") else null
        val archives = entry.file.parentFile.takeIf { format >= 3 }
        return Content(map, profiles, plugins, extras, archives, format)
    }

    /** Do not mutate a single setting before all required original binaries verify. */
    fun preflight(context: Context, content: Content) {
        val installed = PluginSync.decode(PluginInventory.snapshot(context)).items
        for ((identity, row) in PluginSync.decode(content.plugins).items) {
            val hash = row.optString("sha")
            check(hash.matches(SHA)) { "Unverifiable original plugin: " + row.optString("name") }
            if (installed[identity]?.optString("sha") == hash) continue
            val bytes = archivedBinary(content, hash)
            check(SyncBackend.digestBytes(bytes) == hash) {
                "Original plugin archive digest mismatch: " + row.optString("name")
            }
        }
    }

    private fun archivedBinary(content: Content, hash: String): ByteArray {
        check(hash.matches(SHA))
        val archive = content.archives?.let { File(it, "$hash.cs3.enc") }
            ?: throw IOException("This older original backup has no archived .cs3 for $hash. " +
                "Nothing has been changed; restoring a complete plugin set is unavailable.")
        check(archive.isFile) { "Original plugin archive missing: $hash" }
        val encrypted = archive.readText(Charsets.UTF_8)
        val bytes = LocalVault.decrypt(encrypted)
        check(bytes.isNotEmpty() && bytes.size <= LIMIT_FILE) {
            "Invalid original plugin archive length: $hash"
        }
        check(SyncBackend.digestBytes(bytes) == hash) { "Corrupted original plugin: $hash" }
        return bytes
    }

    fun storage(content: Content): SyncStorage = object : SyncStorage {
        override fun userId(): String = error("Local restore has no remote account")
        override fun cipher(): SyncCipher = error("Local restore has no cloud encryption")
        override fun pull(previousCursor: Long?): SyncBackend.PullResult = error("Local restore is offline")
        override fun upload(changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>) {
            error("Local restore must never upload to GitHub")
        }
        override fun flush(cipher: SyncCipher) { error("Local restore must never upload") }
        override fun getEntry(key: String): RemoteEntry? = null
        override fun uploadFile(hash: String, bytes: ByteArray) { error("Local restore is read-only") }
        override fun downloadFile(hash: String): ByteArray = archivedBinary(content, hash)
    }

    /** Used only by LocalSyncJournal.recover; it supplies its own verified files. */
    fun offlineRecoverySource(): SyncStorage = object : SyncStorage {
        override fun userId(): String = error("Not connected")
        override fun cipher(): SyncCipher = error("Not connected")
        override fun pull(previousCursor: Long?): SyncBackend.PullResult = error("Not connected")
        override fun upload(changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>) {
            error("Recovery cannot upload")
        }
        override fun flush(cipher: SyncCipher) { error("Recovery cannot upload") }
        override fun getEntry(key: String): RemoteEntry? = null
        override fun uploadFile(hash: String, bytes: ByteArray) { error("Recovery cannot upload") }
        override fun downloadFile(hash: String): ByteArray = error("Recovery journal supplies its own binary")
    }
}
