package com.tvmon

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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.net.HttpURLConnection
import java.net.URL

/**
 * [Version: v34-Candidate-BruteForce]
 * 1. JS Logic: '0x01 0e 00' 필터링 제거. 16바이트면 무조건 수집 (중복 제거 포함).
 * 2. Kotlin Logic: 수집된 '후보 키(Candidates)'들을 실제 영상 데이터에 대입하여 검증.
 * 3. Validation: 복호화 결과가 '0x47'(Sync Byte)로 시작하면 정답 키로 확정.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
        private const val TAG = "[Bunny-v34]"

        // 정답 키 (검증 완료된 키)
        @Volatile internal var verifiedKey: ByteArray? = null
        
        // 후보 키 리스트 (JS에서 보내온 것들)
        internal val candidateKeys = CopyOnWriteArrayList<ByteArray>()
        
        // 키가 하나라도 들어올 때까지 대기하는 래치 (옵션)
        @Volatile internal var candidatesLatch = CountDownLatch(1)

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
        // 초기화
        proxyServer?.stop()
        proxyServer = null
        verifiedKey = null
        candidateKeys.clear()
        candidatesLatch = CountDownLatch(1)
        
        println("$TAG getUrl started.")
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
        val cleanReferer = "https://tvmon.site/" // TVMON Referer

        // iframe 찾기 로직
        if (!cleanUrl.contains("/v/") && !cleanUrl.contains("/e/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("$TAG Found Iframe: $cleanUrl")
                }
            } catch (e: Exception) {}
        }

        // [비동기] JS Key Stealing 시작
        thread {
            runBlocking {
                JsKeyStealer.stealKey(cleanUrl, DESKTOP_UA, cleanReferer)
            }
        }

        // 프록시 서버 가동
        startProxy(cleanUrl, callback)
        return true
    }

    private suspend fun startProxy(
        targetUrl: String, 
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // M3U8 다운로드 (여기서 Key7 체크)
            val m3u8Res = app.get(targetUrl, headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://tvmon.site/"))
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
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                // 원본 Key 라인은 삭제 (우리가 프록시에서 직접 복호화하므로 플레이어에겐 평문인척 속임)
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    continue 
                }
                
                if (!trimmed.startsWith("#")) {
                    val segmentUrl = when {
                        trimmed.startsWith("http") -> trimmed
                        trimmed.startsWith("/") -> "$domain$trimmed"
                        else -> "$parentUrl/$trimmed"
                    }
                    seqMap[segmentUrl] = currentSeq
                    
                    // 모든 세그먼트를 프록시로 라우팅
                    val encodedSegUrl = URLEncoder.encode(segmentUrl, "UTF-8")
                    newLines.add("$proxyRoot/proxy?url=$encodedSegUrl")
                    currentSeq++
                } else {
                    newLines.add(trimmed)
                }
            }

            val proxyM3u8 = newLines.joinToString("\n")
            proxy.setPlaylist(proxyM3u8)
            proxy.updateSeqMap(seqMap)

            println("$TAG Returning Proxy Playlist URL")
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
        // [수정된 HOOK SCRIPT]
        // 조건: 16바이트면 모두 수집. 단, 중복(Set)은 제거하여 브리지 과부하 방지.
        private const val HOOK_SCRIPT = """
            (function() {
                try {
                    if (window.isHooked) return;
                    window.isHooked = true;
                    
                    var seenKeys = new Set(); // 중복 방지용

                    function checkAndLog(source) {
                        // 조건 완화: 16바이트면 무조건 후보로 등록
                        if (source && source.length === 16) {
                            try {
                                var hex = Array.from(source).map(function(b) {
                                    return ('0' + (b & 0xFF).toString(16)).slice(-2);
                                }).join('');
                                
                                // 이미 본 키가 아니면 전송
                                if (!seenKeys.has(hex)) {
                                    seenKeys.add(hex);
                                    console.log("CANDIDATE_KEY:" + hex);
                                }
                            } catch (e) {}
                        }
                    }

                    const originalSet = Uint8Array.prototype.set;
                    Object.defineProperty(Uint8Array.prototype, 'set', {
                        value: function(source, offset) {
                            if (source) checkAndLog(source);
                            return originalSet.apply(this, arguments);
                        },
                        writable: true, configurable: true
                    });

                    const OriginalUint8Array = window.Uint8Array;
                    function HookedUint8Array(arg1, arg2, arg3) {
                        var arr;
                        if (arguments.length === 0) arr = new OriginalUint8Array();
                        else if (arguments.length === 1) arr = new OriginalUint8Array(arg1);
                        else if (arguments.length === 2) arr = new OriginalUint8Array(arg1, arg2);
                        else arr = new OriginalUint8Array(arg1, arg2, arg3);
                        
                        checkAndLog(arr);
                        return arr;
                    }
                    
                    HookedUint8Array.prototype = OriginalUint8Array.prototype;
                    HookedUint8Array.BYTES_PER_ELEMENT = OriginalUint8Array.BYTES_PER_ELEMENT;
                    HookedUint8Array.from = OriginalUint8Array.from;
                    HookedUint8Array.of = OriginalUint8Array.of;
                    
                    try {
                        Object.defineProperty(window, 'Uint8Array', {
                            value: HookedUint8Array, writable: true, configurable: true
                        });
                    } catch(e) {
                        window.Uint8Array = HookedUint8Array;
                    }

                    console.log("HOOK_INSTALLED");
                } catch(e) {
                    console.log("HOOK_ERROR:" + e.message);
                }
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
                        if (msg.startsWith("CANDIDATE_KEY:")) {
                            val keyHex = msg.substringAfter("CANDIDATE_KEY:")
                            val keyBytes = BunnyPoorCdn.hexToBytes(keyHex)
                            
                            // 후보 리스트에 추가
                            BunnyPoorCdn.candidateKeys.addIfAbsent(keyBytes)
                            
                            // 첫 후보가 들어오면 대기 해제 (일단 시도해볼 수 있도록)
                            if (BunnyPoorCdn.candidatesLatch.count > 0) {
                                BunnyPoorCdn.candidatesLatch.countDown()
                            }
                            
                            // println("$TAG Candidate Added: $keyHex (Total: ${BunnyPoorCdn.candidateKeys.size})")
                        } else if (msg.startsWith("HOOK_ERROR:")) {
                             println("$TAG JS Hook Error: $msg")
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
                    
                    override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                         super.doUpdateVisitedHistory(view, url, isReload)
                         view?.evaluateJavascript(HOOK_SCRIPT, null)
                    }
                }

                // 20초 후 웹뷰 종료 (키 못찾아도 종료)
                val handler = Handler(Looper.getMainLooper())
                handler.postDelayed({
                    try { 
                        if (BunnyPoorCdn.verifiedKey == null) {
                            println("$TAG Timeout. Candidates collected: ${BunnyPoorCdn.candidateKeys.size}")
                        }
                        webView.destroy() 
                    } catch(e:Exception){}
                }, 20000)

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
                    // [Key Wait] 키 후보가 하나라도 모일 때까지 대기
                    if (BunnyPoorCdn.candidateKeys.isEmpty()) {
                        println("$TAG Waiting for candidates...")
                        BunnyPoorCdn.candidatesLatch.await(10, TimeUnit.SECONDS)
                    }

                    val urlParam = path.substringAfter("url=").substringBefore(" ")
                    val targetUrl = URLDecoder.decode(urlParam, "UTF-8")
                    val seq = seqMap[targetUrl] ?: 0L

                    // 영상 데이터 다운로드
                    val url = URL(targetUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.setRequestProperty("User-Agent", "Mozilla/5.0")
                    connection.connect()
                    
                    val inputStream = connection.inputStream
                    val rawData = inputStream.readBytes() // 전체를 메모리에 읽음 (검증을 위해)
                    inputStream.close()

                    // [Brute-Force Verification]
                    // 이미 검증된 키가 있으면 그거 쓰고, 없으면 후보군 다 돌려보기
                    var correctData: ByteArray? = null
                    
                    if (BunnyPoorCdn.verifiedKey != null) {
                        correctData = attemptDecrypt(rawData, BunnyPoorCdn.verifiedKey!!, seq)
                    } else {
                        // 검증 안됐으면 후보군 전수 조사
                        println("$TAG Testing ${BunnyPoorCdn.candidateKeys.size} candidates on Segment $seq")
                        for (key in BunnyPoorCdn.candidateKeys) {
                            val decrypted = attemptDecrypt(rawData, key, seq)
                            // 검증 조건: 첫 바이트가 0x47 (MPEG-TS Sync Byte)
                            if (decrypted != null && decrypted.isNotEmpty() && decrypted[0] == 0x47.toByte()) {
                                println("$TAG Key VERIFIED! : ${Base64.encodeToString(key, Base64.NO_WRAP)}")
                                BunnyPoorCdn.verifiedKey = key // 정답 키 저장
                                correctData = decrypted
                                break
                            }
                        }
                    }

                    val finalData = correctData ?: rawData // 실패하면 원본이라도 내보냄 (어차피 재생 안되겠지만)
                    
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\n\r\n".toByteArray())
                    output.write(finalData)
                }
                output.flush(); socket.close()
            } catch (e: Exception) { 
                try { socket.close() } catch(e2:Exception){} 
            }
        }

        private fun attemptDecrypt(data: ByteArray, key: ByteArray, seq: Long): ByteArray? {
            try {
                val iv = ByteArray(16)
                ByteBuffer.wrap(iv).order(ByteOrder.BIG_ENDIAN).putLong(8, seq)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                return cipher.doFinal(data)
            } catch (e: Exception) { 
                return null 
            }
        }
    }
}
