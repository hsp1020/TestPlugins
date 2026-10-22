package com.SyncCS

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties

internal data class RemoteEntry(
    val key: String, val cipher: String?, val deleted: Boolean,
    val updatedAt: String, val device: String, val version: Long
)

internal class SyncConfiguration(context: Context) {
    private companion object {
        const val PROJECT_URL = "https://zbmofheqyswjbziecmyn.supabase.co"
        const val PUBLISHABLE_KEY = "sb_publishable_AwA37RNRVIv2thMUnepm-g_urhqAUy2"
    }
    val preferences = context.applicationContext.getSharedPreferences("SyncCS_Private", Context.MODE_PRIVATE)
    val url: String get() = PROJECT_URL
    val anonKey: String get() = PUBLISHABLE_KEY
    var email: String
        get() = preferences.getString("email", "")!!
        set(value) { preferences.edit().putString("email", value.trim()).apply() }
    var deviceName: String
        get() = preferences.getString("device_name", android.os.Build.MODEL)!!
        set(value) { preferences.edit().putString("device_name", value).apply() }
    val deviceId: String
        get() {
            val existing = preferences.getString("device_id", null)
            if (existing != null) return existing
            val id = java.util.UUID.randomUUID().toString()
            preferences.edit().putString("device_id", id).commit()
            return id
        }
    // Existing Supabase sessions are intentionally inactive after this upgrade.
    // Explicit GitHub connection is mandatory; never upload legacy data.
    fun active(): Boolean =
        preferences.getString("sync_transport", null) == "github" &&
        preferences.contains("github_token") &&
        preferences.contains("github_login") &&
        preferences.contains("github_account_id") &&
        preferences.contains("secret")
    fun clearAccount() {
        val editor = preferences.edit()
        for (key in preferences.all.keys) {
            if (key.startsWith("modified/") || key.startsWith("file_")) editor.remove(key)
        }
        check(editor.remove("session").remove("secret").remove("base")
            .remove("configured").remove("oauth_pending")
            .remove("pending_plugins").remove("plugin_baseline")
            .remove("remote_cursor").remove("remote_versions")
            .remove("plugin_remote_snapshot").remove("profile_remote_snapshot")
            .remove("profile_baseline").remove("pending_conflicts")
            .remove("list_baselines")
            .remove("github_token").remove("github_login")
            .remove("github_account_id").remove("github_base_revision")
            .remove("github_generation").remove("sync_transport")
            .remove("account_switch_blocked")
            .commit())
    }
}

internal object LocalVault {
    private const val KEY_ALIAS = "synccs_local_protection_v2"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = javax.crypto.KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build()
        )
        return generator.generateKey()
    }
    fun encrypt(plain: ByteArray): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        // AndroidKeyStore requires the provider to generate the IV for AES-GCM encryption.
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        check(iv.size == 12) { "Unexpected AndroidKeyStore GCM nonce length" }
        val ciphertext = cipher.doFinal(plain)
        return Base64.encodeToString(iv + ciphertext, Base64.NO_WRAP)
    }
    fun decrypt(data: String): ByteArray {
        val bytes = Base64.decode(data, Base64.DEFAULT)
        require(bytes.size > 28)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return cipher.doFinal(bytes.copyOfRange(12, bytes.size))
    }
}

