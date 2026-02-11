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

// [v171] Extractor.kt: Referer 정제 + Sec-Fetch 보안 헤더 추가 (403/1607바이트 해결)
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, thumbnailHint: String? = null): Boolean {
        println("[BunnyPoorCdn] v171 시작 - 브라우저 정석 패킷 재현")
        
        val fullPlayerUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        // [v171 핵심 1] Referer에서 쿼리 스트링 제거 (브라우저 표준 동작)
        val strippedReferer = fullPlayerUrl.substringBefore("?") 
        val cleanReferer = "https://tvwiki5.net/"
        val videoId = "video_${System.currentTimeMillis()}"

        val resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false, timeout = 30000L)
        var targetUrl: String? = null
        try {
            val response = app.get(url = fullPlayerUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA), interceptor = resolver)
            if (response.url.contains("/c.html")) targetUrl = response.url
        } catch (e: Exception) { }

        if (targetUrl == null) return false

        try {
            val m3u8Response = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to strippedReferer))
            val m3u8Content = m3u8Response.text
            if (!m3u8Content.contains("#EXTM3U")) return false

            val m3u8Uri = URI(targetUrl)
            val tokenQuery = m3u8Uri.rawQuery

            // [v171 핵심 2] 브라우저 보안 헤더 세트
            val browserHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to strippedReferer,
                "Accept" to "*/*",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Connection" to "keep-alive"
            )

            val keyHeaders = browserHeaders.toMutableMap()
            keyHeaders["Accept"] = "application/octet-stream, */*"

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoH = browserHeaders, keyH = keyHeaders)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalPath = uriMatch.groupValues[1]
                        var absUrl = m3u8Uri.resolve(originalPath).toString()
                        if (!absUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                            absUrl += if (absUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                        }
                        newLines.add(line.replace(originalPath, "$proxyRoot/key?url=${URLEncoder.encode(absUrl, "UTF-8")}"))
                    } else { newLines.add(line) }
                } else if (line.isNotBlank() && !line.startsWith("#")) {
                    var absUrl = m3u8Uri.resolve(line).toString()
                    if (!absUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                        absUrl += if (absUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                    }
                    newLines.add("$proxyRoot/video?url=${URLEncoder.encode(absUrl, "UTF-8")}")
                } else { newLines.add(line) }
            }

            proxyServer!!.setPlaylist(newLines.joinToString("\n"))

            callback(newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                this.referer = fullPlayerUrl 
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
                    handleProxyRequest(path, keyHeaders, output, "application/octet-stream", true)
                } else if (path.contains("/video")) {
                    handleProxyRequest(path, videoHeaders, output, "video/mp2t", false)
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun handleProxyRequest(path: String, headers: Map<String, String>, output: OutputStream, contentType: String, isKey: Boolean) {
            try {
                val urlParam = path.substringAfter("url=").substringBefore(" ")
                val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                runBlocking {
                    val response = app.get(targetUrl, headers = headers)
                    if (response.isSuccessful) {
                        val bytes = response.body.bytes()
                        if (isKey && bytes.size != 16) {
                            output.write("HTTP/1.1 403 KeyMismatch\r\n\r\n".toByteArray())
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
