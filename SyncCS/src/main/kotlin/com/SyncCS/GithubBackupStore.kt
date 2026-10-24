package com.SyncCS

import android.util.Base64
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

/** Safe diagnostics: log GitHub's error class and request ID, never OAuth secrets. */
internal object GithubHttpDiagnostics {
    fun report(stage: String, status: Int, conn: HttpURLConnection, response: ByteArray? = null) {
        val message = runCatching {
            JSONObject(String(response ?: ByteArray(0), Charsets.UTF_8)).optString("message")
        }.getOrDefault("")
        val kind = when {
            message.equals("Bad credentials", true) -> "bad-credentials"
            message.contains("Requires authentication", true) -> "authentication-required"
            message.contains("Resource not accessible", true) -> "access-denied"
            message.contains("rate limit", true) -> "rate-limit"
            message.equals("Not Found", true) -> "not-found"
            message.isBlank() -> "no-error-message"
            else -> "other"
        }
        val auth = conn.getHeaderField("WWW-Authenticate") ?: ""
        val authError = Regex("""error="?([a-z_]+)""").find(auth)?.groupValues?.get(1)
            ?: "unspecified"
        val request = conn.getHeaderField("X-GitHub-Request-Id")
            ?.takeIf { it.matches(Regex("[A-Za-z0-9:-]{1,100}")) } ?: "missing"
        val scopes = conn.getHeaderField("X-OAuth-Scopes")
            ?.replace(Regex("[^a-zA-Z0-9_, -]"), "")?.take(100) ?: "unknown"
        val accepted = conn.getHeaderField("X-Accepted-OAuth-Scopes")
            ?.replace(Regex("[^a-zA-Z0-9_, -]"), "")?.take(100) ?: "unknown"
        val limit = conn.getHeaderField("X-RateLimit-Remaining")
            ?.takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: "unknown"
        SyncTrace.info("github-http-failure",
            "phase=$stage http=$status message=$kind authError=$authError " +
            "requestId=$request scopes=$scopes acceptedScopes=$accepted rateRemaining=$limit " +
                "serverDate=${conn.getHeaderField("Date")?.take(60) ?: "unknown"}")
    }
}

