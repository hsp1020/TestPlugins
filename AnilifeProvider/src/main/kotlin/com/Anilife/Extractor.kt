package com.anilife

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.CookieManager
import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.delay
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.ByteArrayInputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException

class AnilifeProxyExtractor : ExtractorApi() {
    override val name = "Anilife"
    override val mainUrl = "https://api.gcdn.app"
    override val requiresReferer = false

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"
    private val TAG = "[AnilifeExtractor]"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) { }

    fun extractPlayerUrl(html: String, domain: String): String? {
        val patterns = listOf(
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""["']([^"']*h\/live\?p=[^"']+)["']""")
        )
        for (regex in patterns) {
            regex.find(html)?.let {
                var url = it.groupValues[1]
                if (url.contains("h/live") && url.contains("p=")) {
                    if (!url.startsWith("http")) url = if (url.startsWith("/")) "$domain$url" else "$domain/$url"
                    return url.replace("\\/", "/")
                }
            }
        }
        return null
    }

    /**
     * [추출기 하이브리드 통신 브릿지]
     * 쿠키가 있을 때는 OkHttp 수립을 선제 적용해 비디오 주소 추출 속도 극대화
     */
    private suspend fun getDocumentWithFallback(url: String, headers: Map<String, String>): Document {
        val cookieHeader = try {
            CookieManager.getInstance().getCookie("https://anilife.live")
        } catch (e: Exception) {
            null
        }

        if (!cookieHeader.isNullOrEmpty()) {
            try {
                val mergedHeaders = headers.toMutableMap()
                mergedHeaders["Cookie"] = cookieHeader
                
                val response = app.get(url, headers = mergedHeaders)
                if (response.code == 200) {
                    return response.document
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }

        return getDoc(url, headers)
    }

    /**
     * [재생 링크 추출 전용 극효율 쌩웹뷰]
     * resumeWith 및 자바스크립트 리다이렉트 최종 경로 추적 패키지 수립 완료
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getDoc(url: String, headers: Map<String, String>): Document {
        Log.d(TAG, "[RawWebView] 추출용 쌩웹뷰 기동 -> $url")
        val ctx = CloudStreamApp.context ?: throw Exception("Context가 Null입니다.")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(ctx)
                var isResumed = false

                fun cleanup() {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.clearHistory()
                            webView.loadUrl("about:blank") // 백그라운드 악성 자바스크립트 루프 처형
                            webView.destroy() // 메모리 자원 완전 소멸 반환 [1.1, 1.3]
                        } catch (e: Exception) { }
                    }
                }

                class HtmlExtractor {
                    @JavascriptInterface
                    fun processHTML(html: String, finalUrl: String) {
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            // Jsoup.parse에 리다이렉트 완료 주소인 finalUrl을 바인딩하여 resumeWith 리턴 처리
                            continuation.resumeWith(Result.success(Jsoup.parse(html, finalUrl)))
                        }
                    }
                }

                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = DESKTOP_UA
                    loadsImagesAutomatically = false // 이미지 다운로드 전면 차단 [1.1, 1.3]
                    mediaPlaybackRequiresUserGesture = true
                }

                webView.addJavascriptInterface(HtmlExtractor(), "HTMLOUT")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isResumed) return
                        view?.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML, window.location.href);")
                    }

                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val requestUrl = request?.url?.toString() ?: return false
                        if (request.isForMainFrame && !requestUrl.contains("anilife.live") && !requestUrl.contains("about:blank")) {
                            Log.d(TAG, "[RawWebView] [광고 팝업 우회 방어] 강제 리다이렉트 차단: $requestUrl")
                            return true
                        }
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val resourceUrl = request?.url?.toString()?.lowercase() ?: return null
                        val isHeavyOrMalicious = resourceUrl.endsWith(".png") || resourceUrl.endsWith(".jpg") ||
                                                 resourceUrl.endsWith(".jpeg") || resourceUrl.endsWith(".webp") ||
                                                 resourceUrl.endsWith(".gif") || resourceUrl.endsWith(".svg") ||
                                                 resourceUrl.endsWith(".woff") || resourceUrl.endsWith(".woff2") ||
                                                 resourceUrl.endsWith(".ttf") || resourceUrl.endsWith(".css") ||
                                                 resourceUrl.endsWith(".mp4") || resourceUrl.endsWith(".m4a") ||
                                                 resourceUrl.contains("analytics") || resourceUrl.contains("google") ||
                                                 resourceUrl.contains("adsense") || resourceUrl.contains("ads") ||
                                                 resourceUrl.contains("popunder") || resourceUrl.contains("popads") ||
                                                 resourceUrl.contains("exoclick") || resourceUrl.contains("juicyads")

                        if (isHeavyOrMalicious) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        if (failingUrl == url && !isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("접속 실패: $description ($errorCode)")))
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true && !isResumed) {
                            val errorCode = error?.errorCode ?: -1
                            val description = error?.description?.toString() ?: "Unknown"
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("접속 실패: $description ($errorCode)")))
                        }
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("SSL 핸드셰이크 실패: ${error?.toString()}")))
                        }
                    }
                }

                // 동적 레퍼러 헤더 주입 실행 [1.1]
                webView.loadUrl(url, headers)

                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        continuation.resumeWith(Result.failure(Exception("응답 시간 초과 (30초)")))
                    }
                }, 30000)

                continuation.invokeOnCancellation {
                    cleanup()
                }
            }
        }
    }

    suspend fun extractWithProxy(
        m3u8Url: String,
        playerUrl: String,
        referer: String,
        ssid: String?,
        cookies: String,
        targetKeyUrl: String? = null,
        videoId: String = "unknown_id",
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[DEBUG-Extractor] =========================================")
        println("[DEBUG-Extractor] extractWithProxy 실행 개시")
        println("[DEBUG-Extractor] 📥 매개변수 playerUrl: $playerUrl")
        println("[DEBUG-Extractor] 📥 매개변수 videoId: $videoId")

        val cleanHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Accept" to "*/*"
        )

        try {
            var finalM3u8 = ""
            // [최적화 하이브리드 폴백 적용] playerUrl 쌩웹뷰 전송 [1.1]
            println("[DEBUG-Extractor] 하이브리드 브릿지로 playerUrl 수집 호출 시작...")
            val playerDoc = getDocumentWithFallback(playerUrl, cleanHeaders)
            val playerHtml = playerDoc.outerHtml()
            println("[DEBUG-Extractor] 수집 완료된 playerHtml 전체 길이: ${playerHtml.length} 자")

            println("[DEBUG-Extractor] _aldata 매칭 정규식 검사 개시")
            val aldataRegex = Regex("""_aldata\s*=\s*['"]([^'"]+)['"]""")
            val match = aldataRegex.find(playerHtml)
            println("[DEBUG-Extractor] _aldata 정규식 검사 결과 존재 여부: ${match != null}")
            
            if (match != null) {
                val b64 = match.groupValues[1]
                println("[DEBUG-Extractor] 인코딩 b64 데이터 추출 성공 (글자수: ${b64.length}자)")
                
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                println("[DEBUG-Extractor] b64 문자열 디코딩 완수. 디코딩 데이터 크기: ${decoded.length}자")
                
                val vid1080Regex = Regex(""""vid_url_1080"\s*:\s*"([^"]+)"""")
                val vid720Regex = Regex(""""vid_url_720"\s*:\s*"([^"]+)"""")
                
                var apiUrl = vid1080Regex.find(decoded)?.groupValues?.get(1)
                println("[DEBUG-Extractor] vid_url_1080 정규식 매치 결과: $apiUrl")
                
                if (apiUrl.isNullOrBlank() || apiUrl == "none") {
                    apiUrl = vid720Regex.find(decoded)?.groupValues?.get(1)
                    println("[DEBUG-Extractor] vid_url_1080 실패로 인한 vid_url_720 재대조 결과: $apiUrl")
                }
                
                apiUrl = apiUrl?.replace("\\/", "/")
                println("[DEBUG-Extractor] 포맷 정제 완료된 apiUrl: $apiUrl")
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    println("[DEBUG-Extractor] fullApiUrl 최종 API 주소 직접 호출 개시: $fullApiUrl")
                    
                    val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                    println("[DEBUG-Extractor] API 서버 응답 수집 성공. 응답 본문 크기: ${apiRes.length}자, 본문 내용: $apiRes")
                    
                    val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                    val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                    println("[DEBUG-Extractor] apiRes 본문 내 url 주소 정규식 추출 결과: $extractedUrl")
                    
                    if (!extractedUrl.isNullOrBlank()) {
                        finalM3u8 = extractedUrl.replace("\\/", "/")
                        println("[DEBUG-Extractor] 최종 타겟 finalM3u8 주소 도출 성공: $finalM3u8")
                    }
                }
            }

            if (finalM3u8.isBlank()) {
                println("[DEBUG-Extractor] 에러: 비디오 스트리밍 m3u8 주소를 획득하지 못했습니다.")
                return false
            }

            println("[DEBUG-Extractor] finalM3u8 플레이리스트 텍스트 수집 개시 -> $finalM3u8")
            var m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
            println("[DEBUG-Extractor] 수집 완료된 m3u8 텍스트 크기: ${m3u8Content.length}자")
            var baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }

            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                println("[DEBUG-Extractor] 멀티 화질 인덱스(#EXT-X-STREAM-INF) 규격 감지. 하위 화질 플레이리스트 주소 추적 개시")
                val subUrlLine = m3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                println("[DEBUG-Extractor] 탐색된 타겟 하위 m3u8 주소줄: $subUrlLine")
                if (subUrlLine != null) {
                    finalM3u8 = resolveUrl(baseUri, finalM3u8, subUrlLine)
                    println("[DEBUG-Extractor] 절대 경로 조율된 하위 m3u8 주소: $finalM3u8")
                    m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
                    println("[DEBUG-Extractor] 하위 플레이리스트 수집 완료. 텍스트 크기: ${m3u8Content.length}자")
                    baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }
                }
            }

            println("[DEBUG-Extractor] 로컬 가상 프록시 매핑을 위한 리스트 파싱 및 절대경로 재조합 시작...")
            val sb = StringBuilder()
            for (lineText in m3u8Content.lines()) {
                val trimmed = lineText.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    println("[DEBUG-Extractor] 암호화 키 정의 라인(#EXT-X-KEY) 발견. 프록시 흐름 보장을 위해 의도적 필터 탈거")
                    continue
                } else if (trimmed.startsWith("#")) {
                    sb.append(trimmed).append("\n")
                } else {
                    val absUrl = resolveUrl(baseUri, finalM3u8, trimmed)
                    sb.append(absUrl).append("\n")
                }
            }

            val processedPlaylist = sb.toString()
            println("[DEBUG-Extractor] 조율된 로컬 프록시용 데이터 생성 성공 (전체 크기: ${processedPlaylist.length}자)")

            println("[DEBUG-Extractor] 프록시 가상 웹 서버(ProxyWebServer) 구동 준비...")
            synchronized(this) { 
                currentProxyServer?.stop()
                currentProxyServer = null 
                println("[DEBUG-Extractor] 기존 활성화 중이던 프록시 웹 서버 중단 완료")
            }
            
            val proxy = ProxyWebServer().apply { 
                updatePlaylist(processedPlaylist)
                start() 
            }
            currentProxyServer = proxy
            println("[DEBUG-Extractor] 로컬 웹 프록시 서버 정상 구동 (자동 바인딩 포트: ${proxy.port})")

            val proxyUrl = "http://127.0.0.1:${proxy.port}/$videoId/playlist.m3u8"
            println("[DEBUG-Extractor] 최종 앱 프레임워크 콜백 ExtractorLink 발행 주소: $proxyUrl")
            
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = cleanHeaders
            })
            
            println("[DEBUG-Extractor] === extractWithProxy 수립 완료 및 성공 종료 ===")
            return true

        } catch (e: Exception) {
            println("[DEBUG-Extractor] 예외 발생에 따른 스택 트레이스 추적:")
            e.printStackTrace()
            return false
        }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    class ProxyWebServer {
        var port: Int = 0
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlistData: String = ""

        fun updatePlaylist(data: String) {
            playlistData = data
        }

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                CoroutineScope(Dispatchers.IO).launch { 
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val socket = serverSocket!!.accept()
                            launch(Dispatchers.IO) { handleClient(socket) } 
                        } catch (e: Exception) {
                            delay(100L)
                        } 
                    } 
                }
            } catch (e: Exception) { }
        }

        fun stop() { 
            isRunning = false
            try { serverSocket?.close(); serverSocket = null } catch (e: Exception) {} 
        }

        private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return@withContext
                val path = line.split(" ")[1]

                while (true) {
                    val headerLine = reader.readLine()
                    if (headerLine.isNullOrEmpty()) break
                }

                val out = socket.getOutputStream()

                if (path.contains("playlist.m3u8")) {
                    val payload = playlistData.toByteArray(charset("UTF-8"))
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    out.write(payload)
                } else {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                }
                out.flush()
            } catch (e: Exception) {
            } finally {
                try { socket.close() } catch (e: Exception) {}
            }
        }
    }
}
