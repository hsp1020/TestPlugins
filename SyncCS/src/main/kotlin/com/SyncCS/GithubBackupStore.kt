package com.SyncCS

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.net.URL
import java.security.MessageDigest

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
    private val deviceId: String = "unknown"
) {
    data class Snapshot(
        val revision: String, val tree: String, val branch: String,
        val generation: Int, val values: Map<String, String>, val encrypted: Map<String, String>
    )
    private val apiRoot = "https://api.github.com/repos/$owner/$repository"
    internal class ApiException(val code: Int, text: String) : IOException(text)

    private fun api(url: String, method: String = "GET", json: JSONObject? = null): JSONObject {
        require(url.startsWith("https://api.github.com/"))
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer $token")
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
            if (status !in 200..299) throw ApiException(status,
                "GitHub API HTTP $status: " + String(response, Charsets.UTF_8).take(200))
            return if (response.isEmpty()) JSONObject()
                else JSONObject(String(response, Charsets.UTF_8))
        } finally {
            conn.disconnect()
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

    private fun readAt(rev: String, branch: String, cipher: SyncCipher): Snapshot? {
        val (tree, entries) = index(rev)
        val manifestSha = entries["synccs/manifest.json"] ?: run {
            check(entries.keys.none { it.startsWith("synccs/") }) {
                "Existing partial backup must be recovered manually."
            }
            return null
        }
        val manifest = JSONObject(String(blob(manifestSha), Charsets.UTF_8))
        check(manifest.getInt("format") == 1 && manifest.getBoolean("complete"))
        check(cipher.decrypt("synccs/check", manifest.getString("verification"))
            == "synccs-github-v1") { "Invalid recovery passphrase." }
        val encrypted = linkedMapOf<String, String>()
        val values = linkedMapOf<String, String>()
        for (i in 0 until 16) {
            val hash = entries[shard(i)]
                ?: throw IOException("Corrupted backup: missing shard " + shard(i))
            val part = JSONObject(String(blob(hash), Charsets.UTF_8))
            for (key in part.keys()) {
                check(bucket(key) == i && !values.containsKey(key)) { "Invalid backup shard." }
                val ciphertext = part.getString(key)
                encrypted[key] = ciphertext
                values[key] = cipher.decrypt(key, ciphertext)
            }
        }
        return Snapshot(rev, tree, branch, manifest.getInt("generation"), values, encrypted)
    }

    fun current(cipher: SyncCipher): Snapshot? {
        val branch = ensurePrivateRepository()
        return readAt(revision(branch), branch, cipher)
    }

    fun previous(revision: String, branch: String, cipher: SyncCipher): Snapshot? =
        readAt(revision, branch, cipher)

    data class Revision(val id: String, val summary: String, val timestamp: String)

    /** Read-only history for explicit user-directed rollback. */
    fun history(branch: String, limit: Int = 20): List<Revision> {
        require(limit in 1..50)
        return historyArray(branch, limit)
    }

    private fun historyArray(branch: String, limit: Int): List<Revision> {
        val url = "https://api.github.com/repos/$owner/$repository/commits?sha=" +
            URLEncoder.encode(branch, "UTF-8") + "&per_page=$limit"
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 12000
        conn.readTimeout = 25000
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("User-Agent", "SyncCS-Backup")
        try {
            check(conn.responseCode == 200) { "Cannot load GitHub backup revisions." }
            val rows = JSONArray(String(conn.inputStream.use { it.readBytes() }, Charsets.UTF_8))
            return (0 until rows.length()).map { i ->
                val entry = rows.getJSONObject(i)
                val commit = entry.getJSONObject("commit")
                Revision(entry.getString("sha"), commit.optString("message").lineSequence().first(),
                    commit.optJSONObject("committer")?.optString("date").orEmpty())
            }
        } finally { conn.disconnect() }
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
            if (e.code == 409 || e.code == 422)
                throw IOException("Sync conflict: GitHub backup advanced.", e)
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
        for (i in 0 until 16) {
            val part = JSONObject()
            for ((key, value) in initial) {
                if (bucket(key) == i) part.put(key, cipher.encrypt(key, value))
            }
            changes[shard(i)] = part.toString().toByteArray(Charsets.UTF_8)
        }
        changes.putAll(archiveChanges(entries, archives))
        changes["synccs/manifest.json"] = JSONObject()
            .put("format", 1).put("complete", true).put("generation", 1)
            .put("verification", cipher.encrypt("synccs/check", "synccs-github-v1"))
            .toString().toByteArray(Charsets.UTF_8)
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
        check(revision(previous.branch) == previous.revision) {
            "Sync conflict: remote revision changed."
        }
        val deleted = previous.values.keys - target.keys
        check(deleted.all { it in explicitDeletes }) {
            "Incomplete local snapshot: implicit deletion blocked."
        }
        check(deleted.size <= 25 && deleted.size <= maxOf(2, previous.values.size / 100)) {
            "Mass deletion blocked: " + deleted.size
        }
        val changed = (previous.values.keys + target.keys)
            .filter { previous.values[it] != target[it] }
        if (changed.isEmpty() && archives.isEmpty()) return previous.revision
        check(changed.size <= 1000) {
            "Mass update blocked: " + changed.size
        }
        val changes = linkedMapOf<String, ByteArray>()
        for (i in changed.map(::bucket).distinct()) {
            val part = JSONObject()
            for ((key, value) in target) {
                if (bucket(key) != i) continue
                val existing = previous.encrypted[key]
                part.put(key, if (existing != null && previous.values[key] == value)
                    existing else cipher.encrypt(key, value))
            }
            changes[shard(i)] = part.toString().toByteArray(Charsets.UTF_8)
        }
        val (_, existing) = index(previous.revision)
        changes.putAll(archiveChanges(existing, archives))
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
        val manifest = JSONObject().put("format", 1).put("complete", true)
            .put("generation", current.generation + 1)
            .put("verification", cipher.encrypt("synccs/check", "synccs-github-v1"))
            .put("recovered_from", old.revision)
        paths.put(JSONObject().put("path", "synccs/manifest.json")
            .put("mode", "100644").put("type", "blob")
            .put("sha", createBlob(manifest.toString().toByteArray(Charsets.UTF_8))))
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
    data class Profile(val login: String, val id: String)

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
            check(connection.responseCode == 200) { "Cannot verify GitHub account." }
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
            if (status !in 200..299) throw IOException("GitHub OAuth HTTP $status")
            return JSONObject(String(response, Charsets.UTF_8))
        } finally {
            connection.disconnect()
        }
    }
    fun begin(clientId: String): Challenge {
        require(clientId.matches(Regex("[A-Za-z0-9_.-]{8,80}")))
        val r = post("device/code", mapOf("client_id" to clientId, "scope" to "repo"))
        check(!r.has("error")) { r.optString("error_description") }
        return Challenge(r.getString("device_code"), r.getString("user_code"),
            r.getString("verification_uri"), r.optLong("interval", 5).coerceAtLeast(5),
            r.getLong("expires_in"))
    }
    fun poll(clientId: String, challenge: Challenge): String? {
        val r = post("oauth/access_token", mapOf("client_id" to clientId,
            "device_code" to challenge.code,
            "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"))
        return when (r.optString("error")) {
            "" -> r.getString("access_token")
            "authorization_pending" -> null
            "slow_down" -> throw IOException("GitHub OAuth requested slower polling.")
            else -> throw IOException("GitHub OAuth refused: " +
                r.optString("error_description", r.optString("error")).take(150))
        }
    }
}
