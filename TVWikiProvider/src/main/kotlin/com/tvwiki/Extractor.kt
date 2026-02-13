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
 * Version: [2026-02-13-V39-Final-Bridge]
 * - COMBINATION: V36's Chrome UA (Bypass CF) + V38's Key Pre-loading (Bypass Key 403).
 * - FIX: Corrects Key Domain to 'player.bunny-frame.online' (Fixes V36's 404).
 * - STRATEGY: Pre-fetches key in memory and serves it via local proxy (Zero network risk for player).
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [필수] Cloudflare 우회를 위한 Chrome UA (V36 성공 요인)
    private val CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val TAG = "[Bunny-V39]"

    companion object {
        private var proxyServer: ProxyWebServerV39? = null
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
            // Chrome UA 사용
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to CHROME_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            
            val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
            // M3U8 요청 시 메인 사이트 Referer 사용 (V14/V36 성공 요인)
            val m3u8Headers = mutableMapOf("User-Agent" to CHROME_UA, "Referer" to "https://tvwiki5.net/")
            if (cookie.isNotEmpty()) m3u8Headers["Cookie"] = cookie

            var m3u8Content = ""
            try {
                m3u8Content = app.get(capturedUrl, headers = m3u8Headers).text
                // [검증] M3U8이 맞는지 확인
                if (!m3u8Content.contains("#EXTM3U")) {
                    println("$TAG Critical: Downloaded content is NOT M3U8! Body:\n${m3u8Content.take(200)}")
                    return false
                }
            } catch (e: Exception) {
                println("$TAG M3U8 Download Failed: ${e.message}")
                return false
            }

            // [Bridge Core] 키 찾기 및 미리 다운로드
            var keyBytes: ByteArray? = null
            if (m3u8Content.contains("#EXT-X-KEY")) {
                val keyLine = m3u8Content.lines().find { it.startsWith("#EXT-X-KEY") }
                val uriMatch = Regex("""URI="([^"]+)"""").find(keyLine ?: "")
                if (uriMatch != null) {
                    val rawKeyPath = uriMatch.groupValues[1]
                    val baseQuery = capturedUrl.substringAfter("?", "")
                    
                    // [404 해결] 키 도메인을 플레이어 서버로 강제 변경
                    val correctedKeyUrl = if (rawKeyPath.startsWith("http")) {
                        rawKeyPath 
                    } else {
                        "$mainUrl${if (rawKeyPath.startsWith("/")) "" else "/"}$rawKeyPath"
                    }
                    
                    // 토큰이 없으면 부모 토큰 주입
                    val finalKeyUrl = if (baseQuery.isNotEmpty() && !correctedKeyUrl.contains("token=")) {
                        "$correctedKeyUrl${if (correctedKeyUrl.contains("?")) "&" else "?"}$baseQuery"
                    } else correctedKeyUrl

                    println("$TAG Pre-fetching Key: $finalKeyUrl")

                    try {
                        // 키 요청 시에는 플레이어 주소를 Referer로 사용
                        val keyHeaders = mapOf(
                            "User-Agent" to CHROME_UA,
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        ).toMutableMap()
                        if (cookie.isNotEmpty()) keyHeaders["Cookie"] = cookie

                        val keyResponse = app.get(finalKeyUrl, headers = keyHeaders)
                        if (keyResponse.isSuccessful) {
                            keyBytes = keyResponse.body.bytes()
                            println("$TAG Key Downloaded Success! (${keyBytes.size} bytes)")
                        } else {
                            println("$TAG Key Fetch Error: ${keyResponse.code}")
                        }
                    } catch (e: Exception) {
                        println("$TAG Key Fetch Exception: ${e.message}")
                    }
                }
            }

            if (proxyServer == null) {
                proxyServer = ProxyWebServerV39(TAG, CHROME_UA)
                proxyServer?.start()
            }
            
            // 확보한 키와 M3U8 정보 전달
            proxyServer?.updateContext(m3u8Content, capturedUrl, cookie, keyBytes)

            val port = proxyServer?.port ?: return false
            // 플레이어에게는 로컬 주소 제공
            val proxyUrl = "http://127.0.0.1:$port/playlist.m3u8"

            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = cleanUrl
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    class ProxyWebServerV39(private val tag: String, private val userAgent: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var m3u8Content: String = ""
        @Volatile private var m3u8Url: String = ""
        @Volatile private var currentCookie: String = ""
        @Volatile private var cachedKey: ByteArray? = null
        
        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [ProxyV39] Started on port $port")
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag Start Failed: $e") }
        }

        fun updateContext(content: String, url: String, cookie: String, key: ByteArray?) { 
            m3u8Content = content
            m3u8Url = url
            currentCookie = cookie
            cachedKey = key
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

                // 1. M3U8 요청
                if (pathFull.contains("playlist.m3u8")) {
                    var newContent = m3u8Content
                    
                    // 키 URL을 로컬 주소로 변경
                    if (cachedKey != null) {
                        newContent = newContent.replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/local_key.bin"""")
                    }
                    
                    // TS 경로 절대 경로화 (CDN 주소 유지)
                    newContent = resolveTsPaths(newContent, m3u8Url)

                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(newContent.toByteArray())
                } 
                // 2. 키 요청 (메모리에서 즉시 서빙)
                else if (pathFull.contains("local_key.bin")) {
                    if (cachedKey != null) {
                        println("$tag Serving Cached Key")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(cachedKey!!)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                // 3. TS 프록시 (영상)
                else if (pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        // TS 요청 시에는 M3U8 주소(CDN) 또는 플레이어 주소를 Referer로
                        val headers = mutableMapOf(
                            "User-Agent" to userAgent,
                            "Referer" to "https://player.bunny-frame.online/",
                            "Accept" to "*/*"
                        )
                        if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie

                        val response = runBlocking { app.get(targetUrl, headers = headers) }
                        if (response.isSuccessful) {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun resolveTsPaths(content: String, baseUrl: String): String {
            val lines = content.lines().toMutableList()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    // TS는 CDN(poorcdn)에 있으므로 baseUrl 기준 해석
                    val absUrl = try {
                        URI(baseUrl.substringBefore("#")).resolve(line).toString()
                    } catch (e: Exception) { line }
                    
                    // TS에도 토큰 주입 (안전장치)
                    val finalUrl = if (!absUrl.contains("token=") && baseUrl.contains("?")) {
                        "$absUrl&${baseUrl.substringAfter("?")}"
                    } else absUrl

                    lines[i] = "$proxyBase${URLEncoder.encode(finalUrl, "UTF-8")}"
                }
            }
            return lines.joinToString("\n")
        }

        private fun getQueryParam(path: String, key: String): String? {
            return try {
                path.substringAfter("?").split("&").find { it.startsWith("$key=") }?.substringAfter("=")?.let { URLDecoder.decode(it, "UTF-8") }
            } catch (e: Exception) { null }
        }
    }
}
