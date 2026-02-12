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
 * Version: 2026-02-12-String-Parsing-Fix
 * - Fix: Replaced java.net.URI with manual string parsing to guarantee token extraction.
 * - Logic: Parses parent URL params and FORCIBLY overwrites 'token'/'expires' in child URLs.
 * - Debug: Logs exactly which tokens are being injected.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val TAG = "[BunnyPoorCdn-StringFix]"

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
            
            // Cookie Retry
            var cookie = ""
            val cookieManager = CookieManager.getInstance()
            for (i in 0..5) {
                cookie = cookieManager.getCookie(capturedUrl) ?: ""
                if (cookie.isNotEmpty()) break
                delay(200)
            }
            if (cookie.isNotEmpty()) {
                println("$TAG Cookie Found: $cookie")
            }

            val headers = mutableMapOf(
                "User-Agent" to MOBILE_UA,
                "Referer" to "https://tvwiki5.net/", 
                "Accept" to "*/*"
            )
            if (cookie.isNotEmpty()) {
                headers["Cookie"] = cookie
            }

            val finalUrl = if (capturedUrl.contains("#")) capturedUrl.substringBefore("#") else capturedUrl

            if (proxyServer == null) {
                proxyServer = ProxyWebServer(TAG)
                proxyServer?.start()
            }
            proxyServer?.updateHeaders(headers)

            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(finalUrl, "UTF-8")
            val proxyUrl = "http://127.0.0.1:$port/playlist?url=$encodedUrl#.m3u8"

            callback(
                newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://tvwiki5.net/"
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
        
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()

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

                if (pathFull.startsWith("/playlist") || pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        val requestUrl = if (targetUrl.contains("#")) targetUrl.substringBefore("#") else targetUrl
                        
                        val response = runBlocking {
                            app.get(requestUrl, headers = currentHeaders)
                        }

                        if (response.isSuccessful) {
                            if (pathFull.startsWith("/playlist")) {
                                val content = response.text
                                // Rewriting M3U8 content with token override
                                val newContent = rewriteM3u8(content, requestUrl)
                                
                                val header = "HTTP/1.1 200 OK\r\n" +
                                           "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                           "Connection: close\r\n\r\n"
                                output.write(header.toByteArray())
                                output.write(newContent.toByteArray())
                            } else {
                                val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                                val header = "HTTP/1.1 200 OK\r\n" +
                                           "Content-Type: $contentType\r\n" +
                                           "Connection: close\r\n\r\n"
                                output.write(header.toByteArray())
                                output.write(response.body.bytes())
                            }
                        } else {
                            println("$tag [Proxy] Error ${response.code} requesting: $requestUrl")
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

        // --- MANUAL STRING PARSING HELPERS ---
        
        private fun parseQueryString(url: String): Map<String, String> {
            val params = mutableMapOf<String, String>()
            if (!url.contains("?")) return params
            
            val query = url.substringAfter("?")
            if (query.isBlank()) return params

            query.split("&").forEach { pair ->
                val parts = pair.split("=", limit = 2)
                if (parts.size == 2) {
                    params[parts[0]] = parts[1]
                }
            }
            return params
        }

        private fun rewriteM3u8(content: String, baseUrl: String): String {
            val lines = content.lines()
            val refinedLines = mutableListOf<String>()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            val playlistProxyBase = "http://127.0.0.1:$port/playlist?url="

            // 1. Manually extract fresh params from Parent URL
            val baseParams = parseQueryString(baseUrl)
            
            if (baseParams.isNotEmpty()) {
                println("$tag Extracted fresh params: ${baseParams.keys}")
            } else {
                println("$tag WARNING: No params found in base URL: $baseUrl")
            }

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val regex = Regex("""URI="([^"]+)"""")
                    val match = regex.find(trLine)
                    if (match != null) {
                        val keyUrl = match.groupValues[1]
                        // Overwrite logic applied
                        val absUrl = resolveUrlWithOverride(baseUrl, keyUrl, baseParams)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    val absUrl = resolveUrlWithOverride(baseUrl, trLine, baseParams)
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

        // Robust URL Resolver with Param Overwriting
        private fun resolveUrlWithOverride(base: String, url: String, freshParams: Map<String, String>): String {
            // 1. Get absolute URL
            var resolved = if (url.startsWith("http")) {
                url
            } else if (url.startsWith("//")) {
                "https:$url"
            } else {
                try {
                    // Manual path resolution to avoid URI class
                    if (url.startsWith("/")) {
                        val uri = URI(base)
                        "${uri.scheme}://${uri.host}$url"
                    } else {
                        val lastSlash = base.lastIndexOf('/')
                        if (lastSlash != -1) {
                            base.substring(0, lastSlash + 1) + url
                        } else {
                            "$base/$url"
                        }
                    }
                } catch (e: Exception) {
                    url
                }
            }

            if (freshParams.isEmpty()) return resolved

            // 2. Parse existing params of the child URL
            val urlParts = resolved.split("?", limit = 2)
            val baseUrlPart = urlParts[0]
            val existingQuery = if (urlParts.size > 1) urlParts[1] else ""
            
            val currentParams = mutableMapOf<String, String>()
            if (existingQuery.isNotEmpty()) {
                existingQuery.split("&").forEach { 
                    val p = it.split("=", limit = 2)
                    if (p.size == 2) currentParams[p[0]] = p[1]
                }
            }

            // 3. FORCE OVERWRITE: Replace existing tokens with fresh ones
            freshParams.forEach { (key, value) ->
                currentParams[key] = value
            }

            // 4. Rebuild
            val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return "$baseUrlPart?$newQuery"
        }
    }
}
