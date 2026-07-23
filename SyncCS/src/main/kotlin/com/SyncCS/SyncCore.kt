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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

// Models ... (GistRes, GistPayload, BackupFile, BackupVars, SyncDevice 호환성 100% 유지)

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

// SyncCore.kt 의 BackupUtils 객체 부분만 아래로 교체하시면 됩니다.

object BackupUtils {
    private val whitelistKeys = listOf(
        "result_resume_watching_2/",
        "video_pos_dur/",          // 모든 에피소드 재생 위치 (100% 백업 보존)
        "result_watch_state/",
        "result_watch_state_data/",
        "result_season/",          // 모든 시즌 정보 (100% 백업 보존)
        "result_episode/",         // 모든 에피소드 정보 (100% 백업 보존)
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

    private fun extractUpdateTime(jsonStr: String): Long {
        val target = "\"updateTime\""
        val index = jsonStr.indexOf(target)
        if (index == -1) return 0L
        val colonIndex = jsonStr.indexOf(":", index + target.length)
        if (colonIndex == -1) return 0L
        var commaIndex = jsonStr.indexOf(",", colonIndex)
        if (commaIndex == -1) commaIndex = jsonStr.indexOf("}", colonIndex)
        if (commaIndex == -1) return 0L
        return jsonStr.substring(colonIndex + 1, commaIndex).trim().replace("\"", "").toLongOrNull() ?: 0L
    }

    @Suppress("UNCHECKED_CAST")
    fun getBackup(context: Context): BackupFile {
        Log.d("SYNC", "[SyncBackup] getBackup() called (Full History Retained).")
        
        val sharedPrefs = with(DataStore) { context.getSharedPrefs() }
        val prefs = sharedPrefs.all
        val defaultPrefs = with(DataStore) { context.getDefaultSharedPrefs() }.all

        // 1. 홈 화면 '계속 시청' 리스트만 깔끔하게 최근 50개로 유지 (Gist 용량 최적화)
        val resumeKeys = prefs.keys.filter { it.contains("result_resume_watching_2/", ignoreCase = true) }
        val resumeEntries = resumeKeys.mapNotNull { key ->
            val value = prefs[key] as? String ?: return@mapNotNull null
            val time = extractUpdateTime(value)
            val parentId = key.substringAfter("result_resume_watching_2/", "").trim()
            if (parentId.isEmpty()) null else Triple(key, parentId, time)
        }

        val topEntries = resumeEntries.sortedByDescending { it.third }.take(50)
        val allowedResumeKeys = topEntries.map { it.first }.toSet()
        val activeParentIds = topEntries.map { it.second }.toSet()

        val bookmarkParentIds = prefs.keys
            .filter { it.contains("result_watch_state_data/", ignoreCase = true) }
            .map { it.substringAfter("result_watch_state_data/", "").trim() }
            .filter { it.isNotEmpty() }.toSet()

        val currentActiveIds = (activeParentIds + bookmarkParentIds)

        // 2. 툼스톤(삭제 이력 추적) 제어
        val currentKeys = prefs.keys.filter { isCoreSyncKey(it) }.toSet()
        val lastKnownKeys = sharedPrefs.getString("sync_last_known_keys", null)?.let {
            try { mapper.readValue<Set<String>>(it) } catch(e: Exception) { emptySet<String>() }
        } ?: emptySet()

        val newlyDeletedKeys = if (lastKnownKeys.isNotEmpty()) lastKnownKeys - currentKeys else emptySet()
        val deletedMap = sharedPrefs.getString("sync_deleted_keys_map", null)?.let {
            try { mapper.readValue<MutableMap<String, Long>>(it) } catch(e: Exception) { mutableMapOf<String, Long>() }
        } ?: mutableMapOf()

        currentKeys.forEach { deletedMap.remove(it) }
        val now = System.currentTimeMillis()
        newlyDeletedKeys.forEach { deletedMap[it] = now }

        val iterator = deletedMap.entries.iterator()
        while (iterator.hasNext()) {
            if (now - iterator.next().value > 14 * 24 * 3600 * 1000L) {
                iterator.remove()
            }
        }

        // 3. 의도대로 video_pos_dur/ (모든 에피소드 재생위치)를 100% 포함하여 백업
        val filteredPrefs = prefs.filter { (key, _) ->
            when {
                key.contains("download_header_cache/", ignoreCase = true) -> {
                    val id = key.substringAfter("download_header_cache/", "").trim()
                    currentActiveIds.contains(id)
                }
                key.contains("result_resume_watching_2/", ignoreCase = true) -> {
                    allowedResumeKeys.contains(key)
                }
                else -> isAllowed(key) // ★ video_pos_dur/, result_episode/ 등 수천 개 에피소드 전체 보존!
            }
        }.toMutableMap()

        // 삭제 이력 매립
        deletedMap.forEach { (deletedKey, deleteTime) ->
            filteredPrefs[deletedKey] = (-deleteTime).toString()
        }

        val filteredDefaultPrefs = defaultPrefs.filterKeys { isAllowed(it) }

        fun buildVars(map: Map<String, Any?>): BackupVars {
            val cleanedMap = map.mapValues { (key, value) ->
                val unpacked = if (value is String) UtilKt.unpackMultipleSerialization(value) else value
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

        return BackupFile(buildVars(filteredPrefs), buildVars(filteredDefaultPrefs))
    }

    fun restore(context: Context, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean) {
        Log.d("SYNC", "[SyncBackup] restore() started.")
        
        if (restoreSettings) {
            val settingsEditor = with(DataStore) { context.getDefaultSharedPrefs() }.edit()
            backupFile.settings.stringSet?.forEach { (k, v) ->
                val dk = UtilKt.decompressKey(k)
                if (isAllowed(dk)) settingsEditor.putStringSet(dk, v)
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
                    if (isCoreSyncKey(dk) && valueLong != null && valueLong < 0L) {
                        val deletionTime = -valueLong
                        val localValue = localPrefs[dk] as? String
                        val localTime = if (localValue != null) extractUpdateTime(localValue) else 0L
                        
                        if (localTime <= deletionTime) {
                            datastoreEditor.remove(dk)
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
        Log.d("SYNC", "[SyncBackup] restore() finished.")
    }
}

object ApiUtils {
    private val isSyncing = AtomicBoolean(false)

    private fun isLoggedIn(): Boolean {
        val context = AcraApplication.context ?: return false
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val token = prefs.getString("sync_token", null)?.let { 
            try { mapper.readValue<String>(it) } catch(e: Exception) { null } 
        }
        return !token.isNullOrEmpty()
    }

    private suspend fun apiCall(url: String, method: String, body: Any? = null): String? {
        val context = AcraApplication.context ?: return null
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val token = prefs.getString("sync_token", null)?.let { 
            try { mapper.readValue<String>(it) } catch(e: Exception) { null } 
        } ?: return null

        val headers = mapOf(
            "Authorization" to "Bearer $token",
            "Accept" to "application/vnd.github+json",
            "X-GitHub-Api-Version" to "2022-11-28"
        )

        return try {
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
            val listJson = apiCall("https://api.github.com/gists", "GET") ?: return Pair(false, "Auth failed.")
            val gists: List<GistRes> = try { mapper.readValue(listJson) } catch (e: Exception) { emptyList() }
            val existingGist = gists.find { it.description == "Cloudstream Sync Backup" }
            
            if (existingGist != null) {
                val gistId = existingGist.id ?: return Pair(false, "No ID")
                AcraApplication.setKey("sync_project_num", gistId) 
                AcraApplication.setKey("sync_gist_id", gistId)
                return Pair(true, "Connected to existing Gist!")
            } else {
                val createPayload = mapOf("description" to "Cloudstream Sync Backup", "public" to false, "files" to mapOf("init.txt" to mapOf("content" to "Initialized")))
                val createJson = apiCall("https://api.github.com/gists", "POST", createPayload) ?: return Pair(false, "Create failed.")
                val newGist: GistRes = try { mapper.readValue(createJson) } catch (e: Exception) { return Pair(false, "Parse failed.") }
                val gistId = newGist.id ?: return Pair(false, "No ID")
                AcraApplication.setKey("sync_project_num", gistId)
                AcraApplication.setKey("sync_gist_id", gistId)
                return Pair(true, "Auto-created Gist!")
            }
        } else {
            val gistJson = apiCall("https://api.github.com/gists/$customGistId", "GET") ?: return Pair(false, "Invalid Gist ID")
            AcraApplication.setKey("sync_gist_id", customGistId)
            return Pair(true, "Connected!")
        }
    }

    suspend fun fetchDevices(): List<SyncDevice> {
        if (!isLoggedIn()) return emptyList()
        val context = AcraApplication.context ?: return emptyList()
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val gistId = prefs.getString("sync_project_num", null)?.let { 
            try { mapper.readValue<String>(it) } catch(e: Exception) { null } 
        } ?: return emptyList()
        
        val gistJson = apiCall("https://api.github.com/gists/$gistId", "GET") ?: return emptyList()
        val gist: GistRes = try { mapper.readValue(gistJson) } catch (e: Exception) { return emptyList() }

        val files = gist.files ?: return emptyList()
        return files.filter { it.key.startsWith("device_") && it.key.endsWith(".json") }
            .mapNotNull { (_, gistFile) ->
                val content = gistFile.content ?: return@mapNotNull null
                val payload: GistPayload = try { mapper.readValue(content) } catch (e: Exception) { return@mapNotNull null }
                SyncDevice(payload.deviceName, payload.deviceId, gistFile.filename ?: "", payload.data, payload.updatedAt, gistFile.filename ?: "")
            }.sortedBy { it.updatedAt }
    }

    suspend fun deleteDevice(projectId: String, itemId: String): Boolean {
        val context = AcraApplication.context ?: return false
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val gistId = prefs.getString("sync_project_num", null)?.let { 
            try { mapper.readValue<String>(it) } catch(e: Exception) { null } 
        } ?: return false

        val deletePayload = """{"files":{"$itemId":null}}"""
        return apiCall("https://api.github.com/gists/$gistId", "PATCH", deletePayload) != null
    }

    suspend fun syncThisDevice(backupData: String, context: Context? = AcraApplication.context): Pair<Boolean, String> {
        if (!isLoggedIn()) return Pair(false, "Not logged in")
        
        if (!isSyncing.compareAndSet(false, true)) {
            return Pair(false, "Sync in progress")
        }
        
        try {
            val prefs = with(DataStore) { context?.getSharedPrefs() } ?: return Pair(false, "Context error")
            val gistId = prefs.getString("sync_project_num", null)?.let { mapper.readValue<String>(it) } ?: return Pair(false, "Gist ID missing")
            val deviceId = prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) } 
                ?: UtilKt.getDeviceId(context?.packageName ?: "", context ?: AcraApplication.context!!)
            val deviceName = prefs.getString("device_name", null)?.let { mapper.readValue<String>(it) } ?: Build.MODEL
            
            val compressedData = UtilKt.compressJsonToString(backupData)
            val payload = GistPayload(
                deviceName = deviceName,
                deviceId = deviceId,
                updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US).apply {
                    timeZone = java.util.TimeZone.getTimeZone("UTC")
                }.format(java.util.Date()),
                data = compressedData
            )

            val updatePayload = mapOf("files" to mapOf("device_${deviceId}.json" to mapOf("content" to mapper.writeValueAsString(payload))))
            val responseJson = apiCall("https://api.github.com/gists/$gistId", "PATCH", updatePayload)
            return if (responseJson != null) Pair(true, "Updated!") else Pair(false, "Failed upload")
        } finally {
            isSyncing.set(false)
        }
    }
}

// ==========================================
// String & Compression Utilities (기존 Gist 포맷 100% 보존)
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
        "poster" to "v29"
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
                } else break
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
            val decodedBytes = Base64.decode(encoded, Base64.DEFAULT)
            if (decodedBytes.size >= 2 && decodedBytes[0] == 0x1F.toByte() && decodedBytes[1] == 0x8B.toByte()) {
                val bis = ByteArrayInputStream(decodedBytes)
                GZIPInputStream(bis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(decodedBytes, Charsets.UTF_8)
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun getDeviceId(packageName: String, context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) return md5(packageName + androidId)
        return md5(packageName + "${Build.BRAND}_${Build.MODEL}")
    }

    fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        return md.digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
