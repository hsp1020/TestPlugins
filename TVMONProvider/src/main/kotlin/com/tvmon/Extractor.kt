package com.tvmon

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.runBlocking
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Version: 12 (Debug Mode)
 * Modification:
 * 1. ADD: Extensive 'println' logs with [TVMON] tag for debugging
 * 2. Logic: Same as Version 11 (Strict Key7 Detection + Absolute Path Fix)
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVMON] getUrl started. URL: $url, Referer: $referer")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVMON] extract function called")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. iframe 주소 따기
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            println("[TVMON] Searching for iframe in: $cleanReferer")
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] Found iframe URL: $cleanUrl")
                } else {
                    println("[TVMON] No iframe found.")
                }
            } catch (e: Exception) {
                println("[TVMON] Error fetching referrer: ${e.message}")
            }
        }

        var capturedUrl: String? = null

        // [입력 URL 체크]
        if (cleanUrl.contains("/c.html") && cleanUrl.contains("token=")) {
            capturedUrl = cleanUrl
            println("[TVMON] Input URL is already c.html target: $capturedUrl")
        }

        // 2. c.html 요청 납치
        if (capturedUrl == null) {
            println("[TVMON] Starting WebViewResolver for c.html...")
            val resolver = WebViewResolver(
                interceptUrl = Regex("""/c\.html"""), 
                useOkhttp = false,
                timeout = 15000L
            )
            
            try {
                val requestHeaders = mapOf(
                    "Referer" to "https://tvmon.site/", 
                    "User-Agent" to DESKTOP_UA
                )

                val response = app.get(
                    url = cleanUrl,
                    headers = requestHeaders,
                    interceptor = resolver
                )
                
                if (response.url.contains("/c.html") && response.url.contains("token=")) {
                    capturedUrl = response.url
                    println("[TVMON] Captured c.html URL: $capturedUrl")
                } else {
                    println("[TVMON] Failed to capture c.html. Last URL: ${response.url}")
                }
            } catch (e: Exception) {
                println("[TVMON] Error during WebViewResolver: ${e.message}")
            }
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            println("[TVMON] Cookies found: ${cookie?.take(20)}...")

            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "sec-ch-ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\""
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }

            // 요청 URL 정리
            val requestUrl = capturedUrl!!.substringBefore("#")
            println("[TVMON] Requesting M3U8 content from: $requestUrl")
            
            try {
                // M3U8 내용 가져오기
                val m3u8Content = app.get(requestUrl, headers = headers).text
                println("[TVMON] M3U8 Content Fetched (Length: ${m3u8Content.length})")
                // println("[TVMON] Preview: ${m3u8Content.take(100)}") 

                // [Key7 판별 로직]
                val isKey7 = m3u8Content.lines().any { line ->
                    line.startsWith("#EXT-X-KEY") && line.contains("/v/key7")
                }
                println("[TVMON] isKey7 detected? $isKey7")

                if (isKey7) {
                    println("[TVMON] Starting Proxy Server...")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }
                    println("[TVMON] Proxy Server started on port: ${proxyServer!!.port}")

                    // Base URI 준비
                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { 
                        println("[TVMON] URI Creation Failed: ${e.message}"); null 
                    }
                    val sb = StringBuilder()

                    m3u8Content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach

                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val regex = Regex("""URI="([^"]+)"""")
                                val match = regex.find(trimmed)
                                if (match != null) {
                                    val originalKeyUrl = match.groupValues[1]
                                    println("[TVMON] Found Key URL: $originalKeyUrl")
                                    
                                    val absoluteKeyUrl = resolveUrl(baseUri, requestUrl, originalKeyUrl)
                                    println("[TVMON] Resolved Absolute Key URL: $absoluteKeyUrl")
                                    
                                    val encodedKeyUrl = java.net.URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                    val newLine = trimmed.replace(
                                        originalKeyUrl,
                                        "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl"
                                    )
                                    sb.append(newLine).append("\n")
                                } else {
                                    sb.append(trimmed).append("\n")
                                }
                            } else {
                                sb.append(trimmed).append("\n")
                            }
                        } else {
                            // 영상 세그먼트 변환
                            val absoluteSegmentUrl = resolveUrl(baseUri, requestUrl, trimmed)
                            // println("[TVMON] Segment Resolved: $absoluteSegmentUrl") // 너무 많으니 주석 처리
                            sb.append(absoluteSegmentUrl).append("\n")
                        }
                    }

                    proxyServer!!.setPlaylist(sb.toString())
                    val proxyUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                    println("[TVMON] Returning Proxy URL: $proxyUrl")

                    callback(
                        newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://player.bunny-frame.online/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return true
                } else {
                    println("[TVMON] Key7 not detected. Returning original URL.")
                }
                
                // Fallback (Key7 아님)
                callback(
                    newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                return true

            } catch (e: Exception) {
                println("[TVMON] Critical Error in extract: ${e.message}")
                e.printStackTrace()
            }
            
            // 예외 발생 시 Fallback
            callback(
                newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } else {
            println("[TVMON] capturedUrl is null. Extraction failed.")
        }
        
        return false
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        
        if (baseUri != null) {
            return try {
                baseUri.resolve(target).toString()
            } catch (e: Exception) {
                manualResolve(baseUrlStr, target)
            }
        }
        return manualResolve(baseUrlStr, target)
    }

    private fun manualResolve(base: String, target: String): String {
        if (target.startsWith("/")) {
            val hostUrl = if (base.indexOf("/", 8) != -1) base.substring(0, base.indexOf("/", 8)) else base
            return "$hostUrl$target"
        } else {
            val pathUrl = base.substringBeforeLast("/")
            return "$pathUrl/$target"
        }
    }

    // ==========================================
    // Key7 Decryption Logic
    // ==========================================

    data class Layer(
        @JsonProperty("name") val name: String,
        @JsonProperty("xor_mask") val xorMask: String? = null,
        @JsonProperty("pad_len") val padLen: Int? = null,
        @JsonProperty("segment_lengths") val segmentLengths: List<Int>? = null,
        @JsonProperty("real_positions") val realPositions: List<Int>? = null,
        @JsonProperty("init_key") val initKey: String? = null,
        @JsonProperty("noise_lens") val noiseLens: List<Int>? = null,
        @JsonProperty("perm") val perm: List<Int>? = null,
        @JsonProperty("rotations") val rotations: List<Int>? = null,
        @JsonProperty("inverse_sbox") val inverseSbox: String? = null
    )

    data class Key7Response(
        @JsonProperty("encrypted_key") val encryptedKey: String,
        @JsonProperty("layers") val layers: List<Layer>
    )

    private fun decryptKey7(jsonString: String): ByteArray {
        println("[TVMON] decryptKey7 called.")
        try {
            val response = mapper.readValue(jsonString, Key7Response::class.java)
            var data = try {
                Base64.decode(response.encryptedKey, Base64.URL_SAFE)
            } catch (e: Exception) {
                Base64.decode(response.encryptedKey, Base64.DEFAULT)
            }
            println("[TVMON] Initial Encrypted Key Length: ${data.size}")

            var savedSegmentLengths: List<Int>? = null

            for (layer in response.layers.reversed()) {
                // println("[TVMON] Processing Layer: ${layer.name}") // 레이어별 로그
                data = when (layer.name) {
                    "final_encrypt" -> {
                        val mask = layer.xorMask!!.toByteArray()
                        ByteArray(data.size) { i ->
                            (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
                        }
                    }
                    "decoy_shuffle" -> {
                        val segments = mutableMapOf<Int, ByteArray>()
                        val lengths = layer.segmentLengths!!
                        val perm = layer.perm!!
                        
                        var ptr = 0
                        for (i in perm.indices) {
                            val segmentId = perm[i]
                            val len = lengths[segmentId]
                            if (ptr + len <= data.size) {
                                segments[segmentId] = data.copyOfRange(ptr, ptr + len)
                                ptr += len
                            }
                        }

                        val realPositions = layer.realPositions!!
                        val outputStream = java.io.ByteArrayOutputStream()
                        val tempLengths = ArrayList<Int>()
                        
                        realPositions.forEach { id ->
                            val chunk = segments[id]
                            if (chunk != null) {
                                outputStream.write(chunk)
                                tempLengths.add(chunk.size)
                            }
                        }
                        savedSegmentLengths = tempLengths
                        println("[TVMON] Decoy Shuffle: Saved ${tempLengths.size} segment lengths.")
                        outputStream.toByteArray()
                    }
                    "segment_noise" -> {
                        val actualLengths = savedSegmentLengths ?: layer.noiseLens ?: emptyList()
                        val chunks = ArrayList<ByteArray>()
                        var ptr = 0
                        for (len in actualLengths) {
                            if (ptr + len <= data.size) {
                                chunks.add(data.copyOfRange(ptr, ptr + len))
                                ptr += len
                            } else {
                                chunks.add(ByteArray(0))
                            }
                        }
                        val perm = layer.perm ?: (0 until 16).toList()
                        val keyBytes = ByteArray(16)
                        for (i in 0 until 16) {
                            if (i < chunks.size && chunks[i].isNotEmpty()) {
                                val keyIndex = perm[i]
                                if (keyIndex < 16) keyBytes[keyIndex] = chunks[i][0]
                            }
                        }
                        keyBytes
                    }
                    "xor_chain" -> {
                        val initKeyBytes = Base64.decode(layer.initKey!!, Base64.DEFAULT)
                        val out = ByteArray(data.size)
                        if (data.isNotEmpty()) {
                            val iv = if (initKeyBytes.isNotEmpty()) initKeyBytes[0] else 0
                            out[0] = (data[0].toInt() xor iv.toInt()).toByte()
                            for (i in 1 until data.size) {
                                out[i] = (data[i].toInt() xor data[i-1].toInt()).toByte()
                            }
                        }
                        out
                    }
                    "bit_rotate" -> {
                        val rots = layer.rotations!!
                        ByteArray(data.size) { i ->
                            val rot = rots[i % rots.size]
                            val v = data[i].toInt() and 0xFF
                            val r = ((v ushr rot) or (v shl (8 - rot))) and 0xFF
                            r.toByte()
                        }
                    }
                    "sbox" -> {
                        val sbox = Base64.decode(layer.inverseSbox!!, Base64.DEFAULT)
                        ByteArray(data.size) { i ->
                            val index = data[i].toInt() and 0xFF
                            sbox[index]
                        }
                    }
                    "bit_interleave" -> {
                        val perm = layer.perm!!
                        val outBytes = ByteArray(16)
                        for (i in 0 until 128) {
                            val srcByteIdx = i / 8
                            val srcBitIdx = 7 - (i % 8)
                            val bit = (data[srcByteIdx].toInt() shr srcBitIdx) and 1
                            val destIdx = perm[i]
                            val destByteIdx = destIdx / 8
                            val destBitIdx = 7 - (destIdx % 8)
                            if (bit == 1) {
                                outBytes[destByteIdx] = (outBytes[destByteIdx].toInt() or (1 shl destBitIdx)).toByte()
                            }
                        }
                        outBytes
                    }
                    else -> data
                }
            }
            println("[TVMON] Decryption Complete. Final Key Length: ${data.size}")
            return data
        } catch (e: Exception) {
            println("[TVMON] Decryption Failed: ${e.message}")
            e.printStackTrace()
            return ByteArray(0)
        }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) { 
                    println("[TVMON] Proxy Server Thread Started.")
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {
                            // println("[TVMON] Proxy Accept Error: ${e.message}") 
                        } 
                    } 
                }
            } catch (e: Exception) { 
                println("[TVMON] Proxy Start Failed: ${e.message}")
            }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(h: Map<String, String>) {
            currentHeaders = h
        }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                println("[TVMON] Proxy Request: $line") // 요청 로그
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    println("[TVMON] Serving Playlist via Proxy")
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray(charset("UTF-8")))
                } else if (path.contains("/key")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    println("[TVMON] Serving Key via Proxy. Target: $targetUrl")
                    
                    val jsonResponse = runBlocking {
                        app.get(targetUrl, headers = currentHeaders).text
                    }
                    // println("[TVMON] Key JSON Fetched: ${jsonResponse.take(50)}...")
                    
                    val decryptedKey = BunnyPoorCdn().decryptKey7(jsonResponse)
                    
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(decryptedKey)
                    println("[TVMON] Key Served Successfully.")
                }
                output.flush()
                socket.close()
            } catch (e: Exception) { 
                println("[TVMON] Proxy Client Error: ${e.message}")
                try { socket.close() } catch(e2:Exception){} 
            }
        }
    }
}
