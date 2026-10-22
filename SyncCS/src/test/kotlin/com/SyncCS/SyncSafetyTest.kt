package com.SyncCS

import org.junit.Assert.*
import org.junit.Test

class SyncSafetyTest {
    @Test fun newDeviceUsesServerAndDoesNotUploadOldLocalSettings() {
        assertTrue(SyncSafety.firstRemoteRestore(false, false, false, true))
        assertFalse(SyncSafety.firstRemoteRestore(false, true, true, true))
    }

    @Test fun sourceBackupRetryNeverReclassifiedAsNewDevice() {
        assertFalse(SyncSafety.firstRemoteRestore(false, false, true, true))
        assertFalse(SyncSafety.firstRemoteRestore(true, false, false, true))
        assertFalse(SyncSafety.firstRemoteRestore(false, false, false, false))
    }

    @Test fun unexpectedSnapshotOmissionDoesNotCountAsDeletion() {
        assertFalse(SyncSafety.localHasRealChange("old", "<deleted>", false, false))
        assertTrue(SyncSafety.localHasRealChange("old", "<deleted>", false, true))
        assertTrue(SyncSafety.localHasRealChange("old", "changed", true, false))
    }

    @Test fun massDeletionLikeOctoberIncidentIsRejected() {
        val removed = (0 until 7778).map { "p/StremioC/key$it" }.toSet()
        assertThrows(IllegalStateException::class.java) {
            SyncSafety.validateDeletes(removed, removed, 25306)
        }
    }

    @Test fun implicitDeletionRejectedEvenWhenSingleKey() {
        assertThrows(IllegalStateException::class.java) {
            SyncSafety.validateDeletes(setOf("p/StremioC/catalog"), emptySet(), 5000)
        }
    }

    @Test fun ordinaryExplicitDeletionCanProceed() {
        val removed = setOf("p/StremioC/userRemovedCatalog")
        SyncSafety.validateDeletes(removed, removed, 5000)
    }

    @Test fun largeUnexpectedOverwriteIsRejected() {
        assertThrows(IllegalStateException::class.java) {
            SyncSafety.validateChanges(8000)
        }
        SyncSafety.validateChanges(1)
    }

    @Test fun restoringHistoryBlocksOldDeviceGenerations() {
        assertTrue(SyncSafety.acceptGeneration(0, 2, false))
        assertTrue(SyncSafety.acceptGeneration(2, 2, true))
        assertFalse(SyncSafety.acceptGeneration(1, 2, true))
    }
    @Test fun enrolledDeviceCannotBypassRecoveryGenerationByLosingLocalNumber() {
        assertFalse(SyncSafety.acceptGeneration(0, 2, true))
        assertFalse(SyncSafety.acceptGeneration(1, 2, true))
    }

    @Test fun sevenDayCumulativeDeletesCannotBeSplitIntoSmallBatches() {
        assertThrows(IllegalStateException::class.java) {
            SyncSafety.validateDeleteBudget(1, 25)
        }
        SyncSafety.validateDeleteBudget(4, 20)
    }

    @Test fun initializedDeviceCannotSilentlyReplaceDeletedRepository() {
        assertFalse(SyncSafety.canCreateFirstBackup(true, true))
        assertFalse(SyncSafety.canCreateFirstBackup(false, false))
        assertTrue(SyncSafety.canCreateFirstBackup(true, false))
    }

}
