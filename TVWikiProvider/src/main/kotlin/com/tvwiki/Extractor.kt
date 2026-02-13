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
import android.util.Base64
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
 * [Version: v2026-02-13-Chrome144-JsonFix]
 * 1. User-Agent 및 Sec-Ch-Ua 헤더를 사용자의 Chrome 144 환경과 100% 일치시킴.
 * 2. Key 요청 시 'mode=obfuscated' 파라미터를 제거하여 원본 바이너리 키 요청 시도.
 * 3. 만약 서버가 강제로 JSON(1608 bytes)을 줄 경우, JSON 파싱 후 'encrypted_key'를 추출하여 디코딩.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [수정] 사용자 스크린샷 기반 Chrome 144 UA
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

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

        // c.html 인터셉트
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
                
                // [수정] 스크린샷과 동일한 헤더 구성
                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to "https://player.bunny-frame.online/",
                    "Origin" to "https://player.bunny-frame.online",
                    "Cookie" to cookie,
                    "Accept" to "*/*",
                    // [중요] 스크린샷의 Sec-Ch-Ua 그대로 적용
                    "Sec-Ch-Ua" to "\"Not(A:Brand\";v=\"8\", \"Chromium\";v=\"144\", \"Google Chrome\";v=\"144\"",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to "\"Windows\"",
                    "Sec-Fetch-Site" to "cross-site",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty"
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text

                val proxy = ProxyWebServer()
                proxy.start()
                proxy.updateSession(capturedHeaders!!)
                proxyServer = proxy

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

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
                                // 키 요청을 프록시로 돌림
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
                // Fallback
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
                        
                        // [핵심] 키 요청일 경우 mode=obfuscated 파라미터를 강제로 제거
                        // 이렇게 하면 서버가 JSON이 아닌 순수 바이너리 키를 줄 가능성이 높음
                        var finalTargetUrl = targetUrl
                        if (path.contains("/key")) {
                            finalTargetUrl = finalTargetUrl.replace("&mode=obfuscated", "").replace("?mode=obfuscated", "")
                        }

                        runBlocking {
                            try {
                                val res = app.get(finalTargetUrl, headers = currentHeaders)
                                
                                if (res.isSuccessful) {
                                    var rawData = res.body.bytes()
                                    
                                    // [안전장치] 만약 파라미터를 지웠는데도 JSON({...)이 날아왔다면?
                                    // JSON을 파싱해서 encrypted_key만 추출
                                    if (path.contains("/key") && rawData.isNotEmpty() && rawData[0] == '{'.code.toByte()) {
                                        try {
                                            val jsonString = String(rawData)
                                            // 정규식으로 encrypted_key 추출
                                            val keyMatch = Regex(""""encrypted_key"\s*:\s*"([^"]+)"""").find(jsonString)
                                            if (keyMatch != null) {
                                                val b64Key = keyMatch.groupValues[1]
                                                // Base64 디코딩
                                                val decodedKey = Base64.decode(b64Key, Base64.DEFAULT)
                                                // 만약 16바이트라면 이게 정답
                                                if (decodedKey.size == 16) {
                                                    rawData = decodedKey
                                                    println("[BunnyPoorCdn] JSON Key 추출 및 디코딩 성공!")
                                                }
                                            }
                                        } catch (e: Exception) {
                                            // 파싱 실패시 원본 rawData 그대로 전송 (어차피 에러날 것임)
                                        }
                                    }

                                    val contentType = if (path.contains("/key")) "application/octet-stream" else "video/mp2t"
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
