// v9.0 - 5가지 디버깅 방법론 전면 통합 (정적 파싱 -> 내장 리졸버 -> 쌩웹뷰 스니핑 -> 프록시 서버)
package com.KingkongTv

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.*
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.*
import org.jsoup.nodes.Element
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Others)

    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv2" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) mainUrl = cleanedUrl
            }
        } catch (e: Exception) {}
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            var iframeSrc = mainDoc.selectFirst("iframe[src*='wdbroad.com/broadcast']")?.attr("src")
                ?: mainDoc.selectFirst("iframe#broadcastFrame")?.attr("src")
                ?: "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"

            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            
            var rows = contentDoc.select("table.board_list tbody tr")
            if (rows.isEmpty()) rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { it.selectFirst("td.subject")?.hasClass("bold") == true }
            val home = sortedRows.mapNotNull { it.toSearchResult(realUrl) }

            return newHomePageResponse(HomePageList(request.name, home, true), false)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null 

        var title: String? = null
        var streamId: String? = null
        var thumbUrlRaw: String? = null

        val subjectTd = this.selectFirst("td.subject")
        val onClickAttr = subjectTd?.attr("onclick")
        if (onClickAttr != null) {
            title = Regex("""load_video\('[^']*',\s*'[^']*',\s*'([^']+)'""").find(onClickAttr)?.groupValues?.get(1)
            streamId = Regex("""load_video\('broadcast/(\d+)'""").find(onClickAttr)?.groupValues?.get(1)
            thumbUrlRaw = this.selectFirst("td.cateimg img")?.attr("src")
        } 
        
        if (title.isNullOrBlank()) {
            val channelDiv = this.selectFirst("div.channel_on")
            title = channelDiv?.attr("data-title")
            streamId = channelDiv?.attr("data-stream")
            thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        }
        
        if (title.isNullOrBlank() || streamId.isNullOrBlank()) return null
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) title = "🔥 $title"

        val thumbUrl = if (thumbUrlRaw != null) {
            val fixed = fixUrl(refererUrl, thumbUrlRaw)
            "$fixed${if (fixed.contains("?")) "&" else "?"}t=${System.currentTimeMillis()}"
        } else ""

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { 
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "Live Stream", "UTF-8")
        val poster = uri.getQueryParameter("poster")?.let { URLDecoder.decode(it, "UTF-8") }

        return newMovieLoadResponse(title, url, TvType.Others, url) { 
            this.posterUrl = poster
            this.plot = "실시간 중계" 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val refererUrl = URLDecoder.decode(uri.getQueryParameter("ref") ?: "", "UTF-8")
        val playerPageUrl = "https://wdbroad.com/broadcast/view.php?stream=$streamId"
        
        println("DEBUG [KingkongTv] v9.0: === 다중 우회망(Fallback Chain) 추출 시작 ===")
        
        // [방법 5: 모듈화] 추출 로직을 KKTvExtractor로 분리하여 실행
        return KKTvExtractor().extractWithFallbackChain(
            url = playerPageUrl, 
            referer = refererUrl, 
            streamId = streamId, 
            callback = callback
        )
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}

