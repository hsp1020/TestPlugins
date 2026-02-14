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
 * [Version: v30-Final-Solution]
 * 1. JS Injection Timing: onProgressChanged 추가로 주입 시점 극대화.
 * 2. Proxy Wait: 키가 확보될 때까지 프록시 응답을 지연시켜 ExoPlayer 에러 방지.
 * 3. Retry: 키 미확보 시 WebView 새로고침 시도.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        // 전역 키 저장소 (프록시와 공유)
        @Volatile private var globalKey: ByteArray? = null
        private val keyLatch = CountDownLatch(1) // 키 대기용
        private const val TAG = "[Bunny-v30]"
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        proxyServer?.stop()
        proxyServer = null
        globalKey = null // 키 초기화
        // Latch 재생성 (Count 1)
        while (keyLatch.count > 0) keyLatch.countDown() // 기존 대기 해제
        
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

        // [비동기] JS Hooking 시작 (결과 기다리지 않고 프록시 준비)
        // 키가 찾아지면 globalKey에 저장되고 keyLatch가 열림
        thread {
            runBlocking {
                JsKeyStealer.stealKey(cleanUrl, DESKTOP_UA, cleanReferer)
            }
        }

        // 3. 프록시 서버 가동 (키 없이 일단 시작)
        startProxy(cleanUrl, callback)
        return true
    }

    private suspend fun startProxy(
        targetUrl: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val m3u8Res = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://tvwiki5.net/"))
            val m3u8Url = m3u8Res.url
            val m3u8Content = m3u8Res.text

            val proxy = ProxyWebServer()
            proxy.start()
            proxyServer = proxy

            val proxyPort = proxy.port
            val proxyRoot = "http://127.0.0.1:$proxyPort"

            val newLines = mutableListOf<String>()
            val lines = m3u8Content.lines()
            val seqMap = ConcurrentHashMap<String, Long>()
            var currentSeq = Regex("""#EXT-X-MEDIA-SEQUENCE:(\d+)""").find(m3u8Content)?.groupValues?.get(1)?.toLong() ?: 0L
            
            val uri = URI(m3u8Url)
            val domain = "${uri.scheme}://${uri.host}"
            val parentUrl = m3u8Url.substringBeforeLast("/")

            for (line in lines) {
                if (line.startsWith("#EXT-X-KEY")) {
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
                            // 전역 변수에 키 저장 & Latch 해제
                            globalKey = hexToBytes(keyHex)
                            // 10번 카운트다운 (혹시 모를 중복 방지)
                             while (keyLatch.count > 0) keyLatch.countDown()
                            
                            println("$TAG Found Key: $keyHex")
                            webView.destroy()
                        }
                        return true
                    }
                    
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        super.onProgressChanged(view, newProgress)
                        // 진행 중에도 지속적 주입
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                // 20초 타임아웃
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed({
                    try { 
                        if (globalKey == null) {
                            println("$TAG Timeout. Retry reload.")
                            webView.reload() // 리로드 시도
                        } else {
                            webView.destroy()
                        }
                    } catch(e:Exception){}
                }, 10000)

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
                    // [Key Wait Logic] 키가 구해질 때까지 대기 (최대 15초)
                    if (globalKey == null) {
                        println("$TAG Waiting for key...")
                        // 15초 동안 키가 구해지길 기다림
                        // Latch는 0이 되면 풀림
                         val latch = BunnyPoorCdn.Companion.javaClass.getDeclaredField("keyLatch").get(null) as CountDownLatch
                         latch.await(15, TimeUnit.SECONDS)
                    }

                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L

                    runBlocking {
                        try {
                            val res = app.get(targetUrl, headers = mapOf("User-Agent" to "Mozilla/5.0"))
                            if (res.isSuccessful) {
                                val rawData = res.body.bytes()
                                output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                                
                                val key = globalKey
                                val decrypted = if (key != null) {
                                    decryptWithBlindTrim(rawData, key, seq)
                                } else {
                                    // 키가 없으면 그냥 원본 보냄 (재생 실패하겠지만 타임아웃보단 나음)
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
            if (trimOffset != -1) {
                return attemptDecrypt(data, key, seq, trimOffset)
            }
            for (offset in 0..256) {
                if (data.size <= offset + 188) break
                val dec = attemptDecrypt(data, key, seq, offset)
                if (dec != null && dec.size > 188 && dec[0] == 0x47.toByte()) {
                    trimOffset = offset
                    return dec
                }
            }
            return null
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long, offset: Int): ByteArray? {
            try {
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
