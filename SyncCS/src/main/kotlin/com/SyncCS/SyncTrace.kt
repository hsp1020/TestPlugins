package com.SyncCS

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** Temporary two-device synchronization diagnostics; do not log raw settings. */
internal object SyncTrace {
    private const val EVO = "p/ultima_evo_settings/"
    private const val ELV = "p/ElevateFeatures/"
    fun relevant(path: String) = path.startsWith(EVO) || path.startsWith(ELV)
    private fun important(path: String): Boolean {
        if (path.startsWith(ELV)) return true
        val key = path.removePrefix(EVO)
        return path.startsWith(EVO) && listOf("provider_enabled_", "auto_add_",
            "cat_toggle_", "cat_rename_", "cat_order_", "evo_active_cats_json_",
            "evo_name_", "evo_visibility_enabled_", "evo_search_enabled_",
            "evo_instance_count").any { key.startsWith(it) }
    }
    private fun sha(s: String) = SyncBackend.digest(s).take(16)
    fun log(event: String, detail: String = "") {
        Log.i("SyncCSTrace", "pid=" + android.os.Process.myPid() +
            " event=" + event + " " + detail)
    }
    fun summary(event: String, data: Map<String, String?>) {
        val entries = data.filterKeys { relevant(it) }.toSortedMap()
        val essential = entries.filterKeys { important(it) }.toSortedMap()
        fun sum(items: Map<String, String?>) =
            sha(items.entries.joinToString("\n") {
                it.key + "\u0000" + (it.value ?: "<deleted>")
            })
        log(event, "count=" + entries.size + " focusCount=" + essential.size +
            " allSha=" + sum(entries) + " focusSha=" + sum(essential))
        for ((path, raw) in entries) {
            val key = if (path.startsWith(EVO)) path.removePrefix(EVO) else path.removePrefix(ELV)
            val group = when {
                path.startsWith(ELV) -> "elevate"
                key.startsWith("provider_enabled_") -> "enable"
                key.startsWith("auto_add_") -> "auto_add"
                key.startsWith("cat_toggle_") -> "catalog_check"
                key.startsWith("cat_order_") -> "catalog_order"
                key.startsWith("cat_rename_") -> "catalog_rename"
                key.startsWith("evo_active_cats_json_") -> "catalog_json"
                key.startsWith("dynamic_catalogs_") -> "catalog_discovery"
                key.startsWith("ultima_evo_") -> "restore_marker"
                else -> "other"
            }
            val parsed = raw?.let { runCatching { JSONObject(it) }.getOrNull() }
            val type = parsed?.optString("type") ?: if (raw == null) "deleted" else "invalid"
            val bool = if (type == "bool") " value=" + parsed!!.optBoolean("value") else ""
            log(event + "_KEY", "file=" +
                (if (path.startsWith(EVO)) "ultima_evo_settings" else "ElevateFeatures") +
                " kind=" + group + " id=" + sha(path) + " type=" + type + bool +
                " len=" + (raw?.length ?: 0) + " valueSha=" + (raw?.let { sha(it) } ?: "-"))
        }
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
    fun current(context: Context, event: String) {
        val all = mutableMapOf<String, String?>()
        for (file in listOf("ultima_evo_settings", "ElevateFeatures")) {
            val prefs = context.getSharedPreferences(file, Context.MODE_PRIVATE)
            log(event + "_FILE", "file=" + file + " keys=" + prefs.all.size)
            for ((key, value) in prefs.all) {
                encode(value)?.let { all["p/" + file + "/" + key] = it }
            }
        }
        summary(event, all)
    }
}