internal class SyncCipher(private val key: ByteArray) {
    private val random = SecureRandom()
    fun encrypt(itemKey: String, data: String): String {
        val iv = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        cipher.updateAAD(itemKey.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(iv + cipher.doFinal(data.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
    }
    fun decrypt(itemKey: String, payload: String): String {
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        require(bytes.size >= 29)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.updateAAD(itemKey.toByteArray(Charsets.UTF_8))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
    }
    companion object {
        fun derive(password: String, url: String, userId: String): SyncCipher {
            require(password.length >= 12) { "Recovery passphrase must have at least 12 characters." }
            val salt = MessageDigest.getInstance("SHA-256")
                .digest(("SyncCS-v2|" + url + "|" + userId).toByteArray(Charsets.UTF_8))
            val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
            val derived = try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
            finally { spec.clearPassword() }
            return SyncCipher(derived)
        }
        fun storeGithub(config: SyncConfiguration, password: String, userId: String) {
            val cipher = derive(password, "https://api.github.com", userId)
            check(config.preferences.edit().putString("secret",
                LocalVault.encrypt(cipher.key)).commit())
        }

        fun saved(config: SyncConfiguration): SyncCipher {
            val value = config.preferences.getString("secret", null) ?: error("Missing recovery key")
            return SyncCipher(LocalVault.decrypt(value))
        }
        fun store(config: SyncConfiguration, password: String, userId: String) {
            val cipher = derive(password, config.url, userId)
            config.preferences.edit().putString("secret", LocalVault.encrypt(cipher.key)).apply()
        }
    }
}

internal data class GithubLoginRequest(val state: String, val browserUrl: String)

internal class SyncBackend(private val config: SyncConfiguration) : SyncStorage {
    private data class Session(val userId: String, val access: String, val refresh: String, val expiry: Long)
    private var cached: Session? = null

    private fun call(
        relative: String, method: String, bearer: String? = null,
        data: ByteArray? = null, contentType: String = "application/json",
        extra: Map<String, String> = emptyMap()
    ): ByteArray {
        val conn = URL(config.url + relative).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.setRequestProperty("apikey", config.anonKey)
        conn.setRequestProperty("Accept", "application/json")
        if (!bearer.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer " + bearer)
        for ((name, value) in extra) conn.setRequestProperty(name, value)
        if (data != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", contentType)
            conn.outputStream.use { it.write(data) }
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)
            if (code !in 200..299) {
                val detail = String(bytes, Charsets.UTF_8).take(250)
                throw IOException("Sync server HTTP " + code + ": " + detail)
            }
            return bytes
        } finally {
            conn.disconnect()
        }
    }

    private fun encode(json: JSONObject): ByteArray = json.toString().toByteArray(Charsets.UTF_8)
    private fun decode(bytes: ByteArray): JSONObject = JSONObject(String(bytes, Charsets.UTF_8))
    private fun randomToken(): String {
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        return Base64.encodeToString(bytes, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE)
    }

    fun beginGithubLogin(autoReturn: Boolean = false): GithubLoginRequest {
        val authSettings = decode(call("/auth/v1/settings", "GET"))
        require(authSettings.optJSONObject("external")?.optBoolean("github") == true) {
            "GitHub login is not enabled on the SyncCS server yet."
        }
        val state = randomToken()
        val verifier = randomToken()
        val challengeBytes = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.UTF_8))
        val challenge = Base64.encodeToString(
            challengeBytes, Base64.NO_PADDING or Base64.NO_WRAP or Base64.URL_SAFE
        )
        val redirect = android.net.Uri.parse(config.url + "/functions/v1/synccs-github-oauth")
            .buildUpon()
            .apply { if (autoReturn) appendPath("app-return") }
            .appendPath(state).build().toString()
        val uri = android.net.Uri.parse(config.url + "/auth/v1/authorize").buildUpon()
            .appendQueryParameter("provider", "github")
            .appendQueryParameter("redirect_to", redirect)
            .appendQueryParameter("flow_type", "pkce")
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "s256")
            .build().toString()
        val pending = JSONObject().put("state", state).put("verifier", verifier)
            .put("created_at", System.currentTimeMillis())
        check(config.preferences.edit().putString(
            "oauth_pending",
            LocalVault.encrypt(pending.toString().toByteArray(Charsets.UTF_8))
        ).commit())
        return GithubLoginRequest(state, uri)
    }

