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
// Backup Utilities (지능형 복원 및 가변 필터 탑재)
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

    private fun isCoreSyncKey(key: String): Boolean {
        return key.contains("result_resume_watching_2/", ignoreCase = true) ||
               key.contains("result_watch_state/", ignoreCase = true) ||
               key.contains("result_watch_state_data/", ignoreCase = true)
    }

    private fun <T> Map<String, T>.filterAllowedKeys(): Map<String, T> {
        return this.filter { isAllowed(it.key) }
    }


@Suppress("UNCHECKED_CAST")
    fun getBackup(context: Context, resumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null): BackupFile {
        Log.d("SYNC", "[SyncBackup] getBackup() called (Strict Whitelist Mode).")
        
        val sharedPrefs = with(DataStore) { context.getSharedPrefs() }
        val prefs = sharedPrefs.all
        val defaultPrefs = with(DataStore) { context.getDefaultSharedPrefs() }.all

        // 1. 최근 시청 100개 ID 선별
        val resumeKeys = prefs.keys.filter { it.contains("result_resume_watching_2/", ignoreCase = true) }
        val resumeEntries = resumeKeys.mapNotNull { key ->
                val value = prefs[key]
                if (value !is String) return@mapNotNull null
                try {
                    val match = Regex("\"updateTime\"\\s*:\\s*(\\d+)").find(value)
                    val time = match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    val parentId = key.substringAfter("result_resume_watching_2/", "").trim()
                    if (parentId.isEmpty()) null else Triple(key, parentId, time)
                } catch (e: Exception) {
                    null
                }
            }

        val topEntries = resumeEntries.sortedByDescending { it.third }.take(100)
        val allowedResumeKeys = topEntries.map { it.first }.toSet()
        val activeParentIds = topEntries.map { it.second }.toSet()

        val bookmarkKeys = prefs.keys.filter { it.contains("result_watch_state_data/", ignoreCase = true) }
        val bookmarkParentIds = bookmarkKeys.map { key ->
            key.substringAfter("result_watch_state_data/", "").trim()
        }.filter { it.isNotEmpty() }.toSet()

        val currentActiveIds = (activeParentIds + bookmarkParentIds)

        // 2. 툼스톤(삭제 이력) 제어 및 2주 만료 청소
        val currentKeys = prefs.keys.filter { isCoreSyncKey(it) }.toSet()
        val lastKnownKeys = sharedPrefs.getString("sync_last_known_keys", null)?.let {
            try { mapper.readValue<Set<String>>(it) } catch(e: Exception) { emptySet<String>() }
        } ?: emptySet()

        // 새로 삭제가 일어난 키 수집
        val newlyDeletedKeys = if (lastKnownKeys.isNotEmpty()) {
            lastKnownKeys - currentKeys
        } else {
            emptySet()
        }

        // 로컬에 누적 저장되어 있던 삭제 히스토리 맵 로드
        val deletedMap = sharedPrefs.getString("sync_deleted_keys_map", null)?.let {
            try { mapper.readValue<MutableMap<String, Long>>(it) } catch(e: Exception) { mutableMapOf<String, Long>() }
        } ?: mutableMapOf()

        // 지웠던 키를 다시 사용하기 시작했다면, 삭제 히스토리 맵에서 즉시 소멸시킵니다.
        currentKeys.forEach { activeKey ->
            deletedMap.remove(activeKey)
        }

        // 신규 삭제 발생 건 등록
        val now = System.currentTimeMillis()
        newlyDeletedKeys.forEach { key ->
            deletedMap[key] = now
        }

        // 삭제된 지 14일(2주일)이 지난 툼스톤 데이터는 리스트에서 영구 소멸시켜 용량 백업 한계를 방어합니다.
        val iterator = deletedMap.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (now - entry.value > 14 * 24 * 3600 * 1000L) {
                iterator.remove()
                Log.d("SYNC", "[BackupUtils] Tombstone expired and purged: ${entry.key}")
            }
        }

        // 다음 동기화를 위해 상태 갱신 보존
        sharedPrefs.edit().putString("sync_last_known_keys", mapper.writeValueAsString(currentKeys)).apply()
        sharedPrefs.edit().putString("sync_deleted_keys_map", mapper.writeValueAsString(deletedMap)).apply()

        // 3. download_header_cache 필터링
        val filteredPrefs = prefs.filter { (key, _) ->
            when {
                key.contains("download_header_cache/", ignoreCase = true) -> {
                    val id = key.substringAfter("download_header_cache/", "").trim()
                    activeParentIds.contains(id)
                }
                key.contains("result_resume_watching_2/", ignoreCase = true) -> {
                    allowedResumeKeys.contains(key)
                }
                else -> isAllowed(key)
            }
        }

        // 4. 삭제된 구체적인 개별 키 주소들에만 "-9"를 채워 매립합니다.
        val mutableFilteredPrefs = filteredPrefs.toMutableMap()
        deletedMap.forEach { (deletedKey, deleteTime) ->
            mutableFilteredPrefs[deletedKey] = (-deleteTime).toString()
        }

        val filteredDefaultPrefs = defaultPrefs.filterKeys { isAllowed(it) }

        Log.d("SYNC", "[BackupUtils] Whitelist Filtered -> Datastore: ${mutableFilteredPrefs.size} keys, DefaultPrefs: ${filteredDefaultPrefs.size} keys")

        fun buildVars(map: Map<String, Any?>): BackupVars {
            val cleanedMap = map.mapValues { (key, value) ->
                val unpacked = if (value is String) UtilKt.unpackMultipleSerialization(value) else value
                // ★ 핵심 수정: 오직 핵심 시청 기록 관련 키(isCoreSyncKey)일 때만 음수 툼스톤 체크를 실행합니다.
                val isTombstone = unpacked is String && isCoreSyncKey(key) && unpacked.toLongOrNull()?.let { it < 0L } == true
                if (unpacked is String && !isTombstone) UtilKt.compressValueProperties(unpacked) else unpacked
            }
            
            val compressedMap = cleanedMap.mapKeys { (key, _) -> UtilKt.compressKey(key) }

            return BackupVars(
                bool = compressedMap.filterValues { it is Boolean } as Map<String, Boolean>,
                int = compressedMap.filterValues { it is Int } as Map<String, Int>,
                string = compressedMap.filterValues { it is String } as Map<String, String>,
                float = compressedMap.filterValues { it is Float } as Map<String, Float>,
                long = compressedMap.filterValues { it is Long } as Map<String, Long>,
                stringSet = compressedMap.filterValues { it is Set<*> } as Map<String, Set<String>>
            )
        }

        return BackupFile(buildVars(mutableFilteredPrefs), buildVars(filteredDefaultPrefs))
    }

    fun restore(context: Context, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        Log.d("SYNC", "[BackupUtils] restore() started (Strict Whitelist Mode).")
        
        if (restoreSettings) {
            val settingsEditor = with(DataStore) { context.getDefaultSharedPrefs() }.edit()
            for ((key, value) in backupFile.settings.stringSet ?: emptyMap()) {
                val decompressedKey = UtilKt.decompressKey(key)
                if (isAllowed(decompressedKey)) {
                    settingsEditor.putStringSet(decompressedKey, value)
                }
            }
            backupFile.settings.bool?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) settingsEditor.putBoolean(dk, v)
            }
            backupFile.settings.int?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) settingsEditor.putInt(dk, v)
            }
            backupFile.settings.string?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) {
                    val rv = UtilKt.decompressValueProperties(v)
                    settingsEditor.putString(dk, rv)
                }
            }
            backupFile.settings.float?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) settingsEditor.putFloat(dk, v)
            }
            backupFile.settings.long?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) settingsEditor.putLong(dk, v)
            }
            settingsEditor.apply()
        }

        if (restoreDataStore) {
            val datastoreEditor = with(DataStore) { context.getSharedPrefs() }.edit()
            val wrappedEditor = Editor(datastoreEditor)
            
            val datastoreMap = backupFile.datastore.string ?: emptyMap()
            val localPrefs = with(DataStore) { context.getSharedPrefs() }.all
            
            datastoreMap.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) {
                    val valueLong = v.toLongOrNull()
                    // ★ 핵심 해결책: 오직 핵심 시청 기록 키(isCoreSyncKey)일 때만 음수 값을 툼스톤 삭제 신호로 인지합니다.
                    if (isCoreSyncKey(dk) && valueLong != null && valueLong < 0L) {
                        val deletionTime = -valueLong
                        
                        val localValue = localPrefs[dk] as? String
                        val localTime = if (localValue != null) {
                            val match = Regex("\"updateTime\"\\s*:\\s*(\\d+)").find(localValue)
                            match?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                        } else 0L
                        
                        if (localTime <= deletionTime) {
                            datastoreEditor.remove(dk)
                            Log.d("SYNC", "[BackupUtils] Pinpoint delete triggered for key: $dk (Deletion: $deletionTime, Local: $localTime)")
                        } else {
                            Log.d("SYNC", "[BackupUtils] Skip delete for key: $dk because local data is newer. (Local: $localTime, Deletion: $deletionTime)")
                        }
                    } else {
                        val rv = UtilKt.decompressValueProperties(v)
                        wrappedEditor.setKeyRaw(dk, rv)
                    }
                }
            }
            
            backupFile.datastore.bool?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) wrappedEditor.setKeyRaw(dk, v)
            }
            backupFile.datastore.int?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) wrappedEditor.setKeyRaw(dk, v)
            }
            backupFile.datastore.float?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) wrappedEditor.setKeyRaw(dk, v)
            }
            backupFile.datastore.long?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) wrappedEditor.setKeyRaw(dk, v)
            }
            backupFile.datastore.stringSet?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) wrappedEditor.setKeyRaw(dk, v)
            }
            
            datastoreEditor.apply()
        }
        Log.d("SYNC", "[BackupUtils] restore() finished.")
    }
}

