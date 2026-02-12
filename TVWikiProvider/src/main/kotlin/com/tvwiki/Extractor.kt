package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * BunnyPoorCdn Extractor
 * Version: 2026-02-12-Final-V2
 * - Fixed: resolveUrl now correctly appends token even if target URL has query params.
 * - Solves InvalidKeyException (1603 bytes) completely.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
        val cleanUrl = url.replace("&amp;", "&").trim()
        val cleanReferer = referer ?: "https://tvwiki5.net/"

        // 1. WebView 로딩
        var capturedUrl: String? = null
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") 

        val resolver = WebViewResolver(
            interceptUrl = interceptRegex, 
            useOkhttp = false, 
            timeout = 15000L
        )
        
        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to MOBILE_UA
            )

            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (interceptRegex.containsMatchIn(response.url)) {
                capturedUrl = response.url
            }
        } catch (e: Exception) {
            println("[BunnyPoorCdn] WebView failed: ${e.message}")
        }

        if (capturedUrl != null) {
            println("[BunnyPoorCdn] Captured: $capturedUrl")
            delay(1000) 
            
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl) ?: ""
            println("[BunnyPoorCdn] Cookie: $cookie")

            val headers = mutableMapOf(
                "User-Agent" to MOBILE_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )
            if (cookie.isNotEmpty()) {
                headers["Cookie"] = cookie
            }

            val finalUrl = if (capturedUrl.contains("c.html")) "$capturedUrl#.m3u8" else capturedUrl

            // 2. 프록시 서버 구동
            if (proxyServer == null) {
                proxyServer = ProxyWebServer()
                proxyServer?.start()
            }
            proxyServer?.updateHeaders(headers)

            // 3. 로컬 프록시 주소 반환
            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(finalUrl, "UTF-8")
            val proxyUrl = "http://127.0.0.1:$port/playlist?url=$encodedUrl"

            callback(
                newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }

    // ==========================================
    // Local Proxy Server
    // ==========================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("[Proxy] Started on port $port")
                
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { 
                println("[Proxy] Start Failed: $e") 
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateHeaders(h: Map<String, String>) {
            currentHeaders = h
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 10000
                val input = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(input))
                val requestLine = reader.readLine()
                
                if (requestLine == null) {
                    socket.close()
                    return@thread
                }

                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    socket.close()
                    return@thread
                }
                
                val pathFull = parts[1]
                val output = socket.getOutputStream()

                if (pathFull.startsWith("/playlist")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        val response = runBlocking {
                            app.get(targetUrl, headers = currentHeaders)
                        }

                        if (response.isSuccessful) {
                            val content = response.text
                            // [Fix] 쿼리 파라미터 보존 로직 적용
                            val newContent = rewriteM3u8(content, targetUrl)
                            
                            val header = "HTTP/1.1 200 OK\r\n" +
                                       "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                       "Connection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                } 
                else if (pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        val response = runBlocking {
                            app.get(targetUrl, headers = currentHeaders)
                        }

                        if (response.isSuccessful) {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            val header = "HTTP/1.1 200 OK\r\n" +
                                       "Content-Type: $contentType\r\n" +
                                       "Connection: close\r\n\r\n"
                            output.write(header.toByteArray())
                            output.write(response.body.bytes())
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                } else {
                    output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
                
                output.flush()
                socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun getQueryParam(path: String, key: String): String? {
            try {
                if (!path.contains("?")) return null
                val query = path.substringAfter("?")
                val pairs = query.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf("=")
                    if (idx > 0) {
                        if (pair.substring(0, idx) == key) {
                            return URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
                        }
                    }
                }
            } catch (e: Exception) {}
            return null
        }

        private fun rewriteM3u8(content: String, baseUrl: String): String {
            val lines = content.lines()
            val refinedLines = mutableListOf<String>()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            val playlistProxyBase = "http://127.0.0.1:$port/playlist?url="

            // Base URL에서 토큰 등 쿼리 추출
            val baseQuery = try {
                val uri = URI(baseUrl)
                uri.rawQuery
            } catch (e: Exception) { null }

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val regex = Regex("""URI="([^"]+)"""")
                    val match = regex.find(trLine)
                    if (match != null) {
                        val keyUrl = match.groupValues[1]
                        // [Critical Fix] 토큰 강제 주입
                        val absUrl = resolveUrl(baseUrl, keyUrl, baseQuery)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    // URL Line (TS or M3U8)
                    val absUrl = resolveUrl(baseUrl, trLine, baseQuery)
                    val encoded = URLEncoder.encode(absUrl, "UTF-8")
                    if (absUrl.contains(".m3u8")) {
                        refinedLines.add("$playlistProxyBase$encoded")
                    } else {
                        refinedLines.add("$proxyBase$encoded")
                    }
                } else {
                    refinedLines.add(trLine)
                }
            }
            return refinedLines.joinToString("\n")
        }

        // [Fix] 기존 쿼리가 있어도 baseQuery를 강제로 덧붙이는 로직
        private fun resolveUrl(base: String, url: String, baseQuery: String?): String {
            var resolved = if (url.startsWith("http")) {
                url
            } else if (url.startsWith("//")) {
                "https:$url"
            } else {
                try {
                    URI(base).resolve(url).toString()
                } catch (e: Exception) {
                    url
                }
            }

            if (!baseQuery.isNullOrEmpty()) {
                // 이미 ?가 있으면 &로 연결, 없으면 ?로 연결
                val separator = if (resolved.contains("?")) "&" else "?"
                // 중복 방지를 위해 간단히 체크할 수도 있지만, 
                // 토큰이 쿼리 파라미터의 일부라면 중복되어도 서버는 보통 마지막 값을 쓰거나 무시하므로 안전하게 붙임
                resolved = "$resolved$separator$baseQuery"
            }
            return resolved
        }
    }
}
