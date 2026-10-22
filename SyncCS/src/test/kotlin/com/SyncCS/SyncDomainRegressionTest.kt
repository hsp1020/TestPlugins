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

    private fun profile(id: Int, name: String): JSONObject =
        JSONObject().put("keyIndex", id).put("name", name)
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

    @Test fun unapprovedPluginRemovalCannotReachGithub() {
        val original = inventory(plugin("StremioC", 3, "https://server/3.cs3"))
        assertThrows(IllegalStateException::class.java) {
            PluginSync.merge(inventory(), original, original, true)
        }
        val key = PluginSync.decode(original).items.keys.single()
        val approved = PluginSync.merge(inventory(), original, original, true, setOf(key))
        assertTrue(key in approved.target.deleted)
    }

    @Test fun inactiveNativeProfileCannotBeDeletedWithoutApproval() {
        val original = roster(profile(0, "Main"), profile(1, "Movies"))
        val local = roster(profile(0, "Main"))
        assertThrows(IllegalStateException::class.java) {
            ProfileSync.merge(local, original, original)
        }
        val allowed = ProfileSync.merge(local, original, original, setOf(1))
        assertEquals(setOf(1), allowed.target.deleted)
    }

    @Test fun reusingTombstonedProfileNumberIsBlocked() {
        val original = roster(profile(0, "Main"), deleted = listOf(1))
        val newProfile = roster(profile(0, "Main"), profile(1, "Unrelated"))
        assertThrows(IllegalStateException::class.java) {
            ProfileSync.merge(newProfile, original, original)
        }
    }

    @Test fun deletingOnePreferenceRequiresManualApprovalOfExactProposal() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SyncConfiguration(context)
        config.preferences.edit().clear().commit()
        val requested = setOf("p/StremioC/stremio_saved_links")
        assertThrows(IllegalStateException::class.java) {
            SyncDeletionGate.requireApproval(config, requested, $revision)
        }
        assertEquals(requested, SyncDeletionGate.pending(config).toSet())
        SyncDeletionGate.authorizePending(config)
        SyncDeletionGate.requireApproval(config, requested, $revision)
        assertTrue(SyncDeletionGate.approved(config, requested, $revision).containsAll(requested))
        assertTrue(SyncDeletionGate.approved(config, setOf("p/Other/unknown"), $revision).isEmpty())
        assertTrue(SyncDeletionGate.approved(config, requested, $other).isEmpty())
        assertTrue(SyncDeletionGate.approved(config,
            requested + "p/StremioC/other", $revision).isEmpty())
        SyncDeletionGate.consumed(config)
        assertTrue(SyncDeletionGate.approved(config, requested, $revision).isEmpty())
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

    @Test fun onlyReviewedDeletionSetAndRevisionAreAccepted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SyncConfiguration(context)
        config.preferences.edit().clear().commit()
        val reviewed = setOf("d/0/search_history/one")
        val revA = "a".repeat(40)
        val revB = "b".repeat(40)
        assertThrows(IllegalStateException::class.java) {
            SyncDeletionGate.requireApproval(config, reviewed, revA)
        }
        SyncDeletionGate.authorizePending(config)
        assertEquals(reviewed, SyncDeletionGate.approved(config, reviewed, revA))
        assertTrue(SyncDeletionGate.approved(config, reviewed, revB).isEmpty())
        assertTrue(SyncDeletionGate.approved(config,
            reviewed + "d/0/search_history/two", revA).isEmpty())
        SyncDeletionGate.consumed(config)
    }

}
