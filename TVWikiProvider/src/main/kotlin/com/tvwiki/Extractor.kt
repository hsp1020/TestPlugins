package com.tvwiki

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [중요] TVWiki.kt와 동일한 User-Agent 사용 (불일치로 인한 차단 방지)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        // cleanUrl: 불필요한 공백 및 엔티티 제거
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        
        // Video ID 추출 (프록시 경로 생성용)
        val videoId = Regex("""/v/([^?&/]+)""").find(cleanUrl)?.groupValues?.get(1) 
            ?: Regex("""[?&]src=([^&]+)""").find(cleanUrl)?.groupValues?.get(1)?.take(10)
            ?: "video_${System.currentTimeMillis()}"

        // 1. iframe 소스 재탐색 (Desktop UA 사용)
        try {
            val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
            val text = refRes.text
            val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(text)
                ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(text)
            
            if (iframeMatch != null) {
                cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
            }
        } catch (e: Exception) { 
            e.printStackTrace() 
        }

        // 2. WebViewResolver로 c.html (M3U8) 주소 탐색
        var targetUrl: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )

        try {
            // WebView 요청 시에도 동일한 Desktop UA 사용
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA 
            )
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                targetUrl = response.url
            } else {
                return false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        if (targetUrl == null) return false

        // 3. M3U8 내용 다운로드
        val finalTokenUrl = targetUrl
        
        // M3U8 다운로드 헤더: Referer를 메인 도메인으로 설정
        val baseHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Accept" to "*/*"
        )

        try {
            val m3u8Response = app.get(finalTokenUrl, headers = baseHeaders)
            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                return false
            }

            // 4. 프록시 서버 설정
            // [v146 핵심] 
            // - Origin 제거 (403 방지)
            // - Referer: https://player.bunny-frame.online/ (일반화된 도메인 Referer 사용)
            // - UA: Desktop UA
            
            val proxyHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Connection" to "keep-alive"
            )

            // Key 요청용 헤더 (Key는 토큰 주소 Referer가 필요할 수 있음)
            val keyHeaders = proxyHeaders.toMutableMap()
            keyHeaders["Referer"] = finalTokenUrl

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoH = proxyHeaders, keyH = keyHeaders)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 5. M3U8 변조 (로컬 프록시 주소로 교체)
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalKeyUrl = uriMatch.groupValues[1]
                        // 절대 경로 변환
                        val absoluteKeyUrl = if (originalKeyUrl.startsWith("http")) originalKeyUrl else finalTokenUrl.substringBeforeLast("/") + "/" + originalKeyUrl
                        val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                        
                        val newLine = line.replace(originalKeyUrl, "$proxyRoot/key?url=$encodedKeyUrl")
                        newLines.add(newLine)
                    } else {
                        newLines.add(line)
                    }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    // 절대 경로 변환
                    val absoluteSegUrl = if (line.startsWith("http")) line else finalTokenUrl.substringBeforeLast("/") + "/" + line
                    val encodedSegUrl = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                    newLines.add("$proxyRoot/video?url=$encodedSegUrl")
                } else {
                    newLines.add(line)
                }
            }

            val modifiedM3u8 = newLines.joinToString("\n")
            proxyServer!!.setPlaylist(modifiedM3u8)

            // 6. 플레이어에게 전달
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/" 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    // =====================================================================================
    //  Proxy Web Server
    // =====================================================================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var videoHeaders: Map<String, String> = emptyMap()
        @Volatile private var keyHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) {
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) {
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }

        fun updateSession(videoH: Map<String, String>, keyH: Map<String, String>) {
            videoHeaders = videoH
            keyHeaders = keyH
        }

        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size < 2) return@thread
                val path = parts[1]
                
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val responseBytes = currentPlaylist.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                            "Content-Length: ${responseBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(responseBytes)
                } else if (path.contains("/key")) {
                    handleProxyRequest(path, keyHeaders, output, "application/octet-stream")
                } else if (path.contains("/video")) {
                    handleProxyRequest(path, videoHeaders, output, "video/mp2t")
                }

                output.flush()
                socket.close()
            } catch (e: Exception) {
                try { socket.close() } catch(e2:Exception){}
            }
        }

        private fun handleProxyRequest(path: String, headers: Map<String, String>, output: OutputStream, contentType: String) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                if (urlParam.isEmpty()) return
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")

                runBlocking {
                    // Cloudstream app.get 사용 (쿠키 자동 관리)
                    val response = app.get(targetUrl, headers = headers)
                    
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        val header = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: $contentType\r\n" +
                                "Content-Length: ${bytes.size}\r\n" +
                                "Connection: close\r\n\r\n"
                        output.write(header.toByteArray())
                        output.write(bytes)
                    } else {
                        val err = "HTTP/1.1 ${response.code} Error\r\n\r\n"
                        output.write(err.toByteArray())
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }
    }
}