    fun pollGithubLogin(state: String): String? {
        val reply = decode(call(
            "/functions/v1/synccs-github-oauth", "POST",
            data = encode(JSONObject().put("state", state))
        ))
        return when (reply.optString("status")) {
            "pending" -> null
            "approved" -> reply.getString("code")
            "expired" -> throw IOException("GitHub login expired; try again.")
            else -> throw IOException("Unexpected GitHub login response.")
        }
    }

    fun completeGithubLogin(state: String, authCode: String, recoveryPassphrase: String) {
        require(recoveryPassphrase.length >= 12) { "Recovery passphrase needs at least 12 characters." }
        val packed = config.preferences.getString("oauth_pending", null)
            ?: throw IOException("GitHub login request was not found.")
        val pending = JSONObject(String(LocalVault.decrypt(packed), Charsets.UTF_8))
        require(pending.getString("state") == state) { "GitHub login state mismatch." }
        require(System.currentTimeMillis() - pending.getLong("created_at") in 0L..300000L) {
            "GitHub login request expired."
        }
        val answer = decode(call(
            "/auth/v1/token?grant_type=pkce", "POST",
            data = encode(JSONObject()
                .put("auth_code", authCode)
                .put("code_verifier", pending.getString("verifier")))
        ))
        val user = answer.optJSONObject("user") ?: error("Missing authenticated GitHub user.")
        val session = Session(
            user.getString("id"),
            answer.getString("access_token"),
            answer.getString("refresh_token"),
            System.currentTimeMillis() / 1000L + answer.optLong("expires_in", 3600L)
        )
        val derived = SyncCipher.derive(recoveryPassphrase, config.url, session.userId)
        val newKey = newCipherKey(recoveryPassphrase, session.userId)
        val priorUser = config.preferences.getString("session", null)?.let {
            try { JSONObject(String(LocalVault.decrypt(it), Charsets.UTF_8)).optString("user_id") }
            catch (_: Exception) { null }
        }
        val previousAccount = priorUser?.takeIf { it.isNotBlank() }
            ?: config.preferences.getString("last_user_id", null)
        val savedKey = config.preferences.getString("secret", null)
        if (priorUser == session.userId && savedKey != null) {
            require(MessageDigest.isEqual(LocalVault.decrypt(savedKey), newKey)) {
                "Recovery passphrase differs from the key saved on this device."
            }
        }
        cached = session
        try {
            val sample = list().firstOrNull { !it.deleted && it.cipher != null }
            if (sample != null) derived.decrypt(sample.key, sample.cipher!!)
        } catch (error: Exception) {
            cached = null
            throw IOException("Recovery passphrase cannot decrypt this account's existing sync data.", error)
        }
        val editor = config.preferences.edit()
            .putString("secret", LocalVault.encrypt(newKey))
            .putString("last_user_id", session.userId)
            .remove("oauth_pending")
        if (previousAccount != session.userId) {
            editor.remove("base").remove("pending_plugins").remove("plugin_baseline")
                .remove("remote_cursor").remove("remote_versions")
                .remove("plugin_remote_snapshot").remove("profile_remote_snapshot")
                .remove("profile_baseline").remove("pending_conflicts")
                .remove("list_baselines")
            for (key in config.preferences.all.keys) {
                if (key.startsWith("modified/") || key.startsWith("file_")) editor.remove(key)
            }
            // Do not automatically upload previous account's local data to the new account.
            if (!previousAccount.isNullOrBlank()) editor.putBoolean("account_switch_blocked", true)
        }
        check(editor.commit())
        config.email = user.optString("email", "")
        persist(session)
    }

    private fun newCipherKey(password: String, userId: String): ByteArray {
        val salt = MessageDigest.getInstance("SHA-256")
            .digest(("SyncCS-v2|" + config.url + "|" + userId).toByteArray(Charsets.UTF_8))
        val spec = PBEKeySpec(password.toCharArray(), salt, 210_000, 256)
        return try { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded }
        finally { spec.clearPassword() }
    }

