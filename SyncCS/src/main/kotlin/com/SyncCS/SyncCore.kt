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
// Backup Utilities (지능형 지향 필터링 장착 완료)
// ==========================================

object BackupUtils {
    private val whitelistKeys = listOf(
        "result_resume_watching_2/",
        "video_pos_dur/",
        "result_watch_state/",
        "result_watch_state_data/",
        "result_season/",
        "result_episode/",
        "result_dub/",
        "video_source_priority",
        "download_header_cache/"
    )

    private fun isAllowed(str: String): Boolean {
        return whitelistKeys.any { str.contains(it, ignoreCase = true) }
    }

    @Suppress("UNCHECKED_CAST")
    fun getBackup(context: Context, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null): BackupFile {
        Log.d("SYNC", "[BackupUtils] getBackup() called (Strict Whitelist Mode).")
        
        val prefs = with(DataStore) { context.getSharedPrefs() }.all
        val defaultPrefs = with(DataStore) { context.getDefaultSharedPrefs() }.all

        // ★ 용량 폭발 방지: 현재 '이어보기'에 활성화되어 있는 작품의 ID 목록만 추출합니다.
        val resumeKeys = prefs.keys.filter { it.contains("result_resume_watching_2/", ignoreCase = true) }
        val activeParentIds = resumeKeys.mapNotNull { key ->
            key.substringAfter("result_resume_watching_2/", "").trim().ifEmpty { null }
        }.toSet()

        // download_header_cache는 현재 이어보기(activeParentIds)에 활성화되어 있는 작품만 골라서 통과시킵니다.
        val filteredPrefs = prefs.filter { (key, _) ->
            if (key.contains("download_header_cache/", ignoreCase = true)) {
                val id = key.substringAfter("download_header_cache/", "").trim()
                activeParentIds.contains(id)
            } else {
                isAllowed(key)
            }
        }
        val filteredDefaultPrefs = defaultPrefs.filterKeys { isAllowed(it) }

        Log.d("SYNC", "[BackupUtils] Whitelist Filtered -> Datastore: ${filteredPrefs.size} keys, DefaultPrefs: ${filteredDefaultPrefs.size} keys")

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

        return BackupFile(buildVars(filteredPrefs), buildVars(filteredDefaultPrefs))
    }

    private fun <T> restoreMap(context: Context, map: Map<String, T>?, isEditingAppSettings: Boolean) {
        if (map == null) return
        
        if (isEditingAppSettings) {
            val defaultEditor = with(DataStore) { context.getDefaultSharedPrefs() }.edit()
            for ((key, value) in map) {
                if (isAllowed(key)) {
                    Editor(defaultEditor).setKeyRaw(key, value)
                }
            }
            defaultEditor.apply()
        } else {
            val datastoreEditor = with(DataStore) { context.getSharedPrefs() }.edit()
            for ((key, value) in map) {
                if (isAllowed(key)) {
                    Editor(datastoreEditor).setKeyRaw(key, value)
                }
            }
            datastoreEditor.apply()
        }
    }

