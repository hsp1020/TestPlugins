// 파일명: hsp1020/testplugins/TestPlugins-d1a26fae88cb55c8f1d621560cf4e45bd7fd78f6/MoviekingProvider/src/main/kotlin/com/movieking/Extractor.kt
package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.*
import java.net.*
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
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[MovieKingPlayer DBG] ===== extractor start =====")
        println("[MovieKingPlayer DBG] playerUrl=$url")
        println("[MovieKingPlayer DBG] incomingReferer=${referer ?: "<null>"}")

        try {
            val videoId = extractVideoIdDeep(url)
            val uri = URI(url)
            val hostUrl = "${uri.scheme}://${uri.host}"
            val pageReferer = referer?.takeIf { it.isNotBlank() } ?: "$hostUrl/"

            // iframe navigation: the parent MovieKing detail page is the real Referer.
            // Do not force Origin on the first HTML navigation request.
            val playerHeaders = mutableMapOf(
                "Referer" to pageReferer,
                "User-Agent" to DESKTOP_UA,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            println("[MovieKingPlayer DBG] 🌐 player request Referer=$pageReferer")
            var playerRes = app.get(url, headers = playerHeaders)
            var playerHtml = playerRes.text
            var m3u8Url = extractM3u8Url(playerHtml, url)

            logPlayerResponse("parent-referer", playerRes.code, playerRes.url, playerHtml, m3u8Url)

            // Diagnostic/fallback probe: compare with the old self-referer behavior only when
            // the browser-like parent Referer request did not expose an m3u8 URL.
            if (m3u8Url == null && pageReferer.trimEnd('/') != hostUrl.trimEnd('/')) {
                val selfHeaders = mutableMapOf(
                    "Referer" to "$hostUrl/",
                    "Origin" to hostUrl,
                    "User-Agent" to DESKTOP_UA,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                )
                println("[MovieKingPlayer DBG] 🔁 m3u8 미검출 -> 기존 self-referer 방식 비교 요청")
                val selfRes = app.get(url, headers = selfHeaders)
                val selfHtml = selfRes.text
                val selfM3u8 = extractM3u8Url(selfHtml, url)
                logPlayerResponse("self-referer", selfRes.code, selfRes.url, selfHtml, selfM3u8)

                if (selfM3u8 != null) {
                    println("[MovieKingPlayer DBG] ⚠ parent-referer에서는 실패, self-referer에서는 m3u8 검출")
                    playerRes = selfRes
                    playerHtml = selfHtml
                    m3u8Url = selfM3u8
                }
            }

            if (m3u8Url == null) {
                println("[MovieKingPlayer DBG] ❌ player HTML에서 m3u8 URL을 찾지 못함")
                println("[MovieKingPlayer DBG] HTML diagnostic=${diagnosticHtml(playerHtml)}")
                return
            }

            println("[MovieKingPlayer DBG] ✅ m3u8 URL=$m3u8Url")

            // Media requests originate from the player page.
            val mediaHeaders = mutableMapOf(
                "Referer" to playerRes.url,
                "Origin" to hostUrl,
                "User-Agent" to DESKTOP_UA,
                "Accept" to "*/*"
            )

            val playlistRes = app.get(m3u8Url, headers = mediaHeaders)
            val playlistText = playlistRes.text
            println(
                "[MovieKingPlayer DBG] 📃 playlist HTTP=${playlistRes.code} final=${playlistRes.url} " +
                    "len=${playlistText.length} extm3u=${playlistText.trimStart().startsWith("#EXTM3U")}" 
            )

            if (!playlistText.contains("#EXTM3U")) {
                println("[MovieKingPlayer DBG] ❌ 응답이 HLS playlist가 아님: ${diagnosticHtml(playlistText)}")
                return
            }

            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI=["']([^"']+)["'](?:,IV=(0x[0-9a-fA-F]+))?""")
                .find(playlistText)
            val hexIv = keyMatch?.groupValues?.getOrNull(2)?.takeIf { it.isNotBlank() }
            val keyUrl = keyMatch?.groupValues?.getOrNull(1)?.let { resolveUrl(m3u8Url, it) }
            println("[MovieKingPlayer DBG] 🔐 AES128=${keyMatch != null} keyUrl=${keyUrl ?: "<none>"} explicitIV=${hexIv ?: "<none>"}")

            val candidates = if (keyUrl != null) {
                withContext(Dispatchers.Default) {
                    solveKeyCandidatesCombinatorial(mediaHeaders, keyUrl)
                }
            } else {
                emptyList()
            }
            println("[MovieKingPlayer DBG] 🔑 key candidates=${candidates.size}")

            val lines = playlistText.lines()
            val currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""")
                .find(playlistText)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val firstSegmentUrl = lines
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?.let { resolveUrl(m3u8Url, it.trim()) }

            println("[MovieKingPlayer DBG] 🎞 firstSegment=${firstSegmentUrl ?: "<none>"} mediaSequence=$currentSeq")

            var confirmedKey = ByteArray(16)
            var confirmedIvType = -1

            if (firstSegmentUrl != null && candidates.isNotEmpty()) {
                try {
                    val firstSegRes = app.get(firstSegmentUrl, headers = mediaHeaders)
                    val firstSegBytes = firstSegRes.body.bytes()
                    println("[MovieKingPlayer DBG] 🎞 first segment HTTP=${firstSegRes.code} bytes=${firstSegBytes.size}")

                    val ivs = getIvList(currentSeq, hexIv)
                    val checkSize = Math.min(firstSegBytes.size, 188 * 2)

                    outer@ for ((keyIdx, key) in candidates.withIndex()) {
                        for ((ivIdx, iv) in ivs.withIndex()) {
                            try {
                                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                                val head = cipher.update(firstSegBytes.take(checkSize).toByteArray())
                                if (
                                    head.isNotEmpty() &&
                                    head[0] == 0x47.toByte() &&
                                    head.size > 188 &&
                                    head[188] == 0x47.toByte()
                                ) {
                                    confirmedKey = key
                                    confirmedIvType = ivIdx
                                    println("[MovieKingPlayer DBG] ✅ AES key confirmed keyIdx=$keyIdx ivType=$ivIdx")
                                    break@outer
                                }
                            } catch (e: Exception) {
                                // Try the next candidate/IV.
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    println("[MovieKingPlayer DBG] ⚠ first segment/key 검증 실패: ${e.javaClass.simpleName}: ${e.message}")
                }
            }

            if (keyMatch != null && confirmedIvType == -1) {
                println("[MovieKingPlayer DBG] ⚠ AES-128 playlist인데 유효한 키/IV 조합을 확정하지 못함")
            }

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply { start() }

            val newLines = mutableListOf<String>()
            for (line in lines) {
                when {
                    line.startsWith("#EXT-X-KEY") && keyMatch != null -> {
                        var newKeyLine = """#EXT-X-KEY:METHOD=AES-128,URI="http://127.0.0.1:${proxyServer!!.port}/key.bin""""
                        if (confirmedIvType == 0 && hexIv != null) newKeyLine += ",IV=$hexIv"
                        else if (confirmedIvType == 2) newKeyLine += ",IV=0x00000000000000000000000000000000"
                        newLines.add(newKeyLine)
                    }
                    line.isNotBlank() && !line.startsWith("#") -> {
                        newLines.add(resolveUrl(m3u8Url, line.trim()))
                    }
                    else -> newLines.add(line)
                }
            }

            proxyServer!!.updateData(newLines.joinToString("\n"), confirmedKey)
            val localUrl = "http://127.0.0.1:${proxyServer!!.port}/$videoId/playlist.m3u8"
            println("[MovieKingPlayer DBG] 🚀 callback local playlist=$localUrl")

            callback(
                newExtractorLink(name, name, localUrl, ExtractorLinkType.M3U8) {
                    this.referer = "$hostUrl/"
                }
            )
            println("[MovieKingPlayer DBG] ===== extractor success =====")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKingPlayer DBG] ❌ extractor exception: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
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
        println(
            "[MovieKingPlayer DBG] 🧪 $label HTTP=$code final=$finalUrl len=${html.length} " +
                "data-m3u8=${lower.contains("data-m3u8")} dotM3u8=${lower.contains(".m3u8")} " +
                "cf=${lower.contains("cf-challenge") || lower.contains("just a moment")} " +
                "bunny=${lower.contains("bunny.net") || lower.contains("hold tight") || lower.contains("secure connection")} " +
                "resolved=${m3u8Url ?: "<none>"}"
        )
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
        println("[MovieKingPlayer DBG] ℹ videoId payload 미해석 -> fallback=$fallback")
        return "MK_$fallback"
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
        
        val seqIv = ByteArray(16)
        for (i in 0..7) seqIv[15 - i] = (seq shr (i * 8)).toByte()
        ivs.add(seqIv)
        ivs.add(ByteArray(16))
        return ivs
    }

    private suspend fun solveKeyCandidatesCombinatorial(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        return try {
            val res = app.get(kUrl, headers = h)
            println("[MovieKingPlayer DBG] 🔑 key response HTTP=${res.code} final=${res.url} len=${res.text.length}")
            val raw = res.text.trim()
            val json = if (raw.startsWith("{")) {
                raw
            } else {
                try {
                    String(Base64.decode(raw, Base64.DEFAULT))
                } catch (e: Exception) {
                    println("[MovieKingPlayer DBG] ❌ key 응답 Base64 decode 실패: ${e.message}")
                    return emptyList()
                }
            }

            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""")
                .find(json)?.groupValues?.get(1)
            if (encStr == null) {
                println("[MovieKingPlayer DBG] ❌ key 응답에 encrypted_key 없음: ${diagnosticHtml(json)}")
                return emptyList()
            }

            val b64 = try {
                Base64.decode(encStr, Base64.DEFAULT)
            } catch (e: Exception) {
                println("[MovieKingPlayer DBG] ❌ encrypted_key Base64 decode 실패: ${e.message}")
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
                        println("[MovieKingPlayer DBG] ⚠ key candidate 조립 실패: ${e.message}")
                    }
                }
            }

            list.distinctBy { it.contentHashCode() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKingPlayer DBG] ❌ key 처리 예외: ${e.javaClass.simpleName}: ${e.message}")
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
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var finalM3u8: String = ""
        @Volatile private var finalKey: ByteArray = ByteArray(16)

        fun updateData(m3u8: String, key: ByteArray) {
            finalM3u8 = m3u8
            finalKey = key
            println("[MovieKingPlayer DBG] 🧰 proxy data updated: m3u8Len=${m3u8.length}, keyBytes=${key.size}")
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("[MovieKingPlayer DBG] 🧰 local proxy started port=$port")
                CoroutineScope(Dispatchers.IO).launch { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val socket = serverSocket!!.accept()
                            // [수정: 블로킹 I/O 코루틴 스레드 풀 강제 할당 및 예외 대기열 추가]
                            launch(Dispatchers.IO) { handleClient(socket) }
                        } catch (e: Exception) {
                            delay(100L)
                        } 
                    } 
                }
            } catch (e: Exception) {}
        }

        fun stop() { isRunning = false; try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {} }
        
        // [수정: 명시적 I/O 분리 컨텍스트 적용]
        private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return@withContext
                println("[MovieKingPlayer DBG] 🧰 proxy request path=$path")
                val output = socket.getOutputStream()

                // [공통 개선] Connection: close & Content-Length 헤더 명시적 추가
                if (path.contains("/playlist.m3u8")) {
                    val payload = finalM3u8.toByteArray(charset("UTF-8"))
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(payload)
                } else if (path.contains("/key.bin")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nContent-Length: ${finalKey.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(finalKey)
                }
                output.flush()
            } catch (e: Exception) { 
            } finally { try { socket.close() } catch(e2:Exception){} }
        }
    }
}
