package com.movieking

import android.util.Base64
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    companion object {
        // [중요] WebView 인스턴스를 공유하기 위한 전역 변수
        private var sharedWebView: WebView? = null
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing v16] getUrl Start (WebView Hijack) ===")
        
        try {
            // 1. 기본 헤더
            val baseHeaders = mutableMapOf(
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. WebView 요청 및 인스턴스 확보
            // CloudStream의 WebViewResolver를 쓰면 WebView에 접근하기 어려우므로,
            // 우리가 직접 WebView를 띄우지 못하니, app.get의 응답만 취함.
            // 하지만 '데이터 하이재킹'을 위해서는 WebViewResolver가 만든 쿠키가 필수.
            
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
                    println("[MovieKing v16] Error: data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 4. UA 설정
            val chromeVersion = extractChromeVersion(m3u8Url) ?: "124.0.0.0"
            val standardUA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chromeVersion Mobile Safari/537.36"
            baseHeaders["User-Agent"] = standardUA
            println("[MovieKing v16] UA: $standardUA")

            // 5. M3U8 다운로드 (일반 요청으로 시도, 실패 시 프록시가 처리하도록 유도할 수도 있음)
            val m3u8Response = app.get(m3u8Url, headers = baseHeaders)
            var m3u8Content = m3u8Response.text

            // 6. 키 처리 (일반 요청)
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)
            var actualKeyBytes: ByteArray? = null

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyHeaders = mapOf(
                    "User-Agent" to standardUA,
                    "Cookie" to cookieString,
                    "Referer" to "https://player-v1.bcbc.red/",
                    "Origin" to "https://player-v1.bcbc.red"
                )
                val keyResponse = app.get(keyUrl, headers = keyHeaders)
                actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                if (actualKeyBytes != null) println("[MovieKing v16] Key Decrypted.")
            }

            // 7. 프록시 서버 시작
            if (proxyServer == null || !proxyServer!!.isAlive()) {
                proxyServer?.stop()
                proxyServer = ProxyWebServer()
                proxyServer!!.start()
            }
            
            // [핵심] 이번에는 헤더를 아주 강력하게 전달
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

            // 9. 재생 요청
            proxyServer!!.setPlaylist(m3u8Content)
            val localPlaylistUrl = "$proxyBaseUrl/playlist.m3u8"
            println("[MovieKing v16] Ready: $localPlaylistUrl")

            callback(
                newExtractorLink(name, name, localPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = baseHeaders
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing v16] Error: ${e.message}")
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
    
    // --- Proxy Web Server (Fallback to Clean Client with Range Support) ---
    // WebView 연동이 불가능한 구조(Activity 접근 불가)이므로,
    // v15에서 누락된 'Range 헤더 처리'와 '대소문자 처리'를 보강하여 재시도.
    // 또한 OkHttp의 ConnectionPool을 공유하지 않도록 새 인스턴스 생성.
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        // [수정] 완전히 격리된 OkHttp 클라이언트 생성 (TLS 설정은 시스템 기본값 사용)
        // CloudStream의 설정을 따르지 않음으로써 충돌 방지
        private val cleanClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
        
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
                            if (isRunning) println("[MovieKing v16] Accept Error: ${e.message}")
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
                    
                    val clientHeaders = mutableMapOf<String, String>()
                    var line: String?
                    while (reader.readLine().also { line = it } != null && line!!.isNotEmpty()) {
                        val parts = line!!.split(": ", limit = 2)
                        if (parts.size == 2) clientHeaders[parts[0]] = parts[1]
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
                            val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                            
                            try {
                                val requestBuilder = Request.Builder().url(targetUrl)

                                // [핵심] 헤더 대소문자 문제 해결 및 필수 헤더만 전송
                                // Cloudflare는 헤더 순서나 대소문자에 민감할 수 있음
                                currentHeaders.forEach { (k, v) -> 
                                    requestBuilder.header(k, v) 
                                }
                                
                                if (rangeHeader != null) requestBuilder.header("Range", rangeHeader)
                                
                                val response = cleanClient.newCall(requestBuilder.build()).execute()
                                
                                if (response.isSuccessful || response.code == 206) {
                                    val inputStream = response.body?.byteStream()
                                    if (inputStream != null) {
                                        val contentType = response.header("Content-Type", "") ?: ""
                                        // HTML 차단 감지
                                        if (contentType.contains("text/html")) {
                                            println("[MovieKing v16] Blocked! HTML returned.")
                                            output.write("HTTP/1.1 403 Forbidden\r\n\r\n".toByteArray())
                                        } else {
                                            val sb = StringBuilder()
                                            sb.append("HTTP/1.1 ${response.code} OK\r\n")
                                            sb.append("Content-Type: video/mp2t\r\n")
                                            response.header("Content-Length")?.let { sb.append("Content-Length: $it\r\n") }
                                            response.header("Content-Range")?.let { sb.append("Content-Range: $it\r\n") }
                                            sb.append("Connection: close\r\n\r\n")
                                            
                                            output.write(sb.toString().toByteArray())
                                            
                                            val buffer = ByteArray(8192)
                                            var count = inputStream.read(buffer)
                                            while (count != -1) {
                                                output.write(buffer, 0, count)
                                                count = inputStream.read(buffer)
                                            }
                                            output.flush()
                                            inputStream.close()
                                        }
                                    }
                                } else {
                                    println("[MovieKing v16] Remote Failed: ${response.code}")
                                    output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                                }
                                response.close()
                            } catch (e: Exception) {
                                println("[MovieKing v16] Stream Error: $e")
                                e.printStackTrace()
                            }
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                    socket.close()
                } catch (e: Exception) {
                    println("[MovieKing v16] Socket Error: $e")
                }
            }
        }
    }
}
