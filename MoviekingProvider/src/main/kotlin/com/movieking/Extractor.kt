package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

/**
 * v75: Verified Binary AES Key Reconstruction
 * * [수정 내역]
 * 1. XOR 로직 완전 제거 (1MB 스캔 실패로 비-XOR 확정)
 * 2. 바이트 레벨 슬라이싱: 디코딩된 24바이트에서 4+2 규칙 적용
 * 3. 표준 AES-128 키 전달: 플레이어의 내부 복호화 엔진 활용
 */
class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("=== [MovieKing v75] getUrl Start (Verified Binary AES) ===")
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
            
            // #EXT-X-KEY 라인에서 URI 추출
            val keyUriMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""").find(playlistRes)
            val realKeyUrl = keyUriMatch?.groupValues?.get(1)
            
            val port = proxyServer!!.updateSession(baseHeaders, realKeyUrl)
            val proxyBaseUrl = "http://127.0.0.1:$port"
            
            // [핵심] AES 태그 유지, URI만 우리 프록시로 변경 (플레이어가 이 주소로 키를 요청하게 만듦)
            var m3u8Content = if (realKeyUrl != null) {
                playlistRes.replace(realKeyUrl, "$proxyBaseUrl/key.bin")
            } else playlistRes

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    "$proxyBaseUrl/proxy?url=${URLEncoder.encode(segmentUrl, "UTF-8")}"
                } else line
            }

            proxyServer!!.setPlaylist(m3u8Content)
            callback(newExtractorLink(name, name, "$proxyBaseUrl/playlist.m3u8", ExtractorLinkType.M3U8) { 
                this.referer = "https://player-v1.bcbc.red/"
            })
        } catch (e: Exception) { println("[MovieKing v75] Error: $e") }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var targetKeyUrl: String? = null
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var binaryKeyCache: ByteArray? = null

        fun isAlive() = isRunning && serverSocket != null && !serverSocket!!.isClosed
        fun start() {
            serverSocket = ServerSocket(0).also { port = it.localPort }
            isRunning = true
            thread(isDaemon = true) { while (isAlive()) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} } }
        }
        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun updateSession(h: Map<String, String>, kUrl: String?) = port.also { 
            currentHeaders = h; targetKeyUrl = kUrl; binaryKeyCache = null 
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
                else if (path.contains("/key.bin")) {
                    // 플레이어에게 16바이트 바이너리 키 전달
                    val keyData = getOrFetchBinaryKey()
                    if (keyData != null) {
                        println("[MovieKing v75] Key Reconstructed. Sending 16-byte binary to player.")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(keyData)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                else if (path.contains("/proxy")) {
                    val url = URLDecoder.decode(path.substringAfter("url=").substringBefore(" "), "UTF-8")
                    runBlocking {
                        val res = app.get(url, headers = currentHeaders)
                        if (res.isSuccessful) {
                            output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                            // [중요] 암호화된 TS 원본을 플레이어에 전달 (복호화는 플레이어가 수행)
                            res.body.byteStream().use { it.copyTo(output) }
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { println("[MovieKing v75] Proxy Error: $e") }
        }

        private fun getOrFetchBinaryKey(): ByteArray? {
            if (binaryKeyCache != null) return binaryKeyCache
            val url = targetKeyUrl ?: return null
            return try {
                runBlocking {
                    val jsonStr = app.get(url, headers = currentHeaders).text
                    val key = solveKey(jsonStr)
                    binaryKeyCache = key
                    key
                }
            } catch (e: Exception) { null }
        }

        private fun solveKey(jsonText: String): ByteArray? {
            return try {
                val decodedJson = if (jsonText.startsWith("{")) jsonText else String(Base64.decode(jsonText, Base64.DEFAULT))
                val encKeyStr = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(decodedJson)?.groupValues?.get(1) ?: return null
                val ruleJson = Regex(""""rule"\s*:\s*(\{.*?\})""").find(decodedJson)?.groupValues?.get(1) ?: return null
                val perm = Regex(""""permutation"\s*:\s*\[([\d,]+)\]""").find(ruleJson)?.groupValues?.get(1)?.split(",")?.map { it.trim().toInt() } ?: listOf(0,1,2,3)
                
                // [팩트: 바이너리 기반 조립]
                // 1. 32자 문자열을 디코딩하여 24바이트 바이너리 획득
                val rawBytes = Base64.decode(encKeyStr, Base64.DEFAULT)
                val segments = mutableListOf<ByteArray>()
                
                // 2. 4바이트 데이터 + 2바이트 노이즈 규칙으로 4개 세그먼트 추출
                for (i in 0 until 4) {
                    val start = i * (4 + 2) // 4 data + 2 noise
                    if (start + 4 <= rawBytes.size) {
                        segments.add(rawBytes.copyOfRange(start, start + 4))
                    }
                }
                
                if (segments.size < 4) return null

                // 3. 순열에 맞춰 16바이트 AES 키 조립
                val finalKey = ByteArray(16)
                for (i in 0 until 4) {
                    System.arraycopy(segments[perm[i]], 0, finalKey, i * 4, 4)
                }
                
                println("[MovieKing v75] AES Key Solved: ${finalKey.joinToString("") { "%02X".format(it) }}")
                finalKey
            } catch (e: Exception) { null }
        }
    }
}
