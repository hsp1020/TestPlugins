package com.SyncCS

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import android.util.Base64
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// ==========================================
// Data Models
// ==========================================

data class APIRes(
    @JsonProperty("data") var data: Data? = null,
    @JsonProperty("errors") var errors: List<Error>? = null
) {
    data class Data(
        @JsonProperty("viewer") var viewer: Viewer? = null,
        @JsonProperty("addProjectV2DraftIssue") var issue: Issue? = null,
        @JsonProperty("updateProjectV2DraftIssue") var updateIssue: Issue? = null,
        @JsonProperty("deleteProjectV2Item") var delItem: DelItem? = null
    ) {
        data class Viewer(@JsonProperty("projectV2") var projectV2: ProjectV2?) {
            data class ProjectV2(
                @JsonProperty("id") var id: String? = null,
                @JsonProperty("items") var items: Items? = null
            ) {
                data class Items(@JsonProperty("nodes") var nodes: List<Node>? = null) {
                    data class Node(
                        @JsonProperty("id") var id: String? = null,
                        @JsonProperty("updatedAt") var updatedAt: String? = null,
                        @JsonProperty("content") var content: Content? = null
                    ) {
                        data class Content(
                            @JsonProperty("id") var id: String? = null,
                            @JsonProperty("title") var title: String? = null,
                            @JsonProperty("bodyText") var bodyText: String? = null
                        )
                    }
                }
            }
        }
        data class Issue(@JsonProperty("projectItem") var projectItem: ProjectItem?) {
            data class ProjectItem(
                @JsonProperty("id") var id: String? = null,
                @JsonProperty("content") var content: Content? = null
            ) {
                @JsonProperty("draftIssue") var draftIssue: Content? = null

                data class Content(
                    @JsonProperty("id") var id: String? = null
                )
            }
        }
        data class DelItem(@JsonProperty("deletedItemId") var deletedItemId: String? = null)
    }
    data class Error(@JsonProperty("message") var message: String? = null)
}

data class BackupFile(
    @JsonProperty("datastore") val datastore: BackupVars,
    @JsonProperty("settings") val settings: BackupVars
)

data class BackupVars(
    @JsonProperty("_Bool") val bool: Map<String, Boolean>? = null,
    @JsonProperty("_Int") val int: Map<String, Int>? = null,
    @JsonProperty("_String") val string: Map<String, String>? = null,
    @JsonProperty("_Float") val float: Map<String, Float>? = null,
    @JsonProperty("_Long") val long: Map<String, Long>? = null,
    @JsonProperty("_StringSet") val stringSet: Map<String, Set<String>>? = null
)

data class SyncDevice(
    @JsonProperty("name") var name: String,
    @JsonProperty("deviceId") var deviceId: String,
    @JsonProperty("itemId") var itemId: String,
    @JsonProperty("syncedData") var syncedData: String? = null,
    @JsonProperty("updatedAt") var updatedAt: String? = null,
    @JsonProperty("draftIssueId") var draftIssueId: String? = null
)

// ==========================================
// Editor Wrapper
// ==========================================

class Editor(private val editor: SharedPreferences.Editor) {
    @Suppress("UNCHECKED_CAST")
    fun <T> setKeyRaw(path: String, value: T?) {
        if (value == null) return
        when {
            isStringSet(value) -> editor.putStringSet(path, value as Set<String>)
            value is Boolean -> editor.putBoolean(path, value)
            value is Int -> editor.putInt(path, value)
            value is String -> editor.putString(path, value)
            value is Float -> editor.putFloat(path, value)
            value is Long -> editor.putLong(path, value)
        }
    }

    private fun isStringSet(value: Any?): Boolean {
        return value is Set<*> && value.all { it is String }
    }

    fun apply() {
        editor.apply()
    }
}

// ==========================================
// Backup Utilities
// ==========================================