    private fun persist(session: Session) {
        cached = session
        config.preferences.edit().putString("session",
            LocalVault.encrypt(JSONObject().put("user_id", session.userId)
                .put("access", session.access).put("refresh", session.refresh)
                .put("expiry", session.expiry).toString().toByteArray(Charsets.UTF_8))).apply()
    }

    private fun session(): Session {
        val old = cached ?: run {
            val packed = config.preferences.getString("session", null) ?: error("Sign in first")
            val json = JSONObject(String(LocalVault.decrypt(packed), Charsets.UTF_8))
            Session(json.getString("user_id"), json.getString("access"),
                json.getString("refresh"), json.getLong("expiry"))
        }
        if (old.expiry > System.currentTimeMillis() / 1000L + 120) {
            cached = old
            return old
        }
        val refreshed = decode(call("/auth/v1/token?grant_type=refresh_token",
            "POST", data = encode(JSONObject().put("refresh_token", old.refresh))))
        val next = Session(old.userId, refreshed.getString("access_token"),
            refreshed.optString("refresh_token", old.refresh),
            System.currentTimeMillis() / 1000L + refreshed.optLong("expires_in", 3600L))
        persist(next)
        return next
    }

    override fun userId(): String = session().userId

    private fun readEntry(row: JSONObject): RemoteEntry = RemoteEntry(
        row.getString("item_key"),
        row.optString("payload").takeUnless { row.isNull("payload") },
        row.optBoolean("deleted"), row.optString("updated_at"), row.optString("device_id"),
        row.optLong("version")
    )

    override fun getEntry(key: String): RemoteEntry? {
        val encoded = java.net.URLEncoder.encode(key, "UTF-8")
        val reply = call("/rest/v1/sync_entries?select=item_key,payload,deleted,updated_at,device_id,version" +
            "&item_key=eq." + encoded + "&limit=1", "GET", session().access)
        val rows = JSONArray(String(reply, Charsets.UTF_8))
        return if (rows.length() == 0) null else readEntry(rows.getJSONObject(0))
    }

    fun list(): List<RemoteEntry> {
        val token = session().access
        val out = mutableListOf<RemoteEntry>()
        val seenKeys = HashSet<String>()
        var cursor: String? = null
        while (true) {
            val cursorFilter = cursor?.let {
                "&item_key=gt." + java.net.URLEncoder.encode(it, "UTF-8")
            } ?: ""
            val rows = JSONArray(String(call("/rest/v1/sync_entries?select=item_key,payload,deleted,updated_at,device_id,version" +
                "&order=item_key.asc&limit=500" + cursorFilter, "GET", token), Charsets.UTF_8))
            if (rows.length() == 0) break
            for (index in 0 until rows.length()) {
                val row = readEntry(rows.getJSONObject(index))
                check(seenKeys.add(row.key)) { "Sync page returned a duplicate item key" }
                cursor = row.key
                out.add(row)
            }
            check(out.size < 100000) { "Sync entry count exceeds supported page limit" }
            if (rows.length() < 500) break
        }
        return out
    }


    data class PullResult(
        val entries: List<RemoteEntry>,
        val cursor: Long?,
        val full: Boolean
    )

    /**
     * A missing event table is an unmigrated v339 server, not an empty delta.
     * In that case keep using the original full listing.
     */
    private fun newestEventId(): Long? {
        val body = try {
            call("/rest/v1/sync_change_events?select=event_id&order=event_id.desc&limit=1",
                "GET", session().access)
        } catch (error: IOException) {
            if (error.message.orEmpty().contains("HTTP 404")) return null
            throw error
        }
        val events = JSONArray(String(body, Charsets.UTF_8))
        return if (events.length() == 0) 0L else events.getJSONObject(0).getLong("event_id")
    }

