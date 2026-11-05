package com.SyncCS

import org.json.JSONObject

/**
 * Functional post-apply integrity verification. This is not diagnostic
 * logging; never remove it when cleaning temporary trace output.
 */
internal object SyncApplyVerification {
    private fun equivalent(left: String?, right: String?): Boolean {
        if (left == right) return true
        if (left == null || right == null) return false
        return runCatching {
            val a = JSONObject(left)
            val b = JSONObject(right)
            val type = a.getString("type")
            if (type != b.getString("type")) return@runCatching false
            if (type == "set") {
                val x = a.getJSONArray("value")
                val y = b.getJSONArray("value")
                (0 until x.length()).map(x::getString).toSet() ==
                    (0 until y.length()).map(y::getString).toSet()
            } else a.get("value").toString() == b.get("value").toString()
        }.getOrDefault(false)
    }

    fun applied(phase: String, expected: Map<String, String?>,
                actual: Map<String, String>): Boolean {
        var mismatches = 0
        for ((key, value) in expected) {
            val found = actual[key]
            if (equivalent(value, found)) continue
            mismatches++
            // Do not log raw paths (may contain account IDs or user-defined names)
            // or raw setting values (may include API tokens).
            val expectedType = runCatching { value?.let { JSONObject(it).optString("type") } }
                .getOrNull() ?: "deleted"
            val actualType = runCatching { found?.let { JSONObject(it).optString("type") } }
                .getOrNull() ?: "absent"
            val reason = when {
                value == null -> "deletion_not_applied"
                found == null -> "missing_after_apply"
                expectedType != actualType -> "type_mismatch"
                else -> "value_mismatch"
            }
            val scope = when {
                key.startsWith("p/ultima_evo_settings/") -> "ultima_evo"
                key.startsWith("p/ElevateFeatures/") -> "elevate"
                key.startsWith("p/") -> "other_plugin"
                key.startsWith("d/") -> "core_data"
                key.startsWith("s/") -> "default_prefs"
                else -> "unknown"
            }
            if (mismatches <= 48) runCatching {
                SyncTrace.log("VERIFY_MISMATCH", "phase=" + phase +
                    " id=" + SyncBackend.digest(key).take(16) +
                    " scope=" + scope + " reason=" + reason +
                    " expectedType=" + expectedType + " actualType=" + actualType +
                    " expectedLen=" + (value?.length ?: 0) +
                    " actualLen=" + (found?.length ?: 0) +
                    " expectedSha=" + (value?.let { SyncBackend.digest(it).take(16) } ?: "-") +
                    " actualSha=" + (found?.let { SyncBackend.digest(it).take(16) } ?: "-"))
            }
        }
        if (mismatches > 0) {
            runCatching {
                SyncTrace.log("VERIFY_FAILED", "phase=" + phase +
                    " mismatches=" + mismatches + " expected=" + expected.size +
                    " observed=" + actual.size + " logged=" + mismatches.coerceAtMost(48))
            }
        }
        return mismatches == 0
    }
}