object BackupUtils {
    private val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list", "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token", "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active",
        "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC", "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active", "biometric_key", "nginx_user", "download_path_key",
        "download_path_key_visual", "backup_path_key", "backup_dir_path_key", "PLUGINS_KEY", "PLUGINS_KEY_LOCAL",
        "cs3-votes", "last_sync_api", "last_click_action", "last_opened_id", "library_folder", "result_resume_watching_migrated",
        "jsdelivr_proxy_key", "fshare_setup", "fshare_token", "checkbox_fsharehd", "checkbox_fsharecine", "checkbox_fsharesheet",
        "checkbox_fsharefavourite", "checkbox_fsharehdvn", "checkbox_timfshare", "bluphim_token", "device_id", "device_name",
        "sync_token", "sync_project_num", "sync_project_id", "sync_item_id", "sync_device_id", "restore_device", "backup_device",
        "movie_active", "dns_active", "ULTIMA_CURRENT_META_PROVIDERS", "ULTIMA_EXTENSIONS_LIST", "ULTIMA_CURRENT_MEDIA_PROVIDERS",
        "ULTIMA_WATCH_SYNC_CREDS", "used_fstream_providers_v3", "fstream_version", "Aniyomi_Plugin_Successful_Install",
        "enable_support_by_bongngotv", "prerelease_update", "data_store_helper/account_key_index", "VERSION_NAME", 
        "FILES_TO_DELETE_KEY", "HAS_DONE_SETUP", "video_source_priority", "preferred_audio_language",
        "episode_parent_index_", "download_header_cache", "BACKUP_download_header_cache",
        "CONTENT_URI_", "download_status", "search_history", "download_info", "download_resume",
        "download_q_resume", "download_episode_cache",
        
        // ★ 추가 해결 3: 방대한 양의 플러그인 토글 및 플러그인 목록 동기화 차단
        "cat_toggle_",
        "ext_toggle_",
        "show_ext_",
        "PLUGINS_KEY",
        "PLUGINS_KEY_LOCAL"
    )

    private fun isBackup(str: String): Boolean {
        return nonTransferableKeys.none { str.contains(it, ignoreCase = true) }
    }

    @Suppress("UNCHECKED_CAST")
    fun getBackup(context: Context, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null): BackupFile {
        Log.d("SYNC", "[BackupUtils] getBackup() called. Backing up all valid watch history...")
        
        val prefs = with(DataStore) { context.getSharedPrefs() }.all

        val filteredPrefs = prefs.filterKeys { isBackup(it) }

        fun buildVars(map: Map<String, Any?>): BackupVars {
            return BackupVars(
                bool = map.filterValues { it is Boolean } as Map<String, Boolean>,
                int = map.filterValues { it is Int } as Map<String, Int>,
                string = map.filterValues { it is String } as Map<String, String>,
                float = map.filterValues { it is Float } as Map<String, Float>,
                long = map.filterValues { it is Long } as Map<String, Long>,
                stringSet = map.filterValues { it is Set<*> } as Map<String, Set<String>>
            )
        }

        // ★ 추가 해결 1: 앱 자체 설정이 공유되는 것을 막기 위해 settings 백업 강제 차단 (빈 값 전송)
        return BackupFile(buildVars(filteredPrefs), BackupVars())
    }

    private fun <T> restoreMap(context: Context, map: Map<String, T>?) {
        if (map == null) return
        
        // ★ 추가 해결 4: SharedPreferences 에디터를 우회하여 DataStore.setKey API 직접 호출 (UI 즉시 갱신 보장)
        for ((key, value) in map) {
            if (isBackup(key)) {
                try {
                    DataStore.setKey(key, value)
                } catch (e: Exception) {
                    Log.d("SYNC", "Failed to restore key via DataStore: $key")
                }
            }
        }
    }

    fun restore(context: Context, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        Log.d("SYNC", "[BackupUtils] restore() started. restoreSettings=$restoreSettings, restoreDataStore=$restoreDataStore")
        
        // ★ 추가 해결 1: 앱 자체 설정 복원 강제 차단 (restoreSettings 플래그가 넘어오더라도 무시함)
        if (restoreDataStore) {
            restoreMap(context, backupFile.datastore.bool)
            restoreMap(context, backupFile.datastore.int)
            restoreMap(context, backupFile.datastore.string)
            restoreMap(context, backupFile.datastore.float)
            restoreMap(context, backupFile.datastore.long)
            restoreMap(context, backupFile.datastore.stringSet)
        }
        Log.d("SYNC", "[BackupUtils] restore() finished.")
    }
}

