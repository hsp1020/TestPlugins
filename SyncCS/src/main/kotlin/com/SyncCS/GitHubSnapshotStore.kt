package com.SyncCS

import android.content.Context
import android.util.Base64
import org.json.JSONObject
import org.json.JSONArray
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

internal class GitHubSyncConfig(context: Context) {
    private val preferencesContext = context.applicationContext
    val preferences = context.applicationContext.getSharedPreferences("SyncCS_GitHub", Context.MODE_PRIVATE)
    val deviceId: String
        get() = preferences.getString("device_id", null) ?: UUID.randomUUID().toString().also {
            check(preferences.edit().putString("device_id", it).commit())
        }
    var deviceName: String
        get() = preferences.getString("device_name", android.os.Build.MODEL) ?: android.os.Build.MODEL
        set(value) { check(preferences.edit().putString("device_name", value).commit()) }
    var repository: String
        get() = preferences.getString("repository", "") ?: ""
        set(value) {
            require(Regex("[A-Za-z0-9-]{1,39}/[A-Za-z0-9._-]{1,100}").matches(value))
            check(preferences.edit().putString("repository", value).commit())
        }
    fun storeCredentials(token: String, passphrase: String) {
        require(token.isNotBlank() && passphrase.length >= 12)
        check(preferences.edit()
            .putString("access", LocalVault.encrypt(token.trim().toByteArray(Charsets.UTF_8)))
            .putString("recovery", LocalVault.encrypt(passphrase.toByteArray(Charsets.UTF_8)))
            .commit())
    }
    fun token(): String = preferences.getString("access", null)?.let {
        String(LocalVault.decrypt(it), Charsets.UTF_8)
    } ?: error("Connect GitHub before syncing")
    fun passphrase(): String = preferences.getString("recovery", null)?.let {
        String(LocalVault.decrypt(it), Charsets.UTF_8)
    } ?: error("Enter the recovery passphrase")
    fun connected() = repository.isNotBlank() && preferences.contains("access") && preferences.contains("recovery")
    fun disconnect() {
        // Preserve the encrypted old baseline for forensic recovery, but never
        // allow a different GitHub repository to inherit its sync generation.
        val old = File(preferencesContext.filesDir, "synccs-github-baseline-v1.enc")
        if (old.isFile) {
            val archived = File(preferencesContext.filesDir,
                "synccs-github-disconnected-" + System.currentTimeMillis() + ".enc")
            check(old.renameTo(archived)) { "Unable to preserve former baseline" }
        }
        check(preferences.edit().remove("access").remove("recovery").remove("repository")
            .remove("generation").remove("head").commit())
    }
}

internal data class GitHubSnapshot(
    val generation: String,
    val device: String,
    val profiles: String,
    val plugins: String,
    val settings: Map<String, String>,
    val format: Int = 1,
    val deviceName: String = ""
) {
    fun json(): String {
        val entries = JSONObject()
        settings.forEach { (key, value) -> entries.put(key, value) }
        return JSONObject().put("format", format)
            .put("generation", generation)
            .put("device", device)
            .put("deviceName", deviceName)
            .put("profiles", profiles)
            .put("plugins", plugins)
            .put("settings", entries).toString()
    }
    companion object {
        fun parse(json: String): GitHubSnapshot {
            val raw = JSONObject(json)
            require(raw.getInt("format") == 1) { "Unknown GitHub backup format" }
            val generation = raw.getString("generation")
            require(generation.length in 16..100)
            val obj = raw.getJSONObject("settings")
            require(obj.length() <= 100000) { "Backup has too many settings" }
            val data = linkedMapOf<String, String>()
            for (key in obj.keys()) {
                require(key.startsWith("d/") || key.startsWith("s/") || key.startsWith("p/"))
                require(key.length <= 512)
                data[key] = obj.getString(key)
            }
            return GitHubSnapshot(generation, raw.getString("device"),
                raw.getString("profiles"), raw.getString("plugins"), data,
                deviceName = raw.optString("deviceName", ""))
        }
    }
}

internal class GitHubSyncTransport(private val config: GitHubSyncConfig) {
    data class Remote(val sha: String, val snapshot: GitHubSnapshot)
    class HttpFailure(val status: Int, message: String) : IOException("GitHub HTTP " + status + ": " + message)

