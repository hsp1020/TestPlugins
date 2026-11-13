package com.SyncCS

import android.util.Base64
import android.util.Base64OutputStream
import android.util.JsonReader
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

/** A rejected request is replayed at most once with a renewed credential. */
internal object GithubAuthRetry {
    fun <T> once(
        token: String, recover: ((String) -> String?)?, request: (String) -> T
    ): T {
        try {
            return request(token)
        } catch (failure: GithubBackupStore.ApiException) {
            if (failure.code != 401 || recover == null) throw failure
            val replacement = recover(token)
            if (replacement.isNullOrBlank() || replacement == token) {

                throw failure
            }

            return try {
                request(replacement)
            } catch (second: GithubBackupStore.ApiException) {
                throw second
            }
        }
    }
}

/**
 * Standalone GitHub storage engine. It cannot touch the Supabase account.
 * A backup is visible only after the complete set of encrypted shards and a
 * valid manifest have been committed to a private repository.
 */
internal class GithubBackupStore(
    private val token: String,
    private val owner: String,
    private val repository: String = "cloudstream-synccs-backup",
    private val deviceName: String = "Unspecified device",
    private val deviceId: String = "unknown",
    // Allows deterministic, offline Git object/CAS integration tests.
    private val transport: ((String, String, JSONObject?) -> JSONObject)? = null,
    private val recoverUnauthorized: ((String) -> String?)? = null
) {
    data class Snapshot(
        val revision: String, val tree: String, val branch: String,
        val generation: Int, val values: Map<String, String>, val encrypted: Map<String, String>,
        val shardHashes: List<String>,
        val overlayHashes: List<String> = List(16) { "" }
    )
    private val apiRoot = "https://api.github.com/repos/$owner/$repository"
    internal class ApiException(val code: Int, text: String) : IOException(text)
    /** No v2-to-v3 migration: user must explicitly recreate the source backup. */
    internal class OldFormat : IOException("GitHub SyncCS v2 backup requires explicit v3 reset from the source device.")

    @Volatile private var activeToken: String = token

    private fun <T> authorized(request: (String) -> T): T =
        GithubAuthRetry.once(activeToken, recoverUnauthorized?.let { recover ->
            { rejected ->
                recover(rejected)?.also { renewed ->
                    if (renewed != rejected) activeToken = renewed
                }
            }
        }, request)

    /** Parse large Git tree responses without retaining the unrelated file tree. */
    internal fun parseTree(input: InputStream): JSONObject {
        val files = JSONArray()
        var truncated = false
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "truncated" -> truncated = reader.nextBoolean()
                    "tree" -> {
                        reader.beginArray()
                        while (reader.hasNext()) {
                            var path = ""
                            var sha = ""
                            var kind = ""
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "path" -> path = reader.nextString()
                                    "sha" -> sha = reader.nextString()
                                    "type" -> kind = reader.nextString()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                            if (path.startsWith("synccs/") && kind == "blob") {
                                files.put(JSONObject().put("path", path)
                                    .put("sha", sha).put("type", kind))
                            }
                        }
                        reader.endArray()
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        return JSONObject().put("truncated", truncated).put("tree", files)
    }

    private fun api(url: String, method: String = "GET", json: JSONObject? = null): JSONObject {
        require(url.startsWith("https://api.github.com/"))
        return authorized { currentToken ->
            transport?.let { return@authorized it(url, method, json) }
            val traceStarted = android.os.SystemClock.elapsedRealtime()
            var responseStatus = -1
            var responseSize = -1
            var requestSize = 0
            var parseMs = 0L
            var phase = "connect"
            val apiCategory = when {
                url.contains("/git/blobs/") -> "blob"
                url.contains("/git/trees/") -> "tree"
                url.contains("/git/commits/") -> "commit"
                url.contains("/git/ref/") || url.contains("/git/refs") -> "ref"
                url.contains("/compare/") -> "compare"
                url == apiRoot -> "repo"
                url.endsWith("/git/blobs") -> "blob-create"
                url.endsWith("/git/trees") -> "tree-create"
                url.endsWith("/git/commits") -> "commit-create"
                else -> "other"
            }
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = method
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.setRequestProperty("Authorization", "Bearer $currentToken")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "SyncCS-Backup")
            try {
                if (json != null) {
                    phase = "write"
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    val body = json.toString()
                    // Request body is written through OutputStreamWriter.
                    // Avoid allocating a second UTF-8 array solely for logging.
                    requestSize = -1
                    OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
                }
                phase = "response-code"
                val status = conn.responseCode
                responseStatus = status
                responseSize = conn.contentLength
                if (status !in 200..299) {
                    val stage = when {
                        url == apiRoot -> "repository-access"
                        url.contains("/git/ref/heads/") -> "remote-head"
                        url.contains("/git/trees/") -> "tree-read"
                        url.contains("/git/blobs/") -> "blob-read"
                        url.endsWith("/user/repos") -> "repository-create"
                        url.contains("/git/refs") -> "ref-write"
                        else -> "backup-api"
                    }
                    throw ApiException(status, "GitHub API HTTP $status (stage=$stage)")
                }
                phase = "parse"
                val parseStarted = android.os.SystemClock.elapsedRealtime()
                val result = conn.inputStream.use { input ->
                    if (url.contains("/git/trees/") && url.contains("recursive=1"))
                        parseTree(input)
                    else JSONObject(InputStreamReader(input, Charsets.UTF_8).use { it.readText() })
                }
                parseMs = android.os.SystemClock.elapsedRealtime() - parseStarted
                phase = "completed"
                result
            } finally {
                conn.disconnect()
                android.util.Log.i("SyncCSAlloc", "GITHUB_API route=" + apiCategory +
                    " method=" + method + " status=" + responseStatus +
                    " requestBytes=" + requestSize + " responseBytes=" + responseSize +
                    " phase=" + phase + " parseMs=" + parseMs +
                    " totalMs=" + (android.os.SystemClock.elapsedRealtime() - traceStarted))
            }
        }
    }

    private fun get(path: String): JSONObject = api(apiRoot + path)
    private fun write(path: String, method: String, body: JSONObject): JSONObject =
        api(apiRoot + path, method, body)

    fun ensurePrivateRepository(): String {
        val details = try {
            get("")
        } catch (e: ApiException) {
            if (e.code != 404) throw e
            api("https://api.github.com/user/repos", "POST", JSONObject()
                .put("name", repository).put("private", true).put("auto_init", true)
                .put("description", "Encrypted CloudStream synchronization"))
        }
        check(details.optBoolean("private") &&
            details.optJSONObject("owner")?.optString("login")?.equals(owner, true) == true) {
            "SyncCS requires a private repository owned by the authenticated account."
        }
        val branch = details.getString("default_branch")
        check(branch.matches(Regex("[A-Za-z0-9._/-]{1,100}")) &&
            !branch.contains("..") && !branch.startsWith("/"))
        return branch
    }

    private fun branchEndpoint(branch: String): String = "/git/refs/heads/" +
        branch.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8") }

    private fun revision(branch: String): String =
        get("/git/ref/heads/" + branch.split('/').joinToString("/") {
            URLEncoder.encode(it, "UTF-8")
        }).getJSONObject("object").getString("sha")

    private fun index(revision: String): Pair<String, Map<String, String>> {
        check(revision.matches(Regex("[0-9a-f]{40}")))
        val tree = get("/git/commits/$revision").getJSONObject("tree").getString("sha")
        val listing = get("/git/trees/$tree?recursive=1")
        check(!listing.optBoolean("truncated")) { "GitHub backup tree was truncated." }
        val result = linkedMapOf<String, String>()
        val entries = listing.getJSONArray("tree")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.optString("type") == "blob") result[e.getString("path")] = e.getString("sha")
        }
        return tree to result
    }

    /**
     * GitHub raw Blob representation avoids a second Base64 JSON object.
     * The injectable test transport retains the JSON form for offline tests.
     */
    private fun blob(sha: String): ByteArray {
        check(sha.matches(Regex("[0-9a-f]{40}")))
        if (transport != null) {
            val result = get("/git/blobs/$sha")
            check(result.getString("encoding") == "base64")
            return Base64.decode(result.getString("content"), Base64.DEFAULT)
        }
        return authorized { currentToken ->
            val conn = URL("$apiRoot/git/blobs/$sha").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Authorization", "Bearer $currentToken")
            conn.setRequestProperty("Accept", "application/vnd.github.raw+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "SyncCS-Backup")
            try {
                val status = conn.responseCode
                if (status !in 200..299) throw ApiException(status,
                    "GitHub raw Blob HTTP $status (stage=blob-read)")
                val bytes = conn.inputStream.use { input ->
                    val length = if (conn.contentEncoding.isNullOrBlank() ||
                        conn.contentEncoding.equals("identity", ignoreCase = true))
                        conn.contentLengthLong else -1L
                    readBlobResponse(input, length)
                }
                // Gracefully handle providers that still return the JSON
                // representation despite a raw Accept header.
                if (conn.contentType?.contains("json", true) == true &&
                    bytes.firstOrNull()?.toInt() == '{'.code) {
                    val envelope = JSONObject(String(bytes, Charsets.UTF_8))
                    if (envelope.optString("encoding") == "base64")
                        Base64.decode(envelope.getString("content"), Base64.DEFAULT)
                    else bytes
                } else bytes
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Read a known-length raw Blob into one array; preserve streaming fallback. */
    private fun readBlobResponse(input: InputStream, contentLength: Long): ByteArray {
        if (contentLength <= 0L || contentLength > 32L * 1024 * 1024)
            return input.readBytes()
        val bytes = ByteArray(contentLength.toInt())
        var offset = 0
        while (offset < bytes.size) {
            val read = input.read(bytes, offset, bytes.size - offset)
            if (read < 0) throw IOException("Truncated GitHub Blob response")
            if (read == 0) {
                val single = input.read()
                if (single < 0) throw IOException("Truncated GitHub Blob response")
                bytes[offset++] = single.toByte()
            } else offset += read
        }
        val extra = input.read()
        if (extra < 0) return bytes
        val expanded = ByteArrayOutputStream(bytes.size + 1)
        expanded.write(bytes)
        expanded.write(extra)
        input.copyTo(expanded)
        return expanded.toByteArray()
    }

    private val blobPrefix = "{\"content\":\"".toByteArray(Charsets.US_ASCII)
    private val blobSuffix = "\",\"encoding\":\"base64\"}".toByteArray(Charsets.US_ASCII)

    internal fun writeBlobBody(bytes: ByteArray, output: java.io.OutputStream) {
        output.write(blobPrefix)
        // NO_CLOSE permits writing the closing JSON suffix after Base64 finalization.
        val base64 = Base64OutputStream(output, Base64.NO_WRAP or Base64.NO_CLOSE)
        base64.write(bytes)
        base64.close()
        output.write(blobSuffix)
    }

    private fun createBlob(bytes: ByteArray): String {
        if (transport != null) return write("/git/blobs", "POST", JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")).getString("sha")
        return authorized { currentToken ->
            val conn = URL("$apiRoot/git/blobs").openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 12000
            conn.readTimeout = 25000
            conn.setRequestProperty("Authorization", "Bearer $currentToken")
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            conn.setRequestProperty("User-Agent", "SyncCS-Backup")
            conn.setRequestProperty("Content-Type", "application/json")
            val encodedSize = ((bytes.size.toLong() + 2L) / 3L) * 4L
            conn.setFixedLengthStreamingMode(blobPrefix.size.toLong() +
                encodedSize + blobSuffix.size)
            try {
                conn.outputStream.use { output -> writeBlobBody(bytes, output) }
                val status = conn.responseCode
                if (status !in 200..299)
                    throw ApiException(status, "GitHub Blob upload HTTP $status")
                JSONObject(InputStreamReader(conn.inputStream, Charsets.UTF_8).use {
                    it.readText()
                }).getString("sha")
            } finally {
                conn.disconnect()
            }
        }
    }

    private class BucketHashState {
        val digest = MessageDigest.getInstance("SHA-256")
        val output = ByteArray(32)
    }
    private companion object {
        val bucketHashState = object : ThreadLocal<BucketHashState>() {
            override fun initialValue() = BucketHashState()
        }
    }
    private fun bucket(key: String): Int {
        val state = bucketHashState.get()
        state.digest.update(key.toByteArray(Charsets.UTF_8))
        state.digest.digest(state.output, 0, state.output.size)
        return (state.output[0].toInt() and 255) ushr 4
    }
    private fun shard(index: Int): String = "synccs/data/" + index.toString(16) + ".json"

    private fun overlayPath(index: Int): String =
        "synccs/overlay/" + index.toString(16) + ".json"

    private data class Root(val generation: Int, val hashes: List<String>,
                            val overlays: List<String>)

    private fun sealedManifest(
        cipher: SyncCipher, generation: Int, shardHashes: List<String>,
        overlayHashes: List<String>, deltaHash: String? = null
    ): ByteArray {
        check(shardHashes.size == 16 && shardHashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        check(overlayHashes.size == 16 && overlayHashes.all {
            it.isEmpty() || it.matches(Regex("[0-9a-f]{64}"))
        })
        val sealText = shardHashes.joinToString("|") + ":" + overlayHashes.joinToString("|")
        val manifest = JSONObject().put("format", 3).put("complete", true)
            .put("generation", generation)
            .put("verification", cipher.encrypt("synccs/check", "synccs-github-v1"))
            .put("shards", JSONArray(shardHashes))
            .put("overlays", JSONArray(overlayHashes))
            .put("rootSeal", cipher.encrypt("synccs/data-root-v3", sealText))
        if (deltaHash != null) {
            manifest.put("deltaHash", deltaHash)
                .put("deltaSeal", cipher.encrypt("synccs/delta-root-v1", deltaHash))
        }
        return manifest.toString().toByteArray(Charsets.UTF_8)
    }

    private fun verifiedRoot(manifest: JSONObject, cipher: SyncCipher): Root {
        val format = manifest.optInt("format")
        if (format == 2) throw OldFormat()
        check(format == 3) { "Unsupported or damaged SyncCS backup format" }
        check(manifest.getBoolean("complete")) { "Incomplete authenticated v3 backup" }
        check(cipher.decrypt("synccs/check", manifest.getString("verification"))
            == "synccs-github-v1") { "Invalid backup passphrase" }
        val hashes = manifest.getJSONArray("shards").let { a ->
            check(a.length() == 16)
            (0 until 16).map(a::getString)
        }
        val overlays = manifest.getJSONArray("overlays").let { a ->
            check(a.length() == 16)
            (0 until 16).map(a::getString)
        }
        check(hashes.all { it.matches(Regex("[0-9a-f]{64}")) } &&
            overlays.all { it.isEmpty() || it.matches(Regex("[0-9a-f]{64}")) })
        val sealed = hashes.joinToString("|") + ":" + overlays.joinToString("|")
        check(cipher.decrypt("synccs/data-root-v3", manifest.getString("rootSeal")) == sealed) {
            "GitHub v3 authenticated root mismatch"
        }
        return Root(manifest.getInt("generation"), hashes, overlays)
    }

    private fun rootAt(files: Map<String, String>, cipher: SyncCipher): Root {
        val sha = files["synccs/manifest.json"] ?: error("Missing v3 backup root")
        return verifiedRoot(JSONObject(String(blob(sha), Charsets.UTF_8)), cipher)
    }

    private fun checkedRows(
        files: Map<String, String>, path: String, expected: String, index: Int
    ): JSONObject {
        val sha = files[path] ?: error("Missing verified GitHub data $path")
        val bytes = blob(sha)
        check(SyncBackend.digestBytes(bytes) == expected) {
            "GitHub v3 shard/overlay digest mismatch: $path"
        }
        val rows = JSONObject(String(bytes, Charsets.UTF_8))
        for (key in rows.keys()) check(bucket(key) == index) {
            "Invalid GitHub v3 bucket assignment"
        }
        return rows
    }

    /** Resolve one logical bucket; tombstones mask base values. */
    private fun mergedBucket(
        files: Map<String, String>, root: Root, index: Int,
        onVerifiedOverlay: ((JSONObject) -> Unit)? = null
    ): LinkedHashMap<String, String> {
        val base = checkedRows(files, shard(index), root.hashes[index], index)
        val result = linkedMapOf<String, String>()
        for (key in base.keys()) {
            check(!base.isNull(key)) { "Null base ciphertext" }
            result[key] = base.getString(key)
        }
        val overlayHash = root.overlays[index]
        if (overlayHash.isNotEmpty()) {
            val overlay = checkedRows(files, overlayPath(index), overlayHash, index)
            onVerifiedOverlay?.invoke(overlay)
            for (key in overlay.keys()) {
                if (overlay.isNull(key)) result.remove(key)
                else result[key] = overlay.getString(key)
            }
        }
        return result
    }

    private fun readAt(rev: String, branch: String, cipher: SyncCipher): Snapshot? {
        val (tree, entries) = index(rev)
        val manifestSha = entries["synccs/manifest.json"] ?: run {
            check(entries.keys.none { it.startsWith("synccs/") }) {
                "Existing partial backup must be recovered manually."
            }
            return null
        }
        val root = rootAt(entries, cipher)
        val workers = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "SyncCS-GitHub-v3-shard").apply { isDaemon = true }
        }
        val jobs = (0 until 16).map { i ->
            workers.submit(Callable {
                val ciphertext = mergedBucket(entries, root, i)
                val plaintext = linkedMapOf<String, String>()
                for ((key, encrypted) in ciphertext)
                    plaintext[key] = cipher.decrypt(key, encrypted)
                ciphertext to plaintext
            })
        }
        val encrypted = linkedMapOf<String, String>()
        val values = linkedMapOf<String, String>()
        try {
            for (job in jobs) {
                val (sealed, plain) = try {
                    job.get()
                } catch (failure: ExecutionException) {
                    throw (failure.cause ?: failure)
                }
                for ((key, ciphertext) in sealed) {
                    check(encrypted.put(key, ciphertext) == null) { "Duplicate backup key" }
                }
                values.putAll(plain)
            }
        } finally {
            jobs.forEach { if (!it.isDone) it.cancel(true) }
            workers.shutdownNow()
        }
        return Snapshot(rev, tree, branch, root.generation, values,
            encrypted, root.hashes, root.overlays)
    }


    /** Encrypted per-commit changes; old GitHub revisions remain full-snapshot compatible. */
    data class Incremental(
        val revision: String, val generation: Int, val encryptedChanges: Map<String, String?>
    )

    private fun changeJournal(cipher: SyncCipher, entries: Map<String, String?>): ByteArray {
        val rows = JSONObject()
        for ((key, value) in entries) {
            rows.put(key, if (value == null) JSONObject.NULL else cipher.encrypt(key, value))
        }
        return cipher.encrypt("synccs/delta-journal-v1",
            JSONObject().put("format", 1).put("entries", rows).toString())
            .toByteArray(Charsets.UTF_8)
    }

    /**
     * A/B comparison uses authenticated logical buckets at two Git revisions.
     * Compaction may change ciphertext layout, but never creates phantom
     * additions/deletions. No intervening history replay is necessary.
     */
    fun incrementalSince(base: String, cipher: SyncCipher, generation: Int): Incremental? {
        if (!base.matches(Regex("[0-9a-f]{40}")) || generation <= 0) return null
        val branch = ensurePrivateRepository()
        val head = revision(branch)
        if (head == base) return Incremental(head, generation, emptyMap())
        val comparison = get("/compare/$base...$head?per_page=1")
        if (comparison.optString("status") != "ahead") return null
        val (_, beforeFiles) = index(base)
        val (_, afterFiles) = index(head)
        val beforeRoot = rootAt(beforeFiles, cipher)
        val afterRoot = rootAt(afterFiles, cipher)
        if (beforeRoot.generation != generation || afterRoot.generation != generation)
            return null
        val result = linkedMapOf<String, String?>()
        for (bucket in 0 until 16) {
            if (beforeRoot.hashes[bucket] == afterRoot.hashes[bucket] &&
                beforeRoot.overlays[bucket] == afterRoot.overlays[bucket]) continue
            val previous = mergedBucket(beforeFiles, beforeRoot, bucket)
            val current = mergedBucket(afterFiles, afterRoot, bucket)
            for (key in previous.keys + current.keys) {
                val oldCipher = previous[key]
                val newCipher = current[key]
                if (oldCipher != newCipher) {
                    // Re-encryption alone is not a logical edit.
                    val oldValue = oldCipher?.let { cipher.decrypt(key, it) }
                    val newValue = newCipher?.let { cipher.decrypt(key, it) }
                    if (oldValue != newValue) result[key] = newCipher
                }
            }
        }
        SyncSafety.validateChanges(result.size)
        return Incremental(head, generation, result)
    }

    /**
     * Publish only a small v3 overlay. If a bucket's overlay grows too large,
     * compact that bucket in the same CAS commit as the new manifest.
     */
    fun publishPartial(
        expectedRevision: String, expectedGeneration: Int, cipher: SyncCipher,
        requested: Map<String, String?>, approvedDeletes: Set<String>,
        archives: Map<String, ByteArray> = emptyMap()
    ): String {
        val branch = ensurePrivateRepository()
        if (revision(branch) != expectedRevision)
            throw IOException("Sync conflict: remote revision changed.")
        val (tree, existing) = index(expectedRevision)
        val root = rootAt(existing, cipher)
        check(root.generation == expectedGeneration) {
            "Backup recovery generation changed; re-link before uploading"
        }
        SyncSafety.validateChanges(requested.size)
        val started = android.os.SystemClock.elapsedRealtime()
        val hashes = root.hashes.toMutableList()
        val overlays = root.overlays.toMutableList()
        val patches = linkedMapOf<String, ByteArray>()
        val changed = linkedMapOf<String, String?>()
        val nestedDeletes = linkedSetOf<String>()
        var writeBytes = 0L
        var changedShards = 0
        var compactedShards = 0
        val touched = requested.keys.map(::bucket).distinct()
        for (index in touched) {
            var verifiedOverlay: JSONObject? = null
            val visible = mergedBucket(existing, root, index) { verifiedOverlay = it }
            val delta = verifiedOverlay ?: JSONObject()
            var altered = false
            for ((key, raw) in requested) {
                if (bucket(key) != index) continue
                val previous = visible[key]?.let { cipher.decrypt(key, it) }
                if (previous == raw) continue
                if (key == "x/plugins") {
                    val before = PluginSync.decode(previous)
                    val after = PluginSync.decode(raw)
                    nestedDeletes.addAll((before.items.keys - after.items.keys)
                        .map { "x/plugins/$it" })
                } else if (key == "x/profiles") {
                    val before = ProfileSync.decode(previous)
                    val after = ProfileSync.decode(raw)
                    nestedDeletes.addAll((before.items.keys - after.items.keys)
                        .map { "x/profiles/$it" })
                }
                if (raw == null) {
                    visible.remove(key)
                    delta.put(key, JSONObject.NULL)
                } else {
                    val encrypted = cipher.encrypt(key, raw)
                    visible[key] = encrypted
                    delta.put(key, encrypted)
                }
                changed[key] = raw
                altered = true
            }
            if (!altered) continue
            val serialized = delta.toString().toByteArray(Charsets.UTF_8)
            // Bounding both entries and bytes avoids accumulating unbounded
            // encrypted changes while retaining small normal deltas.
            if (delta.length() > 128 || serialized.size > 64 * 1024) {
                val full = JSONObject()
                for ((key, ciphertext) in visible) full.put(key, ciphertext)
                val baseBytes = full.toString().toByteArray(Charsets.UTF_8)
                val emptyOverlay = "{}".toByteArray(Charsets.UTF_8)
                patches[shard(index)] = baseBytes
                patches[overlayPath(index)] = emptyOverlay
                hashes[index] = SyncBackend.digestBytes(baseBytes)
                overlays[index] = SyncBackend.digestBytes(emptyOverlay)
                writeBytes += baseBytes.size + emptyOverlay.size
                compactedShards++
            } else {
                patches[overlayPath(index)] = serialized
                overlays[index] = SyncBackend.digestBytes(serialized)
                writeBytes += serialized.size
            }
            changedShards++
        }
        val deletes = changed.filterValues { it == null }.keys + nestedDeletes
        check(deletes.all { it in approvedDeletes }) {
            "Unverified deletion detected; refusing to publish."
        }
        SyncSafety.validateDeletes(deletes, approvedDeletes, 0)
        val newArchives = archiveChanges(existing, archives)
        android.util.Log.i("SyncCSAlloc", "V3_OVERLAY requestedKeys=" + requested.size +
            " modifiedKeys=" + changed.size + " touchedShards=" + touched.size +
            " changedShards=" + changedShards + " compacted=" + compactedShards +
            " writeBytes=" + writeBytes + " totalMs=" +
            (android.os.SystemClock.elapsedRealtime() - started))
        if (patches.isEmpty() && newArchives.isEmpty()) return expectedRevision
        patches.putAll(newArchives)
        val journal = if (changed.size <= 1000) changeJournal(cipher, changed) else null
        if (journal != null) patches["synccs/delta.enc"] = journal
        patches["synccs/manifest.json"] = sealedManifest(cipher, root.generation,
            hashes, overlays, journal?.let(SyncBackend::digestBytes))
        patches["synccs/audit.enc"] = auditBlob(existing, cipher, changed.keys.toList(), deletes)
        return commit(expectedRevision, tree, branch, patches)
    }

    /** Cheap Git HEAD check, without fetching or decrypting any backup shard. */
    fun remoteHead(): String {
        val branch = ensurePrivateRepository()
        return revision(branch)
    }

    fun current(cipher: SyncCipher): Snapshot? {
        val branch = ensurePrivateRepository()
        return readAt(revision(branch), branch, cipher)
    }

    fun previous(revision: String, branch: String, cipher: SyncCipher): Snapshot? =
        readAt(revision, branch, cipher)

    data class Revision(val id: String, val summary: String, val timestamp: String)
    private val manualRefPrefix = "refs/tags/synccs/manual/"
    private val manualName = Regex("""\d{8}T\d{6}Z-[0-9a-f]{12}""")

    internal fun parseManualBackups(rows: JSONArray): List<Revision> {
        val formatter = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
            .apply {
                timeZone = java.util.TimeZone.getTimeZone("UTC")
                isLenient = false
            }
        val display = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss 'UTC'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
        return (0 until rows.length()).mapNotNull { index ->
            val row = rows.getJSONObject(index)
            val ref = row.optString("ref")
            if (!ref.startsWith(manualRefPrefix)) return@mapNotNull null
            val name = ref.removePrefix(manualRefPrefix)
            if (!manualName.matches(name)) return@mapNotNull null
            val objectInfo = row.optJSONObject("object") ?: return@mapNotNull null
            if (objectInfo.optString("type") != "commit") return@mapNotNull null
            val id = objectInfo.optString("sha")
            if (!id.matches(Regex("[0-9a-f]{40}"))) return@mapNotNull null
            val timestamp = runCatching { formatter.parse(name.substringBefore('-')) }.getOrNull()
                ?: return@mapNotNull null
            Revision(id, "SyncCS user backup", display.format(timestamp))
        }.sortedByDescending { it.timestamp }
    }

    fun manualBackups(): List<Revision> = authorized { currentToken ->
        val url = apiRoot + "/git/matching-refs/tags/synccs/manual/"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer $currentToken")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SyncCS-Backup")
        try {
            val status = conn.responseCode
            if (status == 404) return@authorized emptyList()
            if (status != 200) {
                throw ApiException(status, "Cannot load GitHub user backups (HTTP $status).")
            }
            parseManualBackups(JSONArray(String(conn.inputStream.use {
                it.readBytes()
            }, Charsets.UTF_8)))
        } finally { conn.disconnect() }
    }

    fun createManualBackup(snapshot: Snapshot): Revision {
        require(snapshot.revision.matches(Regex("[0-9a-f]{40}")))
        check(revision(snapshot.branch) == snapshot.revision) {
            "GitHub backup changed after syncing. Sync again before creating a user backup."
        }
        val date = java.text.SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
            .format(java.util.Date())
        val suffix = java.util.UUID.randomUUID().toString().replace("-", "").take(12)
        val ref = manualRefPrefix + date + "-" + suffix
        write("/git/refs", "POST", JSONObject()
            .put("ref", ref).put("sha", snapshot.revision))

        return Revision(snapshot.revision, "SyncCS user backup",
            date.substring(0, 4) + "-" + date.substring(4, 6) + "-" + date.substring(6, 8) +
                " " + date.substring(9, 11) + ":" + date.substring(11, 13) +
                ":" + date.substring(13, 15) + " UTC")
    }

    /** Read-only history for explicit user-directed rollback. */
    fun history(branch: String, limit: Int = 20): List<Revision> {
        require(limit in 1..50)
        return historyArray(branch, limit)
    }

    private fun historyArray(branch: String, limit: Int): List<Revision> = authorized { currentToken ->
        val url = "https://api.github.com/repos/$owner/$repository/commits?sha=" +
            URLEncoder.encode(branch, "UTF-8") + "&per_page=$limit"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer $currentToken")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SyncCS-Backup")
        try {
            val status = conn.responseCode
            if (status != 200) {
                throw ApiException(status, "Cannot load GitHub backup revisions (HTTP $status).")
            }
            val rows = JSONArray(String(conn.inputStream.use { it.readBytes() }, Charsets.UTF_8))
            (0 until rows.length()).map { i ->
                val entry = rows.getJSONObject(i)
                val commit = entry.getJSONObject("commit")
                Revision(entry.getString("sha"), commit.optString("message").lineSequence().first(),
                    commit.optJSONObject("committer")?.optString("date").orEmpty())
            }
        } finally { conn.disconnect() }
    }


    private fun nestedDeletions(previous: Map<String, String>,
                                target: Map<String, String>): Set<String> {
        val removed = linkedSetOf<String>()
        if (previous["x/plugins"] != target["x/plugins"]) {
            val before = PluginSync.decode(previous["x/plugins"])
            val after = PluginSync.decode(target["x/plugins"])
            for (id in before.items.keys - after.items.keys) removed.add("x/plugins/$id")
        }
        if (previous["x/profiles"] != target["x/profiles"]) {
            val before = ProfileSync.decode(previous["x/profiles"])
            val after = ProfileSync.decode(target["x/profiles"])
            for (id in before.items.keys - after.items.keys) removed.add("x/profiles/$id")
        }
        return removed
    }

    private fun auditBlob(existing: Map<String, String>, cipher: SyncCipher,
                          changed: List<String>, deleted: Set<String>): ByteArray {
        val previous = existing["synccs/audit.enc"]?.let { hash ->
            JSONObject(cipher.decrypt("synccs/audit",
                String(blob(hash), Charsets.UTF_8)))
        } ?: JSONObject()
        val now = System.currentTimeMillis()
        val cutoff = now - 7L * 24L * 60L * 60L * 1000L
        val oldDeletions = previous.optJSONArray("deletedLast7Days") ?: JSONArray()
        val recentDeletions = JSONArray()
        var alreadyDeleted = 0
        for (i in 0 until oldDeletions.length()) {
            val entry = oldDeletions.getJSONObject(i)
            if (entry.optLong("time") >= cutoff) {
                recentDeletions.put(entry)
                alreadyDeleted += entry.optInt("count")
            }
        }
        SyncSafety.validateDeleteBudget(deleted.size, alreadyDeleted)
        if (deleted.isNotEmpty()) recentDeletions.put(JSONObject()
            .put("time", now).put("count", deleted.size)
            .put("deviceId", deviceId).put("deviceName", deviceName)
            .put("keys", JSONArray(deleted.sorted())))
        val events = previous.optJSONArray("recentEvents") ?: JSONArray()
        val nextEvents = JSONArray()
        for (i in maxOf(0, events.length() - 29) until events.length()) {
            nextEvents.put(events.getJSONObject(i))
        }
        nextEvents.put(JSONObject().put("time", now)
            .put("deviceId", deviceId).put("deviceName", deviceName)
            .put("changed", changed.size).put("deleted", deleted.size)
            .put("keysSample", JSONArray(changed.take(50))))
        val text = JSONObject().put("format", 1)
            .put("deletedLast7Days", recentDeletions)
            .put("recentEvents", nextEvents).toString()
        return cipher.encrypt("synccs/audit", text).toByteArray(Charsets.UTF_8)
    }

    private fun archivePath(hash: String): String {
        require(hash.matches(Regex("[0-9a-f]{64}")))
        return "synccs/plugins/$hash.cs3"
    }

    fun readArchive(snapshot: Snapshot, hash: String): ByteArray {
        val (_, files) = index(snapshot.revision)
        val entry = files[archivePath(hash)] ?: throw IOException("Plugin archive unavailable: $hash")
        val bytes = blob(entry)
        val actual = SyncBackend.digestBytes(bytes)
        check(actual == hash) { "Plugin archive hash mismatch" }
        return bytes
    }

    private fun archiveChanges(
        existing: Map<String, String>, archives: Map<String, ByteArray>
    ): Map<String, ByteArray> {
        val changes = linkedMapOf<String, ByteArray>()
        for ((hash, bytes) in archives) {
            val path = archivePath(hash)
            val actual = SyncBackend.digestBytes(bytes)
            check(actual == hash && bytes.isNotEmpty() && bytes.size <= 20 * 1024 * 1024)
            if (path !in existing) changes[path] = bytes
        }
        return changes
    }

    private fun commit(
        parent: String, baseTree: String, branch: String,
        changes: Map<String, ByteArray>
    ): String {
        val paths = JSONArray()
        for ((path, bytes) in changes) paths.put(JSONObject()
            .put("path", path).put("mode", "100644").put("type", "blob")
            .put("sha", createBlob(bytes)))
        val newTree = write("/git/trees", "POST",
            JSONObject().put("base_tree", baseTree).put("tree", paths)).getString("sha")
        val next = write("/git/commits", "POST", JSONObject()
            .put("message", "SyncCS encrypted snapshot · " +
                deviceName.take(50).replace("\n", " ") + " · " + deviceId.take(8))
            .put("tree", newTree).put("parents", JSONArray().put(parent))).getString("sha")
        try {
            write(branchEndpoint(branch), "PATCH",
                JSONObject().put("sha", next).put("force", false))
        } catch (e: ApiException) {
            if (e.code == 409 || e.code == 422) {

                throw IOException("Sync conflict: GitHub backup advanced.", e)
            }
            throw e
        }
        return next
    }

    /**
     * Create one complete v3 source snapshot. A legacy v2 backup may be replaced
     * only on explicit source-device approval; its contents are never imported.
     * Historical Git commits remain available without force pushing.
     */
    fun initialize(
        cipher: SyncCipher, initial: Map<String, String>,
        archives: Map<String, ByteArray> = emptyMap(), replaceLegacy: Boolean = false
    ): String {
        val branch = ensurePrivateRepository()
        val old = revision(branch)
        val (tree, entries) = index(old)
        val manifestSha = entries["synccs/manifest.json"]
        if (manifestSha != null) {
            check(replaceLegacy) { "A GitHub backup already exists; source approval required." }
            val priorFormat = JSONObject(String(blob(manifestSha), Charsets.UTF_8))
                .optInt("format")
            check(priorFormat == 2) {
                "Only a legacy v2 snapshot may be replaced by approved v3 initialization."
            }
        } else check(entries.keys.none { it.startsWith("synccs/") }) {
            "Incomplete existing SyncCS backup must not be overwritten."
        }
        val changes = linkedMapOf<String, ByteArray>()
        val hashes = mutableListOf<String>()
        for (i in 0 until 16) {
            val part = JSONObject()
            for ((key, value) in initial) {
                if (bucket(key) == i) part.put(key, cipher.encrypt(key, value))
            }
            val bytes = part.toString().toByteArray(Charsets.UTF_8)
            changes[shard(i)] = bytes
            hashes.add(SyncBackend.digestBytes(bytes))
        }
        changes.putAll(archiveChanges(entries, archives))
        changes["synccs/audit.enc"] = auditBlob(if (replaceLegacy) emptyMap() else entries,
            cipher, initial.keys.sorted(), emptySet())
        changes["synccs/manifest.json"] = sealedManifest(cipher, 1,
            hashes, List(16) { "" })
        return commit(old, tree, branch, changes)
    }

    /**
     * Full restore uses the same verified v3 incremental publisher, so that
     * deletes, nested profile/plugin removals, and CAS share one code path.
     */
    fun update(
        previous: Snapshot, cipher: SyncCipher, target: Map<String, String>,
        explicitDeletes: Set<String>, archives: Map<String, ByteArray> = emptyMap()
    ): String {
        val changed = (previous.values.keys + target.keys)
            .filter { previous.values[it] != target[it] }
        val deleted = previous.values.keys - target.keys
        SyncSafety.validateDeletes(deleted, explicitDeletes, previous.values.size)
        val changes = changed.associateWith { target[it] }
        return publishPartial(previous.revision, previous.generation, cipher,
            changes, explicitDeletes, archives)
    }

    /** Restore a v3 logical snapshot in a new CAS commit and generation. */
    fun rollback(current: Snapshot, targetRevision: String, cipher: SyncCipher): String {
        check(revision(current.branch) == current.revision) {
            "Sync conflict: backup changed before recovery."
        }
        val old = readAt(targetRevision, current.branch, cipher)
            ?: error("Recovery revision does not contain a complete v3 backup")
        val (_, oldFiles) = index(targetRevision)
        val (_, currentFiles) = index(current.revision)
        val paths = JSONArray()
        val restoredOverlays = old.overlayHashes.toMutableList()
        for (i in 0 until 16) {
            val path = shard(i)
            val sha = oldFiles[path] ?: error("Missing recovery shard $path")
            paths.put(JSONObject().put("path", path).put("mode", "100644")
                .put("type", "blob").put("sha", sha))
            val deltaPath = overlayPath(i)
            if (old.overlayHashes[i].isNotEmpty()) {
                val overlaySha = oldFiles[deltaPath] ?: error("Missing recovery overlay")
                paths.put(JSONObject().put("path", deltaPath).put("mode", "100644")
                    .put("type", "blob").put("sha", overlaySha))
            } else if (currentFiles.containsKey(deltaPath)) {
                // Git trees inherit paths from base_tree. Explicitly mask an
                // overlay that exists only in the current (newer) revision.
                val empty = "{}".toByteArray(Charsets.UTF_8)
                paths.put(JSONObject().put("path", deltaPath).put("mode", "100644")
                    .put("type", "blob").put("sha", createBlob(empty)))
                restoredOverlays[i] = SyncBackend.digestBytes(empty)
            }
        }
        val manifest = sealedManifest(cipher, current.generation + 1,
            old.shardHashes, restoredOverlays)
        paths.put(JSONObject().put("path", "synccs/manifest.json")
            .put("mode", "100644").put("type", "blob")
            .put("sha", createBlob(manifest)))
        val nextTree = write("/git/trees", "POST", JSONObject()
            .put("base_tree", current.tree).put("tree", paths)).getString("sha")
        val next = write("/git/commits", "POST", JSONObject()
            .put("message", "SyncCS recovered v3 snapshot · " +
                deviceName.take(50).replace("\n", " ") + " · " + deviceId.take(8))
            .put("tree", nextTree)
            .put("parents", JSONArray().put(current.revision))).getString("sha")
        try {
            write(branchEndpoint(current.branch), "PATCH",
                JSONObject().put("sha", next).put("force", false))
        } catch (error: ApiException) {
            if (error.code == 409 || error.code == 422)
                throw IOException("Sync conflict: backup changed during recovery.", error)
            throw error
        }
        return next
    }

}

internal object GithubDeviceCode {
    // Explicitly opt into rotating access/refresh tokens for OAuth Device Flow.
    internal const val DEVICE_SCOPE = "repo offline_access"
    data class Profile(val login: String, val id: String)
    data class Credential(
        val access: String, val refresh: String? = null,
        val expiresAt: Long = 0L, val issuedAt: Long = 0L,
        val refreshExpiresAt: Long = 0L
    ) {
        fun encode(): String = JSONObject().put("access_token", access)
            .put("refresh_token", refresh).put("expires_at", expiresAt)
            .put("issued_at", issuedAt)
            .put("refresh_expires_at", refreshExpiresAt).toString()

        companion object {
            fun decode(saved: String): Credential {
                if (!saved.startsWith("{")) return Credential(saved)
                val json = JSONObject(saved)
                return Credential(json.getString("access_token"),
                    json.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" },
                    json.optLong("expires_at", 0L),
                    json.optLong("issued_at", 0L),
                    json.optLong("refresh_expires_at", 0L))
            }

            fun issued(response: JSONObject): Credential {
                val now = System.currentTimeMillis()
                val expires = response.optLong("expires_in", 0L)
                val refreshExpires = response.optLong("refresh_token_expires_in", 0L)
                return Credential(response.getString("access_token"),
                    response.optString("refresh_token").takeIf { it.isNotBlank() },
                    if (expires > 0L) now + expires * 1000L else 0L,
                    now,
                    if (refreshExpires > 0L) now + refreshExpires * 1000L else 0L)
            }
        }
    }

    fun profile(token: String): Profile {
        val connection = URL("https://api.github.com/user").openConnection()
            as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "SyncCS-Backup")
        try {
            val status = connection.responseCode
            if (status != 200) {
                throw GithubBackupStore.ApiException(
                    status, "Cannot verify GitHub account (HTTP $status).")
            }
            val response = JSONObject(String(connection.inputStream.use {
                it.readBytes()
            }, Charsets.UTF_8))
            return Profile(response.getString("login"), response.getLong("id").toString())
        } finally {
            connection.disconnect()
        }
    }

    data class Challenge(val code: String, val userCode: String, val verification: String,
                         val pollSeconds: Long, val expiresSeconds: Long)

    private fun post(endpoint: String, fields: Map<String, String>): JSONObject {
        val connection = URL("https://github.com/login/$endpoint").openConnection()
            as HttpURLConnection
        connection.requestMethod = "POST"
        connection.instanceFollowRedirects = false
        connection.connectTimeout = 12000
        connection.readTimeout = 25000
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        connection.doOutput = true
        try {
            val form = fields.entries.joinToString("&") {
                URLEncoder.encode(it.key, "UTF-8") + "=" +
                    URLEncoder.encode(it.value, "UTF-8")
            }
            connection.outputStream.use { it.write(form.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) {
                throw GithubBackupStore.ApiException(status, "GitHub OAuth HTTP $status")
            }
            return JSONObject(String(response, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
    fun begin(clientId: String): Challenge {
        require(clientId.matches(Regex("[A-Za-z0-9_.-]{8,80}")))
        val r = post("device/code", mapOf("client_id" to clientId, "scope" to DEVICE_SCOPE))
        check(!r.has("error")) { r.optString("error_description") }
        return Challenge(r.getString("device_code"), r.getString("user_code"),
            r.getString("verification_uri"), r.optLong("interval", 5).coerceAtLeast(5),
            r.getLong("expires_in"))
    }
    fun poll(clientId: String, challenge: Challenge): Credential? {
        val r = post("oauth/access_token", mapOf("client_id" to clientId,
            "device_code" to challenge.code,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        return when (r.optString("error")) {
            "" -> Credential.issued(r).also {

            }
            "authorization_pending" -> null
            "slow_down" -> throw IOException("GitHub OAuth requested slower polling.")
            else -> {
                val code = r.optString("error")

                throw IOException("GitHub OAuth refused: " +
                    r.optString("error_description", code).take(150))
            }
        }
    }

    fun refresh(clientId: String, refreshToken: String): Credential {
        val r = post("oauth/access_token", mapOf("client_id" to clientId,
            "grant_type" to "refresh_token", "refresh_token" to refreshToken))
        if (r.has("error")) {
            val code = r.optString("error")

            throw GithubBackupStore.ApiException(401,
                "GitHub refresh authorization rejected: " +
                    (code.takeIf { it.matches(Regex("[a-z_]{1,64}")) } ?: "unknown"))
        }
        return Credential.issued(r).also {
            check(!it.refresh.isNullOrBlank() && it.expiresAt > 0L) {
                "GitHub did not provide a rotating refresh token."
            }
        }
    }
}
