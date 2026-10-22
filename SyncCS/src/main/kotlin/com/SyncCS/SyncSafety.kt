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

    fun canCreateFirstBackup(confirmed: Boolean, alreadyEnrolled: Boolean): Boolean =
        confirmed && !alreadyEnrolled

    fun acceptGeneration(local: Int, remote: Int, enrolled: Boolean): Boolean =
        if (enrolled) local > 0 && local == remote
        else local == 0 || local == remote

    fun requireProfileNotReused(
        profileId: Int, deletedOnServer: Set<Int>, deletedInBaseline: Set<Int>
    ) {
        check(profileId !in deletedOnServer && profileId !in deletedInBaseline) {
            "Profile slot $profileId was previously deleted. Automatic slot reuse is blocked " +
                "to prevent an old profile's watch history joining a new profile."
        }
    }

    fun validateDeleteBudget(count: Int, cumulative: Int, limit: Int = 25) {
        require(count >= 0 && cumulative >= 0)
        check(count + cumulative <= limit) {
            "Rolling deletion safety limit reached; manual recovery or review required."
        }
    }
}
