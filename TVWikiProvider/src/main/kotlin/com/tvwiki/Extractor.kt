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
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * BunnyPoorCdn Extractor
 * Version: 2026-02-12-Refined-Imports
 * - Logic Verified: Forces inheritance of query parameters (tokens) from c.html to enc.key.
 * - Fixes: InvalidKeyException (1600~ bytes html error page instead of 16-byte key).
 * - Update: Optimized imports using wildcard (com.lagradost.cloudstream3.utils.*)
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // WebView와 동일한 Mobile UA 사용
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

        var capturedUrl: String? = null
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") 

        // 1. WebView 로딩 (토큰 생성 목적)
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
            
            // 쿠키는 없어도 헤더는 필수
            val headers = mutableMapOf(
                "User-Agent" to MOBILE_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            // URL Fragment(#) 제거 (프록시 전달용)
            val finalUrl = if (capturedUrl.contains("#")) capturedUrl.substringBefore("#") else capturedUrl

            // 2. 프록시 서버 시작
            if (proxyServer == null) {
                proxyServer = ProxyWebServer()
                proxyServer?.start()
            }
            proxyServer?.updateHeaders(headers)

            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(finalUrl, "UTF-8")
            
            // 로컬 프록시 주소 반환 (Cloudstream에는 .m3u8로 인식되게 힌트 추가)
            val proxyUrl = "http://127.0.0.1:$port/playlist?url=$encodedUrl#.m3u8"

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

                // Request Routing
                if (pathFull.startsWith("/playlist") || pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        // URL Fragment 제거 (서버 404 방지)
                        val requestUrl = if (targetUrl.contains("#")) targetUrl.substringBefore("#") else targetUrl
                        
                        val response = runBlocking {
                            app.get(requestUrl, headers = currentHeaders)
                        }

                        if (response.isSuccessful) {
                            if (pathFull.startsWith("/playlist")) {
                                val content = response.text
                                // [핵심] M3U8 내용 재작성 (토큰 승계 로직 적용)
                                val newContent = rewriteM3u8(content, requestUrl)
                                
                                val header = "HTTP/1.1 200 OK\r\n" +
                                           "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                           "Connection: close\r\n\r\n"
                                output.write(header.toByteArray())
                                output.write(newContent.toByteArray())
                            } else {
                                // Key/TS 파일 전달
                                val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                                val header = "HTTP/1.1 200 OK\r\n" +
                                           "Content-Type: $contentType\r\n" +
                                           "Connection: close\r\n\r\n"
                                output.write(header.toByteArray())
                                output.write(response.body.bytes())
                            }
                        } else {
                            println("[Proxy] Error ${response.code} requesting: $requestUrl")
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    } else {
                         output.write("HTTP/1.1 400 Bad Request\r\n\r\n".toByteArray())
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

            // [핵심] Base URL(c.html)에서 토큰(Query Params) 추출
            // 예: token=ABC&expires=123
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
                        // [Fix] 키 URL에 부모의 토큰(baseQuery)을 강제로 이어 붙임
                        val absUrl = resolveUrl(baseUrl, keyUrl, baseQuery)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    // [Fix] TS/M3U8 URL에도 부모의 토큰을 강제로 이어 붙임
                    val absUrl = resolveUrl(baseUrl, trLine, baseQuery)
                    val encoded = URLEncoder.encode(absUrl, "UTF-8")
                    
                    if (absUrl.contains(".m3u8") || absUrl.contains("c.html")) {
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

        private fun resolveUrl(base: String, url: String, baseQuery: String?): String {
            // 1. 절대 경로 변환
            var resolved = if (url.startsWith("http")) {
                url
            } else if (url.startsWith("//")) {
                "https:$url"
            } else {
                try {
                    // base(c.html) 기준으로 상대 경로(enc.key) 해석 -> http://.../enc.key
                    URI(base).resolve(url).toString()
                } catch (e: Exception) {
                    url
                }
            }

            // 2. 토큰(Query) 강제 주입
            if (!baseQuery.isNullOrEmpty()) {
                // 이미 쿼리가 있으면 &로, 없으면 ?로 연결
                val separator = if (resolved.contains("?")) "&" else "?"
                
                // 중복 방지: baseQuery가 이미 포함되어 있지 않을 때만 붙임
                if (!resolved.contains(baseQuery)) {
                     resolved = "$resolved$separator$baseQuery"
                }
            }
            return resolved
        }
    }
}
