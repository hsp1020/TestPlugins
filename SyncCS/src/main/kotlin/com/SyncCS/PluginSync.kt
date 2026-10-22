package com.SyncCS

import org.json.JSONArray
import org.json.JSONObject

/**
 * Add-on inventory reconciliation. Each manifest includes persistent tombstones.
 * Older v338 array manifests are accepted and upgraded on first write.
 * Online identity is name+repository (not release URL); local identity is name+filename.
 */
internal object PluginSync {
    data class State(
        val items: LinkedHashMap<String, JSONObject> = linkedMapOf(),
        val deleted: LinkedHashSet<String> = linkedSetOf()
    )
    data class Merge(val target: State, val changedRemote: Boolean)

    fun identity(row: JSONObject): String {
        return if (row.optString("type") == "local") {
            "local|" + row.optString("name") + "|" + row.optString("file")
        } else {
            // Versioned download URLs must not make an update look like a different plugin.
            "online|" + row.optString("name") + "|" + row.optString("repo")
        }
    }

    private fun newerOnline(local: JSONObject, remote: JSONObject): Boolean =
        local.optString("type") == "online" && remote.optString("type") == "online" &&
            local.optInt("version") > 0 && remote.optInt("version") > 0 &&
            local.optInt("version") > remote.optInt("version")

    fun decode(raw: String?): State {
        val result = State()
        if (raw.isNullOrBlank()) return result
        val root = if (raw.trimStart().startsWith("[")) {
            JSONObject().put("items", JSONArray(raw))
        } else JSONObject(raw)
        val array = root.optJSONArray("items") ?: JSONArray()
        for (i in 0 until array.length()) {
            val item = array.getJSONObject(i)
            val key = identity(item)
            if (key != "online||" && key != "local||" &&
                (result.items[key] == null || !newerOnline(result.items.getValue(key), item))) {
                result.items[key] = item
            }
        }
        val removed = root.optJSONArray("deleted") ?: JSONArray()
        for (i in 0 until removed.length()) result.deleted.add(removed.getString(i))
        result.deleted.forEach { result.items.remove(it) }
        return result
    }

    fun encode(state: State): String {
        val ordered = JSONArray()
        state.items.toSortedMap().values.forEach { ordered.put(it) }
        val removed = JSONArray()
        state.deleted.sorted().forEach { removed.put(it) }
        return JSONObject()
            .put("format", 2)
            .put("items", ordered)
            .put("deleted", removed)
            .toString()
    }

    fun items(raw: String?): JSONArray {
        val result = JSONArray()
        decode(raw).items.values.forEach { result.put(it) }
        return result
    }

    fun matches(left: State, right: State): Boolean {
        if (left.items.keys != right.items.keys) return false
        return left.items.keys.all { key ->
            // The host may normalize version numbers on registration. File hashes,
            // identities and actual installed binaries are the authoritative check.
            val expectedHash = right.items[key]?.optString("sha").orEmpty()
            expectedHash.matches(Regex("[0-9a-f]{64}")) &&
                left.items[key]?.optString("sha") == expectedHash
        }
    }

    /**
     * Three-way reconciliation, preserving independent additions and deletes.
     * A tombstone wins over an edit made by a device that still had that entry
     * in its baseline. A *new* installation after an established baseline can
     * explicitly restore a deleted entry. An uninitialized device never can.
     */
    fun merge(localRaw: String, remoteRaw: String?, baselineRaw: String?,
              allowLocalDeletions: Boolean, approvedRemovals: Set<String> = emptySet()): Merge {
        val local = decode(localRaw)
        val remote = decode(remoteRaw)
        val base = decode(baselineRaw)
        val initialized = baselineRaw != null
        val output = State(LinkedHashMap(remote.items), LinkedHashSet(remote.deleted))

        // Local deletion is intentional only after an inventory baseline exists,
        // and never while CloudStream's plugin loading is still in progress.
        if (initialized && allowLocalDeletions) {
            for (id in base.items.keys - local.items.keys) {
                if (id !in remote.deleted) {
                    check(id in approvedRemovals) {
                        "Extension removed locally without deletion approval: $id"
                    }
                    output.items.remove(id)
                    output.deleted.add(id)
                }
            }
        }
        for ((id, item) in local.items) {
            val previous = base.items[id]
            when {
                // Never replace an already-installed newer release with an older
                // server snapshot, including on a device with no prior baseline.
                initialized && remote.items[id]?.let { newerOnline(item, it) } == true -> {
                    output.items[id] = item
                }
                previous == null && initialized -> {
                    // New local install: clear tombstone only when this device
                    // previously synced a baseline without the extension.
                    output.deleted.remove(id)
                    output.items[id] = item
                }
                previous == null && !initialized && remoteRaw == null &&
                    id !in remote.deleted -> {
                    // One-time migration only when the server has no inventory at all.
                    // A remotely stored empty manifest is an intentional empty set.
                    output.items.putIfAbsent(id, item)
                }
                id in remote.deleted -> Unit // Old device must not resurrect a delete
                previous != null && previous.toString() != item.toString() -> {
                    val remoteItem = remote.items[id]
                    if (remoteItem == null || remoteItem.toString() == previous.toString()) {
                        output.items[id] = item
                    }
                    // Concurrent edits of the same entry: confirmed remote wins.
                }
            }
        }
        output.deleted.forEach { output.items.remove(it) }
        val target = encode(output)
        return Merge(output, remoteRaw == null || encode(remote) != target)
    }
}
