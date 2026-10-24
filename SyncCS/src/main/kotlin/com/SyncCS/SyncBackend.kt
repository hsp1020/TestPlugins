package com.SyncCS

import android.content.Context
import android.util.Base64
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
    val preferences = context.applicationContext.getSharedPreferences(
        "SyncCS_Private", Context.MODE_PRIVATE)
    var deviceName: String
        get() = preferences.getString("device_name", android.os.Build.MODEL)!!
        set(value) { preferences.edit().putString("device_name", value).apply() }
    val deviceId: String
        get() {
            val existing = preferences.getString("device_id", null)
            if (existing != null) return existing
            val id = java.util.UUID.randomUUID().toString()
            check(preferences.edit().putString("device_id", id).commit())
            return id
        }
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
        for (key in listOf(
            "secret", "base", "pending_plugins", "plugin_baseline",
            "remote_cursor", "remote_versions", "plugin_remote_snapshot",
            "profile_remote_snapshot", "profile_baseline", "pending_conflicts",
            "list_baselines", "github_token", "github_login",
            "github_account_id", "github_base_revision", "github_generation",
            "sync_transport", "account_switch_blocked",
            "github_local_recovery_saved", "github_local_restore_pause", "github_client_id",
            "github_initial_backup_confirmed", "github_recovery_in_progress",
            "session", "oauth_pending", "configured", "email",
            "github_pending_token", "github_pending_client",
            "github_refresh_token", "github_token_expires_at",
            "github_token_issued_at", "github_refresh_expires_at",
            "github_rejected_token_hash", "github_rejected_token_reason",
            "github_rejected_refresh_hash", "github_refresh_retry_after",
            "github_auth_diagnostic_history"
        )) editor.remove(key)
        check(editor.commit())
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
    fun encryptForLocalStorage(): String = LocalVault.encrypt(key)
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
        fun saved(config: SyncConfiguration): SyncCipher {
            val value = config.preferences.getString("secret", null) ?: error("Missing recovery key")
            return SyncCipher(LocalVault.decrypt(value))
        }
    }
}


