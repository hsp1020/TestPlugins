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
import kotlinx.coroutines.delay
import kotlin.concurrent.thread

// [v145] Extractor.kt: Desktop UA 통일 + 브라우저 헤더 모방 + 정확한 쿠키/Referer 적용
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [중요] TVWiki.kt와 100% 동일한 User-Agent 사용 (토큰 유효성 유지)
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
        println("[TVWiki v145] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v145] extract 시작: $url")
        
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
                    println("[TVWiki v145] 재탐색 URL: $cleanUrl")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. WebViewResolver로 c.html 탐색
        println("[TVWiki v145] WebViewResolver 시작: $cleanUrl")
        
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
                println("[TVWiki v145] [성공] WebView로 M3U8 주소 발견: $targetUrl")
            } else {
                println("[TVWiki v145] [실패] WebView가 c.html을 찾지 못함.")
                return false
            }

        } catch (e: Exception) {
            println("[TVWiki v145] WebView 실행 중 에러: ${e.message}")
            e.printStackTrace()
            return false
        }

        if (targetUrl == null) return false

        // [v145] 쿠키 추출 강화: M3U8 호스트(nebulacore 등)에 대한 쿠키 확인
        delay(1000) // 쿠키 동기화 대기
        val cookieManager = CookieManager.getInstance()
        cookieManager.flush()
        
        val targetUri = URI(targetUrl)
        val targetHost = targetUri.host
        val targetDomainUrl = "${targetUri.scheme}://$targetHost"
        
        val videoCookie = cookieManager.getCookie(targetUrl) ?: ""
        val domainCookie = cookieManager.getCookie(targetDomainUrl) ?: ""
        val mainCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
        
        val combinedCookies = listOf(videoCookie, domainCookie, mainCookie)
            .flatMap { it.split(";") }
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("; ")
            
        println("[TVWiki v145] 수집된 쿠키($targetHost): $combinedCookies")

        // 3. M3U8 내용 다운로드
        val finalTokenUrl = targetUrl
        val downloadHeaders = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to cleanUrl,
            "Accept" to "*/*"
        )
        if (combinedCookies.isNotEmpty()) downloadHeaders["Cookie"] = combinedCookies

        try {
            val m3u8Response = app.get(finalTokenUrl, headers = downloadHeaders)
            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[TVWiki v145] [치명적] M3U8 형식 아님.")
                return false
            }

            // 4. 프록시 서버 설정
            // [v145] 헤더 완벽 모방: 
            // Referer: cleanUrl (플레이어 주소)
            // Origin: https://player.bunny-frame.online
            // User-Agent: DESKTOP_UA
            // Sec-Ch-Ua: 크롬 브라우저 흉내
            
            val proxyHeaders = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to cleanUrl,
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )
            if (combinedCookies.isNotEmpty()) {
                proxyHeaders["Cookie"] = combinedCookies
            }

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(
                    videoH = proxyHeaders, 
                    keyH = proxyHeaders.apply { put("Referer", finalTokenUrl) } // Key는 c.html 리퍼러가 더 안전할 수 있음
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

            println("[TVWiki v145] 프록시 준비 완료. Port: $proxyPort")

            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/" 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) {
            println("[TVWiki v145] 에러: ${e.message}")
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
