package com.tvwiki

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * [Version: v44-Final-Pro-Fix]
 * 1. FIX 403 ERROR: Proxy 서버의 요청 헤더에 Origin 및 Referer 강제 주입.
 * 2. Key Stealer (v33): 검증된 v33의 JS Hooking 로직 100% 복구.
 * 3. URL Interceptor: WebView 로딩 중 실제 c.html 주소를 낚아채어 ParserException 원천 차단.
 * 4. Stability: 모든 정보(Key + URL)가 모일 때까지 안전하게 대기 후 재생 시작.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v44]"

        @Volatile internal var globalKey: ByteArray? = null
        @Volatile internal var globalM3u8Url: String? = null
        @Volatile internal var readyLatch = CountDownLatch(1)

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
        println("$TAG [Start] getUrl: $url")
        proxyServer?.stop()
        proxyServer = null
        globalKey = null
        globalM3u8Url = null
        readyLatch = CountDownLatch(1)
        
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

        // [1] 메인 스레드에서 WebView 실행 (키와 진짜 URL 동시 탈취)
        val finalUrl = cleanUrl
        Handler(Looper.getMainLooper()).post {
            JsKeyStealer.captureEverything(finalUrl, DESKTOP_UA, cleanReferer)
        }

        // [2] 정보 수집 대기 (최대 20초)
        println("$TAG [Wait] 정보 수집 대기 중...")
        val success = withContext(Dispatchers.IO) {
            readyLatch.await(20, TimeUnit.SECONDS)
        }
        
        val extractedKey = globalKey
        val extractedUrl = globalM3u8Url

        if (extractedKey != null && extractedUrl != null) {
            println("$TAG [Success] 모든 정보 확보 완료. 프록시 가동.")
            startProxy(extractedUrl, extractedKey, callback)
            return true
        } else {
            println("$TAG [Fail] 필수 정보 누락. Key=${extractedKey != null}, URL=${extractedUrl != null}")
            return false
        }
    }

    private suspend fun startProxy(
        targetUrl: String, 
        key: ByteArray, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // [중요] 403 에러 방지를 위한 핵심 헤더 설정
            val headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )

            // 1. 진짜 M3U8 데이터 다운로드
            val m3u8Res = app.get(targetUrl, headers = headers)
            val m3u8Content = m3u8Res.text

            // 2. IV 추출 (M3U8 내부 #EXT-X-KEY 라인)
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
            val hexIv = keyMatch?.groupValues?.get(2)
            println("$TAG [Proxy] IV 감지: $hexIv")
            
            // 3. 프록시 서버 시작
            val proxy = ProxyWebServer()
            proxy.start()
            proxy.updateSession(key, hexIv, headers) // 탈취한 키와 헤더 주입
            proxyServer = proxy

            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

            // 4. M3U8 플레이리스트 재작성
            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()
            val seqMap = ConcurrentHashMap<String, Long>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
            
            val uri = URI(targetUrl)
            val domain = "${uri.scheme}://${uri.host}"
            val parentUrl = targetUrl.substringBeforeLast("/")

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) continue 
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

            proxy.setPlaylist(newLines.joinToString("\n"))
            proxy.updateSeqMap(seqMap)

            // 5. 플레이어에 로컬 프록시 링크 전달
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("$TAG [Proxy] 실행 중 오류: ${e.message}")
            e.printStackTrace()
        }
    }

    object JsKeyStealer {
        // v33에서 검증된 3-7-6 구조 필터링 스크립트
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

        fun captureEverything(url: String, ua: String, referer: String) {
            val webView = WebView(AcraApplication.context!!)
            
            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                userAgentString = ua
                blockNetworkImage = true 
            }

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    if (msg.startsWith("MAGIC_KEY_FOUND:")) {
                        val keyHex = msg.substringAfter("MAGIC_KEY_FOUND:")
                        println("$TAG [JS] 진짜 키 발견: $keyHex")
                        BunnyPoorCdn.globalKey = BunnyPoorCdn.hexToBytes(keyHex)
                        checkAndRelease(webView)
                    }
                    return true
                }
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
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

                // [중요] 실제 스트리밍 URL(c.html) 포획
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url.toString()
                    if (reqUrl.contains("/c.html") || reqUrl.contains("playlist.m3u8")) {
                        if (BunnyPoorCdn.globalM3u8Url == null) {
                            println("$TAG [JS] M3U8 주소 포획: $reqUrl")
                            BunnyPoorCdn.globalM3u8Url = reqUrl
                            // 정보 확보 체크
                            Handler(Looper.getMainLooper()).post { checkAndRelease(webView) }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try { 
                    if (BunnyPoorCdn.readyLatch.count > 0) {
                         println("$TAG [JS] 타임아웃 발생. 대기 해제.")
                         BunnyPoorCdn.readyLatch.countDown()
                         webView.destroy()
                    }
                } catch(e:Exception){}
            }, 20000)

            val headers = mapOf("Referer" to referer)
            webView.loadUrl(url, headers)
        }
        
        private fun checkAndRelease(webView: WebView) {
            if (BunnyPoorCdn.globalKey != null && BunnyPoorCdn.globalM3u8Url != null) {
                println("$TAG [JS] 모든 데이터 수집 완료. 메인 로직 가동.")
                if (BunnyPoorCdn.readyLatch.count > 0) {
                    BunnyPoorCdn.readyLatch.countDown()
                    try { webView.destroy() } catch(e:Exception){}
                }
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
        @Volatile private var decryptionKey: ByteArray? = null
        @Volatile private var requestHeaders: Map<String, String> = emptyMap()

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
        
        fun updateSession(key: ByteArray, iv: String?, headers: Map<String, String>) {
            decryptionKey = key
            playlistIv = iv
            requestHeaders = headers // 403 방지용 헤더 저장
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
                            // [핵심] 세그먼트 요청 시 403 에러 방지를 위해 저장해둔 헤더 사용
                            val res = app.get(targetUrl, headers = requestHeaders)
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                
                                val key = decryptionKey
                                val decrypted = if (key != null) attemptDecrypt(rawData, key, seq) else null
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
                     ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                }
                
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }
}
