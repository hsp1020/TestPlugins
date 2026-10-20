package com.SyncCS

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.time.Instant

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
        val backend = SyncBackend(config)
        try {
            val cipher = backend.cipher()
            val remotes = backend.list()
            // Authenticate every entry before modifying any local data.
            val remoteValues = remotes.associate { item ->
                item.key to if (item.deleted) null else cipher.decrypt(
                    item.key, item.cipher ?: error("Missing encrypted value for " + item.key))
            }
            val remoteByKey = remotes.associateBy { it.key }
            val local = SyncData.snapshot(context)
            val localManifest = PluginInventory.snapshot(context)
            local["x/plugins"] = localManifest
            val base = baseline(context)
            val apply = mutableMapOf<String, String?>()
            val upload = mutableMapOf<String, String?>()
            val next = base.toMutableMap()
            val all = (remoteValues.keys + local.keys + base.keys).toSortedSet()

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
                        if (key == "x/plugins") {
                            if (remote != current) config.preferences.edit()
                                .putString("pending_plugins", remote ?: "[]").apply()
                        } else if (remote != current) {
                            apply[key] = remote
                        }
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
                    val remoteTime = try {
                        Instant.parse(remoteByKey[key]?.updatedAt).toEpochMilli()
                    } catch (_: Exception) { Long.MAX_VALUE }
                    if (modified > remoteTime) {
                        upload[key] = current
                        next[key] = currentHash
                    } else {
                        if (key == "x/plugins") {
                            if (remote != current) config.preferences.edit()
                                .putString("pending_plugins", remote ?: "[]").apply()
                        } else apply[key] = remote
                        next[key] = remoteHash
                    }
                } else if (changedRemote) {
                    if (key == "x/plugins") {
                        if (remote != current) config.preferences.edit()
                            .putString("pending_plugins", remote ?: "[]").apply()
                    } else apply[key] = remote
                    next[key] = remoteHash
                } else if (changedLocal) {
                    if (key == "x/plugins" && config.preferences.contains("pending_plugins")) {
                        // Do not erase remote plugins before installation finishes.
                        continue
                    }
                    upload[key] = current
                    next[key] = currentHash
                }
            }

            applying = true
            try {
                SyncData.apply(context, apply)
            } finally {
                applying = false
            }

            // Upload files first so manifest rows never refer to missing new archives.
            if (upload.containsKey("x/plugins")) {
                PluginInventory.uploadFiles(context, backend, upload["x/plugins"] ?: "[]")
            }
            backend.upload(upload, cipher, remoteByKey.mapValues { it.value.version })

            val baseJson = JSONObject()
            for ((key, hash) in next) baseJson.put(key, hash)
            check(config.preferences.edit().putString("base", baseJson.toString()).commit())
            for (key in apply.keys + upload.keys) {
                config.preferences.edit().remove("modified/" + key).apply()
            }
            if (apply.keys.any { it.startsWith("d/") || it.startsWith("p/") }) {
                MainActivity.bookmarksUpdatedEvent.invoke(true)
            }
            var result = "Synced: downloaded " + apply.size + ", uploaded " + upload.size
            if (activity != null) {
                result += installPending(context, activity, backend)
            }
            lastStatus = result
            result
        } catch (error: Exception) {
            val message = "Sync failed: " + (error.message ?: error.javaClass.simpleName).take(180)
            Log.w("SyncCS", message)
            lastStatus = message
            message
        }
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
