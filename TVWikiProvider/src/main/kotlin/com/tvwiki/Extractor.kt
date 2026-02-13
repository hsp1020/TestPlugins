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
import kotlin.random.Random

/**
 * BunnyPoorCdn Extractor
 * Version: [2026-02-13-V42-Hybrid-Fix]
 * - LOGIC: Combines V34 (Referer Separation) + V41 (Domain Correction).
 * - FIX: M3U8 requests use 'tvwiki5.net' Referer (Bypasses CF 403 on Playlist).
 * - FIX: Key/TS requests use 'player.bunny...' Referer + Domain Correction (Bypasses 404/403 on Key).
 * - UA: Chrome UA fixed.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val TAG = "[Bunny-V42-${Random.nextInt(999)}]"

    companion object {
        private var proxyServer: ProxyWebServerV42? = null
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
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to CHROME_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            
            // 쿠키가 없다는 것이 확인되었으므로 쿠키 로직 제거

            if (proxyServer == null) {
                proxyServer = ProxyWebServerV42(TAG, CHROME_UA)
                proxyServer?.start()
            } else {
                proxyServer?.stop()
                proxyServer = ProxyWebServerV42(TAG, CHROME_UA)
                proxyServer?.start()
            }
            
            proxyServer?.updateContext(cleanUrl) // cleanUrl = Full Player URL

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

    class ProxyWebServerV42(private val tag: String, private val userAgent: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var fullPlayerUrl: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [ProxyV42] Started on port $port")
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag Start Failed: $e") }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close() } catch (e: Exception) {}
        }

        fun updateContext(playerUrl: String) { 
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
                    
                    val isPlaylist = requestUrl.contains("c.html")
                    
                    // [핵심 1: Referer 분리]
                    // c.html 요청 시 -> 메인 사이트 Referer (V14 성공 요인)
                    // Key/TS 요청 시 -> 플레이어 사이트 Referer (브라우저 동작)
                    val referer = if (isPlaylist) "https://tvwiki5.net/" else "https://player.bunny-frame.online/"
                    val origin = if (isPlaylist) "https://tvwiki5.net" else "https://player.bunny-frame.online"
                    
                    val headers = mutableMapOf(
                        "User-Agent" to userAgent,
                        "Referer" to referer,
                        "Origin" to origin,
                        "Accept" to "*/*"
                    )

                    val response = runBlocking { app.get(requestUrl, headers = headers) }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val content = response.text
                            // [핵심 2: 토큰 강제 동기화 + 도메인 교정]
                            val newContent = rewriteM3u8(content, requestUrl)
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
                        println("$tag [Proxy Error] ${response.code} for: $requestUrl (Ref: $referer)")
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
            val baseParams = parseQuery(baseUrl)

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trLine)
                    if (uriMatch != null) {
                        val keyUrl = uriMatch.groupValues[1]
                        
                        // [핵심 3: 키 도메인 강제 변경] (V41에서 배운 404 해결책)
                        val correctedKeyUrl = if (keyUrl.startsWith("http")) keyUrl 
                        else "https://player.bunny-frame.online${if (keyUrl.startsWith("/")) "" else "/"}$keyUrl"

                        // 토큰 강제 주입
                        val absUrl = resolveForceSync(correctedKeyUrl, "", baseParams)
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase${URLEncoder.encode(absUrl, "UTF-8")}"))
                    } else { refinedLines.add(trLine) }
                } else if (!trLine.startsWith("#")) {
                    val absUrl = resolveForceSync(baseUrl, trLine, baseParams)
                    val encoded = URLEncoder.encode(absUrl, "UTF-8")
                    if (absUrl.contains(".m3u8") || absUrl.contains("c.html")) {
                        refinedLines.add("http://127.0.0.1:$port/playlist?url=$encoded")
                    } else {
                        refinedLines.add("$proxyBase$encoded")
                    }
                } else { refinedLines.add(trLine) }
            }
            return refinedLines.joinToString("\n")
        }

        private fun resolveForceSync(base: String, path: String, freshParams: Map<String, String>): String {
            var resolved = if (path.isEmpty()) base else {
                 try { URI(base.substringBefore("#")).resolve(path).toString() } catch (e: Exception) { base }
            }
            val baseUrlOnly = resolved.substringBefore("?")
            val currentParams = parseQuery(resolved).toMutableMap()
            
            // 토큰 강제 최신화
            listOf("token", "expires", "sig", "t").forEach { k ->
                if (freshParams.containsKey(k)) currentParams[k] = freshParams[k]!!
            }
            val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return if (newQuery.isNotEmpty()) "$baseUrlOnly?$newQuery" else resolved
        }
    }
}