// ==========================================
// API Utilities
// ==========================================

object ApiUtils {
    private val isSyncing = AtomicBoolean(false)

    private fun isLoggedIn(): Boolean {
        val context = AcraApplication.context ?: return false
        val prefs = with(DataStore) { context.getSharedPrefs() }
        
        val token = prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) }
        val projectNum = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) }
        
        return !token.isNullOrEmpty() && !projectNum.isNullOrEmpty()
    }

    private suspend fun apiCall(query: String, variables: Map<String, Any>? = null): APIRes? {
        val context = AcraApplication.context ?: return null
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val token = prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) } ?: return null
        
        val payload = mutableMapOf<String, Any>("query" to query)
        if (variables != null) {
            payload["variables"] = variables
        }
        
        return try {
            Log.d("SYNC", "[ApiUtils] Sending GraphQL request...")
            val response = app.post(
                "https://api.github.com/graphql",
                headers = mapOf("Authorization" to "Bearer $token"),
                json = payload
            )
            response.parsedSafe<APIRes>()
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String> {
        if (!isLoggedIn()) return Pair(false, "Please enter Token and Project Number.")
        
        val prefs = with(DataStore) { context?.getSharedPrefs() } ?: return Pair(false, "Context error")
        val numStr = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Project Number missing")
        val num = numStr.toIntOrNull() ?: return Pair(false, "Invalid Project Number")
        
        val query = """query(${'$'}num: Int!) { viewer { projectV2(number: ${'$'}num) { id } } }"""
        val res = apiCall(query, mapOf("num" to num))
        
        if (res?.errors?.isNotEmpty() == true) {
            return Pair(false, "GitHub Error: ${res.errors!!.first().message}")
        }
        
        val projectId = res?.data?.viewer?.projectV2?.id
        if (projectId != null) {
            AcraApplication.setKey("sync_project_id", projectId)
            return Pair(true, "Successfully connected to GitHub Project!")
        }
        
        return Pair(false, "Failed to find project. Check your Token and Project Number.")
    }

    suspend fun fetchDevices(): List<SyncDevice> {
        if (!isLoggedIn()) return emptyList()
        
        val context = AcraApplication.context ?: return emptyList()
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val numStr = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return emptyList()
        val num = numStr.toIntOrNull() ?: return emptyList()
        
        val query = """query(${'$'}num: Int!) { viewer { projectV2(number: ${'$'}num) { id items(first: 100) { nodes { id updatedAt content { ... on DraftIssue { id title bodyText } } } } } } }"""
        val res = apiCall(query, mapOf("num" to num))
        
        val nodes = res?.data?.viewer?.projectV2?.items?.nodes ?: return emptyList()
        
        return nodes.mapNotNull { node ->
            val title = node.content?.title ?: "Unknown"
            val body = node.content?.bodyText ?: ""
            val itemId = node.id ?: return@mapNotNull null
            val updatedAt = node.updatedAt ?: ""
            val draftIssueId = node.content?.id ?: ""
            
            val parts = title.split("|")
            if (parts.size >= 2) {
                SyncDevice(name = parts[0], deviceId = parts[1], itemId = itemId, syncedData = body, updatedAt = updatedAt, draftIssueId = draftIssueId)
            } else {
                SyncDevice(name = title, deviceId = "", itemId = itemId, syncedData = body, updatedAt = updatedAt, draftIssueId = draftIssueId)
            }
        }.sortedBy { it.updatedAt }
    }

    suspend fun deleteDevice(projectId: String, itemId: String): Boolean {
        val delQuery = """mutation(${'$'}projectId: ID!, ${'$'}itemId: ID!) { deleteProjectV2Item(input: {projectId: ${'$'}projectId, itemId: ${'$'}itemId}) { deletedItemId } }"""
        val res = apiCall(delQuery, mapOf("projectId" to projectId, "itemId" to itemId))
        return res?.errors.isNullOrEmpty()
    }

    suspend fun updateDevice(draftIssueId: String, title: String, body: String): Boolean {
        val updateQuery = """mutation(${'$'}draftIssueId: ID!, ${'$'}title: String!, ${'$'}body: String!) { updateProjectV2DraftIssue(input: {draftIssueId: ${'$'}draftIssueId, title: ${'$'}title, body: ${'$'}body}) { draftIssue { id } } }"""
        val res = apiCall(updateQuery, mapOf("draftIssueId" to draftIssueId, "title" to title, "body" to body))
        return res != null && res.errors.isNullOrEmpty()
    }

    suspend fun syncThisDevice(backupData: String, context: Context? = AcraApplication.context): Pair<Boolean, String> {
        if (!isLoggedIn()) return Pair(false, "Not logged in")
        
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d("SYNC", "[ApiUtils] Sync already in progress, skipping duplicate request.")
            return Pair(false, "Sync already in progress")
        }
        
        try {
            val prefs = with(DataStore) { context?.getSharedPrefs() } ?: return Pair(false, "Context error")
            val projectId = prefs.getString("sync_project_id", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Project ID missing. Please save config again.")
            val deviceId = prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Device ID missing")
            val deviceName = prefs.getString("device_name", null)?.let { mapper.readValue<String>(it) } ?: Build.MODEL
            
            val cleanedData = UtilKt.cleanedSyncData(backupData)
            
            Log.d("SYNC", "========== UPLOAD TRIGGERED: JSON DATA START ==========")
            val chunkSize = 3000
            for (i in 0..cleanedData.length / chunkSize) {
                val start = i * chunkSize
                val end = minOf((i + 1) * chunkSize, cleanedData.length)
                if (start < end) {
                    Log.d("SYNC_JSON", cleanedData.substring(start, end))
                }
            }
            Log.d("SYNC", "========== UPLOAD TRIGGERED: JSON DATA END ==========")

            val compressedData = UtilKt.compressJsonToString(cleanedData)
            
            if (compressedData.length > 65000) {
                return Pair(false, "Data too large (${compressedData.length} chars).")
            }

            val devices = fetchDevices()
            val existingDevices = devices.filter { it.deviceId == deviceId }
            val title = "$deviceName|$deviceId"

            if (existingDevices.isNotEmpty()) {
                val target = existingDevices.first()
                Log.d("SYNC", "[ApiUtils] Existing card found. Updating card in place: ${target.name}")
                val updateSuccess = updateDevice(target.draftIssueId ?: "", title, compressedData)
                if (!updateSuccess) {
                    return Pair(false, "Failed to update existing card.")
                }

                if (existingDevices.size > 1) {
                    existingDevices.drop(1).forEach { duplicate ->
                        deleteDevice(projectId, duplicate.itemId)
                    }
                }
                return Pair(true, "Data updated successfully in place!")
            } else {
                val addQuery = """mutation(${'$'}projectId: ID!, ${'$'}title: String!, ${'$'}body: String!) { addProjectV2DraftIssue(input: {projectId: ${'$'}projectId, title: ${'$'}title, body: ${'$'}body}) { projectItem { id } } }"""
                val addRes = apiCall(addQuery, mapOf("projectId" to projectId, "title" to title, "body" to compressedData))
                
                if (addRes?.errors?.isNotEmpty() == true) {
                    return Pair(false, "Upload Error: ${addRes.errors!!.first().message}")
                }
                
                val newItemId = addRes?.data?.issue?.projectItem?.id
                if (newItemId != null) {
                    AcraApplication.setKey("sync_item_id", newItemId)
                    return Pair(true, "Data synced successfully to GitHub!")
                }
                return Pair(false, "Failed to upload data to GitHub.")
            }
        } finally {
            isSyncing.set(false)
        }
    }
}

