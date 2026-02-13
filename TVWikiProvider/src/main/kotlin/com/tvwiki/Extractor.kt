package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * [Version: v2026-02-13-DebugLog]
 * 수정 내용:
 * 1. 1608 바이트 키 에러의 원인을 찾기 위해, 서버가 보낸 에러 메시지(HTML) 내용을 로그캣에 출력하는 기능 추가.
 * -> 로그캣에서 "[BunnyPoorCdn] Key Error Body:" 검색 요망.
 * 2. Referer 정책을 'c.html' 주소 자체로 변경 (브라우저 동작과 유사하게 맞춤).
 */
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
        proxyServer?.stop()
        proxyServer = null
        
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // iframe 주소 추출
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // c.html(=M3U8)을 타겟으로 인터셉트
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null

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
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                
                val cookieManager = CookieManager.getInstance()
                val cookie = cookieManager.getCookie(capturedUrl) ?: ""
                
                // [수정] Referer를 c.html URL로 설정 (HLS 키 요청 시 일반적인 동작)
                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to capturedUrl, 
                    "Origin" to "https://player.bunny-frame.online", // Origin은 유지
                    "Cookie" to cookie,
                    "Accept" to "*/*"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                // 1. M3U8 다운로드
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text

                // 2. 프록시 서버 시작
                val proxy = ProxyWebServer()
                proxy.start()
                proxy.updateSession(capturedHeaders!!)
                proxyServer = proxy

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

                // 3. M3U8 재작성
                val newLines = mutableListOf<String>()
                val lines = m3u8Content.lines()
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}" 
                val parentUrl = capturedUrl.substringBeforeLast("/")

                fun resolveUrl(path: String): String {
                    return when {
                        path.startsWith("http") -> path
                        path.startsWith("/") -> "$domain$path"
                        else -> "$parentUrl/$path"
                    }
                }

                for (line in lines) {
                    when {
                        line.startsWith("#EXT-X-KEY") -> {
                            val keyUriMatch = Regex("""URI="([^"]+)"""").find(line)
                            if (keyUriMatch != null) {
                                val originalKeyPath = keyUriMatch.groupValues[1]
                                val fullKeyUrl = resolveUrl(originalKeyPath)
                                val encodedKeyUrl = URLEncoder.encode(fullKeyUrl, "UTF-8")
                                val newLine = line.replace(originalKeyPath, "$proxyRoot/proxy/key?url=$encodedKeyUrl")
                                newLines.add(newLine)
                            } else {
                                newLines.add(line)
                            }
                        }
                        line.startsWith("http") || (line.isNotBlank() && !line.startsWith("#")) -> {
                            val fullSegUrl = resolveUrl(line)
                            val encodedSegUrl = URLEncoder.encode(fullSegUrl, "UTF-8")
                            newLines.add("$proxyRoot/proxy/seg?url=$encodedSegUrl")
                        }
                        else -> newLines.add(line)
                    }
                }

                val proxyM3u8 = newLines.joinToString("\n")
                proxy.setPlaylist(proxyM3u8)

                callback(
                    newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true

            } catch (e: Exception) {
                callback(
                    newExtractorLink(name, name, capturedUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.headers = capturedHeaders
                    }
                )
                return true
            }
        }
        
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
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
            } catch (e: Exception) {}
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>) {
            currentHeaders = h
        }
        
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    socket.soTimeout = 15000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val line = reader.readLine() ?: return@thread
                    val parts = line.split(" ")
                    if (parts.size < 2) return@thread
                    
                    val path = parts[1]
                    val output = socket.getOutputStream()

                    if (path.contains("/playlist.m3u8")) {
                        val body = currentPlaylist.toByteArray(charset("UTF-8"))
                        val header = "HTTP/1.1 200 OK\r\n" +
                                     "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                     "Content-Length: ${body.size}\r\n" +
                                     "Connection: close\r\n\r\n"
                        output.write(header.toByteArray())
                        output.write(body)
                    } else if (path.contains("/proxy/")) {
                        val urlParam = path.substringAfter("url=").substringBefore(" ")
                        val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                        
                        runBlocking {
                            try {
                                val res = app.get(targetUrl, headers = currentHeaders)
                                
                                if (res.isSuccessful) {
                                    val rawData = res.body.bytes()
                                    val contentType = if (path.contains("/key")) "application/octet-stream" else "video/mp2t"
                                    
                                    // [디버깅] 키 요청인데 16바이트가 아니면 에러 내용을 로그에 출력
                                    if (path.contains("/key") && rawData.size != 16) {
                                        val errorBody = String(rawData)
                                        println("==================================================")
                                        println("[BunnyPoorCdn] Key Error Body (Size: ${rawData.size} bytes):")
                                        println(errorBody)
                                        println("==================================================")
                                    }

                                    val header = "HTTP/1.1 200 OK\r\n" +
                                                 "Content-Type: $contentType\r\n" +
                                                 "Content-Length: ${rawData.size}\r\n" +
                                                 "Connection: close\r\n\r\n"
                                    output.write(header.toByteArray())
                                    output.write(rawData)
                                } else {
                                    val err = "HTTP/1.1 ${res.code} Error\r\nConnection: close\r\n\r\n"
                                    output.write(err.toByteArray())
                                }
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    }
                    output.flush()
                    socket.close()
                } catch (e: Exception) { 
                    try { socket.close() } catch(e2:Exception){} 
                }
            }
        }
    }
}