/** A rejected request is replayed at most once with a renewed credential. */
internal object GithubAuthRetry {
    fun <T> once(
        token: String, recover: ((String) -> String?)?, request: (String) -> T
    ): T {
        try {
            return request(token)
        } catch (failure: GithubBackupStore.ApiException) {
            if (failure.code == 401)
                SyncTrace.info("github-api-token-rejected",
                    "submittedId=${GithubAuthDiagnostics.fingerprint(token)} " +
                        "recoveryConfigured=${recover != null}")
            if (failure.code != 401 || recover == null) throw failure
            val replacement = recover(token)
            if (replacement.isNullOrBlank() || replacement == token) {
                SyncTrace.info("github-401-recovery", "event=unavailable action=pause")
                throw failure
            }
            SyncTrace.info("github-401-recovery", "event=retry action=repeat-once")
            return try {
                request(replacement)
            } catch (second: GithubBackupStore.ApiException) {
                if (second.code == 401) {
                    SyncTrace.info("github-api-token-rejected",
                        "submittedId=${GithubAuthDiagnostics.fingerprint(replacement)} " +
                            "phase=one-time-retry")
                    SyncTrace.info("github-401-recovery", "event=retry-rejected action=pause")
                }
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
        val shardHashes: List<String>
    )
    private val apiRoot = "https://api.github.com/repos/$owner/$repository"
    internal class ApiException(val code: Int, text: String) : IOException(text)

    @Volatile private var activeToken: String = token

    private fun <T> authorized(request: (String) -> T): T =
        GithubAuthRetry.once(activeToken, recoverUnauthorized?.let { recover ->
            { rejected ->
                recover(rejected)?.also { renewed ->
                    if (renewed != rejected) activeToken = renewed
                }
            }
        }, request)

    private fun api(url: String, method: String = "GET", json: JSONObject? = null): JSONObject {
        require(url.startsWith("https://api.github.com/"))
        return authorized { currentToken ->
        transport?.let { return@authorized it(url, method, json) }
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
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }
            }
            val status = conn.responseCode
            val response = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
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
                GithubHttpDiagnostics.report(stage, status, conn, response)
                throw ApiException(status, "GitHub API HTTP $status (stage=$stage)")
            }
            if (response.isEmpty()) JSONObject()
                else JSONObject(String(response, Charsets.UTF_8))
        } finally {
            conn.disconnect()
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

    private fun blob(sha: String): ByteArray {
        check(sha.matches(Regex("[0-9a-f]{40}")))
        val result = get("/git/blobs/$sha")
        check(result.getString("encoding") == "base64")
        return Base64.decode(result.getString("content"), Base64.DEFAULT)
    }

    private fun createBlob(bytes: ByteArray): String =
        write("/git/blobs", "POST", JSONObject()
            .put("content", Base64.encodeToString(bytes, Base64.NO_WRAP))
            .put("encoding", "base64")).getString("sha")

    private fun bucket(key: String): Int =
        (MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8))[0]
            .toInt() and 255) ushr 4
    private fun shard(index: Int): String = "synccs/data/" + index.toString(16) + ".json"

    private fun sealedManifest(cipher: SyncCipher, generation: Int,
                               shardHashes: List<String>, deltaHash: String? = null): ByteArray {
        check(shardHashes.size == 16 && shardHashes.all {
            it.matches(Regex("[0-9a-f]{64}"))
        })
        val canonical = shardHashes.joinToString("|")
        val manifest = JSONObject().put("format", 2).put("complete", true)
            .put("generation", generation)
            .put("verification", cipher.encrypt("synccs/check", "synccs-github-v1"))
            .put("shards", JSONArray(shardHashes))
            .put("rootSeal", cipher.encrypt("synccs/data-root", canonical))
        if (deltaHash != null) {
            manifest.put("deltaHash", deltaHash)
                .put("deltaSeal", cipher.encrypt("synccs/delta-root-v1", deltaHash))
        }
        return manifest.toString().toByteArray(Charsets.UTF_8)
    }

    private fun readAt(rev: String, branch: String, cipher: SyncCipher): Snapshot? {
        val (tree, entries) = index(rev)
        val manifestSha = entries["synccs/manifest.json"] ?: run {
            check(entries.keys.none { it.startsWith("synccs/") }) {
                "Existing partial backup must be recovered manually."
            }
            return null
        }
        val manifest = JSONObject(String(blob(manifestSha), Charsets.UTF_8))
        check(manifest.getInt("format") == 2 && manifest.getBoolean("complete")) {
            "Unsupported or incomplete backup manifest; refusing unsafe sync."
        }
        check(cipher.decrypt("synccs/check", manifest.getString("verification"))
            == "synccs-github-v1") { "Invalid recovery passphrase." }
        val expectedHashes = manifest.getJSONArray("shards")
        check(expectedHashes.length() == 16) { "Incomplete authenticated backup root." }
        val canonical = (0 until 16).map { expectedHashes.getString(it) }
        check(cipher.decrypt("synccs/data-root", manifest.getString("rootSeal"))
            == canonical.joinToString("|")) { "Backup root authentication failed." }
        // Keep the complete authenticated snapshot; only parallelize independent
        // shard reads. Nothing becomes visible until ALL 16 shards are verified.
        val began = System.nanoTime()
        val workers = Executors.newFixedThreadPool(4) { task ->
            Thread(task, "SyncCS-GitHub-shard").apply { isDaemon = true }
        }
        SyncTrace.info("shards-start", "count=16 workers=4 revision=${rev.take(12)}")
        val jobs = (0 until 16).map { i ->
            workers.submit(Callable {
                val bytes = blob(entries[shard(i)]
                    ?: throw IOException("Corrupted backup: missing shard " + shard(i)))
                check(SyncBackend.digestBytes(bytes) == canonical[i]) {
                    "Backup shard $i does not match its authenticated manifest."
                }
                val part = JSONObject(String(bytes, Charsets.UTF_8))
                val sealed = linkedMapOf<String, String>()
                val plain = linkedMapOf<String, String>()
                for (key in part.keys()) {
                    check(bucket(key) == i) { "Invalid backup shard." }
                    val ciphertext = part.getString(key)
                    sealed[key] = ciphertext
                    plain[key] = cipher.decrypt(key, ciphertext)
                }
                sealed to plain
            })
        }
        val encrypted = linkedMapOf<String, String>()
        val values = linkedMapOf<String, String>()
        try {
            for (job in jobs) {
                val part = try {
                    job.get()
                } catch (error: ExecutionException) {
                    throw (error.cause ?: error)
                }
                for ((key, value) in part.first) {
                    check(!encrypted.containsKey(key)) { "Duplicate backup key." }
                    encrypted[key] = value
                }
                values.putAll(part.second)
            }
        } finally {
            jobs.forEach { if (!it.isDone) it.cancel(true) }
            workers.shutdownNow()
        }
        SyncTrace.info("shards-verified",
            "count=16 keys=${values.size} elapsedMs=${(System.nanoTime() - began) / 1000000}")
        return Snapshot(rev, tree, branch, manifest.getInt("generation"), values,
            encrypted, canonical)
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

    private fun readJournal(rev: String, cipher: SyncCipher): Pair<Int, Map<String, String?>>? {
        val (_, files) = index(rev)
        val manifestId = files["synccs/manifest.json"] ?: return null
        val manifest = JSONObject(String(blob(manifestId), Charsets.UTF_8))
        if (!manifest.has("deltaHash")) return null
        check(manifest.getInt("format") == 2 && manifest.getBoolean("complete"))
        check(cipher.decrypt("synccs/check", manifest.getString("verification")) ==
            "synccs-github-v1") { "Untrusted incremental manifest" }
        val shards = manifest.getJSONArray("shards")
        check(shards.length() == 16)
        val hashes = (0 until 16).map { shards.getString(it) }
        check(cipher.decrypt("synccs/data-root", manifest.getString("rootSeal")) ==
            hashes.joinToString("|")) { "Incremental root verification failed" }
        val expected = manifest.getString("deltaHash")
        check(expected.matches(Regex("[0-9a-f]{64}")) &&
            cipher.decrypt("synccs/delta-root-v1", manifest.getString("deltaSeal")) == expected) {
            "Untrusted incremental journal"
        }
        val journalId = files["synccs/delta.enc"] ?: error("Missing sealed change journal")
        val bytes = blob(journalId)
        check(SyncBackend.digestBytes(bytes) == expected) { "Incremental journal hash mismatch" }
        val payload = JSONObject(cipher.decrypt("synccs/delta-journal-v1",
            String(bytes, Charsets.UTF_8)))
        check(payload.getInt("format") == 1)
        val rows = payload.getJSONObject("entries")
        check(rows.length() <= 1000) { "Incremental journal exceeds safe size" }
        val changes = linkedMapOf<String, String?>()
        for (key in rows.keys()) {
            check(key.isNotBlank() && key.length <= 512)
            changes[key] = if (rows.isNull(key)) null else rows.getString(key)
        }
        return manifest.getInt("generation") to changes
    }

    /** SHA-256 digests are sealed with the account's AES key in each full root. */
    private data class VerifiedRoot(val generation: Int, val hashes: List<String>)

    private fun verifiedRoot(files: Map<String, String>, cipher: SyncCipher): VerifiedRoot {
        val manifestId = files["synccs/manifest.json"] ?: error("Missing backup root")
        val manifest = JSONObject(String(blob(manifestId), Charsets.UTF_8))
        check(manifest.getInt("format") == 2 && manifest.getBoolean("complete")) {
            "Incomplete GitHub backup root"
        }
        check(cipher.decrypt("synccs/check", manifest.getString("verification")) ==
            "synccs-github-v1") { "Untrusted GitHub backup root" }
        val shardArray = manifest.getJSONArray("shards")
        check(shardArray.length() == 16) { "Incomplete shard manifest" }
        val hashes = (0 until 16).map { shardArray.getString(it) }
        check(hashes.all { it.matches(Regex("[0-9a-f]{64}")) })
        check(cipher.decrypt("synccs/data-root", manifest.getString("rootSeal")) ==
            hashes.joinToString("|")) { "Backup root authentication failed" }
        return VerifiedRoot(manifest.getInt("generation"), hashes)
    }

    /**
     * Use authenticated snapshots at TWO Git revisions, not one network request
     * per intervening commit. This works even if thousands of commits accumulated,
     * and works across older backups without per-commit journals.
     *
     * Only the changed shards are retrieved; unchanged ciphertext remains
     * verbatim in Git. A missing or altered shard fails closed.
     */
    fun incrementalSince(base: String, cipher: SyncCipher, generation: Int): Incremental? {
        if (!base.matches(Regex("[0-9a-f]{40}")) || generation <= 0) return null
        val branch = ensurePrivateRepository()
        val head = revision(branch)
        if (head == base) return Incremental(head, generation, emptyMap())
        // GitHub's "ahead" status establishes base as an ancestor of HEAD.
        // Any restore that changes the authenticated generation must fall back.
        val comparison = get("/compare/$base...$head?per_page=1")
        if (comparison.optString("status") != "ahead") return null

        val (_, baseFiles) = index(base)
        val (_, headFiles) = index(head)
        val oldRoot = verifiedRoot(baseFiles, cipher)
        val newRoot = verifiedRoot(headFiles, cipher)
        if (oldRoot.generation != generation || newRoot.generation != generation)
            return null

        val result = linkedMapOf<String, String?>()
        var alteredShards = 0
        for (bucketIndex in 0 until 16) {
            if (oldRoot.hashes[bucketIndex] == newRoot.hashes[bucketIndex]) continue
            alteredShards++
            val path = shard(bucketIndex)
            val oldData = blob(baseFiles[path] ?: error("Missing base shard $bucketIndex"))
            val newData = blob(headFiles[path] ?: error("Missing head shard $bucketIndex"))
            check(SyncBackend.digestBytes(oldData) == oldRoot.hashes[bucketIndex] &&
                SyncBackend.digestBytes(newData) == newRoot.hashes[bucketIndex]) {
                "Altered GitHub backup shard; incremental sync blocked."
            }
            val oldEntries = JSONObject(String(oldData, Charsets.UTF_8))
            val newEntries = JSONObject(String(newData, Charsets.UTF_8))
            for (key in (oldEntries.keys().asSequence().toSet() +
                    newEntries.keys().asSequence().toSet())) {
                check(bucket(key) == bucketIndex) { "Invalid authenticated shard assignment" }
                val previous = if (oldEntries.has(key)) oldEntries.getString(key) else null
                val current = if (newEntries.has(key)) newEntries.getString(key) else null
                if (previous != current) {
                    if (current != null) cipher.decrypt(key, current)
                    result[key] = current
                }
            }
        }
        SyncSafety.validateChanges(result.size)
        SyncTrace.info("github-delta-shard-diff",
            "changedShards=$alteredShards changedKeys=${result.size} " +
                "old=${base.take(12)} current=${head.take(12)}")
        return Incremental(head, generation, result)
    }

    /**
     * Update only changed verified shards, preserving the rest verbatim.
     * CAS and the sealed complete snapshot remain authoritative.
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
        val manifest = JSONObject(String(blob(existing["synccs/manifest.json"]
            ?: error("Missing verified GitHub manifest")), Charsets.UTF_8))
        check(manifest.getInt("format") == 2 && manifest.getBoolean("complete"))
        val generation = manifest.getInt("generation")
        check(SyncSafety.acceptGeneration(expectedGeneration, generation, true)) {
            "Backup recovery generation changed; re-link this device before uploading."
        }
        check(cipher.decrypt("synccs/check", manifest.getString("verification")) ==
            "synccs-github-v1")
        val list = manifest.getJSONArray("shards")
        check(list.length() == 16)
        val hashes = (0 until 16).map { list.getString(it) }.toMutableList()
        check(cipher.decrypt("synccs/data-root", manifest.getString("rootSeal")) ==
            hashes.joinToString("|")) { "Backup root authentication failed." }
        SyncSafety.validateChanges(requested.size)
        val patches = linkedMapOf<String, ByteArray>()
        val changed = linkedMapOf<String, String?>()
        val nestedDeletes = linkedSetOf<String>()
        for (bucket in requested.keys.map(::bucket).distinct()) {
            val path = shard(bucket)
            val bytes = blob(existing[path] ?: error("Missing GitHub shard $bucket"))
            check(SyncBackend.digestBytes(bytes) == hashes[bucket]) {
                "Backup shard $bucket failed verification"
            }
            val rows = JSONObject(String(bytes, Charsets.UTF_8))
            var altered = false
            for ((key, value) in requested) {
                if (bucket(key) != bucket) continue
                val previous = if (rows.has(key)) cipher.decrypt(key, rows.getString(key)) else null
                if (previous == value) continue
                if (key == "x/plugins") {
                    val old = PluginSync.decode(previous)
                    val next = PluginSync.decode(value)
                    nestedDeletes.addAll((old.items.keys - next.items.keys)
                        .map { "x/plugins/$it" })
                } else if (key == "x/profiles") {
                    val old = ProfileSync.decode(previous)
                    val next = ProfileSync.decode(value)
                    nestedDeletes.addAll((old.items.keys - next.items.keys)
                        .map { "x/profiles/$it" })
                }
                if (value == null) {
                    if (previous != null) rows.remove(key)
                } else rows.put(key, cipher.encrypt(key, value))
                changed[key] = value
                altered = true
            }
            if (altered) {
                val data = rows.toString().toByteArray(Charsets.UTF_8)
                patches[path] = data
                hashes[bucket] = SyncBackend.digestBytes(data)
            }
        }
        val deletes = changed.filterValues { it == null }.keys + nestedDeletes
        check(deletes.all { it in approvedDeletes }) {
            "Unverified deletion detected; refusing to publish."
        }
        SyncSafety.validateDeletes(deletes, approvedDeletes, 0)
        val newArchives = archiveChanges(existing, archives)
        SyncTrace.info("github-publish-effective",
            "requested=${requested.size} changed=${changed.size} " +
                "skippedIdentical=${requested.size - changed.size} " +
                "shards=${patches.size} archiveRequested=${archives.size} " +
                "archiveNew=${newArchives.size} deletions=${deletes.size}")
        if (patches.isEmpty() && newArchives.isEmpty()) {
            SyncTrace.info("github-publish-noop", "revision=${expectedRevision.take(12)}")
            return expectedRevision
        }
        patches.putAll(newArchives)
        val delta = if (changed.size <= 1000) changeJournal(cipher, changed) else null
        if (delta != null) patches["synccs/delta.enc"] = delta
        patches["synccs/manifest.json"] = sealedManifest(cipher, generation, hashes,
            delta?.let(SyncBackend::digestBytes))
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
                GithubHttpDiagnostics.report("manual-backup-list", status, conn,
                    conn.errorStream?.use { it.readBytes() })
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
        SyncTrace.info("github-user-backup",
            "event=created revision=${snapshot.revision.take(12)}")
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
                GithubHttpDiagnostics.report("backup-history", status, conn,
                    conn.errorStream?.use { it.readBytes() })
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
        val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(actual == hash) { "Plugin archive hash mismatch" }
        return bytes
    }

    private fun archiveChanges(
        existing: Map<String, String>, archives: Map<String, ByteArray>
    ): Map<String, ByteArray> {
        val changes = linkedMapOf<String, ByteArray>()
        for ((hash, bytes) in archives) {
            val path = archivePath(hash)
            val actual = MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
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
                SyncTrace.info("github-cas-rejected",
                    "status=${e.code} parent=${parent.take(12)}")
                throw IOException("Sync conflict: GitHub backup advanced.", e)
            }
            throw e
        }
        return next
    }

    /** One atomic first backup; an interrupted attempt cannot look complete. */
    fun initialize(cipher: SyncCipher, initial: Map<String, String>,
                   archives: Map<String, ByteArray> = emptyMap()): String {
        val branch = ensurePrivateRepository()
        val old = revision(branch)
        val (tree, entries) = index(old)
        check(entries.keys.none { it.startsWith("synccs/") }) {
            "A GitHub backup already exists; never silently overwrite it."
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
        changes["synccs/audit.enc"] = auditBlob(entries, cipher,
            initial.keys.sorted(), emptySet())
        changes["synccs/manifest.json"] = sealedManifest(cipher, 1, hashes)
        return commit(old, tree, branch, changes)
    }

    /**
     * Strict CAS; no force pushes. Unexpected key disappearance is never accepted
     * as a delete, and mass updates are blocked before writing any Git objects.
     */
    fun update(
        previous: Snapshot, cipher: SyncCipher, target: Map<String, String>,
        explicitDeletes: Set<String>, archives: Map<String, ByteArray> = emptyMap()
    ): String {
        if (revision(previous.branch) != previous.revision) {
            throw IOException("Sync conflict: remote revision changed.")
        }
        val deleted = previous.values.keys - target.keys
        SyncSafety.validateDeletes(deleted, explicitDeletes, previous.values.size)
        val logicalDeletions = deleted + nestedDeletions(previous.values, target)
        val changed = (previous.values.keys + target.keys)
            .filter { previous.values[it] != target[it] }
        val newArchives = archiveChanges(index(previous.revision).second, archives)
        if (changed.isEmpty() && newArchives.isEmpty()) return previous.revision
        SyncSafety.validateChanges(changed.size)
        val changes = linkedMapOf<String, ByteArray>()
        val hashes = previous.shardHashes.toMutableList()
        for (i in changed.map(::bucket).distinct()) {
            val part = JSONObject()
            for ((key, value) in target) {
                if (bucket(key) != i) continue
                val existing = previous.encrypted[key]
                part.put(key, if (existing != null && previous.values[key] == value)
                    existing else cipher.encrypt(key, value))
            }
            val bytes = part.toString().toByteArray(Charsets.UTF_8)
            changes[shard(i)] = bytes
            hashes[i] = SyncBackend.digestBytes(bytes)
        }
        val delta = if (changed.size <= 1000) changeJournal(cipher,
            changed.associateWith { target[it] }) else null
        if (delta != null) changes["synccs/delta.enc"] = delta
        changes["synccs/manifest.json"] = sealedManifest(cipher,
            previous.generation, hashes, delta?.let(SyncBackend::digestBytes))
        val (_, existing) = index(previous.revision)
        changes.putAll(newArchives)
        changes["synccs/audit.enc"] = auditBlob(existing, cipher, changed, logicalDeletions)
        return commit(previous.revision, previous.tree, previous.branch, changes)
    }

    /** Only for an explicit user-approved recovery. Rejects concurrent writes. */
    fun rollback(current: Snapshot, targetRevision: String, cipher: SyncCipher): String {
        check(revision(current.branch) == current.revision) {
            "Sync conflict: backup changed before recovery."
        }
        val old = readAt(targetRevision, current.branch, cipher)
            ?: error("Recovery revision does not contain a complete backup")
        val (_, oldFiles) = index(targetRevision)
        val paths = JSONArray()
        for ((path, hash) in oldFiles) {
            if (path.startsWith("synccs/data/")) {
                paths.put(JSONObject().put("path", path).put("mode", "100644")
                    .put("type", "blob").put("sha", hash))
            }
        }
        val manifest = sealedManifest(cipher, current.generation + 1, old.shardHashes)
        paths.put(JSONObject().put("path", "synccs/manifest.json")
            .put("mode", "100644").put("type", "blob")
            .put("sha", createBlob(manifest)))
        val newTree = write("/git/trees", "POST", JSONObject()
            .put("base_tree", current.tree).put("tree", paths)).getString("sha")
        val next = write("/git/commits", "POST", JSONObject()
            .put("message", "SyncCS recovered snapshot · " +
                deviceName.take(50).replace("\n", " ") + " · " + deviceId.take(8))
            .put("tree", newTree).put("parents", JSONArray().put(current.revision)))
            .getString("sha")
        try {
            write(branchEndpoint(current.branch), "PATCH",
                JSONObject().put("sha", next).put("force", false))
        } catch (e: ApiException) {
            if (e.code == 409 || e.code == 422)
                throw IOException("Sync conflict: backup changed during recovery.", e)
            throw e
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
                GithubHttpDiagnostics.report("account-profile", status, connection,
                    connection.errorStream?.use { it.readBytes() })
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
                GithubHttpDiagnostics.report("oauth-$endpoint", status, connection, response)
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
                SyncTrace.info("github-oauth-issued",
                    "fingerprint=${SyncBackend.digest(it.access).take(12)} " +
                    "type=${if (it.expiresAt > 0L) "expiring" else "non-expiring"} " +
                    "issuedAtMs=${it.issuedAt} expiresAtMs=${it.expiresAt} " +
                    "refreshPresent=${it.refresh != null} " +
                    "refreshExpiresAtMs=${it.refreshExpiresAt}")
            }
            "authorization_pending" -> null
            "slow_down" -> throw IOException("GitHub OAuth requested slower polling.")
            else -> {
                val code = r.optString("error")
                SyncTrace.info("github-oauth-response",
                    "phase=device-poll error=${code.takeIf {
                        it.matches(Regex("[a-z_]{1,64}"))
                    } ?: "unknown"}")
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
            SyncTrace.info("github-oauth-response",
                "phase=refresh error=${code.takeIf {
                    it.matches(Regex("[a-z_]{1,64}"))
                } ?: "unknown"}")
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
