package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v86: Deep-ID Debugging Mode
 * [수정 사항]
 * 1. ID/EID 추출 전과정 로깅 (타임스탬프 원인 파악용)
 * 2. URL 디코딩 후 재검색 로직 추가 (인코딩된 파라미터 대응)
 * 3. 타임스탬프 백업 제거: 실패 시 "FAILED_ID"로 표시하여 즉시 확인 가능하게 변경
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v86] getUrl Start ===")
        println("[MovieKing v86] 1. Original Input URL: $url")
        
        try {
            // [추출 로직 강화] URL 디코딩 후 재분석 (파라미터가 겹쳐있는 경우 대비)
            val decodedUrl = URLDecoder.decode(url, "UTF-8")
            if (decodedUrl != url) println("[MovieKing v86] 2. Decoded URL for analysis: $decodedUrl")

            val idMatch = Regex("""[?&]id=([^&]+)""").find(decodedUrl)
            val eidMatch = Regex("""[?&]eid=([^&]+)""").find(decodedUrl)
            
            val id = idMatch?.groupValues?.get(1)
            val eid = eidMatch?.groupValues?.get(1)
            
            println("[MovieKing v86] 3. Extraction Result -> ID: ${id ?: "NOT_FOUND"}, EID: ${eid ?: "NOT_FOUND"}")

            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            println("[MovieKing v86] 4. Detected M3U8 URL: $m3u8Url")

            // 경로 고유값 결정 (id_eid 우선)
            val videoId = when {
                id != null && eid != null -> "${id}_$eid"
                id != null -> id
                eid != null -> "e$eid"
                else -> {
                    // M3U8 경로에서라도 숫자 추출 시도
                    val pathId = Regex("""/video/(\d+)""").find(m3u8Url)?.groupValues?.get(1)
                    if (pathId != null) {
                        println("[MovieKing v86] ID not in params, extracted from path: $pathId")
                        pathId
                    } else {
                        val fallback = "UNKNOWN_" + url.hashCode().toString().takeLast(6)
                        println("[MovieKing v86] WARNING: ID extraction failed. Using fallback: $fallback")
                        fallback
                    }
                }
            }

            println("[MovieKing v86] 5. Final Determined VideoID: $videoId")

            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val candidates = if (keyMatch != null) solveKeyCandidatesSync(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates)
            
            val port = proxyServer!!.port
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            // 프록시 경로 구성 로깅
            val proxyRoot = "http://127.0.0.1:$port/$videoId"
            println("[MovieKing v86] 6. Constructing Proxy Path: $proxyRoot/playlist.m3u8")

            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            
            println("[MovieKing v86] 7. Callback complete. Final Playlist ready.")
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v86] FATAL Error: $e") }
    }

    private suspend fun solveKeyCandidatesSync(h: Map<String, String>, kUrl: String): List<ByteArray> {
        return try {
            val jsonStr = app.get(kUrl, headers = h).text
            val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
            val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return emptyList()
            val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return emptyList()
            
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            val sources = mutableListOf<ByteArray>()
            try { sources.add(Base64.decode(encKeyStr, Base64.DEFAULT)) } catch (e: Exception) {}
            sources.add(encKeyStr.toByteArray())

            sources.mapNotNull { src ->
                val segments = mutableListOf<ByteArray>()
                for (i in 0 until 4) {
                    val start = i * (size + noise)
                    if (start + size <= src.size) segments.add(src.copyOfRange(start, start + size))
                }
                if (segments.size == 4) {
                    val k = ByteArray(16)
                    for (j in 0 until 4) System.arraycopy(segments[perm[j]], 0, k, j * 4, 4)
                    k
                } else null
            }
        } catch (e: Exception) { emptyList() }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var keyCandidates: List<ByteArray> = emptyList()
        @Volatile private var confirmedKey: ByteArray? = null
        @Volatile private var confirmedIvMode: Int = -1
        @Volatile private var confirmedOffset: Int = 0

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, iv: String?, k: List<ByteArray>) {
            currentHeaders = h; playlistIv = iv; keyCandidates = k
            confirmedKey = null; confirmedIvMode = -1; confirmedOffset = 0
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                // [디버그] 실제 플레이어로부터 들어오는 요청 경로 확인
                if (path.contains("/playlist.m3u8")) {
                    println("[MovieKing v86] Proxy Received Request for M3U8: $path")
                    output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    val seq = Regex("""(\d+)\.ts""").find(targetUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            if (confirmedKey == null) findJackpot(rawData, seq)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (confirmedKey != null) {
                                val result = decryptAes(rawData, confirmedKey!!, getIv(confirmedIvMode, seq))
                                if (result.size > confirmedOffset) output.write(result, confirmedOffset, result.size - confirmedOffset)
                            } else {
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v86] Proxy Error: $e") }
        }

        private fun findJackpot(data: ByteArray, seq: Long) {
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    try {
                        val decrypted = decryptAes(data.take(8192).toByteArray(), key, getIv(mode, seq))
                        for (off in 0..2048) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off + 188] == 0x47.toByte()) {
                                println("[MovieKing v86] JACKPOT! Mode: $mode, Offset: $off")
                                confirmedKey = key; confirmedIvMode = mode; confirmedOffset = off
                                return
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        private fun getIv(mode: Int, seq: Long): ByteArray {
            val iv = ByteArray(16)
            when (mode) {
                0 -> {
                    val hex = playlistIv?.removePrefix("0x") ?: ""
                    try { hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() } } catch(e:Exception) {}
                }
                1 -> for (i in 0..7) iv[15 - i] = (seq shr (i * 8)).toByte()
            }
            return iv
        }

        private fun decryptAes(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}
