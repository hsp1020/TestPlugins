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
import android.webkit.CookieManager
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v134] Extractor.kt: 쿠키 수집 로직 복구 (로그 확인 필수) & Origin 제거
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
        println("[TVWiki v134] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v134] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        val videoId = Regex("""/v/([^?&/]+)""").find(cleanUrl)?.groupValues?.get(1) ?: "video_${System.currentTimeMillis()}"

        // 1. iframe 소스 재탐색
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v134] 재탐색 URL: $cleanUrl")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. WebViewResolver로 c.html 주소 탐색 & 쿠키 생성 유도
        println("[TVWiki v134] WebViewResolver 시작: $cleanUrl")
        
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
                println("[TVWiki v134] [성공] WebView로 M3U8 주소 발견: $targetUrl")
            } else {
                println("[TVWiki v134] [실패] WebView가 c.html을 찾지 못함.")
                return false
            }

        } catch (e: Exception) {
            println("[TVWiki v134] WebView 실행 중 에러: ${e.message}")
            e.printStackTrace()
            return false
        }

        if (targetUrl == null) return false

        // [v134 핵심] 쿠키 수집 및 병합 (여기서 로그가 반드시 찍혀야 함)
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        
        val videoCookie = cookieManager.getCookie(targetUrl) ?: ""
        val mainCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
        
        // 중복 제거 및 병합
        val combinedCookies = listOf(videoCookie, mainCookie)
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            
        println("[TVWiki v134] 수집된 쿠키: $combinedCookies")

        // 3. M3U8 내용 다운로드
        val finalTokenUrl = targetUrl
        
        // M3U8 다운로드용 헤더 (쿠키 포함)
        val downloadHeaders = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Accept" to "*/*"
        )
        if (combinedCookies.isNotEmpty()) {
            downloadHeaders["Cookie"] = combinedCookies
        }

        try {
            println("[TVWiki v134] M3U8 내용 다운로드 요청")
            val m3u8Response = app.get(finalTokenUrl, headers = downloadHeaders)
            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[TVWiki v134] [치명적] 다운로드된 데이터가 M3U8 형식이 아님.")
                return false
            }

            // 4. 프록시 서버 설정
            // [v134] Video/Key 헤더 모두에 쿠키 추가. Origin 제거.
            val commonProxyHeaders = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Accept" to "*/*"
            )
            if (combinedCookies.isNotEmpty()) {
                commonProxyHeaders["Cookie"] = combinedCookies
            }

            // Key용 헤더 (Token Referer)
            val keyHeaders = commonProxyHeaders.toMutableMap()
            keyHeaders["Referer"] = finalTokenUrl

            // Video용 헤더 (Main Referer)
            val videoHeaders = commonProxyHeaders.toMutableMap()
            videoHeaders["Referer"] = "https://player.bunny-frame.online/"
            // videoHeaders["Origin"] = "https://player.bunny-frame.online" // 403 원인일 수 있어 제거

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(
                    videoH = videoHeaders, 
                    keyH = keyHeaders
                )
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            val baseUrl = finalTokenUrl.substringBeforeLast("/") + "/"

            // 5. M3U8 변조
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

            println("[TVWiki v134] 프록시 준비 완료. Port: $proxyPort")

            // 6. 플레이어에게 전달
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/" 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) {
            println("[TVWiki v134] 처리 중 에러: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

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
