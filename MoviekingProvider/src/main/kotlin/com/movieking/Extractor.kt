package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing v21] getUrl Start (Forensic Mode) ===")
        
        try {
            // 1. 헤더 준비
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. WebView 요청 (토큰 및 쿠키 획득)
            try {
                app.get(
                    url,
                    headers = baseHeaders,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = baseHeaders)
            }

            // 3. M3U8 다운로드
            val m3u8Response = app.get(url, headers = baseHeaders)
            val playerHtml = m3u8Response.text
            
            // 쿠키 확보
            val cookies = m3u8Response.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            baseHeaders["Cookie"] = cookieString

            // 4. M3U8 주소 파싱
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing v21] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 5. UA 추출
            val chromeVersion = extractChromeVersion(m3u8Url) ?: "124.0.0.0"
            val standardUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
            baseHeaders["User-Agent"] = standardUA
            println("[MovieKing v21] UA: $standardUA")

            // 6. 진짜 M3U8 다운로드
            val playlistResponse = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = playlistResponse.text

            // 7. 키(Key) 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                if (actualKeyBytes != null) println("[MovieKing v21] Key Decrypted.")
            }

            // 8. 프록시 서버 시작
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val port = proxyServer!!.updateSession(baseHeaders, actualKeyBytes)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            // 9. M3U8 변조 (모든 트래픽을 프록시로 유도하여 감식)
            if (keyMatch != null && actualKeyBytes != null) {
                val localKeyUrl = "$proxyBaseUrl/key.bin"
                m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], localKeyUrl)
            }

            val m3u8Base = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = if (line.startsWith("http")) line else "$m3u8Base$line"
                    val encodedUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                    "$proxyBaseUrl/proxy?url=$encodedUrl"
                } else {
                    line
                }
            }

            // 10. 재생 요청
            proxyServer!!.setPlaylist(m3u8Content)
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"
            println("[MovieKing v21] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = baseHeaders // 프록시가 무시하더라도 혹시 모르니 전달
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v21] Error: ${e.message}")
        }
    }

    // --- Helper Functions ---
    private fun extractChromeVersion(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"Chrome\(([^)]+)\)"""").find(payload)
            uaMatch?.groupValues?.get(1)
        } catch (e: Exception) { null }
    }

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        return try {
            val decodedJsonStr = try { String(Base64.decode(jsonText, Base64.DEFAULT)) } catch (e: Exception) { jsonText }
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            val cleanBytes = encryptedBytes.drop(2).toByteArray()
            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }
            val segments = listOf(cleanBytes.copyOfRange(0, 4), cleanBytes.copyOfRange(4, 8), cleanBytes.copyOfRange(8, 12), cleanBytes.copyOfRange(12, 16))
            val resultKey = ByteArray(16)
            var offset = 0
            for (idx in permutation) {
                val seg = segments[idx]
                System.arraycopy(seg, 0, resultKey, offset, 4)
                offset += 4
            }
            resultKey
        } catch (e: Exception) { null }
    }
    
    // --- Proxy Web Server (Forensic Mode) ---
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentKey: ByteArray? = null
        @Volatile private var currentPlaylist: String = ""

        fun isAlive(): Boolean = isRunning && serverSocket != null && !serverSocket!!.isClosed

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket?.localPort ?: 0
                isRunning = true
                thread(start = true, isDaemon = true) {
                    while (isAlive()) {
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            if (isRunning) println("[MovieKing v21] Accept Error: ${e.message}")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                isRunning = false
            }
        }

        fun updateSession(headers: Map<String, String>, key: ByteArray?): Int {
            currentHeaders = headers
            currentKey = key
            return port
        }

        fun setPlaylist(m3u8: String) {
            currentPlaylist = m3u8
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (e: Exception) { }
        }

        private fun handleClient(socket: Socket) {
            thread(start = true) {
                try {
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val requestLine = reader.readLine() ?: return@thread
                    
                    val parts = requestLine.split(" ")
                    if (parts.size >= 2) {
                        val path = parts[1]
                        val output = socket.getOutputStream()
                        
                        if (path.contains("/playlist.m3u8")) {
                            val data = currentPlaylist.toByteArray()
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(data)
                            output.flush()
                        }
                        else if (path.contains("/key.bin") && currentKey != null) {
                            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${currentKey!!.size}\r\nConnection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(currentKey)
                            output.flush()
                        }
                        else if (path.contains("/proxy")) {
                            val urlParam = path.substringAfter("url=").substringBefore(" ")
                            val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                            
                            try {
                                // [증거 수집 1] 요청 정보 기록
                                // println("[MovieKing v21] Requesting TS: $targetUrl")
                                
                                val responseBytes = runBlocking {
                                    // 도메인 체크 로직 (이전 성공 요인)
                                    val safeHeaders = if (targetUrl.contains("bcbc.red")) {
                                        currentHeaders
                                    } else {
                                        mapOf("User-Agent" to (currentHeaders["User-Agent"] ?: ""))
                                    }
                                    
                                    // [증거 수집 2] 헤더 기록
                                    // println("[MovieKing v21] Headers sent: ${safeHeaders.keys}")

                                    val res = app.get(targetUrl, headers = safeHeaders)
                                    
                                    // [증거 수집 3] 응답 코드 및 타입 기록
                                    if (!res.isSuccessful) {
                                        println("[MovieKing v21] SERVER ERROR: ${res.code}")
                                    }
                                    // Content-Type 확인
                                    // println("[MovieKing v21] Content-Type: ${res.headers["Content-Type"]}")
                                    
                                    if (res.isSuccessful) res.body.bytes() else null
                                }
                                
                                if (responseBytes != null && responseBytes.isNotEmpty()) {
                                    // [증거 수집 4] 데이터 내용 정밀 분석 (여기가 핵심)
                                    val hexSnippet = responseBytes.take(4).joinToString(" ") { "%02X".format(it) }
                                    val textSnippet = String(responseBytes.take(50).toByteArray())
                                    
                                    println("[MovieKing v21] DATA DUMP | Size: ${responseBytes.size} | Hex: [$hexSnippet] | Text: $textSnippet")
                                    
                                    if (hexSnippet.startsWith("47")) {
                                        // 0x47 = MPEG-TS Sync Byte (정상)
                                        println("[MovieKing v21] VERDICT: Valid TS Stream detected.")
                                    } else if (textSnippet.trim().startsWith("<")) {
                                        // HTML 감지
                                        println("[MovieKing v21] VERDICT: BLOCKED (HTML received).")
                                    } else {
                                        // 알 수 없는 데이터
                                        println("[MovieKing v21] VERDICT: UNKNOWN/CORRUPT data.")
                                    }

                                    // 플레이어에게 전송
                                    val header = "HTTP/1.1 200 OK\r\n" +
                                            "Content-Type: video/mp2t\r\n" +
                                            "Content-Length: ${responseBytes.size}\r\n" +
                                            "Connection: close\r\n\r\n"
                                    output.write(header.toByteArray())
                                    output.write(responseBytes)
                                    output.flush()
                                } else {
                                    println("[MovieKing v21] FAIL: Empty response or Connection Reset")
                                    output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                }
                            } catch (e: Exception) {
                                println("[MovieKing v21] EXCEPTION: $e")
                                // e.printStackTrace() // 필요 시 주석 해제
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v21] Socket Error: $e")
                }
            }
        }
    }
}