/** Shared data-transfer schema and SHA utilities, with no legacy network endpoint. */
internal class SyncBackend private constructor() {
    data class PullResult(
        val entries: List<RemoteEntry>,
        val cursor: Long?,
        val full: Boolean
    )
    companion object {
        fun digest(data: String): String =
            digestBytes(data.toByteArray(Charsets.UTF_8))
        fun digestBytes(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}

/**
 * Refresh expiring OAuth tokens without issuing another device authorization.
 * Rotate access and refresh tokens together; never include either in a backup.
 */
internal object GithubOAuthSession {
    @Synchronized
    fun accessToken(config: SyncConfiguration): String {
        val prefs = config.preferences
        val encrypted = prefs.getString("github_token", null)
            ?: error("GitHub authorization is missing")
        val token = String(LocalVault.decrypt(encrypted), Charsets.UTF_8)
        val until = prefs.getLong("github_token_expires_at", 0L)
        val issuedAt = prefs.getLong("github_token_issued_at", 0L)
        val refreshExpires = prefs.getLong("github_refresh_expires_at", 0L)
        val now = System.currentTimeMillis()
        val encryptedRefresh = prefs.getString("github_refresh_token", null)
        val clientId = prefs.getString("github_client_id", null)
        val expiring = until > 0L
        val due = expiring && now + 120_000L >= until

        if (!due) return token
        if (encryptedRefresh == null || clientId == null) {

            return token
        }
        return try {
            rotate(config, prefs, clientId, encryptedRefresh, "pre-expiry")
        } catch (failure: GithubBackupStore.ApiException) {
            if (failure.code != 401) throw failure
            // OAuth refresh rejection differs from an API request's 401, but
            // without a working refresh token the session cannot continue safely.
            SyncAuthGuard.reject(config, 401)
            SyncAuthGuard.recordRefreshFailure(config, permanent = true)

            throw java.io.IOException("GitHub refresh authorization was rejected.", failure)
        }
    }

    /**
     * Recover an unexpected 401 only when this credential is still current.
     * Concurrent shard requests reuse the credential rotated by the first request.
     */
    @Synchronized
    fun recoverAfter401(config: SyncConfiguration, rejectedToken: String): String? {

        val prefs = config.preferences
        val encrypted = prefs.getString("github_token", null) ?: return null
        val saved = String(LocalVault.decrypt(encrypted), Charsets.UTF_8)
        if (saved != rejectedToken) {

            return saved
        }
        val encryptedRefresh = prefs.getString("github_refresh_token", null)
        val clientId = prefs.getString("github_client_id", null)
        if (encryptedRefresh == null || clientId == null) {
            SyncAuthGuard.reject(config, 401)

            return null
        }
        return try {
            rotate(config, prefs, clientId, encryptedRefresh, "http-401")
        } catch (error: java.io.IOException) {
            // GitHub may have rotated this pair in another loaded plugin instance.
            if (prefs.getString("github_refresh_token", null) != encryptedRefresh) {
                val current = prefs.getString("github_token", null) ?: return null
                return String(LocalVault.decrypt(current), Charsets.UTF_8)
            }
            SyncAuthGuard.reject(config, 401)
            SyncAuthGuard.recordRefreshFailure(config, permanent =
                error is GithubBackupStore.ApiException && error.code == 401)

            null
        }
    }

    /**
     * A previous API 401 must not permanently mask a valid refresh token.
     * Retry only if a usable refresh credential exists; permanently rejected
     * refresh tokens are not submitted again.
     */
    @Synchronized
    fun resumeRejected(config: SyncConfiguration): Boolean {
        if (!SyncAuthGuard.needsReauthentication(config)) return true
        if (!SyncAuthGuard.mayRecover(config)) return false
        val encrypted = config.preferences.getString("github_token", null) ?: return false
        val rejected = String(LocalVault.decrypt(encrypted), Charsets.UTF_8)

        return try {
            val renewed = recoverAfter401(config, rejected)
            if (!renewed.isNullOrBlank() && renewed != rejected) {
                SyncAuthGuard.clearAfterLogin(config)

                true
            } else {
                // The failed refresh has already been classified and rate-limited.
                false
            }
        } catch (failure: java.io.IOException) {
            SyncAuthGuard.recordRefreshFailure(config, permanent = false)

            false
        }
    }

    private fun rotate(
        config: SyncConfiguration, prefs: android.content.SharedPreferences,
        clientId: String, encryptedRefresh: String, reason: String
    ): String {

        // Another loaded SyncCS instance may already have rotated this pair.
        if (prefs.getString("github_refresh_token", null) != encryptedRefresh) {

            val current = prefs.getString("github_token", null)
                ?: error("GitHub authorization disappeared during refresh")
            return String(LocalVault.decrypt(current), Charsets.UTF_8)
        }
        val oldAccess = runCatching {
            prefs.getString("github_token", null)?.let {
                String(LocalVault.decrypt(it), Charsets.UTF_8)
            }
        }.getOrNull()

        val renewed = try {
            GithubAuthFlow.retry("refresh-token") {
                GithubDeviceCode.refresh(clientId,
                    String(LocalVault.decrypt(encryptedRefresh), Charsets.UTF_8))
            }
        } catch (failure: java.io.IOException) {

            // A losing concurrent refresh must not treat a new token pair as revoked.
            if (prefs.getString("github_refresh_token", null) != encryptedRefresh) {
                val current = prefs.getString("github_token", null)
                    ?: error("GitHub authorization disappeared during refresh")

                return String(LocalVault.decrypt(current), Charsets.UTF_8)
            }
            throw failure
        }
        // Do not overwrite a newer token pair committed by another instance.
        if (prefs.getString("github_refresh_token", null) != encryptedRefresh) {

            val current = prefs.getString("github_token", null)
                ?: error("GitHub authorization disappeared during refresh")
            return String(LocalVault.decrypt(current), Charsets.UTF_8)
        }
        val newRefresh = renewed.refresh?.takeIf { it.isNotBlank() }
            ?: throw java.io.IOException("GitHub did not return a rotating refresh credential.")
        check(prefs.edit()
            .putString("github_token", LocalVault.encrypt(renewed.access.toByteArray(Charsets.UTF_8)))
            .putString("github_refresh_token", LocalVault.encrypt(
                newRefresh.toByteArray(Charsets.UTF_8)))
            .putLong("github_token_expires_at", renewed.expiresAt)
            .putLong("github_token_issued_at", renewed.issuedAt)
            .putLong("github_refresh_expires_at", renewed.refreshExpiresAt)
            .commit()) { "Cannot persist renewed GitHub authorization" }

        return renewed.access
    }
}

// OAuth authorization is stored separately from an activated sync session.
internal object GithubAuthFlow {
    private const val PENDING_TOKEN = "github_pending_token"
    private const val PENDING_CLIENT = "github_pending_client"

    fun pending(config: SyncConfiguration, clientId: String): GithubDeviceCode.Credential? {
        if (config.preferences.getString(PENDING_CLIENT, null) != clientId) return null
        val encrypted = config.preferences.getString(PENDING_TOKEN, null) ?: return null
        return try {
            GithubDeviceCode.Credential.decode(
                String(LocalVault.decrypt(encrypted), Charsets.UTF_8))
        } catch (error: Exception) {
            clear(config)

            null
        }
    }

    fun save(config: SyncConfiguration, clientId: String, token: GithubDeviceCode.Credential) {
        check(token.access.isNotBlank()) { "Empty GitHub authorization" }
        val encrypted = LocalVault.encrypt(token.encode().toByteArray(Charsets.UTF_8))
        check(config.preferences.edit()
            .putString(PENDING_CLIENT, clientId)
            .putString(PENDING_TOKEN, encrypted)
            .commit()) { "Could not preserve pending GitHub authorization" }

    }

    fun clear(config: SyncConfiguration) {
        check(config.preferences.edit()
            .remove(PENDING_TOKEN).remove(PENDING_CLIENT).commit()) {
            "Could not clear pending GitHub authorization"
        }
    }

    fun isTransient(error: java.io.IOException): Boolean =
        when (error) {
            is GithubBackupStore.ApiException -> error.code in setOf(500, 502, 503, 504)
            is java.net.UnknownHostException, is java.net.SocketTimeoutException,
            is java.net.ConnectException, is java.net.SocketException -> true
            else -> false
        }

    fun httpCode(error: Exception): Int =
        (error as? GithubBackupStore.ApiException)?.code ?: 0

    fun <T> retry(step: String, action: () -> T): T {
        repeat(3) { attempt ->
            try {
                return action()
            } catch (error: java.io.IOException) {
                if (!isTransient(error) || attempt == 2) throw error

                try {
                    Thread.sleep(350L * (attempt + 1))
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw java.io.InterruptedIOException("GitHub authorization retry interrupted")
                }
            }
        }
        error("Unreachable GitHub authorization retry state")
    }
}
