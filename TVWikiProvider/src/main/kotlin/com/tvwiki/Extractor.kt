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
 * Version: [2026-02-13-V29-Cookie-Deep-Scan]
 * - PURPOSE: DIAGNOSIS ONLY.
 * - CHECK 1: Dumps ALL cookies from CookieManager for related domains.
 * - CHECK 2: Downloads c.html content and prints it (Is it M3U8 or HTML error?).
 * - CHECK 3: Tries to fetch the KEY url inside c.html and prints server response.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val MOBILE_UA = System.getProperty("http.agent") ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    private val TAG = "[Bunny-V29]"

    companion object {
        private var proxyServer: ProxyWebServerV29? = null
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
            val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to MOBILE_UA), interceptor = resolver)
            if (interceptRegex.containsMatchIn(response.url)) capturedUrl = response.url
        } catch (e: Exception) { println("$TAG WebView failed: ${e.message}") }

        if (capturedUrl != null) {
            println("$TAG ================= DIAGNOSIS START =================")
            println("$TAG Captured URL: $capturedUrl")
            
            // [진단 1] 쿠키 상태 확인
            val cookieManager = CookieManager.getInstance()
            val domains = listOf(
                capturedUrl,
                "https://player.bunny-frame.online",
                "https://poorcdn.com",
                "https://every9.poorcdn.com"
            )
            
            var validCookie = ""
            domains.forEach { domain ->
                val c = cookieManager.getCookie(domain)
                println("$TAG Cookie for [$domain]: ${c ?: "NULL/EMPTY"}")
                if (!c.isNullOrEmpty()) validCookie = c
            }
            
            // [진단 2] c.html 실제 내용물 확인 (HTTP Request)
            try {
                println("$TAG Fetching M3U8 Content...")
                val m3u8Headers = mapOf(
                    "User-Agent" to MOBILE_UA,
                    "Referer" to "https://tvwiki5.net/", // 일단 V14 성공 케이스 기준
                    "Cookie" to validCookie
                )
                val m3u8Response = app.get(capturedUrl, headers = m3u8Headers)
                println("$TAG M3U8 Response Code: ${m3u8Response.code}")
                println("$TAG M3U8 Content Type: ${m3u8Response.headers["Content-Type"]}")
                
                val body = m3u8Response.text
                if (body.length > 500) {
                     println("$TAG M3U8 Body (First 500 chars):\n${body.substring(0, 500)}...")
                } else {
                     println("$TAG M3U8 Body:\n$body")
                }

                // [진단 3] Key URL 추출 및 테스트
                if (body.contains("#EXT-X-KEY")) {
                    val keyLine = body.lines().find { it.startsWith("#EXT-X-KEY") }
                    val uriMatch = Regex("""URI="([^"]+)"""").find(keyLine ?: "")
                    if (uriMatch != null) {
                        val keyRelPath = uriMatch.groupValues[1]
                        println("$TAG Found Key Path: $keyRelPath")
                        
                        // 절대 경로 변환 (표준)
                        val keyAbsUrl = URI(capturedUrl).resolve(keyRelPath).toString()
                        println("$TAG Testing Key URL: $keyAbsUrl")
                        
                        // 키 요청 테스트
                        val keyHeaders = mapOf(
                            "User-Agent" to MOBILE_UA,
                            "Referer" to capturedUrl, // M3U8을 Referer로
                            "Cookie" to validCookie
                        )
                        val keyResponse = app.get(keyAbsUrl, headers = keyHeaders)
                        println("$TAG Key Response Code: ${keyResponse.code}")
                        println("$TAG Key Content Type: ${keyResponse.headers["Content-Type"]}")
                        println("$TAG Key Body Length: ${keyResponse.text.length} bytes")
                        if (keyResponse.text.length > 100) {
                            println("$TAG Key Body (Likely Error HTML):\n${keyResponse.text.take(300)}")
                        } else {
                            println("$TAG Key seems valid (Binary data)")
                        }
                    }
                }

            } catch (e: Exception) {
                println("$TAG Diagnosis Error: ${e.message}")
                e.printStackTrace()
            }
            println("$TAG ================= DIAGNOSIS END ===================")

            // 기존 로직 유지 (재생 시도는 함)
            if (proxyServer == null) {
                proxyServer = ProxyWebServerV29(TAG)
                proxyServer?.start()
            }
            proxyServer?.updateContext(validCookie, capturedUrl)

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

    class ProxyWebServerV29(private val tag: String) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentCookie: String = ""
        @Volatile private var m3u8FullUrl: String = ""
        private val mobileUa = System.getProperty("http.agent") ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                println("$tag [ProxyV29] Started on port $port")
                thread(isDaemon = true) { 
                    while (isRunning && serverSocket != null) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) { } 
                    } 
                }
            } catch (e: Exception) { println("$tag [ProxyV29] Start Failed: $e") }
        }

        fun updateContext(cookie: String, m3u8Url: String) { 
            currentCookie = cookie 
            m3u8FullUrl = m3u8Url
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
                    
                    val referer = if (requestUrl.contains("c.html")) "https://tvwiki5.net/" else m3u8FullUrl
                    val headers = mutableMapOf(
                        "User-Agent" to mobileUa,
                        "Referer" to referer,
                        "Accept" to "*/*"
                    )
                    if (currentCookie.isNotEmpty()) headers["Cookie"] = currentCookie

                    val response = runBlocking { app.get(requestUrl, headers = headers) }

                    if (response.isSuccessful) {
                        if (pathFull.startsWith("/playlist")) {
                            val newContent = rewriteM3u8(response.text, requestUrl)
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(newContent.toByteArray())
                        } else {
                            val contentType = response.headers["Content-Type"] ?: "application/octet-stream"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(response.body.bytes())
                        }
                    } else {
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
            listOf("token", "expires", "sig", "t").forEach { k ->
                if (freshParams.containsKey(k)) currentParams[k] = freshParams[k]!!
            }
            return if (currentParams.isNotEmpty()) {
                val newQuery = currentParams.entries.joinToString("&") { "${it.key}=${it.value}" }
                "$baseUrlOnly?$newQuery"
            } else resolved
        }
    }
}
