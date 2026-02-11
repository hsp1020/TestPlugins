package com.tvwiki

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.SubtitleFile
import java.io.*
import java.net.*
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v131] Extractor.kt: MovieKing 방식의 프록시 서버 적용
// Video 요청(Main Referer)과 Key 요청(Token Referer)을 분리하여 2000/2004 에러 해결
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
        println("[TVWiki v131] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v131] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // 1. iframe 소스 재탐색
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v131] 재탐색 URL: $cleanUrl")
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        // 2. HTML 페이지에서 M3U8 주소 찾기 (중요)
        var targetUrl = cleanUrl
        val videoHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/",
            "Accept" to "*/*"
        )

        try {
            println("[TVWiki v131] 페이지 정보 요청: $targetUrl")
            var response = app.get(targetUrl, headers = videoHeaders, allowRedirects = true)
            var content = response.text
            
            // HTML일 경우 내부 M3U8 주소 탐색
            if (!content.trim().startsWith("#EXTM3U")) {
                println("[TVWiki v131] HTML 응답 감지. M3U8 주소 탐색.")
                
                val m3u8Regexes = listOf(
                    Regex("""["'](https?://[^"']+/c\.html[^"']*)["']"""),
                    Regex("""file:\s*["']([^"']+\.m3u8[^"']*)["']"""),
                    Regex("""source:\s*["']([^"']+\.m3u8[^"']*)["']""")
                )

                var foundUrl: String? = null
                for (regex in m3u8Regexes) {
                    val match = regex.find(content)
                    if (match != null) {
                        foundUrl = match.groupValues[1].replace("\\/", "/")
                        break
                    }
                }

                if (foundUrl != null) {
                    println("[TVWiki v131] M3U8 주소 발견: $foundUrl")
                    targetUrl = foundUrl
                    response = app.get(targetUrl, headers = videoHeaders, allowRedirects = true)
                    content = response.text
                } else if (response.url.contains("c.html") || response.url.contains(".m3u8")) {
                    targetUrl = response.url
                    println("[TVWiki v131] 리다이렉트 주소 사용: $targetUrl")
                } else {
                    println("[TVWiki v131] [오류] M3U8 주소 탐색 실패")
                    return false
                }
            }

            if (!content.contains("#EXTM3U")) {
                println("[TVWiki v131] [치명적] 최종 데이터 M3U8 아님.")
                return false
            }

            val finalTokenUrl = targetUrl
            println("[TVWiki v131] M3U8 확보 성공. Token URL: $finalTokenUrl")

            // 3. 프록시 서버 시작 및 세션 설정
            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(
                    videoH = videoHeaders, 
                    keyH = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to finalTokenUrl, // Key 요청용 Token Referer
                        "Accept" to "*/*"
                    )
                )
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"
            val baseUrl = finalTokenUrl.substringBeforeLast("/") + "/"

            // 4. M3U8 변조 (프록시 주소로 교체)
            val newLines = mutableListOf<String>()
            val lines = content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalKeyUrl = uriMatch.groupValues[1]
                        val absoluteKeyUrl = if (originalKeyUrl.startsWith("http")) originalKeyUrl else baseUrl + originalKeyUrl
                        val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                        
                        // /key 엔드포인트로 연결
                        val newLine = line.replace(originalKeyUrl, "$proxyRoot/key?url=$encodedKeyUrl")
                        newLines.add(newLine)
                    } else {
                        newLines.add(line)
                    }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    val absoluteSegUrl = if (line.startsWith("http")) line else baseUrl + line
                    val encodedSegUrl = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                    
                    // /video 엔드포인트로 연결
                    newLines.add("$proxyRoot/video?url=$encodedSegUrl")
                } else {
                    newLines.add(line)
                }
            }

            val modifiedM3u8 = newLines.joinToString("\n")
            proxyServer!!.setPlaylist(modifiedM3u8)

            println("[TVWiki v131] 프록시 준비 완료. Port: $proxyPort")

            // 5. 플레이어에게 프록시 주소 전달
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/" 
                    this.quality = Qualities.Unknown.value
                }
            )
            return true

        } catch (e: Exception) {
            println("[TVWiki v131] 에러: ${e.message}")
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

                if (path.contains("/playlist.m3u8")) {
                    val responseBytes = currentPlaylist.toByteArray(Charsets.UTF_8)
                    val header = "HTTP/1.1 200 OK\r\n" +
                            "Content-Type: application/vnd.apple.mpegurl; charset=utf-8\r\n" +
                            "Content-Length: ${responseBytes.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(responseBytes)
                } else if (path.contains("/key")) {
                    // Key 요청 시 Token Referer 사용
                    handleProxyRequest(path, keyHeaders, output, "application/octet-stream")
                } else if (path.contains("/video")) {
                    // Video 요청 시 Main Referer 사용
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
