/**
 * [Version: v23.2-Ultimate-TVMON-Restored]
 * 1. [Restoration] TVMON(2번)의 Key7 판별, IV 추출, 플레이리스트 재작성 로직을 원본 그대로 복원.
 * 2. [Integration] 1번의 '전 타이밍 수동 주입'과 '전수 검증(Brute-force)' 엔진을 Key7 로직 내부에 결합.
 * 3. [Requirement] 16바이트 데이터 무조건 수집 및 실데이터 기반 0x47 검증 수행.
 * 4. [Debug] 실행 단계별 상세 추적 로그(println) 포함.
 */
package com.tvmon

import android.util.Base64
import android.webkit.*
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[TVMON-v23.2]"

        val capturedKeys: MutableSet<String> = Collections.synchronizedSet(mutableSetOf<String>())
        @Volatile var verifiedKey: ByteArray? = null
        @Volatile var currentIv: ByteArray? = null
        @Volatile var testSegmentUrl: String? = null

        fun String.hexToByteArray(): ByteArray {
            val len = length
            val data = ByteArray(len / 2)
            var i = 0
            while (i < len) {
                data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i+1], 16)).toByte()
                i += 2
            }
            return data
        }
    }

    data class Layer(@JsonProperty("name") val name: String, @JsonProperty("xor_mask") val xorMask: String? = null, @JsonProperty("pad_len") val padLen: Int? = null, @JsonProperty("segment_lengths") val segmentLengths: List<Int>? = null, @JsonProperty("real_positions") val realPositions: List<Int>? = null, @JsonProperty("init_key") val initKey: String? = null, @JsonProperty("noise_lens") val noiseLens: List<Int>? = null, @JsonProperty("perm") val perm: List<Int>? = null, @JsonProperty("rotations") val rotations: List<Int>? = null, @JsonProperty("inverse_sbox") val inverseSbox: String? = null)
    data class Key7Response(@JsonProperty("encrypted_key") val encryptedKey: String, @JsonProperty("layers") val layers: List<Layer>)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        println("$TAG [DEBUG] getUrl 시작. URL: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, thumbnailHint: String? = null): Boolean {
        println("$TAG [STEP 1] extract() 시작")
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        // 1. iframe 추출 (2번 원본 유지)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                println("$TAG [STEP 1-1] iframe 파싱 시도")
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$TAG iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) { println("$TAG [ERROR] iframe 파싱 실패: ${e.message}") }
        }

        // 2. 웹뷰 후킹 가동 (수동 주입 방식 결합)
        println("$TAG [STEP 2] 웹뷰 기반 키 수집 가동")
        capturedKeys.clear()
        verifiedKey = null
        testSegmentUrl = null
        
        thread {
            runBlocking {
                JsKeyStealer.stealKey(cleanUrl, DESKTOP_UA, cleanReferer)
            }
        }

        // 3. M3U8 요청 및 Key7 판별 (2번 원본 유지)
        val cookie = CookieManager.getInstance().getCookie(cleanUrl)
        val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/", "Origin" to "https://player.bunny-frame.online")
        if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

        try {
            println("$TAG [STEP 3] M3U8 데이터 요청")
            var requestUrl = cleanUrl.substringBefore("#")
            var res = app.get(requestUrl, headers = headers)
            var content = res.text.trim()

            if (!content.startsWith("#EXTM3U")) {
                Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                    requestUrl = it.groupValues[1]
                    content = app.get(requestUrl, headers = headers).text.trim()
                }
            }

            val isKey7 = content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
            println("$TAG [CHECK] Key7 암호화 여부: $isKey7")

            if (isKey7) {
                println("$TAG [STEP 4] Key7 전용 프록시 서버 초기화")
                proxyServer?.stop()
                proxyServer = ProxyWebServer().apply {
                    start()
                    updateSession(headers)
                }

                // IV 추출 (2번 원본 유지)
                val ivMatch = Regex("""IV=(0x[0-9a-fA-F]+)""").find(content)
                val ivHex = ivMatch?.groupValues?.get(1) ?: "0x00000000000000000000000000000000"
                currentIv = ivHex.removePrefix("0x").hexToByteArray()
                println("$TAG [DATA] IV 확보: $ivHex")

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
                                val encodedKeyUrl = URLEncoder.encode(absoluteKeyUrl, "UTF-8")
                                val newLine = trimmed.replace(originalKeyUrl, "http://127.0.0.1:${proxyServer!!.port}/key?url=$encodedKeyUrl")
                                sb.append(newLine).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else sb.append(trimmed).append("\n")
                    } else {
                        val absoluteSegUrl = resolveUrl(baseUri, requestUrl, trimmed)
                        if (testSegmentUrl == null) testSegmentUrl = absoluteSegUrl
                        val encodedSegUrl = URLEncoder.encode(absoluteSegUrl, "UTF-8")
                        sb.append("http://127.0.0.1:${proxyServer!!.port}/seg?url=$encodedSegUrl").append("\n")
                    }
                }

                proxyServer!!.setPlaylist(sb.toString())
                val proxyFinalUrl = "http://127.0.0.1:${proxyServer!!.port}/playlist.m3u8"
                
                callback(newExtractorLink(name, name, proxyFinalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"; this.headers = headers
                })
                return true
            }

            println("$TAG Key7이 아니므로 일반 스트림 반환")
            callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"; this.headers = headers
            })
            return true

        } catch (e: Exception) { println("$TAG [ERROR] 추출 실패: ${e.message}") }
        return false
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    object JsKeyStealer {
        private const val HOOK_SCRIPT = """
            (function() {
                try {
                    if (window.isHooked) return;
                    window.isHooked = true;
                    if (typeof G !== 'undefined') window.G = false;

                    function reportRawKey(source) {
                        if (source && source.length === 16) {
                            var hex = Array.from(source).map(function(b) {
                                return ('0' + (b & 0xFF).toString(16)).slice(-2);
                            }).join('');
                            console.log("BRUTE_CANDIDATE:" + hex);
                        }
                    }

                    const originalSet = Uint8Array.prototype.set;
                    Object.defineProperty(Uint8Array.prototype, 'set', {
                        value: function(source) {
                            reportRawKey(source);
                            return originalSet.apply(this, arguments);
                        },
                        configurable: true
                    });

                    const OriginalUint8Array = window.Uint8Array;
                    function HookedUint8Array() {
                        var arr = new OriginalUint8Array(...arguments);
                        reportRawKey(arr);
                        return arr;
                    }
                    HookedUint8Array.prototype = OriginalUint8Array.prototype;
                    window.Uint8Array = HookedUint8Array;
                    console.log("HOOK_LOADED_BRUTE_FORCE");
                } catch(e) { console.log("HOOK_ERR:" + e.message); }
            })();
        """

        suspend fun stealKey(url: String, ua: String, referer: String) {
            withContext(Dispatchers.Main) {
                val webView = WebView(AcraApplication.context!!)
                webView.settings.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = ua }
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(msg: ConsoleMessage?): Boolean {
                        val txt = msg?.message() ?: ""
                        if (txt.startsWith("BRUTE_CANDIDATE:")) {
                            val hex = txt.substringAfter("BRUTE_CANDIDATE:")
                            if (BunnyPoorCdn.capturedKeys.add(hex)) {
                                println("$TAG [CANDIDATE] 후보 키 캡처: $hex")
                            }
                        }
                        return true
                    }
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }
                webView.loadUrl(url, mapOf("Referer" to referer))
            }
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
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                thread(isDaemon = true) {
                    println("$TAG [PROXY] 시작됨 (포트: $port)")
                    while (isRunning) { try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {} }
                }
            } catch (e: Exception) { println("$TAG [ERROR] 프록시 구동 실패: ${e.message}") }
        }

        fun stop() { isRunning = false; serverSocket?.close() }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }

        private fun handleClient(socket: Socket) = thread {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size < 2) return@thread
                val path = parts[1]
                val output = socket.getOutputStream()

                when {
                    path.contains("/playlist.m3u8") -> {
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n".toByteArray())
                        output.write(currentPlaylist.toByteArray())
                    }
                    path.contains("/key") -> {
                        println("$TAG [PROXY] Key 요청 수신. 후보군(${capturedKeys.size}개) 전수 검증 시작.")
                        if (verifiedKey == null) {
                            verifiedKey = verifyMultipleKeys()
                        }
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
                        output.write(verifiedKey ?: ByteArray(16))
                        println("$TAG [PROXY] 검증된 키 반환 완료.")
                    }
                    path.contains("/seg") -> {
                        val targetUrl = URLDecoder.decode(path.substringAfter("url="), "UTF-8")
                        val conn = URL(targetUrl).openConnection() as HttpURLConnection
                        currentHeaders.forEach { (k, v) -> conn.setRequestProperty(k, v) }
                        
                        val inputStream = conn.inputStream
                        output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())

                        // 첫 세그먼트의 경우 오프셋 탐색을 통한 0x47 동기화 (2번 원본 유지)
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
            } catch (e: Exception) { try { socket.close() } catch(e2:Exception){} }
        }

        private fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            println("$TAG [VERIFY] 테스트 세그먼트 다운로드: $url")
            
            return try {
                val responseData = runBlocking { app.get(url, headers = currentHeaders).body.bytes() }
                val testChunk = responseData.copyOfRange(0, 1024)
                val iv = currentIv ?: ByteArray(16)

                synchronized(capturedKeys) {
                    for (hex in capturedKeys) {
                        val keyBytes = hex.hexToByteArray()
                        try {
                            val decrypted = decryptAES(testChunk, keyBytes, iv)
                            // 0x47 동기화 바이트 확인
                            if (decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("$TAG [SUCCESS] 유효한 진짜 키 발견: $hex")
                                return keyBytes
                            }
                        } catch (e: Exception) {}
                    }
                }
                println("$TAG [FAIL] 모든 후보군이 일치하지 않음.")
                null
            } catch (e: Exception) { println("$TAG [ERROR] 검증 로직 예외: ${e.message}"); null }
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