    fun restore(context: Context, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        Log.d("SYNC", "[BackupUtils] restore() started (Strict Whitelist Mode).")
        
        restoreMap(context, backupFile.settings.bool, true)
        restoreMap(context, backupFile.settings.int, true)
        restoreMap(context, backupFile.settings.string, true)
        restoreMap(context, backupFile.settings.float, true)
        restoreMap(context, backupFile.settings.long, true)
        restoreMap(context, backupFile.settings.stringSet, true)

        if (restoreDataStore) {
            restoreMap(context, backupFile.datastore.bool, false)
            restoreMap(context, backupFile.datastore.int, false)
            restoreMap(context, backupFile.datastore.string, false)
            restoreMap(context, backupFile.datastore.float, false)
            restoreMap(context, backupFile.datastore.long, false)
            restoreMap(context, backupFile.datastore.stringSet, false)
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
            
            Log.d("SYNC", "========== UPLOAD TRIGGERED: JSON DATA START ==========")
            val chunkSize = 3000
            for (i in 0..backupData.length / chunkSize) {
                val start = i * chunkSize
                val end = minOf((i + 1) * chunkSize, backupData.length)
                if (start < end) {
                    Log.d("SYNC_JSON", backupData.substring(start, end))      
                }
            }
            Log.d("SYNC", "========== UPLOAD TRIGGERED: JSON DATA END ==========")

            val compressedData = UtilKt.compressJsonToString(backupData)
            
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
                    Log.d("SYNC", "[ApiUtils] Upload Error: ${addRes.errors!!.first().message}")
                    return Pair(false, "Upload Error: ${addRes.errors!!.first().message}")
                }
                
                val newItemId = addRes?.data?.issue?.projectItem?.id
                if (newItemId != null) {
                    AcraApplication.setKey("sync_item_id", newItemId)
                    Log.d("SYNC", "[ApiUtils] Upload successful! New Item ID: $newItemId")
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
    // 1단계: 프로필 접두사 매핑 규칙 (0/result_... ➔ 0/k1/... 동적 완벽 치환)
    private val prefixMap = listOf(
        "result_resume_watching_2/" to "k1/",
        "video_pos_dur/" to "k2/",
        "download_header_cache/" to "k3/",
        "result_watch_state/" to "k4/",
        "result_watch_state_data/" to "k5/",
        "result_season/" to "k6/",
        "result_episode/" to "k7/",
        "result_dub/" to "k8/",
        "search_history/" to "k9/",
        "video_quality_priority/" to "k10/",
        "video_profile_name/" to "k11/",
        "home_bookmarked_last_list" to "k12",
        "user_custom_sites" to "k13",
        "search_pref_tags" to "k14",
        "search_pref_providers" to "k15",
        "resize_mode" to "k16",
        "duration_mode" to "k17",
        "REPOSITORIES_KEY" to "k18",
        "subtitle_settings" to "k19",
        "results_sorting_mode" to "k20",
        "home_pref_homepage" to "k21",
        "playback_speed" to "k22",
        "fshare_username" to "k23",
        "fshare_password" to "k24",
        "monplayer_links" to "k25",
        "xtream_iptv_links" to "k26",
        "iptv_links" to "k27",
        "library_sorting_mode" to "k28",
        "subs_auto_select" to "k29",
        "video_profile_types_2/" to "k30/", // ★ 추가: 로그에 대량 반복 검출된 프로필 타입 접두사
        "download_queue_key" to "k31",       // ★ 추가: 대량 반복 검출된 다운로드 큐 정보
        "sync_last_updated_" to "k32_",       // ★ 추가: 동기화 타임스탬프 관리 접두사
        "home_api_used" to "k33"              // ★ 추가: 홈에서 선택한 확장 프로그램 소스
    )

    // 2단계: 일반 및 이스케이프 따옴표 전용 안전 매핑 (오치환 제로 설계)
    private val propertyMap = listOf(
        "bookmarkedTime" to "v1",
        "latestUpdatedTime" to "v2",
        "name" to "v3",
        "url" to "v4",
        "type" to "v5",
        "apiName" to "v6",
        "posterUrl" to "v7",
        "year" to "v8",
        "syncData" to "v9",
        "quality" to "v10",
        "posterHeaders" to "v11",
        "plot" to "v12",
        "rating" to "v13",
        "tags" to "v14",
        "searchedAt" to "v15",
        "searchText" to "v16",
        "parentId" to "v17",
        "episodeId" to "v18",
        "episode" to "v19",
        "season" to "v20",
        "updateTime" to "v21",
        "isFromDownload" to "v22",
        "position" to "v23",
        "duration" to "v24",
        "mainUrl" to "v25",
        "searchUrl" to "v26",
        "cacheTime" to "v27",
        "title" to "v28",
        "poster" to "v29",
        "TvSeries" to "v30",
        "Movie" to "v31",
        "NSFW" to "v32",
        "Anime" to "v33",
        "Live" to "v34",
        "AnimeMovie" to "v35",
        "AsianDrama" to "v36",
        "Cartoon" to "v37",
        "Torrent" to "v38",
        "slug" to "v39",
        "iconUrl" to "v40",                  // ★ 추가: 레포지토리 내 아이콘 프로퍼티
        "_Bool" to "s1",
        "_Int" to "s2",
        "_String" to "s3",
        "_Float" to "s4",
        "_Long" to "s5",
        "_StringSet" to "s6",
        "swipe_enabled_key" to "s7",
        "skip_startup_account_select_key" to "s8",
        "auto_update" to "s9",
        "rotate_video_key" to "s10",
        "auto_download_plugins_key" to "s11",
        "auto_update_plugins" to "s12",
        "player_resize_enabled_key" to "s13",
        "bg_startup_tracing" to "s14",
        "playback_speed_enabled_key" to "s15",
        "acra.disable" to "s16",
        "enable_nsfw_on_providers_key" to "s17",
        "preview_seekbar_key" to "s18",
        "show_trailers_key" to "s19",
        "show_fillers_key" to "s20",
        "random_button_key" to "s21",
        "swipe_vertical_enabled_key" to "s22",
        "bottom_title_key" to "s23",
        "auto_rotate_video_key" to "s24",
        "advanced_search" to "s25",
        "speedup_key" to "s26",
        "episode_sync_enabled_key" to "s27",
        "hide_player_control_names_key" to "s28",
        "show_kitsu_posters_key" to "s29",
        "double_tap_enabled_key" to "s30",
        "pip_enabled_key" to "s31",
        "double_tap_pause_enabled_key" to "s32",
        "enable_skip_op_from_database" to "s33",
        "autoplay_next_key" to "s34",
        "android_tv_interface_off_seek_key" to "s35",
        "DNS over HTTPS" to "s36",
        "double_tap_seek_time_key2" to "s37",
        "auto_download_plugins_key2" to "s38",
        "automatic_backup_key" to "s39",
        "download_concurrent_key" to "s40",
        "com.facebook.appevents.SessionInfo.interruptionCount" to "s41",
        "download_parallel_key" to "s42",
        "android_tv_interface_on_seek_key" to "s43",
        "app_layout_key" to "s44",
        "backup_dir_key" to "s45",
        "com.applovin.sdk.impl.isFirstRun" to "s46",
        "com.facebook.appevents.SessionInfo.sessionId" to "s47",
        "primary_color_key" to "s48",
        "app_theme_key" to "s49",
        "app_locale" to "s50",
        "com.facebook.appevents.SessionInfo.sessionEndTime" to "s51",
        "com.facebook.appevents.SessionInfo.sessionStartTime" to "s52",
        "prefer_media_type_key_2" to "s53",
        "datastore" to "s54"
    )

    // 3단계: 특수 규격 문자 매핑 규칙
    private val valueMap = listOf(
        "https://" to "h//",
        ":null," to ":n,",
        ":false}" to ":f}",
        ":true}" to ":t}"
    )

    // JSON Key를 안전하게 이중 치환하는 독립 확장 확장 함수
    private fun String.replaceKeySafe(original: String, replacement: String): String {
        return this
            .replace("\"$original\"", "\"$replacement\"")
            .replace("\\\"$original\\\"", "\\\"$replacement\\\"")
    }

    private fun replaceKeys(json: String): String {
        var result = json
        
        // 1. 동적 프로필 접두사 매핑 일괄 치환
        for ((original, replacement) in prefixMap) {
            result = result.replace(original, replacement)
        }
        
        // 2. 내부 직렬화 따옴표 이중 치환
        for ((original, replacement) in propertyMap) {
            result = result.replaceKeySafe(original, replacement)
        }
        
        // 3. 특수 규격 문자 일괄 치환
        for ((original, replacement) in valueMap) {
            result = result.replace(original, replacement)
        }
        
        return result
    }

    private fun restoreKeys(json: String): String {
        var result = json
        
        for ((original, replacement) in valueMap) {
            result = result.replace(replacement, original)
        }
        
        for ((original, replacement) in propertyMap) {
            result = result.replaceKeySafe(replacement, original)
        }
        
        for ((original, replacement) in prefixMap) {
            result = result.replace(replacement, original)
        }
        
        return result
    }

    fun compressJsonToString(json: String): String {
        val shrunkenJson = replaceKeys(json)
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(shrunkenJson.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressJsonFromString(encoded: String): String {
        return try {
            val decodedBytes = if (encoded.contains("-") || encoded.contains("_")) {
                Base64.decode(encoded, Base64.URL_SAFE)
            } else {
                Base64.decode(encoded, Base64.DEFAULT)
            }

            val rawJson = if (decodedBytes.size >= 2 && decodedBytes[0] == 0x1F.toByte() && decodedBytes[1] == 0x8B.toByte()) {
                val bis = ByteArrayInputStream(decodedBytes)
                GZIPInputStream(bis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(decodedBytes, Charsets.UTF_8)
            }
            
            restoreKeys(rawJson)
        } catch (e: Exception) {
            Log.d("SYNC", "[UtilKt] Failed to decompress: ${e.message}")
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
