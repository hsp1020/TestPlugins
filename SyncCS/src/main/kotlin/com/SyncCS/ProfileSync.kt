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
        val identities = ProfileIdentity.observe(context)
        DataStoreHelper.getAccounts(context).forEach { account ->
            state.items[account.keyIndex] = JSONObject()
                .put("uuid", identities.getValue(account.keyIndex))
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
            if (id >= 0) {
                ProfileIdentity.uuid(item)
                state.items[id] = item
            }
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
        return JSONObject().put("format", 2).put("items", items)
            .put("deleted", deleted).toString()
    }

    fun merge(localRaw: String, remoteRaw: String?, baselineRaw: String?): Merge {
        val local = decode(localRaw)
        val remote = decode(remoteRaw)
        val base = decode(baselineRaw)
        val target = State(LinkedHashMap(remote.items), LinkedHashSet(remote.deleted))
        if (baselineRaw == null) {
            // On a new device, the existing server list is canonical.
            if (remoteRaw == null) target.items.putAll(local.items)
        } else {
            // A roster removal after a durable baseline is a normal sync change.
            // New devices (without a baseline) must adopt the server roster.
            for (id in base.items.keys - local.items.keys) {
                if (id > 0 && id !in remote.deleted) {
                     target.items.remove(id)
                    target.deleted.add(id)
                }
            }
            for ((id, item) in local.items) {
                val previous = base.items[id]
                val remoteItem = remote.items[id]
                when {
                    previous == null && remoteItem == null -> {
                        // Fresh UUID is allowed to occupy a retired numeric slot.
                        // The prior UUID's watch data stays archived separately.
                        target.deleted.remove(id)
                        target.items[id] = item
                    }
                    previous != null &&
                        ProfileIdentity.uuid(previous) != ProfileIdentity.uuid(item) &&
                        (remoteItem == null ||
                            ProfileIdentity.uuid(remoteItem) == ProfileIdentity.uuid(previous)) -> {
                        // Deleted and recreated between sync passes, possibly
                        // with the SAME numeric slot and the SAME display name.
                        target.deleted.remove(id)
                        target.items[id] = item
                    }
                    previous != null &&
                        ProfileIdentity.uuid(previous) == ProfileIdentity.uuid(item) &&
                        id !in remote.deleted &&
                        remoteItem?.toString() == previous.toString() &&
                        previous.toString() != item.toString() -> {
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
        val remoteIdentities = state.items.mapValues { (_, row) -> ProfileIdentity.uuid(row) }
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
        // CloudStream only knows numeric slots. Adopt the server UUID sidecar
        // before subsequent watch-state reconciliation.
        ProfileIdentity.adopt(context, remoteIdentities)
        val valid = accounts.mapTo(mutableSetOf()) { it.keyIndex }
        if (DataStoreHelper.selectedKeyIndex !in valid) {
            DataStoreHelper.setAccount(accounts.first { it.keyIndex == 0 }, showToast = false)
        }
        // Only synchronize the profile roster. CloudStream owns the lifecycle
        // of its stored profile data; a remote roster change must not wipe it.
        MainActivity.reloadAccountEvent.invoke(true)
    }
}
