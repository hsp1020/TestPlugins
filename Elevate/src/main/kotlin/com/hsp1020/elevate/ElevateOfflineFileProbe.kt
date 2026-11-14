package com.hsp1020.elevate

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * Independent, read-only post-first-frame diagnostic. Its timings are NOT the
 * playback DataSource's reads or the core Matroska extractor's parse timings.
 * It uses separate file handles and never changes the active player.
 */
internal object ElevateOfflineFileProbe {
    private const val TAG = "ElevateDownloadStart"
    private const val PREFIX_LIMIT = 256 * 1024
    private const val HEADER_LIMIT = 64 * 1024
    private const val SAMPLE_SIZE = 64 * 1024

    private fun emit(id: Int, started: Long, phase: String, message: String) {
        Log.i(TAG, "id=$id elapsedMs=${SystemClock.elapsedRealtime() - started} " +
            "phase=PROBE_$phase ${message.take(340)}")
    }

    fun start(context: Context, uri: Uri, id: Int, started: Long, resumeMs: Long) {
        Thread({
            // Keep the independent I/O test outside the measured startup path.
            try { Thread.sleep(1000L) } catch (_: InterruptedException) { return@Thread }
            val beginning = SystemClock.elapsedRealtime()
            emit(id, started, "BEGIN", "postFirstFrame=true firstFramePositionMs=$resumeMs " +
                "scheme=${uri.scheme} authority=${uri.authority}")
            runCatching { inspect(context.applicationContext, uri, id, started) }
                .onFailure { emit(id, started, "ERROR", "type=${it.javaClass.simpleName} " +
                    "message=${it.message?.take(100)}") }
            emit(id, started, "FINISH", "durationMs=${SystemClock.elapsedRealtime() - beginning}")
        }, "ElevateOfflineFileProbe").apply { isDaemon = true; start() }
    }

