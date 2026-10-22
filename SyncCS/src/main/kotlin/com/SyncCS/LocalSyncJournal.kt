package com.SyncCS

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * Durable, encrypted local undo journal. It is created BEFORE any CloudStream
 * settings, account, or plugin mutation. If the process dies, the next
 * foreground synchronization must finish restoring it before normal sync.
 */
internal object LocalSyncJournal {
    private const val LIMIT_TOTAL = 256L * 1024 * 1024
    private const val LIMIT_FILE = 20L * 1024 * 1024
    private fun directory(context: Context) = File(context.filesDir, "synccs-local-transaction")
    private fun live(context: Context) = File(directory(context), "active")
    fun pending(context: Context) = live(context).isDirectory

    private val transactionKeys = listOf(
        "base", "profile_baseline", "profile_remote_snapshot",
        "plugin_baseline", "plugin_remote_snapshot", "pending_plugins",
        "remote_cursor", "remote_versions", "list_baselines",
        "github_base_revision", "github_generation", "pending_conflicts"
    )

    private fun privateMetadata(context: Context): JSONObject {
        val preferences = SyncConfiguration(context).preferences
        val output = JSONObject()
        for (key in transactionKeys) {
            if (!preferences.contains(key)) continue
            val value = preferences.all[key] ?: continue
            when (value) {
                is String -> output.put(key, JSONObject().put("type", "string").put("value", value))
                is Int -> output.put(key, JSONObject().put("type", "int").put("value", value))
                is Long -> output.put(key, JSONObject().put("type", "long").put("value", value))
                is Boolean -> output.put(key, JSONObject().put("type", "bool").put("value", value))
                else -> error("Unsupported SyncCS transaction setting: $key")
            }
        }
        return output
    }

    private fun restorePrivateMetadata(context: Context, snapshot: JSONObject) {
        val editor = SyncConfiguration(context).preferences.edit()
        for (key in transactionKeys) editor.remove(key)
        for (key in snapshot.keys()) {
            check(key in transactionKeys)
            val item = snapshot.getJSONObject(key)
            when (item.getString("type")) {
                "string" -> editor.putString(key, item.getString("value"))
                "int" -> editor.putInt(key, item.getInt("value"))
                "long" -> editor.putLong(key, item.getLong("value"))
                "bool" -> editor.putBoolean(key, item.getBoolean("value"))
                else -> error("Invalid saved SyncCS metadata.")
            }
        }
        check(editor.commit()) { "Could not restore SyncCS sync baselines." }
    }

    private fun manifest(folder: File): JSONObject {
        val file = File(folder, "snapshot.enc")
        check(file.isFile) { "Incomplete SyncCS undo journal; manual recovery required." }
        return JSONObject(String(LocalVault.decrypt(file.readText(Charsets.UTF_8)),
            Charsets.UTF_8))
    }

    fun capture(context: Context) {
        if (pending(context)) return
        val root = directory(context)
        val temp = File(root, "staging")
        if (temp.exists()) check(temp.deleteRecursively())
        check(temp.mkdirs()) { "Cannot allocate a local recovery journal." }
        try {
            val values = JSONObject(SyncData.snapshot(context))
            val profiles = ProfileSync.snapshot(context)
            val plugins = PluginInventory.snapshot(context)
            val online = PluginManager.getPluginsOnline().toList()
            val offline = PluginManager.getPluginsLocal().toList()
            val needed = PluginSync.decode(plugins).items.values
            val stored = linkedSetOf<String>()
            var totalBytes = 0L
            for (plugin in needed) {
                val hash = plugin.optString("sha")
                check(hash.matches(Regex("[0-9a-f]{64}"))) {
                    "Cannot create recovery checkpoint without a verifiable plugin hash."
                }
                if (!stored.add(hash)) continue
                val path = if (plugin.optString("type") == "local") {
                    offline.firstOrNull {
                        it.internalName == plugin.optString("name") &&
                            File(it.filePath).name == plugin.optString("file")
                    }?.filePath
                } else {
                    online.firstOrNull {
                        it.internalName == plugin.optString("name") &&
                            it.url == plugin.optString("url")
                    }?.filePath
                } ?: error("Installed plugin binary vanished before recovery checkpoint.")
                val file = File(path)
                check(file.isFile && file.length() in 1..LIMIT_FILE) {
                    "Unsafe or missing plugin binary during local checkpoint."
                }
                totalBytes += file.length()
                check(totalBytes <= LIMIT_TOTAL) {
                    "Local rollback requires over 256 MiB. Sync cancelled without changes."
                }
                val destination = File(temp, "$hash.cs3")
                file.copyTo(destination, overwrite = false)
                check(SyncBackend.digestBytes(destination.readBytes()) == hash) {
                    "Plugin changed during local checkpoint."
                }
            }
            val meta = JSONObject().put("format", 1)
                .put("created", System.currentTimeMillis())
                .put("values", values)
                .put("profiles", profiles).put("plugins", plugins)
                .put("privateSyncState", privateMetadata(context))
            File(temp, "snapshot.enc").writeText(
                LocalVault.encrypt(meta.toString().toByteArray(Charsets.UTF_8)),
                Charsets.UTF_8)
            check(temp.renameTo(live(context))) { "Could not activate the local rollback journal." }
        } catch (error: Exception) {
            temp.deleteRecursively()
            throw error
        }
    }

