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
 * v82: Dynamic Path Isolation Mode
 * [수정 사항]
 * 1. 고유 ID 기반 경로: /playlist.m3u8 대신 /ID/playlist.m3u8 사용 (영상별 구분 가능)
 * 2. v81의 Strict Sync 로직 및 IV 자동 매칭 엔진 유지
 * 3. 플레이어 캐시 및 로그 가독성 개선
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        // [추가] URL에서 고유 ID 추출하여 경로 구분
        val videoId = Regex("id=(\\d+)").find(url)?.groupValues?.get(1) ?: System.currentTimeMillis().toString()
        println("=== [MovieKing v82] getUrl Start (ID: $videoId) ===")
        
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply { start() }
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val realKeyUrl = keyMatch?.groupValues?.get(1)
            val tagIv = keyMatch?.groupValues?.get(2)

            // 키 조립 완료 후 진행 (v81 Strict Sync)
            val candidates = if (realKeyUrl != null) solveKeyCandidatesSync(baseHeaders, realKeyUrl) else emptyList()
            proxyServer!!.updateSession(baseHeaders, tagIv, candidates)
            
            val port = proxyServer!!.port
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            
            // 프록시 경로에 videoId 포함
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "http://127.0.0.1:$port/$videoId/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            
            // [결과] 이제 고정된 이름이 아닌 고유 ID가 포함된 M3U8 주소 반환
            callback(newExtractorLink(name, name, "http://127.0.0.1:$port/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/" 
            })
        } catch (e: Exception) { println("[MovieKing v82] Error: $e") }
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

                // path.contains()를 사용하므로 ID가 앞에 붙어도 정상 작동함
                if (path.contains("/playlist.m3u8")) {
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
            } catch (e: Exception) { println("[MovieKing v82] Proxy Error: $e") }
        }

        private fun findJackpot(data: ByteArray, seq: Long) {
            for (key in keyCandidates) {
                for (mode in 0..2) {
                    try {
                        val decrypted = decryptAes(data.take(8192).toByteArray(), key, getIv(mode, seq))
                        for (off in 0..2048) {
                            if (decrypted[off] == 0x47.toByte() && decrypted[off + 188] == 0x47.toByte()) {
                                println("[MovieKing v82] JACKPOT! Mode: $mode, Offset: $off")
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
