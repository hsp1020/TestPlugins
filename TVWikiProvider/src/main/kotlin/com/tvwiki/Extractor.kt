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

// [v172] Extractor.kt: WebView 엔진을 이용한 진짜 16바이트 키 획득 및 검증 로그 추가
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
        println("[BunnyPoorCdn] v172 시작 - WebView 키 획득 모드")
        
        val fullPlayerUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val strippedReferer = fullPlayerUrl.substringBefore("?")
        val videoId = "video_${System.currentTimeMillis()}"

        // 1. M3U8 주소(c.html) 확보
        val m3u8Resolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), useOkhttp = false)
        var targetM3u8Url: String? = null
        try {
            val response = app.get(url = fullPlayerUrl, headers = mapOf("Referer" to "https://tvwiki5.net/", "User-Agent" to DESKTOP_UA), interceptor = m3u8Resolver)
            if (response.url.contains("/c.html")) targetM3u8Url = response.url
        } catch (e: Exception) { }

        if (targetM3u8Url == null) return false

        try {
            // 2. M3U8 내용 읽기
            val m3u8Response = app.get(targetM3u8Url, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to fullPlayerUrl))
            val m3u8Content = m3u8Response.text
            if (!m3u8Content.contains("#EXTM3U")) return false

            val m3u8Uri = URI(targetM3u8Url)
            val tokenQuery = m3u8Uri.rawQuery

            // 3. [v172 핵심] WebView를 사용하여 암호화 키 직접 획득
            val keyUriMatch = Regex("""URI="([^"]+)"""").find(m3u8Content)
            var finalKeyData: ByteArray? = null
            
            if (keyUriMatch != null) {
                val relKeyPath = keyUriMatch.groupValues[1]
                var absKeyUrl = m3u8Uri.resolve(relKeyPath).toString()
                if (!absKeyUrl.contains("token=") && !tokenQuery.isNullOrEmpty()) {
                    absKeyUrl += if (absKeyUrl.contains("?")) "&$tokenQuery" else "?$tokenQuery"
                }

                println("[BunnyPoorCdn] WebView로 키 요청 시작: $absKeyUrl")
                
                // WebView가 직접 wrap_key.php를 실행하게 함 (모든 보안 검사 통과)
                val keyResolver = WebViewResolver(interceptUrl = Regex("""wrap_key\.php"""), useOkhttp = false)
                val keyRes = app.get(absKeyUrl, headers = mapOf("Referer" to strippedReferer, "User-Agent" to DESKTOP_UA), interceptor = keyResolver)
                
                finalKeyData = keyRes.body.bytes()
                
                // [검증 로그]
                println("[BunnyPoorCdn] 획득한 데이터 크기: ${finalKeyData?.size ?: 0} bytes")
                if (finalKeyData?.size == 16) {
                    println("[BunnyPoorCdn] 팩트 확인: 16바이트 키 정상 획득 성공!")
                } else {
                    println("[BunnyPoorCdn] 경고: 여전히 ${finalKeyData?.size} bytes 데이터가 옴. 차단됨.")
                    return false
                }
            }

            // 4. 프록시 서버 설정 (이미 키를 가졌으므로 중계만 담당)
            val videoHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to strippedReferer,
                "Accept" to "*/*",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "cross-site",
                "Connection" to "keep-alive"
            )

            proxyServer?.stop()
            proxyServer = ProxyWebServer().apply {
                start()
                updateSession(videoHeaders, finalKeyData)
            }

            val proxyPort = proxyServer!!.port
            val proxyRoot = "http://127.0.0.1:$proxyPort/$videoId"
            
            // 5. M3U8 변조 (이미 확보한 키로 URI 교체)
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    // 키 경로는 프록시의 고정된 /key 주소로 변경
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
                this.referer = fullPlayerUrl 
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
                val firstLine = reader.readLine() ?: return@thread
                val path = firstLine.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val bytes = currentPlaylist.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${bytes.size}\r\n\r\n".toByteArray())
                    output.write(bytes)
                } else if (path.contains("/key")) {
                    // [핵심] 이미 WebView로 받아둔 진짜 키를 즉시 반환
                    if (cachedKey != null) {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: 16\r\n\r\n".toByteArray())
                        output.write(cachedKey)
                    } else {
                        output.write("HTTP/1.1 403 NoKeyFound\r\n\r\n".toByteArray())
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
