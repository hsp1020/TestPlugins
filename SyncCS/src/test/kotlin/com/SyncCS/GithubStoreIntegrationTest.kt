package com.SyncCS

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs the production GithubBackupStore against an in-memory Git Data API.
 * Two stores use the SAME simulated repository; no GitHub token or network.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class GithubStoreIntegrationTest {
    private class FakeGit {
        private var sequence = 0L
        private fun sha(): String = (++sequence).toString(16).padStart(40, '0')
        private val blobs = linkedMapOf<String, ByteArray>()
        private val trees = linkedMapOf<String, Map<String, String>>()
        private val commits = linkedMapOf<String, Pair<String, String?>>()
        private var head: String
        var failNextPublish = false
        val simultaneousBlobReads = AtomicInteger()
        val peakBlobReads = AtomicInteger()

        init {
            val tree = sha()
            trees[tree] = emptyMap()
            head = sha()
            commits[head] = tree to null
        }

        private fun responseId(id: String) = JSONObject().put("sha", id)
        private fun path(url: String): String = url.removePrefix(
            "https://api.github.com/repos/test-owner/cloudstream-synccs-backup"
        )

        fun handle(url: String, method: String, body: JSONObject?): JSONObject {
            val p = path(url)
            when {
                method == "GET" && p == "" -> return JSONObject()
                    .put("private", true)
                    .put("owner", JSONObject().put("login", "test-owner"))
                    .put("default_branch", "main")
                method == "GET" && p == "/git/ref/heads/main" ->
                    return JSONObject().put("object", responseId(head))
                method == "GET" && p.startsWith("/compare/") -> {
                    val specs = p.substringAfter("/compare/").substringBefore("?").split("...")
                    val base = specs[0]
                    val target = specs[1]
                    val chain = mutableListOf<String>()
                    var cursor: String? = target
                    while (cursor != null && cursor != base && chain.size <= commits.size) {
                        chain.add(cursor)
                        cursor = commits[cursor]?.second
                    }
                    if (cursor != base) return JSONObject().put("status", "diverged")
                        .put("total_commits", 0).put("commits", JSONArray())
                    chain.reverse()
                    val query = p.substringAfter("?", "")
                    val params = query.split("&").mapNotNull { entry ->
                        val fields = entry.split("=", limit = 2)
                        if (fields.size == 2) fields[0] to fields[1] else null
                    }.toMap()
                    val perPage = params["per_page"]?.toInt() ?: 30
                    val page = params["page"]?.toInt() ?: 1
                    check(perPage in 1..100 && page > 0)
                    val entries = JSONArray()
                    for (revision in chain.drop((page - 1) * perPage).take(perPage)) {
                        entries.put(JSONObject().put("sha", revision)
                            .put("parents", JSONArray().put(JSONObject()
                                .put("sha", commits.getValue(revision).second))))
                    }
                    return JSONObject().put("status", if (chain.isEmpty()) "identical" else "ahead")
                        .put("total_commits", chain.size).put("commits", entries)
                }
                method == "GET" && p.startsWith("/git/commits/") -> {
                    val key = p.substringAfterLast("/")
                    val tree = commits[key]?.first ?: error("Commit not found $key")
                    return JSONObject().put("tree", responseId(tree))
                }
                method == "GET" && p.startsWith("/git/trees/") -> {
                    val key = p.substringAfter("/git/trees/").substringBefore("?")
                    val contents = trees[key] ?: error("Tree not found $key")
                    val rows = JSONArray()
                    contents.forEach { (file, id) -> rows.put(JSONObject()
                        .put("path", file).put("sha", id).put("type", "blob")) }
                    return JSONObject().put("truncated", false).put("tree", rows)
                }
                method == "GET" && p.startsWith("/git/blobs/") -> {
                    val id = p.substringAfterLast("/")
                    val running = simultaneousBlobReads.incrementAndGet()
                    peakBlobReads.updateAndGet { maxOf(it, running) }
                    try {
                        Thread.sleep(15)
                        val bytes = blobs[id] ?: error("Blob not found $id")
                        return JSONObject().put("encoding", "base64")
                            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                    } finally {
                        simultaneousBlobReads.decrementAndGet()
                    }
                }
                method == "POST" && p == "/git/blobs" -> {
                    val bytes = Base64.decode(body!!.getString("content"), Base64.DEFAULT)
                    val key = sha()
                    blobs[key] = bytes
                    return responseId(key)
                }
                method == "POST" && p == "/git/trees" -> {
                    val old = trees[body!!.getString("base_tree")] ?: error("Unknown base tree")
                    val next = old.toMutableMap()
                    val items = body.getJSONArray("tree")
                    for (i in 0 until items.length()) {
                        val e = items.getJSONObject(i)
                        next[e.getString("path")] = e.getString("sha")
                    }
                    val key = sha()
                    trees[key] = next
                    return responseId(key)
                }
                method == "POST" && p == "/git/commits" -> {
                    val parent = body!!.getJSONArray("parents").getString(0)
                    val tree = body.getString("tree")
                    check(parent in commits && tree in trees)
                    val key = sha()
                    commits[key] = tree to parent
                    return responseId(key)
                }
                method == "PATCH" && p == "/git/refs/heads/main" -> {
                    if (failNextPublish) {
                        failNextPublish = false
                        throw IOException("Injected interrupted network publish")
                    }
                    val next = body!!.getString("sha")
                    if (body.optBoolean("force") || commits[next]?.second != head)
                        throw GithubBackupStore.ApiException(422, "non-fast-forward")
                    head = next
                    return JSONObject().put("object", responseId(head))
                }
                else -> error("Unexpected mock GitHub API operation: $method $url")
            }
        }

        fun fileId(path: String): String? =
            trees[commits.getValue(head).first]?.get(path)

        fun blobContents(path: String): String? =
            fileId(path)?.let { String(blobs.getValue(it), Charsets.UTF_8) }

        fun corruptFile(path: String) {
            val sha = fileId(path) ?: error("Missing path: $path")
            blobs[sha] = "{}".toByteArray(Charsets.UTF_8)
        }

        fun installLegacyV2Manifest() {
            val oldTree = trees.getValue(commits.getValue(head).first)
            val manifestSha = sha()
            blobs[manifestSha] = JSONObject().put("format", 2)
                .put("complete", true).toString().toByteArray(Charsets.UTF_8)
            val treeSha = sha()
            trees[treeSha] = oldTree + ("synccs/manifest.json" to manifestSha)
            val commitSha = sha()
            commits[commitSha] = treeSha to head
            head = commitSha
        }

        fun corruptOneDataBlob() {
            val currentTree = trees[commits.getValue(head).first]!!
            val key = currentTree.entries.first {
                it.key.startsWith("synccs/data/") &&
                    String(blobs.getValue(it.value), Charsets.UTF_8) != "{}"
            }.value
            blobs[key] = "{}".toByteArray()
        }
    }

    private fun store(api: FakeGit, name: String): GithubBackupStore =
        GithubBackupStore("test-token", "test-owner",
            deviceName = name, deviceId = name,
            transport = api::handle)

    private fun cipher() = SyncCipher.derive(
        "strong-github-backup-passphrase", "https://api.github.com", "test-owner-id"
    )

    @Test fun changedShardCorruptionFailsClosedInsteadOfApplyingPartialData() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val key = "d/0/video_pos_dur/movie"
        val first = a.initialize(c, mapOf(key to "100"))
        a.publishPartial(first, 1, c, mapOf(key to "200"), emptySet())
        api.corruptOneDataBlob()
        assertThrows(IllegalStateException::class.java) {
            b.incrementalSince(first, c, 1)
        }
    }

    @Test fun moreThanEightCommitsRemainsIncremental() {
        val api = FakeGit()
        val writer = store(api, "A")
        val reader = store(api, "B")
        val key = cipher()
        writer.initialize(key, mapOf("d/0/video_pos_dur/seed" to "before"))
        val baseline = reader.current(key)!!
        var revision = baseline.revision
        for (i in 1..12) {
            revision = writer.publishPartial(revision, baseline.generation, key,
                mapOf("d/0/video_pos_dur/seed" to "update-$i"), emptySet())
        }
        val delta = reader.incrementalSince(baseline.revision, key, baseline.generation)!!
        assertEquals(revision, delta.revision)
        assertEquals(setOf("d/0/video_pos_dur/seed"), delta.encryptedChanges.keys)
        assertEquals("update-12", key.decrypt("d/0/video_pos_dur/seed",
            delta.encryptedChanges.getValue("d/0/video_pos_dur/seed")!!))
    }

    @Test fun longHistoryOverOneHundredCommitsRemainsIncremental() {
        val api = FakeGit()
        val writer = store(api, "A")
        val reader = store(api, "B")
        val key = cipher()
        val initial = mapOf("d/0/video_pos_dur/movie" to "0")
        writer.initialize(key, initial)
        val baseline = reader.current(key)!!
        var revision = baseline.revision
        for (i in 1..105) {
            revision = writer.publishPartial(revision, baseline.generation, key,
                mapOf("d/0/video_pos_dur/movie" to i.toString()), emptySet())
        }
        val delta = reader.incrementalSince(baseline.revision, key, baseline.generation)!!
        assertEquals(revision, delta.revision)
        assertEquals(1, delta.encryptedChanges.size)
        assertEquals("105", key.decrypt("d/0/video_pos_dur/movie",
            delta.encryptedChanges.getValue("d/0/video_pos_dur/movie")!!))
    }


    @Test fun unchangedPartialWriteDoesNotCreateAnotherGitCommit() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val key = "d/0/video_pos_dur/movie"
        val old = a.initialize(c, mapOf(key to "100"))
        val same = a.publishPartial(old, 1, c, mapOf(key to "100"), emptySet())
        assertEquals(old, same)
        assertEquals(old, a.current(c)!!.revision)
    }

    @Test fun concurrentPartialWriteCannotPublishOverAnotherDevice() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val base = a.initialize(c, mapOf("d/0/video_pos_dur/movie" to "100"))
        val newer = a.publishPartial(base, 1, c,
            mapOf("d/0/video_pos_dur/movie" to "200"), emptySet())
        assertThrows(IOException::class.java) {
            b.publishPartial(base, 1, c,
                mapOf("p/StremioC/settings" to "new"), emptySet())
        }
        assertEquals(newer, a.current(c)!!.revision)
        assertEquals("200", b.current(c)!!.values["d/0/video_pos_dur/movie"])
        assertNull(b.current(c)!!.values["p/StremioC/settings"])
    }

    @Test fun partialDeletionWithoutExplicitProofCannotPublish() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val key = "d/0/video_pos_dur/movie"
        val old = a.initialize(c, mapOf(key to "100"))
        assertThrows(IllegalStateException::class.java) {
            a.publishPartial(old, 1, c, mapOf(key to null), emptySet())
        }
        assertEquals(old, a.current(c)!!.revision)
        assertEquals("100", a.current(c)!!.values[key])
    }

    @Test fun incrementalThreeKeysAfterInitialSnapshotAndNoChange() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val initial = (0 until 256).associate { "d/0/video_pos_dur/$it" to "start-$it" }
        a.initialize(c, initial)
        val before = b.current(c)!!
        val modifications = mapOf(
            "d/0/video_pos_dur/1" to "new-1",
            "d/0/video_pos_dur/2" to "new-2",
            "d/0/video_pos_dur/3" to "new-3"
        )
        val published = a.publishPartial(before.revision, before.generation, c,
            modifications, emptySet())
        val delta = b.incrementalSince(before.revision, c, before.generation)!!
        assertEquals(published, delta.revision)
        assertEquals(modifications.keys, delta.encryptedChanges.keys)
        modifications.forEach { (key, expected) ->
            assertEquals(expected, c.decrypt(key, delta.encryptedChanges.getValue(key)!!))
        }
        assertTrue(b.incrementalSince(published, c, before.generation)!!
            .encryptedChanges.isEmpty())
        assertEquals(initial + modifications, b.current(c)!!.values)
    }

    @Test fun incrementalDeletionsAndMultiCommitCatchup() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        a.initialize(c, mapOf("d/0/video_pos_dur/1" to "one"))
        val first = b.current(c)!!
        val second = a.publishPartial(first.revision, first.generation, c,
            mapOf("d/0/video_pos_dur/1" to null), setOf("d/0/video_pos_dur/1"))
        a.publishPartial(second, first.generation, c,
            mapOf("d/0/video_pos_dur/2" to "two"), emptySet())
        val changes = b.incrementalSince(first.revision, c, first.generation)!!
        assertTrue(changes.encryptedChanges.containsKey("d/0/video_pos_dur/1"))
        assertNull(changes.encryptedChanges["d/0/video_pos_dur/1"])
        assertEquals("two", c.decrypt("d/0/video_pos_dur/2",
            changes.encryptedChanges.getValue("d/0/video_pos_dur/2")!!))
        assertFalse(b.current(c)!!.values.containsKey("d/0/video_pos_dur/1"))
    }

    @Test fun restoredGenerationFallsBackToFullSnapshot() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val first = a.initialize(c, mapOf("d/0/video_pos_dur/1" to "one"))
        val before = a.current(c)!!
        a.publishPartial(before.revision, before.generation, c,
            mapOf("d/0/video_pos_dur/1" to "two"), emptySet())
        val newer = a.current(c)!!
        a.rollback(newer, first, c)
        assertNull(a.incrementalSince(newer.revision, c, newer.generation))
        assertEquals("one", a.current(c)!!.values["d/0/video_pos_dur/1"])
    }

    @Test fun sourceBackupAndUnlinkedBRestoreSameValues() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val key = cipher()
        val original = mapOf(
            "d/0/search_history/q" to "recent query",
            "p/StremioC/catalog" to "registered-on-A",
            "d/0/video_pos_dur/id" to "long-progress"
        )
        assertNull(b.current(key))
        val commit = a.initialize(key, original)
        assertEquals(commit, b.current(key)!!.revision)
        assertEquals(original, b.current(key)!!.values)
        assertEquals(1, b.current(key)!!.generation)
    }

    @Test fun allShardsAreVerifiedWithConcurrentReads() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val expected = (0 until 48).associate { "d/0/video_pos_dur/$it" to "position-$it" }
        a.initialize(c, expected)
        assertEquals(expected, a.current(c)!!.values)
        assertTrue("GitHub shard reads should overlap", api.peakBlobReads.get() > 1)
    }

    @Test fun failedPublicationNeverExposesPartialBackup() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val original = mapOf("d/0/search_history/one" to "saved")
        val head = a.initialize(c, original)
        val existing = a.current(c)!!
        api.failNextPublish = true
        assertThrows(IOException::class.java) {
            a.update(existing, c, original + ("d/0/search_history/two" to "new"), emptySet())
        }
        assertEquals(head, a.current(c)!!.revision)
        assertEquals(original, a.current(c)!!.values)
    }

    @Test fun concurrentDevicesCannotOverwriteNewerHead() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        a.initialize(c, mapOf("d/0/video_pos_dur/1" to "100"))
        val oldA = a.current(c)!!
        val oldB = b.current(c)!!
        a.update(oldA, c, oldA.values + ("d/0/video_pos_dur/1" to "120"), emptySet())
        assertThrows(IOException::class.java) {
            b.update(oldB, c, oldB.values + ("d/0/video_pos_dur/1" to "90"), emptySet())
        }
        assertEquals("120", b.current(c)!!.values["d/0/video_pos_dur/1"])
    }

    @Test fun verifiedDeletionAndRecoveryPreserveHistory() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val initial = mapOf("p/StremioC/catalog" to "good",
            "d/0/search_history/one" to "saved")
        val originalRevision = a.initialize(c, initial)
        val old = a.current(c)!!
        val target = initial - "d/0/search_history/one"
        assertThrows(IllegalStateException::class.java) {
            a.update(old, c, target, emptySet())
        }
        a.update(old, c, target, setOf("d/0/search_history/one"))
        assertEquals(target, a.current(c)!!.values)
        val before = a.current(c)!!
        a.rollback(before, originalRevision, c)
        val restored = a.current(c)!!
        assertEquals(initial, restored.values)
        assertEquals(before.generation + 1, restored.generation)
        assertFalse(SyncSafety.acceptGeneration(before.generation, restored.generation, true))
    }

    @Test fun eightLibraryDeletionsFromBReachAWithoutManualReview() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val original = (0 until 13).associate { i ->
            "u/11111111-1111-4111-8111-111111111111/result_watch_state_data/$i" to "item-$i"
        }
        a.initialize(c, original)
        val onB = b.current(c)!!
        val removed = original.keys.take(8).toSet()
        b.update(onB, c, original.filterKeys { it !in removed }, removed)
        val onA = a.current(c)!!
        assertEquals(5, onA.values.size)
        assertTrue(removed.none(onA.values::containsKey))
    }

    @Test fun retryAfterConcurrentGitCommitPreservesIndependentDeviceChanges() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        a.initialize(c, mapOf("d/0/video_pos_dur/movie" to "100"))
        val oldA = a.current(c)!!
        val oldB = b.current(c)!!
        a.update(oldA, c, oldA.values + ("d/0/video_pos_dur/movie" to "200"), emptySet())
        assertThrows(IOException::class.java) {
            b.update(oldB, c, oldB.values + ("d/0/result_watch_state_data/show" to "saved"), emptySet())
        }
        val latest = b.current(c)!!
        b.update(latest, c,
            latest.values + ("d/0/result_watch_state_data/show" to "saved"), emptySet())
        val final = a.current(c)!!.values
        assertEquals("200", final["d/0/video_pos_dur/movie"])
        assertEquals("saved", final["d/0/result_watch_state_data/show"])
    }

    @Test fun corruptedShardCannotBeSilentlyAppliedToDevice() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        a.initialize(c, mapOf("p/StremioC/catalog" to "a"))
        api.corruptOneDataBlob()
        assertThrows(IllegalStateException::class.java) { a.current(c) }
    }
    @Test fun reusedProfileSlotKeepsOldUuidArchiveButNeverAttachesItToNewProfile() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val uuidOld = "11111111-1111-4111-8111-111111111111"
        val uuidNew = "22222222-2222-4222-8222-222222222222"
        val uuidDefault = "33333333-3333-4333-8333-333333333333"
        fun profiles(uuid: String): String {
            val zero = JSONObject().put("keyIndex", 0).put("uuid", uuidDefault)
                .put("name", "Main")
            val one = JSONObject().put("keyIndex", 1).put("uuid", uuid)
                .put("name", "Same")
            return ProfileSync.encode(ProfileSync.State(
                linkedMapOf(0 to zero, 1 to one)))
        }
        val initial = mapOf("x/profiles" to profiles(uuidOld),
            "u/$uuidOld/video_pos_dur/example" to "old progress")
        a.initialize(c, initial)
        val bObservedBefore = b.current(c)!!
        val currentA = a.current(c)!!
        a.update(currentA, c, currentA.values + mapOf(
            "x/profiles" to profiles(uuidNew),
            "u/$uuidNew/video_pos_dur/example" to "new progress"
        ), emptySet())
        val remote = b.current(c)!!
        val merged = ProfileSync.merge(
            bObservedBefore.values.getValue("x/profiles"),
            remote.values.getValue("x/profiles"),
            bObservedBefore.values.getValue("x/profiles"))
        assertEquals(uuidNew, merged.target.items.getValue(1).getString("uuid"))
        assertEquals(setOf(uuidNew), ProfileIdentity.replacedByServer(
            ProfileSync.decode(bObservedBefore.values.getValue("x/profiles")), merged.target))
        assertNull(ProfileIdentity.nativePath("u/$uuidOld/video_pos_dur/example", merged.target))
        assertEquals("d/1/video_pos_dur/example",
            ProfileIdentity.nativePath("u/$uuidNew/video_pos_dur/example", merged.target))
        assertEquals("old progress", remote.values["u/$uuidOld/video_pos_dur/example"])
        assertEquals("new progress", remote.values["u/$uuidNew/video_pos_dur/example"])
    }

    @Test fun v3OverlayUpdatesOneKeyWithoutRewritingBaseShard() {
        val api = FakeGit()
        val store = store(api, "A")
        val c = cipher()
        val initial = (0 until 640).associate { "d/0/video_pos_dur/$it" to "old-$it" }
        val revision = store.initialize(c, initial)
        val fileIds = (0 until 16).map { api.fileId("synccs/data/" +
            it.toString(16) + ".json") }
        val edited = mapOf("d/0/video_pos_dur/3" to "new-3")
        store.publishPartial(revision, 1, c, edited, emptySet())
        assertEquals(fileIds, (0 until 16).map {
            api.fileId("synccs/data/" + it.toString(16) + ".json")
        })
        assertNotNull((0 until 16).firstNotNullOfOrNull {
            api.fileId("synccs/overlay/" + it.toString(16) + ".json")
        })
        assertEquals(initial + edited, store.current(c)!!.values)
        assertEquals(edited.keys, store.incrementalSince(revision, c, 1)!!
            .encryptedChanges.keys)
    }

    @Test fun v3OverlayCompactionAndTombstonesAreLogicalChangesOnly() {
        val api = FakeGit()
        val a = store(api, "A")
        val b = store(api, "B")
        val c = cipher()
        val initial = linkedMapOf<String, String>()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        var n = 0
        while (initial.size < 145) {
            val key = "d/0/video_pos_dur/compact-${n++}"
            if ((digest.digest(key.toByteArray(Charsets.UTF_8))[0].toInt() and 255) ushr 4 == 0) {
                initial[key] = "original"
            }
        }
        val initialRevision = a.initialize(c, initial)
        val modifications = initial.keys.take(130).associateWith { "updated" }
        val newRevision = a.publishPartial(initialRevision, 1, c, modifications, emptySet())
        assertEquals("{}", api.blobContents("synccs/overlay/0.json"))
        assertEquals(initial + modifications, b.current(c)!!.values)
        assertEquals(modifications.keys,
            b.incrementalSince(initialRevision, c, 1)!!.encryptedChanges.keys)
        val removed = initial.keys.first()
        a.publishPartial(newRevision, 1, c, mapOf(removed to null), setOf(removed))
        assertFalse(b.current(c)!!.values.containsKey(removed))
        assertNull(b.incrementalSince(initialRevision, c, 1)!!.encryptedChanges[removed])
    }

    @Test fun v3CorruptedOverlayFailsClosed() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val initial = a.initialize(c, mapOf("d/0/video_pos_dur/1" to "one"))
        a.publishPartial(initial, 1, c, mapOf("d/0/video_pos_dur/1" to "two"), emptySet())
        val path = (0 until 16).map { "synccs/overlay/" + it.toString(16) + ".json" }
            .first { api.fileId(it) != null }
        api.corruptFile(path)
        assertThrows(IllegalStateException::class.java) { a.current(c) }
    }

    @Test fun v3RollbackMasksOverlayMissingAtTargetRevision() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        val first = a.initialize(c, mapOf("d/0/video_pos_dur/1" to "one"))
        a.publishPartial(first, 1, c, mapOf("d/0/video_pos_dur/1" to "two"), emptySet())
        val newer = a.current(c)!!
        a.rollback(newer, first, c)
        assertEquals("one", a.current(c)!!.values["d/0/video_pos_dur/1"])
        assertEquals(newer.generation + 1, a.current(c)!!.generation)
    }

    @Test fun legacyV2RequiresExplicitFreshSourceInsteadOfMigration() {
        val api = FakeGit()
        val a = store(api, "A")
        val c = cipher()
        api.installLegacyV2Manifest()
        assertThrows(GithubBackupStore.OldFormat::class.java) { a.current(c) }
        assertThrows(IllegalStateException::class.java) {
            a.initialize(c, mapOf("d/0/search_history/test" to "new"))
        }
        a.initialize(c, mapOf("d/0/search_history/test" to "new"), replaceLegacy = true)
        assertEquals(mapOf("d/0/search_history/test" to "new"), a.current(c)!!.values)
    }

    @Test fun streamedTreeParserRetainsOnlySyncCSBlobPaths() {
        val store = store(FakeGit(), "A")
        val tree = JSONObject().put("truncated", false).put("tree", JSONArray()
            .put(JSONObject().put("path", "unrelated/large-file.bin")
                .put("sha", "a".repeat(40)).put("type", "blob"))
            .put(JSONObject().put("path", "synccs/data/0.json")
                .put("sha", "b".repeat(40)).put("type", "blob"))
            .put(JSONObject().put("path", "synccs/other-folder")
                .put("sha", "c".repeat(40)).put("type", "tree")))
        val parsed = store.parseTree(java.io.ByteArrayInputStream(
            tree.toString().toByteArray(Charsets.UTF_8)))
        assertFalse(parsed.getBoolean("truncated"))
        assertEquals(1, parsed.getJSONArray("tree").length())
        assertEquals("synccs/data/0.json",
            parsed.getJSONArray("tree").getJSONObject(0).getString("path"))
    }

    @Test fun streamedBlobBodyIsIdenticalToExistingGithubBase64Json() {
        val store = store(FakeGit(), "A")
        for (bytes in listOf(byteArrayOf(), byteArrayOf(0), byteArrayOf(0, 1),
            byteArrayOf(0, 1, 2), byteArrayOf(1, 2, 3, 4, -1),
            ByteArray(131_071) { ((it * 31) and 255).toByte() })) {
            val output = java.io.ByteArrayOutputStream()
            store.writeBlobBody(bytes, output)
            val expected = JSONObject()
                .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
                .put("encoding", "base64")
            val actual = JSONObject(String(output.toByteArray(), Charsets.UTF_8))
            assertEquals(expected.getString("content"), actual.getString("content"))
            assertEquals(expected.getString("encoding"), actual.getString("encoding"))
            assertEquals(expected.toString().toByteArray(Charsets.UTF_8).size,
                output.toByteArray().size)
        }
    }

}
