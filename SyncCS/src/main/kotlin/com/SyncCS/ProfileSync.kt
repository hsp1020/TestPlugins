package com.SyncCS

import android.content.Context
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Server-authoritative CloudStream profile indices. No new profile IDs or UI.
 * Profile metadata is encrypted by SyncCipher like every other sync entry.
 */
internal object ProfileSync {
    data class State(
        val items: LinkedHashMap<Int, JSONObject> = linkedMapOf(),
        val deleted: LinkedHashSet<Int> = linkedSetOf()
    )
    data class Merge(val target: State, val changedRemote: Boolean)

    fun snapshot(context: Context): String {
        val state = State()
        DataStoreHelper.getAccounts(context).forEach { account ->
            state.items[account.keyIndex] = JSONObject()
                .put("keyIndex", account.keyIndex)
                .put("name", account.name)
                .put("customImage", account.customImage ?: JSONObject.NULL)
                .put("defaultImageIndex", account.defaultImageIndex)
                .put("lockPin", account.lockPin ?: JSONObject.NULL)
        }
        return encode(state)
    }

    fun decode(raw: String?): State {
        val state = State()
        if (raw.isNullOrBlank()) return state
        val root = JSONObject(raw)
        val items = root.optJSONArray("items") ?: JSONArray()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            val id = item.getInt("keyIndex")
            if (id >= 0) state.items[id] = item
        }
        val removed = root.optJSONArray("deleted") ?: JSONArray()
        for (index in 0 until removed.length()) {
            val id = removed.getInt(index)
            if (id > 0) state.deleted.add(id)
        }
        state.deleted.forEach { state.items.remove(it) }
        return state
    }

    fun encode(state: State): String {
        val items = JSONArray()
        state.items.toSortedMap().values.forEach { items.put(it) }
        val deleted = JSONArray()
        state.deleted.sorted().forEach { deleted.put(it) }
        return JSONObject().put("format", 1).put("items", items)
            .put("deleted", deleted).toString()
    }

    fun merge(localRaw: String, remoteRaw: String?, baselineRaw: String?,
              approvedRemovals: Set<Int> = emptySet()): Merge {
        val local = decode(localRaw)
        val remote = decode(remoteRaw)
        val base = decode(baselineRaw)
        val target = State(LinkedHashMap(remote.items), LinkedHashSet(remote.deleted))
        if (baselineRaw == null) {
            // On a new device, the existing server list is canonical.
            if (remoteRaw == null) target.items.putAll(local.items)
        } else {
            // Independent local additions, edits and removals are three-way merged.
            // A temporarily incomplete native profile roster is never proof of
            // intentional deletion. Keep the server roster until explicit review.
            for (id in base.items.keys - local.items.keys) {
                if (id > 0 && id !in remote.deleted) {
                    check(id in approvedRemovals) {
                        "Profile $id disappeared locally without deletion approval."
                    }
                    target.items.remove(id)
                    target.deleted.add(id)
                }
            }
            for ((id, item) in local.items) {
                val previous = base.items[id]
                val remoteItem = remote.items[id]
                when {
                    previous == null && id !in remote.items -> {
                        SyncSafety.requireProfileNotReused(id, remote.deleted, base.deleted)
                        target.items[id] = item
                    }
                    previous != null && previous.toString() != item.toString() &&
                        remoteItem?.toString() == previous.toString() -> {
                        target.items[id] = item
                    }
                }
            }
        }
        // CloudStream's default account is always present.
        target.deleted.remove(0)
        if (!target.items.containsKey(0)) {
            local.items[0]?.let { target.items[0] = it }
        }
        target.deleted.forEach { target.items.remove(it) }
        return Merge(target, remoteRaw == null || encode(remote) != encode(target))
    }

    fun apply(context: Context, state: State) {
        val current = decode(snapshot(context))
        if (encode(current) == encode(state)) return
        val accounts = state.items.toSortedMap().values.map { item ->
            DataStoreHelper.Account(
                keyIndex = item.getInt("keyIndex"),
                name = item.getString("name"),
                customImage = item.optString("customImage").takeUnless {
                    item.isNull("customImage") || it.isBlank()
                },
                defaultImageIndex = item.optInt("defaultImageIndex", 0),
                lockPin = item.optString("lockPin").takeUnless {
                    item.isNull("lockPin") || it.isBlank()
                }
            )
        }
        require(accounts.any { it.keyIndex == 0 }) { "Missing default CloudStream profile" }
        DataStoreHelper.accounts = accounts.toTypedArray()
        val valid = accounts.mapTo(mutableSetOf()) { it.keyIndex }
        if (DataStoreHelper.selectedKeyIndex !in valid) {
            DataStoreHelper.setAccount(accounts.first { it.keyIndex == 0 }, showToast = false)
        }
        // Only synchronize the profile roster. CloudStream owns the lifecycle
        // of its stored profile data; a remote roster change must not wipe it.
        MainActivity.reloadAccountEvent.invoke(true)
    }
}
