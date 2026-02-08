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
 * v79: Dual-Key Auto-Match Mode
 * [해결 원인]
 * 1. 키 재료가 'Base64'인 경우와 'Raw String'인 경우를 모두 지원 (시도 3-5번 실패 해결)
 * 2. 3가지 IV(Tag, Seq, Zero)와 2가지 KeySource를 조합하여 0x47을 찾는 자동 매칭 엔진 탑재
 * 3. 3001 에러 원천 차단
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v79] getUrl Start (Dual-Key Auto Mode) ===")
        try {
            val baseHeaders = mutableMapOf("Referer" to "https://player-v1.bcbc.red/", "Origin" to "https://player-v1.bcbc.red")
            val playerHtml = app.get(url, headers = baseHeaders).text
            val m3u8Url = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)?.groupValues?.get(1)?.replace("\\/", "/") ?: return
            
            val playlistRes = app.get(m3u8Url, headers = baseHeaders).text
            
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=0x([0-9a-fA-F]+))?""").find(playlistRes)
            val port = proxyServer!!.updateSession(baseHeaders, keyMatch?.groupValues?.get(1), keyMatch?.groupValues?.get(2))
            
            var m3u8Content = playlistRes.lines().filterNot { it.contains("#EXT-X-KEY") }.joinToString("\n")
            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "http://127.0.0.1:$port/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "http://127.0.0.1:$port/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player-v1.bcbc.red/" })
        } catch (e: Exception) { println("[MovieKing v79] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var targetKeyUrl: String? = null
        @Volatile private var playlistIv: String? = null
        @Volatile private var currentPlaylist: String = ""
        
        // 정답 조합 캐시
        @Volatile private var finalKey: ByteArray? = null
        @Volatile private var finalIvMode: Int = -1 // 0: Tag, 1: Seq, 2: Zero

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, kUrl: String?, iv: String?) = port.also { 
            currentHeaders = h; targetKeyUrl = kUrl; playlistIv = iv; finalKey = null; finalIvMode = -1
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
                } 
                else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    runBlocking {
                        val res = app.get(targetUrl, headers = currentHeaders)
                        if (res.isSuccessful) {
                            val rawData = res.body.bytes()
                            val seq = Regex("""(\d+)\.ts""").find(targetUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
                            
                            // [핵심] 정답을 찾을 때까지 모든 조합 테스트
                            if (finalKey == null) findWinningCombo(rawData, seq)

                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                            if (finalKey != null) {
                                val iv = getIvByMode(finalIvMode, seq)
                                output.write(decryptAes(rawData, finalKey!!, iv))
                            } else {
                                output.write(rawData)
                            }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v79] Proxy Error: $e") }
        }

        private suspend fun findWinningCombo(data: ByteArray, seq: Long) {
            val url = targetKeyUrl ?: return
            val jsonStr = app.get(url, headers = currentHeaders).text
            val decodedJson = if (jsonStr.startsWith("{")) jsonStr else String(Base64.decode(jsonStr, Base64.DEFAULT))
            
            val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return
            val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return
            val noise = Regex(""""noise_length"\s*:\s*(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 2
            val size = Regex(""""segment_sizes"\s*:\s*\[(\d+)""").find(ruleJson)?.groupValues?.get(1)?.toInt() ?: 4
            val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)

            // 후보 1: Base64 디코딩 기반 / 후보 2: Raw 문자열 기반
            val sources = mutableListOf<ByteArray>()
            try { sources.add(Base64.decode(encKeyStr, Base64.DEFAULT)) } catch (e: Exception) {}
            sources.add(encKeyStr.toByteArray())

            for (src in sources) {
                val segments = mutableListOf<ByteArray>()
                for (i in 0 until 4) {
                    val start = i * (size + noise)
                    if (start + size <= src.size) segments.add(src.copyOfRange(start, start + size))
                }
                if (segments.size < 4) continue
                
                val keyCandidate = ByteArray(16)
                for (i in 0 until 4) System.arraycopy(segments[perm[i]], 0, keyCandidate, i * 4, 4)

                // 3가지 IV 모드 테스트
                for (mode in 0..2) {
                    val iv = getIvByMode(mode, seq)
                    val result = decryptAes(data.take(1024).toByteArray(), keyCandidate, iv)
                    if (result.isNotEmpty() && result[0] == 0x47.toByte()) {
                        println("[MovieKing v79] JACKPOT! Mode: $mode, KeySource: ${if(src.size > 20) "B64" else "Raw"}")
                        finalKey = keyCandidate
                        finalIvMode = mode
                        return
                    }
                }
            }
        }

        private fun getIvByMode(mode: Int, seq: Long): ByteArray {
            return when (mode) {
                0 -> { // Tag IV
                    val hex = playlistIv ?: ""
                    val clean = if (hex.startsWith("0x")) hex.substring(2) else hex
                    try { clean.chunked(2).take(16).map { it.toInt(16).toByte() }.toByteArray() } catch(e:Exception) { ByteArray(16) }
                }
                1 -> { // Seq IV
                    ByteArray(16).apply { for (i in 0..7) this[15 - i] = (seq shr (i * 8)).toByte() }
                }
                else -> ByteArray(16) // Zero IV
            }
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
