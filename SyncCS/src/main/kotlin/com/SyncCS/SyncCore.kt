package com.SyncCS

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// =========================================================================
// [1] SharedPreferences Helper (Exact Jackson Handling)
// =========================================================================
object SharedPrefsHelper {
    private val mapper = jacksonObjectMapper()

    fun getPrefs(context: Context? = null): SharedPreferences {
        val ctx = context ?: AcraApplication.context ?: CloudStreamApp.context ?: throw IllegalStateException("Context is null")
        return with(DataStore) {
            ctx.getSharedPrefs()
        }
    }

    fun getDefaultPrefs(context: Context? = null): SharedPreferences {
        val ctx = context ?: AcraApplication.context ?: CloudStreamApp.context ?: throw IllegalStateException("Context is null")
        return with(DataStore) {
            ctx.getDefaultSharedPrefs()
        }
    }

    fun <T> getKey(key: String, clazz: Class<T>, defValue: T? = null, context: Context? = null): T? {
        return try {
            val pref = getPrefs(context)
            val json = pref.getString(key, null) ?: return defValue
            mapper.readValue(json, clazz)
        } catch (e: Exception) {
            defValue
        }
    }

    fun setKey(key: String, value: Any?, context: Context? = null) {
        try {
            val pref = getPrefs(context)
            if (value == null) {
                pref.edit().remove(key).apply()
            } else {
                val json = mapper.writeValueAsString(value)
                pref.edit().putString(key, json).apply()
            }
        } catch (e: Exception) {
            Log.e("SyncCS", "Failed to save key: $key", e)
        }
    }
}

