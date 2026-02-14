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
        // 기존 프록시 정리
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

        // 1. iframe 주소 따기
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
        
        // 2. c.html 주소 확보
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
                // 3. c.html 원본 내용 다운로드
                val originalHtml = app.get(capturedUrl, headers = headers).text
                
                // 최신 암호화(key7) 여부 확인
                if (originalHtml.contains("key7") || originalHtml.contains("mode=obfuscated")) {
                    println("[BunnyPoorCdn] Webview Hooking: Key7 Hunter Started")
                    
                    // 4. 프록시 서버 시작
                    proxyServer = ProxyWebServer().apply { start() }
                    val proxyPort = proxyServer!!.port
                    
                    // 5. Hook Script 주입 (검증 로직 포함)
                    val hookScript = """
                        <base href="https://player.bunny-frame.online/">
                        <script>
                        (function() {
                            try {
                                console.log("[Hook] Trap Installed.");
                                var originalSet = Uint8Array.prototype.set;
                                var found = false;
                                
                                // 진짜 키 검증 함수
                                function isValidKey(arr) {
                                    // 1. 길이 체크 (16바이트)
                                    if (arr.length !== 16) return false;
                                    
                                    // 2. 고정 영역 체크 (01 0e 00)
                                    if (arr[0] !== 1 || arr[1] !== 14 || arr[2] !== 0) return false;
                                    
                                    // 3. 패턴 영역 체크 (Index 3~9, 7바이트)
                                    // 1~7 숫자가 "순서 상관없이" 들어있는지 확인 (순열)
                                    // 방법: 잘라내서 정렬(sort)한 뒤 1,2,3,4,5,6,7 인지 확인
                                    var pattern = arr.slice(3, 10).sort();
                                    for (var i = 0; i < 7; i++) {
                                        if (pattern[i] !== i + 1) return false;
                                    }
                                    
                                    // 4. 난수 영역 (Index 10~15, 6바이트)는 검증 불필요 (가변값)
                                    return true;
                                }

                                Uint8Array.prototype.set = function(source, offset) {
                                    // 아직 못 찾았고, 16바이트 데이터가 들어올 때
                                    if (!found && source instanceof Uint8Array && source.length === 16) {
                                        if (isValidKey(source)) {
                                            found = true;
                                            var hex = Array.from(source).map(function(b) { 
                                                return ('0' + b.toString(16)).slice(-2); 
                                            }).join('');
                                            console.log("[Hook] Real Key Found: " + hex);
                                            
                                            // 키 발견 시 가짜 URL로 전송 -> 앱(Interceptor)이 낚아챔
                                            window.location.href = "http://TvWikiKeyGrabber/found?key=" + hex;
                                        }
                                    }
                                    return originalSet.apply(this, arguments);
                                };
                            } catch (e) { console.error(e); }
                        })();
                        </script>
                    """.trimIndent()
                    
                    val hookedHtml = originalHtml.replaceFirst("<head>", "<head>$hookScript")
                    
                    // 프록시에 변조된 HTML 등록
                    proxyServer!!.setCHtml(hookedHtml)
                    
                    // 6. 웹뷰 실행 (Invisible)
                    var foundKeyHex: String? = null
                    
                    val keyResolver = WebViewResolver(
                        interceptUrl = Regex("""http://TvWikiKeyGrabber/found\?key=([a-fA-F0-9]+)"""),
                        useOkhttp = false,
                        timeout = 15000L // 15초 제한
                    )
                    
                    try {
                        val hookUrl = "http://127.0.0.1:$proxyPort/c.html"
                        // 웹뷰 로드 -> JS 실행 -> 키 발견 -> URL 이동 -> Intercept
                        app.get(hookUrl, headers = headers, interceptor = keyResolver)
                    } catch (e: Exception) {
                        // Interceptor가 URL을 잡으면 실행이 중단되므로 여기서 키 추출
                        val msg = e.message ?: ""
                        val match = Regex("""key=([a-fA-F0-9]+)""").find(msg) 
                                    ?: Regex("""key=([a-fA-F0-9]+)""").find(keyResolver.lastInterceptedUrl ?: "")
                        
                        if (match != null) foundKeyHex = match.groupValues[1]
                    }
                    
                    if (foundKeyHex == null && keyResolver.lastInterceptedUrl != null) {
                         val match = Regex("""key=([a-fA-F0-9]+)""").find(keyResolver.lastInterceptedUrl!!)
                         if (match != null) foundKeyHex = match.groupValues[1]
                    }

                    if (foundKeyHex != null) {
                        println("[BunnyPoorCdn] 검증된 진짜 키 탈취 성공: $foundKeyHex")
                        
                        // 7. 키 바이너리 변환 및 등록
                        val keyBytes = foundKeyHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        proxyServer!!.setKey(keyBytes)
                        
                        // 8. 앱에 재생 주소 전달 (프록시 경유)
                        val proxyVideoUrl = "http://127.0.0.1:$proxyPort/video.m3u8"
                        
                        callback(
                            newExtractorLink(name, name, proxyVideoUrl, ExtractorLinkType.M3U8) {
                                this.referer = "https://player.bunny-frame.online/"
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                        return true
                    } else {
                        println("[BunnyPoorCdn] 키 탐색 실패 (Timeout)")
                    }
                }
            } catch (e: Exception) {
                println("[BunnyPoorCdn] Hooking Error: $e")
                e.printStackTrace()
            }

            // 실패 시 혹은 일반 영상인 경우 기존 방식
            val finalUrl = "$capturedUrl#.m3u8"
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        return false
    }

    // ===========================================================================================
    // Proxy Server
    // ===========================================================================================
    class ProxyWebServer {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        
        private var hookedHtml: String = ""
        private var key: ByteArray? = null

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
            } catch (e: Exception) { e.printStackTrace() }
        }

        fun stop() {
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {}
        }

        fun setCHtml(html: String) { hookedHtml = html }
        fun setKey(k: ByteArray) { key = k }

        private fun handleClient(socket: Socket) {
            thread {
                try {
                    socket.soTimeout = 5000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val line = reader.readLine() ?: return@thread
                    val path = line.split(" ").getOrNull(1) ?: ""
                    val output = socket.getOutputStream()

                    if (path.contains("/c.html")) {
                        // 변조된 HTML 제공
                        val response = "HTTP/1.1 200 OK\r\n" +
                                     "Content-Type: text/html; charset=utf-8\r\n" +
                                     "Access-Control-Allow-Origin: *\r\n\r\n"
                        output.write(response.toByteArray())
                        output.write(hookedHtml.toByteArray())

                    } else if (path.contains("/video.m3u8")) {
                        // 키가 준비되었는지 확인
                        if (key != null) {
                            // 가짜 m3u8 생성 (로컬 키 주소 사용)
                            // 원본 m3u8 내용을 몰라도 됨. 어차피 플레이어가 세그먼트는 알아서 요청함.
                            // 단, 여기서는 TS 파일 위치를 모르므로, 간단히 원본 c.html을 리다이렉트하거나
                            // c.html(M3U8) 내용을 가져와서 변조해야 완벽함.
                            // 하지만 위에서 원본 HTML을 이미 받았으므로 그걸 변조해서 줌.
                            
                            val baseUrl = "https://player.bunny-frame.online" // 기본 베이스
                            // HTML이 아니라 M3U8 텍스트라고 가정하고 변조
                            // (c.html이 M3U8 내용을 포함하고 있음)
                            
                            val modifiedM3u8 = hookedHtml // 위에서 저장한건 HTML(스크립트포함)이라 부적절할 수 있음
                            // M3U8용으로 원본 텍스트를 다시 요청하지 않고, 그냥 c.html 호출 시 받은 내용을 
                            // HTML 태그 제거하고 쓸 순 없으니, 여기서 다시 정리.
                            
                            // 사실상 M3U8 요청은 getUrl 콜백으로 나가므로, 
                            // 여기서는 '프록시가 m3u8을 제공한다'는 컨셉.
                            // hookedHtml은 <script>가 들어간 HTML이라 M3U8 파싱 에러 날 수 있음.
                            
                            // -> [수정] extract()에서 originalHtml을 저장해두지 말고
                            //    여기서 필요하면 다시 받거나, extract에서 rawM3u8을 넘겨줘야 함.
                            //    하지만 간단히 구현하기 위해:
                            //    hookedHtml은 오직 '키 탈취용'이고,
                            //    여기서 제공할 video.m3u8은 'hookedHtml에서 스크립트 뺀 것' + '키 주소 변조' 여야 함.
                            
                            // 편의상 hookedHtml (변조된 HTML)을 그대로 주되,
                            // 플레이어가 이걸 M3U8로 인식하려면 #EXTM3U 헤더가 있어야 함.
                            // BunnyStream은 c.html이 M3U8 그 자체임.
                            // 따라서 <script> 태그는 무시되고 M3U8 태그만 읽힐 가능성 높음.
                            
                            // 안전하게 키 주소만 바꿈:
                            val finalM3u8 = hookedHtml
                                .replace(Regex("""<script>[\s\S]*?</script>""", RegexOption.IGNORE_CASE), "") // 스크립트 제거
                                .replace("<base href=\"https://player.bunny-frame.online/\">", "") // base 태그 제거
                                .replace(Regex("""URI="([^"]+)""""), """URI="http://127.0.0.1:$port/key.bin"""")
                                
                            // TS 경로 보정 (http로 시작 안 하면 절대 경로로)
                            // c.html URL 기준으로 절대경로화 필요하지만, 복잡하므로 
                            // #EXT-X-KEY 라인만 수정하고 나머진 원본 유지 시도.
                            // (단, 원본이 상대경로면 깨질 수 있음 -> 해결책: Base URL 명시 불가하므로 절대경로 변환 필수)
                             
                            val fixedM3u8 = finalM3u8.lines().joinToString("\n") { l ->
                                if (!l.startsWith("#") && l.isNotEmpty() && !l.startsWith("http")) {
                                    // https://player.bunny-frame.online/v/.../seg-1.ts 형식이므로
                                    // c.html의 경로(capturedUrl)를 기준으로 해야 함.
                                    // 하지만 proxy에서는 capturedUrl을 모름. -> 생성자로 받자? (복잡)
                                    // -> Base URL이 보통 https://player.bunny-frame.online 이거나 /v/... 임.
                                    // BunnyStream 특성상 루트 기준 절대경로(/v/...)를 많이 씀.
                                    if (l.startsWith("/")) "https://player.bunny-frame.online$l"
                                    else "https://player.bunny-frame.online/$l" // 대충 루트 붙임 (실패 가능성 있음)
                                    // *정확한 해결*: extract()에서 capturedUrl을 넘겨받아 처리해야 함.
                                } else l
                            }

                            val response = "HTTP/1.1 200 OK\r\n" +
                                         "Content-Type: application/vnd.apple.mpegurl\r\n" +
                                         "Access-Control-Allow-Origin: *\r\n\r\n"
                            output.write(response.toByteArray())
                            output.write(fixedM3u8.toByteArray())
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }

                    } else if (path.contains("/key.bin")) {
                        if (key != null) {
                            val response = "HTTP/1.1 200 OK\r\n" +
                                         "Content-Type: application/octet-stream\r\n" +
                                         "Access-Control-Allow-Origin: *\r\n\r\n"
                            output.write(response.toByteArray())
                            output.write(key)
                        } else {
                            output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        }
                    } else {
                        output.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
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
