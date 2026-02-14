package com.tvwiki

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch // 대기용
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Version: v35-Hybrid-Integration]
 * 1. Network: M3U8 및 c.html 주소 확보는 기존 안정적인 방식(WebViewResolver) 사용.
 * 2. Key: 복잡한 연산 대신 v33의 JS Hooking 방식을 사용하여 '완성된 키'를 탈취.
 * 3. Proxy: 탈취한 키를 주입받아 단순 복호화만 수행 (가장 가볍고 빠름).
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v35]"
        
        // JS로 훔친 키를 임시 저장할 공간 (Companion Object)
        @Volatile internal var globalKey: ByteArray? = null
        @Volatile internal var keyLatch = CountDownLatch(1)

        fun hexToBytes(hex: String): ByteArray {
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 프록시 및 키 상태 초기화
        proxyServer?.stop()
        proxyServer = null
        globalKey = null
        keyLatch = CountDownLatch(1)

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

        // 1. [Old Way] Iframe URL 확보
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // 2. [Old Way] WebViewResolver로 c.html (암호화된 페이지) 주소 확보
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        var capturedUrl: String? = null
        var capturedHeaders: Map<String, String>? = null

        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                // [Hybrid Step 1] c.html URL을 알았으니, 여기서 JS Hooking을 시도하여 키를 훔칩니다.
                // 이 과정은 비동기로 진행되며, 키가 확보되면 globalKey에 저장됩니다.
                thread {
                    runBlocking {
                         JsKeyStealer.stealKey(capturedUrl!!, DESKTOP_UA, "https://player.bunny-frame.online/")
                    }
                }

                // 기존 헤더 로직
                val cookie = CookieManager.getInstance().getCookie(capturedUrl) ?: ""
                capturedHeaders = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to "https://player.bunny-frame.online/",
                    "Origin" to "https://player.bunny-frame.online",
                    "Cookie" to cookie
                )
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (capturedUrl != null && capturedHeaders != null) {
            try {
                // 3. [Hybrid Step 2] 키가 구해질 때까지 잠시 대기 (최대 10초)
                // c.html 로딩 중에 키가 생성되므로, M3U8 요청 전에 키를 확보하는 것이 안전함.
                println("$TAG Waiting for key extraction...")
                keyLatch.await(10, TimeUnit.SECONDS)
                
                if (globalKey == null) {
                    println("$TAG Key extraction failed or timed out. Proceeding anyway (might fail).")
                } else {
                    println("$TAG Key acquired successfully!")
                }

                // 4. [Old Way] M3U8 다운로드 및 파싱
                val m3u8Res = app.get(capturedUrl, headers = capturedHeaders!!)
                val m3u8Content = m3u8Res.text

                // IV 추출 (키는 JS로 구했지만 IV는 M3U8에 있음)
                val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
                val hexIv = keyMatch?.groupValues?.get(2)
                
                // 5. 프록시 서버 시작
                val proxy = ProxyWebServer()
                proxy.start()
                // [Hybrid Step 3] 구해둔 진짜 키를 프록시에 주입
                proxy.updateSession(globalKey, hexIv)
                proxyServer = proxy

                val proxyPort = proxy.port
                val proxyRoot = "http://127.0.0.1:$proxyPort"

                // 6. [Old Way] Playlist Rewrite (로컬 프록시 주소로 변경)
                val newLines = mutableListOf<String>()
                val lines = m3u8Content.lines()
                val seqMap = ConcurrentHashMap<String, Long>()
                var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
                
                val uri = URI(capturedUrl)
                val domain = "${uri.scheme}://${uri.host}"
                val parentUrl = capturedUrl.substringBeforeLast("/")

                for (line in lines) {
                    if (line.startsWith("#EXT-X-KEY")) {
                        // 키 라인은 제거 (프록시가 이미 복호화해서 보낼 것임)
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
                return true

            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        return false
    }

    object JsKeyStealer {
        // v33의 강력한 후킹 스크립트 (3-7-6 법칙 검증 포함)
        private const val HOOK_SCRIPT = """
            (function() {
                if (window.isHooked) return;
                window.isHooked = true;
                if (typeof G !== 'undefined') window.G = false;

                function checkAndLog(source) {
                    if (source && source.length === 16) {
                        if (source[0] === 0x01 && source[1] === 0x0e && source[2] === 0x00) {
                            try {
                                var body = Array.from(source.slice(3, 10));
                                body.sort(function(a, b) { return a - b; });
                                var isValid = true;
                                for (var i = 0; i < 7; i++) {
                                    if (body[i] !== (i + 1)) { isValid = false; break; }
                                }
                                if (isValid) {
                                    var hex = Array.from(source).map(function(b) {
                                        return ('0' + (b & 0xFF).toString(16)).slice(-2);
                                    }).join('');
                                    console.log("MAGIC_KEY_FOUND:" + hex);
                                    return true;
                                }
                            } catch (e) {}
                        }
                    }
                    return false;
                }

                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source) checkAndLog(source);
                    return originalSet.apply(this, arguments);
                };

                const OriginalUint8Array = window.Uint8Array;
                window.Uint8Array = function(arg1, arg2, arg3) {
                    var arr;
                    if (arguments.length === 0) arr = new OriginalUint8Array();
                    else if (arguments.length === 1) arr = new OriginalUint8Array(arg1);
                    else if (arguments.length === 2) arr = new OriginalUint8Array(arg1, arg2);
                    else arr = new OriginalUint8Array(arg1, arg2, arg3);
                    checkAndLog(arr);
                    return arr;
                };
                window.Uint8Array.prototype = OriginalUint8Array.prototype;
                window.Uint8Array.from = OriginalUint8Array.from;
                window.Uint8Array.of = OriginalUint8Array.of;
                Object.defineProperty(window.Uint8Array, 'name', { value: 'Uint8Array' });
            })();
        """

        suspend fun stealKey(url: String, ua: String, referer: String) {
            withContext(Dispatchers.Main) {
                // WebView 생성 (Context Fix 적용)
                val webView = WebView(AcraApplication.context!!)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = ua
                    blockNetworkImage = true // 이미지 로딩 차단으로 속도 향상
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("MAGIC_KEY_FOUND:")) {
                            val keyHex = msg.substringAfter("MAGIC_KEY_FOUND:")
                            // 전역 변수에 키 저장 & Latch 해제 (프록시 서버가 이 값을 씀)
                            BunnyPoorCdn.globalKey = BunnyPoorCdn.hexToBytes(keyHex)
                            BunnyPoorCdn.keyLatch.countDown()
                            
                            println("$TAG Found Key: $keyHex")
                            webView.destroy()
                        }
                        return true
                    }
                    
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        // 진행 중 지속적 주입
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                // 15초 타임아웃
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed({
                    try { 
                        if (BunnyPoorCdn.globalKey == null) {
                            println("$TAG Timeout. Retry reload.")
                            webView.reload()
                        } else {
                            webView.destroy()
                        }
                    } catch(e:Exception){}
                }, 15000)

                val headers = mapOf("Referer" to referer)
                webView.loadUrl(url, headers)
            }
        }
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        @Volatile private var playlistIv: String? = null
        
        // v33 방식의 키 저장소
        @Volatile private var decryptionKey: ByteArray? = null

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
        
        // 외부에서 훔친 키를 주입받는 메소드
        fun updateSession(key: ByteArray?, iv: String?) {
            decryptionKey = key
            playlistIv = iv
        }
        
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun updateSeqMap(map: ConcurrentHashMap<String, Long>) { seqMap = map }

        private fun handleClient(socket: Socket) = thread {
            try {
                socket.soTimeout = 15000
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
                            val res = app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                
                                val key = decryptionKey
                                val decrypted = if (key != null) {
                                    // 키가 있으면 바로 복호화 (Zero-Alloc 방식 적용)
                                    attemptDecrypt(rawData, key, seq)
                                } else {
                                    // 키가 없으면 원본 전송 (재생 안되겠지만 크래시는 방지)
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

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            return try {
                val iv = ByteArray(16)
                if (!playlistIv.isNullOrEmpty()) {
                     val hex = playlistIv!!.removePrefix("0x")
                     hex.chunked(2).take(16).forEachIndexed { i, s -> iv[i] = s.toInt(16).toByte() }
                } else {
                     // 기본 IV 생성 (Big Endian)
                     ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                }
                
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }
}
