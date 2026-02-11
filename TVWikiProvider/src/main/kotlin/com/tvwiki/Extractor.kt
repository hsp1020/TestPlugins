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
import java.net.URLDecoder
import java.net.URLEncoder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.runBlocking
import kotlin.concurrent.thread

// [v167] Extractor.kt: InvalidKeyException(1607 bytes) 해결 - Key 전용 헤더 보강
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
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[BunnyPoorCdn] extract 시작 (v167 - Key Header Fix)")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        val videoId = "video_${System.currentTimeMillis()}"

        // 1. iframe 재탐색 (생략 가능하나 안정성을 위해 유지)
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
            } catch (e: Exception) { }
        }

        var targetUrl: String? = null

        // 2. WebViewResolver
        val resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false, timeout = 30000L)
        try {
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA), interceptor = resolver)
            if (response.url.contains("/c.html")) targetUrl = response.url
        } catch (e: Exception) { }

        if (targetUrl == null) return false

        // 3. M3U8 다운로드
        try {
            val m3u8Response = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanUrl))
            val m3u8Content = m3u8Response.text
            if (!m3u8Content.contains("#EXTM3U")) return false

            val m3u8Uri = URI(targetUrl)
            val tokenQuery = m3u8Uri.rawQuery

            // 4. 프록시 서버 설정
            // [v167] Video와 Key의 헤더를 차별화
            val videoHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/", 
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            // [v167 핵심] Key 요청 시 서버가 텍스트/HTML을 주지 않도록 Accept를 명시적으로 설정
            val keyHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "application/octet-stream, */*" 
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoH = videoHeaders, keyH = keyHeaders)
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
                        val originalKeyPath = uriMatch.groupValues[1]
                        var absoluteKeyUrl = m3u8Uri.resolve(originalKeyPath).toString()
                        if (!absoluteKeyUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                            absoluteKeyUrl += if (absoluteKeyUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                        }
                        val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                        newLines.add(line.replace(originalKeyPath, "$proxyRoot/key?url=$encodedKeyUrl"))
                    } else { newLines.add(line) }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    var absoluteSegUrl = m3u8Uri.resolve(line).toString()
                    if (!absoluteSegUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                        absoluteSegUrl += if (absoluteSegUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                    }
                    newLines.add("$proxyRoot/video?url=${URLEncoder.encode(absoluteSegUrl, "UTF-8")}")
                } else { newLines.add(line) }
            }

            proxyServer!!.setPlaylist(newLines.joinToString("\n"))

            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.referer = cleanUrl 
                this.quality = Qualities.Unknown.value
            })
            return true
        } catch (e: Exception) { }
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
                    while (isRunning && serverSocket != null) {
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val bytes = currentPlaylist.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    output.write(bytes)
                } else if (path.contains("/key")) {
                    handleProxyRequest(path, keyHeaders, output, "application/octet-stream")
                } else if (path.contains("/video")) {
                    handleProxyRequest(path, videoHeaders, output, "video/mp2t")
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun handleProxyRequest(path: String, headers: Map<String, String>, output: OutputStream, contentType: String) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                runBlocking {
                    val response = app.get(targetUrl, headers = headers)
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        // [v167 중요] 만약 응답 데이터가 16바이트가 아니라면 강제로 에러 처리하여 재시도 유도
                        if (path.contains("/key") && bytes.size != 16) {
                            output.write("HTTP/1.1 500 KeySizeMismatch\r\n\r\n".toByteArray())
                        } else {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    } else {
                        output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                    }
                }
            } catch (e: Exception) { }
        }
    }
}
