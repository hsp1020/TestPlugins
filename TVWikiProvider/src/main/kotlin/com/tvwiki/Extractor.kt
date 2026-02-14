package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread
import org.json.JSONObject
import android.util.Base64

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // Fiddler로 검증된 최신 Windows Chrome User-Agent
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        // 프록시 서버 인스턴스 (중복 실행 방지)
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 새 영상 재생 시 기존 프록시 종료
        proxyServer?.stop()
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // 1. iframe 주소 따기
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        var capturedUrl: String? = null

        // 2. c.html 요청 가로채기
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        try {
            val requestHeaders = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA)
            val response = app.get(url = cleanUrl, headers = requestHeaders, interceptor = resolver)
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (capturedUrl != null) {
            // 쿠키 및 헤더 준비
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                // 3. M3U8 내용 다운로드 및 분석
                val m3u8Content = app.get(capturedUrl, headers = headers).text
                
                // [핵심] 사용자가 확인한 패턴: URI="/v/key7..." 감지
                val keyMatch = Regex("""URI="([^"]*key7[^"]*)"""").find(m3u8Content)
                
                if (keyMatch != null) {
                    println("[BunnyPoorCdn] 최신 암호화(Key7) 감지됨. 복호화 시도.")
                    
                    // 3-1. 키 JSON 주소 추출 및 절대 경로 변환
                    var keyUrl = keyMatch.groupValues[1].replace("&amp;", "&")
                    
                    // 만약 keyUrl이 "/v/key7..." 처럼 상대경로라면 도메인을 붙여줌
                    if (!keyUrl.startsWith("http")) {
                        val uri = URI(capturedUrl)
                        val domain = "${uri.scheme}://${uri.host}"
                        if (keyUrl.startsWith("/")) {
                            keyUrl = "$domain$keyUrl"
                        } else {
                            // 현재 경로 기준 (드문 케이스지만 대비)
                            val basePath = capturedUrl.substringBeforeLast("/")
                            keyUrl = "$basePath/$keyUrl"
                        }
                    }
                    
                    println("[BunnyPoorCdn] Key JSON 요청 URL: $keyUrl")
                    
                    // 3-2. JSON 다운로드 및 복호화
                    val jsonStr = app.get(keyUrl, headers = headers).text
                    val decryptedKey = decryptKey7(jsonStr)

                    if (decryptedKey != null) {
                        println("[BunnyPoorCdn] 키 복호화 성공.")
                        
                        // 3-3. 프록시 서버 가동
                        // 프록시는 1) 변조된 M3U8 제공 2) 복호화된 Key 제공
                        proxyServer = ProxyWebServer(decryptedKey, m3u8Content, capturedUrl).apply {
                            start()
                        }
                        
                        // 3-4. 앱에는 로컬 프록시 주소를 전달
                        val proxyUrl = "http://127.0.0.1:${proxyServer!!.port}/video.m3u8"
                        
                        callback(
                            newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                                this.referer = "https://player.bunny-frame.online/"
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                        return true
                    } else {
                        println("[BunnyPoorCdn] 키 복호화 실패.")
                    }
                }
            } catch (e: Exception) {
                println("[BunnyPoorCdn] Key7 분석 실패, 일반 모드로 진행: $e")
                e.printStackTrace()
            }

            // [일반 영상] 혹은 복호화 실패 시 기존 방식대로 전달
            val finalUrl = "$capturedUrl#.m3u8"
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        
        return false
    }

    // ===========================================================================================
    // JSON 복호화 로직 (Reverse Layers)
    // ===========================================================================================
    private fun decryptKey7(jsonStr: String): ByteArray? {
        try {
            val json = JSONObject(jsonStr)
            val encryptedKeyB64 = json.getString("encrypted_key")
            var data = Base64.decode(encryptedKeyB64, Base64.URL_SAFE)
            val layers = json.getJSONArray("layers")
            
            // 레이어 역순 실행
            for (i in layers.length() - 1 downTo 0) {
                val layer = layers.getJSONObject(i)
                when (layer.getString("name")) {
                    "final_encrypt" -> {
                        val mask = Base64.decode(layer.getString("xor_mask"), Base64.DEFAULT)
                        for (j in data.indices) data[j] = (data[j].toInt() xor mask[j % mask.size].toInt()).toByte()
                    }
                    "decoy_shuffle" -> {
                        val segLens = layer.getJSONArray("segment_lengths")
                        val realPos = layer.getJSONArray("real_positions")
                        val segments = mutableListOf<ByteArray>()
                        var offset = 0
                        for (k in 0 until segLens.length()) {
                            val len = segLens.getInt(k)
                            segments.add(data.copyOfRange(offset, offset + len))
                            offset += len
                        }
                        val realKey = ByteArray(16)
                        for (k in 0 until 16) realKey[k] = segments[realPos.getInt(k)][0]
                        data = realKey
                    }
                    "xor_chain" -> {
                        val initKey = Base64.decode(layer.getString("init_key"), Base64.DEFAULT)
                        for (j in data.indices) data[j] = (data[j].toInt() xor initKey[j % initKey.size].toInt()).toByte()
                    }
                    "segment_noise" -> {
                        val perm = layer.getJSONArray("perm")
                        val newData = ByteArray(16)
                        for (j in 0 until 16) newData[j] = data[perm.getInt(j)]
                        data = newData
                    }
                    "bit_rotate" -> {
                        val rotations = layer.getJSONArray("rotations")
                        val newData = ByteArray(16)
                        for (j in 0 until 16) {
                            val rot = rotations.getInt(j % 8)
                            val b = data[j].toInt() and 0xFF
                            newData[j] = (((b ushr rot) or (b shl (8 - rot))) and 0xFF).toByte()
                        }
                        data = newData
                    }
                    "sbox" -> {
                        val invSbox = Base64.decode(layer.getString("inverse_sbox"), Base64.URL_SAFE)
                        for (j in data.indices) data[j] = invSbox[data[j].toInt() and 0xFF]
                    }
                    "bit_interleave" -> {
                        val perm = layer.getJSONArray("perm")
                        val bits = IntArray(128)
                        for (j in 0 until 16) {
                            val b = data[j].toInt() and 0xFF
                            for (bit in 0 until 8) bits[j * 8 + bit] = (b shr (7 - bit)) and 1
                        }
                        val newBits = IntArray(128)
                        for (j in 0 until 128) newBits[j] = bits[perm.getInt(j)]
                        val newData = ByteArray(16)
                        for (j in 0 until 16) {
                            var b = 0
                            for (bit in 0 until 8) b = (b shl 1) or newBits[j * 8 + bit]
                            newData[j] = b.toByte()
                        }
                        data = newData
                    }
                }
            }
            return data
        } catch (e: Exception) { return null }
    }

    // ===========================================================================================
    // 로컬 프록시 서버
    // ===========================================================================================
    class ProxyWebServer(
        private val key: ByteArray,
        private val originalM3u8: String,
        private val originalUrl: String
    ) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0

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
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    socket.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val line = reader.readLine() ?: return@thread
                    val path = line.split(" ").getOrNull(1) ?: ""
                    val output = socket.getOutputStream()

                    if (path.contains("/video.m3u8")) {
                        // M3U8 변조: 키 URI를 로컬로, TS 경로는 절대 경로로
                        val baseUrl = originalUrl.substringBeforeLast("/")
                        val modifiedM3u8 = originalM3u8.lines().joinToString("\n") { l ->
                            when {
                                l.contains("#EXT-X-KEY") -> 
                                    l.replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/key.bin"""")
                                !l.startsWith("#") && l.isNotEmpty() && !l.startsWith("http") -> 
                                    "$baseUrl/$l"
                                else -> l
                            }
                        }
                        
                        val response = "HTTP/1.1 200 OK\r\n" +
                                     "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                     "Access-Control-Allow-Origin: *\r\n\r\n"
                        output.write(response.toByteArray())
                        output.write(modifiedM3u8.toByteArray())

                    } else if (path.contains("/key.bin")) {
                        // 키 제공
                        val response = "HTTP/1.1 200 OK\r\n" +
                                     "Content-Type: application/octet-stream\r\n" +
                                     "Access-Control-Allow-Origin: *\r\n\r\n"
                        output.write(response.toByteArray())
                        output.write(key)
                        
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    }
                    output.flush()
                    socket.close()
                } catch (e: Exception) { 
                    try { socket.close() } catch(e2: Exception) {} 
                }
            }
        }
    }
}
