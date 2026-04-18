// v9.0 - 403 차단 완벽 해결: 모바일 전용 플레이어 동적 추적 및 합법적 M3U8 토큰 가로채기 로직 적용
package com.KingkongTv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            val home = sortedRows.mapNotNull { row ->
                row.toSearchResult(realUrl)
            }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        var title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        if (this.selectFirst("td.subject")?.hasClass("bold") == true) {
            title = "🔥 $title"
        }

        val thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        val thumbUrl = if (thumbUrlRaw != null) {
            val fixedUrl = fixUrl(refererUrl, thumbUrlRaw)
            val separator = if (fixedUrl.contains("?")) "&" else "?"
            "$fixedUrl${separator}t=${System.currentTimeMillis()}"
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
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        return newMovieLoadResponse(title, url, TvType.Others, url) {
            this.posterUrl = poster
            this.plot = "실시간 중계" 
        }
    }

    // 모바일 웹뷰를 백그라운드에 띄워 재생기가 네트워크로 요청하는 실제 M3U8을 낚아채는 가로채기 함수
    private suspend fun getStreamUrlFromWebView(url: String, referer: String): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null

        cont.invokeOnCancellation {
            handler.post {
                try { webView?.destroy(); webView = null } catch (e: Exception) {}
            }
        }

        handler.post {
            try {
                val context = AcraApplication.context ?: return@post
                webView = WebView(context)
                
                val iosUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
                
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = iosUserAgent
                    mediaPlaybackRequiresUserGesture = false // 동영상 자동 재생 허용 (M3U8 호출 유도)
                }

                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("KKTV_DEBUG [WebView] 15초 타임아웃 도달. 가로채기 실패.")
                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 15000)

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        // 억지로 치환한 가짜가 아닌, 서버가 뱉어낸 진짜 합법적 m3u8 또는 flv 요청을 포착
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".flv")) {
                            println("KKTV_DEBUG [WebView] 합법적인 스트리밍 원본 URL 캡처 완료: $reqUrl")
                            if (cont.isActive) {
                                handler.removeCallbacks(timeoutRunnable)
                                handler.post {
                                    try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                    cont.resume(reqUrl)
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                println("KKTV_DEBUG [WebView] 모바일 플레이어 로딩 시작: $url")
                webView?.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                println("KKTV_DEBUG [WebView] 초기화 중 에러: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
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
        val rawRef = uri.getQueryParameter("ref")
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/kktv/index.php"
        
        val rawTitle = uri.getQueryParameter("title") ?: ""
        val cleanTitle = rawTitle.replace("🔥 ", "")
        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
        
        val iosUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

        println("KKTV_DEBUG [Phase 1] 메인 페이지에서 모바일 플레이어(Iframe) 원본 경로 탐색 시작")
        val mainChannelUrl = "https://kktv.speed10-1.com/kktv/index.php"
        val mainRes = try { app.get(mainChannelUrl, headers = mapOf("User-Agent" to iosUserAgent)).text } catch (e: Exception) { "" }
        
        // 아이폰 UA로 메인 페이지 접속 시 할당되는 모바일 전용 iframe 경로 추출
        val mobileIframeMatch = Regex("""<iframe[^>]+id=['"]broadcastFrame['"][^>]+src=['"]([^'"]+)['"]""").find(mainRes)
            ?: Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""").find(mainRes)

        val mobilePlayerUrl = if (mobileIframeMatch != null) {
            val rawSrc = mobileIframeMatch.groupValues[1]
            val baseUrl = fixUrl(mainChannelUrl, rawSrc).substringBefore("?")
            "$baseUrl?ch=$streamId&title=$encodedTitle&start=on&num=1"
        } else {
            println("KKTV_DEBUG [Phase 1] 모바일 Iframe 탐색 실패. 기본 /m/ 경로를 추정하여 적용합니다.")
            "https://kktv.speed10-1.com/kktv/theme/kktv/pages/m/m_view.php?ch=$streamId&title=$encodedTitle&start=on&num=1"
        }
        println("KKTV_DEBUG [Phase 2] 최종 조립된 모바일 플레이어 URL: $mobilePlayerUrl")

        var interceptedUrl: String? = null
        
        // 1. HTML 파싱 선 시도 (WebView를 띄우지 않아도 되면 속도가 훨씬 빠르므로)
        try {
            val mobileResponse = app.get(mobilePlayerUrl, headers = mapOf("User-Agent" to iosUserAgent, "Referer" to refererUrl)).text
            interceptedUrl = Regex("""['"](http[^'"]+\.(?:m3u8|flv)[^'"]*)['"]""").find(mobileResponse)?.groupValues?.get(1)
            if (interceptedUrl != null) println("KKTV_DEBUG [Phase 3] 모바일 HTML 파싱을 통해 추출 성공: $interceptedUrl")
        } catch (e: Exception) {
            println("KKTV_DEBUG [Phase 3] 모바일 HTML 파싱 실패: ${e.message}")
        }

        // 2. HTML 파싱으로 못 찾았을 경우(난독화 등), WebView 백그라운드 구동으로 동적 추출
        if (interceptedUrl == null) {
            println("KKTV_DEBUG [Phase 4] HTML 파싱 실패. 모바일 WebView 렌더링 및 네트워크 가로채기(Intercept) 시작")
            interceptedUrl = getStreamUrlFromWebView(mobilePlayerUrl, refererUrl)
        }

        // 3. ExtractorLink 반환 (403을 유발하던 강제 확장자 치환 폴백 코드는 전부 제거)
        if (interceptedUrl != null) {
            val type = if (interceptedUrl.contains(".flv")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            
            val finalUrl = if (interceptedUrl.contains("play.ogtv3.com") && interceptedUrl.startsWith("https://")) {
                interceptedUrl.replaceFirst("https://", "http://")
            } else {
                interceptedUrl
            }

            println("KKTV_DEBUG [Phase 5] 클라우드스트림 플레이어에 최종 전달되는 URL: $finalUrl")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (Mobile 우회)",
                    url = finalUrl,
                    type = type
                ) {
                    this.referer = "https://kktv.speed10-1.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to "https://kktv.speed10-1.com",
                        "Referer" to "https://kktv.speed10-1.com/",
                        "User-Agent" to iosUserAgent
                    )
                }
            )
            return true
        } else {
            println("KKTV_DEBUG [Error] 모든 모바일 우회 및 가로채기 방식 실패.")
            return false
        }
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try {
            val baseUri = java.net.URI(baseUrl)
            val resolved = baseUri.resolve(relativeUrl)
            resolved.toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}
