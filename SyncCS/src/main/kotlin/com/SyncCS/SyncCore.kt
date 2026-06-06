package com.SyncCS

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.mapper
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import kotlin.text.Charsets.UTF_8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// =========================================================================
// [1] Jackson Models Area (1:1 Restored)
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
// [2] Shared SharedPreferences Editor Class (1:1 Restored)
// =========================================================================
class Editor(val editor: SharedPreferences.Editor) {
    fun <T> setKeyRaw(path: String, value: T) {
        if (isStringSet(value)) {
            @Suppress("UNCHECKED_CAST")
            this.editor.putStringSet(path, value as Set<String>)
            return
        }
        when (value) {
            is Boolean -> this.editor.putBoolean(path, value)
            is Int -> this.editor.putInt(path, value)
            is String -> this.editor.putString(path, value)
            is Float -> this.editor.putFloat(path, value)
            is Long -> this.editor.putLong(path, value)
        }
    }

    private fun isStringSet(value: Any?): Boolean {
        if (value !is Set<*>) return false
        for (item in value) {
            if (item !is String) return false
        }
        return true
    }

    fun apply() {
        this.editor.apply()
        System.gc()
    }
}

// =========================================================================
// [3] BackupUtils Area (1:1 Restored)
// =========================================================================
object BackupUtils {
    val nonTransferableKeys = listOf(
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
        "sync_item_id", "sync_device_id", "restore_device", "backup_device", "movie_active", "dns_active",
        "ULTIMA_CURRENT_META_PROVIDERS", "ULTIMA_EXTENSIONS_LIST", "ULTIMA_CURRENT_MEDIA_PROVIDERS", "ULTIMA_WATCH_SYNC_CREDS",
        "used_fstream_providers_v3", "fstream_version", "Aniyomi_Plugin_Successful_Install", "enable_support_by_bongngotv",
        "download_info", "download_resume", "download_q_resume", "download_episode_cache", "prerelease_update",
        "data_store_helper/account_key_index", "VERSION_NAME", "FILES_TO_DELETE_KEY", "HAS_DONE_SETUP",
        "video_source_priority", "preferred_audio_language"
    )

    fun isResume(key: String): Boolean {
        for (item in nonTransferableKeys) {
            if (key.contains(item)) {
                return false
            }
        }
        return true
    }

    fun isBackup(key: String, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?): Boolean {
        if (!isResume(key)) return false
        
        if (key.contains("result_resume_watching") || key.contains("result_watch_state")) {
            if (resumeWatching == null) return false
            val parts = key.split("/")
            if (parts.size >= 2) {
                val mediaId = parts[1]
                return resumeWatching.any { item ->
                    item.toString().contains(mediaId)
                }
            }
        }
        return true
    }

    fun getBackup(context: Context?, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>?): BackupFile? {
        if (context == null) return null
        val listTake = resumeWatching?.take(50)
        
        val pref = with(DataStore) { context.getSharedPrefs() }
        val defaultPref = with(DataStore) { context.getDefaultSharedPrefs() }
        
        val filteredPref = pref.all.filterKeys { isBackup(it, listTake) }
        val filteredDefaultPref = defaultPref.all.filterKeys { isBackup(it, listTake) }

        fun splitVars(map: Map<String, Any?>): BackupVars {
            val bools = map.filterValues { it is Boolean }.mapValues { it.value as Boolean }
            val ints = map.filterValues { it is Int }.mapValues { it.value as Int }
            val strings = map.filterValues { it is String }.mapValues { it.value as String }
            val floats = map.filterValues { it is Float }.mapValues { it.value as Float }
            val longs = map.filterValues { it is Long }.mapValues { it.value as Long }
            val stringSets = map.filterValues { it is Set<*> }.mapValues { @Suppress("UNCHECKED_CAST") (it.value as Set<String>) }
            
            return BackupVars(bools, ints, strings, floats, longs, stringSets)
        }

        return BackupFile(splitVars(filteredPref), splitVars(filteredDefaultPref))
    }

    fun getEditor(context: Context, isEditingAppSettings: Boolean): com.SyncCS.Editor {
        val prefs = if (isEditingAppSettings) {
            with(DataStore) { context.getDefaultSharedPrefs() }
        } else {
            with(DataStore) { context.getSharedPrefs() }
        }
        return com.SyncCS.Editor(prefs.edit())
    }

