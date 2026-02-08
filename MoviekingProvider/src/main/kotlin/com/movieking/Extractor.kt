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
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
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
        println("=== [MovieKing v4] getUrl Start (Debug Mode) ===")
        
        try {
            // 1. 헤더 설정
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

            // 3. M3U8 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing v4] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }
            println("[MovieKing v4] M3U8 URL found: $m3u8Url")

            // 4. UA 추출
            val extractedUA = extractUserAgentFromToken(m3u8Url)
            val targetUA = extractedUA ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
            baseHeaders["User-Agent"] = targetUA
            println("[MovieKing v4] Using UA: $targetUA")

            // 5. M3U8 다운로드
            val m3u8Response = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = m3u8Response.text

            // 6. 키 처리 (상세 로그 추가)
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = baseHeaders)
                println("[MovieKing v4] Key Response Length: ${keyResponse.text.length}")
                
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    // 키 검증용 Hex 출력
                    val keyHex = actualKeyBytes.joinToString("") { "%02x".format(it) }
                    println("[MovieKing v4] Decrypted Key (Hex): $keyHex")
                } else {
                    println("[MovieKing v4] Key Decryption FAILED")
                }
            }

            // 7. 프록시 서버 시작
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
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
            println("[MovieKing v4] Local Playlist Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v4] Error: ${e.message}")
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
            
            // 디버그용: JSON 구조 확인
            // println("[MovieKing v4] Raw Key JSON: $decodedJsonStr")

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
        } catch (e: Exception) { 
            println("[MovieKing v4] Key Decrypt Error: ${e.message}")
            null 
        }
    }
    
    // --- Proxy Web Server (Debug Enhanced) ---
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
                            if (isRunning) println("[MovieKing v4] Accept Error: ${e.message}")
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
                    
                    // 클라이언트 헤더 읽기 (Range 헤더 확인용)
                    val clientHeaders = mutableMapOf<String, String>()
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                        val parts = line!!.split(": ", limit = 2)
                        if (parts.size == 2) {
                            clientHeaders[parts[0]] = parts[1]
                        }
                    }
                    val rangeHeader = clientHeaders["Range"]
                    
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
                            
                            println("[MovieKing v4] Proxy Requesting: $targetUrl")
                            if (rangeHeader != null) println("[MovieKing v4] Client Range: $rangeHeader")
                            
                            try {
                                val connection = URL(targetUrl).openConnection() as HttpURLConnection
                                connection.requestMethod = "GET"
                                connection.connectTimeout = 15000
                                connection.readTimeout = 15000
                                
                                // 헤더 포워딩
                                currentHeaders.forEach { (k, v) -> connection.setRequestProperty(k, v) }
                                if (rangeHeader != null) connection.setRequestProperty("Range", rangeHeader)

                                connection.connect()

                                val responseCode = connection.responseCode
                                val contentType = connection.contentType
                                
                                // [분석 핵심] 서버 응답 로그
                                println("[MovieKing v4] Upstream Response: $responseCode ($contentType)")

                                if (responseCode == 200 || responseCode == 206) {
                                    val inputStream = connection.inputStream
                                    
                                    // [분석 핵심] 데이터 앞부분 찍어보기 (HTML인지 확인)
                                    val buffer = ByteArray(8192)
                                    val bytesRead = inputStream.read(buffer)
                                    
                                    if (bytesRead != -1) {
                                        // 처음 50바이트를 텍스트로 변환해 로그 출력
                                        val preview = String(buffer, 0, minOf(bytesRead, 100))
                                        println("[MovieKing v4] Data Preview: $preview")
                                        
                                        // 응답 시작
                                        val header = "HTTP/1.1 $responseCode OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n"
                                        output.write(header.toByteArray())
                                        
                                        // 읽은 첫 버퍼 전송
                                        output.write(buffer, 0, bytesRead)
                                        
                                        // 나머지 전송
                                        var count: Int
                                        while (inputStream.read(buffer).also { count = it } != -1) {
                                            output.write(buffer, 0, count)
                                        }
                                    } else {
                                        // 빈 응답
                                        println("[MovieKing v4] Empty Response from upstream")
                                        output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                                    }
                                    
                                    output.flush()
                                    inputStream.close()
                                } else {
                                    output.write("HTTP/1.1 $responseCode Error\r\n\r\n".toByteArray())
                                    println("[MovieKing v4] Remote Error: $responseCode")
                                }
                                connection.disconnect()
                            } catch (e: Exception) {
                                println("[MovieKing v4] Stream Error: ${e.message}")
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
