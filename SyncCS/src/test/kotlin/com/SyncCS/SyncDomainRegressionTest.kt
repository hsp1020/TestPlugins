package com.SyncCS

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SyncDomainRegressionTest {
    private fun plugin(name: String, version: Int, url: String): JSONObject =
        JSONObject().put("type", "online").put("name", name)
            .put("repo", "https://example.org/repo.json")
            .put("url", url).put("version", version)
            .put("sha", "a".repeat(64))

    private fun inventory(vararg items: JSONObject): String =
        JSONObject().put("format", 2).put("items", JSONArray(items.toList()))
            .put("deleted", JSONArray()).toString()

    private fun profile(id: Int, name: String, uuid: String = java.util.UUID.nameUUIDFromBytes(
        "$id/$name".toByteArray()).toString()): JSONObject =
        JSONObject().put("keyIndex", id).put("name", name)
            .put("uuid", uuid)
            .put("customImage", JSONObject.NULL).put("defaultImageIndex", 0)
            .put("lockPin", JSONObject.NULL)

    private fun roster(vararg profiles: JSONObject, deleted: List<Int> = emptyList()): String =
        JSONObject().put("format", 1).put("items", JSONArray(profiles.toList()))
            .put("deleted", JSONArray(deleted)).toString()

    @Test fun firstLinkOfBDoesNotPublishItsHigherVersionLegacyPlugin() {
        val server = inventory(plugin("StreamPlay", 4, "https://server/4.cs3"))
        val staleB = inventory(plugin("StreamPlay", 99, "https://legacy/99.cs3"))
        val result = PluginSync.merge(staleB, server, null, true)
        assertFalse(result.changedRemote)
        val installed = result.target.items.values.first()
        assertEquals("https://server/4.cs3", installed.getString("url"))
    }

    @Test fun removingPluginAfterFirstSyncPropagatesWithoutManualApproval() {
        val original = inventory(plugin("StremioC", 3, "https://server/3.cs3"))
        val key = PluginSync.decode(original).items.keys.single()
        val merged = PluginSync.merge(inventory(), original, original, true)
        assertTrue(key in merged.target.deleted)
        assertTrue(key !in merged.target.items)
        assertTrue(merged.changedRemote)
    }

    @Test fun unlinkedOldDeviceCannotDeleteServerPlugin() {
        val original = inventory(plugin("StremioC", 3, "https://server/3.cs3"))
        val merged = PluginSync.merge(inventory(), original, null, true)
        assertFalse(merged.changedRemote)
        assertEquals(1, merged.target.items.size)
    }

    @Test fun connectedDeviceProfileRemovalCreatesTombstoneAutomatically() {
        val original = roster(profile(0, "Main"), profile(1, "Movies"))
        val local = roster(profile(0, "Main"))
        val merged = ProfileSync.merge(local, original, original)
        assertEquals(setOf(1), merged.target.deleted)
    }

    @Test fun unlinkedOldDeviceCannotDeleteRemoteProfile() {
        val original = roster(profile(0, "Main"), profile(1, "Movies"))
        val merged = ProfileSync.merge(roster(profile(0, "Main")), original, null)
        assertEquals(2, merged.target.items.size)
        assertTrue(merged.target.deleted.isEmpty())
    }

    @Test fun deletedProfileNumberCanBeReusedWithNewUuid() {
        val original = roster(profile(0, "Main"), deleted = listOf(1))
        val newProfile = roster(profile(0, "Main"), profile(1, "Unrelated"))
        val merged = ProfileSync.merge(newProfile, original, original)
        assertTrue(merged.target.items.containsKey(1))
        assertTrue(1 !in merged.target.deleted)
        assertEquals(profile(1, "Unrelated").getString("uuid"),
            merged.target.items.getValue(1).getString("uuid"))
    }

    @Test fun deleteAndRecreateBetweenSyncsRotatesIdentity() {
        val before = roster(profile(0, "Main"), profile(1, "Same name", "11111111-1111-4111-8111-111111111111"))
        val after = roster(profile(0, "Main"), profile(1, "Same name", "22222222-2222-4222-8222-222222222222"))
        val merged = ProfileSync.merge(after, before, before)
        assertEquals("22222222-2222-4222-8222-222222222222",
            merged.target.items.getValue(1).getString("uuid"))
        val staleB = ProfileSync.merge(before, after, before)
        assertEquals("22222222-2222-4222-8222-222222222222",
            staleB.target.items.getValue(1).getString("uuid"))
    }

    @Test fun separateUuidNamespacesPreventOldWatchHistoryAttachingToNewProfile() {
        val old = ProfileSync.decode(roster(profile(0, "Main"),
            profile(1, "Same", "11111111-1111-4111-8111-111111111111")))
        val next = ProfileSync.decode(roster(profile(0, "Main"),
            profile(1, "Same", "22222222-2222-4222-8222-222222222222")))
        val oldPath = ProfileIdentity.remotePath("d/1/video_pos_dur/123", old)
        val newPath = ProfileIdentity.remotePath("d/1/video_pos_dur/123", next)
        assertNotEquals(oldPath, newPath)
        assertNull(ProfileIdentity.nativePath(oldPath, next))
        assertEquals("d/1/video_pos_dur/123", ProfileIdentity.nativePath(newPath, next))
    }

    @Test fun oldBProfileUuidIsNeverConsideredTheNewAProfile() {
        val before = ProfileSync.decode(roster(profile(0, "Main"),
            profile(1, "Same", "11111111-1111-4111-8111-111111111111")))
        val after = ProfileSync.decode(roster(profile(0, "Main"),
            profile(1, "Same", "22222222-2222-4222-8222-222222222222")))
        val merged = ProfileSync.merge(ProfileSync.encode(before),
            ProfileSync.encode(after), ProfileSync.encode(before))
        val replaced = ProfileIdentity.replacedByServer(before, merged.target)
        assertEquals(setOf("22222222-2222-4222-8222-222222222222"), replaced)
        val localB = ProfileIdentity.toRemote(mapOf(
            "d/1/video_pos_dur/episode" to "old B playback"), merged.target)
        val serverA = mapOf(
            "u/22222222-2222-4222-8222-222222222222/video_pos_dur/episode" to "new A playback")
        assertEquals(serverA.keys, localB.keys)
        // The coordinator overwrites B in every replaced namespace even if the
        // local entry was edited more recently.
        assertTrue(localB.keys.all { it.substringAfter("u/").substringBefore('/') in replaced })
        assertEquals("new A playback", serverA[localB.keys.single()])
    }

    @Test fun deletedSlotMarkerRotatesUuidEvenIfProfileIsRecreatedOffline() {
        val first = "11111111-1111-4111-8111-111111111111"
        val second = "22222222-2222-4222-8222-222222222222"
        assertEquals(first, ProfileIdentity.resolveIdentity(first, first) {
            throw AssertionError("No rotation expected for unchanged native profile")
        })
        assertEquals(second, ProfileIdentity.resolveIdentity(first, null) { second })
        assertEquals(second, ProfileIdentity.resolveIdentity(second, second) {
            throw AssertionError("Rename must not rotate native marker")
        })
    }

    @Test fun baselineBackedLibraryDeleteIsOrdinaryChange() {
        assertTrue(SyncSafety.localHasRealChange("stored", "<deleted>", false, true))
        assertFalse(SyncSafety.localHasRealChange("stored", "<deleted>", false, false))
    }

    @Test fun authenticatedManifestRootRejectsUntrustedShardList() {
        val cipher = SyncCipher.derive("an-example-passphrase-123",
            "https://api.github.com", "device-1")
        val original = (0 until 16).map { "a".repeat(64) }.joinToString("|")
        val seal = cipher.encrypt("synccs/data-root", original)
        assertEquals(original, cipher.decrypt("synccs/data-root", seal))
        val tampered = original.replaceFirst("a", "b")
        assertNotEquals(tampered, cipher.decrypt("synccs/data-root", seal))
        assertThrows(Exception::class.java) {
            cipher.decrypt("synccs/another-purpose", seal)
        }
    }

    @Test fun libraryRemovalAfterPairingCreatesCloudDeletion() {
        val state = ProfileSync.decode(roster(profile(0, "Main")))
        val uuid = ProfileIdentity.uuid(state.items.getValue(0))
        val key = "u/$uuid/result_watch_state_data/movie"
        val saved = JSONObject().put("type", "string").put("value", "stored").toString()
        val plan = SyncPreferencePlanner.reconcile(
            mapOf(key to saved), emptyMap(),
            mutableMapOf(key to SyncBackend.digest(saved)), state,
            setOf(0), emptySet(), setOf(key), JSONObject(), false, true)
        assertTrue(plan.upload.containsKey(key))
        assertNull(plan.upload[key])
        assertTrue(plan.apply.isEmpty())
    }

    @Test fun firstLinkIgnoresOldLocalWatchHistory() {
        val state = ProfileSync.decode(roster(profile(0, "Main")))
        val uuid = ProfileIdentity.uuid(state.items.getValue(0))
        val canonical = "u/$uuid/result_watch_state_data/new"
        val stale = "u/$uuid/result_watch_state_data/old"
        val newValue = JSONObject().put("type", "string").put("value", "server").toString()
        val oldValue = JSONObject().put("type", "string").put("value", "stale").toString()
        val plan = SyncPreferencePlanner.reconcile(
            mapOf(canonical to newValue), mapOf(stale to oldValue),
            mutableMapOf(), state, setOf(0), emptySet(), emptySet(),
            JSONObject(), true, true)
        assertEquals(newValue, plan.apply[canonical])
        assertTrue(plan.apply.containsKey(stale))
        assertNull(plan.apply[stale])
        assertTrue(plan.upload.isEmpty())
    }

    @Test fun olderDeviceReceivesRemoteDeletionInsteadOfResurrectingIt() {
        val state = ProfileSync.decode(roster(profile(0, "Main")))
        val uuid = ProfileIdentity.uuid(state.items.getValue(0))
        val key = "u/$uuid/result_watch_state_data/movie"
        val current = JSONObject().put("type", "string").put("value", "stale").toString()
        val plan = SyncPreferencePlanner.reconcile(
            emptyMap(), mapOf(key to current),
            mutableMapOf(key to SyncBackend.digest(current)), state,
            setOf(0), emptySet(), emptySet(), JSONObject(), false, true)
        assertTrue(plan.apply.containsKey(key))
        assertNull(plan.apply[key])
        assertTrue(plan.upload.isEmpty())
    }

}