// ==========================================
// API Utilities (★ 긴급 완벽 복구 완료)
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
// String & Compression Utilities (완벽 복구 완료)
// ==========================================

object UtilKt {
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
        "video_profile_types_2/" to "k30/",
        "download_queue_key" to "k31",
        "sync_last_updated_" to "k32_",
        "home_api_used" to "k33",
        "video_source_priority/" to "k34/"
    )

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
        "iconUrl" to "v40",
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

    private val valueMap = listOf(
        "https://" to "h//",
        ":null," to ":n,",
        ":false}" to ":f}",
        ":true}" to ":t}"
    )

    fun unpackMultipleSerialization(value: String): String {
        var current = value.trim()
        while (current.startsWith("\"") && current.endsWith("\"") && current.length > 2) {
            try {
                val unwrapped = mapper.readValue<String>(current)
                if (unwrapped == current || unwrapped.isEmpty()) break
                
                val trimmedUnwrapped = unwrapped.trim()
                if (trimmedUnwrapped.startsWith("{") && trimmedUnwrapped.endsWith("}")) {
                    current = trimmedUnwrapped
                    break
                }
                if (trimmedUnwrapped.startsWith("[") && trimmedUnwrapped.endsWith("]")) {
                    current = trimmedUnwrapped
                    break
                }
                if (trimmedUnwrapped.startsWith("\"") && trimmedUnwrapped.endsWith("\"")) {
                    current = trimmedUnwrapped
                } else {
                    break
                }
            } catch (e: Exception) {
                break
            }
        }
        return current
    }

    private fun String.replaceKeySafe(original: String, replacement: String): String {
        return this
            .replace("\"$original\"", "\"$replacement\"")
            .replace("\\\"$original\\\"", "\\\"$replacement\\\"")
    }

    fun compressKey(key: String): String {
        var result = key
        for ((original, replacement) in prefixMap) {
            if (result.contains(original)) {
                result = result.replace(original, replacement)
                break
            }
        }
        return result
    }

    fun decompressKey(key: String): String {
        var result = key
        for ((original, replacement) in prefixMap) {
            if (result.contains(replacement)) {
                result = result.replace(replacement, original)
                break
            }
        }
        return result
    }

    fun compressValueProperties(value: String): String {
        var result = value
        for ((original, replacement) in propertyMap) {
            result = result.replaceKeySafe(original, replacement)
        }
        for ((original, replacement) in valueMap) {
            result = result.replace(original, replacement)
        }
        return result
    }

    fun decompressValueProperties(value: String): String {
        var result = value
        for ((original, replacement) in valueMap) {
            result = result.replace(replacement, original)
        }
        for ((original, replacement) in propertyMap) {
            result = result.replaceKeySafe(replacement, original)
        }
        return result
    }

    fun compressJsonToString(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressJsonFromString(encoded: String): String {
        return try {
            val decodedBytes = if (encoded.contains("-") || encoded.contains("_")) {
                Base64.decode(encoded, Base64.URL_SAFE)
            } else {
                Base64.decode(encoded, Base64.DEFAULT)
            }

            if (decodedBytes.size >= 2 && decodedBytes[0] == 0x1F.toByte() && decodedBytes[1] == 0x8B.toByte()) {
                val bis = ByteArrayInputStream(decodedBytes)
                GZIPInputStream(bis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(decodedBytes, Charsets.UTF_8)
            }
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
