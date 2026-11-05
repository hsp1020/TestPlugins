package com.ultimaevo

import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.CloudStreamApp
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** Temporary two-device diagnostics. Values and provider/catalog names remain redacted. */
internal object EvoSyncTrace {
    private const val PREFIX = "p/ultima_evo_settings/"
    private fun sha(s: String) =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }.take(16)
    fun log(event: String, info: String = "") {
        Log.i("EvoSyncTrace", "pid=" + android.os.Process.myPid() +
            " event=" + event + " " + info)
    }
    private fun focus(key: String) =
        listOf("provider_enabled_", "auto_add_", "cat_toggle_", "cat_rename_",
            "cat_order_", "evo_active_cats_json_", "evo_name_", "evo_visibility_enabled_",
            "evo_search_enabled_", "evo_instance_count").any { key.startsWith(it) }
    private fun group(key: String) = when {
        key.startsWith("provider_enabled_") -> "enable"
        key.startsWith("auto_add_") -> "auto_add"
        key.startsWith("cat_toggle_") -> "catalog_check"
        key.startsWith("cat_rename_") -> "catalog_rename"
        key.startsWith("cat_order_") -> "catalog_order"
        key.startsWith("evo_active_cats_json_") -> "catalog_json"
        else -> "instance_option"
    }
    private fun encode(value: Any?): String? {
        val j = JSONObject()
        when (value) {
            is Boolean -> j.put("type", "bool").put("value", value)
            is Int -> j.put("type", "int").put("value", value)
            is Long -> j.put("type", "long").put("value", value)
            is Float -> j.put("type", "float").put("value", value.toDouble())
            is String -> j.put("type", "string").put("value", value)
            is Set<*> -> if (value.all { it is String }) {
                j.put("type", "set").put("value", JSONArray(value.toList()))
            } else return null
            else -> return null
        }
        return j.toString()
    }
    fun snapshot(context: Context, event: String) {
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val data = prefs.all.mapNotNull { (key, value) ->
            encode(value)?.let { key to it }
        }.toMap().toSortedMap()
        val important = data.filterKeys { focus(it) }.toSortedMap()
        val checksum = sha(important.entries.joinToString("\n") {
            PREFIX + it.key + "\u0000" + it.value
        })
        log(event, "allKeys=" + data.size + " focusCount=" + important.size +
            " focusSha=" + checksum)
        for ((key, raw) in important) {
            val type = JSONObject(raw).optString("type")
            val bool = if (type == "bool") " value=" + JSONObject(raw).optBoolean("value") else ""
            log(event + "_KEY", "kind=" + group(key) + " id=" + sha(PREFIX + key) +
                " type=" + type + bool + " len=" + raw.length +
                " valueSha=" + sha(raw))
        }
    }
    fun backup(context: Context, event: String) {
        val raw = runCatching {
            CloudStreamApp.getKey<String>("native_backup_ultimaevo")
        }.getOrNull()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val savedHash = prefs.getString("ultima_evo_last_applied_backup_hash", null)
        log(event, "nativePresent=" + (raw != null) + " nativeSize=" + (raw?.length ?: 0) +
            " nativeSha=" + (raw?.let { sha(it) } ?: "-") +
            " nativeMatchesMarker=" + (raw != null &&
                raw.hashCode().toString() == savedHash) +
            " restoreComplete=" + prefs.getBoolean("ultima_evo_restore_complete", false))
    }
}
