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
 * Version: [2026-02-12-V10-SecHeaders]
 * - NEW: Implements Sec-Fetch-* headers to bypass advanced CDN protection.
 * - NEW: Stop token override (Key tokens are unique and must not be changed).
 * - FIX: Strict Directory-based URL resolution for 'c.html' files.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val PLAYER_REFERER = "https://player.bunny-frame.online/"
    private val TAG = "[BunnyPoorCdn-V10-Sec]"

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
            
            // Cookie sync
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl) ?: ""

            if (proxyServer == null) {
                proxyServer = ProxyWebServer(TAG)
                proxyServer?.start()
            }
            
            // Proxy Context 업데이트
            proxyServer?.updateContext(cookie, capturedUrl)

            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(capturedUrl, "UTF-8")
            
            // 힌트(#.m3u8)는 Cloudstream 앱의 HLS 인식을 위해 끝에만 붙입니다.
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
        @Volatile private var currentReferer: String = ""
        
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

        fun updateContext(cookie: String, playlistUrl: String) {
            currentCookie = cookie
            currentReferer = playlistUrl
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
                    
                    // [핵심: 신규 헤더 전략] Sec-Fetch 계열 헤더 추가
                    val headers = mutableMapOf(
                        "User-Agent" to mobileUa,
                        "Referer" to playerReferer, // Playlist와 Key 모두 Player 도메인으로 통일 (로그 분석 결과)
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
                            // [핵심: 새로운 리라이팅] 억지 토큰 주입 제거
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

            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val uriMatch = Regex("""URI="([^"]+)"""").find(trLine)
                    if (uriMatch != null) {
                        val keyUrl = uriMatch.groupValues[1]
                        // [Fix] 단순 절대 경로 변환만 수행 (토큰 덮어쓰기 안함)
                        val absUrl = resolveUrlSimple(baseUrl, keyUrl)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    // [Fix] TS 조각 절대 경로 변환
                    val absUrl = resolveUrlSimple(baseUrl, trLine)
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

        // [Fix] 억지 로직을 제거한 순수 절대 경로 변환기
        private fun resolveUrlSimple(base: String, url: String): String {
            if (url.startsWith("http")) return url
            if (url.startsWith("//")) return "https:$url"
            
            return try {
                // c.html이 파일명이므로, 마지막 슬래시까지만 자르고 붙입니다.
                val baseDir = if (base.contains("?")) base.substringBefore("?") else base
                val lastSlash = baseDir.lastIndexOf('/')
                if (lastSlash != -1) {
                    val dir = baseDir.substring(0, lastSlash + 1)
                    dir + url
                } else {
                    "$base/$url"
                }
            } catch (e: Exception) {
                url
            }
        }
    }
}
