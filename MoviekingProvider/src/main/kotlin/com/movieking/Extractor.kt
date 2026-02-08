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
import java.net.URL
import java.net.HttpURLConnection
import java.net.URLEncoder
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
        println("=== [MovieKing v2] getUrl Start ===")
        
        try {
            // 1. 기본 헤더 준비
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. WebView 요청
            val playerResponse = try {
                app.get(
                    url,
                    headers = baseHeaders,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = baseHeaders)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            baseHeaders["Cookie"] = cookieString

            // 3. M3U8 파싱
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing v2] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 4. UA 추출
            val extractedUA = extractUserAgentFromToken(m3u8Url)
            val targetUA = extractedUA ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            baseHeaders["User-Agent"] = targetUA

            // 5. M3U8 다운로드
            val m3u8Response = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = m3u8Response.text

            // 6. 키 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                if (actualKeyBytes != null) println("[MovieKing v2] Key decrypted.")
            }

            // 7. [핵심 수정] 서버 상태 정밀 검사 (좀비 방지)
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                println("[MovieKing v2] Server is dead or null. Starting NEW server...")
                // 혹시 모르니 기존꺼 확실히 죽임
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            } else {
                println("[MovieKing v2] Server is ALIVE. Reusing port ${proxyServer!!.port}")
            }
            
            // 데이터 업데이트
            val port = proxyServer!!.updateSession(baseHeaders, actualKeyBytes)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            // 8. M3U8 변조
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

            // 9. 데이터 등록
            proxyServer!!.setPlaylist(m3u8Content)
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v2] Error: ${e.message}")
        }
    }

    // --- Helper Functions ---
    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"([^"]+)"""").find(payload)
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
    
    // --- Proxy Web Server (Health Check 추가) ---
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentKey: ByteArray? = null
        @Volatile private var currentPlaylist: String = ""

        // [중요] 좀비 판별 함수
        fun isAlive(): Boolean {
            return isRunning && serverSocket != null && !serverSocket!!.isClosed
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket?.localPort ?: 0
                isRunning = true
                println("[MovieKing v2] Server Started on $port")
                
                thread(start = true, isDaemon = true) {
                    while (isAlive()) { // 살아있는 동안만 루프
                        try {
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            if (isRunning) println("[MovieKing v2] Accept Error (might be closed): ${e.message}")
                        }
                    }
                    println("[MovieKing v2] Server Loop Ended")
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
            try { 
                serverSocket?.close() 
                println("[MovieKing v2] Server Stopped")
            } catch (e: Exception) { }
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
                            val targetUrl = java.net.URLDecoder.decode(urlParam, "UTF-8")
                            
                            try {
                                val connection = URL(targetUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 15000
                                connection.readTimeout = 15000
                                
                                currentHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                                connection.connect()

                                val responseCode = connection.responseCode
                                if (responseCode == 200 || responseCode == 206) {
                                    val inputStream = connection.inputStream
                                    val header = "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n"
                                    output.write(header.toByteArray())
                                    
                                    val buffer = ByteArray(8192)
                                    var count: Int
                                    while (inputStream.read(buffer).also { count = it } != -1) {
                                        output.write(buffer, 0, count)
                                    }
                                    output.flush()
                                    inputStream.close()
                                } else {
                                    output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                                    println("[MovieKing v2] Remote Error $responseCode: $targetUrl")
                                }
                                connection.disconnect()
                            } catch (e: Exception) {
                                println("[MovieKing v2] Stream Error: ${e.message}")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }
}