    fun <T> restoreMap(context: Context, map: Map<String, T>?, isEditingAppSettings: Boolean) {
        val edit = getEditor(context, isEditingAppSettings)
        if (map != null) {
            for ((key, value) in map) {
                if (isResume(key)) {
                    edit.setKeyRaw(key, value)
                }
            }
        }
        edit.apply()
    }

    fun restore(context: Context?, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        if (context == null) return
        if (restoreSettings) {
            restoreMap(context, backupFile.settings.bool, true)
            restoreMap(context, backupFile.settings.int, true)
            restoreMap(context, backupFile.settings.string, true)
            restoreMap(context, backupFile.settings.float, true)
            restoreMap(context, backupFile.settings.long, true)
            restoreMap(context, backupFile.settings.stringSet, true)
        }
        if (restoreDataStore) {
            restoreMap(context, backupFile.datastore.bool, false)
            restoreMap(context, backupFile.datastore.int, false)
            restoreMap(context, backupFile.datastore.string, false)
            restoreMap(context, backupFile.datastore.float, false)
            restoreMap(context, backupFile.datastore.long, false)
            restoreMap(context, backupFile.datastore.stringSet, false)
        }
    }
}

// =========================================================================
// [4] ApiUtils Area (1:1 Restored & mapper deprecation bypassed)
// =========================================================================
class ApiUtils private constructor() {
    companion object {
        val INSTANCE = ApiUtils()
    }

    private val mapper = com.lagradost.cloudstream3.mapper

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
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    private fun toStringData(obj: Any): String {
        return mapper.writeValueAsString(obj)
    }

    suspend fun apiCall(query: String): APIRes? = withContext(Dispatchers.IO) {
        try {
            val formattedQuery = query.replace("\n", " ").replace(Regex("\\s+"), " ")
            val bodyMap = mapOf("query" to formattedQuery)
            val bodyStr = mapper.writeValueAsString(bodyMap)

            val requestBody = bodyStr.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.github.com/graphql")
                .addHeader("Authorization", "Bearer ${getSavedValue("sync_token")}")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Cloudstream-Sync")
                .post(requestBody)
                .build()

            val response = unsafeClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            if (response.code != 200) {
                return@withContext null
            }
            mapper.readValue(responseBody, APIRes::class.java)
        } catch (e: Exception) {
            null
        }
    }

    private fun getSavedValue(key: String): String? {
        val context = AcraApplication.context ?: return null
        val raw = with(DataStore) { context.getSharedPrefs().getString(key, null) } ?: return null
        return try {
            mapper.readValue(raw, String::class.java)
        } catch (e: Exception) {
            null
        }
    }

    fun isLoggedIn(): Boolean {
        val token = getSavedValue("sync_token")
        val projectNum = getSavedValue("sync_project_num")
        val projectId = getSavedValue("sync_project_id")
        return !token.isNullOrEmpty() && !projectNum.isNullOrEmpty() && !projectId.isNullOrEmpty()
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String>? = withContext(Dispatchers.IO) {
        val token = getSavedValue("sync_token")?.replace("\"", "") ?: return@withContext Pair(false, "Missing token")
        val projectNumStr = getSavedValue("sync_project_num")?.replace("\"", "") ?: return@withContext Pair(false, "Missing project number")
        val projectNum = projectNumStr.toIntOrNull() ?: return@withContext Pair(false, "Invalid project number")

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

        val res = apiCall(query) ?: return@withContext Pair(false, "Connection error")
        val project = res.data?.viewer?.projectV2 ?: return@withContext Pair(false, "Project not found")
        val projectId = project.id ?: return@withContext Pair(false, "Project ID not found")

        AcraApplication.Companion.setKey("sync_project_id", projectId)
        Pair(true, "Project synced")
    }

    suspend fun syncThisDevice(syncData: String): Pair<Boolean, String>? = withContext(Dispatchers.IO) {
        val token = getSavedValue("sync_token")?.replace("\"", "") ?: return@withContext Pair(false, "Missing token")
        val projectId = getSavedValue("sync_project_id")?.replace("\"", "") ?: return@withContext Pair(false, "Missing Project ID")
        val context = AcraApplication.context ?: return@withContext Pair(false, "Missing Context")
        
        val deviceName = getSavedValue("device_name") ?: Build.MODEL
        val deviceId = UtilKt.getDeviceId(context.packageName, context)

        val storedItemId = getSavedValue("sync_item_id")?.replace("\"", "")

        if (!storedItemId.isNullOrEmpty()) {
            val delQuery = """
                mutation {
                    deleteProjectV2Item(input: {projectId: "$projectId", itemId: "$storedItemId"}) {
                        deletedItemId
                    }
                }
            """.trimIndent()
            apiCall(delQuery)
        }

        val escapedPayload = syncData.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val escapedName = "$deviceName|$deviceId".replace("\"", "\\\"")

        val addQuery = """
            mutation {
                addProjectV2DraftIssue(input: {projectId: "$projectId", title: "$escapedName", body: "$escapedPayload"}) {
                    projectItem { id }
                }
            }
        """.trimIndent()

        val res = apiCall(addQuery) ?: return@withContext Pair(false, "Connection error")
        val newItemId = res.data?.issue?.projectItem?.id
        if (!newItemId.isNullOrEmpty()) {
            AcraApplication.Companion.setKey("sync_item_id", newItemId)
            Pair(true, "Sync completed")
        } else {
            Pair(false, "Failed to get draft issue ID")
        }
    }

    suspend fun fetchDevices(): List<SyncDevice>? = withContext(Dispatchers.IO) {
        val projectNumStr = getSavedValue("sync_project_num")?.replace("\"", "") ?: return@withContext null
        val projectNum = projectNumStr.toIntOrNull() ?: return@withContext null

        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) { nodes { id content { ... on DraftIssue { id title body } } } }
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(query) ?: return@withContext null
        val nodes = res.data?.viewer?.projectV2?.items?.nodes ?: return@withContext null

        val list = mutableListOf<SyncDevice>()
        for (node in nodes) {
            val itemId = node.id ?: continue
            val title = node.content?.title ?: continue
            val body = node.content?.body ?: node.content?.bodyText ?: ""
            val parts = title.split("|")
            if (parts.size >= 2) {
                list.add(SyncDevice(parts[0], parts[1], itemId, body))
            }
        }
        list
    }
}

