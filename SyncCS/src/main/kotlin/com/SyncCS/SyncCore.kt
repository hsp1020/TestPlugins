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
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

// =========================================================================
// [1] Native SharedPreferences Helper
// =========================================================================

object SharedPrefsHelper {
    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences("DataStore", Context.MODE_PRIVATE)
    }

    fun getDefaultPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(context.packageName + "_preferences", Context.MODE_PRIVATE)
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
                            @JsonProperty("bodyText") var bodyText: String? = null,
                            @JsonProperty("body") var body: String? = null // GitHub API 업데이트 대응 (body / bodyText 유연성 확보)
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
// [3] Util Area
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
            Log.e("SyncCS", "Decompression Error", e)
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
            Log.e("SyncCS", "Encryption Error", e)
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
            Log.e("SyncCS", "Decryption Error", e)
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
// [4] BackupUtils Area
// =========================================================================

object BackupUtils {

    private val nonTransferableKeys = listOf(
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
        val pref = SharedPrefsHelper.getPrefs(context)
        val defaultPref = SharedPrefsHelper.getDefaultPrefs(context)

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

    fun restoreMap(context: Context, incoming: Map<String, out Any?>?, isAppSettings: Boolean, syncPlugins: Boolean = false) {
        if (incoming.isNullOrEmpty()) return
        val pref = if (isAppSettings) SharedPrefsHelper.getDefaultPrefs(context) else SharedPrefsHelper.getPrefs(context)
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
        } catch (e: Exception) {
            OkHttpClient()
        }
    }

    fun isLoggedIn(): Boolean {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return false
        val pref = SharedPrefsHelper.getPrefs(context)
        val token = pref.getString("sync_token", null)
        val projectNum = pref.getString("sync_project_num", null)
        val projectId = pref.getString("sync_project_id", null)
        return !token.isNullOrEmpty() && !projectNum.isNullOrEmpty() && !projectId.isNullOrEmpty()
    }

    suspend fun apiCall(query: String, token: String): APIRes? = withContext(Dispatchers.IO) {
        try {
            Log.d("SyncCS", "--- [API CALL START] ---")
            val formattedQuery = query.replace("\n", " ").replace(Regex("\\s+"), " ")
            val bodyMap = mapOf("query" to formattedQuery)
            val bodyStr = mapper.writeValueAsString(bodyMap)

            Log.d("SyncCS", "Request Body: $bodyStr")
            Log.d("SyncCS", "Token used: ${token.take(6)}...")

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

            Log.d("SyncCS", "HTTP Response Code: $code")
            Log.d("SyncCS", "Raw Server Response: $responseBody")
            Log.d("SyncCS", "--- [API CALL END] ---")

            if (code != 200) {
                Log.e("SyncCS", "Server returned error code: $code")
                return@withContext null
            }

            mapper.readValue(responseBody, APIRes::class.java)
        } catch (e: Exception) {
            Log.e("SyncCS", "Network Exception in apiCall", e)
            null
        }
    }

    suspend fun syncProjectDetails(context: Context?): Pair<Boolean, String> {
        Log.d("SyncCS", "syncProjectDetails Called")
        if (context == null) return Pair(false, "No context")
        val pref = SharedPrefsHelper.getPrefs(context)
        val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return Pair(false, "Missing token")
        val projectNum = pref.getString("sync_project_num", null)?.replace("\"", "")?.toIntOrNull() ?: return Pair(false, "Invalid project number")

        // 타입 오류 수정: ... on ProjectV2ItemDraftIssue -> ... on DraftIssue 로 전면 수정
        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) {
                            nodes {
                                id
                                content {
                                    ... on DraftIssue {
                                        id
                                        title
                                        body
                                    }
                                }
                            }
                        }
                    }
                }
            }
        """.trimIndent()

        val res = apiCall(query, token)
        
        if (res == null) {
            Log.e("SyncCS", "Response object is completely null")
            return Pair(false, "Failed to connect to GitHub. Check Logcat.")
        }

        if (res.errors != null && res.errors!!.isNotEmpty()) {
            val errorMsg = res.errors!!.joinToString(", ") { it.message ?: "Unknown Error" }
            Log.e("SyncCS", "GraphQL returned Errors: $errorMsg")
            return Pair(false, "GitHub Error: $errorMsg")
        }

        val project = res.data?.viewer?.projectV2
        if (project == null) {
            Log.e("SyncCS", "viewer.projectV2 is null. Check Project Number, or your token permissions.")
            return Pair(false, "Failed to connect to GitHub or project not found")
        }

        val projectId = project.id
        if (projectId.isNullOrEmpty()) {
            Log.e("SyncCS", "Project ID missing from JSON")
            return Pair(false, "Project ID not found in response")
        }

        pref.edit().putString("sync_project_id", projectId).apply()
        Log.d("SyncCS", "Successfully found Project ID: $projectId")

        return Pair(true, "Successfully linked to GitHub Project!")
    }

    suspend fun syncThisDevice(syncData: String, context: Context): Pair<Boolean, String> {
        val pref = SharedPrefsHelper.getPrefs(context)
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
        val pref = SharedPrefsHelper.getPrefs(context)
        val token = pref.getString("sync_token", null)?.replace("\"", "") ?: return emptyList()
        val projectNum = pref.getString("sync_project_num", null)?.replace("\"", "")?.toIntOrNull() ?: return emptyList()

        // 타입 오류 수정: ... on ProjectV2ItemDraftIssue -> ... on DraftIssue 로 전면 수정
        val query = """
            query {
                viewer {
                    projectV2(number: $projectNum) {
                        id
                        items(first: 100) {
                            nodes {
                                id
                                content {
                                    ... on DraftIssue {
                                        id
                                        title
                                        body
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
            
            // bodyText가 아닌 바뀐 스키마인 body로 추출
            val body = node.content?.body ?: node.content?.bodyText ?: ""

            val parts = title.split("|")
            if (parts.size >= 2) {
                list.add(SyncDevice(parts[0], parts[1], itemId, Util.decrypt(body, token)))
            }
        }
        return list
    }
}
