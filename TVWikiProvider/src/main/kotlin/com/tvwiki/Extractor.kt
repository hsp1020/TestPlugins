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
import com.lagradost.cloudstream3.network.WebViewResolver
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
 * [Version: v46-Final-Stability]
 * 1. Key Logic (v33): Started/Finished/Progress 모든 시점에 JS 주입하여 키 탈취 성공률 극대화.
 * 2. URL Fetch (Old): WebViewResolver를 사용하여 안정적으로 c.html 주소 확보.
 * 3. 403 Fix: Proxy 서버 요청 시 Referer/Origin 헤더를 필수로 포함하여 서버 차단 우회.
 * 4. Logs: 모든 핵심 단계의 println 로그 유지.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v46]"

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
        println("$TAG [Start] getUrl: $url")
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

        if (!cleanUrl.contains("/v/") && !cleanUrl.contains("/e/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // [Track 1] 키 탈취 시작 (v33 훅킹 타이밍 복구)
        val targetUrlForJs = cleanUrl
        Handler(Looper.getMainLooper()).post {
            JsKeyStealer.stealKey(targetUrlForJs, DESKTOP_UA, cleanReferer)
        }

        // [Track 2] URL 확보 (구버전 방식)
        var capturedM3u8Url: String? = null
        try {
            val resolver = WebViewResolver(
                interceptUrl = Regex("""/c\.html|playlist\.m3u8"""), 
                useOkhttp = false,
                timeout = 15000L
            )
            val response = app.get(
                url = cleanUrl,
                headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA),
                interceptor = resolver
            )
            if (response.url.contains("/c.html") || response.url.contains("playlist.m3u8")) {
                capturedM3u8Url = response.url
                println("$TAG [Fetcher] URL Captured: $capturedM3u8Url")
            }
        } catch (e: Exception) { 
            println("$TAG [Fetcher] Error or Timeout: ${e.message}")
        }

        // [Wait] 키 확보 대기 (UI 스레드 영향 없도록 IO에서 대기)
        println("$TAG [Main] Waiting for Key...")
        withContext(Dispatchers.IO) {
            keyLatch.await(20, TimeUnit.SECONDS)
        }
        
        val finalKey = globalKey
        if (finalKey != null && capturedM3u8Url != null) {
            println("$TAG [Success] Launching Proxy with Key and URL.")
            startProxy(capturedM3u8Url, finalKey, callback)
            return true
        } else {
            println("$TAG [Fail] Missing Data. Key=${finalKey != null}, URL=${capturedM3u8Url != null}")
            return false
        }
    }

    private suspend fun startProxy(
        targetUrl: String, 
        key: ByteArray, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // [Critical] 403 에러 방지 헤더
            val requestHeaders = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )

            val m3u8Res = app.get(targetUrl, headers = requestHeaders)
            val m3u8Content = m3u8Res.text

            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
            val hexIv = keyMatch?.groupValues?.get(2)
            
            val proxy = ProxyWebServer()
            proxy.start()
            proxy.updateSession(key, hexIv, requestHeaders) 
            proxyServer = proxy

            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

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

            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) { e.printStackTrace() }
    }

    object JsKeyStealer {
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

        fun stealKey(url: String, ua: String, referer: String) {
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
                        println("$TAG Found Key: $keyHex")
                        BunnyPoorCdn.globalKey = BunnyPoorCdn.hexToBytes(keyHex)
                        BunnyPoorCdn.keyLatch.countDown()
                        webView.destroy()
                    }
                    return true
                }
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    // [v33 복구] 진행 중 지속 주입
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // [v33 복구] 완료 시 주입
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                }
                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    super.onPageStarted(view, url, favicon)
                    // [v33 복구] 시작 시 주입 (가장 중요)
                    view?.evaluateJavascript(HOOK_SCRIPT, null)
                }
            }

            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try { 
                    if (BunnyPoorCdn.keyLatch.count > 0) {
                         println("$TAG [JS] Timeout! Releasing latch.")
                         BunnyPoorCdn.keyLatch.countDown()
                         webView.destroy()
                    }
                } catch(e:Exception){}
            }, 25000)

            val headers = mapOf("Referer" to referer)
            webView.loadUrl(url, headers)
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
        @Volatile private var proxyHeaders: Map<String, String> = emptyMap()

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
        
        fun updateSession(key: ByteArray, iv: String?, h: Map<String, String>) {
            decryptionKey = key
            playlistIv = iv
            proxyHeaders = h
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
                            // [Fix] 세그먼트 요청 시 403 에러 방지를 위해 저장해둔 헤더 사용
                            val res = app.get(targetUrl, headers = proxyHeaders)
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
                cipher.doFinal(data)
            } catch (e: Exception) { null }
        }
    }
}
