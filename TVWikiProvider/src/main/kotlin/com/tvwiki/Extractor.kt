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
 * Version: [2026-02-13-V38-Key-Bridge-Reset]
 * - STRATEGY: "Alternative 3 - Bridge". Pre-downloads the Key byte array and serves it locally.
 * - FIX 1 (404): Corrects Key Domain. Forces Key URL to use 'player.bunny-frame.online' instead of CDN.
 * - FIX 2 (403): Bypasses Key blocking by serving the key from RAM (No network request by Player).
 * - RESET: Removed complex header logic. Uses simple System UA.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // 시스템 기본 UA 사용 (WebView와 일치)
    private val SYSTEM_UA = System.getProperty("http.agent") ?: "Mozilla/5.0 (Linux; Android 10; Mobile; rv:120.0) Gecko/120.0 Firefox/120.0"
    private val TAG = "[Bunny-V38-Bridge]"

    companion object {
        private var proxyServer: ProxyWebServerV38? = null
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
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to SYSTEM_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            
            // 1. M3U8 내용 다운로드
            val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
            val headers = mutableMapOf("User-Agent" to SYSTEM_UA, "Referer" to "https://tvwiki5.net/")
            if (cookie.isNotEmpty()) headers["Cookie"] = cookie

            var m3u8Content = ""
            try {
                m3u8Content = app.get(capturedUrl, headers = headers).text
            } catch (e: Exception) {
                println("$TAG M3U8 Download Failed: ${e.message}")
                return false
            }

            // 2. [Bridge Core] Key URL 찾기 및 도메인 교정
            var keyBytes: ByteArray? = null
            if (m3u8Content.contains("#EXT-X-KEY")) {
                val keyLine = m3u8Content.lines().find { it.startsWith("#EXT-X-KEY") }
                val uriMatch = Regex("""URI="([^"]+)"""").find(keyLine ?: "")
                if (uriMatch != null) {
                    val rawKeyPath = uriMatch.groupValues[1]
                    
                    // [404 해결] 키 도메인을 CDN이 아닌 플레이어 서버로 강제 지정
                    // capturedUrl(M3U8)에서 쿼리(토큰) 추출
                    val baseQuery = capturedUrl.substringAfter("?", "")
                    
                    // Key URL 재조립: https://player.bunny-frame.online + /v/key7... + ?token=...
                    val correctedKeyUrl = if (rawKeyPath.startsWith("http")) {
                        rawKeyPath // 이미 절대 경로면 유지 (보통 아님)
                    } else {
                        // 중요: 도메인을 mainUrl(player...)로 교체
                        "$mainUrl${if (rawKeyPath.startsWith("/")) "" else "/"}$rawKeyPath"
                    }
                    
                    // 토큰 붙이기
                    val finalKeyUrl = if (baseQuery.isNotEmpty() && !correctedKeyUrl.contains("token=")) {
                        "$correctedKeyUrl${if (correctedKeyUrl.contains("?")) "&" else "?"}$baseQuery"
                    } else correctedKeyUrl

                    println("$TAG Pre-fetching Key from: $finalKeyUrl")

                    // 3. [Bridge Core] 키 미리 다운로드 (Pre-load)
                    try {
                        // 키 요청 헤더: Referer를 플레이어 주소로 설정
                        val keyHeaders = mapOf(
                            "User-Agent" to SYSTEM_UA,
                            "Referer" to "$mainUrl/",
                            "Origin" to mainUrl
                        ).toMutableMap()
                        if (cookie.isNotEmpty()) keyHeaders["Cookie"] = cookie

                        val keyResponse = app.get(finalKeyUrl, headers = keyHeaders)
                        if (keyResponse.isSuccessful) {
                            keyBytes = keyResponse.body.bytes()
                            println("$TAG Key Downloaded! Size: ${keyBytes.size} bytes")
                        } else {
                            println("$TAG Key Fetch Error: ${keyResponse.code}")
                        }
                    } catch (e: Exception) {
                        println("$TAG Key Fetch Exception: ${e.message}")
                    }
                }
            }

            // 4. 프록시 서버 시작 (키 데이터 전달)
            if (proxyServer == null) {
                proxyServer = ProxyWebServerV38(TAG)
                proxyServer?.start()
            }
            // M3U8 원본, 쿠키, 그리고 **미리 받은 키**를 전달
            proxyServer?.updateContext(m3u8Content, capturedUrl, cookie, keyBytes)

            val port = proxyServer?.port ?: return false
            // 플레이어에게 줄 주소는 프록시의 로컬 주소
            val proxyUrl = "http://127.0.0.1:$port/playlist.m3u8"

            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = cleanUrl
                this.quality = Qualities.Unknown.value
            })
            return true
        }
        return false
    }

    class ProxyWebServerV38(private val tag: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        @Volatile private var m3u8Content: String = ""
        @Volatile private var m3u8Url: String = ""
        @Volatile private var currentCookie: String = ""
        @Volatile private var cachedKey: ByteArray? = null
        
        private val mobileUa = System.getProperty("http.agent") ?: "Mozilla/5.0"

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [ProxyV38] Started on port $port")
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

                // 1. 플레이리스트 요청 처리
                if (pathFull.contains("playlist.m3u8")) {
                    // M3U8 내용 변조: Key URL을 로컬 주소로 변경
                    var newContent = m3u8Content
                    if (cachedKey != null) {
                        // 키가 확보되었다면 로컬 키 주소로 교체
                        newContent = newContent.replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/local_key.bin"""")
                        println("$tag Serving Rewritten M3U8 (Key -> Local)")
                    } else {
                        // 키 확보 실패 시, 도메인이라도 고쳐서 시도 (Fallback)
                        println("$tag Serving M3U8 (Key Fallback Mode)")
                    }
                    
                    // TS 파일 경로 절대 경로화 (도메인 404 방지)
                    newContent = resolveTsPaths(newContent, m3u8Url)

                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                    output.write(newContent.toByteArray())
                } 
                // 2. 로컬 키 요청 처리 (핵심: 브릿지)
                else if (pathFull.contains("local_key.bin")) {
                    if (cachedKey != null) {
                        println("$tag Serving Key from Memory (${cachedKey!!.size} bytes)")
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\n\r\n".toByteArray())
                        output.write(cachedKey!!)
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                }
                // 3. TS 파일 프록시 (기존 방식 유지)
                else if (pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        val headers = mutableMapOf("User-Agent" to mobileUa, "Referer" to "https://player.bunny-frame.online/", "Accept" to "*/*")
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
            
            // TS 파일은 보통 CDN(poorcdn)에 있으므로 baseUrl(c.html의 주소)을 따름
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.isNotEmpty() && !line.startsWith("#")) {
                    val absUrl = try {
                        URI(baseUrl.substringBefore("#")).resolve(line).toString()
                    } catch (e: Exception) { line }
                    
                    // TS URL에도 토큰이 없으면 부모 토큰 주입 (안전장치)
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
