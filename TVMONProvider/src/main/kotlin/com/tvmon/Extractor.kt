package com.tvmon

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.* // 요청하신 import 추가
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.mapper
import kotlinx.coroutines.runBlocking // suspend 함수 호출을 위한 import
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Version: 4
 * Modification:
 * 1. Build Error Fix: app.get suspend function call inside ProxyWebServer
 * 2. Added imports
 * 3. Logic: Same as Version 3 (Key7 Dynamic Length Handling)
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
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. iframe 주소 따기
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        var capturedUrl: String? = null

        // 2. c.html 요청 납치
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
            }
        } catch (e: Exception) {}

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)

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

            val m3u8Url = "$capturedUrl#.m3u8"
            
            try {
                val m3u8Content = app.get(m3u8Url, headers = headers).text
                
                // Key7 패턴이 발견되면 프록시 서버 구동
                if (m3u8Content.contains("/v/key7")) {
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }

                    // Key URI를 로컬 프록시 주소로 변조
                    val newM3u8Content = m3u8Content.replace(
                        Regex("""URI="([^"]+)"""")
                    ) { matchResult ->
                        val originalKeyUrl = matchResult.groupValues[1]
                        if (originalKeyUrl.contains("/v/key7")) {
                            val encodedKeyUrl = java.net.URLEncoder.encode(originalKeyUrl, "UTF-8")
                            """URI="http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl""""
                        } else {
                            matchResult.value
                        }
                    }

                    proxyServer!!.setPlaylist(newM3u8Content)

                    callback(
                        newExtractorLink(name, name, "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8", ExtractorLinkType.M3U8) {
                            this.referer = "https://player.bunny-frame.online/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            // 일반 영상(Key7 아님)일 경우
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        
        return false
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
        try {
            val response = mapper.readValue(jsonString, Key7Response::class.java)
            // Base64 디코딩 (URL Safe 처리 포함)
            var data = try {
                Base64.decode(response.encryptedKey, Base64.URL_SAFE)
            } catch (e: Exception) {
                Base64.decode(response.encryptedKey, Base64.DEFAULT)
            }

            // [중요] decoy_shuffle 단계에서 결정된 "실제 세그먼트들의 길이"를 저장하는 변수
            var savedSegmentLengths: List<Int>? = null

            // 레이어를 역순으로 수행 (JSON 순서: Interleave -> ... -> Final)
            // 복호화 순서: Final -> ... -> Interleave
            for (layer in response.layers.reversed()) {
                data = when (layer.name) {
                    "final_encrypt" -> {
                        val mask = layer.xorMask!!.toByteArray()
                        ByteArray(data.size) { i ->
                            (data[i].toInt() xor mask[i % mask.size].toInt()).toByte()
                        }
                    }
                    "decoy_shuffle" -> {
                        // 1. Shuffled Blob 분해
                        // perm[i]는 현재 데이터의 i번째 덩어리가 원본의 몇 번째 조각인지를 나타냄
                        // 따라서 길이는 segmentLengths[perm[i]]를 사용
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

                        // 2. Real Positions 추출 및 연결
                        // 추출된 순서대로 길이를 저장하여 segment_noise에 전달
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
                        savedSegmentLengths = tempLengths // 저장
                        outputStream.toByteArray()
                    }
                    "segment_noise" -> {
                        // [핵심] decoy_shuffle에서 넘어온 실제 길이를 최우선 사용
                        // noise_lens와 실제 길이가 다를 경우(Case 3), 실제 길이를 따라야 함
                        val actualLengths = savedSegmentLengths ?: layer.noiseLens ?: emptyList()
                        
                        val chunks = ArrayList<ByteArray>()
                        var ptr = 0
                        
                        // 데이터 스트림을 순서대로 분할
                        for (len in actualLengths) {
                            if (ptr + len <= data.size) {
                                chunks.add(data.copyOfRange(ptr, ptr + len))
                                ptr += len
                            } else {
                                chunks.add(ByteArray(0))
                            }
                        }

                        // 키 바이트 추출
                        val perm = layer.perm ?: (0 until 16).toList()
                        val keyBytes = ByteArray(16)
                        
                        for (i in 0 until 16) {
                            if (i < chunks.size && chunks[i].isNotEmpty()) {
                                val keyIndex = perm[i]
                                if (keyIndex < 16) {
                                    keyBytes[keyIndex] = chunks[i][0] // 첫 바이트가 Key
                                }
                            }
                        }
                        keyBytes
                    }
                    "xor_chain" -> {
                        // Decrypt: P[0] = C[0] ^ IV, P[i] = C[i] ^ C[i-1]
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
            return data
        } catch (e: Exception) {
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
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { }
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
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray(charset("UTF-8")))
                } else if (path.contains("/key")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    
                    // [빌드 에러 수정] suspend 함수인 app.get을 runBlocking으로 감싸서 호출
                    val jsonResponse = runBlocking {
                        app.get(targetUrl, headers = currentHeaders).text
                    }
                    
                    val decryptedKey = BunnyPoorCdn().decryptKey7(jsonResponse)
                    
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(decryptedKey)
                }
                output.flush()
                socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }
    }
}