    private fun request(url: String, method: String = "GET", body: JSONObject? = null, token: String? = config.token()): JSONObject {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod = method
        conn.connectTimeout = 12000
        conn.readTimeout = 30000
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        conn.setRequestProperty("User-Agent", "CloudStream-SyncCS")
        if (!token.isNullOrBlank()) conn.setRequestProperty("Authorization", "Bearer " + token)
        if (body != null) {
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        try {
            val status = conn.responseCode
            val bytes = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { it.readBytes() } ?: ByteArray(0)
            if (status !in 200..299) throw HttpFailure(status, String(bytes, Charsets.UTF_8).take(250))
            return if (bytes.isEmpty()) JSONObject() else JSONObject(String(bytes, Charsets.UTF_8))
        } finally {
            conn.disconnect()
        }
    }
    private fun api(path: String): String =
        "https://api.github.com/repos/" + config.repository + path

    private fun userId(): String = request("https://api.github.com/user").getLong("id").toString()
    private fun cipher(): SyncCipher = SyncCipher.derive(
        config.passphrase(), "github-sync/" + config.repository.lowercase(), userId()
    )
    private fun compress(text: String): String {
        val buffer = ByteArrayOutputStream()
        GZIPOutputStream(buffer).use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return Base64.encodeToString(buffer.toByteArray(), Base64.NO_WRAP)
    }
    private fun decompress(encoded: String): String {
        val compressed = Base64.decode(encoded, Base64.DEFAULT)
        require(compressed.size <= 16 * 1024 * 1024)
        val output = ByteArrayOutputStream()
        GZIPInputStream(ByteArrayInputStream(compressed)).use { input ->
            val block = ByteArray(8192)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                output.write(block, 0, count)
                require(output.size() <= 32 * 1024 * 1024) { "Uncompressed backup too large" }
            }
        }
        return output.toString("UTF-8")
    }
    private fun encode(snapshot: GitHubSnapshot): String =
        JSONObject().put("format", 1)
            .put("encrypted", cipher().encrypt("github-sync-state-v1", compress(snapshot.json())))
            .toString()
    private fun decode(value: String): GitHubSnapshot {
        val obj = JSONObject(value)
        require(obj.getInt("format") == 1)
        val text = cipher().decrypt("github-sync-state-v1", obj.getString("encrypted"))
        return GitHubSnapshot.parse(decompress(text))
    }
    fun checkPrivateRepository() {
        val response = request(api(""))
        require(response.optBoolean("private")) {
            "Refusing to save backups in a public GitHub repository"
        }
        val permissions = response.optJSONObject("permissions")
        require(permissions?.optBoolean("push") == true) {
            "GitHub token needs Contents write permission on this private repository"
        }
        require(!response.optBoolean("archived")) { "Repository is archived" }
    }
    private fun refSha(): String? = try {
        request(api("/git/ref/heads/synccs-backup")).getJSONObject("object").getString("sha")
    } catch (error: HttpFailure) {
        if (error.status == 404) null else throw error
    }
    private fun commitTree(commit: String): String =
        request(api("/git/commits/" + commit)).getJSONObject("tree").getString("sha")

    fun read(): Remote? {
        checkPrivateRepository()
        val sha = refSha() ?: return null
        return readAt(sha)
    }

