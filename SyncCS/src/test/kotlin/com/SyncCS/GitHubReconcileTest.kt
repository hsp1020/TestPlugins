package com.SyncCS

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReconcileTest {
    private fun sample(generation: String = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa",
                       settings: Map<String, String> = emptyMap()): GitHubSnapshot =
        GitHubSnapshot(generation, "device-1", "{}", "{}", settings)

    @Test fun missingLocalEntryIsNotAnImplicitDelete() {
        val base = sample(settings = mapOf("p/StremioC/stremio_saved_links" to "A"))
        val current = sample()
        val server = sample(settings = base.settings)
        val plan = GitHubSyncEngine.reconcile(base, current, server, emptySet())
        assertEquals("A", plan.merged.settings["p/StremioC/stremio_saved_links"])
        assertEquals(0, plan.deleted)
        assertEquals(0, plan.changed)
    }

    @Test fun explicitSingleDeletionIsSupportedByPlanner() {
        val key = "p/StremioC/old"
        val base = sample(settings = mapOf(key to "A"))
        val plan = GitHubSyncEngine.reconcile(base, sample(), base, setOf(key))
        assertFalse(plan.merged.settings.containsKey(key))
        assertEquals(1, plan.deleted)
    }

    @Test fun massDeletionIsRejectedEvenIfIncorrectlyMarkedExplicit() {
        val data = (0..100).associate { "p/StremioC/config_" + it to "old" }
        val base = sample(settings = data)
        val deleted = data.keys.take(11).toSet()
        val current = sample(settings = data.filterKeys { it !in deleted })
        var blocked = false
        try {
            GitHubSyncEngine.reconcile(base, current, base, deleted)
        } catch (error: IllegalStateException) {
            blocked = true
        }
        assertTrue(blocked)
    }

    @Test fun staleDeviceCannotWriteAfterGenerationChange() {
        val base = sample(settings = mapOf("d/setting" to "A"))
        val current = sample(settings = mapOf("d/setting" to "B"))
        val server = sample(
            generation = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb",
            settings = mapOf("d/setting" to "A")
        )
        var blocked = false
        try {
            GitHubSyncEngine.reconcile(base, current, server, emptySet())
        } catch (error: IllegalStateException) {
            blocked = true
        }
        assertTrue(blocked)
    }

    @Test fun collisionPrefersRemoteAndRecordsConflict() {
        val key = "p/StremioC/stremio_saved_links"
        val base = sample(settings = mapOf(key to "A"))
        val current = sample(settings = mapOf(key to "B"))
        val server = sample(settings = mapOf(key to "C"))
        val plan = GitHubSyncEngine.reconcile(base, current, server, emptySet())
        assertEquals("C", plan.merged.settings[key])
        assertEquals(1, plan.conflicts)
        assertEquals(0, plan.changed)
    }

    @Test fun singleLocalUpdateWinsWhenServerHasNotChanged() {
        val key = "d/0/result_resume_watching_2/1"
        val base = sample(settings = mapOf(key to "time=10"))
        val current = sample(settings = mapOf(key to "time=15"))
        val plan = GitHubSyncEngine.reconcile(base, current, base, emptySet())
        assertEquals("time=15", plan.merged.settings[key])
        assertEquals(1, plan.changed)
    }

    @Test fun hugeLocalInjectionIsRefused() {
        val additions = (1..110).associate { "p/Legacy/value_" + it to "stale" }
        val empty = sample()
        var blocked = false
        try {
            GitHubSyncEngine.reconcile(empty, sample(settings = additions), empty, emptySet())
        } catch (error: IllegalStateException) {
            blocked = true
        }
        assertTrue(blocked)
    }
}
