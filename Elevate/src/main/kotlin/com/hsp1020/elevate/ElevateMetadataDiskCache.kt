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
                        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".gz") }
                            .orEmpty().sortedBy { it.lastModified() }
                        var bytes = files.sumOf { it.length() }
                        var count = files.size
                        for (entry in files) {
                            if (bytes <= MAX_TOTAL_BYTES && count <= 1200) break
                            bytes -= entry.length()
                            if (entry.delete()) count--
                        }
                    }
                }
            } catch (_: Exception) {
                // Cache failures never break metadata display.
            } finally { temp.delete() }
        }
    }
}
