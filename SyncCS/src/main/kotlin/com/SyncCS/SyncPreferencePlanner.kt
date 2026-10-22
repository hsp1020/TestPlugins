package com.SyncCS

import org.json.JSONObject

/**
 * Pure per-preference reconciliation stage, separate from GitHub I/O,
 * CloudStream restoration, and commit/rollback. Never infer a deletion
 * from an uninitialized profile or an unmarked missing preference.
 */
internal object SyncPreferencePlanner {
    data class Plan(
        val apply: MutableMap<String, String?>,
        val upload: MutableMap<String, String?>,
        val next: MutableMap<String, String>,
        val conflicts: MutableMap<String, String>
    )

    private fun fingerprint(value: String?): String =
        if (value == null) "<deleted>" else SyncBackend.digest(value)

    fun reconcile(
        remoteValues: Map<String, String?>,
        local: Map<String, String>,
        base: MutableMap<String, String>,
        profiles: ProfileSync.State,
        activeProfiles: Set<Int>,
        replacedProfileUuids: Set<String>,
        modifiedKeys: Set<String>,
        priorLists: JSONObject,
        firstRemoteRestore: Boolean,
        fullSnapshot: Boolean
    ): Plan {
        val apply = mutableMapOf<String, String?>()
        val upload = mutableMapOf<String, String?>()
        val next = base.toMutableMap()
        next.remove("x/plugins")
        next.remove("x/profiles")
        val conflicts = mutableMapOf<String, String>()
        val all = (remoteValues.keys + local.keys + base.keys)
            .filter { it != "x/plugins" && it != "x/profiles" }.toSortedSet()

            for (key in all) {
                val nativeKey = ProfileIdentity.nativePath(key, profiles) ?: continue
                // Old backups may contain now-excluded generated import bookkeeping.
                // Do not keep replaying those two-way-changing keys.
                if (!SyncData.isTracked(nativeKey)) {
                    SyncTrace.info("skip-obsolete-setting",
                        "keyHash=${SyncBackend.digest(key).take(12)}")
                    continue
                }
                val current = local[key]
                // Deletions explicitly observed after the initial baseline are normal
                // changes; restore only keys missing without a change event.
                val remotePresent = remoteValues.containsKey(key)
                val remote = remoteValues[key]
                val old = base[key]
                val currentHash = fingerprint(current)
                val remoteHash = fingerprint(remote)

                // A missing profile is inactive, not a request to delete its
                // stored history. Keep both server data and the last baseline
                // intact until that profile index is active again.
                val profileId = if (key.startsWith("d/")) {
                    key.removePrefix("d/").substringBefore('/').toIntOrNull()
                } else null
                if (profileId != null && profileId !in activeProfiles) continue
                // Never attach archived UUID watch state to a new profile that
                // happens to reuse the same CloudStream numeric keyIndex.
                if (key.startsWith("u/") &&
                    ProfileIdentity.nativePath(key, profiles) == null) {
                    continue
                }
                val namespace = key.removePrefix("u/").substringBefore('/')
                if (key.startsWith("u/") && namespace in replacedProfileUuids) {
                    if (remote != current) apply[key] = remote
                    if (remotePresent) next[key] = remoteHash
                    else next.remove(key)
                    continue
                }

                if (old == null) {
                    if (remotePresent) {
                        if (remote != current) apply[key] = remote
                        next[key] = remoteHash
                    } else if (current != null) {
                        if (firstRemoteRestore) {
                            apply[key] = null
                        } else {
                            upload[key] = current
                            next[key] = currentHash
                        }
                    }
                    continue
                }
                // A missing key is NOT evidence of user deletion. Plugin startup,
                // a failed preferences enumeration, or a filtered snapshot can omit it.
                val explicitlyChanged = modifiedKeys.contains(key)
                if (current == null && !explicitlyChanged &&
                    remotePresent && remote != null) {
                    // A temporarily missing local preference must be repaired
                    // from the complete remote snapshot, never uploaded as delete.
                    apply[key] = remote
                    next[key] = remoteHash
                    continue
                }
                val changedLocal = SyncSafety.localHasRealChange(
                    old, currentHash, current != null, explicitlyChanged
                )
                val changedRemote = if (fullSnapshot) remoteHash != old
                    else remotePresent && remoteHash != old
                when {
                    changedLocal && changedRemote && currentHash == remoteHash -> {
                        next[key] = remoteHash
                    }
                    changedLocal && changedRemote -> {
                        // Collection keys are merged by independent membership
                        // additions/removals, using the last applied raw ancestry.
                        val collectionMerge = SharedListSync.merge(
                            key, priorLists.optString(key).takeIf {
                                priorLists.has(key)
                            }, current, remote
                        )
                        if (collectionMerge != null && collectionMerge != remote) {
                            upload[key] = collectionMerge
                            apply[key] = collectionMerge
                            next[key] = fingerprint(collectionMerge)
                        } else {
                            // Single-valued keys: the committed server version
                            // wins; retain the losing local value for recovery.
                            if (collectionMerge == null && current != null) conflicts[key] = current
                            apply[key] = remote
                            next[key] = remoteHash
                        }
                    }
                    changedRemote -> {
                        apply[key] = remote
                        next[key] = remoteHash
                    }
                    changedLocal -> {
                        upload[key] = current
                        next[key] = currentHash
                    }
                }
            }

        return Plan(apply, upload, next, conflicts)
    }
}
