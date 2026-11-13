package com.SyncCS

import org.json.JSONArray
import org.json.JSONObject

/**
 * Three-way merging for actual collection preferences, not arbitrary scalar
 * strings. Remote order is authoritative when both devices reorder a list.
 */
internal object SharedListSync {
    private data class ListValue(val kind: String, val items: List<String>)

    // Compare the last path segment without allocating a substring per setting.
    private fun stringCollectionKind(key: String): String? = when {
        key == "seen_providers" || key.endsWith("/seen_providers") ||
            key == "provider_order" || key.endsWith("/provider_order") -> "csv"
        key == "stremio_addons" || key.endsWith("/stremio_addons") -> "lines"
        key == "user_pinned_providers" || key.endsWith("/user_pinned_providers") ||
            key == "search_pref_providers" || key.endsWith("/search_pref_providers") ||
            key == "search_pref_tags" || key.endsWith("/search_pref_tags") -> "json"
        else -> null
    }

    private fun parse(key: String, raw: String): ListValue? {
        val wrapper = JSONObject(raw)
        when (wrapper.optString("type")) {
            "set" -> {
                val array = wrapper.getJSONArray("value")
                return ListValue("set", (0 until array.length()).map { array.getString(it) })
            }
            "string" -> {
                val kind = stringCollectionKind(key) ?: return null
                val value = wrapper.getString("value")
                return when (kind) {
                    "csv" -> ListValue(
                        "csv", value.split(',').map { it.trim() }.filter(String::isNotEmpty)
                    )
                    "lines" -> ListValue(
                        "lines", value.lines().map { it.trim() }.filter(String::isNotEmpty)
                    )
                    "json" -> {
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

    fun isCollection(key: String, raw: String): Boolean {
        // A collection requires type "set" on any key, or type "string" on
        // one of the recognized list keys. Escaped JSON can encode those
        // types via unicode escapes; keep the old parser for all such inputs.
        // This conservative rejection avoids JSONObject/JSONTokener for ordinary
        // settings, even during a full 30k-key audit.
        if ('\\' !in raw && !raw.contains("\"set\"") &&
            (stringCollectionKind(key) == null || !raw.contains("\"string\""))) {
            return false
        }
        return runCatching { parse(key, raw) != null }.getOrDefault(false)
    }
}