    private fun inspect(context: Context, uri: Uri, id: Int, started: Long) {
        val cues = LinkedHashSet<Long>()
        val seekHeads = LinkedHashSet<Long>()
        val sampled = LinkedHashSet<Long>()
        var fileSize = -1L
        try {
            val openedAt = SystemClock.elapsedRealtimeNanos()
            context.contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                emit(id, started, "FD_OPEN", "elapsedUs=${us(openedAt)} " +
                    "statSize=${descriptor.statSize}")
                FileInputStream(descriptor.fileDescriptor).channel.use { channel ->
                    fileSize = runCatching { channel.size() }.getOrDefault(descriptor.statSize)
                    emit(id, started, "FILE", "size=$fileSize seekable=${fileSize > 0}")
                    if (fileSize > 0) {
                        val reader = Reader(channel, fileSize, id, started)
                        val bytes = reader.take(0, PREFIX_LIMIT, "prefix")
                        val segment = children(bytes, 0, bytes.size)
                            .firstOrNull { it.id == 0x18538067L }
                        if (segment == null) {
                            emit(id, started, "MKV", "segmentNotFound prefixBytes=${bytes.size}")
                        } else {
                            val segmentBase = segment.content.toLong()
                            emit(id, started, "MKV_SEGMENT", "segmentBase=$segmentBase " +
                                "declaredLength=${segment.length}")
                            val firstChildren = children(bytes, segment.content, bytes.size)
                            emit(id, started, "MKV_TOP", "prefixElements=${firstChildren.size} " +
                                "types=${firstChildren.take(15).joinToString(",") { it.id.toString(16) }}")
                            for (item in firstChildren) {
                                if (item.id == 0x114d9b74L) seekHeads.add(item.start.toLong())
                                if (item.id == 0x1c53bb6bL) cues.add(item.start.toLong())
                                // The first cluster can span most of the file.
                                if (item.id == 0x1f43b675L) break
                            }
                            // Visit only a bounded number of linked SeekHeads; never
                            // scan the whole movie for signatures or decode its frames.
                            val processed = HashSet<Long>()
                            var hops = 0
                            while (seekHeads.isNotEmpty() && hops < 8) {
                                val pos = seekHeads.first()
                                seekHeads.remove(pos)
                                if (!processed.add(pos) || pos >= fileSize) continue
                                hops++
                                val headBytes = reader.take(pos, HEADER_LIMIT, "seekhead")
                                val head = element(headBytes, 0) ?: continue
                                if (head.id != 0x114d9b74L) {
                                    emit(id, started, "MKV_SEEKHEAD", "offset=$pos unexpected=${head.id.toString(16)}")
                                    continue
                                }
                                val parseAt = SystemClock.elapsedRealtimeNanos()
                                var entries = 0
                                for (entry in children(headBytes, head.content, head.end)) {
                                    if (entry.id != 0x4dbbL) continue
                                    var targetId = 0L
                                    var targetPosition = -1L
                                    for (field in children(headBytes, entry.content, entry.end)) {
                                        if (field.id == 0x53abL) targetId = number(headBytes, field)
                                        if (field.id == 0x53acL) targetPosition = number(headBytes, field)
                                    }
                                    if (targetPosition < 0 || targetPosition > fileSize - segmentBase) continue
                                    val absolute = segmentBase + targetPosition
                                    entries++
                                    if (targetId == 0x1c53bb6bL) cues.add(absolute)
                                    if (targetId == 0x114d9b74L) seekHeads.add(absolute)
                                    emit(id, started, "MKV_SEEK_ENTRY",
                                        "source=$pos type=${targetId.toString(16)} relative=$targetPosition absolute=$absolute")
                                }
                                emit(id, started, "MKV_SEEKHEAD",
                                    "offset=$pos entries=$entries parseUs=${us(parseAt)}")
                            }
                            emit(id, started, "MKV_INDEX", "seekHeads=$hops cueTargets=${cues.size}")
                            for (cuePosition in cues.take(4)) {
                                if (cuePosition !in 0 until fileSize) continue
                                val cueBytes = reader.take(cuePosition, HEADER_LIMIT, "cues")
                                val cue = element(cueBytes, 0)
                                if (cue?.id != 0x1c53bb6bL) {
                                    emit(id, started, "MKV_CUES", "offset=$cuePosition valid=false")
                                    continue
                                }
                                val parseAt = SystemClock.elapsedRealtimeNanos()
                                val count = children(cueBytes, cue.content, cue.end)
                                    .count { it.id == 0xbbL }
                                emit(id, started, "MKV_CUES",
                                    "offset=$cuePosition declaredLength=${cue.length} " +
                                        "samplePoints=$count complete=${cue.length <= cueBytes.size - cue.content} " +
                                        "parseUs=${us(parseAt)}")
                            }
                        }
                    }
                }
            } ?: emit(id, started, "FD_OPEN", "nullDescriptor=true")
        } catch (e: Exception) {
            emit(id, started, "FD_ERROR", "type=${e.javaClass.simpleName} " +
                "message=${e.message?.take(90)}")
        }

