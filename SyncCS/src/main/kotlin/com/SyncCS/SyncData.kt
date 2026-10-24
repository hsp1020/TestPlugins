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
        "result_subscribed_state_data/", "video_pos_dur/", "download_header_cache/",
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
        // UUID marker and native import snapshots are device-side derived data.
        if (key.endsWith("/synccs_profile_uuid") ||
            key.endsWith("/native_backup_stremioc") ||
            key.endsWith("/native_backup_streamplay") ||
            key == "native_backup_stremioc" || key == "native_backup_streamplay") return false
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
        // Native import bookkeeping is recomputed by refreshNativeBackup after
        // a settings restore. Synchronizing it causes A/B restore ping-pong.
        if (key in setOf("stremio_c_restore_complete",
                "stremio_c_last_applied_backup_hash", "streamplay_restore_complete",
                "streamplay_last_applied_backup_hash")) return false
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

    internal fun referencedHeaderIds(keys: Collection<String>): Set<String> =
        keys.mapNotNull { key ->
            val marker = when {
                "/result_resume_watching_2/" in key -> "/result_resume_watching_2/"
                "/result_watch_state_data/" in key -> "/result_watch_state_data/"
                else -> return@mapNotNull null
            }
            key.substringAfter(marker).takeIf { it.toIntOrNull() != null }
        }.toSet()

    internal fun excludeUnreferencedHeaderRestores(
        local: Map<String, String>, apply: MutableMap<String, String?>,
        upload: Map<String, String?>
    ) {
        val effective = local.keys.toMutableSet()
        for ((key, value) in apply + upload) {
            if (value == null) effective.remove(key) else effective.add(key)
        }
        val referenced = referencedHeaderIds(effective)
        apply.keys.filter {
            it.startsWith("d/download_header_cache/") &&
                it.removePrefix("d/download_header_cache/") !in referenced
        }.forEach { apply.remove(it) }
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
    /** Keep both snapshot modes identical for generated, unreferenced header caches. */
    internal fun includeHeaderCache(key: String, referencedIds: Set<String>): Boolean =
        !key.startsWith("download_header_cache/") ||
            key.removePrefix("download_header_cache/") in referencedIds

    fun snapshot(context: Context): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        val data = with(DataStore) { context.getSharedPrefs() }
        refreshCineStreamKeys()
        val profileIndices = DataStoreHelper.getAccounts(context)
            .mapTo(mutableSetOf()) { it.keyIndex }
        val stored = data.all
        val headerIds = referencedHeaderIds(stored.keys.filter {
            it.substringBefore('/').toIntOrNull() in profileIndices
        })
        for ((name, value) in stored) {
            if (!includeHeaderCache(name, headerIds)) continue
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


    /**
     * Read only explicit tracked keys during incremental reconciliation.
     * Unknown writes cannot bypass validation: a complete audit runs at app
     * startup, on first restore, on roster changes and periodically.
     *
     * The core SharedPreferences map is acquired once, never JSON-encoded in
     * its entirety. Missing values remain missing (not implicit deletions).
     */
    /**
     * SharedPreferences.all clones the entire preference map, even for one key.
     * Probe supported Android preference types directly. Wrong-type reads
     * throw ClassCastException; only selected keys pay this small cost.
     */
    internal fun selectedValue(prefs: SharedPreferences, key: String): Any? {
        if (!prefs.contains(key)) return null
        try { return prefs.getString(key, null) } catch (_: ClassCastException) {}
        try { return prefs.getLong(key, 0L) } catch (_: ClassCastException) {}
        try { return prefs.getInt(key, 0) } catch (_: ClassCastException) {}
        try { return prefs.getBoolean(key, false) } catch (_: ClassCastException) {}
        try { return prefs.getFloat(key, 0f) } catch (_: ClassCastException) {}
        return try { prefs.getStringSet(key, null) } catch (_: ClassCastException) { null }
    }

    /**
     * The native profile references are indexed by <slot>/<kind>/<item ID>.
     * For selected download-header entries, look up those two exact references
     * instead of enumerating every key stored in CloudStream.
     */
    internal fun selectedReferencedHeaders(
        prefs: SharedPreferences, installedSlots: Set<Int>, ids: Set<String>
    ): Set<String> = ids.filterTo(mutableSetOf()) { id ->
        id.toIntOrNull() != null && installedSlots.any { slot ->
            prefs.contains("$slot/result_resume_watching_2/$id") ||
                prefs.contains("$slot/result_watch_state_data/$id")
        }
    }

    fun snapshotSelected(context: Context, nativePaths: Collection<String>): MutableMap<String, String> {
        val result = linkedMapOf<String, String>()
        if (nativePaths.isEmpty()) return result
        val started = android.os.SystemClock.elapsedRealtime()
        refreshCineStreamKeys()
        val dataPaths = nativePaths.filter { it.startsWith("d/") && isTracked(it) }
        val defaultPaths = nativePaths.filter { it.startsWith("s/") && isTracked(it) }
        val pluginPaths = nativePaths.filter { it.startsWith("p/") && isTracked(it) }
        if (dataPaths.isNotEmpty()) {
            val indices = DataStoreHelper.getAccounts(context)
                .mapTo(mutableSetOf()) { it.keyIndex }
            val prefs = with(DataStore) { context.getSharedPrefs() }
            // Reference lookup is constant-time per selected header. Never
            // clone the complete 25k-entry map for an orphaned header marker.
            val headers = dataPaths.asSequence()
                .filter { it.startsWith("d/download_header_cache/") }
                .map { it.removePrefix("d/download_header_cache/") }
                .toSet()
            val referencedHeaders = selectedReferencedHeaders(prefs, indices, headers)
            for (path in dataPaths) {
                val key = path.removePrefix("d/")
                if (!belongsToInstalledProfile(key, indices) || key.length > 500 ||
                    !includeHeaderCache(key, referencedHeaders)) continue
                val raw = selectedValue(prefs, key) ?: continue
                valueJson(raw)?.takeIf { it.length <= 524288 }?.let { result[path] = it }
            }
        }
        if (defaultPaths.isNotEmpty()) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            for (path in defaultPaths) {
                val raw = selectedValue(prefs, path.removePrefix("s/")) ?: continue
                valueJson(raw)?.let { result[path] = it }
            }
        }
        if (pluginPaths.isNotEmpty()) {
            val validFiles = pluginFiles(context).toSet()
            val grouped = pluginPaths.groupBy {
                it.removePrefix("p/").substringBefore('/')
            }
            for ((name, paths) in grouped) {
                if (name !in validFiles) continue
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                for (path in paths) {
                    val key = path.removePrefix("p/$name/")
                    val raw = selectedValue(prefs, key) ?: continue
                    valueJson(raw)?.takeIf { it.length <= 524288 }?.let { result[path] = it }
                }
            }
        }
        SyncTrace.info("selected-local-read",
            "requested=${nativePaths.size} data=${dataPaths.size} " +
                "defaults=${defaultPaths.size} pluginPrefs=${pluginPaths.size} " +
                "found=${result.size} headerCandidates=${dataPaths.count {
                    it.startsWith("d/download_header_cache/")
                }} " +
                "headerLookupAll=false elapsedMs=${android.os.SystemClock.elapsedRealtime() - started}")
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

    fun apply(context: Context, changes: Map<String, String?>,
              refreshNativeSnapshots: Boolean = true) {
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
        if (refreshNativeSnapshots && changes.keys.any { it.startsWith("p/StremioC/") }) {
            refreshNativeBackup(context, "StremioC", "native_backup_stremioc",
                "stremio_c_restore_complete", "stremio_c_last_applied_backup_hash")
        }
        if (refreshNativeSnapshots && changes.keys.any { it.startsWith("p/StreamPlay/") }) {
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

    /** Verify .cs3 content without allocating whole extension packages in Java heap. */
    private fun sha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered(64 * 1024).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private suspend fun ensureLoaded(context: Context, name: String, path: String, url: String? = null): Boolean {
        val current = PluginManager.plugins[path]
        val instance = if (current != null) current else {
            val apiName = name.replace("provider", "", ignoreCase = true)
            if (!PluginManager.loadSinglePlugin(context, apiName)) return false
            PluginManager.plugins[path] ?: return false
        }
        if (url != null) {
            synchronized(PluginManager.urlPlugins) { PluginManager.urlPlugins[url] = instance }
        }
        return true
    }

    fun snapshot(context: Context): String {
        val scanStart = android.os.SystemClock.elapsedRealtime()
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
                sha256(file)
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
                .put("file", file.name).put("sha", sha256(file)))
        }
        val duration = android.os.SystemClock.elapsedRealtime() - scanStart
        SyncTrace.info("plugin-inventory-scan", "count=${items.length()} elapsedMs=$duration")
        return items.toString()
    }

    fun uploadFiles(context: Context, backend: SyncStorage, manifest: String) {
        val config = SyncConfiguration(context)
        val registry = config.preferences
        val entries = PluginSync.items(manifest)
        val online = PluginManager.getPluginsOnline().toList()
        val local = PluginManager.getPluginsLocal().toList()
        for (index in 0 until entries.length()) {
            val row = entries.getJSONObject(index)
            val hash = row.optString("sha")
            if (!hash.matches(Regex("[a-f0-9]{64}")) ||
                (backend !is GithubSyncAdapter && registry.getBoolean("file_" + hash, false))) continue
            val plugin = if (row.optString("type") == "online") {
                val repoId = row.optString("repo")
                online.firstOrNull {
                    it.internalName == row.optString("name") &&
                        it.url == row.optString("url") &&
                        (repoId.isBlank() || File(it.filePath).parentFile?.name ==
                            PluginManager.getPluginSanitizedFileName(repoId))
                }
            } else {
                local.firstOrNull {
                    it.internalName == row.optString("name") &&
                        File(it.filePath).name == row.optString("file")
                }
            }
            val file = plugin?.filePath?.let(::File) ?: continue
            if (!file.isFile || file.length() !in 1L..MAX_FILE.toLong()) continue
            val bytes = file.readBytes()
            // The object path is content-addressed; never write a differently
            // named or outdated binary under the manifest's hash.
            if (SyncBackend.digestBytes(bytes) != hash) {
                if (row.optString("type") == "local") {
                    error("Local .cs3 changed during backup: " + row.optString("name"))
                }
                continue
            }
            try {
                backend.uploadFile(hash, bytes)
                if (backend !is GithubSyncAdapter) {
                    registry.edit().putBoolean("file_" + hash, true).apply()
                }
            } catch (error: Exception) {
                if (row.optString("type") == "local") throw error
                // Online extensions can also be fetched from their repository.
            }
        }
    }

    suspend fun restore(context: Context, activity: Activity, backend: SyncStorage, raw: String): List<String> {
        val report = mutableListOf<String>()
        val rows = PluginSync.items(raw)
        val target = PluginSync.decode(raw).items

        // Use CloudStream's own uninstall path so both the binary and the plugin
        // registry change together. The merge has already decided which entries
        // are intentional deletions; never delete SyncCS itself.
        val current = PluginSync.decode(snapshot(context)).items
        // Fail closed before the first uninstall. This guarantees that
        // every binary to be replaced is already available and SHA-verified.
        for ((id, item) in target) {
            val desiredHash = item.optString("sha")
            if (current[id]?.optString("sha") == desiredHash &&
                desiredHash.matches(Regex("[0-9a-f]{64}"))) continue
            if (!desiredHash.matches(Regex("[0-9a-f]{64}"))) {
                return listOf("Unverifiable plugin backup: " + item.optString("name"))
            }
            val bytes = try {
                backend.downloadFile(desiredHash)
            } catch (error: Exception) {
                return listOf("Plugin archive is missing: " + item.optString("name"))
            }
            if (SyncBackend.digestBytes(bytes) != desiredHash) {
                return listOf("Plugin archive digest mismatch: " + item.optString("name"))
            }
        }
        val existingLocal = PluginManager.getPluginsLocal()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val name = row.optString("name")
            if (name.isBlank() || name.equals("SyncCS", true)) continue
            val hash = row.optString("sha")
            if (row.optString("type") == "online") {
                val url = row.optString("url")
                val repo = row.optString("repo")
                val existing = PluginManager.getPluginsOnline().firstOrNull {
                    it.internalName == name && it.url == url &&
                        File(it.filePath).isFile &&
                        (hash.isBlank() ||
                            sha256(File(it.filePath)) == hash)
                }
                if (existing != null) {
                    if (!ensureLoaded(activity, name, existing.filePath, url)) {
                        report.add("Could not load " + name)
                    }
                    continue
                }
                var installed = false
                if (url.startsWith("https://") && repo.startsWith("https://")) {
                    if ((RepositoryManager.getRepositories().toList() +
                            RepositoryManager.PREBUILT_REPOSITORIES.toList()).none { it.url == repo }) {
                        RepositoryManager.addRepository(RepositoryData("Restored for " + name, repo))
                    }
                    try {
                        installed = PluginManager.downloadPlugin(
                            activity, url,
                            hash.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let { "sha256-" + it },
                            name, repo, true
                        ) && ensureLoaded(activity, name,
                            PluginManager.getPluginPath(context, name, repo).absolutePath, url)
                    } catch (_: Exception) { }
                }
                if (!installed && hash.matches(Regex("[a-f0-9]{64}")) && repo.isNotBlank()) {
                    try {
                        val bytes = backend.downloadFile(hash)
                        if (SyncBackend.digestBytes(bytes) == hash) {
                            val path = PluginManager.getPluginPath(context, name, repo)
                            path.parentFile?.mkdirs()
                            val temp = File(path.parentFile, path.name + ".temp")
                            val backup = File(path.parentFile, path.name + ".synccs-old")
                            temp.writeBytes(bytes)
                            if (backup.exists() && LocalSyncJournal.pending(context)) {
                                // A previous crash may have left this temporary
                                // file behind. The active, verified undo journal
                                // contains the original archive bytes.
                                check(backup.delete()) {
                                    "Cannot clear abandoned plugin swap backup: " + name
                                }
                            }
                            check(!backup.exists()) { "Unrecovered plugin backup: " + name }
                            val prior = PluginManager.getPluginsOnline()
                                .firstOrNull { it.filePath == path.absolutePath }
                            val hadFile = path.exists()
                            if (hadFile) check(path.renameTo(backup))
                            try {
                                PluginManager.unloadPlugin(path.absolutePath)
                                check(temp.renameTo(path))
                                val meta = PluginData(name, url, true, path.absolutePath, row.optInt("version"))
                                val current = PluginManager.getPluginsOnline()
                                    .filter { it.filePath != path.absolutePath }
                                com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                    PLUGINS_KEY, (current + meta).toTypedArray())
                                check(ensureLoaded(activity, name, path.absolutePath, url))
                                installed = true
                                backup.delete()
                            } catch (error: Exception) {
                                PluginManager.unloadPlugin(path.absolutePath)
                                path.delete()
                                if (hadFile) check(backup.renameTo(path))
                                val current = PluginManager.getPluginsOnline()
                                    .filter { it.filePath != path.absolutePath }
                                com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                    PLUGINS_KEY, (current + listOfNotNull(prior)).toTypedArray())
                                if (hadFile && prior != null) {
                                    ensureLoaded(activity, name, path.absolutePath, prior.url)
                                }
                                throw error
                            } finally {
                                temp.delete()
                            }
                        }
                    } catch (_: Exception) { }
                }
                if (!installed) report.add(
                    if (repo.isBlank()) "Missing repository URL for " + name
                    else "Could not install " + name
                )
            } else {
                val filename = row.optString("file").substringAfterLast('/').substringAfterLast('\\')
                val existing = existingLocal.firstOrNull {
                    it.internalName == name && File(it.filePath).name == filename &&
                        File(it.filePath).isFile &&
                        sha256(File(it.filePath)) == hash
                }
                if (existing != null) {
                    if (!ensureLoaded(activity, name, existing.filePath)) {
                        report.add("Could not load local .cs3: " + name)
                    }
                    continue
                }
                if (!hash.matches(Regex("[a-f0-9]{64}"))) {
                    report.add("No backup for local plugin " + name)
                    continue
                }
                try {
                    val bytes = backend.downloadFile(hash)
                    check(SyncBackend.digestBytes(bytes) == hash)
                    check(filename.endsWith(".cs3", true) && filename.length < 120)
                    val destination = try {
                        val directory = File(Environment.getExternalStorageDirectory(), "Cloudstream3/plugins")
                        check(directory.exists() || directory.mkdirs())
                        val output = File(directory, filename)
                        output.writeBytes(bytes)
                        output
                    } catch (_: Exception) {
                        val directory = File(context.filesDir, "synccs-local")
                        check(directory.exists() || directory.mkdirs())
                        val output = File(directory, filename)
                        output.writeBytes(bytes)
                        output
                    }
                    check(SyncBackend.digestBytes(destination.readBytes()) == hash)
                    val original = PluginManager.getPluginsLocal()
                        .filter { it.filePath != destination.absolutePath }
                    val archived = PluginData(
                        name, "", false, destination.absolutePath, row.optInt("version", 0)
                    )
                    com.lagradost.cloudstream3.CloudStreamApp.setKey(
                        com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL,
                        (original + archived).toTypedArray()
                    )
                    PluginManager.unloadPlugin(destination.absolutePath)
                    check(ensureLoaded(activity, name, destination.absolutePath))
                } catch (_: Exception) {
                    report.add("Could not restore local .cs3: " + name)
                }
            }
        }
        // Only uninstall obsolete extensions after all replacements are verified.
        if (report.isNotEmpty()) return report
        val onlineNow = PluginManager.getPluginsOnline().toList()
        val localNow = PluginManager.getPluginsLocal().toList()
        for ((key, row) in current) {
            if (key in target || row.optString("name").equals("SyncCS", true)) continue
            val name = row.optString("name")
            val installed = if (row.optString("type") == "online") {
                val repoId = row.optString("repo")
                onlineNow.firstOrNull {
                    it.internalName == name && it.url == row.optString("url") &&
                        (repoId.isBlank() || File(it.filePath).parentFile?.name ==
                            PluginManager.getPluginSanitizedFileName(repoId))
                }
            } else {
                localNow.firstOrNull {
                    it.internalName == name &&
                        File(it.filePath).name == row.optString("file")
                }
            }
            if (installed != null) {
                try {
                    val path = File(installed.filePath)
                    if (!PluginManager.deletePlugin(path)) {
                        if (!path.exists()) {
                            val installedOnline = PluginManager.getPluginsOnline()
                                .filter { it.filePath != installed.filePath }
                            val installedLocal = PluginManager.getPluginsLocal()
                                .filter { it.filePath != installed.filePath }
                            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                PLUGINS_KEY, installedOnline.toTypedArray()
                            )
                            com.lagradost.cloudstream3.CloudStreamApp.setKey(
                                com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL,
                                installedLocal.toTypedArray()
                            )
                        } else report.add("Could not delete " + name)
                    }
                } catch (_: Exception) {
                    report.add("Could not delete " + name)
                } catch (_: LinkageError) {
                    report.add("Unsupported uninstall for " + name)
                }
            }
        }

        return report
    }
}
