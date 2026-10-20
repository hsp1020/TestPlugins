package com.SyncCS

import org.json.JSONArray
import org.json.JSONObject

/**
 * Three-way merging for actual collection preferences, not arbitrary scalar
 * strings. Remote order is authoritative when both devices reorder a list.
 */
internal object SharedListSync {
    private data class ListValue(val kind: String, val items: List<String>)

    private fun parse(key: String, raw: String): ListValue? {
        val wrapper = JSONObject(raw)
        when (wrapper.optString("type")) {
            "set" -> {
                val array = wrapper.getJSONArray("value")
                return ListValue("set", (0 until array.length()).map { array.getString(it) })
            }
            "string" -> {
                val name = key.substringAfterLast('/')
                val value = wrapper.getString("value")
                return when (name) {
                    "seen_providers", "provider_order" -> ListValue(
                        "csv", value.split(',').map { it.trim() }.filter(String::isNotEmpty)
                    )
                    "stremio_addons" -> ListValue(
                        "lines", value.lines().map { it.trim() }.filter(String::isNotEmpty)
                    )
                    "user_pinned_providers", "search_pref_providers", "search_pref_tags" -> {
                        val array = JSONArray(value)
                        ListValue("json", (0 until array.length()).map { array.getString(it) })
                    }
                    else -> null
                }
            }
            else -> return null
        }
    }

    /**
     * Returns null for unsupported formats or missing three-way ancestry;
     * the coordinator then applies the deterministic server-wins policy.
     */
    fun merge(key: String, baseRaw: String?, localRaw: String?, remoteRaw: String?): String? {
        if (baseRaw == null || localRaw == null || remoteRaw == null) return null
        val base = runCatching { parse(key, baseRaw) }.getOrNull() ?: return null
        val local = runCatching { parse(key, localRaw) }.getOrNull() ?: return null
        val remote = runCatching { parse(key, remoteRaw) }.getOrNull() ?: return null
        if (base.kind != local.kind || base.kind != remote.kind) return null
        val removedLocally = base.items.toSet() - local.items.toSet()
        val remoteItems = remote.items.filterNot { it in removedLocally }
        val locallyAdded = local.items.filter { it !in base.items }
        val merged = (remoteItems + locallyAdded).distinct()
        val encodedValue: Any = when (local.kind) {
            "set", "json" -> JSONArray(merged)
            "csv" -> merged.joinToString(",")
            "lines" -> merged.joinToString("\n")
            else -> return null
        }
        val payload = encodedValue.toString()
        return when (local.kind) {
            "set" -> JSONObject().put("type", "set")
                .put("value", encodedValue).toString()
            "json", "csv", "lines" -> JSONObject().put("type", "string")
                .put("value", payload).toString()
            else -> null
        }
    }

    fun isCollection(key: String, raw: String): Boolean =
        runCatching { parse(key, raw) != null }.getOrDefault(false)
}
