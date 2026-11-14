package com.hsp1020.elevate

import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Private, persistent metadata cache. No StremioC code or cache dependency. */
internal object ElevateMetadataDiskCache {
    private const val MAX_RAW_BYTES = 2 * 1024 * 1024
    private const val MAX_FILE_BYTES = 2L * 1024 * 1024
    private const val MAX_TOTAL_BYTES = 50L * 1024 * 1024
    private val lock = Any()
    private val limits = mapOf("manifest" to 100, "meta" to 200,
        "tmdb_detail" to 700, "tmdb_season" to 300, "tmdb_logo" to 200)
    private var quotaChecked = false
    private var writesSinceQuota = 0
    private var lastQuotaCheckMs = 0L
    private const val QUOTA_CHECK_WRITES = 16
    private const val QUOTA_CHECK_INTERVAL_MS = 30_000L

    internal data class Entry(val body: String, val at: Long, val ttlMs: Long)

    private fun directory(): File? = CloudStreamApp.context?.filesDir
        ?.let { File(it, "elevate-metadata-v1") }

    private fun file(dir: File, kind: String, key: String): File {
        val hash = MessageDigest.getInstance("SHA-256")
            .digest((kind + ":" + key).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(dir, kind + "_" + hash + ".gz")
    }

    suspend fun read(kind: String, key: String): Entry? = withContext(Dispatchers.IO) {
        val target = directory()?.takeIf { it.isDirectory }?.let { file(it, kind, key) }
            ?: return@withContext null
        if (!target.isFile || target.length() > MAX_FILE_BYTES) return@withContext null
        try {
            GZIPInputStream(target.inputStream()).bufferedReader(Charsets.UTF_8).use { reader ->
                val at = reader.readLine()?.toLongOrNull() ?: return@use null
                val ttl = reader.readLine()?.toLongOrNull() ?: return@use null
                val now = System.currentTimeMillis()
                if (ttl <= 0 || at > now || now - at >= ttl) return@use null
                val body = reader.readText()
                if (body.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES)
                    return@use null
                target.setLastModified(now)
                Entry(body, at, ttl)
            }
        } catch (_: Exception) { null }
    }

    suspend fun write(kind: String, key: String, body: String, ttlMs: Long) {
        if (ttlMs <= 0L || body.contains("data:image/", true) ||
            body.toByteArray(Charsets.UTF_8).size > MAX_RAW_BYTES) return
        withContext(Dispatchers.IO) {
            val dir = directory()?.apply { mkdirs() }?.takeIf { it.isDirectory }
                ?: return@withContext
            val temp = runCatching { File.createTempFile("elevate_", ".tmp", dir) }
                .getOrNull() ?: return@withContext
            try {
                GZIPOutputStream(temp.outputStream()).bufferedWriter(Charsets.UTF_8).use {
                    it.write(System.currentTimeMillis().toString())
                    it.newLine()
                    it.write(ttlMs.toString())
                    it.newLine()
                    it.write(body)
                }
                if (temp.length() > MAX_FILE_BYTES) return@withContext
                synchronized(lock) {
                    if (temp.renameTo(file(dir, kind, key))) {
                        writesSinceQuota++
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (!quotaChecked || writesSinceQuota >= QUOTA_CHECK_WRITES ||
                            now - lastQuotaCheckMs >= QUOTA_CHECK_INTERVAL_MS) {
                            val files = dir.listFiles()?.filter {
                                it.isFile && it.name.endsWith(".gz")
                            }.orEmpty()
                            val count = files.count { it.name.startsWith("${kind}_") }
                            if (files.sumOf { it.length() } > MAX_TOTAL_BYTES ||
                                count > (limits[kind] ?: 1200)) {
                                for ((group, limit) in limits) {
                                    files.filter { it.name.startsWith("${group}_") }
                                        .sortedByDescending { it.lastModified() }
                                        .drop(limit).forEach { it.delete() }
                                }
                                val remain = files.filter { it.exists() }
                                    .sortedBy { it.lastModified() }
                                var total = remain.sumOf { it.length() }
                                for (entry in remain) {
                                    if (total <= MAX_TOTAL_BYTES) break
                                    val bytes = entry.length()
                                    if (entry.delete()) total -= bytes
                                }
                            }
                            quotaChecked = true
                            writesSinceQuota = 0
                            lastQuotaCheckMs = now
                        }
                    }
                }
            } catch (_: Exception) {
                // Cache failures never break metadata display.
            } finally { temp.delete() }
        }
    }
}
