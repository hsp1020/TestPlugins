package com.anhdaden

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainAPIKt
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper.ResumeWatchingResult
import com.lagradost.nicehttp.NiceHttp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.text.Charsets.UTF_8

// =========================================================================
// [1] Models Area (데이터 구조 및 GraphQL 규격 데이터 클래스 정의 영역)
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
        data class Viewer(
            @JsonProperty("projectV2") var projectV2: ProjectV2? = null
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class ProjectV2(
                @JsonProperty("id") var id: String? = null,
                @JsonProperty("items") var items: Items? = null
            ) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                data class Items(
                    @JsonProperty("nodes") var nodes: Array<Node>? = null
                ) {
                    @JsonIgnoreProperties(ignoreUnknown = true)
                    data class Node(
                        @JsonProperty("id") var id: String? = null,
                        @JsonProperty("content") var content: Content? = null
                    ) {
                        @JsonIgnoreProperties(ignoreUnknown = true)
                        data class Content(
                            @JsonProperty("id") var id: String? = null,
                            @JsonProperty("title") var title: String? = null,
                            @JsonProperty("bodyText") var bodyText: String? = null
                        )
                    }
                }
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class Issue(
            @JsonProperty("projectItem") var projectItem: ProjectItem? = null
        ) {
            @JsonIgnoreProperties(ignoreUnknown = true)
            data class ProjectItem(
                @JsonProperty("id") var id: String? = null,
                @JsonProperty("content") var content: Content? = null
            ) {
                @JsonIgnoreProperties(ignoreUnknown = true)
                data class Content(
                    @JsonProperty("id") var id: String? = null
                )
            }
        }

        @JsonIgnoreProperties(ignoreUnknown = true)
        data class DelItem(
            @JsonProperty("deletedItemId") var deletedItemId: String? = null
        )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Error(
        @JsonProperty("message") var message: String? = null
    )
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
// [2] Util Area (최적화 가공, 단말 디바이스 식별 및 암호화 구현 영역)
// =========================================================================

object Util {

    fun cleanedSyncData(str: String): String {
        return try {
            str.replace(Regex("\\\\\\\"overview\\\\\\\"\\s*:\\s*\\\\\\\"(.*?)(?<!\\\\)\\\\\\\""), "\\\\\\\"overview\\\\\\\":\\\\\\\"\\\\\\\"")
               .replace(Regex("\\\\\\\"plot\\\\\\\"\\s*:\\s*\\\\\\\"(.*?)(?<!\\\\)\\\\\\\""), "\\\\\\\"plot\\\\\\\":\\\\\\\"\\\\\\\"")
               .replace(Regex("\\\\\\\"tags\\\\\\\"\\s*:\\s*\\[.*?]"), "\\\\\\\"tags\\\\\\\":[]")
        } catch (e: Exception) {
            str
        }
    }

    fun restoreText(str: String): String {
        return str
    }

    fun compressJsonToString(str: String): String {
        val bytes = str.toByteArray(UTF_8)
        return Base64.encodeToString(bytes, Base64.NO_WRAP or Base64.NO_PADDING)
    }

    fun decompressJsonFromString(str: String): String {
        return try {
            String(Base64.decode(str, Base64.NO_WRAP or Base64.NO_PADDING), UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    fun encrypt(str: String, keySeed: String): String {
        return try {
            val keyBytes = sha256(keySeed)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = ByteArray(12).apply { SecureRandom().nextBytes(this) }
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            val encryptedBytes = cipher.doFinal(str.toByteArray(UTF_8))
            
            val combined = ByteArray(iv.size + encryptedBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(encryptedBytes, 0, combined, iv.size, encryptedBytes.size)
            Base64.encodeToString(combined, Base64.NO_WRAP or Base64.NO_PADDING)
        } catch (e: Exception) {
            str
        }
    }

    fun decrypt(str: String, keySeed: String): String {
        return try {
            val combined = Base64.decode(str, Base64.NO_WRAP or Base64.NO_PADDING)
            val iv = ByteArray(12)
            val encryptedBytes = ByteArray(combined.size - 12)
            System.arraycopy(combined, 0, iv, 0, 12)
            System.arraycopy(combined, 12, encryptedBytes, 0, encryptedBytes.size)

            val keyBytes = sha256(keySeed)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encryptedBytes), UTF_8)
        } catch (e: Exception) {
            str
        }
    }

    private fun sha256(input: String): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(input.toByteArray(UTF_8))
    }

    fun getDeviceId(packageName: String, context: Context): String {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        if (!androidId.isNullOrEmpty()) {
            return md5(packageName + androidId)
        }
        val serial = if (Build.VERSION.SDK_INT >= 26) {
            try { Build.getSerial() } catch (e: SecurityException) { "" }
        } else {
            Build.SERIAL
        }
        if (!serial.isNullOrEmpty() && serial != "unknown") {
            return md5(packageName + serial)
        }
        val fallback = "${Build.BRAND}_${Build.MODEL}_${Build.DEVICE}"
        return md5(packageName + UUID.nameUUIDFromBytes(fallback.toByteArray(UTF_8)).toString())
    }

    fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }
}

// =========================================================================
// [3] BackupUtils Area (차분 동기화 추출 및 로컬 데이터 동적 병합 영역)
// =========================================================================

object BackupUtils {

    val nonTransferableKeys = listOf(
        "anilist_unixtime", "anilist_token", "anilist_user", "anilist_cached_list", "anilist_accounts", "anilist_active",
        "mal_user", "mal_cached_list", "mal_unixtime", "mal_refresh_token", "mal_token", "mal_accounts", "mal_active",
        "simkl_token", "simkl_user", "simkl_cached_list", "simkl_cached_time", "simkl_accounts", "simkl_active",
        "SIMKL_API_CACHE", "ANIWAVE_SIMKL_SYNC", "open_subtitles_user", "opensubtitles_accounts", "opensubtitles_active",
        "subdl_user", "subdl_accounts", "subdl_active", "biometric_key", "nginx_user", "download_path_key",
        "download_path_key_visual", "backup_path_key", "backup_dir_path_key", "cs3-votes",
        "last_sync_api", "last_click_action", "last_opened_id", "library_folder", "result_resume_watching_migrated",
        "jsdelivr_proxy_key", "fshare_setup", "fshare_token", "checkbox_fsharehd", "checkbox_fsharecine",
        "checkbox_fsharesheet", "checkbox_fsharefavourite", "checkbox_fsharehdvn", "checkbox_timfshare",
        "bluphim_token", "device_id", "device_name", "sync_token", "sync_project_num", "sync_project_id",
        "sync_item_id", "sync_device_id", "restore_device", "backup_device", "sync_plugins_enabled", "movie_active", "dns_active",
        "last_sync_timestamp"
    )

    fun isResume(key: String, syncPlugins: Boolean = false): Boolean {
        if (syncPlugins && (key.contains("PLUGINS_KEY") || key.contains("PLUGINS_KEY_LOCAL"))) {
            return true
        }
        return nonTransferableKeys.none { key.contains(it) }
    }

    fun getDeltaBackup(context: Context, lastSyncTime: Long, syncPlugins: Boolean = false): BackupFile? {
        val dataStore = DataStore.INSTANCE
        val pref = dataStore.getSharedPrefs(context)
        val defaultPref = dataStore.getDefaultSharedPrefs(context)

        fun getDeltaMap(all: Map<String, *>): Map<String, Any?> {
            return all.filter { (key, value) ->
                if (!isResume(key, syncPlugins)) return@filter false
                if (value is String) {
                    try {
                        val obj = JSONObject(value)
                        val updateTime = obj.optLong("latestUpdatedTime", obj.optLong("bookmarkedTime", 0L))
                        updateTime > lastSyncTime
                    } catch (e: Exception) {
                        true
                    }
                } else {
                    true
                }
            }
        }

        val deltaPrefs = getDeltaMap(pref.all)
        val deltaDefaultPrefs = getDeltaMap(defaultPref.all)

        if (deltaPrefs.isEmpty() && deltaDefaultPrefs.isEmpty()) return null

        fun splitVars(map: Map<String, Any?>): BackupVars {
            return BackupVars(
                bool = map.filterValues { it is Boolean }.mapValues { it.value as Boolean },
                int = map.filterValues { it is Int }.mapValues { it.value as Int },
                string = map.filterValues { it is String }.mapValues { it.value as String },
                float = map.filterValues { it is Float }.mapValues { it.value as Float },
                long = map.filterValues { it is Long }.mapValues { it.value as Long },
                stringSet = map.filterValues { it is Set<*> }.mapValues { it.value as Set<String> }
            )
        }

        return BackupFile(
            datastore = splitVars(deltaPrefs),
            settings = splitVars(deltaDefaultPrefs)
        )
    }

    fun <T> restoreMap(context: Context, incoming: Map<String, T>?, isAppSettings: Boolean, syncPlugins: Boolean = false) {
        if (incoming.isNullOrEmpty()) return
        val pref = if (isAppSettings) DataStore.INSTANCE.getDefaultSharedPrefs(context) else DataStore.INSTANCE.getSharedPrefs(context)
        val editor = pref.edit()

        for ((key, incomingValue) in incoming) {
            if (!isResume(key, syncPlugins)) continue

            val localValue = pref.all[key]
            if (localValue != null && incomingValue is String && localValue is String) {
                val merged = resolveConflict(localValue, incomingValue)
                editor.putString(key, merged)
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

    fun restore(context: Context?, backupFile: BackupFile, restoreSettings: Boolean, restoreDataStore: Boolean, syncPlugins: Boolean = false) {
        if (context == null) return
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
// [4] ApiUtils Area (GitHub API 연계 및 GraphQL 네트워크 엔진 영역)
// =========================================================================

object ApiUtils {
    private val http = NiceHttp()

    fun isLoggedIn(): Boolean {
        val context = AcraApplication.Companion.getContext() ?: return false
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val token = pref.getString("sync_token", null)
        val projectNum = pref.getString("sync_project_num", null)
        val projectId = pref.getString("sync_project_id", null)
        return !token.isNullOrEmpty() && !projectNum.isNullOrEmpty() && !projectId.isNullOrEmpty()
    }

    suspend fun apiCall(query: String, token: String): APIRes? = withContext(Dispatchers.IO) {
        try {
            val formattedQuery = query.replace("\n", " ").replace(Regex("\\s+"), " ")
            val bodyMap = mapOf("query" to formattedQuery)
            val bodyStr = MainAPIKt.getMapper().writeValueAsString(bodyMap)

            val response = http.post(
                url = "https://api.github.com/graphql",
                headers = mapOf(
                    "Authorization" to "Bearer $token",
                    "Content-Type" to "application/json",
                    "User-Agent" to "Cloudstream-Sync"
                ),
                data = bodyStr
            ).text

            MainAPIKt.getMapper().readValue(response, APIRes::class.java)
        } catch (e: Exception) {
            null
        }
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String> {
        if (context == null) return Pair(false, "No context")
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return Pair(false, "Missing token")
        val projectNum = pref.getString("sync_project_num", null)?.replace("\"", "")?.toIntOrNull() ?: return Pair(false, "Invalid project number")

        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) {
                            nodes {
                                id
                                content {
                                    ... on ProjectV2ItemDraftIssue {
                                        id
                                        title
                                        bodyText
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(query, token)
        val project = res?.data?.viewer?.projectV2 ?: return Pair(false, "Failed to connect to GitHub or project not found")
        val projectId = project.id ?: return Pair(false, "Project ID not found")

        pref.edit()
            .putString("sync_project_id", projectId)
            .apply()

        return Pair(true, "Successfully linked to GitHub Project!")
    }

    suspend fun syncThisDevice(syncData: String, context: Context): Pair<Boolean, String> {
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return Pair(false, "Missing token")
        val projectId = pref.getString("sync_project_id", null)?.replace("\"", "") ?: return Pair(false, "Missing Project ID")
        val deviceName = pref.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        val deviceId = Util.getDeviceId(context.packageName, context)

        val storedItemId = pref.getString("sync_item_id", null)?.replace("\"", "")

        if (!storedItemId.isNullOrEmpty()) {
            val delQuery = """
                mutation {
                    deleteProjectV2Item(input: {projectId: "$projectId", itemId: "$storedItemId"}) {
                        deletedItemId
                    }
                }
            """.trimIndent()
            apiCall(delQuery, token)
        }

        val encryptedPayload = Util.encrypt(syncData, token)

        val escapedPayload = encryptedPayload.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
        val escapedName = "$deviceName|$deviceId".replace("\"", "\\\"")

        val addQuery = """
            mutation {
                addProjectV2DraftIssue(input: {projectId: "$projectId", title: "$escapedName", body: "$escapedPayload"}) {
                    projectItem {
                        id
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(addQuery, token)
        val newItemId = res?.data?.issue?.projectItem?.id

        if (!newItemId.isNullOrEmpty()) {
            pref.edit().putString("sync_item_id", newItemId).apply()
            return Pair(true, "Sync completed successfully")
        }

        return Pair(false, "Sync failed during upload")
    }

    suspend fun fetchDevices(context: Context): List<SyncDevice> {
        val pref = DataStore.INSTANCE.getSharedPrefs(context)
        val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return emptyList()
        val projectNum = pref.getString("sync_project_num", null)?.replace("\"", "")?.toIntOrNull() ?: return emptyList()

        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) {
                            nodes {
                                id
                                content {
                                    ... on ProjectV2ItemDraftIssue {
                                        id
                                        title
                                        bodyText
                                    }
                                }
                            }
                        }
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
            val body = node.content?.bodyText ?: ""

            val parts = title.split("|")
            if (parts.size >= 2) {
                val name = parts[0]
                val devId = parts[1]

                val decryptedPayload = Util.decrypt(body, token)
                list.add(SyncDevice(name, devId, itemId, decryptedPayload))
            }
        }
        return list
    }
}
