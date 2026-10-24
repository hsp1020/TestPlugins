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
        GithubAuthDiagnostics.record(config, "api-401-rejected")
        GithubAuthDiagnostics.snapshot(config, "auth-guard-rejected")

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
        GithubAuthDiagnostics.record(config,
            if (permanent) "refresh-permanently-rejected" else "refresh-retry-deferred",
            detail = "retryAfterMs=${prefs.getLong(REFRESH_RETRY_AFTER, 0L)}")

    }

    fun clearAfterLogin(config: SyncConfiguration) {
        check(config.preferences.edit()
            .remove(BAD_TOKEN).remove(REASON)
            .remove(REJECTED_REFRESH).remove(REFRESH_RETRY_AFTER).commit()) {
            "Could not reset GitHub authorization failure state"
        }
        GithubAuthDiagnostics.record(config, "authorization-restored")

    }

    const val MESSAGE =
        "Sync paused: GitHub rejected the saved authorization (HTTP 401). " +
            "Open Sync account and authorize the same GitHub account again. " +
            "Local changes are preserved."
}


/**
 * Persist bounded, non-secret OAuth lifecycle evidence across app restarts.
 * GitHub's "Bad credentials" response does not disclose its revocation reason;
 * the events below identify what this client did before the rejection.
 */
internal object GithubAuthDiagnostics {
    internal const val HISTORY_KEY = "github_auth_diagnostic_history"
    private const val MAX_EVENTS = 12

    fun fingerprint(raw: String?): String =
        raw?.takeIf { it.isNotEmpty() }?.let { SyncBackend.digest(it).take(12) } ?: "missing"

    @Synchronized
    fun record(config: SyncConfiguration, event: String, access: String? = null,
               detail: String = "") {
        // Callers supply only controlled event names and non-secret diagnostic fields.
        val stamp = System.currentTimeMillis()
        val marker = fingerprint(access)
        val safeDetail = detail.take(160)

        runCatching {
            val prefs = config.preferences
            val old = org.json.JSONArray(prefs.getString(HISTORY_KEY, "[]"))
            val updated = org.json.JSONArray()
            for (index in maxOf(0, old.length() - MAX_EVENTS + 1) until old.length())
                updated.put(old.getJSONObject(index))
            updated.put(org.json.JSONObject()
                .put("at", stamp).put("event", event)
                .put("accessId", marker).put("detail", safeDetail))
            check(prefs.edit().putString(HISTORY_KEY, updated.toString()).commit())
        }.onFailure {

        }
    }

    fun snapshot(config: SyncConfiguration, phase: String, submitted: String? = null) {
        val prefs = config.preferences
        val stored = runCatching {
            prefs.getString("github_token", null)?.let {
                String(LocalVault.decrypt(it), Charsets.UTF_8)
            }
        }.getOrNull()
        val refresh = runCatching {
            prefs.getString("github_refresh_token", null)?.let {
                String(LocalVault.decrypt(it), Charsets.UTF_8)
            }
        }.getOrNull()
        val now = System.currentTimeMillis()
        val issued = prefs.getLong("github_token_issued_at", 0L)
        val expires = prefs.getLong("github_token_expires_at", 0L)
        val refreshExpires = prefs.getLong("github_refresh_expires_at", 0L)
        val expiryClass = when {
            expires == 0L -> "unknown-legacy"
            expires <= now -> "locally-expired"
            else -> "not-locally-expired"
        }

        runCatching {
            val history = org.json.JSONArray(prefs.getString(HISTORY_KEY, "[]"))
            val recent = (maxOf(0, history.length() - MAX_EVENTS) until history.length())
                .joinToString(";") { index ->
                    val e = history.getJSONObject(index)
                    "${e.optLong("at")}:${e.optString("event")}:" +
                        "${e.optString("accessId")}:${e.optString("detail")}"
                }

        }.onFailure {

        }
    }
}
