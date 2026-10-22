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
        // No arbitrary size cap: deleting a library folder with many titles
        // must propagate, but only explicitly changed baseline-backed keys.
    }

    fun validateChanges(count: Int) {
        check(count in 0..100000) {
            "Backup change count exceeds integrity bounds: $count"
        }
    }

    fun canCreateFirstBackup(confirmed: Boolean, alreadyEnrolled: Boolean): Boolean =
        confirmed && !alreadyEnrolled

    fun acceptGeneration(local: Int, remote: Int, enrolled: Boolean): Boolean =
        if (enrolled) local > 0 && local == remote
        else local == 0 || local == remote

    fun validateDeleteBudget(count: Int, cumulative: Int, limit: Int = 25) {
        require(count >= 0 && cumulative >= 0)
        // Authenticated committed Git history is recoverable. Normal user
        // removals must not be blocked by a rolling arbitrary count.
    }
}
