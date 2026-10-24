package com.SyncCS

/**
 * Do not repeatedly submit a GitHub credential which the server has already
 * rejected. This stores only a SHA-256 identifier of the encrypted local
 * token, never the access token itself.
 *
 * A newly authenticated session stores a new encrypted token and can proceed
 * without deleting local unsynchronized changes or cloud baselines.
 */
internal object SyncAuthGuard {
    private const val BAD_TOKEN = "github_rejected_token_hash"
    private const val REASON = "github_rejected_token_reason"
    private const val REJECTED_REFRESH = "github_rejected_refresh_hash"
    private const val REFRESH_RETRY_AFTER = "github_refresh_retry_after"

    fun tokenHash(encryptedToken: String?): String? =
        encryptedToken?.takeIf { it.isNotBlank() }?.let { SyncBackend.digest(it) }

    fun matchesRejectedToken(encryptedToken: String?, storedHash: String?): Boolean {
        val current = tokenHash(encryptedToken)
        return current != null && storedHash != null && current == storedHash
    }

    fun needsReauthentication(config: SyncConfiguration): Boolean =
        matchesRejectedToken(config.preferences.getString("github_token", null),
            config.preferences.getString(BAD_TOKEN, null))

    fun reject(config: SyncConfiguration, statusCode: Int) {
        if (statusCode != 401) return
        val token = tokenHash(config.preferences.getString("github_token", null)) ?: return
        check(config.preferences.edit()
            .putString(BAD_TOKEN, token)
            .putString(REASON, "github-http-401")
            .commit()) { "Cannot record rejected GitHub credential" }
        SyncTrace.info("github-auth-rejected",
            "http=401 credentialId=${token.take(12)} " +
                "action=reauthorize noLocalDataDeleted=true")
    }

    /** Only a saved, still-usable refresh credential can unblock a rejected access token. */
    fun mayRecover(config: SyncConfiguration): Boolean {
        if (!needsReauthentication(config)) return false
        val prefs = config.preferences
        val refresh = prefs.getString("github_refresh_token", null)
        if (refresh.isNullOrBlank() || prefs.getString("github_client_id", null).isNullOrBlank())
            return false
        val expiry = prefs.getLong("github_refresh_expires_at", 0L)
        if (expiry > 0L && System.currentTimeMillis() >= expiry) return false
        return !matchesRejectedToken(refresh, prefs.getString(REJECTED_REFRESH, null)) &&
            System.currentTimeMillis() >= prefs.getLong(REFRESH_RETRY_AFTER, 0L)
    }

    fun recordRefreshFailure(config: SyncConfiguration, permanent: Boolean) {
        val prefs = config.preferences
        val editor = prefs.edit()
        if (permanent) {
            val refresh = tokenHash(prefs.getString("github_refresh_token", null))
            if (refresh != null) editor.putString(REJECTED_REFRESH, refresh)
            editor.remove(REFRESH_RETRY_AFTER)
        } else {
            editor.putLong(REFRESH_RETRY_AFTER, System.currentTimeMillis() + 300_000L)
        }
        check(editor.commit()) { "Cannot persist GitHub refresh recovery state" }
        SyncTrace.info("github-auth-recovery-limited",
            "permanent=$permanent retryAfterMs=${prefs.getLong(REFRESH_RETRY_AFTER, 0L)}")
    }

    fun clearAfterLogin(config: SyncConfiguration) {
        check(config.preferences.edit()
            .remove(BAD_TOKEN).remove(REASON)
            .remove(REJECTED_REFRESH).remove(REFRESH_RETRY_AFTER).commit()) {
            "Could not reset GitHub authorization failure state"
        }
        SyncTrace.info("github-auth-restored",
            "savedCredential=true action=resume-sync")
    }

    const val MESSAGE =
        "Sync paused: GitHub rejected the saved authorization (HTTP 401). " +
            "Open Sync account and authorize the same GitHub account again. " +
            "Local changes are preserved."
}
