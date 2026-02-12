package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
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

/**
 * BunnyPoorCdn Extractor
 * Version: 2026-02-12-Proxy-Fix
 * - Solves 2001 Error (403): Injects Headers via Local Proxy
 * - Solves Data URI Crash: Serves content via HTTP (127.0.0.1)
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // Mobile UA (WebView 일치)
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    companion object {
        // 싱글톤 프록시 서버 관리
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

        // 1. WebView 로딩 (세션 생성)
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
            delay(1000) // 쿠키 동기화 대기
            
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

            // 2. 프록시 서버 구동 및 설정 업데이트
            if (proxyServer == null) {
                proxyServer = ProxyWebServer()
                proxyServer?.start()
            }
            // 이번 요청에 사용할 헤더 업데이트
            proxyServer?.updateHeaders(headers)

            // 3. 로컬 프록시 주소 반환
            // 플레이어는 127.0.0.1로 접속하고, 프록시가 실제 서버로 중계함
            val port = proxyServer?.port ?: return false
            val encodedUrl = URLEncoder.encode(finalUrl, "UTF-8")
            val proxyUrl = "http://127.0.0.1:$port/playlist?url=$encodedUrl"

            callback(
                newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    // 프록시를 타므로 헤더는 여기엔 필요 없지만, 혹시 모를 로직을 위해 남김
                }
            )
            return true
        }
        return false
    }

    // ==========================================
    // Local Proxy Server Class
    // ==========================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        // 멀티스레드 환경에서 안전하게 헤더 관리
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()

        fun start() {
            try {
                // 포트 0 = 랜덤 포트 자동 할당
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("[Proxy] Started on port $port")
                
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val client = serverSocket!!.accept()
                            handleClient(client)
                        } catch (e: Exception) {
                            // 소켓 닫힘 등
                        } 
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

                // Request Line: "GET /path?query HTTP/1.1"
                val parts = requestLine.split(" ")
                if (parts.size < 2) {
                    socket.close()
                    return@thread
                }
                
                val pathFull = parts[1]
                val output = socket.getOutputStream()

                // 1. M3U8 Playlist 요청 처리 (/playlist?url=...)
                if (pathFull.startsWith("/playlist")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        // 실제 M3U8 다운로드
                        val response = app.get(targetUrl, headers = currentHeaders)
                        if (response.isSuccessful) {
                            val content = response.text
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
                // 2. Proxy 요청 처리 (/proxy?url=...) - Key, TS 파일 등
                else if (pathFull.startsWith("/proxy")) {
                    val targetUrl = getQueryParam(pathFull, "url")
                    if (targetUrl != null) {
                        // 실제 리소스 다운로드 (바이너리)
                        val response = app.get(targetUrl, headers = currentHeaders)
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

        // M3U8 내용 중 URL을 로컬 프록시 주소로 변조
        private fun rewriteM3u8(content: String, baseUrl: String): String {
            val lines = content.lines()
            val newLines = mutableListOf<String>()
            val proxyBase = "http://127.0.0.1:$port/proxy?url="
            
            // 재귀 처리를 위한 Playlist Proxy
            val playlistProxyBase = "http://127.0.0.1:$port/playlist?url="

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (line.isEmpty()) continue
                
                if (line.startsWith("#EXT-X-KEY")) {
                    // 키 URL 변조
                    val regex = Regex("""URI="([^"]+)"""")
                    val match = regex.find(line)
                    if (match != null) {
                        val keyUrl = match.groupValues[1]
                        val absUrl = resolveUrl(baseUrl, keyUrl)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        val newLine = line.replace(keyUrl, "$proxyBase$encoded")
                        newLines.add(newLine)
                    } else {
                        newLines.add(line)
                    }
                } else if (line.startsWith("#EXT-X-STREAM-INF")) {
                    // Master Playlist의 경우 하위 M3U8도 /playlist로 처리해야 함
                    newLines.add(line)
                    val nextLine = lines.getOrNull(i + 1)?.trim()
                    if (nextLine != null && !nextLine.startsWith("#")) {
                         val absUrl = resolveUrl(baseUrl, nextLine)
                         val encoded = URLEncoder.encode(absUrl, "UTF-8")
                         newLines.add("$playlistProxyBase$encoded")
                         // 다음 라인(원본 URL)은 건너뛰도록 처리해야 하지만, 
                         // 루프가 단순 line 단위라 여기서 추가하고 루프에서 다음 라인을 무시하도록 로직이 복잡해질 수 있음.
                         // 하지만 여기선 단순히 replace만 하면 되는데 구조상 다음 라인일 확률이 100%이므로
                         // lines[i+1]을 수정하는 대신, 여기서 추가하고 i를 증가시키는게 맞음.
                         // 다만 코틀린 forEach/indices에선 인덱스 조작이 안되므로
                         // 여기서는 로직 단순화를 위해 "URL 라인"을 만났을 때 처리하는 아래 로직에 맡기되,
                         // 이것이 Stream Inf 직후인지 판단하는게 정확함.
                         // *단순화*: 모든 .m3u8 링크는 playlist로, 나머지는 proxy로.
                    }
                } else if (!line.startsWith("#")) {
                    // URL 라인 (TS 또는 M3U8)
                    val absUrl = resolveUrl(baseUrl, line)
                    val encoded = URLEncoder.encode(absUrl, "UTF-8")
                    
                    if (absUrl.contains(".m3u8")) {
                        newLines.add("$playlistProxyBase$encoded")
                    } else {
                        newLines.add("$proxyBase$encoded")
                    }
                } else {
                    newLines.add(line)
                }
            }
            
            // 중복 처리 방지 (위에서 #EXT-X-STREAM-INF 처리 시 다음 줄 건너뛰기 로직이 없어서 중복될 수 있음)
            // 따라서 위 로직을 수정하여, "URL 라인" 처리 부분에서 모든 것을 담당하게 함.
            
            val refinedLines = mutableListOf<String>()
            for (line in lines) {
                val trLine = line.trim()
                if (trLine.isEmpty()) continue
                
                if (trLine.startsWith("#EXT-X-KEY")) {
                    val regex = Regex("""URI="([^"]+)"""")
                    val match = regex.find(trLine)
                    if (match != null) {
                        val keyUrl = match.groupValues[1]
                        val absUrl = resolveUrl(baseUrl, keyUrl)
                        val encoded = URLEncoder.encode(absUrl, "UTF-8")
                        refinedLines.add(trLine.replace(keyUrl, "$proxyBase$encoded"))
                    } else {
                        refinedLines.add(trLine)
                    }
                } else if (!trLine.startsWith("#")) {
                    // URL
                    val absUrl = resolveUrl(baseUrl, trLine)
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

        private fun resolveUrl(base: String, url: String): String {
            if (url.startsWith("http")) return url
            if (url.startsWith("//")) return "https:$url"
            return try {
                URI(base).resolve(url).toString()
            } catch (e: Exception) {
                url
            }
        }
    }
}
