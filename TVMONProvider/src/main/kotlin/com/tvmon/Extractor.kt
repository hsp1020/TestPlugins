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
 * Version: v22.0 (Logic Preserved + Multi-Key Hooking)
 * Modification:
 * 1. [RESTORE] Restored original logic structure (Key7 vs non-Key7 branching).
 * 2. [HOOK] Multi-key capture via WebView console monitoring.
 * 3. [IV] Automatic IV extraction from M3U8 tag.
 * 4. [VERIFY] Real-time key verification using 0x47 Sync Byte check.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        // 다중 키 후보 수집용
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
        println("[TVMON][v22.0] getUrl 시작: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVMON] extract() 진입.")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. iframe 주소 따기 (원본 로직 유지)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("[TVMON] iframe 검색 중: $cleanReferer")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVMON] iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) { println("[TVMON] iframe 파싱 에러: ${e.message}") }
        }

        var capturedUrl: String? = null
        if (cleanUrl.contains("/c.html") && cleanUrl.contains("token=")) {
            capturedUrl = cleanUrl
        }

        // 2. WebView 후킹 설정 (키 3개 이상 수집 대응)
        if (capturedUrl == null) {
            println("[TVMON] WebView 후킹 시작 (다중 키 수집 모드)")
            val hookScript = """
                (function() {
                    if (typeof G !== 'undefined') window.G = false;
                    const originalSet = Uint8Array.prototype.set;
                    Uint8Array.prototype.set = function(source, offset) {
                        if (source instanceof Uint8Array && source.length === 16) {
                            var hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:" + hex);
                        }
                        return originalSet.apply(this, arguments);
                    };
                    console.log("Hooking ready.");
                })();
            """.trimIndent()

            val resolver = WebViewResolver(
                interceptUrl = Regex("""/c\.html"""), 
                additionalJs = hookScript,
                useOkhttp = false,
                timeout = 15000L
            )
            
            try {
                capturedKeys.clear()
                verifiedKey = null
                val response = app.get(url = cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA), interceptor = resolver)
                
                // 후킹된 키들이 로그에 찍힐 때까지 충분히 대기
                println("[TVMON] 키 수집 대기 중 (5초)...")
                delay(5000) 
                
                if (response.url.contains("/c.html")) {
                    capturedUrl = response.url
                    println("[TVMON] c.html 획득 완료: $capturedUrl")
                }
            } catch (e: Exception) { println("[TVMON] WebView 에러: ${e.message}") }
        }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                var requestUrl = capturedUrl.substringBefore("#")
                var content = app.get(requestUrl, headers = headers).text.trim()

                // M3U8 추출 로직 유지
                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                // [원본 로직] Key7 검사 및 분기
                val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                println("[TVMON] Key7 여부: $isKey7 / 수집된 키 후보: ${capturedKeys.size}개")

                if (isKey7) {
                    proxyServer?.stop()
                    proxyServer = ProxyWebServer().apply {
                        start()
                        updateSession(headers)
                    }

                    // IV 및 검증용 첫 세그먼트 주소 추출
                    val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
                    currentIv = ivMatch?.groupValues?.get(1)?.removePrefix("0x")?.hexToByteArray() ?: ByteArray(16)
                    println("[TVMON] IV 추출 완료: ${ivMatch?.groupValues?.get(1) ?: "기본값(0x0)"}")

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()

                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach

                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val regex = Regex("""URI="([^"]+)"""")
                                val match = regex.find(trimmed)
                                if (match != null) {
                                    val originalKeyUrl = match.groupValues[1]
                                    val absoluteKeyUrl = resolveUrl(baseUri, requestUrl, originalKeyUrl)
                                    val encodedKeyUrl = java.net.URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                    // 프록시 주소로 교체
                                    val newLine = trimmed.replace(originalKeyUrl, "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl")
                                    sb.append(newLine).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            // 세그먼트 주소 처리
                            val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentUrl == null) testSegmentUrl = absoluteSegUrl // 검증용으로 첫 조각 저장
                            val encodedSegUrl = java.net.URLEncoder.encode(absoluteSegUrl, "UTF-8")
                            sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encodedSegUrl").append("\n")
                        }
                    }

                    proxyServer!!.setPlaylist(sb.toString())
                    val proxyUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                    println("[TVMON] 프록시 M3U8 반환.")
                    callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                    })
                    return true
                } 

                // [원본 로직] Key7 아님 -> 원본 주소 반환
                println("[TVMON] Key7 아님. 원본 URL 반환.")
                callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                })
                return true

            } catch (e: Exception) { println("[TVMON] 추출 중 에러: ${e.message}") }
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

    // ==========================================
    // 기존 Data Structure 보존
    // ==========================================
    data class Layer(@JsonProperty("name") val name: String, @JsonProperty("xor_mask") val xorMask: String? = null, @JsonProperty("pad_len") val padLen: Int? = null, @JsonProperty("segment_lengths") val segmentLengths: List<Int>? = null, @JsonProperty("real_positions") val realPositions: List<Int>? = null, @JsonProperty("init_key") val initKey: String? = null, @JsonProperty("noise_lens") val noiseLens: List<Int>? = null, @JsonProperty("perm") val perm: List<Int>? = null, @JsonProperty("rotations") val rotations: List<Int>? = null, @JsonProperty("inverse_sbox") val inverseSbox: String? = null)
    data class Key7Response(@JsonProperty("encrypted_key") val encryptedKey: String, @JsonProperty("layers") val layers: List<Layer>)

    // ==========================================
    // Proxy Server with Multi-Key Verification
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
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) { println("[TVMON] 프록시 시작 실패: ${e.message}") }
        }

        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                val path = line.split(" ")[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(currentPlaylist.toByteArray())
                } else if (path.contains("/key")) {
                    // [핵심 수정] 가로챈 여러 키 중 실제 세그먼트를 복호화할 수 있는 키를 찾음
                    if (verifiedKey == null) {
                        println("[TVMON] 키 검증 프로세스 가동 (후보군: ${capturedKeys.size}개)")
                        verifiedKey = verifyMultipleKeys()
                    }
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(verifiedKey ?: ByteArray(16))
                } else if (path.contains("/seg")) {
                    val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                    val conn = URL(targetUrl).openConnection() as HttpURLConnection
                    currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                    
                    val inputStream = conn.inputStream
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())

                    // [원본 로직] Sync Byte Seeker 유지
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
                output.flush(); socket.close()
            } catch (e: Exception) { socket.close() }
        }

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            return try {
                println("[TVMON] 검증용 데이터 로딩: $url")
                val data = runBlocking { app.get(url, headers = currentHeaders).content }
                val testChunk = data.copyOfRange(0, 1024)

                for (hex in capturedKeys) {
                    val keyBytes = hex.hexToByteArray()
                    try {
                        val decrypted = decryptAES(testChunk, keyBytes, currentIv ?: ByteArray(16))
                        if (decrypted[0] == 0x47.toByte()) {
                            println("[TVMON] 키 검증 성공! 진짜 키: $hex")
                            return keyBytes
                        }
                    } catch (e: Exception) { println("[TVMON] 키 불일치: $hex") }
                }
                println("[TVMON] 경고: 맞는 키를 찾지 못했습니다.")
                null
            } catch (e: Exception) { println("[TVMON] 검증 중 에러: ${e.message}"); null }
        }

        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
            return cipher.doFinal(data)
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
