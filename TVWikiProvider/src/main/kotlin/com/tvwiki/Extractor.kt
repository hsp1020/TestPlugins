package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import java.net.URI
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v157] Extractor.kt: 로그 진단 전용 버전 (문제 원인 팩트 체크)
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
        println("[BunnyPoorCdn] getUrl 호출")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[BunnyPoorCdn] extract 시작 (v157 - 진단모드)")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        val videoId = "video_${System.currentTimeMillis()}"

        // 1. iframe 재탐색
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[BunnyPoorCdn] 재탐색 URL: $cleanUrl")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        var targetUrl: String? = null

        // 2. WebViewResolver
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        try {
            val requestHeaders = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA)
            val response = app.get(url = cleanUrl, headers = requestHeaders, interceptor = resolver)
            
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                targetUrl = response.url
                println("[BunnyPoorCdn] c.html 확보 성공: $targetUrl")
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (targetUrl == null) return false

        // 3. M3U8 다운로드 및 로그 출력
        try {
            val m3u8Headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanUrl, "Accept" to "*/*")
            val m3u8Response = app.get(targetUrl, headers = m3u8Headers)
            val m3u8Content = m3u8Response.text
            
            println("[BunnyPoorCdn] === M3U8 Content Start ===")
            println(m3u8Content) // [중요] M3U8 내용 전체 출력
            println("[BunnyPoorCdn] === M3U8 Content End ===")
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[BunnyPoorCdn] M3U8 형식 아님")
                return false
            }

            // Base URL 계산 로직 확인
            val baseUrl = targetUrl.substringBeforeLast("/") + "/"
            println("[BunnyPoorCdn] Calculated BaseURL: $baseUrl")

            // 4. 프록시 서버 설정
            val commonHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to cleanUrl, // 일단 cleanUrl로 유지 (로그 확인 후 변경 예정)
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(headers = commonHeaders)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 5. M3U8 변조
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalKeyUrl = uriMatch.groupValues[1]
                        val absoluteKeyUrl = if (originalKeyUrl.startsWith("http")) originalKeyUrl else baseUrl + originalKeyUrl
                        // Key URL 변환 로그
                        // println("[BunnyPoorCdn] Key Rewrite: $originalKeyUrl -> $absoluteKeyUrl") 
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

            println("[BunnyPoorCdn] 프록시 준비 완료: $proxyRoot/playlist.m3u8")

            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = cleanUrl 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) { e.printStackTrace() }
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var proxyHeaders: Map<String, String> = emptyMap()
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

        fun updateSession(headers: Map<String, String>) {
            proxyHeaders = headers
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
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${responseBytes.size}\r\nConnection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(responseBytes)
                } else if (path.contains("/key") || path.contains("/video")) {
                    handleProxyRequest(path, proxyHeaders, output)
                }
                output.flush()
                socket.close()
            } catch (e: Exception) {
                try { socket.close() } catch(e2:Exception){}
            }
        }

        private fun handleProxyRequest(path: String, headers: Map<String, String>, output: OutputStream) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                if (urlParam.isEmpty()) return
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")

                // [중요] 실제 요청 URL과 결과를 로그로 출력
                println("[BunnyPoorCdn Proxy] Requesting Target: $targetUrl")
                println("[BunnyPoorCdn Proxy] Using Headers: $headers")

                runBlocking {
                    val response = app.get(targetUrl, headers = headers)
                    
                    println("[BunnyPoorCdn Proxy] Response Code: ${response.code}")
                    if (!response.isSuccessful) {
                         // 실패 시 응답 내용 일부 출력 (403/404 원인 파악용)
                         println("[BunnyPoorCdn Proxy] Error Body: ${response.text.take(200)}")
                    }

                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        val contentType = if(path.contains("/key")) "application/octet-stream" else "video/mp2t"
                        val header = "HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n"
                        output.write(header.toByteArray())
                        output.write(bytes)
                    } else {
                        val err = "HTTP/1.1 ${response.code} Error\r\n\r\n"
                        output.write(err.toByteArray())
                    }
                }
            } catch (e: Exception) {
                println("[BunnyPoorCdn Proxy] Exception: ${e.message}")
            }
        }
    }
}
