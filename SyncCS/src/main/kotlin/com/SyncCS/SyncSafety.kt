package com.SyncCS

/** Pure fail-closed guards. Tested on the JVM independently of CloudStream. */
internal object SyncSafety {
    fun firstRemoteRestore(
        baselineExists: Boolean,
        createdFreshBackup: Boolean,
        confirmedSourceDevice: Boolean,
        remoteHasData: Boolean
    ): Boolean =
        !baselineExists && !createdFreshBackup && !confirmedSourceDevice && remoteHasData

    fun localHasRealChange(
        previousHash: String, currentHash: String,
        keyIsPresent: Boolean, explicitChange: Boolean
    ): Boolean =
        previousHash != currentHash && (keyIsPresent || explicitChange)

    fun validateDeletes(
        deleted: Set<String>, explicit: Set<String>, currentTotal: Int
    ) {
        check(deleted.all { it in explicit }) {
            "Incomplete local snapshot: implicit deletion blocked."
        }
        check(deleted.size <= 25 && deleted.size <= maxOf(2, currentTotal / 100)) {
            "Mass deletion blocked: " + deleted.size
        }
    }

    fun validateChanges(count: Int) {
        check(count <= 1000) { "Mass update blocked: $count" }
    }

    fun acceptGeneration(local: Int, remote: Int): Boolean =
        local == 0 || local == remote
}
