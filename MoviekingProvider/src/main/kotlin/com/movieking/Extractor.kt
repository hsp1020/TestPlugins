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
import kotlin.concurrent.thread
import okhttp3.Request

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
        println("=== [MovieKing v11] getUrl Start (Key Fix) ===")
        
        try {
            // 1. 기본 헤더 (브라우저 탐색용)
            val browserHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )

            // 2. WebView (토큰 획득)
            val playerResponse = try {
                app.get(
                    url,
                    headers = browserHeaders,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = browserHeaders)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 모든 요청에 공통으로 쓸 헤더
            browserHeaders["Cookie"] = cookieString

            // 3. M3U8 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing v11] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 4. UA 표준화
            val chromeVersion = extractChromeVersion(m3u8Url) ?: "124.0.0.0"
            val standardUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
            browserHeaders["User-Agent"] = standardUA
            println("[MovieKing v11] UA: $standardUA")

            // 5. M3U8 다운로드
            val m3u8Response = app.get(m3u8Url, headers = browserHeaders)
            var m3u8Content = m3u8Response.text

            // 6. 키 처리 (핵심 수정)
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                println("[MovieKing v11] Found Key URL: $keyUrl")

                // [수정] 키 다운로드 시에는 보안 헤더를 좀 줄여서 보냄 (API 호환성)
                val keyHeaders = mapOf(
                    "User-Agent" to standardUA,
                    "Cookie" to cookieString,
                    "Referer" to "https://player-v1.bcbc.red/",
                    "Origin" to "https://player-v1.bcbc.red"
                )

                val keyResponse = app.get(keyUrl, headers = keyHeaders)
                // 내용 확인 로그
                // println("[MovieKing v11] Key Response: ${keyResponse.text}")
                
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    println("[MovieKing v11] Key Decrypted Successfully (${actualKeyBytes.size} bytes)")
                } else {
                    println("[MovieKing v11] Key Decryption FAILED! Response might be HTML or invalid.")
                    // 키 해독 실패 시 더 진행하지 않고 리턴하는 게 나을 수 있음
                    // 하지만 혹시 모르니 진행은 하되 로그 남김
                }
            }

            // 7. 프록시 서버 시작
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            val port = proxyServer!!.updateSession(browserHeaders, actualKeyBytes)
            val proxyBaseUrl = "http://127.0.0.1:$port"

            // 8. M3U8 변조
            if (keyMatch != null && actualKeyBytes != null) {
                val localKeyUrl = "$proxyBaseUrl/key.bin"
                m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], localKeyUrl)
                println("[MovieKing v11] M3U8 Key URI replaced with Proxy URL")
            } else if (keyMatch != null) {
                println("[MovieKing v11] WARNING: Key not replaced because decryption failed.")
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

            // 9. 재생 요청
            proxyServer!!.setPlaylist(m3u8Content)
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"
            println("[MovieKing v11] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = browserHeaders
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v11] Error: ${e.message}")
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
            
            // JSON 파싱 시도
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
            println("[MovieKing v11] Decrypt Error: ${e.message}")
            null 
        }
    }
    
    // --- Proxy Web Server ---
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
                            if (isRunning) println("[MovieKing v11] Accept Error: ${e.message}")
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
                                val isSameDomain = targetUrl.contains("bcbc.red")
                                val requestBuilder = Request.Builder().url(targetUrl)

                                currentHeaders.forEach { (k, v) ->
                                    if (k.equals("Cookie", ignoreCase = true) || k.equals("Origin", ignoreCase = true)) {
                                        if (isSameDomain) requestBuilder.addHeader(k, v)
                                    } else {
                                        requestBuilder.addHeader(k, v)
                                    }
                                }
                                
                                val response = app.baseClient.newCall(requestBuilder.build()).execute()
                                
                                if (response.isSuccessful || response.code == 206) {
                                    val inputStream = response.body?.byteStream()
                                    if (inputStream != null) {
                                        val sb = StringBuilder()
                                        sb.append("HTTP/1.1 ${response.code} OK\r\n")
                                        sb.append("Content-Type: video/mp2t\r\n")
                                        response.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                                        sb.append("Connection: close\r\n\r\n")
                                        
                                        output.write(sb.toString().toByteArray())
                                        
                                        val buffer = ByteArray(8192)
                                        var count: Int
                                        while (inputStream.read(buffer).also { count = it } != -1) {
                                            output.write(buffer, 0, count)
                                        }
                                        output.flush()
                                        inputStream.close()
                                    }
                                } else {
                                    println("[MovieKing v11] Remote Fail: ${response.code}")
                                    output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                                }
                                response.close()
                            } catch (e: Exception) {
                                println("[MovieKing v11] Proxy Error: $e")
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v11] Socket Error: $e")
                }
            }
        }
    }
}
