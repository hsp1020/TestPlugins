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
import android.util.Log

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
        println("[TVWiki-Snatch] 탈취 프로세스 시작 - 대상: $url")
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // 1. 플레이어 원본 HTML 확보
        val playerPageRes = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer, "User-Agent" to DESKTOP_UA))
        val originalHtml = playerPageRes.text
        
        // 2. 프록시 가동
        proxyServer = ProxyWebServer().apply { start() }
        val proxyPort = proxyServer!!.port
        println("[TVWiki-Snatch] 로컬 프록시 대기 중 (Port: $proxyPort)")

        // 3. 5가지 절대 규칙 검증 로직이 포함된 Hook 스크립트
        val hookScript = """
            <base href="https://player.bunny-frame.online/">
            <script>
            (function() {
                try {
                    var originalSet = Uint8Array.prototype.set;
                    var found = false;

                    function logToApp(status, hex, msg) {
                        var url = "http://SnatchLog/report?status=" + status + "&key=" + hex + "&msg=" + encodeURIComponent(msg);
                        new Image().src = url;
                    }

                    function checkKey(arr) {
                        if (arr.length !== 16) return { v: false, m: "Length mismatch(" + arr.length + ")" };
                        
                        // 규칙 1: 01 0e 00 시작
                        if (arr[0] !== 1 || arr[1] !== 14 || arr[2] !== 0) {
                            return { v: false, m: "Prefix mismatch(" + arr[0] + "," + arr[1] + "," + arr[2] + ")" };
                        }
                        
                        // 규칙 2: 4~10바이트 패턴 영역 (1~7 순열)
                        var pattern = Array.from(arr.slice(3, 10)).sort(function(a,b){return a-b;});
                        for (var i = 0; i < 7; i++) {
                            if (pattern[i] !== i + 1) return { v: false, m: "Pattern(1-7) mismatch" };
                        }
                        
                        return { v: true, m: "Success" };
                    }

                    Uint8Array.prototype.set = function(source, offset) {
                        if (!found && source instanceof Uint8Array && source.length === 16) {
                            var hex = Array.from(source).map(function(b){return('0'+b.toString(16)).slice(-2);}).join('');
                            var res = checkKey(source);
                            
                            if (res.v) {
                                found = true;
                                logToApp("MATCH", hex, "Found real key!");
                                window.location.href = "http://SnatchResult/found?key=" + hex;
                            } else {
                                logToApp("REJECT", hex, res.m);
                            }
                        }
                        return originalSet.apply(this, arguments);
                    };
                    console.log("Snatcher Active");
                } catch (e) { logToApp("ERROR", "", e.toString()); }
            })();
            </script>
        """.trimIndent()

        proxyServer!!.prepare(originalHtml.replaceFirst("<head>", "<head>$hookScript"), cleanUrl)

        // 4. 웹뷰 실행 및 결과 가로채기
        val keyResolver = WebViewResolver(
            interceptUrl = Regex("""http://SnatchResult/found\?key=([a-fA-F0-9]+)"""),
            useOkhttp = false,
            timeout = 25000L
        )

        var foundKeyHex: String? = null
        try {
            println("[TVWiki-Snatch] 웹뷰에서 플레이어 로직 실행 중...")
            val response = app.get("http://127.0.0.1:$proxyPort/index.html", interceptor = keyResolver)
            foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(response.url)?.groupValues?.get(1)
        } catch (e: Exception) {
            foundKeyHex = Regex("""key=([a-fA-F0-9]+)""").find(e.message ?: "")?.groupValues?.get(1)
        }

        if (foundKeyHex != null) {
            println("[TVWiki-Snatch] ★★★ 키 탈취 성공! 값: $foundKeyHex ★★★")
            val keyBytes = foundKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            proxyServer!!.setKey(keyBytes)

            // c.html(m3u8) 확보 및 변조 대기
            try {
                val cHtmlResolver = WebViewResolver(interceptUrl = Regex("""/c\.html"""), timeout = 10000L)
                val cHtmlRes = app.get(cleanUrl, headers = mapOf("User-Agent" to DESKTOP_UA), interceptor = cHtmlResolver)
                val m3u8Url = cHtmlRes.url
                val m3u8Raw = app.get(m3u8Url, headers = mapOf("User-Agent" to DESKTOP_UA)).text
                proxyServer!!.setM3u8(m3u8Raw, m3u8Url)

                callback(
                    newExtractorLink(name, name, "http://127.0.0.1:$proxyPort/video.m3u8", ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            } catch (e: Exception) {
                println("[TVWiki-Snatch] M3U8 확보 실패: ${e.message}")
            }
        } else {
            println("[TVWiki-Snatch] !!! 키 탈취 실패 (규칙에 맞는 키가 25초 내에 생성되지 않음) !!!")
        }

        return false
    }

    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        private var hookedHtml = ""
        private var m3u8Raw = ""
        private var m3u8Url = ""
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
            } catch (e: Exception) {}
        }

        fun stop() { isRunning = false; try { serverSocket?.close() } catch (e: Exception) {} }
        fun prepare(html: String, url: String) { hookedHtml = html }
        fun setKey(k: ByteArray) { key = k }
        fun setM3u8(raw: String, url: String) { m3u8Raw = raw; m3u8Url = url }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    val reader = socket.getInputStream().bufferedReader()
                    val line = reader.readLine() ?: return@thread
                    val path = line.split(" ").getOrNull(1) ?: ""
                    val output = socket.getOutputStream()

                    when {
                        path.contains("/report") -> {
                            val status = path.substringAfter("status=", "").substringBefore("&")
                            val msg = java.net.URLDecoder.decode(path.substringAfter("msg=", "").substringBefore("&"), "UTF-8")
                            val k = path.substringAfter("key=", "").substringBefore("&")
                            println("[TVWiki-Snatch][JS-Log] $status | Key: $k | Reason: $msg")
                            output.write("HTTP/1.1 204 No Content\r\n\r\n".toByteArray())
                        }
                        path.contains("/index.html") -> {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n$hookedHtml".toByteArray())
                        }
                        path.contains("/video.m3u8") -> {
                            val baseUrl = m3u8Url.substringBeforeLast("/")
                            val modified = m3u8Raw.lines().joinToString("\n") { l ->
                                when {
                                    l.contains("#EXT-X-KEY") -> l.replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/key.bin"""")
                                    !l.startsWith("#") && l.isNotEmpty() && !l.startsWith("http") -> "$baseUrl/$l"
                                    else -> l
                                }
                            }
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n\r\n$modified".toByteArray())
                        }
                        path.contains("/key.bin") -> {
                            output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n\r\n".toByteArray())
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
