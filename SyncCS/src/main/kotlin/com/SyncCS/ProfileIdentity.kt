package com.SyncCS

import android.content.Context
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.json.JSONObject
import java.util.UUID

/**
 * Sidecar profile identities. CloudStream keeps its native numeric account
 * indices unchanged; SyncCS uses stable UUIDs for cloud watch-history keys.
 *
 * Observe the core account-roster event synchronously: a delete and an add
 * can reuse the same numeric slot before the next scheduled sync.
 */
internal object ProfileIdentity {
    private const val KEY = "profile_slot_identities_v2"
    private const val ROSTER = "profile_last_seen_slots_v2"
    private const val RETIRED = "profile_retired_slots_v2"
    // CloudStream's AccountViewModel.handleAccountDelete calls removeKeys(slot)
    // BEFORE removing the account from the roster. A marker inside that same
    // namespace is erased even if SyncCS is not running at deletion time.
    private const val MARKER = "synccs_profile_uuid"
    private fun marker(slot: Int): String = "$slot/$MARKER"
    private val idPattern = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

    fun valid(uuid: String): Boolean = uuid.matches(idPattern)

    private fun json(raw: String?): JSONObject =
        if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)

    @Synchronized
    fun observe(context: Context): Map<Int, String> {
        val preferences = SyncConfiguration(context).preferences
        val current = DataStoreHelper.getAccounts(context).map { it.keyIndex }.toSet()
        val previous = preferences.getStringSet(ROSTER, null)?.mapNotNull { it.toIntOrNull() }?.toSet()
        val identities = json(preferences.getString(KEY, null))
        val retired = preferences.getStringSet(RETIRED, emptySet())?.toMutableSet() ?: mutableSetOf()
        val native = with(DataStore) { context.getSharedPrefs() }
        val nativeWrite = native.edit()
        var markerChanged = false
        // Retire a slot on the first account roster event showing it absent.
        // A later event re-creating the same number gets a NEW UUID.
        if (previous != null) {
            for (slot in previous - current) {
                identities.remove(slot.toString())
                retired.add(slot.toString())
            }
        }
        var changed = previous != current
        for (slot in current) {
            val stored = identities.optString(slot.toString())
            val coreMarker = native.getString(marker(slot), null)
            if (stored.isNotBlank()) check(valid(stored)) {
                "Invalid local profile identity for slot $slot"
            }
            if (coreMarker != null) check(valid(coreMarker)) {
                "Invalid CloudStream-sidecar profile marker for slot $slot"
            }
            // If the old numeric slot survived but its CloudStream marker was
            // removed, CloudStream has deleted/recreated the profile. Rotate
            // even when SyncCS missed all events and the display name is equal.
            val identity = when {
                coreMarker != null -> coreMarker
                stored.isNotBlank() -> UUID.randomUUID().toString()
                else -> UUID.randomUUID().toString()
            }
            if (stored != identity) {
                identities.put(slot.toString(), identity)
                changed = true
            }
            if (coreMarker == null) {
                nativeWrite.putString(marker(slot), identity)
                markerChanged = true
            }
        }
        if (markerChanged) check(nativeWrite.commit()) {
            "Could not write profile identity markers into CloudStream storage"
        }
        if (changed || !preferences.contains(KEY)) {
            check(preferences.edit().putString(KEY, identities.toString())
                .putStringSet(ROSTER, current.map { it.toString() }.toSet())
                .putStringSet(RETIRED, retired).commit()) {
                "Could not save UUID profile identities"
            }
        }
        return current.associateWith { identities.getString(it.toString()) }
    }

    @Synchronized
    fun adopt(context: Context, remote: Map<Int, String>) {
        check(remote.isNotEmpty() && remote.containsKey(0))
        check(remote.values.all(::valid) && remote.values.toSet().size == remote.size) {
            "Backup contains missing or duplicate profile UUIDs"
        }
        val preferences = SyncConfiguration(context).preferences
        val current = DataStoreHelper.getAccounts(context).map { it.keyIndex }.toSet()
        check(current == remote.keys) {
            "Native profile roster is inconsistent with its cloud UUIDs"
        }
        val identities = JSONObject()
        remote.forEach { (slot, uuid) -> identities.put(slot.toString(), uuid) }
        val retired = preferences.getStringSet(RETIRED, emptySet())?.toMutableSet() ?: mutableSetOf()
        // Tombstones in the server manifest are handled by profile sync itself;
        // keep a local trace of abandoned numeric slots.
        val previous = json(preferences.getString(KEY, null))
        for (name in previous.keys()) {
            if (!remote.containsKey(name.toInt())) retired.add(name)
        }
        val native = with(DataStore) { context.getSharedPrefs() }
        val editor = native.edit()
        for ((slot, uuid) in remote) editor.putString(marker(slot), uuid)
        check(editor.commit()) { "Could not adopt cloud profile identity markers" }
        check(preferences.edit().putString(KEY, identities.toString())
            .putStringSet(ROSTER, current.map { it.toString() }.toSet())
            .putStringSet(RETIRED, retired).commit()) {
            "Could not adopt cloud profile UUIDs"
        }
    }

    fun uuid(item: JSONObject): String {
        val value = item.optString("uuid")
        check(valid(value)) { "Missing stable profile UUID" }
        return value
    }

    /**
     * Native 'd/1/VIDEO_POS_DUR/...' -> encrypted cloud
     * 'u/<uuid>/VIDEO_POS_DUR/...'. Old generation data stays attached to the
     * retired UUID, NEVER to a newly created account occupying slot 1.
     */
    fun remotePath(nativePath: String, profiles: ProfileSync.State): String {
        if (!nativePath.startsWith("d/")) return nativePath
        val number = nativePath.removePrefix("d/").substringBefore('/')
        if (!number.all(Char::isDigit) || '/' !in nativePath.removePrefix("d/"))
            return nativePath
        val slot = number.toIntOrNull() ?: return nativePath
        val profile = profiles.items[slot] ?: return nativePath
        val suffix = nativePath.removePrefix("d/$slot/")
        return "u/" + uuid(profile) + "/" + suffix
    }

    fun nativePath(remotePath: String, profiles: ProfileSync.State): String? {
        if (!remotePath.startsWith("u/")) return remotePath
        val raw = remotePath.removePrefix("u/")
        val id = raw.substringBefore('/')
        if ('/' !in raw || !valid(id)) {
            error("Malformed per-profile backup key")
        }
        val slot = profiles.items.entries.firstOrNull { (_, row) -> uuid(row) == id }?.key
            ?: return null // Retired profiles are archived but never reattached.
        return "d/$slot/" + raw.substringAfter('/')
    }

    /** Profiles replaced by the server cannot upload an old device's slot data. */
    fun replacedByServer(local: ProfileSync.State, target: ProfileSync.State): Set<String> =
        target.items.mapNotNull { (slot, row) ->
            val was = local.items[slot]?.let(::uuid)
            val now = uuid(row)
            now.takeIf { was != now }
        }.toSet()

    fun toRemote(values: Map<String, String>, profiles: ProfileSync.State): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        for ((key, value) in values) {
            val destination = remotePath(key, profiles)
            check(!result.containsKey(destination)) {
                "Duplicate watch-state key after UUID encoding: $destination"
            }
            result[destination] = value
        }
        return result
    }

    fun toNative(changes: Map<String, String?>, profiles: ProfileSync.State): Map<String, String?> {
        val result = linkedMapOf<String, String?>()
        for ((key, value) in changes) {
            val destination = nativePath(key, profiles) ?: continue
            check(!result.containsKey(destination)) {
                "Ambiguous profile watch-state restore: $destination"
            }
            result[destination] = value
        }
        return result
    }
}
