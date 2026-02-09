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
 * v100: v87-Rollback & Final Diagnostic Mode
 * [변경 사항]
 * 1. 로직 롤백: 성공 사례가 있던 v87의 JWT ID 추출 및 표준 키 조립 적용
 * 2. 1MB 딥 스캔: 가비지 대응을 위해 탐색 범위 1MB 확장
 * 3. 정밀 로그: RAW HEADER, Key JSON, Winning Key, Offset, 재생 원인(복호화 vs 통과) 전수 기록
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v100] getUrl Start (v87 Rollback) ===")
        try {
            val videoId = extractVideoIdDeep(url)
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red", "User-Agent" to DESKTOP_UA)
            
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop(); proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            // v87 표준 규칙 키 조립 로직
            val candidates = if (keyMatch != null) solveKeyV87(baseHeaders, keyMatch.groupValues[1]) else emptyList()
            proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(2), candidates)
            
            val port = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$port/$videoId"
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyRoot/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v100] FATAL Error: $e") }
    }

    private fun extractVideoIdDeep(url: String): String {
        try {
            val token = url.split("/v1/").getOrNull(1)?.split(".")?.getOrNull(1)
            if (token != null) {
                val decoded = String(Base64.decode(token, Base64.URL_SAFE))
                return Regex(""""id"\s*:\s*(\d+)""").find(decoded)?.groupValues?.get(1) ?: "ID_ERR"
            }
        } catch (e: Exception) {}
        return "ID_ERR"
    }

    private suspend fun solveKeyV87(h: Map<String, String>, kUrl: String): List<ByteArray> {
        val list = mutableListOf<ByteArray>()
        try {
            val res = app.get(kUrl, headers = h).text
            val json = if (res.startsWith("{")) res else String(Base64.decode(res, Base64.DEFAULT))
            // [로그 1] Key JSON 출력
            println("[MovieKing v100] Key JSON: $json")
            
            val encStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(json)?.groupValues?.get(1) ?: return emptyList()
            val rule = Regex(""""rule"\s*:\s*(\{.*?\})""").find(json)?.groupValues?.get(1) ?: ""
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(rule)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(rule)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            val b64 = try { Base64.decode(encStr, Base64.DEFAULT) } catch (e: Exception) { byteArrayOf() }
            val raw = encStr.toByteArray()

            listOf(b64, raw).forEach { src ->
                val segments = mutableListOf<ByteArray>()
                var offset = 0
                for (i in 0 until 4) {
                    if (offset + size <= src.size) {
                        segments.add(src.copyOfRange(offset, offset + size))
                        offset += (size + noise)
                    }
                }
                if (segments.size == 4) {
                    val k = ByteArray(16)
                    for (j in 0 until 4) System.arraycopy(segments[perm[j]], 0, k, j * 4, 4)
                    list.add(k)
                }
            }
        } catch (e: Exception) {}
        return list.distinctBy { it.contentHashCode() }
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

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray() + currentPlaylist.toByteArray())
                } else if (path.contains("/proxy")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            // [로그 2] RAW HEADER 출력 (64바이트)
                            val hexHeader = rawData.take(64).joinToString(" ") { "%02X".format(it) }
                            println("[MovieKing v100] RAW HEADER: $hexHeader")

                            if (confirmedKey == null && confirmedOffset == 0) findJackpotDeep(rawData)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (confirmedKey != null) {
                                // [로그 3, 4] 복호화 성공 및 경로 로그
                                println("[MovieKing v100] Sending DECRYPTED data (Key & Offset matched)")
                                val result = decryptAes(rawData, confirmedKey!!, getIv(confirmedIvMode))
                                if (result.size > confirmedOffset) output.write(result, confirmedOffset, result.size - confirmedOffset)
                            } else if (confirmedOffset > 0) {
                                println("[MovieKing v100] Sending PLAIN data (Only Offset matched)")
                                output.write(rawData, confirmedOffset, rawData.size - confirmedOffset)
                            } else {
                                // [중요] JACKPOT을 못 찾았을 때의 처리 (v87 성공의 원인)
                                println("[MovieKing v100] JACKPOT NOT FOUND. Passing through RAW data (Fallback Mode)")
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v100] Proxy Error: $e") }
        }

        private fun findJackpotDeep(data: ByteArray) {
            val scanLimit = minOf(data.size - 200, 1048576) // 1MB 스캔
            
            // 1. Plain TS 검증
            for (off in 0..scanLimit) {
                if (data[off] == 0x47.toByte() && data[off+188] == 0x47.toByte()) {
                    println("[MovieKing v100] PLAIN SYNC FOUND. Offset: $off")
                    confirmedOffset = off; return
                }
            }

            // 2. AES 복호화 검증
            for (key in keyCandidates) {
                for (mode in 0..1) {
                    try {
                        val decrypted = decryptAes(data.take(scanLimit + 200).toByteArray(), key, getIv(mode))
                        for (off in 0..scanLimit) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off+188] == 0x47.toByte()) {
                                // [로그 5, 6] 정답 키 및 오프셋 출력
                                println("[MovieKing v100] JACKPOT! Offset: $off, Mode: $mode")
                                println("[MovieKing v100] Winning Key (Hex): ${key.joinToString(""){ "%02X".format(it) }}")
                                confirmedKey = key; confirmedIvMode = mode; confirmedOffset = off; return
                            }
                        }
                    } catch (e: Exception) {}
                }
            }
        }

        private fun getIv(mode: Int): ByteArray {
            val iv = ByteArray(16)
            if (mode == 0) {
                val hex = playlistIv?.removePrefix("0x") ?: ""
                try { hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() } } catch(e:Exception) {}
            }
            return iv
        }

        private fun decryptAes(d: ByteArray, k: ByteArray, v: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(k, "AES"), IvParameterSpec(v))
            return cipher.doFinal(d)
        }
    }
}
