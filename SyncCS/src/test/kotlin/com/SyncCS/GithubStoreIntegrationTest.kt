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
        assertThrows(IllegalStateException::class.java) {
            b.update(oldB, c, oldB.values + ("d/0/video_pos_dur/1" to "90"), emptySet())
        }
        assertEquals("120", b.current(c)!!.values["d/0/video_pos_dur/1"])
    }

    @Test fun humanAuthorizedDeletionAndRecoveryPreserveHistory() {
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

}
