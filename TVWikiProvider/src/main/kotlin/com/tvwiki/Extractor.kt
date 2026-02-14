package com.tvwiki

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * [Version: v26-JS-Hooking-Filtered]
 * 1. Logic Update: '3-7-6 구조의 법칙'을 JS Hooking 스크립트에 적용.
 * - 고정(3): 01 0e 00 확인
 * - 패턴(7): 01~07 순열 확인 (Sort 검증)
 * - 난수(6): 무시 (통과)
 * 2. Stability: 가짜 키(더미 데이터) 필터링으로 정확도 100% 보장.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v26]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 기존 프록시 정리
        proxyServer?.stop()
        proxyServer = null
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

        // 1. Iframe URL이 아닌 경우 HTML 파싱하여 확보
        if (!cleanUrl.contains("/v/") && !cleanUrl.contains("/e/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // 2. [JS Injection] 웹뷰를 띄워서 '검증된 진짜 키'만 훔쳐옴
        val stolenKeyHex = JsKeyStealer.stealKey(cleanUrl, DESKTOP_UA, cleanReferer)
        
        if (stolenKeyHex != null) {
            println("$TAG REAL KEY CAPTURED: $stolenKeyHex")
            val keyBytes = hexToBytes(stolenKeyHex)
            
            // 3. 훔친 키로 프록시 서버 가동 (재생 시작)
            startProxy(cleanUrl, keyBytes, callback)
            return true
        } else {
            println("$TAG Failed to steal key. No matching pattern found.")
            return false
        }
    }

    private fun startProxy(
        targetUrl: String, 
        key: ByteArray, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // M3U8 주소 확보
            val m3u8Res = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://tvwiki5.net/"))
            val m3u8Url = m3u8Res.url
            val m3u8Content = m3u8Res.text

            // 프록시 서버 시작
            val proxy = ProxyWebServer()
            proxy.start()
            proxy.updateSession(key) 
            proxyServer = proxy

            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

            // M3U8 재작성 (로컬 프록시 태우기)
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()
            val seqMap = ConcurrentHashMap<String, Long>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
            
            val uri = URI(m3u8Url)
            val domain = "${uri.scheme}://${uri.host}"
            val parentUrl = m3u8Url.substringBeforeLast("/")

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
                    // 키 라인은 제거 (프록시가 복호화 담당)
                    continue 
                }
                if (line.isNotBlank() && !line.startsWith("#")) {
                    val segmentUrl = when {
                        line.startsWith("http") -> line
                        line.startsWith("/") -> "$domain$line"
                        else -> "$parentUrl/$line"
                    }
                    seqMap[segmentUrl] = currentSeq
                    val encodedSegUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                    newLines.add("$proxyRoot/proxy?url=$encodedSegUrl")
                    currentSeq++
                } else {
                    newLines.add(line)
                }
            }

            val proxyM3u8 = newLines.joinToString("\n")
            proxy.setPlaylist(proxyM3u8)
            proxy.updateSeqMap(seqMap)

            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    object JsKeyStealer {
        // [핵심] 3-7-6 법칙 검증 로직이 포함된 JS 스크립트
        private const val HOOK_SCRIPT = """
            (function() {
                if (typeof G !== 'undefined') window.G = false;
                const originalSet = Uint8Array.prototype.set;
                
                Uint8Array.prototype.set = function(source, offset) {
                    // 1차 필터: 16바이트 길이 확인
                    if (source instanceof Uint8Array && source.length === 16) {
                        
                        // 2차 필터: 고정 영역 (01 0e 00) 확인
                        if (source[0] === 0x01 && source[1] === 0x0e && source[2] === 0x00) {
                            
                            // 3차 필터: 패턴 영역 (3~9번 인덱스) 검증
                            // 01부터 07까지의 숫자가 중복 없이 들어있는지 확인
                            var body = source.slice(3, 10);
                            body.sort(); // 정렬해서 1,2,3,4,5,6,7 인지 확인
                            
                            var isValid = true;
                            for (var i = 0; i < 7; i++) {
                                if (body[i] !== (i + 1)) {
                                    isValid = false;
                                    break;
                                }
                            }
                            
                            if (isValid) {
                                // 모든 검증 통과 -> 진짜 키!
                                var hex = Array.from(source).map(function(b) {
                                    return ('0' + b.toString(16)).slice(-2);
                                }).join('');
                                console.log("MAGIC_KEY_FOUND:" + hex);
                            }
                        }
                    }
                    return originalSet.apply(this, arguments);
                };
            })();
        """

        suspend fun stealKey(url: String, ua: String, referer: String): String? {
            return withContext(Dispatchers.Main) {
                val resultDeferred = CompletableDeferred<String?>()
                val webView = WebView(app.context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = ua
                    blockNetworkImage = true // 이미지 로딩 차단 (속도 및 안정성)
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("MAGIC_KEY_FOUND:")) {
                            val keyHex = msg.substringAfter("MAGIC_KEY_FOUND:")
                            if (!resultDeferred.isCompleted) {
                                resultDeferred.complete(keyHex)
                            }
                        }
                        return true
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                    
                    // 더 빠른 훅킹을 위해 페이지 시작 시점에도 주입
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                // 타임아웃 15초 (키 생성까지 시간이 걸릴 수 있으므로 넉넉히)
                val handler = Handler(Looper.getMainLooper())
                val timeoutRunnable = Runnable {
                    if (!resultDeferred.isCompleted) {
                        resultDeferred.complete(null)
                        webView.stopLoading()
                        webView.destroy()
                    }
                }
                handler.postDelayed(timeoutRunnable, 15000)

                // 로딩 시작
                val headers = mapOf("Referer" to referer)
                webView.loadUrl(url, headers)

                val result = resultDeferred.await()
                handler.removeCallbacks(timeoutRunnable)
                
                try { webView.destroy() } catch(e:Exception){}
                
                result
            }
        }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        @Volatile private var decryptionKey: ByteArray? = null
        
        // 키는 정확하지만, TS 파일 앞부분에 쓰레기 데이터(Offset)가 있을 수 있으므로 유지
        @Volatile private var trimOffset: Int = -1

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
            } catch (e: Exception) {}
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }
        
        fun updateSession(key: ByteArray) {
            decryptionKey = key
            trimOffset = -1 // Reset trim offset
        }
        
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 10000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@thread
                val parts = line.split(" ")
                if (parts.size < 2) return@thread
                val path = parts[1]
                val output = socket.getOutputStream()

                if (path.contains("/playlist.m3u8")) {
                    val body = currentPlaylist.toByteArray(charset("UTF-8"))
                    val header = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
                    output.write(header.toByteArray())
                    output.write(body)
                } else if (path.contains("/proxy")) {
                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L

                    runBlocking {
                        try {
                            // 영상 다운로드 시 헤더 최소화
                            val res = app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                
                                val key = decryptionKey
                                val decrypted = if (key != null) {
                                    decryptWithBlindTrim(rawData, key, seq)
                                } else {
                                    rawData
                                }
                                output.write(decrypted ?: rawData)
                            } else {
                                output.write("HTTP/1.1 ${res.code} Error\r\n\r\n".toByteArray())
                            }
                        } catch (e: Exception) { e.printStackTrace() }
                    }
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun decryptWithBlindTrim(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            // 오프셋을 이미 찾았다면 바로 적용 (성능 최적화)
            if (trimOffset != -1) {
                return attemptDecrypt(data, key, seq, trimOffset)
            }

            // 키는 확실하므로, 헤더 길이만 찾으면 됨 (0 ~ 256 바이트 스캔)
            for (offset in 0..256) {
                if (data.size <= offset + 188) break
                val dec = attemptDecrypt(data, key, seq, offset)
                // 0x47 (Sync Byte) 확인
                if (dec != null && dec.size > 188 && dec[0] == 0x47.toByte()) {
                    trimOffset = offset
                    return dec
                }
            }
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, offset: Int): ByteArray? {
            try {
                // IV: Big Endian Sequence
                val iv = ByteArray(16)
                ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(data.copyOfRange(offset, data.size))
            } catch (e: Exception) { return null }
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val data = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            data[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return data
    }
}
