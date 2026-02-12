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

// [v178] Extractor.kt: M3U8(Main Referer) + Key(Player Referer) - 성공 로직 단순 결합
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
        println("[BunnyPoorCdn] v178 시작 - 성공 로직 단순 결합")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val videoId = "video_${System.currentTimeMillis()}"

        // 1. M3U8 주소(c.html) 확보 [v163 성공 방식]
        // 무한 로딩을 피하기 위해 재시도 로직 제거하고 단판 승부
        val m3u8Resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false, timeout = 30000L)
        var targetUrl: String? = null
        try {
            val response = app.get(
                url = cleanUrl, 
                // [중요] 초기 진입은 'tvwiki5.net' 리퍼러 사용 (no3.png 회피)
                headers = mapOf("Referer" to "https://tvwiki5.net/", "User-Agent" to DESKTOP_UA), 
                interceptor = m3u8Resolver
            )
            if (response.url.contains("/c.html")) {
                targetUrl = response.url
                println("[BunnyPoorCdn] M3U8 확보 성공: $targetUrl")
            } else if (response.url.contains("no3.png")) {
                println("[BunnyPoorCdn] 실패: no3.png 차단됨. (Referer 이슈)")
                return false
            }
        } catch (e: Exception) { 
            e.printStackTrace()
        }

        if (targetUrl == null) return false

        // 2. M3U8 다운로드 [v170 수정 방식]
        try {
            // [중요] CDN 요청 시에는 'cleanUrl(플레이어)'을 리퍼러로 변경
            // v163, v177은 여기서 계속 tvwiki5.net을 써서 403이 떴음
            val m3u8Response = app.get(
                targetUrl, 
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanUrl) 
            )
            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[BunnyPoorCdn] M3U8 다운로드 실패 (내용 없음)")
                return false
            }

            val m3u8Uri = URI(targetUrl)
            val tokenQuery = m3u8Uri.rawQuery

            // 3. 프록시 서버 설정
            // Key와 Video 요청 모두 '플레이어 주소'를 리퍼러로 사용 (Origin 제거)
            val videoHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to cleanUrl, 
                "Accept" to "*/*",
                "Connection" to "keep-alive"
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoHeaders)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 4. M3U8 변조
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                    if (uriMatch != null) {
                        val originalPath = uriMatch.groupValues[1]
                        var absUrl = m3u8Uri.resolve(originalPath).toString()
                        // 토큰 전파
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
                this.referer = cleanUrl 
                this.quality = Qualities.Unknown.value
            })
            return true
        } catch (e: Exception) { e.printStackTrace() }
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var videoHeaders: Map<String, String> = emptyMap()
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

        fun updateSession(vH: Map<String, String>) {
            videoHeaders = vH
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
                    handleProxyRequest(path, videoHeaders, output, "application/octet-stream")
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
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                        output.write(bytes)
                    } else {
                        output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                    }
                }
            } catch (e: Exception) { }
        }
    }
}
