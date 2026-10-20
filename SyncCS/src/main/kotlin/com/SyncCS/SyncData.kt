package com.SyncCS

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.plugins.RepositoryManager
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.ui.settings.extensions.RepositoryData
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.PREFERENCES_NAME
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

internal object SyncData {
    private val dataPrefixes = listOf(
        "result_resume_watching_2/", "result_watch_state/",
        "result_watch_state_data/", "result_favorites_state_data/",
        "result_subscribed_state_data/", "video_pos_dur/",
        "video_watch_state/", "result_season/", "result_episode/", "result_dub/",
        "video_source_priority/", "video_quality_priority/", "video_profile_name/",
        "video_profile_types_2/", "video_profile_settings/",
        "auth_tokens/", "auth_ids/", "download_header_cache/"
    )
    private val globallyShared = setOf(
        "user_pinned_providers", "user_custom_sites", "REPOSITORIES_KEY",
        "provider_order", "seen_providers", "stremio_addons",
        "home_api_used",
        "search_pref_providers", "search_pref_tags", "home_pref_homepage",
        "home_bookmarked_last_list", "library_sorting_mode", "results_sorting_mode"
    )
    private val pluginPrefixes = listOf(
        "cinestream", "streamplay", "stremioc", "ultimaevo", "ultima_evo",
        "elevate", "youtube"
    )
    private val defaultShared = emptySet<String>()
    private fun pluginScoped(key: String): Boolean {
        val value = key.substringAfter('/', key).lowercase()
        if (listOf("cache", "crash", "logcat", "history_temp", "download_path",
                "device_id", "keystore").any { value.contains(it) }) return false
        val compact = value.replace(Regex("[^a-z0-9]"), "")
        return pluginPrefixes.any { prefix ->
            value.startsWith(prefix) || compact.startsWith(prefix.replace("_", "")) ||
                value.contains("_" + prefix + "_") || value.endsWith("_" + prefix) ||
                value.contains("/" + prefix + "/")
        }
    }
    private val forbiddenFileParts = listOf(
        "synccs", "cache", "webview", "com.google", "firebase", "analytics",
        "crashlytics", "androidx", "gms", "advertis", "encrypted"
    )
    private fun dataAllowed(key: String): Boolean {
        if (key in globallyShared) return true
        val slash = key.indexOf('/')
        if (slash < 0) return false
        val account = key.substring(0, slash)
        val rest = if (account.all(Char::isDigit)) key.substring(slash + 1) else key
        return rest in globallyShared || dataPrefixes.any { rest.startsWith(it) } ||
            pluginScoped(rest)
    }
    fun isTracked(path: String): Boolean = when {
        path.startsWith("d/") -> dataAllowed(path.removePrefix("d/"))
        path.startsWith("s/") -> path.removePrefix("s/") in defaultShared ||
            pluginScoped(path.removePrefix("s/"))
        path.startsWith("p/") -> {
            val file = path.removePrefix("p/").substringBefore('/')
            val key = path.removePrefix("p/").substringAfter('/', "")
            file !in listOf("StremioC_Manifest", "AwOriginVisitLoggerPrefs",
                "android.app.ActivityThread.IDS") &&
                key.isNotBlank() && key.length <= 300 &&
                !key.contains("cache", true) && !key.contains("logcat", true)
        }
        else -> false
    }

    private fun pluginFiles(context: Context): List<String> {
        val folder = File(context.applicationInfo.dataDir, "shared_prefs")
        return folder.listFiles()?.asSequence()?.filter { it.isFile && it.extension == "xml" }
            ?.map { it.nameWithoutExtension }
            ?.filter { name ->
                name != PREFERENCES_NAME && name != context.packageName + "_preferences" &&
                name.length in 1..80 && !name.contains("..") && !name.contains('/') &&
                forbiddenFileParts.none { name.contains(it, ignoreCase = true) } &&
                name != "StremioC_Manifest" && name != "AwOriginVisitLoggerPrefs" &&
                name != "android.app.ActivityThread.IDS"
            }?.toList() ?: emptyList()
    }

