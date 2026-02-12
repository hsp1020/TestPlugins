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
 * Version: [2026-02-13-V36-NoCookie-FullMask]
 * - FACT: User confirmed NO COOKIES. Removed all cookie logic.
 * - FIX: Implemented Full Chrome Header Masking (Sec-Ch-Ua, etc.) to bypass Cloudflare without cookies.
 * - FIX: Hardcoded modern Chrome UA.
 * - LOGIC: Force Token Sync kept active for sub-resources.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // 최신 Chrome Android UA 고정
    private val CHROME_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36"
    private val TAG = "[Bunny-V36]"

    companion object {
        private var proxyServer: ProxyWebServerV36? = null
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
            // WebView 요청 시에도 Chrome UA 사용
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to CHROME_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG Captured: $capturedUrl")
            // 쿠키 관련 로직 전부 삭제함

            if (proxyServer == null) {
                proxyServer = ProxyWebServerV36(TAG, CHROME_UA)
                proxyServer?.start()
            }
            // 전체 플레이어 URL을 Referer용으로 전달
            proxyServer?.updateContext(cleanUrl)

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

    class ProxyWebServerV36(private val tag: String, private val userAgent: String) {
        private var serverSocket: ServerSocket? = null
        var port: Int = 0
        @Volatile private var fullPlayerUrl: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                println("$tag [ProxyV36] Started on port $port")
                thread(isDaemon = true) { 
                    while (serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag Start Failed: $e") }
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
                    // Referer: M3U8 요청시엔 Player URL, 나머지는 M3U8 URL(을 모르니 일단 Player URL 시도)
                    // V14 성공 사례: M3U8 요청 시 Player URL Referer가 먹혔음.
                    val referer = fullPlayerUrl
                    
                    // [핵심] Cloudflare 우회용 풀세트 헤더 (쿠키 없음)
                    val headers = mutableMapOf(
                        "Host" to URI(requestUrl).host,
                        "Connection" to "keep-alive",
                        "sec-ch-ua" to "\"Chromium\";v=\"130\", \"Android WebView\";v=\"130\", \"Not?A_Brand\";v=\"99\"",
                        "sec-ch-ua-mobile" to "?1",
                        "sec-ch-ua-platform" to "\"Android\"",
                        "Upgrade-Insecure-Requests" to "1",
                        "User-Agent" to userAgent,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
                        "Sec-Fetch-Site" to "cross-site", // 초기 진입은 cross-site
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-User" to "?1",
                        "Sec-Fetch-Dest" to "iframe",
                        "Referer" to referer,
                        "Accept-Encoding" to "gzip, deflate, br",
                        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                    )

                    // Key/TS 요청일 경우 헤더 미세 조정
                    if (!isPlaylist) {
                        headers["Sec-Fetch-Dest"] = "empty"
                        headers["Sec-Fetch-Mode"] = "cors"
                        headers["Sec-Fetch-Site"] = "same-origin" // 도메인이 같다고 가정
                        headers.remove("Upgrade-Insecure-Requests")
                        headers.remove("Sec-Fetch-User")
                    }

                    val response = runBlocking { app.get(requestUrl, headers = headers) }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val content = response.text
                            val newContent = rewriteM3u8(content, requestUrl)
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
                        println("$tag [Proxy Error] ${response.code} for: $requestUrl")
                        if (response.code == 403) {
                             // 디버깅용: HTML 본문 로그 출력
                             println("$tag [CRITICAL BODY] ${response.text.take(300)}")
                        }
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
            // 토큰 강제 최신화
            listOf("token", "expires", "sig", "t").forEach { k ->
                if (freshParams.containsKey(k)) currentParams[k] = freshParams[k]!!
            }
            val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
            return if (newQuery.isNotEmpty()) "$baseUrlOnly?$newQuery" else resolved
        }
    }
}