// ==========================================
// String & Compression Utilities
// ==========================================

object UtilKt {
    private val replacementMap = listOf(
        "\"result_resume_watching_2/" to "\"k1/",
        "\"video_pos_dur/" to "\"k2/",
        "\"result_watch_state/" to "\"k4/",
        "\"result_watch_state_data/" to "\"k5/",
        "\"result_season/" to "\"k6/",
        "\"result_episode/" to "\"k7/",
        "\"result_dub/" to "\"k8/",
        "\"search_history/" to "\"k9/",
        "\"video_quality_priority/" to "\"k10/",
        "\"video_profile_name/" to "\"k11/",
        "\"home_bookmarked_last_list" to "\"k12",
        "\"user_custom_sites" to "\"k13",
        "\"search_pref_tags" to "\"k14",
        "\"search_pref_providers" to "\"k15",
        "\"resize_mode" to "\"k16",
        "\"duration_mode" to "\"k17",
        "\"REPOSITORIES_KEY" to "\"k18",
        "\"subtitle_settings" to "\"k19",
        "\"results_sorting_mode" to "\"k20",
        "\"home_pref_homepage" to "\"k21",
        "\"playback_speed" to "\"k22",
        "\"fshare_username" to "\"k23",
        "\"fshare_password" to "\"k24",
        "\"monplayer_links" to "\"k25",
        "\"xtream_iptv_links" to "\"k26",
        "\"iptv_links" to "\"k27",
        "\"library_sorting_mode" to "\"k28",
        "\"subs_auto_select" to "\"k29",
        "\"bookmarkedTime\"" to "\"v1\"",
        "\"latestUpdatedTime\"" to "\"v2\"",
        "\"name\"" to "\"v3\"",
        "\"url\"" to "\"v4\"",
        "\"type\"" to "\"v5\"",
        "\"apiName\"" to "\"v6\"",
        "\"posterUrl\"" to "\"v7\"",
        "\"year\"" to "\"v8\"",
        "\"syncData\"" to "\"v9\"",
        "\"quality\"" to "\"v10\"",
        "\"posterHeaders\"" to "\"v11\"",
        "\"plot\"" to "\"v12\"",
        "\"rating\"" to "\"v13\"",
        "\"tags\"" to "\"v14\"",
        "\"searchedAt\"" to "\"v15\"",
        "\"searchText\"" to "\"v16\"",
        "\"parentId\"" to "\"v17\"",
        "\"episodeId\"" to "\"v18\"",
        "\"episode\"" to "\"v19\"",
        "\"season\"" to "\"v20\"",
        "\"updateTime\"" to "\"v21\"",
        "\"isFromDownload\"" to "\"v22\"",
        "\"position\"" to "\"v23\"",
        "\"duration\"" to "\"v24\"",
        "\"mainUrl\"" to "\"v25\"",
        "\"searchUrl\"" to "\"v26\"",
        "\"cacheTime\"" to "\"v27\"",
        "\"title\"" to "\"v28\"",
        "\"poster\"" to "\"v29\"",
        "\"TvSeries\"" to "\"v30\"",
        "\"Movie\"" to "\"v31\"",
        "\"NSFW\"" to "\"v32\"",
        "\"Anime\"" to "\"v33\"",
        "\"Live\"" to "\"v34\"",
        "\"AnimeMovie\"" to "\"v35\"",
        "\"AsianDrama\"" to "\"v36\"",
        "\"Cartoon\"" to "\"v37\"",
        "\"Torrent\"" to "\"v38\"",
        "\"slug\"" to "\"v39\"",
        "\"https://" to "\"h//",
        "\":null," to "\":n,",
        "\":false}" to "\":f}",
        "\":true}" to "\":t}",
        "\"_Bool\"" to "\"s1\"",
        "\"_Int\"" to "\"s2\"",
        "\"_String\"" to "\"s3\"",
        "\"_Float\"" to "\"s4\"",
        "\"_Long\"" to "\"s5\"",
        "\"_StringSet\"" to "\"s6\"",
        "\"swipe_enabled_key\"" to "\"s7\"",
        "\"skip_startup_account_select_key\"" to "\"s8\"",
        "\"auto_update\"" to "\"s9\"",
        "\"rotate_video_key\"" to "\"s10\"",
        "\"auto_download_plugins_key\"" to "\"s11\"",
        "\"auto_update_plugins\"" to "\"s12\"",
        "\"player_resize_enabled_key\"" to "\"s13\"",
        "\"bg_startup_tracing\"" to "\"s14\"",
        "\"playback_speed_enabled_key\"" to "\"s15\"",
        "\"acra.disable\"" to "\"s16\"",
        "\"enable_nsfw_on_providers_key\"" to "\"s17\"",
        "\"preview_seekbar_key\"" to "\"s18\"",
        "\"show_trailers_key\"" to "\"s19\"",
        "\"show_fillers_key\"" to "\"s20\"",
        "\"random_button_key\"" to "\"s21\"",
        "\"swipe_vertical_enabled_key\"" to "\"s22\"",
        "\"bottom_title_key\"" to "\"s23\"",
        "\"auto_rotate_video_key\"" to "\"s24\"",
        "\"advanced_search\"" to "\"s25\"",
        "\"speedup_key\"" to "\"s26\"",
        "\"episode_sync_enabled_key\"" to "\"s27\"",
        "\"hide_player_control_names_key\"" to "\"s28\"",
        "\"show_kitsu_posters_key\"" to "\"s29\"",
        "\"double_tap_enabled_key\"" to "\"s30\"",
        "\"pip_enabled_key\"" to "\"s31\"",
        "\"double_tap_pause_enabled_key\"" to "\"s32\"",
        "\"enable_skip_op_from_database\"" to "\"s33\"",
        "\"autoplay_next_key\"" to "\"s34\"",
        "\"android_tv_interface_off_seek_key\"" to "\"s35\"",
        "\"DNS over HTTPS\"" to "\"s36\"",
        "\"double_tap_seek_time_key2\"" to "\"s37\"",
        "\"auto_download_plugins_key2\"" to "\"s38\"",
        "\"automatic_backup_key\"" to "\"s39\"",
        "\"download_concurrent_key\"" to "\"s40\"",
        "\"com.facebook.appevents.SessionInfo.interruptionCount\"" to "\"s41\"",
        "\"download_parallel_key\"" to "\"s42\"",
        "\"android_tv_interface_on_seek_key\"" to "\"s43\"",
        "\"app_layout_key\"" to "\"s44\"",
        "\"backup_dir_key\"" to "\"s45\"",
        "\"com.applovin.sdk.impl.isFirstRun\"" to "\"s46\"",
        "\"com.facebook.appevents.SessionInfo.sessionId\"" to "\"s47\"",
        "\"primary_color_key\"" to "\"s48\"",
        "\"app_theme_key\"" to "\"s49\"",
        "\"app_locale\"" to "\"s50\"",
        "\"com.facebook.appevents.SessionInfo.sessionEndTime\"" to "\"s51\"",
        "\"com.facebook.appevents.SessionInfo.sessionStartTime\"" to "\"s52\"",
        "\"prefer_media_type_key_2\"" to "\"s53\"",
        "\"datastore\"" to "\"s54\""
    )

