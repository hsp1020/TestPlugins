package com.tvwiki

import android.os.Handler
import android.os.Looper
import android.util.Base64
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
 * [Version: v43-Final-Rollback-Stability]
 * 1. Rollback: v33의 키 추출 방식(Constructor Hooking)을 100% 복구.
 * 2. Bug Fix: v33의 ParserException 해결 (WebView 로딩 중 실제 c.html URL을 Intercept하여 확보).
 * 3. Log Restore: 키 발견, URL 포획, 프록시 통신 등 모든 디버깅 로그 복구.
 * 4. Threading: UI 스레드 차단 없이 백그라운드에서 키 대기.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v43]"

        // 전역 공유 변수
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
        
        // 상태 초기화
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

        // 1. Iframe 주소 보정
        if (!cleanUrl.contains("/v/") && !cleanUrl.contains("/e/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$TAG Iframe 발견: $cleanUrl")
                }
            } catch (e: Exception) {}
        }

        // 2. [v33 방식] 메인 스레드에서 데이터 탈취 시작
        val finalUrl = cleanUrl
        Handler(Looper.getMainLooper()).post {
            JsKeyStealer.stealData(finalUrl, DESKTOP_UA, cleanReferer)
        }

        // 3. [Wait] 정보 수집 완료 대기 (UI 스레드 차단 방지 위해 백그라운드 대기)
        println("$TAG [Main] 키와 M3U8 주소 대기 중...")
        val success = withContext(Dispatchers.IO) {
            readyLatch.await(20, TimeUnit.SECONDS)
        }
        
        // 4. 병합 결과 확인 및 프록시 실행
        val extractedKey = globalKey
        val extractedUrl = globalM3u8Url

        if (extractedKey != null && extractedUrl != null) {
            println("$TAG [Success] 모든 정보 확보! 재생을 시작합니다.")
            println("$TAG - URL: $extractedUrl")
            startProxy(extractedUrl, extractedKey, callback)
            return true
        } else {
            println("$TAG [Fail] 정보 확보 실패. Key=${extractedKey != null}, URL=${extractedUrl != null}")
            return false
        }
    }

    private suspend fun startProxy(
        targetUrl: String, 
        key: ByteArray, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/"
            )

            // 진짜 M3U8 데이터 다운로드
            println("$TAG [Proxy] M3U8 리스트 다운로드: $targetUrl")
            val m3u8Res = app.get(targetUrl, headers = headers)
            val m3u8Content = m3u8Res.text

            // IV 추출
            val keyMatch = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"(?:,IV=(0x[0-9a-fA-F]+))?""").find(m3u8Content)
            val hexIv = keyMatch?.groupValues?.get(2)
            println("$TAG [Proxy] IV 포착: $hexIv")
            
            val proxy = ProxyWebServer()
            proxy.start()
            proxy.updateSession(key, hexIv) 
            proxyServer = proxy

            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

            // 플레이리스트 재작성
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

            println("$TAG [Proxy] 플레이어 연결 준비 완료.")
            callback(
                newExtractorLink(name, name, "$proxyRoot/playlist.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
        } catch (e: Exception) {
            println("$TAG [Proxy] 치명적 오류: ${e.message}")
            e.printStackTrace()
        }
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
                        println("$TAG [JS] 키 발견: $keyHex")
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

                // [Fix] c.html 주소 낚아채기 (네트워크 간섭 최소화)
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url.toString()
                    if (reqUrl.contains("/c.html") || reqUrl.contains("playlist.m3u8")) {
                        if (BunnyPoorCdn.globalM3u8Url == null) {
                            println("$TAG [JS] M3U8 주소 포착: $reqUrl")
                            BunnyPoorCdn.globalM3u8Url = reqUrl
                            // 정보 확보 체크
                            Handler(Looper.getMainLooper()).post { checkAndRelease(webView) }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            // 15초 안전장치
            val handler = Handler(Looper.getMainLooper())
            handler.postDelayed({
                try { 
                    if (BunnyPoorCdn.readyLatch.count > 0) {
                         println("$TAG [JS] 타임아웃 발생. 대기 해제.")
                         BunnyPoorCdn.readyLatch.countDown()
                         webView.destroy()
                    }
                } catch(e:Exception){}
            }, 15000)

            val headers = mapOf("Referer" to referer)
            webView.loadUrl(url, headers)
        }
        
        private fun checkAndRelease(webView: WebView) {
            // Key와 URL이 모두 준비되면 Latch 해제
            if (BunnyPoorCdn.globalKey != null && BunnyPoorCdn.globalM3u8Url != null) {
                println("$TAG [JS] 키와 주소 모두 확보 완료. 메인 프로세스 진행.")
                if (BunnyPoorCdn.readyLatch.count > 0) {
                    BunnyPoorCdn.readyLatch.countDown()
                    try { webView.destroy() } catch(e:Exception){}
                }
            }
        }

        // v41에서 누락된 stealData 정식 명칭 수정
        fun stealData(url: String, ua: String, referer: String) = stealKey(url, ua, referer)
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var seqMap: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
        @Volatile private var playlistIv: String? = null
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
        
        fun updateSession(key: ByteArray, iv: String?) {
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
                                    attemptDecrypt(rawData, key, seq)
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