    private fun changedSince(startCursor: Long): Pair<List<RemoteEntry>, Long> {
        val token = session().access
        var cursor = startCursor
        val names = linkedSetOf<String>()
        while (true) {
            val events = JSONArray(String(call(
                "/rest/v1/sync_change_events?select=event_id,item_key" +
                    "&event_id=gt." + cursor + "&order=event_id.asc&limit=500",
                "GET", token
            ), Charsets.UTF_8))
            if (events.length() == 0) break
            for (index in 0 until events.length()) {
                val event = events.getJSONObject(index)
                val id = event.getLong("event_id")
                check(id > cursor) { "Sync change cursor did not advance" }
                cursor = id
                names.add(event.getString("item_key"))
            }
            check(names.size <= 100000) { "Sync delta exceeds supported page limit" }
            if (events.length() < 500) break
        }
        val result = mutableListOf<RemoteEntry>()
        for (keys in names.toList().chunked(30)) {
            val inFilter = keys.joinToString(",") { "\"" + it.replace("\\", "\\\\").replace("\"", "\\\"") + "\"" }
            val encoded = java.net.URLEncoder.encode("(" + inFilter + ")", "UTF-8")
            val rows = JSONArray(String(call(
                "/rest/v1/sync_entries?select=item_key,payload,deleted,updated_at,device_id,version" +
                    "&item_key=in." + encoded,
                "GET", token
            ), Charsets.UTF_8))
            for (index in 0 until rows.length()) result.add(readEntry(rows.getJSONObject(index)))
        }
        check(result.size == names.size) { "Sync event referenced a missing server entry" }
        return result to cursor
    }

    override fun pull(previousCursor: Long?): PullResult {
        if (previousCursor == null) {
            // Taking the cursor BEFORE the snapshot and replaying its events
            // prevents a write concurrent with the snapshot from being lost.
            val start = newestEventId()
            val snapshot = list()
            if (start == null) return PullResult(snapshot, null, true)
            val (delta, cursor) = changedSince(start)
            val items = snapshot.associateByTo(linkedMapOf()) { it.key }
            delta.forEach { items[it.key] = it }
            return PullResult(items.values.toList(), cursor, true)
        }
        return try {
            val (delta, cursor) = changedSince(previousCursor)
            PullResult(delta, cursor, false)
        } catch (error: IOException) {
            if (!error.message.orEmpty().contains("HTTP 404")) throw error
            PullResult(list(), null, true)
        }
    }

    override fun upload(changes: Map<String, String?>, cipher: SyncCipher, revisions: Map<String, Long>) {
        if (changes.isEmpty()) return
        val current = session()
        changes.entries.chunked(50).forEach { batch ->
            val json = JSONArray()
            for ((key, value) in batch) {
                json.put(JSONObject().put("item_key", key)
                    .put("device_id", config.deviceId).put("deleted", value == null)
                    .put("expected_version", revisions[key] ?: JSONObject.NULL)
                    .put("payload", if (value == null) JSONObject.NULL else cipher.encrypt(key, value)))
            }
            call("/rest/v1/rpc/sync_apply_changes", "POST", current.access,
                encode(JSONObject().put("p_changes", json)))
        }
    }

    override fun uploadFile(hash: String, bytes: ByteArray) {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val s = session()
        call("/storage/v1/object/sync-files/" + s.userId + "/" + hash + ".cs3",
            "POST", s.access, bytes, "application/octet-stream", mapOf("x-upsert" to "true"))
    }

    override fun downloadFile(hash: String): ByteArray {
        require(hash.matches(Regex("[a-f0-9]{64}")))
        val s = session()
        return call("/storage/v1/object/sync-files/" + s.userId + "/" + hash + ".cs3", "GET", s.access)
    }

    override fun cipher(): SyncCipher = SyncCipher.saved(config)
    companion object {
        fun digest(data: String): String =
            MessageDigest.getInstance("SHA-256").digest(data.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        fun digestBytes(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
