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
                actual: Map<String, String>): Boolean =
        expected.all { (key, value) -> equivalent(value, actual[key]) }
}