// =========================================================================
// [5] Native Bytecode Utilities Area (1:1 Restored)
// =========================================================================
object UtilKt {
    fun cleanedSyncData(str: String): String {
        return try {
            val s1 = Regex("""\\\"overview\\\"\s*:\s*\\\"(.*?)(?<!\\)\\\"""").replace(str, """\\\"overview\\\":\\\"\\\"""")
            val s2 = Regex("""\\\"plot\\\"\s*:\s*\\\"(.*?)(?<!\\)\\\"""").replace(s1, """\\\"plot\\\":\\\"\\\"""")
            val s3 = Regex("""\\\"tags\\\"\s*:\s*\[.*?]""").replace(s2, """\\\"tags\\\":[]""")

            var result = s3
            val minifyDict = listOf(
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
                "\\\"Cartoon\\\"" to "\\\"v37\\\"", "Torrent" to "v37", "\\\"slug\\\"" to "\\\"v39\\\"",
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
            val minifyDict = listOf(
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
                "\\\"Cartoon\\\"" to "\\\"v37\\\"", "Torrent" to "v37", "\\\"slug\\\"" to "\\\"v39\\\"",
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
            for ((old, new) in minifyDict) {
                result = result.replace(new, old)
            }
            result
        } catch (e: Exception) {
            str
        }
    }

    fun compressJsonToString(str: String): String {
        return Base64.encodeToString(str.toByteArray(UTF_8), 10)
    }

    fun decompressJsonFromString(str: String): String {
        return try {
            String(Base64.decode(str, 10), UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun encrypt(str: String, keySeed: String): String {
        return str
    }

    fun decrypt(str: String, keySeed: String): String {
        return str
    }

    fun getDeviceId(packageName: String, context: Context): String {
        val string = Settings.Secure.getString(context.contentResolver, "android_id")
        if (!string.isNullOrEmpty()) {
            return md5(packageName + string)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val serial = Build.getSerial()
                if (!serial.isNullOrEmpty()) {
                    return md5(packageName + serial)
                }
            } catch (unused: SecurityException) {
            }
        } else {
            val serial = Build.SERIAL
            if (!serial.isNullOrEmpty() && serial != "unknown") {
                return md5(packageName + serial)
            }
        }
        val fallback = Build.BRAND + "_" + Build.MODEL + "_" + Build.DEVICE
        return md5(packageName + UUID.nameUUIDFromBytes(fallback.toByteArray(UTF_8)).toString())
    }

    fun md5(input: String): String {
        val messageDigest = MessageDigest.getInstance("MD5")
        val bytes = messageDigest.digest(input.toByteArray(UTF_8))
        val sb = StringBuilder()
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }
}
