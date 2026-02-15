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
import kotlinx.coroutines.delay
import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

/**
 * Version: v23.3 (Constructor Parameter Fix & All Logic Preserved)
 * Modification:
 * 1. FIXED: Corrected parameter name from 'interceptUrls' to 'interceptUrl' (singular).
 * 2. FIXED: Passed 'interceptUrl' as a mandatory parameter to resolve build failure.
 * 3. LOG: Maintained all println debug logs for every single process.
 * 4. OPTIMIZE: Hooking and 6s delay only run if key7 is detected.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        val capturedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile var verifiedKey: ByteArray? = null
        @Volatile var currentIv: ByteArray? = null
        @Volatile var testSegmentUrl: String? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVMON][v23.3] getUrl 시작. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVMON] [STEP 1] extract() 진입.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. iframe 주소 추출
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("[TVMON] [LOG] iframe 검색 중...")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] [LOG] iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] iframe 파싱 실패: ${e.message}") }
        }

        var capturedUrl: String? = null
        
        // 2. c.html 주소 획득 (BUILD FIX: interceptUrl 파라미터명 및 타입 수정)
        println("[TVMON] [STEP 2] c.html URL 캡처 시도 (Fast Mode)...")
        val fastResolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""))
        
        try {
            val fastRes = app.get(
                url = cleanUrl, 
                headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA), 
                interceptor = fastResolver
            )
            if (fastRes.url.contains("/c.html")) {
                capturedUrl = fastRes.url
                println("[TVMON] [STEP 2-1] c.html 확보 성공: $capturedUrl")
            }
        } catch (e: Exception) { println("[TVMON] [ERROR] Fast WebView 에러: ${e.message}") }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[TVMON] [LOG] 쿠키 적용됨.")
            }

            try {
                println("[TVMON] [STEP 3] M3U8 메인 파일 분석 중...")
                var requestUrl = capturedUrl.substringBefore("#")
                var content = app.get(requestUrl, headers = headers).text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        println("[TVMON] [LOG] 실제 M3U8 주소 발견: $requestUrl")
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // 3. Key7 여부 확인 (key7일 때만 후킹 및 6초 대기 실행)
                val isKey7 = content.contains("/v/key7")
                println("[TVMON] [CHECK] Key7 적용 여부: $isKey7")

                if (isKey7) {
                    println("[TVMON] [ACTION] Key7 탐지됨. 정밀 후킹 프로세스 가동 (6초 대기)...")
                    
                    // [BUILD FIX] interceptUrl 파라미터명 수정
                    val hookResolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""))
                    
                    capturedKeys.clear()
                    verifiedKey = null
                    
                    // 후킹 실행을 위한 재로드
                    app.get(url = cleanUrl, headers = headers, interceptor = hookResolver)
                    
                    println("[TVMON] [WAIT] 키 후보군 수집 대기 중 (6초)...")
                    delay(6000) 
                    println("[TVMON] [LOG] 수집된 후보 키 개수: ${capturedKeys.size}")
                    
                    // 4. 프록시 서버 설정
                    println("[TVMON] [STEP 4] 프록시 서버 초기화...")
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }

                    // IV 추출
                    val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
                    val ivHex = ivMatch?.groupValues?.get(1) ?: "0x00000000000000000000000000000000"
                    currentIv = ivHex.removePrefix("0x").hexToByteArray()
                    println("[TVMON] [DATA] 추출된 IV: $ivHex")

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()

                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    val originalKeyUrl = match.groupValues[1]
                                    val absoluteKeyUrl = resolveUrl(baseUri, requestUrl, originalKeyUrl)
                                    val encodedKeyUrl = java.net.URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                    val newLine = trimmed.replace(originalKeyUrl, "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl")
                                    sb.append(newLine).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentUrl == null) testSegmentUrl = absoluteSegUrl
                            val encodedSegUrl = java.net.URLEncoder.encode(absoluteSegUrl, "UTF-8")
                            sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encodedSegUrl").append("\n")
                        }
                    }

                    proxyServer!!.setPlaylist(sb.toString())
                    val finalProxyUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                    println("[TVMON] [FINISH] 프록시 M3U8 최종 생성 완료.")
                    
                    callback(newExtractorLink(name, name, finalProxyUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } else {
                    println("[TVMON] [SKIP] Key7이 아니므로 후킹 과정을 생략하고 즉시 반환합니다.")
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                }
            } catch (e: Exception) { println("[TVMON] [ERROR] 추출 도중 예외: ${e.message}") }
        }
        return false
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    // 데이터 모델
    data class Layer(@JsonProperty("name") val name: String, @JsonProperty("xor_mask") val xorMask: String? = null, @JsonProperty("pad_len") val padLen: Int? = null, @JsonProperty("segment_lengths") val segmentLengths: List<Int>? = null, @JsonProperty("real_positions") val realPositions: List<Int>? = null, @JsonProperty("init_key") val initKey: String? = null, @JsonProperty("noise_lens") val noiseLens: List<Int>? = null, @JsonProperty("perm") val perm: List<Int>? = null, @JsonProperty("rotations") val rotations: List<Int>? = null, @JsonProperty("inverse_sbox") val inverseSbox: String? = null)
    data class Key7Response(@JsonProperty("encrypted_key") val encryptedKey: String, @JsonProperty("layers") val layers: List<Layer>)

    // ==========================================
    // Proxy Server & Verification (v23.0 Logic)
    // ==========================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                thread(isDaemon = true) {
                    println("[PROXY] 서버 실행됨 (포트: $port)")
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) { println("[PROXY] [ERROR] 서버 시작 실패") }
        }

        fun stop() { isRunning = false; serverSocket?.close(); println("[PROXY] 서버 중지") }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    path.contains("/playlist.m3u8") -> {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        if (verifiedKey == null) {
                            println("[PROXY] [ACTION] 키 검증 프로세스 시작...")
                            verifiedKey = verifyMultipleKeys()
                        }
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                        println("[PROXY] [RES] 검증된 키 반환 완료.")
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        val conn = URL(targetUrl).openConnection() as HttpURLConnection
                        currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        
                        val inputStream = conn.inputStream
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())

                        val buffer = ByteArray(65536)
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead > 0) {
                            var offset = -1
                            for (i in 0 until bytesRead - 376) {
                                if (buffer[i] == 0x47.toByte() && buffer[i+188] == 0x47.toByte() && buffer[i+376] == 0x47.toByte()) {
                                    offset = i; break
                                }
                            }
                            if (offset != -1) output.write(buffer, offset, bytesRead - offset)
                            else output.write(buffer, 0, bytesRead)
                            inputStream.copyTo(output)
                        }
                        inputStream.close()
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            println("[VERIFY] 검증 프로세스 가동. 대상 세그먼트: $url")
            return try {
                val responseData = runBlocking { 
                    println("[VERIFY] 데이터 다운로드 중...")
                    app.get(url, headers = currentHeaders).body.bytes() 
                }
                val testChunk = responseData.copyOfRange(0, 1024)

                synchronized(capturedKeys) {
                    println("[VERIFY] 총 ${capturedKeys.size}개의 후보 키 검사 중...")
                    for (hex in capturedKeys) {
                        val keyBytes = hex.hexToByteArray()
                        try {
                            val decrypted = decryptAES(testChunk, keyBytes, currentIv ?: ByteArray(16))
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("[VERIFY] [SUCCESS] 일치하는 키 발견: $hex")
                                return keyBytes
                            }
                        } catch (e: Exception) { }
                    }
                }
                println("[VERIFY] [FAIL] 일치하는 키를 찾지 못했습니다.")
                null
            } catch (e: Exception) { println("[VERIFY] [ERROR] 검증 중 오류: ${e.message}"); null }
        }

        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val data = ByteArray(length / 2)
    for (i in 0 until length step 2) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i+1], 16)).toByte()
    }
    return data
}