    fun cleanedSyncData(syncData: String): String {
        return try {
            var result = syncData
                .replace(Regex("\\\\\"tags\\\\\"\\s*:\\s*\\[.*?]"), "\\\\\"tags\\\\\":[]")
                .replace(Regex("\\\\\"plot\\\\\"\\s*:\\s*\\\\\"(.*?)(?<!\\\\)\\\\\""), "\\\\\"plot\\\\\":\\\\\"\\\\\"")
                .replace(Regex("\\\\\"overview\\\\\"\\s*:\\s*\\\\\"(.*?)(?<!\\\\)\\\\\""), "\\\\\"overview\\\\\":\\\\\"\\\\\"")
            
            for ((old, new) in replacementMap) {
                result = result.replace(old, new)
            }
            result
        } catch (e: Exception) {
            syncData
        }
    }

    fun restoreText(minified: String): String {
        var result = minified
        for ((old, new) in replacementMap) {
            result = result.replace(new, old)
        }
        return result
    }

    fun compressJsonToString(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun decompressJsonFromString(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP or Base64.URL_SAFE)
            try {
                val bis = ByteArrayInputStream(bytes)
                GZIPInputStream(bis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } catch (e: Exception) {
                String(bytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun getDeviceId(packageName: String, context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) {
            return md5(packageName + androidId)
        }
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val serial = Build.getSerial()
                if (!serial.isNullOrEmpty()) {
                    return md5(packageName + serial)
                }
            } catch (e: SecurityException) {
                // Ignore
            }
        } else {
            @Suppress("DEPRECATION")
            val serial = Build.SERIAL
            if (!serial.isNullOrEmpty() && serial != "unknown") {
                return md5(packageName + serial)
            }
        }
        val fallback = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        val uuid = UUID.nameUUIDFromBytes(fallback.toByteArray(Charsets.UTF_8))
        return md5(packageName + uuid.toString())
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
