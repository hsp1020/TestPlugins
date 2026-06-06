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
     * [재생 링크 추출 전용 극효율 쌩웹뷰]
     * 하드웨어 가속 강제 해제, 이미지/광고 도메인 차단, 팝업 리다이렉트 원천 봉쇄가 적용된 최적화 엔진
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getDoc(url: String): Document {
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
                            Log.d(TAG, "[RawWebView] 사용 완료된 추출기 웹뷰 소멸 완료")
                        } catch (e: Exception) { }
                    }
                }

                class HtmlExtractor {
                    @JavascriptInterface
                    fun processHTML(html: String) {
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resume(Jsoup.parse(html, url))
                        }
                    }
                }

                // [하드웨어 최적화] 하드웨어 가속 해제하여 GPU/CPU 점유 최소화 [1.1, 1.3]
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = DESKTOP_UA
                    loadsImagesAutomatically = false // 미디어 로드 전 단계이므로 불필요한 이미지 원천 거절 [1.1, 1.3]
                    mediaPlaybackRequiresUserGesture = true
                }

                webView.addJavascriptInterface(HtmlExtractor(), "HTMLOUT")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isResumed) return
                        view?.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML);")
                    }

                    // [리다이렉트 원천 차단 최적화] 광고 서버로의 화면 강제 이동 및 팝업 생성을 철저하게 막아 렉 예방
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val requestUrl = request?.url?.toString() ?: return false
                        if (request.isForMainFrame && !requestUrl.contains("anilife.live") && !requestUrl.contains("about:blank")) {
                            Log.d(TAG, "[RawWebView] [광고 팝업 우회 방어] 강제 리다이렉트 차단: $requestUrl")
                            return true // 차단 적용
                        }
                        return false
                    }

                    // [데이터/트래픽 다이어트 핵심 최적화] 불필요한 미디어 이진 파일 및 광고 네트워크 패킷 드롭 [1.1, 1.2, 1.3]
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
                            // 빈 응답 객체를 반환하여 실제 통신 전송 및 다운로드를 전면 취소 [1.1]
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

                webView.loadUrl(url)

                // 30초 대기 안전 강제 탈출 장치
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
        val cleanHeaders = mapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://anilife.live/",
            "Origin" to "https://anilife.live",
            "Accept" to "*/*"
        )

        try {
            var finalM3u8 = ""
            // [최적화 우회 적용] 차단 및 우회 챌린지가 존재하는 playerUrl 페이지를 극효율 쌩웹뷰 모듈로 우회 획득 [1.1]
            val playerDoc = getDoc(playerUrl)
            val playerHtml = playerDoc.outerHtml()

            val aldataRegex = Regex("""_aldata\s*=\s*['"]([^'"]+)['"]""")
            val match = aldataRegex.find(playerHtml)
            
            if (match != null) {
                val b64 = match.groupValues[1]
                val decoded = String(Base64.decode(b64, Base64.DEFAULT))
                val vid1080Regex = Regex(""""vid_url_1080"\s*:\s*"([^"]+)"""")
                val vid720Regex = Regex(""""vid_url_720"\s*:\s*"([^"]+)"""")
                
                var apiUrl = vid1080Regex.find(decoded)?.groupValues?.get(1)
                if (apiUrl.isNullOrBlank() || apiUrl == "none") {
                    apiUrl = vid720Regex.find(decoded)?.groupValues?.get(1)
                }
                
                apiUrl = apiUrl?.replace("\\/", "/")
                
                if (!apiUrl.isNullOrBlank() && apiUrl != "none") {
                    val fullApiUrl = if (apiUrl.startsWith("http")) apiUrl else "https://$apiUrl"
                    val apiRes = app.get(fullApiUrl, headers = cleanHeaders).text
                    val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
                    val extractedUrl = urlRegex.find(apiRes)?.groupValues?.get(1)
                    
                    if (!extractedUrl.isNullOrBlank()) {
                        finalM3u8 = extractedUrl.replace("\\/", "/")
                    }
                }
            }

            if (finalM3u8.isBlank()) return false

            var m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
            var baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }

            if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                val subUrlLine = m3u8Content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                if (subUrlLine != null) {
                    finalM3u8 = resolveUrl(baseUri, finalM3u8, subUrlLine)
                    m3u8Content = app.get(finalM3u8, headers = cleanHeaders).text
                    baseUri = try { URI(finalM3u8) } catch (e: Exception) { null }
                }
            }

            val sb = StringBuilder()
            for (lineText in m3u8Content.lines()) {
                val trimmed = lineText.trim()
                if (trimmed.isEmpty()) continue
                
                if (trimmed.startsWith("#EXT-X-KEY")) {
                    continue
                } else if (trimmed.startsWith("#")) {
                    sb.append(trimmed).append("\n")
                } else {
                    val absUrl = resolveUrl(baseUri, finalM3u8, trimmed)
                    sb.append(absUrl).append("\n")
                }
            }

            val processedPlaylist = sb.toString()

            synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }
            val proxy = ProxyWebServer().apply { 
                updatePlaylist(processedPlaylist)
                start() 
            }
            currentProxyServer = proxy

            val proxyUrl = "http://127.0.0.1:${proxy.port}/$videoId/playlist.m3u8"
            
            callback(newExtractorLink(name, name, proxyUrl, ExtractorLinkType.M3U8) {
                this.referer = "https://anilife.live/"
                this.headers = cleanHeaders
            })
            
            return true

        } catch (e: Exception) {
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
