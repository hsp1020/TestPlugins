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
 * Version: [2026-02-13-V31-Final-Integrated]
 * - FEATURE: Permanent Deep Scan Diagnostics (User Requested).
 * - FIX: Hardcoded Chrome UA to bypass Cloudflare (Dalvik UA caused 403).
 * - FIX: Force Token Sync to handle stale keys.
 * - HEADER: Smart Referer Strategy (Main site for Playlist, Player for Keys).
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [중요] Cloudflare 우회를 위한 고정 Chrome UA (Dalvik 차단 방지)
    private val CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val TAG = "[Bunny-V31]"

    companion object {
        private var proxyServer: ProxyWebServerV31? = null
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
            // WebView에도 Chrome UA 강제 적용
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to CHROME_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            
            // --- [DIAGNOSIS START] ---
            // 사용자 요청대로 진단 로직 영구 유지
            val cookieManager = CookieManager.getInstance()
            val c1 = cookieManager.getCookie(capturedUrl) ?: ""
            val c2 = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
            val validCookie = if (c1.isNotEmpty()) c1 else c2
            
            println("$TAG Cookies found: [$validCookie]")

            try {
                println("$TAG [DIAG] Fetching M3U8 with Chrome UA...")
                val diagHeaders = mapOf(
                    "User-Agent" to CHROME_UA,
                    "Referer" to "https://tvwiki5.net/",
                    "Origin" to "https://player.bunny-frame.online"
                )
                val diagResponse = app.get(capturedUrl, headers = diagHeaders)
                println("$TAG [DIAG] M3U8 Code: ${diagResponse.code}")
                
                if (!diagResponse.isSuccessful) {
                     val body = diagResponse.text
                     println("$TAG [DIAG] ERROR BODY (First 300): ${body.take(300)}")
                } else {
                     println("$TAG [DIAG] M3U8 Fetch Success!")
                }
            } catch (e: Exception) {
                println("$TAG [DIAG] Error: ${e.message}")
            }
            // --- [DIAGNOSIS END] ---

            if (proxyServer == null) {
                proxyServer = ProxyWebServerV31(TAG, CHROME_UA)
                proxyServer?.start()
            }
            
            // 플레이어 전체 주소를 Referer 컨텍스트로 전달
            proxyServer?.updateContext(validCookie, cleanUrl)

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

    class ProxyWebServerV31(private val tag: String, private val userAgent: String) {
        private var serverSocket: ServerSocket? = null
        var port: Int = 0
        @Volatile private var currentCookie: String = ""
        @Volatile private var fullPlayerUrl: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                println("$tag [ProxyV31] Started on port $port")
                thread(isDaemon = true) { 
                    while (serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag Start Failed: $e") }
        }

        fun updateContext(cookie: String, playerUrl: String) { 
            currentCookie = cookie 
            fullPlayerUrl = playerUrl
        }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: return@thread
                val parts = requestLine.split(" ")
                if (parts.size < 2) return@thread
                val pathFull = parts[1]
                val output = socket.getOutputStream()

                if (pathFull.startsWith("/playlist") || pathFull.startsWith("/proxy")) {
                    val targetUrlRaw = getQueryParam(pathFull, "url") ?: return@thread
                    val requestUrl = if (targetUrlRaw.contains("#")) targetUrlRaw.substringBefore("#") else targetUrlRaw
                    
                    // [헤더 전략] c.html은 Main Site, 나머지는 Player URL
                    val referer = if (requestUrl.contains("c.html")) "https://tvwiki5.net/" else fullPlayerUrl
                    
                    val headers = mutableMapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to "https://player.bunny-frame.online",
                        "Accept" to "*/*"
                    )
                    if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie

                    val response = runBlocking { app.get(requestUrl, headers = headers) }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val content = response.text
                            // [토큰 강제 동기화]
                            val newContent = rewriteM3u8(content, requestUrl)
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
                        // [DIAGNOSIS] 에러 시 본문 출력
                        println("$tag [Proxy Error] ${response.code} for: $requestUrl")
                        val errBody = response.text
                        if (errBody.length < 500) println("$tag [Error Body] $errBody")
                        
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

        private fun parseQuery(url: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            if (!url.contains("?")) return params
            url.substringAfter("?").split("&").forEach { pair ->
                val p = pair.split("=", limit = 2)
                if (p.size == 2) params[p[0]] = p[1]
            }
            return params
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
                        // Force Sync Token
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

        private fun resolveForceSync(base: String, path: String, freshParams: Map<String, String>): String {
            var resolved = try { URI(base.substringBefore("#")).resolve(path).toString() } catch (e: Exception) {
                if (path.startsWith("/")) "${URI(base).scheme}://${URI(base).host}$path"
                else "${base.substringBefore("?").substringBeforeLast("/")}/$path"
            }
            val baseUrlOnly = resolved.substringBefore("?")
            val currentParams = parseQuery(resolved).toMutableMap()
            
            // 토큰 강제 최신화 (1608바이트 에러 해결)
            listOf("token", "expires", "sig", "t").forEach { k ->
                if (freshParams.containsKey(k)) currentParams[k] = freshParams[k]!!
            }
            val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return if (newQuery.isNotEmpty()) "$baseUrlOnly?$newQuery" else resolved
        }
    }
}
