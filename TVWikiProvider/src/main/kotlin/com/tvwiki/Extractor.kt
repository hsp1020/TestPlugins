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
 * Version: [2026-02-12-V12-Final-Override]
 * - SOLUTION: Combines standard URI resolution with FORCED token overriding.
 * - Fixes 404: standard URI.resolve() prevents double slashes.
 * - Fixes 403 (1609 bytes): Fresh token from c.html replaces stale tokens in Key/TS URLs.
 * - Security: Includes Sec-Fetch-* headers for full browser imitation.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val PLAYER_REFERER = "https://player.bunny-frame.online/"
    private val TAG = "[BunnyPoorCdn-2026-02-12-V12-Final-Override]"

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
            println("$TAG WebView failed: ${e.message}")
        }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl) ?: ""

            if (proxyServer == null) {
                proxyServer = ProxyWebServer(TAG)
                proxyServer?.start()
            }
            
            proxyServer?.updateContext(cookie)

            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(capturedUrl, "UTF-8")
            val proxyUrl = "http://127.0.0.1:$port/playlist?url=$encodedUrl#.m3u8"

            callback(
                newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                    this.referer = PLAYER_REFERER
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }

    class ProxyWebServer(private val tag: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var currentCookie: String = ""
        
        private val mobileUa = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        private val playerReferer = "https://player.bunny-frame.online/"

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [Proxy] Started on port $port")
                
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { 
                println("$tag [Proxy] Start Failed: $e") 
            }
        }

        fun updateContext(cookie: String) {
            currentCookie = cookie
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
                    
                    val headers = mutableMapOf(
                        "User-Agent" to mobileUa,
                        "Referer" to playerReferer,
                        "Accept" to "*/*",
                        "Sec-Fetch-Dest" to "empty",
                        "Sec-Fetch-Mode" to "cors",
                        "Sec-Fetch-Site" to "cross-site"
                    )
                    if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie

                    val response = runBlocking {
                        app.get(requestUrl, headers = headers)
                    }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val content = response.text
                            // [Fix] M3U8 주소에서 토큰 추출 및 주입
                            val newContent = rewriteM3u8(content, requestUrl)
                            
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
                        println("$tag [Proxy] Error ${response.code} for: $requestUrl")
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                output.flush()
                socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun getQueryParam(path: String, key: String): String? {
            return try {
                val query = path.substringAfter("?")
                query.split("&").find { it.startsWith("$key=") }?.substringAfter("=")?.let {
                    URLDecoder.decode(it, "UTF-8")
                }
            } catch (e: Exception) { null }
        }

        private fun rewriteM3u8(content: String, baseUrl: String): String {
            val lines = content.lines()
            val refinedLines = mutableListOf<String>()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            val playlistProxyBase = "http://127.0.0.1:$port/playlist?url="

            // Base URL에서 최신 쿼리 파라미터 추출
            val baseParams = parseQuery(baseUrl.substringAfter("?"))

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trLine)
                    if (uriMatch != null) {
                        val keyUrl = uriMatch.groupValues[1]
                        // [Fix] 경로 결합 + 토큰 덮어쓰기
                        val absUrl = resolveAndOverride(baseUrl, keyUrl, baseParams)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    // TS 세그먼트도 동일하게 처리
                    val absUrl = resolveAndOverride(baseUrl, trLine, baseParams)
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

        private fun parseQuery(query: String?): Map<String, String> {
            val params = mutableMapOf<String, String>()
            if (query.isNullOrEmpty() || !query.contains("=")) return params
            query.split("&").forEach { pair ->
                val p = pair.split("=", limit = 2)
                if (p.size == 2) params[p[0]] = p[1]
            }
            return params
        }

        // [핵심] V11의 올바른 결합에 V12의 토큰 덮어쓰기를 합친 함수
        private fun resolveAndOverride(base: String, path: String, freshParams: Map<String, String>): String {
            // 1. V11 방식: 표준 경로 결합
            var resolved = if (path.startsWith("http")) path 
            else if (path.startsWith("//")) "https:$path"
            else {
                try {
                    val baseUri = URI(base.substringBefore("#"))
                    baseUri.resolve(path).toString()
                } catch (e: Exception) {
                    val baseDir = base.substringBefore("?").substringBeforeLast("/")
                    "$baseDir/$path"
                }
            }

            if (freshParams.isEmpty()) return resolved

            // 2. V12 방식: 쿼리 파라미터 덮어쓰기
            val parts = resolved.split("?", limit = 2)
            val baseUrlOnly = parts[0]
            val existingParams = parseQuery(if (parts.size > 1) parts[1] else "").toMutableMap()
            
            // 최신 토큰/만료시간 강제 주입
            freshParams.forEach { (k, v) ->
                if (k == "token" || k == "expires" || k == "sig") {
                    existingParams[k] = v
                }
            }

            val newQuery = existingParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return if (newQuery.isNotEmpty()) "$baseUrlOnly?$newQuery" else baseUrlOnly
        }
    }
}
