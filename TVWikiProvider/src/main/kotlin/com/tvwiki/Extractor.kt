package com.tvwiki

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import kotlin.concurrent.thread
import android.util.Base64

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    companion object {
        private var proxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        proxyServer?.stop()
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

        // 1. iframe 주소 추출 로직
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { e.printStackTrace() }
        }

        var capturedUrl: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        // 2. c.html URL 가로채기
        try {
            val requestHeaders = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA)
            val response = app.get(url = cleanUrl, headers = requestHeaders, interceptor = resolver)
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
            }
        } catch (e: Exception) { e.printStackTrace() }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                // 3. c.html(M3U8) 내용 확인
                val originalContent = app.get(capturedUrl, headers = headers).text
                
                // 최신 암호화(key7) 감지 시 웹뷰 후킹 시도
                if (originalContent.contains("key7") || originalContent.contains("mode=obfuscated")) {
                    proxyServer = ProxyWebServer().apply { start() }
                    val proxyPort = proxyServer!!.port
                    
                    // 4. 진짜 키 검증 로직이 포함된 후킹 스크립트 작성
                    val hookScript = """
                        <base href="https://player.bunny-frame.online/">
                        <script>
                        (function() {
                            try {
                                var originalSet = Uint8Array.prototype.set;
                                var found = false;
                                
                                function isValidKey(arr) {
                                    if (arr.length !== 16) return false;
                                    // 규칙 1: 01 0e 00 시작
                                    if (arr[0] !== 1 || arr[1] !== 14 || arr[2] !== 0) return false;
                                    // 규칙 2: 4~10바이트가 01~07 순열인지 확인
                                    var pattern = Array.from(arr.slice(3, 10)).sort();
                                    for (var i = 0; i < 7; i++) {
                                        if (pattern[i] !== i + 1) return false;
                                    }
                                    return true;
                                }

                                Uint8Array.prototype.set = function(source, offset) {
                                    if (!found && source instanceof Uint8Array && isValidKey(source)) {
                                        found = true;
                                        var hex = Array.from(source).map(function(b) { 
                                            return ('0' + b.toString(16)).slice(-2); 
                                        }).join('');
                                        window.location.href = "http://TvWikiKeyGrabber/found?key=" + hex;
                                    }
                                    return originalSet.apply(this, arguments);
                                };
                            } catch (e) { }
                        })();
                        </script>
                    """.trimIndent()

                    // m3u8 주소 변조 및 프록시 설정
                    proxyServer!!.prepare(hookScript, capturedUrl, originalContent)
                    
                    val keyResolver = WebViewResolver(
                        interceptUrl = Regex("""http://TvWikiKeyGrabber/found\?key=([a-fA-F0-9]+)"""),
                        useOkhttp = false,
                        timeout = 20000L
                    )
                    
                    var foundKeyHex: String? = null
                    try {
                        // 변조된 페이지를 웹뷰에서 실행하여 키 탈취
                        val hookUrl = "http://127.0.0.1:$proxyPort/c.html"
                        val response = app.get(hookUrl, headers = headers, interceptor = keyResolver)
                        foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(response.url)?.groupValues?.get(1)
                    } catch (e: Exception) {
                        // 빌드 에러 해결: lastInterceptedUrl 대신 e.message에서 추출
                        foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(e.message ?: "")?.groupValues?.get(1)
                    }

                    if (foundKeyHex != null) {
                        val keyBytes = foundKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        proxyServer!!.setKey(keyBytes)
                        
                        callback(
                            newExtractorLink(name, name, "http://127.0.0.1:$proxyPort/video.m3u8", ExtractorLinkType.M3U8) {
                                this.referer = "https://player.bunny-frame.online/"
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                        return true
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // 일반 영상 처리
            callback(
                newExtractorLink(name, name, "$capturedUrl#.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        private var hookScript: String = ""
        private var originalUrl: String = ""
        private var originalContent: String = ""
        private var key: ByteArray? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0)
                port = serverSocket!!.localPort
                isRunning = true
                thread(isDaemon = true) {
                    while (isRunning) {
                        try { handleClient(serverSocket!!.accept()) } catch (e: Exception) {}
                    }
                }
            } catch (e: Exception) { }
        }

        fun stop() {
            isRunning = false
            serverSocket?.close()
        }

        fun prepare(script: String, url: String, content: String) {
            hookScript = script
            originalUrl = url
            originalContent = content
        }

        fun setKey(k: ByteArray) { key = k }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    val reader = socket.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@thread
                    val path = line.split(" ").getOrNull(1) ?: ""
                    val output = socket.getOutputStream()

                    when {
                        path.contains("/c.html") -> {
                            // 웹뷰용 후킹 페이지 (HTML로 감싸서 전달)
                            val html = "<html><head>$hookScript</head><body></body></html>"
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n$html".toByteArray())
                        }
                        path.contains("/video.m3u8") -> {
                            val baseUrl = originalUrl.substringBeforeLast("/")
                            val modified = originalContent.lines().joinToString("\n") { l ->
                                when {
                                    l.contains("#EXT-X-KEY") -> l.replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/key.bin"""")
                                    !l.startsWith("#") && l.isNotEmpty() && !l.startsWith("http") -> "$baseUrl/$l"
                                    else -> l
                                }
                            }
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n$modified".toByteArray())
                        }
                        path.contains("/key.bin") -> {
                            output.write("HTTP/1.1 200 OK\r\n\r\n".toByteArray())
                            key?.let { output.write(it) }
                        }
                    }
                    output.flush()
                    socket.close()
                } catch (e: Exception) { socket.close() }
            }
        }
    }
}
