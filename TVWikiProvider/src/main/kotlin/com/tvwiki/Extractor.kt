package com.tvwiki

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v133] Extractor.kt: 프록시 URL에 Video ID 추가 + Video 요청 헤더에 Origin 추가 (403 해결)
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
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
        println("[TVWiki v133] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v133] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // 1. Video ID 추출 (URL 구조: .../v/VIDEO_ID?...)
        val videoId = Regex("""/v/([^?&/]+)""").find(cleanUrl)?.groupValues?.get(1) ?: "video_${System.currentTimeMillis()}"
        println("[TVWiki v133] Video ID 추출: $videoId")

        // 2. iframe 소스 재탐색
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v133] 재탐색 URL: $cleanUrl")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 3. WebViewResolver로 c.html 주소 탐색 (v132 성공 로직 유지)
        println("[TVWiki v133] WebViewResolver 시작: $cleanUrl")
        
        var targetUrl: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )

        try {
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
                println("[TVWiki v133] [성공] WebView로 M3U8 주소 발견: $targetUrl")
            } else {
                println("[TVWiki v133] [실패] WebView가 c.html을 찾지 못함.")
                return false
            }

        } catch (e: Exception) {
            println("[TVWiki v133] WebView 실행 중 에러: ${e.message}")
            e.printStackTrace()
            return false
        }

        if (targetUrl == null) return false

        // 4. M3U8 내용 다운로드
        val finalTokenUrl = targetUrl
        
        // [v133 중요] Video 헤더에 Origin 추가 (403 방지)
        val videoHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Origin" to "https://player.bunny-frame.online", // 403 Forbidden 해결의 핵심
            "Accept" to "*/*"
        )

        try {
            println("[TVWiki v133] M3U8 내용 다운로드 요청")
            val m3u8Response = app.get(finalTokenUrl, headers = videoHeaders)
            
            if (!m3u8Response.isSuccessful) {
                println("[TVWiki v133] M3U8 다운로드 실패: ${m3u8Response.code}")
                return false
            }

            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[TVWiki v133] [치명적] 다운로드된 데이터가 M3U8 형식이 아님.")
                return false
            }

            // 5. 프록시 서버 설정
            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(
                    videoH = videoHeaders, 
                    keyH = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to finalTokenUrl, // Key 요청 시 Token Referer 사용
                        "Accept" to "*/*"
                    )
                )
            }

            val proxyPort = proxyServer!!.port
            // [v133] URL에 Video ID 포함 (http://127.0.0.1:PORT/VIDEO_ID/...)
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            val baseUrl = finalTokenUrl.substringBeforeLast("/") + "/"

            // 6. M3U8 변조
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalKeyUrl = uriMatch.groupValues[1]
                        val absoluteKeyUrl = if (originalKeyUrl.startsWith("http")) originalKeyUrl else baseUrl + originalKeyUrl
                        val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                        
                        val newLine = line.replace(originalKeyUrl, "$proxyRoot/key?url=$encodedKeyUrl")
                        newLines.add(newLine)
                    } else {
                        newLines.add(line)
                    }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    val absoluteSegUrl = if (line.startsWith("http")) line else baseUrl + line
                    val encodedSegUrl = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                    
                    newLines.add("$proxyRoot/video?url=$encodedSegUrl")
                } else {
                    newLines.add(line)
                }
            }

            val modifiedM3u8 = newLines.joinToString("\n")
            proxyServer!!.setPlaylist(modifiedM3u8)

            println("[TVWiki v133] 프록시 준비 완료. Port: $proxyPort, ID: $videoId")

            // 7. 플레이어에게 전달
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/" 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) {
            println("[TVWiki v133] 처리 중 에러: ${e.message}")
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
            } catch (e: Exception) { println("[TVWiki Proxy] Server Start Failed: $e") }
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

                // [v133] 경로 매칭 로직은 contains를 사용하므로 ID가 포함되어도 동작함
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