// [방법 5: 모듈화 아키텍처 적용]
class KKTvExtractor : ExtractorApi() {
    override val name = "KKTV_MultiEngine"
    override val mainUrl = "https://wdbroad.com"
    override val requiresReferer = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}

    suspend fun extractWithFallbackChain(url: String, referer: String, streamId: String, callback: (ExtractorLink) -> Unit): Boolean {
        var finalUrl: String? = null
        var solvedMethod = "알 수 없음"

        // 1. [방법 4: 정적 데이터 분석 (Anilife 스타일)] - 가장 빠르고 가벼운 방법
        println("DEBUG [KingkongTv] 🔍 [테스트 1단계] 방법 4: 정적 파싱 및 Base64 해독 시도...")
        finalUrl = tryMethod4Static(url, referer)
        if (finalUrl != null) solvedMethod = "방법 4 (정적 분석 및 Base64)"

        // 2. [방법 1: 내장 WebViewResolver (TVWiki 스타일)] - JS 렌더링 후 DOM 캡처
        if (finalUrl == null) {
            println("DEBUG [KingkongTv] 🔍 [테스트 2단계] 방법 1: Cloudstream 내장 WebViewResolver 시도...")
            finalUrl = tryMethod1Resolver(url, referer)
            if (finalUrl != null) solvedMethod = "방법 1 (내장 WebViewResolver)"
        }

        // 3. [방법 2: MainLooper 쌩웹뷰 스니핑 (Kotbc 스타일)] - 모든 네트워크 트래픽 실시간 가로채기
        if (finalUrl == null) {
            println("DEBUG [KingkongTv] 🔍 [테스트 3단계] 방법 2: MainLooper 쌩웹뷰(Raw WebView) 스니핑 시도...")
            finalUrl = tryMethod2RawWebView(url, referer)
            if (finalUrl != null) solvedMethod = "방법 2 (쌩웹뷰 네트워크 스니핑)"
        }

        // 4. 모든 방법 실패 시 구형 백업 주소 사용
        if (finalUrl == null) {
            println("DEBUG [KingkongTv] ❌ 모든 추출 방법 실패. 구형 백업 주소 사용.")
            finalUrl = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            solvedMethod = "백업 하드코딩"
        }

        println("DEBUG [KingkongTv] 🎯 [결과 보고] 해결된 방식: $solvedMethod | 최종 주소: $finalUrl")

        // 5. [방법 3: 로컬 프록시 서버 (Anilife 스타일)] - 추출된 주소의 재생 거부(CORS 등)를 막기 위해 프록시 우회
        println("DEBUG [KingkongTv] 🔍 [테스트 4단계] 방법 3: 로컬 프록시 서버(ProxyWebServer) 구동 시도...")
        val proxyUrl = runProxyServer(finalUrl) ?: finalUrl

        callback.invoke(
            newExtractorLink(
                source = name,
                name = solvedMethod,
                url = proxyUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://wdbroad.com/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to userAgent,
                    "Origin" to "https://wdbroad.com",
                    "Referer" to "https://wdbroad.com/"
                )
            }
        )
        return true
    }

    private suspend fun tryMethod4Static(url: String, referer: String): String? {
        try {
            val response = app.get(url, headers = mapOf("Referer" to referer)).text
            
            // 일반 M3U8 정규식 매칭
            var m3u8 = Regex("""https?://[^"']+\.m3u8[^"']*""").find(response)?.value
            if (m3u8 != null) return m3u8

            // 숨겨진 Base64 문자열 전체 디코딩 검사
            val base64Pattern = Regex("""['"]([a-zA-Z0-9+/=]{40,})['"]""")
            for (match in base64Pattern.findAll(response)) {
                try {
                    val decoded = String(Base64.decode(match.groupValues[1], Base64.DEFAULT), Charsets.UTF_8)
                    if (decoded.contains(".m3u8")) {
                        return Regex("""https?://[^"']+\.m3u8[^"']*""").find(decoded)?.value ?: decoded
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) {}
        return null
    }

    private suspend fun tryMethod1Resolver(url: String, referer: String): String? {
        try {
            val interceptor = com.lagradost.cloudstream3.network.WebViewResolver(
                Regex("""wdbroadcast|playlist|\.m3u8"""), 
                timeout = 10000L
            )
            val response = app.get(url, headers = mapOf("Referer" to referer), interceptor = interceptor)
            val html = response.text
            return Regex("""https?://[^"']+\.m3u8[^"']*""").find(html)?.value
        } catch (e: Exception) {}
        return null
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun tryMethod2RawWebView(url: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val context: Context = (AcraApplication.context ?: app) as Context
                    val webView = WebView(context)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = userAgent
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    var isFound = false
                    val handler = Handler(Looper.getMainLooper())

                    val timeoutRunnable = Runnable {
                        if (cont.isActive) {
                            println("DEBUG [KingkongTv] [방법 2] 타임아웃 발생")
                            cont.resume(null)
                            try { webView.destroy() } catch (e: Exception) {}
                        }
                    }
                    handler.postDelayed(timeoutRunnable, 15000)

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            if (!isFound && reqUrl.contains(".m3u8")) {
                                isFound = true
                                handler.removeCallbacks(timeoutRunnable)
                                if (cont.isActive) cont.resume(reqUrl)
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("""
                                (function() {
                                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; } });
                                    Object.defineProperty(document, 'hidden', { get: function() { return false; } });
                                    var btns = document.querySelectorAll('button, .play-btn, div[class*="play"]');
                                    for(var i=0; i<btns.length; i++) btns[i].click();
                                    var v = document.querySelector('video'); if(v) v.play();
                                })();
                            """.trimIndent(), null)
                        }
                    }
                    webView.loadUrl(url, mapOf("Referer" to referer))
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private suspend fun runProxyServer(m3u8Url: String): String? {
        try {
            val content = app.get(m3u8Url, headers = mapOf("Referer" to "https://wdbroad.com/", "User-Agent" to userAgent)).text
            if (!content.contains("#EXTM3U")) return null

            var baseUri = try { URI(m3u8Url) } catch (e: Exception) { null }
            val sb = StringBuilder()
            
            for (line in content.lines()) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue
                if (trimmed.startsWith("#")) {
                    sb.append(trimmed).append("\n")
                } else {
                    val absUrl = try { baseUri?.resolve(trimmed).toString() } catch (e: Exception) { trimmed }
                    sb.append(absUrl).append("\n")
                }
            }

            synchronized(this) { currentProxyServer?.stop(); currentProxyServer = null }
            val proxy = ProxyWebServer().apply { 
                updatePlaylist(sb.toString())
                start() 
            }
            currentProxyServer = proxy
            
            println("DEBUG [KingkongTv] 🎯 [방법 3: 로컬 프록시 서버] 구축 완료: Port ${proxy.port}")
            return "http://127.0.0.1:${proxy.port}/playlist.m3u8"
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] ❌ [방법 3] 프록시 구축 실패, 원본 링크 반환")
            return null
        }
    }

    class ProxyWebServer {
        var port: Int = 0
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        @Volatile private var playlistData: String = ""

        fun updatePlaylist(data: String) { playlistData = data }

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                CoroutineScope(Dispatchers.IO).launch { 
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

        private fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val line = reader.readLine() ?: return
                
                while (true) { if (reader.readLine().isNullOrEmpty()) break }

                val out = socket.getOutputStream()
                if (line.contains("playlist.m3u8")) {
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
