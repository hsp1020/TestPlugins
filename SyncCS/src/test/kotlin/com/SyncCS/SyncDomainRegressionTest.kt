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



    @Test fun changedOnlyReadDoesNotLoseUnrelatedBaselineKeys() {
        val profiles = ProfileSync.decode(roster(profile(0, "Main")))
        val changedKey = "p/StremioC/theme"
        val untouchedKey = "p/StreamPlay/settings"
        val previous = "{\"type\":\"string\",\"value\":\"old\"}"
        val newValue = "{\"type\":\"string\",\"value\":\"new\"}"
        val oldHash = SyncBackend.digest(previous)
        val baselines = mutableMapOf(changedKey to oldHash, untouchedKey to oldHash)
        val plan = SyncPreferencePlanner.reconcile(
            emptyMap(), mapOf(changedKey to newValue), baselines, profiles,
            setOf(0), emptySet(), setOf(changedKey), JSONObject(), false,
            false, false)
        assertEquals(newValue, plan.upload[changedKey])
        assertFalse(plan.upload.containsKey(untouchedKey))
        assertFalse(plan.apply.containsKey(untouchedKey))
        assertEquals(oldHash, plan.next[untouchedKey])
    }

    @Test fun completeAuditFindsUnobservedNewPreferenceWithoutDeletion() {
        val profiles = ProfileSync.decode(roster(profile(0, "Main")))
        val key = "p/StremioC/unobserved_new_setting"
        val current = "{\"type\":\"string\",\"value\":\"present\"}"
        val plan = SyncPreferencePlanner.reconcile(
            emptyMap(), mapOf(key to current), mutableMapOf(), profiles,
            setOf(0), emptySet(), emptySet(), JSONObject(), false,
            false, true)
        assertEquals(current, plan.upload[key])
        assertEquals(SyncBackend.digest(current), plan.next[key])
        assertTrue(plan.apply.isEmpty())
    }

    @Test fun explicitLocalDeletionIsStillPropagatedWithoutFullSnapshot() {
        val profiles = ProfileSync.decode(roster(profile(0, "Main")))
        val key = "p/StremioC/deleted_setting"
        val previous = "{\"type\":\"bool\",\"value\":true}"
        val plan = SyncPreferencePlanner.reconcile(
            emptyMap(), emptyMap(), mutableMapOf(key to SyncBackend.digest(previous)),
            profiles, setOf(0), emptySet(), setOf(key), JSONObject(),
            false, false, false)
        assertTrue(plan.upload.containsKey(key))
        assertNull(plan.upload[key])
        assertEquals("<deleted>", plan.next[key])
        assertTrue(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(null, "<deleted>"))
    }

    @Test fun pluginJsonFieldOrderDoesNotCauseNewGitCommit() {
        val old = inventory(plugin("StremioC", 17, "https://example.org/stremioc.cs3"))
        val permuted = JSONObject().put("sha", "a".repeat(64))
            .put("version", 17).put("url", "https://example.org/stremioc.cs3")
            .put("repo", "https://example.org/repo.json").put("name", "StremioC")
            .put("type", "online")
        val merged = PluginSync.merge(inventory(permuted), old, old, true)
        assertFalse(merged.changedRemote)
    }

    @Test fun selectedAndFullSnapshotShareOrphanHeaderExclusion() {
        val referenced = setOf("42", "88")
        assertTrue(SyncData.includeHeaderCache("download_header_cache/42", referenced))
        assertTrue(SyncData.includeHeaderCache("download_header_cache/88", referenced))
        assertFalse(SyncData.includeHeaderCache("download_header_cache/41", referenced))
        assertFalse(SyncData.includeHeaderCache("download_header_cache/99", emptySet()))
        assertTrue(SyncData.includeHeaderCache("0/video_pos_dur/42", referenced))
    }

    @Test fun unreferencedHeaderMarkerCannotJustifyDeletingAnExistingServerValue() {
        val state = ProfileSync.decode(roster(profile(0, "Main")))
        val key = "d/download_header_cache/unused"
        val plan = SyncPreferencePlanner.reconcile(
            emptyMap(), emptyMap(), mutableMapOf(key to SyncBackend.digest("old")),
            state, setOf(0), emptySet(), emptySet(), JSONObject(), false,
            false, false)
        assertFalse(plan.upload.containsKey(key))
        assertTrue(plan.next.containsKey(key))
    }

    @Test fun progressThrottleIsBoundedAndDoesNotDelayOtherPreferences() {
        assertTrue(SyncPlaybackThrottle.isProgressPath("d/0/video_pos_dur/123"))
        assertTrue(SyncPlaybackThrottle.isProgressPath("d/0/result_resume_watching_2/123"))
        assertTrue(SyncPlaybackThrottle.isProgressPath("d/result_resume_watching_2/123"))
        assertFalse(SyncPlaybackThrottle.isProgressPath("d/0/result_watch_state_data/123"))
        assertFalse(SyncPlaybackThrottle.isProgressPath("p/StremioC/catalog"))
        assertFalse(SyncPlaybackThrottle.isProgressPath("s/user_settings"))
        assertEquals(5_000L, SyncPlaybackThrottle.delayMs(500L, 0L))
        assertEquals(19_000L, SyncPlaybackThrottle.delayMs(11_000L, 30_000L))
        assertEquals(0L, SyncPlaybackThrottle.delayMs(31_000L, 30_000L))
    }

    @Test fun remoteTombstoneInPartialSnapshotIsPreserved() {
        val profiles = ProfileSync.decode(roster(profile(0, "Main")))
        val key = "d/0/video_pos_dur/example"
        val before = "{\"type\":\"string\",\"value\":\"position\"}"
        val baseline = mutableMapOf(key to SyncBackend.digest(before))
        val p = SyncPreferencePlanner.reconcile(
            mapOf(key to null), emptyMap(), baseline, profiles,
            setOf(0), emptySet(), emptySet(), JSONObject(), false,
            false, false)
        assertTrue(p.apply.containsKey(key))
        assertNull(p.apply[key])
        assertEquals("<deleted>", p.next[key])
        assertTrue(p.upload.isEmpty())
    }

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

    @Test fun pluginRestoreRequiresActivityButIsIndependentFromLibrarySync() {
        assertFalse(SyncPluginRestorePolicy.canRestoreNow(false, true))
        assertFalse(SyncPluginRestorePolicy.canRestoreNow(true, false))
        assertTrue(SyncPluginRestorePolicy.canRestoreNow(true, true))
    }

    @Test fun staleDirtyMarkersAreOnlyRemovedAfterValueMatchesVerifiedBaseline() {
        val saved = JSONObject().put("type", "string").put("value", "saved").toString()
        val altered = JSONObject().put("type", "string").put("value", "local-change").toString()
        val hash = SyncBackend.digest(saved)
        assertTrue(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(saved, hash))
        assertFalse(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(altered, hash))
        assertFalse(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(null, hash))
        assertTrue(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(null, null))
        assertFalse(SyncDirtyMarkerCleaner.matchesVerifiedBaseline(saved, null))
    }


    @Test fun failedCommitWithUnchangedPluginBinariesCanRecoverWithoutActivity() {
        assertFalse(SyncRecoveryPolicy.requiresForegroundBinaryRestore(true, false))
        assertFalse(SyncRecoveryPolicy.keepNewPlugins(true, false))
    }

    @Test fun pluginInstalledAfterCheckpointSurvivesRecovery() {
        assertTrue(SyncRecoveryPolicy.wasChangedAfter(2001L, 2000L))
        assertTrue(SyncRecoveryPolicy.keepNewPlugins(false, true))
        assertFalse(SyncRecoveryPolicy.requiresForegroundBinaryRestore(false, true))
    }

    @Test fun syncMutatedBinariesStillRequireForegroundRollback() {
        assertTrue(SyncRecoveryPolicy.requiresForegroundBinaryRestore(false, false))
        assertFalse(SyncRecoveryPolicy.keepNewPlugins(false, false))
    }

    @Test fun dirtyMarkerCreatedBeforeCheckpointIsNotTreatedAsNewUserAction() {
        assertFalse(SyncRecoveryPolicy.wasChangedAfter(1000L, 1000L))
        assertFalse(SyncRecoveryPolicy.wasChangedAfter(999L, 1000L))
        assertFalse(SyncRecoveryPolicy.wasChangedAfter("invalid", 1000L))
    }


    @Test fun rejectedCredentialIsOnlyBlockedUntilNewEncryptedTokenArrives() {
        val old = "encrypted-session-before-reauth"
        val renewed = "encrypted-session-after-reauth"
        val rejected = SyncAuthGuard.tokenHash(old)
        assertNotNull(rejected)
        assertTrue(SyncAuthGuard.matchesRejectedToken(old, rejected))
        assertFalse(SyncAuthGuard.matchesRejectedToken(renewed, rejected))
        assertFalse(SyncAuthGuard.matchesRejectedToken(null, rejected))
        assertFalse(SyncAuthGuard.matchesRejectedToken(old, null))
    }

    @Test fun continueWatchingHeaderSelectionExcludesUnrelatedAndRetiredProfiles() {
        val keys = listOf(
            "0/result_resume_watching_2/42",
            "1/result_watch_state_data/88",
            "0/video_pos_dur/900",
            "0/result_resume_watching_2/not-an-id",
            "download_header_cache/999"
        )
        val active = keys.filter { it.substringBefore('/').toIntOrNull() in setOf(0, 1) }
        assertEquals(setOf("42", "88"), SyncData.referencedHeaderIds(active))
        assertTrue(SyncData.isTracked("d/download_header_cache/42"))
        assertFalse(SyncData.isTracked("d/BACKUP_download_header_cache/42"))
    }

    @Test fun arrivingContinueWatchingRecordKeepsItsHeaderRestore() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val apply = mutableMapOf<String, String?>(
            "u/$uuid/result_resume_watching_2/42" to "resume",
            "d/download_header_cache/42" to "header"
        )
        SyncData.excludeUnreferencedHeaderRestores(emptyMap(), apply, emptyMap())
        assertEquals("header", apply["d/download_header_cache/42"])
    }

    @Test fun deletedResumeDoesNotReimportOrphanedHeaderCache() {
        val uuid = "11111111-1111-4111-8111-111111111111"
        val resume = "u/$uuid/result_resume_watching_2/42"
        val apply = mutableMapOf<String, String?>(
            "d/download_header_cache/42" to "stale",
            "d/download_header_cache/99" to "unrelated"
        )
        SyncData.excludeUnreferencedHeaderRestores(
            mapOf(resume to "old"), apply, mapOf(resume to null)
        )
        assertTrue(apply.isEmpty())
    }

    @Test fun pendingGithubOAuthCannotEnableUnverifiedSync() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SyncConfiguration(context)
        config.preferences.edit().clear()
            .putString("github_pending_token", "encrypted-but-not-active")
            .putString("github_pending_client", "test-client")
            .commit()
        assertFalse(config.active())
        assertNull(GithubAuthFlow.pending(config, "other-client"))
        assertFalse(config.active())
        config.clearAccount()
        assertFalse(config.preferences.contains("github_pending_token"))
        assertFalse(config.preferences.contains("github_pending_client"))
    }

    @Test fun oauthNetworkRetryOnlyAcceptsTemporaryFailures() {
        assertTrue(GithubAuthFlow.isTransient(java.net.UnknownHostException("offline")))
        assertTrue(GithubAuthFlow.isTransient(java.net.SocketTimeoutException("timeout")))
        assertTrue(GithubAuthFlow.isTransient(GithubBackupStore.ApiException(503, "temporary")))
        assertFalse(GithubAuthFlow.isTransient(GithubBackupStore.ApiException(401, "revoked")))
        assertFalse(GithubAuthFlow.isTransient(GithubBackupStore.ApiException(403, "forbidden")))
        assertFalse(GithubAuthFlow.isTransient(GithubBackupStore.ApiException(404, "missing")))
        assertFalse(GithubAuthFlow.isTransient(java.io.IOException("invalid passphrase")))
    }

    @Test fun oauthRetryNeverRepeatsUnauthorizedRequest() {
        var attempts = 0
        try {
            GithubAuthFlow.retry("test-profile") {
                attempts++
                throw GithubBackupStore.ApiException(401, "rejected")
            }
            fail("401 must fail without retry")
        } catch (error: GithubBackupStore.ApiException) {
            assertEquals(401, error.code)
        }
        assertEquals(1, attempts)
    }

    @Test fun manualBackupReferencesAreSeparateAndSorted() {
        val a = "1".repeat(40)
        val b = "2".repeat(40)
        val records = JSONArray()
            .put(JSONObject().put("ref", "refs/tags/synccs/manual/20261023T120100Z-abcdef123456")
                .put("object", JSONObject().put("type", "commit").put("sha", a)))
            .put(JSONObject().put("ref", "refs/tags/synccs/manual/20261023T121100Z-abcdef123457")
                .put("object", JSONObject().put("type", "commit").put("sha", b)))
            .put(JSONObject().put("ref", "refs/tags/other-branch")
                .put("object", JSONObject().put("type", "commit").put("sha", a)))
            .put(JSONObject().put("ref", "refs/tags/synccs/manual/20261023T121200Z-abcdef123458")
                .put("object", JSONObject().put("type", "tag").put("sha", a)))
        val parsed = GithubBackupStore("unused", "unused").parseManualBackups(records)
        assertEquals(2, parsed.size)
        assertEquals(b, parsed[0].id)
        assertEquals(a, parsed[1].id)
        assertTrue(parsed.all { it.summary == "SyncCS user backup" })
    }

    @Test fun oauthCredentialPersistsExpirationAndRefreshDetails() {
        val before = System.currentTimeMillis()
        val credential = GithubDeviceCode.Credential.issued(JSONObject()
            .put("access_token", "access1")
            .put("refresh_token", "refresh1")
            .put("expires_in", 28800)
            .put("refresh_token_expires_in", 15897600))
        assertEquals("access1", credential.access)
        assertEquals("refresh1", credential.refresh)
        assertTrue(credential.issuedAt >= before)
        assertTrue(credential.expiresAt >= before + 28800000L)
        assertTrue(credential.refreshExpiresAt >= before + 15897600000L)
        assertEquals(credential, GithubDeviceCode.Credential.decode(credential.encode()))
    }

    @Test fun unexpected401RenewsAndRetriesExactlyOnce() {
        val seen = mutableListOf<String>()
        var renewals = 0
        val outcome = GithubAuthRetry.once("old", { rejected ->
            assertEquals("old", rejected)
            renewals++
            "fresh"
        }) { token ->
            seen += token
            if (token == "old") throw GithubBackupStore.ApiException(401, "Bad credentials")
            "verified"
        }
        assertEquals("verified", outcome)
        assertEquals(listOf("old", "fresh"), seen)
        assertEquals(1, renewals)
    }

    @Test fun unexpected401WithoutRefreshDoesNotRepeatRequest() {
        var attempts = 0
        try {
            GithubAuthRetry.once("old", { null }) {
                attempts++
                throw GithubBackupStore.ApiException(401, "Bad credentials")
            }
            fail("401 must stop without a fresh credential")
        } catch (error: GithubBackupStore.ApiException) {
            assertEquals(401, error.code)
        }
        assertEquals(1, attempts)
    }

    @Test fun unauthorizedRetryRejectsSecond401WithoutFurtherRefresh() {
        var attempts = 0
        var renewals = 0
        try {
            GithubAuthRetry.once("old", {
                renewals++
                "fresh"
            }) {
                attempts++
                throw GithubBackupStore.ApiException(401, "Bad credentials")
            }
            fail("Repeated 401 must not loop")
        } catch (error: GithubBackupStore.ApiException) {
            assertEquals(401, error.code)
        }
        assertEquals(2, attempts)
        assertEquals(1, renewals)
    }

    @Test fun otherGitHubErrorsMustNotTriggerRefresh() {
        var renewals = 0
        try {
            GithubAuthRetry.once("old", {
                renewals++
                "fresh"
            }) {
                throw GithubBackupStore.ApiException(403, "Forbidden")
            }
            fail("403 must propagate")
        } catch (error: GithubBackupStore.ApiException) {
            assertEquals(403, error.code)
        }
        assertEquals(0, renewals)
    }

    @Test fun newDeviceAuthorizationRequestsRotatingTokens() {
        assertEquals("repo offline_access", GithubDeviceCode.DEVICE_SCOPE)
    }

    @Test fun rejectedAuthorizationIsRecoverableOnlyWithUnusedRefreshCredential() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SyncConfiguration(context)
        config.preferences.edit().clear()
            .putString("github_token", "sample-encrypted-access")
            .putString("github_client_id", "test-client")
            .putString("github_refresh_token", "sample-encrypted-refresh")
            .commit()
        SyncAuthGuard.reject(config, 401)
        assertTrue(SyncAuthGuard.mayRecover(config))
        SyncAuthGuard.recordRefreshFailure(config, permanent = true)
        assertFalse(SyncAuthGuard.mayRecover(config))
        SyncAuthGuard.clearAfterLogin(config)
        assertFalse(SyncAuthGuard.needsReauthentication(config))
    }

    @Test fun oauthDiagnosticsRetainOnlyBoundedSafeTokenIdentifiers() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val config = SyncConfiguration(context)
        config.preferences.edit().clear().commit()
        for (index in 0 until 20) {
            GithubAuthDiagnostics.record(config, "test-issued", "test-secret-$index",
                "expiresAtMs=1 refreshPresent=true")
        }
        val stored = config.preferences.getString(GithubAuthDiagnostics.HISTORY_KEY, null)!!
        val events = JSONArray(stored)
        assertEquals(12, events.length())
        assertFalse(stored.contains("test-secret-"))
        assertEquals(GithubAuthDiagnostics.fingerprint("test-secret-19"),
            events.getJSONObject(11).getString("accessId"))
        assertEquals("test-issued", events.getJSONObject(0).getString("event"))
    }

    @Test fun oauthLegacyCredentialWithoutExpirationStaysCompatible() {
        val legacy = GithubDeviceCode.Credential.decode("old-token")
        assertEquals("old-token", legacy.access)
        assertNull(legacy.refresh)
        assertEquals(0L, legacy.expiresAt)
        assertEquals(0L, legacy.issuedAt)
        assertEquals(0L, legacy.refreshExpiresAt)
    }

    @Test fun interruptedSyncMustNotRollbackNewGithubCredentials() {
        for (key in listOf("github_token", "github_login", "github_account_id",
                "github_client_id", "github_refresh_token",
                "github_token_expires_at", "github_token_issued_at",
                "github_refresh_expires_at", "sync_transport", "secret")) {
            assertFalse(LocalSyncJournal.canRestoreJournalKey(key))
        }
        for (key in listOf("base", "remote_cursor", "profile_baseline",
                "plugin_baseline", "remote_versions", "pending_plugins")) {
            assertTrue(LocalSyncJournal.canRestoreJournalKey(key))
        }
    }


}