        if (fileSize <= 0L) {
            emit(id, started, "DATASOURCE_SKIP", "reason=unknownFileSize")
            return
        }
        // Controlled positional reads through the same Media3 DefaultDataSource
        // family used for offline files. This is an independent comparison,
        // not an interceptor into the live player.
        for (point in listOf(0L, fileSize / 4, fileSize / 2,
            (fileSize - SAMPLE_SIZE).coerceAtLeast(0L)) + cues.take(2)) {
            if (point in 0 until fileSize) sampled.add(point)
        }
        for (point in sampled.take(6)) sampleSource(context, uri, point,
            minOf(SAMPLE_SIZE.toLong(), fileSize - point).toInt(), id, started)
    }

    private fun sampleSource(context: Context, uri: Uri, offset: Long,
                             length: Int, id: Int, started: Long) {
        val dataSource = DefaultDataSource.Factory(context).createDataSource()
        val start = SystemClock.elapsedRealtimeNanos()
        var actual = 0L
        var reads = 0
        try {
            val spec = DataSpec.Builder().setUri(uri).setPosition(offset)
                .setLength(length.toLong()).build()
            val available = dataSource.open(spec)
            emit(id, started, "DS_OPEN", "offset=$offset length=$length " +
                "available=$available elapsedUs=${us(start)}")
            val buffer = ByteArray(16 * 1024)
            while (actual < length) {
                val nStart = SystemClock.elapsedRealtimeNanos()
                val n = dataSource.read(buffer, 0,
                    minOf(buffer.size, length - actual.toInt()))
                emit(id, started, "DS_READ", "position=${offset + actual} " +
                    "bytes=$n elapsedUs=${us(nStart)}")
                if (n <= 0 || n == C.RESULT_END_OF_INPUT) break
                reads++
                actual += n
            }
            emit(id, started, "DS_SUMMARY",
                "offset=$offset totalBytes=$actual calls=$reads elapsedUs=${us(start)}")
        } catch (e: Exception) {
            emit(id, started, "DS_ERROR",
                "offset=$offset bytes=$actual type=${e.javaClass.simpleName} " +
                    "elapsedUs=${us(start)}")
        } finally {
            val closeAt = SystemClock.elapsedRealtimeNanos()
            runCatching { dataSource.close() }
                .onFailure { emit(id, started, "DS_CLOSE_ERROR",
                    "offset=$offset type=${it.javaClass.simpleName}") }
            emit(id, started, "DS_CLOSE", "offset=$offset elapsedUs=${us(closeAt)}")
        }
    }

    private fun us(startNs: Long): Long = (SystemClock.elapsedRealtimeNanos() - startNs) / 1000L

    private class Reader(private val channel: FileChannel, private val size: Long,
                         private val id: Int, private val start: Long) {
        fun take(position: Long, count: Int, kind: String): ByteArray {
            if (position !in 0 until size) return byteArrayOf()
            val requested = minOf(count.toLong(), size - position).toInt()
            val buffer = ByteBuffer.allocate(requested)
            val at = SystemClock.elapsedRealtimeNanos()
            var read = 0
            var calls = 0
            while (buffer.hasRemaining()) {
                val callAt = SystemClock.elapsedRealtimeNanos()
                val n = channel.read(buffer, position + read)
                emit(id, start, "FD_READ",
                    "kind=$kind position=${position + read} bytes=$n elapsedUs=${us(callAt)}")
                if (n <= 0) break
                read += n
                calls++
            }
            emit(id, start, "FD_RANGE", "kind=$kind from=$position bytes=$read " +
                "calls=$calls elapsedUs=${us(at)}")
            return if (read == requested) buffer.array() else buffer.array().copyOf(read)
        }
    }

    private data class Element(val id: Long, val start: Int, val content: Int,
                               val end: Int, val length: Long)

    private fun vint(bytes: ByteArray, pos: Int, keepMarker: Boolean): Pair<Long, Int>? {
        if (pos !in bytes.indices) return null
        val first = bytes[pos].toInt() and 255
        var width = 1
        var marker = 128
        while (marker != 0 && (first and marker) == 0) {
            marker = marker shr 1
            width++
        }
        if (marker == 0 || width > (if (keepMarker) 4 else 8)) return null
        if (pos + width > bytes.size) return null
        var value = (if (keepMarker) first else first and (marker - 1)).toLong()
        for (i in 1 until width) value = (value shl 8) or
            (bytes[pos + i].toLong() and 255L)
        return value to width
    }

    private fun element(bytes: ByteArray, pos: Int): Element? {
        val id = vint(bytes, pos, true) ?: return null
        val length = vint(bytes, pos + id.second, false) ?: return null
        val content = pos + id.second + length.second
        if (content > bytes.size) return null
        val bounded = (content.toLong() + length.first).coerceAtMost(bytes.size.toLong())
        return Element(id.first, pos, content, bounded.toInt(), length.first)
    }

    private fun children(bytes: ByteArray, from: Int, to: Int): List<Element> {
        val out = ArrayList<Element>()
        var pos = from
        while (pos < to && out.size < 4096) {
            val e = element(bytes, pos) ?: break
            if (e.end <= pos || e.content > to) break
            out.add(e)
            pos = e.end
        }
        return out
    }

    private fun number(bytes: ByteArray, e: Element): Long {
        if (e.length > 8 || e.length <= 0) return -1L
        var result = 0L
        for (p in e.content until e.end) result = (result shl 8) or
            (bytes[p].toLong() and 255L)
        return result
    }
}