    fun readAt(sha: String): Remote {
        checkPrivateRepository()
        require(Regex("[a-fA-F0-9]{40}").matches(sha)) { "Invalid Git commit ID" }
        val tree = request(api("/git/trees/" + commitTree(sha))).getJSONArray("tree")
        var blob: String? = null
        for (i in 0 until tree.length()) {
            val item = tree.getJSONObject(i)
            if (item.optString("path") == "synccs-backup.enc.json" &&
                item.optString("type") == "blob") {
                blob = item.getString("sha")
            }
        }
        require(blob != null) { "Git commit has no valid SyncCS encrypted checkpoint" }
        val data = request(api("/git/blobs/" + blob))
        require(data.optString("encoding") == "base64")
        val value = String(Base64.decode(data.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
        return Remote(sha, decode(value))
    }

    fun verifyArchivedPlugins(remote: Remote) {
        val required = PluginSync.decode(remote.snapshot.plugins).items.values
            .map { it.optString("sha") }.toSet()
        if (required.isEmpty()) return
        check(required.all { it.matches(Regex("[a-f0-9]{64}")) }) {
            "Backup manifest contains a plugin without a verified SHA-256"
        }
        val contents = request(api("/git/trees/" + commitTree(remote.sha) + "?recursive=1"))
        check(!contents.optBoolean("truncated")) { "Incomplete Git tree" }
        val tree = contents.getJSONArray("tree")
        val existing = mutableMapOf<String, String>()
        for (i in 0 until tree.length()) {
            val entry = tree.getJSONObject(i)
            existing[entry.optString("path")] = entry.optString("sha")
        }
        val encryptor = cipher()
        for (hash in required) {
            val path = "synccs-binaries/" + hash + ".enc.json"
            val blobId = existing[path] ?: error("Missing backup archive for plugin SHA " + hash)
            val blob = request(api("/git/blobs/" + blobId))
            val encodedJson = String(Base64.decode(blob.getString("content"), Base64.DEFAULT), Charsets.UTF_8)
            val encrypted = JSONObject(encodedJson).getString("encrypted")
            val clear = encryptor.decrypt("github-sync-binary-" + hash, encrypted)
            val bytes = Base64.decode(clear, Base64.DEFAULT)
            check(SyncBackend.digestBytes(bytes) == hash) {
                "Plugin archive integrity verification failed for " + hash
            }
        }
    }

    fun save(snapshot: GitHubSnapshot, expectedHead: String?,
             binaryFiles: Map<String, ByteArray> = emptyMap()): String {
        checkPrivateRepository()
        val actualHead = refSha()
        require(actualHead == expectedHead) { "Backup changed on another device; refresh first" }
        val repoInfo = request(api(""))
        val parent = actualHead ?: request(api("/git/ref/heads/" +
            repoInfo.getString("default_branch"))).getJSONObject("object").getString("sha")
        val baseTree = commitTree(parent)
        val contents = request(api("/git/trees/" + baseTree + "?recursive=1"))
        check(!contents.optBoolean("truncated")) { "Cannot verify complete backup tree" }
        val existing = mutableSetOf<String>()
        val old = contents.getJSONArray("tree")
        for (i in 0 until old.length()) existing.add(old.getJSONObject(i).optString("path"))
        val required = PluginSync.decode(snapshot.plugins).items.values
            .map { it.optString("sha") }.toSet()
        check(required.all { it.matches(Regex("[a-f0-9]{64}")) }) {
            "Refusing a backup with unverified plugin archives"
        }
        val entries = JSONArray()
        val binaryCipher = if (binaryFiles.isNotEmpty()) cipher() else null
        for (hash in required) {
            val path = "synccs-binaries/" + hash + ".enc.json"
            if (path in existing) continue
            val binary = binaryFiles[hash] ?: error("Plugin archive missing for SHA " + hash)
            check(binary.size in 1..(20 * 1024 * 1024))
            check(SyncBackend.digestBytes(binary) == hash)
            val encrypted = binaryCipher!!.encrypt("github-sync-binary-" + hash,
                Base64.encodeToString(binary, Base64.NO_WRAP))
            val binaryJson = JSONObject().put("format", 1).put("encrypted", encrypted).toString()
            val fileBlob = request(api("/git/blobs"), "POST", JSONObject()
                .put("content", binaryJson).put("encoding", "utf-8")).getString("sha")
            entries.put(JSONObject().put("path", path).put("mode", "100644")
                .put("type", "blob").put("sha", fileBlob))
        }
        val content = encode(snapshot)
        require(content.toByteArray(Charsets.UTF_8).size < 20 * 1024 * 1024)
        val blob = request(api("/git/blobs"), "POST",
            JSONObject().put("content", content).put("encoding", "utf-8")).getString("sha")
        entries.put(JSONObject().put("path", "synccs-backup.enc.json")
            .put("mode", "100644").put("type", "blob").put("sha", blob))
        val updatedTree = request(api("/git/trees"), "POST", JSONObject()
            .put("base_tree", baseTree).put("tree", entries)).getString("sha")
        val newCommit = request(api("/git/commits"), "POST", JSONObject()
            .put("message", "SyncCS encrypted checkpoint")
            .put("tree", updatedTree).put("parents", JSONArray().put(parent))).getString("sha")
        try {
            if (actualHead == null) {
                request(api("/git/refs"), "POST", JSONObject()
                    .put("ref", "refs/heads/synccs-backup").put("sha", newCommit))
            } else {
                request(api("/git/refs/heads/synccs-backup"), "PATCH",
                    JSONObject().put("sha", newCommit).put("force", false))
            }
        } catch (error: HttpFailure) {
            if (error.status == 409 || error.status == 422) {
                throw IOException("Concurrent GitHub sync detected. No local changes applied.", error)
            }
            throw error
        }
        return newCommit
    }
}

internal object GitHubDeviceFlow {
    data class Request(val code: String, val deviceCode: String, val url: String, val interval: Long)
    private fun post(parameters: Map<String, String>, path: String): JSONObject {
        val conn = URL("https://github.com/" + path).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 12000
        conn.readTimeout = 20000
        conn.doOutput = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val body = parameters.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        try {
            val status = conn.responseCode
            val value = (if (status in 200..299) conn.inputStream else conn.errorStream)
                ?.use { String(it.readBytes(), Charsets.UTF_8) } ?: ""
            if (status !in 200..299) throw IOException("GitHub authorization HTTP " + status)
            return JSONObject(value)
        } finally {
            conn.disconnect()
        }
    }
    fun begin(clientId: String): Request {
        require(Regex("[A-Za-z0-9_]{8,100}").matches(clientId))
        val result = post(mapOf("client_id" to clientId), "login/device/code")
        return Request(result.getString("user_code"), result.getString("device_code"),
            result.getString("verification_uri"), result.optLong("interval", 5L).coerceAtLeast(5L))
    }
    fun poll(clientId: String, request: Request): JSONObject = post(mapOf(
        "client_id" to clientId, "device_code" to request.deviceCode,
        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"
    ), "login/oauth/access_token")
}
