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
        "cat_toggle_", "ext_toggle_", "PLUGINS_KEY", "PLUGINS_KEY_LOCAL"
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
                int = map.filterValues { it is Int } as Map<String, Integer>,
                string = map.filterValues { it is String } as Map<String, String>,
                float = map.filterValues { it is Float } as Map<String, Float>,
                long = map.filterValues { it is Long } as Map<String, Long>,
                stringSet = map.filterValues { it is Set<*> } as Map<String, Set<String>>
            )
        }

        return BackupFile(buildVars(filteredPrefs), BackupVars())
    }

    private fun <T> restoreMap(context: Context, map: Map<String, T>?, isEditingAppSettings: Boolean) {
        if (map == null) return
        
        if (isEditingAppSettings) {
            val defaultEditor = with(DataStore) { context.getDefaultSharedPrefs() }.edit()
            for ((key, value) in map) {
                if (isBackup(key)) {
                    Editor(defaultEditor).setKeyRaw(key, value)
                }
            }
            defaultEditor.apply()
        } else {
            for ((key, value) in map) {
                if (isBackup(key)) {
                    try {
                        // ★ 해결: with(DataStore) 범위를 지정하여 context 객체 위에서 확장 함수인 setKey를 성공적으로 호출합니다.
                        with(DataStore) {
                            context.setKey(key, value)
                        }
                    } catch (e: Exception) {
                        Log.d("SYNC", "Failed to restore key via DataStore: $key")
                    }
                }
            }
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
    fun compressJsonToString(json: String): String {
        val bos = ByteArrayOutputStream()
        GZIPOutputStream(bos).use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
    }

    fun decompressJsonFromString(encoded: String): String {
        return try {
            val bytes = Base64.decode(encoded, Base64.DEFAULT)
            if (bytes.size >= 2 && bytes[0] == 0x1F.toByte() && bytes[1] == 0x8B.toByte()) {
                val bis = ByteArrayInputStream(bytes)
                GZIPInputStream(bis).bufferedReader(Charsets.UTF_8).use { it.readText() }
            } else {
                String(bytes, Charsets.UTF_8)
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
