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
import com.lagradost.cloudstream3.utils.DataStoreHelper
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
        "search_history/", "auth_tokens/", "auth_ids/"
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

    // Use the same CineStream configuration sources as StremioC's Export / Import.
    // CineStream stores settings in the CloudStream DataStore under arbitrary key
    // names, not only names starting with "cinestream" or "Provider".
    private val cineStreamFallbackKeys = setOf(
        "ScrapeConcurrency", "DownloadEnable", "ProviderCineStream", "ProviderSimkl",
        "ProviderTmdb", "showbox_ui_token", "wyzie_subs_api_key",
        "gramcinema_bearer_token", "stremio_addons", "new_provider_default_on",
        "seen_providers", "provider_order", "cloudflare_webview_bypass_enabled"
    )
    @Volatile private var cineStreamDiscoveredKeys: Set<String> = cineStreamFallbackKeys

    private fun cineStreamKeyAllowed(key: String): Boolean =
        key.length in 1..200 && key.all {
            it.isLetterOrDigit() || it == '_' || it == '-' || it == '.'
        } && !listOf("cache", "crash", "logcat", "download_path", "keystore")
            .any { key.contains(it, ignoreCase = true) }

    // CineStream's SettingsProviders persists enabled switches by ProviderRegistry
    // keys, while Cloudflare WebView stores a separate cookie for each domain.
    private fun cineStreamDomainKey(key: String): Boolean =
        key.startsWith("p_") || (key.startsWith("stremio_") &&
            key != "stremio_addons") || key.startsWith("cf_cookie_")

    fun refreshCineStreamKeys(): Set<String> {
        val keys = cineStreamFallbackKeys.toMutableSet()
        try {
            val loaderManager = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
            val manager = loaderManager.getField("INSTANCE").get(null)
            var cineLoader: ClassLoader? = null
            for (field in loaderManager.declaredFields) {
                val collection = try {
                    field.isAccessible = true
                    val content = field.get(
                        if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else manager
                    )
                    when (content) {
                        is Map<*, *> -> content.values
                        is Collection<*> -> content
                        is Array<*> -> content.toList()
                        else -> emptyList<Any>()
                    }
                } catch (_: Exception) {
                    emptyList<Any>()
                }
                cineLoader = collection.firstOrNull { candidate ->
                    val className = candidate?.javaClass?.name.orEmpty()
                    className.contains("megix", true) || className.contains("CineStream", true)
                }?.javaClass?.classLoader
                if (cineLoader != null) break
            }
            if (cineLoader != null) {
                val settings = Class.forName("com.megix.settings.Settings", false, cineLoader)
                val companion = settings.declaredFields.firstOrNull { it.name == "INSTANCE" }?.let {
                    it.isAccessible = true
                    it.get(null)
                }
                for (field in settings.declaredFields) {
                    if (field.type != String::class.java) continue
                    try {
                        field.isAccessible = true
                        val owner = if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else companion
                        val key = field.get(owner) as? String ?: continue
                        if (cineStreamKeyAllowed(key)) keys.add(key)
                    } catch (_: Exception) { }
                }
            }
        } catch (_: Exception) { }
        catch (_: LinkageError) { }

        // StremioC also derives keys from active and ordered providers/addons.
        val data = with(DataStore) {
            com.lagradost.cloudstream3.CloudStreamApp.context?.getSharedPrefs()
        }
        try {
            for (property in listOf("seen_providers", "provider_order")) {
                (data?.getString(property, null) ?: "").split(',').forEach { item ->
                    val key = item.trim()
                    if (cineStreamKeyAllowed(key)) keys.add(key)
                }
            }
            (data?.getString("stremio_addons", null) ?: "").lines().forEach { line ->
                val name = line.substringBefore('|').trim()
                if (name.isNotBlank()) {
                    val suffix = name.lowercase()
                        .replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    val key = "stremio_" + suffix
                    if (cineStreamKeyAllowed(key)) keys.add(key)
                }
            }
        } catch (_: Exception) { }
        cineStreamDiscoveredKeys = keys
        return keys
    }
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
        if (key in globallyShared || key in cineStreamDiscoveredKeys ||
            (cineStreamDomainKey(key) && cineStreamKeyAllowed(key))) return true
        val slash = key.indexOf('/')
        if (slash < 0) return false
        val account = key.substring(0, slash)
        val rest = if (account.all(Char::isDigit)) key.substring(slash + 1) else key
        return rest in globallyShared || dataPrefixes.any { rest.startsWith(it) } ||
            pluginScoped(rest) || rest in cineStreamDiscoveredKeys ||
            (cineStreamDomainKey(rest) && cineStreamKeyAllowed(rest))
    }
    // Only sync data for profiles currently present in the native roster.
    // Inactive profile data is left untouched, not deleted by SyncCS.
    private fun belongsToInstalledProfile(key: String, indices: Set<Int>): Boolean {
        val prefix = key.substringBefore('/', "")
        if (prefix.isEmpty() || !prefix.all(Char::isDigit) || !key.contains('/')) return true
        return prefix.toIntOrNull() in indices
    }

    private fun pluginPreferenceAllowed(file: String, key: String): Boolean {
        // StreamPlay telemetry depends on the current device and isn't a setting.
        if (file.equals("StreamPlay", true) &&
            (key == "STREAMPLAY_TELEMETRY_V2" || key.startsWith("provider_stats_"))) return false
        return true
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
                pluginPreferenceAllowed(file, key) &&
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
        refreshCineStreamKeys()
        val profileIndices = DataStoreHelper.getAccounts(context)
            .mapTo(mutableSetOf()) { it.keyIndex }
        for ((name, value) in data.all) {
            if (!belongsToInstalledProfile(name, profileIndices)) continue
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
                if (key.length > 300 || !pluginPreferenceAllowed(file, key) ||
                    key.contains("cache", true) || key.contains("logcat", true)) continue
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

    private fun refreshNativeBackup(
        context: Context, file: String, backupKey: String, completeKey: String, hashKey: String
    ) {
        val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
        val payload = JSONObject()
        for ((key, value) in prefs.all) {
            if (value is Set<*>) {
                payload.put(key, JSONArray(value.toList()))
            } else if (value != null) {
                payload.put(key, value)
            }
        }
        val json = payload.toString()
        check(prefs.edit().putBoolean(completeKey, true)
            .putString(hashKey, json.hashCode().toString()).commit())
        with(DataStore) { context.setKey(backupKey, json) }
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
        val profileIndices = DataStoreHelper.getAccounts(context)
            .mapTo(mutableSetOf()) { it.keyIndex }
        for ((key, value) in changes) {
            when {
                key.startsWith("d/") -> {
                    val original = key.removePrefix("d/")
                    if (!dataAllowed(original) ||
                        !belongsToInstalledProfile(original, profileIndices)) continue
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
                        prefKey.isBlank() || !pluginPreferenceAllowed(name, prefKey)) continue
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
        // Keep native import snapshots consistent with restored plugin settings.
        if (changes.keys.any { it.startsWith("p/StremioC/") }) {
            refreshNativeBackup(context, "StremioC", "native_backup_stremioc",
                "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash")
        }
        if (changes.keys.any { it.startsWith("p/StreamPlay/") }) {
            refreshNativeBackup(context, "StreamPlay", "native_backup_streamplay",
                "streamplay_restore_complete", "streamplay_last_applied_backup_hash")
        }
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
        // Include CloudStream's built-in repositories as well as user-added ones:
        // extensions from prebuilt repos otherwise have no recoverable repo URL.
        val repos = (RepositoryManager.getRepositories().toList() +
            RepositoryManager.PREBUILT_REPOSITORIES.toList()).distinctBy { it.url }
        val items = JSONArray()
        for (plugin in PluginManager.getPluginsOnline()) {
            if (plugin.internalName.equals("SyncCS", true)) continue
            val repo = repos.firstOrNull {
                val parent = File(plugin.filePath).parentFile?.name
                parent == PluginManager.getPluginSanitizedFileName(it.url)
            }?.url ?: ""
            val file = File(plugin.filePath)
            val sha = if (file.isFile && file.length() in 1L..MAX_FILE.toLong()) {
                GitHubDigest.sha256(file.readBytes())
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
                .put("file", file.name).put("sha", GitHubDigest.sha256(file.readBytes())))
        }
        return items.toString()
    }

}
