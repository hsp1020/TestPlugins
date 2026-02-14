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
        println("[TVWiki-Debug] getUrl 시작 - URL: $url")
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

        // 1. iframe 추출
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki-Debug] iframe 주소 갱신: $cleanUrl")
                }
            } catch (e: Exception) { 
                println("[TVWiki-Debug] iframe 추출 실패: ${e.message}")
            }
        }

        var capturedUrl: String? = null
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        // 2. c.html 가로채기
        try {
            println("[TVWiki-Debug] WebView 가동하여 c.html 가로채기 시도...")
            val requestHeaders = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA)
            val response = app.get(url = cleanUrl, headers = requestHeaders, interceptor = resolver)
            if (response.url.contains("/c.html")) {
                capturedUrl = response.url
                println("[TVWiki-Debug] c.html 가로채기 성공: $capturedUrl")
            }
        } catch (e: Exception) { 
            println("[TVWiki-Debug] c.html 가로채기 중 오류: ${e.message}")
        }

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
                println("[TVWiki-Debug] c.html 내용 분석 중...")
                val originalContent = app.get(capturedUrl, headers = headers).text
                
                if (originalContent.contains("key7") || originalContent.contains("mode=obfuscated")) {
                    println("[TVWiki-Debug] Key7 암호화 감지! 웹뷰 후킹 프로세스 진입")
                    
                    proxyServer = ProxyWebServer().apply { start() }
                    val proxyPort = proxyServer!!.port
                    println("[TVWiki-Debug] 로컬 프록시 서버 가동됨 (Port: $proxyPort)")
                    
                    // JS 후킹 코드 작성 (3-7-6 구조 검증 포함)
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
                                    // 규칙 2: 4~10바이트가 1~7 숫자의 순열인지 확인
                                    var pattern = Array.from(arr.slice(3, 10)).sort(function(a,b){return a-b;});
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
                                        // 앱이 가로챌 URL로 키 전송
                                        window.location.href = "http://TvWikiKeyGrabber/found?key=" + hex;
                                    }
                                    return originalSet.apply(this, arguments);
                                };
                                console.log("Hook Ready.");
                            } catch (e) { }
                        })();
                        </script>
                    """.trimIndent()

                    proxyServer!!.prepare(hookScript, capturedUrl, originalContent)
                    
                    val keyResolver = WebViewResolver(
                        interceptUrl = Regex("""http://TvWikiKeyGrabber/found\?key=([a-fA-F0-9]+)"""),
                        useOkhttp = false,
                        timeout = 20000L
                    )
                    
                    var foundKeyHex: String? = null
                    try {
                        println("[TVWiki-Debug] 키 사냥용 웹뷰 실행 중 (최대 20초)...")
                        val hookUrl = "http://127.0.0.1:$proxyPort/c.html"
                        val response = app.get(hookUrl, headers = headers, interceptor = keyResolver)
                        foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(response.url)?.groupValues?.get(1)
                    } catch (e: Exception) {
                        foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(e.message ?: "")?.groupValues?.get(1)
                    }

                    if (foundKeyHex != null) {
                        println("[TVWiki-Debug] ★★★ 키 탈취 성공: $foundKeyHex ★★★")
                        val keyBytes = foundKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        proxyServer!!.setKey(keyBytes)
                        
                        println("[TVWiki-Debug] 최종 재생 링크 전송 중...")
                        callback(
                            newExtractorLink(name, name, "http://127.0.0.1:$proxyPort/video.m3u8", ExtractorLinkType.M3U8) {
                                this.referer = "https://player.bunny-frame.online/"
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                        return true
                    } else {
                        println("[TVWiki-Debug] !!! 키 탈취 실패 (검증 조건을 만족하는 키가 발견되지 않음) !!!")
                    }
                }
            } catch (e: Exception) { 
                println("[TVWiki-Debug] 예외 발생: ${e.message}")
            }

            // 일반 모드
            println("[TVWiki-Debug] 일반 모드 재생 시도 (key7 미감지 혹은 실패)")
            callback(
                newExtractorLink(name, name, "$capturedUrl#.m3u8", ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        println("[TVWiki-Debug] extract 프로세스 실패")
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
            try { serverSocket?.close() } catch (e: Exception) {}
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

                    println("[TVWiki-Debug] 프록시 요청 수신: $path")

                    when {
                        path.contains("/c.html") -> {
                            val html = "<html><head>$hookScript</head><body></body></html>"
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nContent-Length: ${html.length}\r\n\r\n$html"
                            output.write(response.toByteArray())
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
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nContent-Length: ${modified.toByteArray().size}\r\n\r\n"
                            output.write(response.toByteArray())
                            output.write(modified.toByteArray())
                        }
                        path.contains("/key.bin") -> {
                            println("[TVWiki-Debug] 플레이어가 복호화 키를 요청했습니다.")
                            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: ${key?.size ?: 0}\r\n\r\n"
                            output.write(response.toByteArray())
                            key?.let { output.write(it) }
                        }
                    }
                    output.flush()
                    socket.close()
                } catch (e: Exception) { 
                    try { socket.close() } catch(e2: Exception) {} 
                }
            }
        }
    }
}
