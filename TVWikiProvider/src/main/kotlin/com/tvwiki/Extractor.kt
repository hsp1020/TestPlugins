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

// [v178] Extractor.kt: 리퍼러 체인 정상화 (플레이어 로딩=Main, 파일 로딩=Player)
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
        println("[BunnyPoorCdn] v178 시작 - 올바른 리퍼러 체인 적용")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val videoId = "video_${System.currentTimeMillis()}"

        // 1. M3U8 주소 확보 (no3.png 회피를 위해 'tvwiki5.net' 리퍼러 사용)
        val m3u8Resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false, timeout = 30000L)
        var targetUrl: String? = null
        try {
            val response = app.get(
                url = cleanUrl, 
                // [1단계] 플레이어 페이지를 열 때는 메인 사이트가 리퍼러여야 함
                headers = mapOf("Referer" to "https://tvwiki5.net/", "User-Agent" to DESKTOP_UA), 
                interceptor = m3u8Resolver
            )
            if (response.url.contains("/c.html")) {
                targetUrl = response.url
                println("[BunnyPoorCdn] M3U8 주소 확보 성공: $targetUrl")
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (targetUrl == null) return false

        // 2. M3U8 다운로드 (여기서부터는 '플레이어 주소'가 리퍼러여야 함)
        try {
            // [v178 수정] CDN 요청 시에는 cleanUrl(플레이어 주소)을 리퍼러로 사용 (v177의 실수 수정)
            val m3u8Response = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanUrl))
            val m3u8Content = m3u8Response.text
            
            if (!m3u8Content.contains("#EXTM3U")) {
                println("[BunnyPoorCdn] M3U8 내용 다운로드 실패 또는 형식 오류")
                return false
            }

            val m3u8Uri = URI(targetUrl)
            val tokenQuery = m3u8Uri.rawQuery

            // 3. 진짜 키 획득 (WebView 사용 + Player Referer)
            val keyUriMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
            var finalKeyData: ByteArray? = null
            
            if (keyUriMatch != null) {
                val relKeyPath = keyUriMatch.groupValues[1]
                var absKeyUrl = m3u8Uri.resolve(relKeyPath).toString()
                if (!absKeyUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                    absKeyUrl += if (absKeyUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                }

                println("[BunnyPoorCdn] 키 요청: $absKeyUrl")
                
                // [2단계] 키 파일 요청 시에도 플레이어 주소를 리퍼러로 사용
                val keyResolver = WebViewResolver(interceptUrl = Regex("""wrap_key\.php"""), useOkhttp = false)
                val keyRes = app.get(
                    url = absKeyUrl, 
                    headers = mapOf("Referer" to cleanUrl, "User-Agent" to DESKTOP_UA), 
                    interceptor = keyResolver
                )
                
                finalKeyData = keyRes.body.bytes()
                println("[BunnyPoorCdn] 키 데이터 크기: ${finalKeyData?.size} bytes")
            }

            // 4. 프록시 서버 설정
            // [3단계] 영상 세그먼트 요청 시에도 플레이어 주소를 리퍼러로 사용
            val videoHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to cleanUrl,
                "Accept" to "*/*",
                "Connection" to "keep-alive"
                // Origin은 제거 (v170 성공 요인)
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoHeaders, finalKeyData)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 5. M3U8 변조
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    newLines.add(line.replace(Regex("""URI="[^"]+""""), "URI=\"$proxyRoot/key\""))
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
        @Volatile private var cachedKey: ByteArray? = null
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

        fun updateSession(vH: Map<String, String>, key: ByteArray?) {
            videoHeaders = vH
            cachedKey = key
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
                    if (cachedKey != null && cachedKey!!.size == 16) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(cachedKey)
                    } else {
                        output.write("HTTP/1.1 403 KeyMissing\r\n\r\n".toByteArray())
                    }
                } else if (path.contains("/video")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    runBlocking {
                        val response = app.get(targetUrl, headers = videoHeaders)
                        if (response.isSuccessful) {
                            val bytes = response.body.bytes()
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                            output.write(bytes)
                        } else {
                            output.write("HTTP/1.1 ${response.code} Error\r\n\r\n".toByteArray())
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }
    }
}
