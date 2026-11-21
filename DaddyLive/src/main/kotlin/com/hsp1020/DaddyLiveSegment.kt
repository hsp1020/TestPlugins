package com.hsp1020

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** Decode only the special segment wrappers used by the embedded DaddyLive player. */
internal object DaddyLiveSegment {
    private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
    private val rawMarker = "TIKTIKRAW".toByteArray()
    private val gzipMarker = "TIKTIKTSGZ".toByteArray()
    private val pixelMarker = "TIKTIKPX".toByteArray()
    private const val MAX_BYTES = 32 * 1024 * 1024

    private fun matches(bytes: ByteArray, at: Int, pattern: ByteArray): Boolean =
        at >= 0 && at + pattern.size <= bytes.size &&
            pattern.indices.all { bytes[at + it] == pattern[it] }

    private fun index(bytes: ByteArray, pattern: ByteArray): Int {
        for (at in 0..(bytes.size - pattern.size).coerceAtLeast(-1)) {
            if (matches(bytes, at, pattern)) return at
        }
        return -1
    }

    private fun ts(bytes: ByteArray, at: Int): Boolean =
        at >= 0 && at + 188 < bytes.size &&
            bytes[at] == 0x47.toByte() && bytes[at + 188] == 0x47.toByte()

    private fun be32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255 shl 24) or
            (bytes[at + 1].toInt() and 255 shl 16) or
            (bytes[at + 2].toInt() and 255 shl 8) or (bytes[at + 3].toInt() and 255)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
            ((bytes[at + 2].toInt() and 255) shl 16) or ((bytes[at + 3].toInt() and 255) shl 24)

    private fun unzip(bytes: ByteArray, offset: Int, length: Int): ByteArray? = runCatching {
        GZIPInputStream(ByteArrayInputStream(bytes, offset, length)).use { it.readLimited() }
            .takeIf { it.isNotEmpty() && it[0] == 0x47.toByte() }
    }.getOrNull()

    private fun java.io.InputStream.readLimited(): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            if (out.size() + count > MAX_BYTES) return ByteArray(0)
            out.write(buffer, 0, count)
        }
        return out.toByteArray()
    }

    fun unwrap(bytes: ByteArray): ByteArray? {
        if (bytes.size < 8 || bytes.size > MAX_BYTES) return null
        if (ts(bytes, 0)) return bytes
        if (matches(bytes, 0, pngMagic)) return png(bytes)
        if (matches(bytes, 0, "RIFF".toByteArray()) && matches(bytes, 8, "WEBP".toByteArray())) {
            var at = 12
            while (at + 8 <= bytes.size) {
                val size = le32(bytes, at + 4)
                if (size < 0 || size > bytes.size - at - 8) break
                if (matches(bytes, at, "EXIF".toByteArray())) {
                    val payload = at + 8
                    return bytes.copyOfRange(payload, payload + size).takeIf { ts(it, 0) }
                }
                at += 8 + size + (size and 1)
            }
            return null
        }
        val raw = index(bytes, rawMarker)
        if (raw >= 0 && ts(bytes, raw + rawMarker.size)) return bytes.copyOfRange(raw + rawMarker.size, bytes.size)
        val zip = index(bytes, gzipMarker)
        if (zip >= 0) return unzip(bytes, zip + gzipMarker.size, bytes.size - zip - gzipMarker.size)
        for (at in 0 until bytes.size - 188) {
            if (ts(bytes, at)) return bytes.copyOfRange(at, bytes.size)
        }
        // Preserve supported non-TS formats (e.g. fMP4) rather than altering ordinary streams.
        return bytes
    }

    private fun png(bytes: ByteArray): ByteArray? {
        var at = 8
        var width = 0
        var height = 0
        var depth = 0
        var color = 0
        var interlace = 0
        val compressed = ByteArrayOutputStream()
        while (at + 12 <= bytes.size) {
            val size = be32(bytes, at)
            if (size < 0 || size > bytes.size - at - 12) return null
            val payload = at + 8
            when {
                matches(bytes, at + 4, "IHDR".toByteArray()) && size >= 13 -> {
                    width = be32(bytes, payload)
                    height = be32(bytes, payload + 4)
                    depth = bytes[payload + 8].toInt() and 255
                    color = bytes[payload + 9].toInt() and 255
                    interlace = bytes[payload + 12].toInt() and 255
                }
                matches(bytes, at + 4, "IDAT".toByteArray()) -> {
                    if (compressed.size() + size > MAX_BYTES) return null
                    compressed.write(bytes, payload, size)
                }
                matches(bytes, at + 4, "IEND".toByteArray()) -> {
                    val end = payload + size + 4
                    if (ts(bytes, end)) return bytes.copyOfRange(end, bytes.size)
                    break
                }
            }
            at += size + 12
        }
        if (width < 1 || height < 1 || width.toLong() * height > 2_000_000L ||
            depth != 8 || interlace != 0 || (color != 2 && color != 6)) return null
        val step = if (color == 6) 4 else 3
        val rowSize = width * step
        val inflated = runCatching {
            InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { it.readLimited() }
        }.getOrNull() ?: return null
        if (inflated.size < (rowSize + 1L) * height) return null
        val rgb = ByteArray(width * height * 3)
        var position = 0
        var output = 0
        var previous = ByteArray(rowSize)
        repeat(height) {
            val filter = inflated[position++].toInt() and 255
            val line = ByteArray(rowSize)
            for (i in 0 until rowSize) {
                val left = if (i >= step) line[i - step].toInt() and 255 else 0
                val up = previous[i].toInt() and 255
                val upperLeft = if (i >= step) previous[i - step].toInt() and 255 else 0
                val predictor = when (filter) {
                    0 -> 0
                    1 -> left
                    2 -> up
                    3 -> (left + up) / 2
                    4 -> {
                        val p = left + up - upperLeft
                        val a = kotlin.math.abs(p - left)
                        val b = kotlin.math.abs(p - up)
                        val c = kotlin.math.abs(p - upperLeft)
                        if (a <= b && a <= c) left else if (b <= c) up else upperLeft
                    }
                    else -> return null
                }
                line[i] = ((inflated[position++].toInt() and 255) + predictor).toByte()
            }
            for (i in 0 until rowSize step step) {
                rgb[output++] = line[i]
                rgb[output++] = line[i + 1]
                rgb[output++] = line[i + 2]
            }
            previous = line
        }
        val marker = index(rgb, pixelMarker)
        if (marker < 0 || marker + pixelMarker.size + 4 > rgb.size) return null
        val lengthAt = marker + pixelMarker.size
        val length = be32(rgb, lengthAt)
        val gzipAt = lengthAt + 4
        if (length < 2 || length > rgb.size - gzipAt) return null
        return unzip(rgb, gzipAt, length)
    }
}