    private fun valueJson(value: Any?): String? {
        val result = JSONObject()
        when (value) {
            is Boolean -> result.put("type", "bool").put("value", value)
            is Int -> result.put("type", "int").put("value", value)
            is Long -> result.put("type", "long").put("value", value)
            is Float -> result.put("type", "float").put("value", value.toDouble())
            is String -> result.put("type", "string").put("value", value)
            is Set<*> -> if (value.all { it is String }) {
                result.put("type", "set").put("value", JSONArray(value.toList()))
            } else return null
            else -> return null
        }
        return result.toString()
    }
    fun snapshot(context: Context): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        val data = with(DataStore) { context.getSharedPrefs() }
        for ((name, value) in data.all) {
            if (!dataAllowed(name) || name.length > 500) continue
            valueJson(value)?.let { if (it.length <= 524288) result["d/" + name] = it }
        }
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        for ((name, value) in defaults.all) {
            if (name !in defaultShared && !pluginScoped(name)) continue
            valueJson(value)?.let { result["s/" + name] = it }
        }
        for (file in pluginFiles(context)) {
            val pref = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            for ((key, value) in pref.all) {
                if (key.length > 300 || key.contains("cache", true) || key.contains("logcat", true)) continue
                valueJson(value)?.let {
                    if (it.length <= 524288) result["p/" + file + "/" + key] = it
                }
            }
        }
        return result
    }

    private fun edit(editor: SharedPreferences.Editor, key: String, raw: String?) {
        if (raw == null) {
            editor.remove(key)
            return
        }
        val item = JSONObject(raw)
        when (item.getString("type")) {
            "bool" -> editor.putBoolean(key, item.getBoolean("value"))
            "int" -> editor.putInt(key, item.getInt("value"))
            "long" -> editor.putLong(key, item.getLong("value"))
            "float" -> editor.putFloat(key, item.getDouble("value").toFloat())
            "string" -> editor.putString(key, item.getString("value"))
            "set" -> {
                val arr = item.getJSONArray("value")
                editor.putStringSet(key, (0 until arr.length()).map { arr.getString(it) }.toSet())
            }
            else -> error("Unknown preference value type")
        }
    }

    /**
     * CloudStream versions expose these caches as either fields or Kotlin getters
     * with different JVM signatures. Do not link to a particular getter at compile time:
     * a binary mismatch would otherwise terminate the entire host process.
     */
    private fun invalidatePriorityCaches(): Boolean {
        val helper = try {
            Class.forName("com.lagradost.cloudstream3.ui.player.source_priority.QualityDataHelper")
        } catch (_: LinkageError) {
            return false
        } catch (_: ClassNotFoundException) {
            return false
        }
        val instance = runCatching { helper.getField("INSTANCE").get(null) }.getOrNull()
        var cleared = 0
        for (name in listOf("sourcePriorityCache", "qualityPriorityCache")) {
            val getterName = "get" + name.replaceFirstChar { it.uppercaseChar() }
            val cache = try {
                val accessor = helper.methods.firstOrNull {
                    it.name == getterName && it.parameterCount == 0
                }
                if (accessor != null) {
                    accessor.invoke(instance)
                } else {
                    val field = helper.declaredFields.firstOrNull { it.name == name }
                    field?.also { it.isAccessible = true }?.get(
                        if (field != null && java.lang.reflect.Modifier.isStatic(field.modifiers)) null
                        else instance
                    )
                }
            } catch (_: ReflectiveOperationException) {
                null
            } catch (_: SecurityException) {
                null
            } catch (_: LinkageError) {
                null
            }
            if (cache is MutableMap<*, *>) {
                try {
                    cache.clear()
                    cleared++
                } catch (_: UnsupportedOperationException) {
                    // This CloudStream version exposes an immutable cache.
                }
            }
        }
        return cleared == 2
    }

    /**
     * Refresh service-account caches only when the running host exposes the
     * matching public companion methods. Persisted accounts survive restart even
     * when immediate in-memory cache refresh is unavailable.
     */
    private fun refreshAccountCaches(): Boolean {
        return try {
            val owner = Class.forName("com.lagradost.cloudstream3.syncproviders.AccountManager")
            val companion = owner.getField("Companion").get(null)
            val methods = companion.javaClass.methods
            val allApis = methods.firstOrNull { it.name == "getAllApis" && it.parameterCount == 0 }
                ?.invoke(companion) as? Array<*> ?: return false
            val accountsMethod = methods.firstOrNull { it.name == "accounts" && it.parameterCount == 1 }
                ?: return false
            val setter = methods.firstOrNull {
                it.name == "setCachedAccounts" && it.parameterCount == 1
            } ?: return false
            val accountMap = mutableMapOf<String, Any>()
            for (api in allApis) {
                if (api == null) continue
                val prefix = api.javaClass.methods.firstOrNull {
                    it.name == "getIdPrefix" && it.parameterCount == 0
                }?.invoke(api) as? String ?: continue
                val values = accountsMethod.invoke(companion, prefix) ?: continue
                accountMap[prefix] = values
            }
            setter.invoke(companion, accountMap)
            methods.firstOrNull { it.name == "updateAccountIds" && it.parameterCount == 0 }
                ?.invoke(companion)
            true
        } catch (_: ReflectiveOperationException) {
            false
        } catch (_: SecurityException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    fun apply(context: Context, changes: Map<String, String?>) {
        if (changes.isEmpty()) return
        val data = with(DataStore) { context.getSharedPrefs() }
        val defaults = PreferenceManager.getDefaultSharedPreferences(context)
        val dataEdit = data.edit()
        val defaultEdit = defaults.edit()
        val pluginEditors = mutableMapOf<String, SharedPreferences.Editor>()
        var accountsChanged = false
        var priorityChanged = false
        for ((key, value) in changes) {
            when {
                key.startsWith("d/") -> {
                    val original = key.removePrefix("d/")
                    if (!dataAllowed(original)) continue
                    edit(dataEdit, original, value)
                    if (original.contains("video_source_priority/") ||
                        original.contains("video_quality_priority/")) priorityChanged = true
                    if (original.contains("auth_tokens/") || original.contains("auth_ids/")) accountsChanged = true
                }
                key.startsWith("s/") -> {
                    val original = key.removePrefix("s/")
                    if (original in defaultShared || pluginScoped(original)) {
                        edit(defaultEdit, original, value)
                    }
                }
                key.startsWith("p/") -> {
                    val name = key.removePrefix("p/").substringBefore('/')
                    val prefKey = key.removePrefix("p/").substringAfter('/', "")
                    if (name.length !in 1..80 || name.contains("..") ||
                        forbiddenFileParts.any { name.contains(it, true) } ||
                        name in listOf("StremioC_Manifest", "AwOriginVisitLoggerPrefs",
                            "android.app.ActivityThread.IDS") ||
                        prefKey.isBlank()) continue
                    val editor = pluginEditors.getOrPut(name) {
                        context.getSharedPreferences(name, Context.MODE_PRIVATE).edit()
                    }
                    edit(editor, prefKey, value)
                }
            }
        }
        check(dataEdit.commit()) { "CloudStream settings restore failed" }
        check(defaultEdit.commit()) { "Default settings restore failed" }
        pluginEditors.values.forEach { check(it.commit()) { "Plugin settings restore failed" } }
        if (priorityChanged && !invalidatePriorityCaches()) {
            android.util.Log.w("SyncCS", "Priority cache APIs unavailable; restart CloudStream to reload priorities.")
        }
        if (accountsChanged && !refreshAccountCaches()) {
            android.util.Log.w("SyncCS", "Account cache APIs unavailable; restart CloudStream to reload accounts.")
        }
    }
}

internal object PluginInventory {
    private const val MAX_FILE = 20 * 1024 * 1024

    fun snapshot(context: Context): String {
        val repos = RepositoryManager.getRepositories()
        val items = JSONArray()
        for (plugin in PluginManager.getPluginsOnline()) {
            if (plugin.internalName.equals("SyncCS", true)) continue
            val repo = repos.firstOrNull {
                val parent = File(plugin.filePath).parentFile?.name
                parent == PluginManager.getPluginSanitizedFileName(it.url)
            }?.url ?: ""
            val file = File(plugin.filePath)
            val sha = if (file.isFile && file.length() in 1L..MAX_FILE.toLong()) {
                SyncBackend.digestBytes(file.readBytes())
            } else ""
            items.put(JSONObject().put("type", "online").put("name", plugin.internalName)
                .put("url", plugin.url ?: "").put("repo", repo).put("version", plugin.version)
                .put("sha", sha))
        }
        for (plugin in PluginManager.getPluginsLocal()) {
            if (plugin.internalName.equals("SyncCS", true)) continue
            val file = File(plugin.filePath)
            if (!file.isFile || file.length() !in 1L..MAX_FILE.toLong()) continue
            items.put(JSONObject().put("type", "local").put("name", plugin.internalName)
                .put("file", file.name).put("sha", SyncBackend.digestBytes(file.readBytes())))
        }
        return items.toString()
    }

    fun uploadFiles(context: Context, backend: SyncBackend, manifest: String) {
        val config = SyncConfiguration(context)
        val registry = config.preferences
        val entries = JSONArray(manifest)
        val online = PluginManager.getPluginsOnline().associateBy { it.internalName }
        val local = PluginManager.getPluginsLocal().associateBy { it.internalName }
        for (index in 0 until entries.length()) {
            val row = entries.getJSONObject(index)
            val hash = row.optString("sha")
            if (hash.isBlank() || registry.getBoolean("file_" + hash, false)) continue
            val plugin = if (row.optString("type") == "online") {
                online[row.optString("name")]
            } else local[row.optString("name")]
            val file = plugin?.filePath?.let(::File) ?: continue
            if (!file.isFile || file.length() !in 1L..MAX_FILE.toLong()) continue
            try {
                backend.uploadFile(hash, file.readBytes())
                registry.edit().putBoolean("file_" + hash, true).apply()
            } catch (error: Exception) {
                if (row.optString("type") == "local") throw error
                // Online extensions can still be downloaded from their repository.
            }
        }
    }

    suspend fun restore(context: Context, activity: Activity, backend: SyncBackend, raw: String): List<String> {
        val report = mutableListOf<String>()
        val rows = JSONArray(raw)
        val existingLocal = PluginManager.getPluginsLocal()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val name = row.optString("name")
            if (name.isBlank() || name.equals("SyncCS", true)) continue
            val hash = row.optString("sha")
            if (row.optString("type") == "online") {
                val url = row.optString("url")
                val repo = row.optString("repo")
                if (PluginManager.getPluginsOnline().any {
                    it.internalName == name && it.url == url && File(it.filePath).exists()
                }) continue
                var installed = false
                if (url.startsWith("https://") && repo.startsWith("https://")) {
                    if (RepositoryManager.getRepositories().none { it.url == repo }) {
                        RepositoryManager.addRepository(RepositoryData("Restored for " + name, repo))
                    }
                    try {
                        installed = PluginManager.downloadPlugin(
                            activity, url,
                            hash.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { "sha256-" + it },
                            name, repo, false
                        )
                    } catch (_: Exception) { }
                }
                if (!installed && hash.matches(Regex("[a-f0-9]{64}")) && repo.isNotBlank()) {
                    try {
                        val bytes = backend.downloadFile(hash)
                        if (SyncBackend.digestBytes(bytes) == hash) {
                            val path = PluginManager.getPluginPath(context, name, repo)
                            path.parentFile?.mkdirs()
                            val temp = File(path.parentFile, path.name + ".temp")
                            temp.writeBytes(bytes)
                            if (path.exists()) path.delete()
                            check(temp.renameTo(path))
                            val meta = PluginData(name, url, true, path.absolutePath, row.optInt("version"))
                            val current = PluginManager.getPluginsOnline().filter { it.filePath != path.absolutePath }
                            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                PLUGINS_KEY, (current + meta).toTypedArray())
                            installed = true
                        }
                    } catch (_: Exception) { }
                }
                if (!installed) report.add(
                    if (repo.isBlank()) "Missing repository URL for " + name
                    else "Could not install " + name
                )
            } else {
                if (existingLocal.any { it.internalName == name && File(it.filePath).exists() }) continue
                if (!hash.matches(Regex("[a-f0-9]{64}"))) {
                    report.add("No backup for local plugin " + name)
                    continue
                }
                try {
                    val bytes = backend.downloadFile(hash)
                    check(SyncBackend.digestBytes(bytes) == hash)
                    val filename = row.optString("file").substringAfterLast('/').substringAfterLast('\\')
                    check(filename.endsWith(".cs3", true) && filename.length < 120)
                    val savedToLegacyFolder = try {
                        val directory = File(Environment.getExternalStorageDirectory(), "Cloudstream3/plugins")
                        check(directory.exists() || directory.mkdirs())
                        File(directory, filename).writeBytes(bytes)
                        true
                    } catch (_: Exception) {
                        false
                    }
                    if (!savedToLegacyFolder) {
                        // Scoped-storage Android devices cannot always write /sdcard/Cloudstream3.
                        // Register a private extension copy so the existing host loader can load it on restart.
                        val archiveRepo = "https://synccs.invalid/local-archive"
                        val privateFile = PluginManager.getPluginPath(context, name, archiveRepo)
                        check(privateFile.parentFile?.let { it.exists() || it.mkdirs() } == true)
                        val temp = File(privateFile.parentFile, privateFile.name + ".temp")
                        temp.writeBytes(bytes)
                        check(SyncBackend.digestBytes(temp.readBytes()) == hash)
                        if (privateFile.exists()) check(privateFile.delete())
                        check(temp.renameTo(privateFile))
                        val original = PluginManager.getPluginsOnline().filter {
                            it.filePath != privateFile.absolutePath
                        }
                        val archived = PluginData(
                            name, "https://synccs.invalid/local-archive/" + hash + ".cs3",
                            true, privateFile.absolutePath, row.optInt("version", 0)
                        )
                        com.lagradost.cloudstream3.CloudStreamApp.setKey(
                            PLUGINS_KEY, (original + archived).toTypedArray()
                        )
                    }
                } catch (_: Exception) {
                    report.add("Could not restore local .cs3: " + name)
                }
            }
        }
        return report
    }
}