// =========================================================================
// [2] Models Area
// =========================================================================
@JsonIgnoreProperties(ignoreUnknown = true)
data class APIRes(
    @JsonProperty("data") var data: Data? = null,
    @JsonProperty("errors") var errors: Array<Error>? = null
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Data(
        @JsonProperty("viewer") var viewer: Viewer? = null,
        @JsonProperty("addProjectV2DraftIssue") var issue: Issue? = null,
        @JsonProperty("deleteProjectV2Item") var delItem: DelItem? = null
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Viewer(@JsonProperty("projectV2") var projectV2: ProjectV2? = null) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class ProjectV2(@JsonProperty("id") var id: String? = null, @JsonProperty("items") var items: Items? = null) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                data class Items(@JsonProperty("nodes") var nodes: Array<Node>? = null) {
                    @JsonIgnoreProperties(ignoreUnknown = true)
                    data class Node(@JsonProperty("id") var id: String? = null, @JsonProperty("content") var content: Content? = null) {
                        @JsonIgnoreProperties(ignoreUnknown = true)
                        data class Content(
                            @JsonProperty("id") var id: String? = null,
                            @JsonProperty("title") var title: String? = null,
                            @JsonProperty("bodyText") var bodyText: String? = null,
                            @JsonProperty("body") var body: String? = null
                        )
                    }
                }
            }
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Issue(@JsonProperty("projectItem") var projectItem: ProjectItem? = null) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class ProjectItem(@JsonProperty("id") var id: String? = null, @JsonProperty("content") var content: Content? = null) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                data class Content(@JsonProperty("id") var id: String? = null)
            }
        }
        @JsonIgnoreProperties(ignoreUnknown = true)
        data class DelItem(@JsonProperty("deletedItemId") var deletedItemId: String? = null)
    }
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Error(@JsonProperty("message") var message: String? = null)
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupFile(
    @JsonProperty("datastore") var datastore: BackupVars,
    @JsonProperty("settings") var settings: BackupVars
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class BackupVars(
    @JsonProperty("_Bool") var bool: Map<String, Boolean>? = null,
    @JsonProperty("_Int") var int: Map<String, Int>? = null,
    @JsonProperty("_String") var string: Map<String, String>? = null,
    @JsonProperty("_Float") var float: Map<String, Float>? = null,
    @JsonProperty("_Long") var long: Map<String, Long>? = null,
    @JsonProperty("_StringSet") var stringSet: Map<String, Set<String>>? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SyncDevice(
    @JsonProperty("name") var name: String,
    @JsonProperty("deviceId") var deviceId: String,
    @JsonProperty("itemId") var itemId: String,
    @JsonProperty("syncedData") var syncedData: String? = null
)

// =========================================================================
// [3] Util Area (GZIP with plain Base64 Fallback Decoder + HTML entity unescaping)
// =========================================================================
object Util {
    private val minifyDict = listOf(
        "/result_resume_watching_2/" to "/k1/", "/video_pos_dur/" to "/k2/", "download_header_cache/" to "/k3/",
        "/result_watch_state/" to "/k4/", "/result_watch_state_data/" to "/k5/", "/result_season/" to "/k6/",
        "/result_episode/" to "/k7/", "/result_dub/" to "/k8/", "/search_history/" to "/k9/",
        "/video_quality_priority/" to "/k10/", "/video_profile_name/" to "/k11/", "/home_bookmarked_last_list" to "/k12",
        "user_custom_sites" to "k13", "/search_pref_tags" to "/k14", "/search_pref_providers" to "/k15",
        "resize_mode" to "k16", "/duration_mode" to "/k17", "REPOSITORIES_KEY" to "k18", "subtitle_settings" to "k19",
        "/results_sorting_mode" to "/k20", "/home_pref_homepage" to "/k21", "/playback_speed" to "/k22",
        "fshare_username" to "k23", "fshare_password" to "k24", "monplayer_links" to "k25", "xtream_iptv_links" to "k26",
        "iptv_links" to "k27", "/library_sorting_mode" to "/k28", "subs_auto_select" to "k29",
        "\\\"bookmarkedTime\\\"" to "\\\"v1\\\"", "\\\"latestUpdatedTime\\\"" to "\\\"v2\\\"", "\\\"name\\\"" to "\\\"v3\\\"",
        "\\\"url\\\"" to "\\\"v4\\\"", "\\\"type\\\"" to "\\\"v5\\\"", "\\\"apiName\\\"" to "\\\"v6\\\"",
        "\\\"posterUrl\\\"" to "\\\"v7\\\"", "\\\"year\\\"" to "\\\"v8\\\"", "\\\"syncData\\\"" to "\\\"v9\\\"",
        "\\\"quality\\\"" to "\\\"v10\\\"", "\\\"posterHeaders\\\"" to "\\\"v11\\\"", "\\\"plot\\\"" to "\\\"v12\\\"",
        "\\\"rating\\\"" to "\\\"v13\\\"", "\\\"tags\\\"" to "\\\"v14\\\"", "\\\"searchedAt\\\"" to "\\\"v15\\\"",
        "\\\"searchText\\\"" to "\\\"v16\\\"", "\\\"parentId\\\"" to "\\\"v17\\\"", "\\\"episodeId\\\"" to "\\\"v18\\\"",
        "\\\"episode\\\"" to "\\\"v19\\\"", "\\\"season\\\"" to "\\\"v20\\\"", "\\\"updateTime\\\"" to "\\\"v21\\\"",
        "\\\"isFromDownload\\\"" to "\\\"v22\\\"", "\\\"position\\\"" to "\\\"v23\\\"", "\\\"duration\\\"" to "\\\"v24\\\"",
        "\\\"mainUrl\\\"" to "\\\"v25\\\"", "\\\"searchUrl\\\"" to "\\\"v26\\\"", "\\\"cacheTime\\\"" to "\\\"v27\\\"",
        "\\\"title\\\"" to "\\\"v28\\\"", "\\\"poster\\\"" to "\\\"v29\\\"", "\\\"TvSeries\\\"" to "\\\"v30\\\"",
        "\\\"Movie\\\"" to "\\\"v31\\\"", "\\\"NSFW\\\"" to "\\\"v32\\\"", "\\\"Anime\\\"" to "\\\"v33\\\"",
        "\\\"Live\\\"" to "\\\"v34\\\"", "\\\"AnimeMovie\\\"" to "\\\"v35\\\"", "\\\"AsianDrama\\\"" to "\\\"v36\\\"",
        "\\\"Cartoon\\\"" to "\\\"v37\\\"", "\\\"Torrent\\\"" to "\\\"v38\\\"", "\\\"slug\\\"" to "\\\"v39\\\"",
        "\\\"https://" to "\\\"h//", "\\\":null," to "\\\":n,", "\\\":false}" to "\\\":f}", "\\\":true}" to "\\\":t}",
        "\"_Bool\"" to "\"s1\"", "\"_Int\"" to "\"s2\"", "\"_String\"" to "\"s3\"", "\"_Float\"" to "\"s4\"",
        "\"_Long\"" to "\"s5\"", "\"_StringSet\"" to "\"s6\"", "\"swipe_enabled_key\"" to "\"s7\"",
        "\"skip_startup_account_select_key\"" to "\"s8\"", "\"auto_update\"" to "\"s9\"", "\"rotate_video_key\"" to "\"s10\"",
        "\"auto_download_plugins_key\"" to "\"s11\"", "\"auto_update_plugins\"" to "\"s12\"", "\"player_resize_enabled_key\"" to "\"s13\"",
        "\"bg_startup_tracing\"" to "\"s14\"", "\"playback_speed_enabled_key\"" to "\"s15\"", "\"acra.disable\"" to "\"s16\"",
        "\"enable_nsfw_on_providers_key\"" to "\"s17\"", "\"preview_seekbar_key\"" to "\"s18\"", "\"show_trailers_key\"" to "\"s19\"",
        "\"show_fillers_key\"" to "\"s20\"", "\"random_button_key\"" to "\"s21\"", "\"swipe_vertical_enabled_key\"" to "\"s22\"",
        "\"bottom_title_key\"" to "\"s23\"", "\"auto_rotate_video_key\"" to "\"s24\"", "\"advanced_search\"" to "\"s25\"",
        "\"speedup_key\"" to "\"s26\"", "\"episode_sync_enabled_key\"" to "\"s27\"", "\"hide_player_control_names_key\"" to "\"s28\"",
        "\"show_kitsu_posters_key\"" to "\"s29\"", "\"double_tap_enabled_key\"" to "\"s30\"", "\"pip_enabled_key\"" to "\"s31\"",
        "\"double_tap_pause_enabled_key\"" to "\"s32\"", "\"enable_skip_op_from_database\"" to "\"s33\"", "\"autoplay_next_key\"" to "\"s34\"",
        "\"android_tv_interface_off_seek_key\"" to "\"s35\"", "\"DNS over HTTPS\"" to "\"s36\"", "\"double_tap_seek_time_key2\"" to "\"s37\"",
        "\"auto_download_plugins_key2\"" to "\"s38\"", "\"automatic_backup_key\"" to "\"s39\"", "\"download_concurrent_key\"" to "\"s40\"",
        "\"com.facebook.appevents.SessionInfo.interruptionCount\"" to "\"s41\"", "\"download_parallel_key\"" to "\"s42\"",
        "\"android_tv_interface_on_seek_key\"" to "\"s43\"", "\"app_layout_key\"" to "\"s44\"", "\"backup_dir_key\"" to "\"s45\"",
        "\"com.applovin.sdk.impl.isFirstRun\"" to "\"s46\"", "\"com.facebook.appevents.SessionInfo.sessionId\"" to "\"s47\"",
        "\"primary_color_key\"" to "\"s48\"", "\"app_theme_key\"" to "\"s49\"", "\"app_locale\"" to "\"s50\"",
        "\"com.facebook.appevents.SessionInfo.sessionEndTime\"" to "\"s51\"", "\"com.facebook.appevents.SessionInfo.sessionStartTime\"" to "\"s52\"",
        "\"prefer_media_type_key_2\"" to "\"s53\"", "\"datastore\"" to "\"s54\""
    )

    fun cleanedSyncData(str: String): String {
        return try {
            var result = str
            result = Regex("""\\\"overview\\\"\s*:\s*\\\"(.*?)(?<!\\)\\\"""").replace(result, """\\\"overview\\\":\\\"\\\"""")
            result = Regex("""\\\"plot\\\"\s*:\s*\\\"(.*?)(?<!\\)\\\"""").replace(result, """\\\"plot\\\":\\\"\\\"""")
            result = Regex("""\\\"tags\\\"\s*:\s*\[.*?]""").replace(result, """\\\"tags\\\":[]""")

            for ((old, new) in minifyDict) {
                result = result.replace(old, new)
            }
            result
        } catch (e: Exception) {
            str
        }
    }

    fun restoreText(str: String): String {
        return try {
            var result = str
            for ((old, new) in minifyDict) {
                result = result.replace(new, old)
            }
            result
        } catch (e: Exception) {
            str
        }
    }

    fun compressJsonToString(str: String): String {
        return try {
            val bos = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(bos).use { gzos ->
                gzos.write(str.toByteArray(UTF_8))
            }
            Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            Log.e("SyncCS", "GZIP Compression failed. Falling back to Base64.", e)
            val bytes = str.toByteArray(UTF_8)
            Base64.encodeToString(bytes, 10)
        }
    }

    fun decompressJsonFromString(str: String): String {
        // Strip HTML entities first (e.g. &quot; -> ", &amp; -> &) to preserve Base64 alphabets
        val unescaped = try {
            android.text.Html.fromHtml(str, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
        } catch (e: NoSuchMethodError) {
            @Suppress("DEPRECATION")
            android.text.Html.fromHtml(str).toString()
        } catch (e: Exception) {
            str
        }

        // Clean remaining HTML tags and any whitespaces
        val cleanStr = unescaped.replace(Regex("<[^>]*>"), "").replace(Regex("\\s+"), "").trim()

        return try {
            val bytes = Base64.decode(cleanStr, Base64.NO_WRAP or Base64.NO_PADDING)
            val bis = java.io.ByteArrayInputStream(bytes)
            java.util.zip.GZIPInputStream(bis).bufferedReader(UTF_8).use { it.readText() }
        } catch (e: Exception) {
            try {
                val bytes = Base64.decode(cleanStr, 10)
                String(bytes, UTF_8)
            } catch (ex: Exception) {
                Log.e("SyncCS", "All decompression methods failed.", ex)
                ""
            }
        }
    }

    fun encrypt(str: String, keySeed: String): String {
        return str
    }

    fun decrypt(str: String, keySeed: String): String {
        return str
    }

    fun getDeviceId(context: Context? = null): String {
        val ctx = context ?: AcraApplication.context ?: CloudStreamApp.context ?: return ""
        val androidId = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(ctx.packageName + androidId)
        val fallback = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        return md5(ctx.packageName + UUID.nameUUIDFromBytes(fallback.toByteArray(UTF_8)).toString())
    }

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// =========================================================================
// [4] BackupUtils Area (Optimized Filter Controls)
// =========================================================================
object BackupUtils {
    private val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list", "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token", "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active",
        "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC", "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active", "biometric_key", "nginx_user", "download_path_key",
        "download_path_key_visual", "backup_path_key", "backup_dir_path_key", "PLUGINS_KEY", "PLUGINS_KEY_LOCAL", "cs3-votes",
        "last_sync_api", "last_click_action", "last_opened_id", "library_folder", "result_resume_watching_migrated",
        "jsdelivr_proxy_key", "fshare_setup", "fshare_token", "checkbox_fsharehd", "checkbox_fsharecine",
        "checkbox_fsharesheet", "checkbox_fsharefavourite", "checkbox_fsharehdvn", "checkbox_timfshare",
        "bluphim_token", "device_id", "device_name", "sync_token", "sync_project_num", "sync_project_id",
        "sync_item_id", "sync_device_id", "restore_device", "backup_device", "sync_plugins_enabled", "movie_active", "dns_active",
        "last_sync_timestamp",
        "ULTIMA_CURRENT_META_PROVIDERS", "ULTIMA_EXTENSIONS_LIST", "ULTIMA_CURRENT_MEDIA_PROVIDERS", "ULTIMA_WATCH_SYNC_CREDS",
        "used_fstream_providers_v3", "fstream_version", "Aniyomi_Plugin_Successful_Install", "enable_support_by_bongngotv",
        "download_info", "download_resume", "download_q_resume", "download_episode_cache", "download_header_cache",
        "prerelease_update", "data_store_helper/account_key_index", "VERSION_NAME", "FILES_TO_DELETE_KEY", 
        "HAS_DONE_SETUP", "video_source_priority", "preferred_audio_language"
    )

    fun isResume(key: String, syncPlugins: Boolean = false): Boolean {
        if (key.contains("PLUGINS_KEY") || key.contains("PLUGINS_KEY_LOCAL")) {
            return syncPlugins
        }
        
        if (key.contains("cache") || key.contains("CACHE") || key.contains("download_")) return false
        if (key.startsWith("cat_") || key.contains("cat_toggle") || key.contains("cat_order") || key.contains("cat_rename")) return false
        
        return nonTransferableKeys.none { key.contains(it) }
    }

    fun isBackup(key: String, resumeWatching: List<Any>? = null, syncPlugins: Boolean = false): Boolean {
        if (!isResume(key, syncPlugins)) return false
        
        if (key.contains("result_resume_watching") || key.contains("result_watch_state") || 
            key.contains("result_season") || key.contains("result_episode") || key.contains("result_dub")) {
            if (resumeWatching == null) return false
            
            val parts = key.split("/")
            if (parts.size < 2) return false
            val mediaId = parts.lastOrNull() ?: return false
            
            return resumeWatching.any { item ->
                item.toString().contains(mediaId)
            }
        }
        return true
    }

    fun getFullBackup(context: Context, resumeWatching: List<Any>? = null, syncPlugins: Boolean = false): BackupFile? {
        val pref = SharedPrefsHelper.getPrefs(context)
        val defaultPref = SharedPrefsHelper.getDefaultPrefs(context)

        val rawPrefs = pref.all.filter { isBackup(it.key, resumeWatching, syncPlugins) }
        val validPrefs = rawPrefs.mapValues { entry ->
            val value = entry.value
            if (value is String && (entry.key.contains("result_resume_watching") || entry.key.contains("result_watch_state"))) {
                try {
                    val jsonObj = JSONObject(value)
                    jsonObj.remove("overview")
                    jsonObj.remove("plot")
                    jsonObj.remove("tags")
                    jsonObj.toString()
                } catch (e: Exception) {
                    value
                }
            } else {
                value
            }
        }
        val validDefaultPrefs = defaultPref.all.filter { isBackup(it.key, resumeWatching, syncPlugins) }

        if (validPrefs.isEmpty() && validDefaultPrefs.isEmpty()) return null

        fun splitVars(map: Map<String, Any?>): BackupVars {
            return BackupVars(
                bool = map.filterValues { it is Boolean }.mapValues { it.value as Boolean },
                int = map.filterValues { it is Int }.mapValues { it.value as Int },
                string = map.filterValues { it is String }.mapValues { it.value as String },
                float = map.filterValues { it is Float }.mapValues { it.value as Float },
                long = map.filterValues { it is Long }.mapValues { it.value as Long },
                stringSet = map.filterValues { it is Set<*> }.mapValues { @Suppress("UNCHECKED_CAST") (it.value as Set<String>) }
            )
        }

        return BackupFile(
            datastore = splitVars(validPrefs),
            settings = splitVars(validDefaultPrefs)
        )
    }

    fun restoreMap(context: Context, incoming: Map<String, out Any?>?, isAppSettings: Boolean, syncPlugins: Boolean = false) {
        if (incoming.isNullOrEmpty()) return
        val pref = if (isAppSettings) SharedPrefsHelper.getDefaultPrefs(context) else SharedPrefsHelper.getPrefs(context)
        val editor = pref.edit()

        Log.d("SyncCS", "Restoring ${incoming.size} keys to ${if(isAppSettings) "Settings" else "DataStore"}")

        for ((key, incomingValue) in incoming) {
            if (!isResume(key, syncPlugins)) continue

            val localValue = pref.all[key]
            
            if (localValue != null && incomingValue is String && localValue is String) {
                val mergedValue = resolveConflict(localValue, incomingValue)
                editor.putString(key, mergedValue)
            } else {
                when (incomingValue) {
                    is Boolean -> editor.putBoolean(key, incomingValue)
                    is Int -> editor.putInt(key, incomingValue)
                    is String -> editor.putString(key, incomingValue)
                    is Float -> editor.putFloat(key, incomingValue)
                    is Long -> editor.putLong(key, incomingValue)
                    is Set<*> -> {
                        @Suppress("UNCHECKED_CAST")
                        editor.putStringSet(key, incomingValue as Set<String>)
                    }
                }
            }
        }
        editor.apply()
    }

    private fun resolveConflict(localStr: String, incomingStr: String): String {
        return try {
            val localObj = JSONObject(localStr)
            val incomingObj = JSONObject(incomingStr)
            val localTime = localObj.optLong("latestUpdatedTime", localObj.optLong("bookmarkedTime", 0L))
            val incomingTime = incomingObj.optLong("latestUpdatedTime", incomingObj.optLong("bookmarkedTime", 0L))
            
            if (incomingTime >= localTime) incomingStr else localStr
        } catch (e: Exception) {
            incomingStr
        }
    }

    fun restore(context: Context, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean, syncPlugins: Boolean = false) {
        if (restoreSettings) {
            restoreMap(context, backupFile.settings.bool, true, syncPlugins)
            restoreMap(context, backupFile.settings.int, true, syncPlugins)
            restoreMap(context, backupFile.settings.string, true, syncPlugins)
            restoreMap(context, backupFile.settings.float, true, syncPlugins)
            restoreMap(context, backupFile.settings.long, true, syncPlugins)
            restoreMap(context, backupFile.settings.stringSet, true, syncPlugins)
        }
        if (restoreDataStore) {
            restoreMap(context, backupFile.datastore.bool, false, syncPlugins)
            restoreMap(context, backupFile.datastore.int, false, syncPlugins)
            restoreMap(context, backupFile.datastore.string, false, syncPlugins)
            restoreMap(context, backupFile.datastore.float, false, syncPlugins)
            restoreMap(context, backupFile.datastore.long, false, syncPlugins)
            restoreMap(context, backupFile.datastore.stringSet, false, syncPlugins)
        }
    }
}

// =========================================================================
// [5] ApiUtils Area
// =========================================================================
object ApiUtils {
    private val mapper = jacksonObjectMapper()

    private val unsafeClient: OkHttpClient by lazy {
        try {
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
            })
            val sslContext = SSLContext.getInstance("SSL")
            sslContext.init(null, trustAllCerts, SecureRandom())
            OkHttpClient.Builder()
                .sslSocketFactory(sslContext.socketFactory, trustAllCerts[0] as X509TrustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } catch (e: Exception) { OkHttpClient() }
    }

    fun isLoggedIn(context: Context? = null): Boolean {
        val token = SharedPrefsHelper.getKey("sync_token", String::class.java, context = context)
        val projectNum = SharedPrefsHelper.getKey("sync_project_num", String::class.java, context = context)
        val projectId = SharedPrefsHelper.getKey("sync_project_id", String::class.java, context = context)
        return !token.isNullOrEmpty() && !projectNum.isNullOrEmpty() && !projectId.isNullOrEmpty()
    }

    suspend fun apiCall(query: String, token: String): APIRes? = withContext(Dispatchers.IO) {
        try {
            val formattedQuery = query.replace("\n", " ").replace(Regex("\\s+"), " ")
            val bodyMap = mapOf("query" to formattedQuery)
            val bodyStr = mapper.writeValueAsString(bodyMap)

            val requestBody = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.github.com/graphql")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Cloudstream-Sync")
                .post(requestBody)
                .build()

            val response = unsafeClient.newCall(request).execute()
            val code = response.code
            val responseBody = response.body?.string() ?: ""

            if (code != 200) {
                Log.e("SyncCS", "API Failed. HTTP Code: $code, Res: $responseBody")
                return@withContext null
            }

            val res = mapper.readValue(responseBody, APIRes::class.java)
            if (res.errors != null && res.errors!!.isNotEmpty()) {
                val errorMsg = res.errors!!.joinToString(", ") { it.message ?: "Unknown Error" }
                Log.e("SyncCS", "GitHub GraphQL Error: $errorMsg")
            }
            res
        } catch (e: Exception) {
            Log.e("SyncCS", "apiCall Error", e)
            null
        }
    }

    suspend fun syncProjectDetails(context: Context? = null): Pair<Boolean, String> {
        val token = SharedPrefsHelper.getKey("sync_token", String::class.java, context = context) ?: return Pair(false, "Missing token")
        val projectNumStr = SharedPrefsHelper.getKey("sync_project_num", String::class.java, context = context) ?: return Pair(false, "Missing project number")
        val projectNum = projectNumStr.toIntOrNull() ?: return Pair(false, "Invalid project number")

        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) {
                            nodes {
                                id
                                content { ... on DraftIssue { id title body } }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(query, token)
        if (res == null) return Pair(false, "Failed to connect to GitHub. Check Logcat or Network.")
        
        if (res.errors != null && res.errors!!.isNotEmpty()) {
            val errorMsg = res.errors!!.joinToString(", ") { it.message ?: "Unknown Error" }
            return Pair(false, "GitHub Error: $errorMsg")
        }

        val project = res.data?.viewer?.projectV2 ?: return Pair(false, "Project not found or lacks permissions.")
        val projectId = project.id ?: return Pair(false, "Project ID not found in response.")

        SharedPrefsHelper.setKey("sync_project_id", projectId, context)
        return Pair(true, "Successfully linked to GitHub Project!")
    }

    suspend fun syncThisDevice(syncData: String, context: Context? = null): Pair<Boolean, String> {
        val token = SharedPrefsHelper.getKey("sync_token", String::class.java, context = context) ?: return Pair(false, "Missing token")
        val projectId = SharedPrefsHelper.getKey("sync_project_id", String::class.java, context = context) ?: return Pair(false, "Missing Project ID")
        val deviceName = SharedPrefsHelper.getKey("device_name", String::class.java, context = context) ?: Build.MODEL
        val deviceId = Util.getDeviceId(context)

        val storedItemId = SharedPrefsHelper.getKey("sync_item_id", String::class.java, context = context)

        if (!storedItemId.isNullOrEmpty()) {
            val delQuery = """
                mutation {
                    deleteProjectV2Item(input: {projectId: "$projectId", itemId: "$storedItemId"}) {
                        deletedItemId
                    }
                }
            """.trimIndent()
            val delRes = apiCall(delQuery, token)
            if (delRes?.errors != null && delRes.errors!!.isNotEmpty()) {
                val errorMsg = delRes.errors!!.joinToString(", ") { it.message ?: "Unknown Error" }
                Log.w("SyncCS", "Item deletion warning: $errorMsg")
            }
        }

        val encryptedPayload = Util.encrypt(syncData, token)
        val escapedPayload = encryptedPayload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val escapedName = "$deviceName|$deviceId".replace("\"", "\\\"")

        val payloadLength = escapedPayload.length
        Log.d("SyncCS", "Uploading payload. Escaped Length: $payloadLength")

        val addQuery = """
            mutation {
                addProjectV2DraftIssue(input: {projectId: "$projectId", title: "$escapedName", body: "$escapedPayload"}) {
                    projectItem { id }
                }
            }
        """.trimIndent()

        val res = apiCall(addQuery, token)
        if (res == null) return Pair(false, "Network error or connection failed. (Payload: $payloadLength chars)")

        if (res.errors != null && res.errors!!.isNotEmpty()) {
            val errorMsg = res.errors!!.joinToString(", ") { it.message ?: "Unknown Error" }
            return Pair(false, "GitHub Error: $errorMsg")
        }

        val newItemId = res.data?.issue?.projectItem?.id
        if (!newItemId.isNullOrEmpty()) {
            SharedPrefsHelper.setKey("sync_item_id", newItemId, context)
            Log.d("SyncCS", "Sync completed successfully.")
            return Pair(true, "Sync completed successfully")
        }

        return Pair(false, "Failed to get project item ID from GitHub. (Payload: $payloadLength chars)")
    }

    suspend fun fetchDevices(context: Context? = null): List<SyncDevice> {
        val token = SharedPrefsHelper.getKey("sync_token", String::class.java, context = context) ?: return emptyList()
        val projectNumStr = SharedPrefsHelper.getKey("sync_project_num", String::class.java, context = context) ?: return emptyList()
        val projectNum = projectNumStr.toIntOrNull() ?: return emptyList()

        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) { nodes { id content { ... on DraftIssue { id title body bodyText } } } }
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(query, token)
        val nodes = res?.data?.viewer?.projectV2?.items?.nodes ?: return emptyList()

        val list = mutableListOf<SyncDevice>()
        for (node in nodes) {
            val itemId = node.id ?: continue
            val title = node.content?.title ?: continue
            
            // Prefer bodyText over body to bypass raw HTML encoding of Base64 chars
            val body = if (!node.content?.bodyText.isNullOrEmpty()) node.content?.bodyText!! else node.content?.body ?: ""
            val parts = title.split("|")
            if (parts.size >= 2) {
                val decrypted = Util.decrypt(body, token)
                val decompressed = Util.decompressJsonFromString(decrypted)
                val restored = Util.restoreText(decompressed)
                list.add(SyncDevice(parts[0], parts[1], itemId, restored))
            }
        }
        return list
    }
}