    /**
     * Roll back both preferences and plugin archives. Keep journal on any
     * mismatch: it is never safe to declare recovery successful prematurely.
     */
    suspend fun recover(context: Context, activity: Activity?, storage: SyncStorage) {
        val dir = live(context)
        if (!dir.exists()) return
        check(activity != null && !activity.isFinishing && !activity.isDestroyed) {
            "Local rollback requires a foreground CloudStream activity. Sync is paused."
        }
        val metadata = manifest(dir)
        check(metadata.getInt("format") == 1)
        val original = metadata.getJSONObject("values")
        val backup = linkedMapOf<String, String>()
        for (key in original.keys()) backup[key] = original.getString(key)
        // The prior roster is applied before attempting to restore its data.
        ProfileSync.apply(context, ProfileSync.decode(metadata.getString("profiles")))
        val now = SyncData.snapshot(context)
        val restore = linkedMapOf<String, String?>()
        for ((key, value) in backup) {
            if (now[key] != value) restore[key] = value
        }
        for (key in now.keys - backup.keys) restore[key] = null
        SyncData.apply(context, restore)

        val originalManifest = metadata.getString("plugins")
        val source = object : SyncStorage by storage {
            override fun downloadFile(hash: String): ByteArray {
                check(hash.matches(Regex("[0-9a-f]{64}")))
                val bytes = File(dir, "$hash.cs3").readBytes()
                check(SyncBackend.digestBytes(bytes) == hash) {
                    "Local recovery binary was corrupted."
                }
                return bytes
            }
        }
        val errors = PluginInventory.restore(context, activity, source, originalManifest)
        check(errors.isEmpty()) { "Plugin rollback incomplete: " + errors.joinToString(" | ") }
        // Restore the originally saved native backup AFTER extension reload,
        // which can replay or refresh settings during plugin initialization.
        val nativeKeys = setOf("d/native_backup_stremioc", "d/native_backup_streamplay")
        val nativeBackup = nativeKeys.associateWith { backup[it] }
        SyncData.apply(context, nativeBackup)
        val restored = SyncData.snapshot(context)
        check(restored.keys == backup.keys &&
            backup.all { (key, value) -> restored[key] == value }) {
            "Local settings verification failed after rollback: missing or extra keys."
        }
        check(PluginSync.matches(PluginSync.decode(PluginInventory.snapshot(context)),
            PluginSync.decode(originalManifest))) { "Installed plugins differ after rollback." }
        check(ProfileSync.encode(ProfileSync.decode(ProfileSync.snapshot(context))) ==
            ProfileSync.encode(ProfileSync.decode(metadata.getString("profiles")))) {
            "Profile roster verification failed after rollback."
        }
        restorePrivateMetadata(context, metadata.getJSONObject("privateSyncState"))
        finish(context)
    }

    fun finish(context: Context) {
        val dir = live(context)
        if (!dir.exists()) return
        val complete = File(directory(context), "completed")
        if (complete.exists()) check(complete.deleteRecursively()) {
            "Could not remove retired recovery journal."
        }
        // Rename is atomic within the same directory: a crash while deleting
        // retired files can never leave a partially deleted ACTIVE journal.
        check(dir.renameTo(complete)) { "Could not retire active recovery journal." }
        // Residual completed files are safe to remove on the next transaction.
        complete.deleteRecursively()
    }
}
