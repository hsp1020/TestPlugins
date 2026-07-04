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
// Gist Data Models
// ==========================================

data class GistRes(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("description") val description: String? = null,
    @JsonProperty("files") val files: Map<String, GistFile>? = null
) {
    data class GistFile(
        @JsonProperty("filename") val filename: String? = null,
        @JsonProperty("content") val content: String? = null,
        @JsonProperty("raw_url") val rawUrl: String? = null
    )
}

data class GistPayload(
    @JsonProperty("deviceName") val deviceName: String,
    @JsonProperty("deviceId") val deviceId: String,
    @JsonProperty("updatedAt") val updatedAt: String,
    @JsonProperty("data") val data: String
)

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
                Log.d("SYNC", "[SyncBackup] Tombstone expired and purged: ${entry.key}")
            }
        }

        // 다음 동기화를 위해 상태 갱신 보존
        sharedPrefs.edit().putString("sync_last_known_keys", mapper.writeValueAsString(currentKeys)).apply()
        sharedPrefs.edit().putString("sync_deleted_keys_map", mapper.writeValueAsString(deletedMap)).apply()

        // ★ [핵심 필터링 로직 수정] 
        // 용량 낭비가 가장 심하면서 복원 시 필요 없는 다운로드 캐시는 원천 차단하고,
        // 나머지 재생 위치(video_pos_dur/) 및 세부 시청 메타데이터들은 필터 차단 없이 100% 보존합니다.
        val filteredPrefs = prefs.filter { (key, _) ->
            when {
                // 다운로드 헤더 캐시는 링크 만료 특성상 타 기기 복원 시 무용하므로 백업 대상에서 통째로 예외 처리합니다.
                key.contains("download_header_cache/", ignoreCase = true) -> false

                // 최근 시청 목록(UI 메인화면 노출 제한용)은 기존 설계대로 상위 100개 유지
                key.contains("result_resume_watching_2/", ignoreCase = true) -> {
                    allowedResumeKeys.contains(key)
                }

                // 나머지 시청기록(재생 위치, 시즌, 에피소드, 더빙 정보, 북마크 등)은 누적된 데이터 전부 허용하여 복원 보존
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
                // 오직 핵심 시청 기록 관련 키(isCoreSyncKey)일 때만 음수 툼스톤 체크를 실행합니다.
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
                    // 오직 핵심 시청 기록 키(isCoreSyncKey)일 때만 음수 값을 툼스톤 삭제 신호로 인지합니다.
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

    // 진단용 데이터 크기 분석 메서드
    fun analyzeBackupData(context: Context) {
        val sharedPrefs = with(DataStore) { context.getSharedPrefs() }
        val prefs = sharedPrefs.all

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

        val filteredPrefs = prefs.filter { (key, _) ->
            when {
                key.contains("download_header_cache/", ignoreCase = true) -> false
                key.contains("result_resume_watching_2/", ignoreCase = true) -> {
                    allowedResumeKeys.contains(key)
                }
                else -> isAllowed(key)
            }
        }

        Log.d("SYNC_DEBUG", "==================================================")
        Log.d("SYNC_DEBUG", "             [백업 데이터 정밀 분석 시작]            ")
        Log.d("SYNC_DEBUG", "==================================================")
        Log.d("SYNC_DEBUG", "필터 적용 후 총 백업 대상 키 개수: ${filteredPrefs.size} 개")

        val categorySizes = mutableMapOf<String, Int>()
        val categoryCounts = mutableMapOf<String, Int>()
        val keySizeList = mutableListOf<Pair<String, Int>>()

        filteredPrefs.forEach { (key, value) ->
            val valueStr = value.toString()
            val valLen = valueStr.length
            keySizeList.add(key to valLen)

            var matchedCategory = "기타 (분류 안 됨)"
            for (prefix in whitelistKeys) {
                if (key.contains(prefix, ignoreCase = true)) {
                    matchedCategory = prefix
                    break
                }
            }

            categorySizes[matchedCategory] = categorySizes.getOrDefault(matchedCategory, 0) + valLen
            categoryCounts[matchedCategory] = categoryCounts.getOrDefault(matchedCategory, 0) + 1
        }

        Log.d("SYNC_DEBUG", "--------------------------------------------------")
        Log.d("SYNC_DEBUG", " [1] 카테고리별 점유 현황 (점유율 순)")
        Log.d("SYNC_DEBUG", "--------------------------------------------------")
        categoryCounts.keys.sortedByDescending { categorySizes[it] }.forEach { cat ->
            val rawCharCount = categorySizes[cat] ?: 0
            Log.d("SYNC_DEBUG", "분류: $cat -> 데이터 개수: ${categoryCounts[cat]}개, 누적 문자 수: $rawCharCount 자")
        }

        Log.d("SYNC_DEBUG", "--------------------------------------------------")
        Log.d("SYNC_DEBUG", " [2] 개별 키 중 가장 용량이 큰 Top 20")
        Log.d("SYNC_DEBUG", "--------------------------------------------------")
        keySizeList.sortedByDescending { it.second }.take(20).forEachIndexed { index, pair ->
            val originalVal = filteredPrefs[pair.first].toString()
            val preview = if (originalVal.length > 120) originalVal.take(120) + "..." else originalVal
            Log.d("SYNC_DEBUG", "#${index + 1}: ${pair.first} -> 크기: ${pair.second} 자")
            Log.d("SYNC_DEBUG", "     └ 미리보기: $preview")
        }

        try {
            val backupFile = getBackup(context)
            val backupJson = mapper.writeValueAsString(backupFile)
            val compressedData = UtilKt.compressJsonToString(backupJson)
            Log.d("SYNC_DEBUG", "--------------------------------------------------")
            Log.d("SYNC_DEBUG", " [3] 최종 압축 결과 예측")
            Log.d("SYNC_DEBUG", "--------------------------------------------------")
            Log.d("SYNC_DEBUG", "압축 전 순수 JSON 크기: ${backupJson.length} 자")
            Log.d("SYNC_DEBUG", "압축 후 Base64 결과물 크기: ${compressedData.length} 자 (한계치: 65,000자)")
        } catch (e: Exception) {
            Log.d("SYNC_DEBUG", "최종 예측 실패: ${e.message}")
        }
        Log.d("SYNC_DEBUG", "==================================================")
    }
}

// ==========================================
// API Utilities (★ Gist REST API 전면 교체 완료)
// ==========================================

object ApiUtils {
    private val isSyncing = AtomicBoolean(false)

    private fun isLoggedIn(): Boolean {
        val context = AcraApplication.context ?: return false
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val token = prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) }
        return !token.isNullOrEmpty()
    }

    private suspend fun apiCall(url: String, method: String, body: Any? = null): String? {
        val context = AcraApplication.context ?: return null
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val token = prefs.getString("sync_token", null)?.let { mapper.readValue<String>(it) } ?: return null

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28"
        )

        return try {
            Log.d("SYNC", "[ApiUtils] Sending Gist API $method request to $url...")
            val response = when (method.uppercase()) {
                "GET" -> app.get(url, headers = headers)
                "POST" -> app.post(url, headers = headers, json = body)
                "PATCH" -> app.patch(url, headers = headers, json = body)
                "DELETE" -> app.delete(url, headers = headers)
                else -> null
            }
            response?.text
        } catch (e: Exception) {
            Log.e("SYNC", "[ApiUtils] Gist API Call failed: ${e.message}")
            null
        }
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String> {
        if (!isLoggedIn()) return Pair(false, "Please enter Token.")
        
        val prefs = with(DataStore) { context?.getSharedPrefs() } ?: return Pair(false, "Context error")
        val customGistId = prefs.getString("sync_project_num", null)?.let { 
            try { mapper.readValue<String>(it) } catch(e: Exception) { "" }
        }?.trim() ?: ""

        if (customGistId.isEmpty()) {
            // 1. 자동으로 기존 Gist 검색
            Log.d("SYNC", "[ApiUtils] Gist ID not provided. Searching for existing Sync Gist...")
            val listJson = apiCall("https://api.github.com/gists", "GET")
            if (listJson == null) {
                return Pair(false, "Failed to authenticate or fetch Gists. Check your Token.")
            }

            val gists: List<GistRes> = try {
                mapper.readValue(listJson)
            } catch (e: Exception) {
                emptyList()
            }

            val existingGist = gists.find { it.description == "Cloudstream Sync Backup" }
            if (existingGist != null) {
                val gistId = existingGist.id ?: return Pair(false, "Found Gist but it has no ID.")
                AcraApplication.setKey("sync_project_num", gistId) // UI와 SharedPref 호환성 유지를 위해 동일 변수에 매핑
                AcraApplication.setKey("sync_gist_id", gistId)
                Log.d("SYNC", "[ApiUtils] Connected to existing Gist: $gistId")
                return Pair(true, "Successfully connected to existing GitHub Gist!")
            } else {
                // 2. 검색에 실패했으므로 새로 생성
                Log.d("SYNC", "[ApiUtils] No existing Gist found. Creating a new one...")
                val createPayload = mapOf(
                    "description" to "Cloudstream Sync Backup",
                    "public" to false,
                    "files" to mapOf(
                        "init.txt" to mapOf(
                            "content" to "Cloudstream Sync Initialized"
                        )
                    )
                )
                val createJson = apiCall("https://api.github.com/gists", "POST", createPayload)
                if (createJson == null) {
                    return Pair(false, "Failed to create a new Gist. Check Gist scopes in your Token.")
                }

                val newGist: GistRes = try {
                    mapper.readValue(createJson)
                } catch (e: Exception) {
                    return Pair(false, "Failed to parse created Gist response.")
                }

                val gistId = newGist.id ?: return Pair(false, "Created Gist has no ID.")
                AcraApplication.setKey("sync_project_num", gistId)
                AcraApplication.setKey("sync_gist_id", gistId)
                Log.d("SYNC", "[ApiUtils] Created new Gist ID: $gistId")
                return Pair(true, "Successfully auto-created a new GitHub Gist!")
            }
        } else {
            // 사용자가 수동으로 Gist ID를 입력한 경우 유효성 검증
            Log.d("SYNC", "[ApiUtils] Verifying specified Gist ID: $customGistId")
            val gistJson = apiCall("https://api.github.com/gists/$customGistId", "GET")
            if (gistJson == null) {
                return Pair(false, "Gist ID is invalid or Token has no access to it.")
            }
            AcraApplication.setKey("sync_gist_id", customGistId)
            return Pair(true, "Successfully connected to GitHub Gist!")
        }
    }

    suspend fun fetchDevices(): List<SyncDevice> {
        if (!isLoggedIn()) return emptyList()
        
        val context = AcraApplication.context ?: return emptyList()
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val gistId = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return emptyList()
        
        val gistJson = apiCall("https://api.github.com/gists/$gistId", "GET") ?: return emptyList()
        val gist: GistRes = try {
            mapper.readValue(gistJson)
        } catch (e: Exception) {
            return emptyList()
        }

        val files = gist.files ?: return emptyList()
        return files.filter { it.key.startsWith("device_") && it.key.endsWith(".json") }
            .mapNotNull { (_, gistFile) ->
                val content = gistFile.content ?: return@mapNotNull null
                val payload: GistPayload = try {
                    mapper.readValue(content)
                } catch (e: Exception) {
                    return@mapNotNull null
                }
                
                SyncDevice(
                    name = payload.deviceName,
                    deviceId = payload.deviceId,
                    itemId = gistFile.filename ?: "", // Gist 파일 이름을 itemId 자리에 매핑하여 삭제 호환성 유지
                    syncedData = payload.data,
                    updatedAt = payload.updatedAt,
                    draftIssueId = gistFile.filename ?: ""
                )
            }.sortedBy { it.updatedAt }
    }

    suspend fun deleteDevice(projectId: String, itemId: String): Boolean {
        // Gist에서 특정 파일을 삭제하려면 PATCH 통신으로 해당 파일 이름을 Key로 잡고 Value에 null을 전달합니다.
        val context = AcraApplication.context ?: return false
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val gistId = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return false

        // 잭슨 설정에 따른 null 필드 생략 오작동 방지를 위해 수동 JSON 구조 작성
        val deletePayload = """{"files":{"$itemId":null}}"""
        val resJson = apiCall("https://api.github.com/gists/$gistId", "PATCH", deletePayload)
        return resJson != null
    }

    suspend fun updateDevice(draftIssueId: String, title: String, body: String): Boolean {
        // 덤프 규격 맞춤용 메서드 보존 (Gist 이식으로 동기화 로직은 syncThisDevice에서 자동 처리됩니다)
        return true
    }

    suspend fun syncThisDevice(backupData: String, context: Context? = AcraApplication.context): Pair<Boolean, String> {
        if (!isLoggedIn()) return Pair(false, "Not logged in")
        
        if (!isSyncing.compareAndSet(false, true)) {
            Log.d("SYNC", "[ApiUtils] Sync already in progress, skipping duplicate request.")
            return Pair(false, "Sync already in progress")
        }
        
        try {
            val prefs = with(DataStore) { context?.getSharedPrefs() } ?: return Pair(false, "Context error")
            val gistId = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Gist ID missing. Please save config again.")
            val deviceId = prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Device ID missing")
            val deviceName = prefs.getString("device_name", null)?.let { mapper.readValue<String>(it) } ?: Build.MODEL
            
            val compressedData = UtilKt.compressJsonToString(backupData)
            
            // Gist의 기본 업로드 한계 용량인 10MB 중 여유분을 감안하여 5MB(5,000,000자)로 제한을 수십 배 이상 상향합니다.
            if (compressedData.length > 5000000) {
                return Pair(false, "Data too large (${compressedData.length} chars).")
            }

            val payload = GistPayload(
                deviceName = deviceName,
                deviceId = deviceId,
                updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()),
                data = compressedData
            )

            val payloadStr = mapper.writeValueAsString(payload)
            val updatePayload = mapOf(
                "files" to mapOf(
                    "device_${deviceId}.json" to mapOf(
                        "content" to payloadStr
                    )
                )
            )

            val responseJson = apiCall("https://api.github.com/gists/$gistId", "PATCH", updatePayload)
            return if (responseJson != null) {
                Pair(true, "Data updated successfully in place!")
            } else {
                Pair(false, "Failed to upload data to GitHub Gist.")
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
