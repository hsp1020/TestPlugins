// 파일명: hsp1020/testplugins/TestPlugins-d1a26fae88cb55c8f1d621560cf4e45bd7fd78f6/MoviekingProvider/src/main/kotlin/com/movieking/Extractor.kt
package com.movieking

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    // 2026-09: MovieKing iframe host changed from player-v2.bcbc.red to player-v2.pongping.top.
    override val mainUrl = "https://player-v2.pongping.top"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private val proxyLock = Any()
        @Volatile private var proxyServer: ProxyWebServer? = null

        private fun server(): ProxyWebServer? = synchronized(proxyLock) {
            proxyServer?.takeIf { it.running } ?: ProxyWebServer().takeIf { it.start() }
                ?.also { proxyServer = it }
        }
    }

    private data class KeySection(
        val lineIndex: Int,
        val keyUrl: String,
        val explicitIv: String?,
        val startSequence: Long,
        var sampleSegment: String? = null,
        var confirmedKey: ByteArray? = null,
        var confirmedIv: ByteArray? = null,
        var plainKey: Boolean = false,
    )

    private val uriAttribute = Regex("""URI=(["'])([^"']+)\1""")
    private val ivAttribute = Regex(""",IV=(?:"[^"]*"|'[^']*'|0x[0-9A-Fa-f]+)""")

    private fun absoluteAttributes(line: String, base: String): String =
        uriAttribute.replace(line) { match ->
            val quote = match.groupValues[1]
            "URI=$quote${resolveUrl(base, match.groupValues[2])}$quote"
        }

    // Read a bounded encrypted prefix. AES-CBC header verification never needs a full segment.
    private suspend fun segmentPrefix(url: String, headers: Map<String, String>, trace: Long): ByteArray {
        val response = app.get(url, headers = headers + ("Range" to "bytes=0-575"))
        val data = ByteArray(576)
        var count = 0
        response.body.byteStream().use { input ->
            while (count < data.size) {
                val read = input.read(data, count, data.size - count)
                if (read <= 0) break
                count += read
            }
        }
        Log.i("MovieKingProxyTrace", "phase=SEGMENT_PROBE trace=$trace http=${response.code} bytes=$count " +
            "rangeRequested=true")
        return data.copyOf(count - count % 16)
    }

    private fun mediaHeader(bytes: ByteArray): Boolean =
        (bytes.size >= 189 && bytes[0] == 0x47.toByte() &&
            bytes[188] == 0x47.toByte() &&
            (bytes.size < 377 || bytes[376] == 0x47.toByte())) ||
        (bytes.size >= 3 && bytes[0] == 0x49.toByte() &&
            bytes[1] == 0x44.toByte() && bytes[2] == 0x33.toByte()) ||
        (bytes.size >= 8 && when (String(bytes, 4, 4, Charsets.US_ASCII)) {
            "moof", "styp", "ftyp", "sidx", "emsg" -> true
            else -> false
        })


    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val trace = android.os.SystemClock.elapsedRealtimeNanos()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            val uri = URI(url)
            val hostUrl = "${uri.scheme}://${uri.host}"
            val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$hostUrl/"
            val playerHeaders = mapOf(
                "Referer" to pageReferer,
                "User-Agent" to DESKTOP_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            var playerRes = app.get(url, headers = playerHeaders)
            var m3u8Url = extractM3u8Url(playerRes.text, url)
            Log.i("MovieKingProxyTrace", "phase=PLAYER trace=$trace variant=parent http=${playerRes.code} " +
                "hasPlaylist=${m3u8Url != null}")
            if (m3u8Url == null && pageReferer.trimEnd('/') != hostUrl.trimEnd('/')) {
                val selfHeaders = playerHeaders + mapOf("Referer" to "$hostUrl/", "Origin" to hostUrl)
                val response = app.get(url, headers = selfHeaders)
                val candidate = extractM3u8Url(response.text, url)
                Log.i("MovieKingProxyTrace", "phase=PLAYER trace=$trace variant=self http=${response.code} " +
                    "hasPlaylist=${candidate != null}")
                if (candidate != null) {
                    playerRes = response
                    m3u8Url = candidate
                }
            }
            if (m3u8Url == null) {
                Log.w("MovieKingProxyTrace", "phase=NO_PLAYLIST trace=$trace")
                return
            }

            val mediaHeaders = mapOf(
                "Referer" to playerRes.url,
                "Origin" to hostUrl,
                "User-Agent" to DESKTOP_UA,
                "Accept" to "*/*"
            )
            val playlistResponse = app.get(m3u8Url, headers = mediaHeaders)
            val playlist = playlistResponse.text
            if (!playlist.trimStart().startsWith("#EXTM3U")) {
                Log.w("MovieKingProxyTrace", "phase=INVALID_PLAYLIST trace=$trace http=${playlistResponse.code}")
                return
            }
            val lines = playlist.lines()
            val mediaSequence = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
                .find(playlist)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
            var sequence = mediaSequence
            var active: KeySection? = null
            val sections = mutableListOf<KeySection>()
            for ((index, line) in lines.withIndex()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#EXT-X-KEY:")) {
                    val method = Regex("""METHOD=([^,]+)""").find(trimmed)?.groupValues?.get(1)
                    if (method == "AES-128") {
                        val match = uriAttribute.find(trimmed)
                        if (match == null) {
                            Log.w("MovieKingProxyTrace", "phase=KEY_MISSING_URI trace=$trace line=$index")
                            return
                        }
                        val explicitIv = Regex("""(?:^|,)IV=(0x[0-9A-Fa-f]{32})""")
                            .find(trimmed)?.groupValues?.get(1)
                        active = KeySection(index, resolveUrl(m3u8Url, match.groupValues[2]), explicitIv, sequence)
                        sections.add(active)
                    } else active = null
                } else if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    if (active?.sampleSegment == null) active?.sampleSegment = resolveUrl(m3u8Url, trimmed)
                    sequence++
                }
            }
            Log.i("MovieKingProxyTrace", "phase=PLAYLIST trace=$trace http=${playlistResponse.code} " +
                "bytes=${playlist.length} keySections=${sections.size} mediaSequence=$mediaSequence")

            // No AES key: preserve the original URL and let Media3 fetch the playlist directly.
            if (sections.isEmpty()) {
                callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.headers = mediaHeaders
                    this.referer = playerRes.url
                })
                Log.i("MovieKingProxyTrace", "phase=DIRECT trace=$trace reason=no_aes_key")
                return
            }

            val payloadCache = HashMap<String, ByteArray>()
            for ((position, section) in sections.withIndex()) {
                val keyPayload = payloadCache[section.keyUrl] ?: app.get(section.keyUrl, headers = mediaHeaders)
                    .body.bytes().also { payloadCache[section.keyUrl] = it }
                if (keyPayload.size == 16) {
                    section.plainKey = true
                    Log.i("MovieKingProxyTrace", "phase=KEY_SOURCE trace=$trace section=$position type=raw_aes")
                    continue
                }
                val candidates = withContext(Dispatchers.Default) { solveKeyCandidatesCombinatorial(keyPayload) }
                val segmentUrl = section.sampleSegment
                if (segmentUrl == null || candidates.isEmpty()) {
                    Log.w("MovieKingProxyTrace", "phase=KEY_INVALID trace=$trace section=$position " +
                        "reason=${if (segmentUrl == null) "no_segment" else "no_candidates"}")
                    return
                }
                val encrypted = segmentPrefix(segmentUrl, mediaHeaders, trace)
                if (encrypted.size < 16) {
                    Log.w("MovieKingProxyTrace", "phase=KEY_INVALID trace=$trace section=$position reason=short_segment")
                    return
                }
                val ivs = getIvList(section.startSequence, section.explicitIv)
                var winner: ByteArray? = null
                var winningIv: ByteArray? = null
                var attempts = 0
                withContext(Dispatchers.Default) {
                    outer@ for (candidate in candidates) {
                        if (candidate.size != 16) continue
                        for (iv in ivs) {
                            attempts++
                            try {
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(candidate, "AES"), IvParameterSpec(iv))
                                if (mediaHeader(cipher.doFinal(encrypted))) {
                                    winner = candidate
                                    winningIv = iv
                                    break@outer
                                }
                            } catch (_: Exception) { }
                        }
                    }
                }
                if (winner == null) {
                    Log.w("MovieKingProxyTrace", "phase=KEY_INVALID trace=$trace section=$position " +
                        "reason=no_verified_candidate attempts=$attempts candidates=${candidates.size}")
                    return
                }
                section.confirmedKey = winner
                section.confirmedIv = winningIv
                Log.i("MovieKingProxyTrace", "phase=KEY_VERIFIED trace=$trace section=$position " +
                    "attempts=$attempts iv=${if (winningIv.contentEquals(ivs.first())) "primary" else "fallback"}")
            }

            if (sections.all { it.plainKey }) {
                callback(newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.headers = mediaHeaders
                    this.referer = playerRes.url
                })
                Log.i("MovieKingProxyTrace", "phase=DIRECT trace=$trace reason=raw_aes_keys")
                return
            }

            val proxy = server() ?: run {
                Log.e("MovieKingProxyTrace", "phase=SERVER_START_FAILED trace=$trace")
                return
            }
            val sessionId = UUID.randomUUID().toString().replace("-", "")
            val prefix = "http://127.0.0.1:${proxy.port}/$sessionId"
            val keys = HashMap<String, ByteArray>()
            val byLine = sections.associateBy { it.lineIndex }
            val rewritten = lines.mapIndexed { index, original ->
                val section = byLine[index]
                if (section != null) {
                    if (section.plainKey) {
                        absoluteAttributes(original, m3u8Url)
                    } else {
                        val keyId = "k$index"
                        keys[keyId] = requireNotNull(section.confirmedKey)
                        var line = uriAttribute.replace(original) {
                            "URI=\"$prefix/key/$keyId.bin\""
                        }
                        line = line.replace(ivAttribute, "")
                        val implicitIv = sequenceIv(section.startSequence)
                        val usedIv = requireNotNull(section.confirmedIv)
                        if (section.explicitIv != null || !usedIv.contentEquals(implicitIv)) {
                            val hex = usedIv.joinToString("") { "%02x".format(it.toInt() and 255) }
                            line += ",IV=0x$hex"
                        }
                        line
                    }
                } else if (original.trim().startsWith("#")) {
                    absoluteAttributes(original, m3u8Url)
                } else if (original.isNotBlank()) {
                    resolveUrl(m3u8Url, original.trim())
                } else original
            }.joinToString("\n")

            proxy.addSession(sessionId, rewritten, keys)
            callback(newExtractorLink(name, name, "$prefix/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.headers = mediaHeaders
                this.referer = playerRes.url
            })
            Log.i("MovieKingProxyTrace", "phase=LINK trace=$trace mode=proxy session=${sessionId.take(8)} " +
                "keyCount=${keys.size} totalMs=${android.os.SystemClock.elapsedRealtime() - started}")
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Log.e("MovieKingProxyTrace", "phase=EXCEPTION trace=$trace", error)
        }
    }

    private fun extractM3u8Url(html: String, baseUrl: String): String? {
        val normalized = html.replace("\\/", "/").replace("&amp;", "&")
        val patterns = listOf(
            Regex("""data-m3u8\s*=\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""[\"'](?:m3u8|hls|file|src)[\"']\s*:\s*[\"']([^\"']+\.m3u8[^\"']*)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""(?:file|source|src)\s*[:=]\s*['\"]([^'\"]+\.m3u8[^'\"]*)['\"]""", RegexOption.IGNORE_CASE),
            Regex("""https?://[^\s'\"<>]+\.m3u8[^\s'\"<>]*""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val match = pattern.find(normalized) ?: continue
            val candidate = if (match.groupValues.size > 1 && match.groupValues[1].isNotBlank()) {
                match.groupValues[1]
            } else {
                match.value
            }
            return resolveUrl(baseUrl, candidate)
        }
        return null
    }

    private fun resolveUrl(baseUrl: String, candidate: String): String {
        val cleaned = candidate.trim().replace("\\/", "/").replace("&amp;", "&")
        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            cleaned
        }
    }

    private fun logPlayerResponse(label: String, code: Int, finalUrl: String, html: String, m3u8Url: String?) {
        val lower = html.lowercase()
    }

    private fun diagnosticHtml(html: String): String {
        if (html.isBlank()) return "<empty>"
        val compact = html.replace(Regex("""\s+"""), " ").trim()
        val lower = compact.lowercase()
        val markers = listOf("data-m3u8", ".m3u8", "cf-challenge", "just a moment", "hold tight", "secure connection", "player")
        val pos = markers.map { lower.indexOf(it) }.filter { it >= 0 }.minOrNull() ?: 0
        val start = (pos - 250).coerceAtLeast(0)
        val end = (pos + 950).coerceAtMost(compact.length)
        return compact.substring(start, end)
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val parts = url.split(Regex("/v\\d+/"))
            if (parts.size >= 2) {
                // Old token format contained a Base64 JSON payload. New JWE-style tokens may not.
                for (payload in parts[1].split(".")) {
                    if (payload.isBlank()) continue
                    try {
                        val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                        val idMatch = Regex(""""id"\s*:\s*(\d+)""").find(decoded)
                        if (idMatch != null) return idMatch.groupValues[1]
                    } catch (_: Exception) {
                    }
                }
            }
        } catch (_: Exception) {
        }
        val fallback = url.hashCode().toString().replace("-", "n")
        return "MK_$fallback"
    }

    private fun sequenceIv(seq: Long): ByteArray = ByteArray(16).also { iv ->
        for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
    }

    private fun getIvList(seq: Long, hexIv: String?): List<ByteArray> {
        val ivs = mutableListOf<ByteArray>()
        if (!hexIv.isNullOrEmpty()) {
            try {
                val hex = hexIv.removePrefix("0x")
                val iv = ByteArray(16)
                hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                ivs.add(iv)
            } catch(e:Exception) { ivs.add(ByteArray(16)) }
        } else ivs.add(ByteArray(16))
        
        ivs.add(sequenceIv(seq))
        ivs.add(ByteArray(16))
        return ivs
    }

    private fun solveKeyCandidatesCombinatorial(payload: ByteArray): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        return try {
            val raw = payload.toString(Charsets.UTF_8).trim()
            val json = if (raw.startsWith("{")) {
                raw
            } else {
                try {
                    String(Base64.decode(raw, Base64.DEFAULT))
                } catch (e: Exception) {
                    return emptyList()
                }
            }

            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""")
                .find(json)?.groupValues?.get(1)
            if (encStr == null) {
                return emptyList()
            }

            val b64 = try {
                Base64.decode(encStr, Base64.DEFAULT)
            } catch (e: Exception) {
                byteArrayOf()
            }

            if (b64.size == 16) list.add(b64)
            if (b64.size >= 22) {
                val src = b64
                val targetGapsList = listOf(listOf(0, 2, 2, 2, 2), listOf(0, 1, 1, 1, 1))
                val allPerms = generatePermutations(listOf(0, 1, 2, 3))

                for (gaps in targetGapsList) {
                    try {
                        val segs = mutableListOf<ByteArray>()
                        var idx = gaps[0]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[1]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[2]
                        segs.add(src.copyOfRange(idx, idx + 4)); idx += 4 + gaps[3]
                        segs.add(src.copyOfRange(idx, idx + 4))

                        for (perm in allPerms) {
                            val k = ByteArray(16)
                            for (j in 0 until 4) System.arraycopy(segs[perm[j]], 0, k, j * 4, 4)
                            list.add(k)
                        }
                    } catch (e: Exception) {
                    }
                }
            }

            list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun generatePermutations(list: List<Int>): List<List<Int>> {
        if (list.isEmpty()) return listOf(emptyList())
        val result = mutableListOf<List<Int>>()
        for (i in list.indices) {
            val elem = list[i]
            val rest = list.take(i) + list.drop(i + 1)
            for (p in generatePermutations(rest)) result.add(listOf(elem) + p)
        }
        return result
    }

    class ProxyWebServer {
        private data class Session(
            val playlist: ByteArray,
            val keys: Map<String, ByteArray>,
            @Volatile var lastAccess: Long = android.os.SystemClock.elapsedRealtime()
        )

        private val sessions = ConcurrentHashMap<String, Session>()
        private var socket: ServerSocket? = null
        @Volatile var running = false
            private set
        var port = 0
            private set

        fun start(): Boolean = try {
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 60000
            socket = server
            port = server.localPort
            running = true
            Log.i("MovieKingProxyTrace", "phase=SERVER_START port=$port bind=loopback")
            CoroutineScope(Dispatchers.IO).launch {
                while (running) {
                    try {
                        val client = server.accept()
                        launch { serve(client) }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (running) Log.w("MovieKingProxyTrace", "phase=ACCEPT_ERROR type=${e.javaClass.simpleName}")
                    }
                    expire()
                }
            }
            true
        } catch (error: Exception) {
            Log.e("MovieKingProxyTrace", "phase=SERVER_BIND_FAILED", error)
            false
        }

        fun addSession(id: String, playlist: String, keys: Map<String, ByteArray>) {
            sessions[id] = Session(playlist.toByteArray(Charsets.UTF_8), keys.toMap())
            Log.i("MovieKingProxyTrace", "phase=SESSION_ADD id=${id.take(8)} active=${sessions.size} " +
                "playlistBytes=${sessions[id]?.playlist?.size} keys=${keys.size}")
        }

        private fun expire() {
            val now = android.os.SystemClock.elapsedRealtime()
            for ((id, state) in sessions) {
                if (now - state.lastAccess >= 24L * 60 * 60 * 1000 && sessions.remove(id, state)) {
                    Log.i("MovieKingProxyTrace", "phase=SESSION_EXPIRE id=${id.take(8)} active=${sessions.size}")
                }
            }
            if (sessions.isEmpty()) {
                running = false
                try { socket?.close() } catch (_: Exception) { }
                socket = null
                Log.i("MovieKingProxyTrace", "phase=SERVER_STOP reason=idle")
            }
        }

        private suspend fun serve(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 5000
                    val first = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                    val path = first.split(" ").getOrNull(1)?.substringBefore('?').orEmpty()
                    val parts = path.trimStart('/').split('/')
                    val id = parts.firstOrNull().orEmpty()
                    val session = sessions[id]
                    val data = if (session == null) null else when {
                        parts.size == 2 && parts[1] == "playlist.m3u8" -> session.playlist
                        parts.size == 3 && parts[1] == "key" && parts[2].endsWith(".bin") ->
                            session.keys[parts[2].removeSuffix(".bin")]
                        else -> null
                    }
                    if (data != null) session?.lastAccess = android.os.SystemClock.elapsedRealtime()
                    val type = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream"
                    val status = if (data == null) "404 Not Found" else "200 OK"
                    val payload = data ?: ByteArray(0)
                    val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
                        "Connection: close\r\nContent-Length: ${payload.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                    val output = socket.getOutputStream()
                    output.write(header.toByteArray(Charsets.US_ASCII))
                    output.write(payload)
                    output.flush()
                    Log.i("MovieKingProxyTrace", "phase=HTTP id=${id.take(8)} resource=${parts.drop(1).joinToString("/")} " +
                        "status=$status bytes=${payload.size}")
                }
            } catch (e: Exception) {
                Log.w("MovieKingProxyTrace", "phase=HTTP_ERROR type=${e.javaClass.simpleName}")
                try { client.close() } catch (_: Exception) { }
            }
        }
    }

}
