package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLEncoder
import java.net.URLDecoder
import java.io.BufferedReader
import java.io.InputStreamReader
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking

/**
 * BunnyPoorCdn Extractor
 * Version: [2026-02-13-V22.1-Final-Perfect-Headers]
 * - NEW: Implements Dynamic Header Engine. It differentiates between 'cross-site' entries (Playlist)
 * and 'same-origin' internal calls (Key/Segments).
 * - FIXED: Mimics browser's Referer chain (Key/TS refer to the M3U8 URL on the SAME HOST).
 * - FIXED: Forced Token Overwrite fixes the 1608 bytes error by replacing stale tokens.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val TAG = "[BunnyPoorCdn-2026-02-13-V22.1]"

    companion object {
        private var proxyServer: ProxyWebServerV22? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, thumbnailHint: String? = null): Boolean {
        val cleanUrl = url.replace("&amp;", "&").trim()
        val cleanReferer = referer ?: "https://tvwiki5.net/"
        var capturedUrl: String? = null
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") 

        val resolver = WebViewResolver(interceptUrl = interceptRegex, useOkhttp = false, timeout = 15000L)
        try {
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to MOBILE_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
            
            if (proxyServer == null) {
                proxyServer = ProxyWebServerV22(TAG)
                proxyServer?.start()
            }
            // M3U8 주소를 하위 리소스의 Referer 및 도메인 비교용으로 저장
            proxyServer?.updateContext(cookie, capturedUrl)

            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(capturedUrl, "UTF-8")
            
            callback(newExtractorLink(name, name, "http://127.0.0.1:$port/playlist?url=$encodedUrl#.m3u8", ExtractorLinkType.M3U8) {
                this.referer = cleanUrl
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    class ProxyWebServerV22(private val tag: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentCookie: String = ""
        @Volatile private var m3u8FullUrl: String = ""
        
        private val mobileUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [ProxyV22.1] Started on port $port")
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag [ProxyV22.1] Start Failed: $e") }
        }

        fun updateContext(cookie: String, m3u8Url: String) { 
            currentCookie = cookie 
            m3u8FullUrl = m3u8Url 
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return@thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@thread
                val pathFull = parts[1]
                val output = socket.getOutputStream()

                if (pathFull.startsWith("/playlist") || pathFull.startsWith("/proxy")) {
                    val targetUrlRaw = getQueryParam(pathFull, "url") ?: return@thread
                    val requestUrl = if (targetUrlRaw.contains("#")) targetUrlRaw.substringBefore("#") else targetUrlRaw
                    
                    // [지능형 엔진 핵심 로직]
                    val targetUri = URI(requestUrl)
                    val baseUri = URI(m3u8FullUrl)
                    val isSameOrigin = targetUri.host == baseUri.host
                    
                    val headers = mutableMapOf(
                        "User-Agent" to mobileUa,
                        "Accept" to "*/*",
                        "Sec-Fetch-Dest" to "empty"
                    )
                    
                    if (isSameOrigin && !requestUrl.contains("c.html")) {
                        // 키(Key) 및 세그먼트(TS) 요청: M3U8과 같은 도메인이므로 same-origin 적용
                        headers["Referer"] = m3u8FullUrl
                        headers["Sec-Fetch-Site"] = "same-origin"
                        headers["Sec-Fetch-Mode"] = "no-cors"
                    } else {
                        // 플레이리스트(c.html) 요청: 플레이어 외부에서 진입하므로 cross-site 적용
                        headers["Referer"] = "https://player.bunny-frame.online/"
                        headers["Origin"] = "https://player.bunny-frame.online"
                        headers["Sec-Fetch-Site"] = "cross-site"
                        headers["Sec-Fetch-Mode"] = "cors"
                    }
                    
                    if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie

                    val response = runBlocking { app.get(requestUrl, headers = headers) }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val newContent = rewriteM3u8(response.text, requestUrl)
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
                        println("$tag [ProxyV22.1] Error ${response.code} for: $requestUrl (Mode: ${headers["Sec-Fetch-Site"]})")
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun getQueryParam(path: String, key: String): String? {
            return try {
                path.substringAfter("?").split("&").find { it.startsWith("$key=") }?.substringAfter("=")?.let { URLDecoder.decode(it, "UTF-8") }
            } catch (e: Exception) { null }
        }

        private fun rewriteM3u8(content: String, baseUrl: String): String {
            val lines = content.lines()
            val refinedLines = mutableListOf<String>()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            val playlistProxyBase = "http://127.0.0.1:$port/playlist?url="
            val baseParams = parseQuery(baseUrl)

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trLine)
                    if (uriMatch != null) {
                        val keyUrl = uriMatch.groupValues[1]
                        val absUrl = resolveForceSync(baseUrl, keyUrl, baseParams)
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase${URLEncoder.encode(absUrl, "UTF-8")}"))
                    } else { refinedLines.add(trLine) }
                } else if (!trLine.startsWith("#")) {
                    val absUrl = resolveForceSync(baseUrl, trLine, baseParams)
                    val encoded = URLEncoder.encode(absUrl, "UTF-8")
                    if (absUrl.contains(".m3u8") || absUrl.contains("c.html")) {
                        refinedLines.add("$playlistProxyBase$encoded")
                    } else {
                        refinedLines.add("$proxyBase$encoded")
                    }
                } else { refinedLines.add(trLine) }
            }
            return refinedLines.joinToString("\n")
        }

        private fun parseQuery(url: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            if (!url.contains("?")) return params
            url.substringAfter("?").split("&").forEach { pair ->
                val p = pair.split("=", limit = 2)
                if (p.size == 2) params[p[0]] = p[1]
            }
            return params
        }

        private fun resolveForceSync(base: String, path: String, freshParams: Map<String, String>): String {
            var resolved = try { URI(base.substringBefore("#")).resolve(path).toString() } catch (e: Exception) {
                if (path.startsWith("/")) "${URI(base).scheme}://${URI(base).host}$path"
                else "${base.substringBefore("?").substringBeforeLast("/")}/$path"
            }
            if (freshParams.isEmpty()) return resolved
            val baseUrlOnly = resolved.substringBefore("?")
            val currentParams = parseQuery(resolved).toMutableMap()
            
            // 토큰 강제 주입 (V20에서 효과 확인됨)
            listOf("token", "expires", "sig", "t").forEach { k ->
                if (freshParams.containsKey(k)) currentParams[k] = freshParams[k]!!
            }
            val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return "$baseUrlOnly?$newQuery"
        }
    }
}
