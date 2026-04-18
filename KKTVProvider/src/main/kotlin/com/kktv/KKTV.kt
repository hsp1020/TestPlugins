// v14.4 - window.top === window.self (access-denied) 방어 로직 무력화 패치 완료
package com.KingkongTv

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
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

    private val portalUrl = "https://kingkongtv.org"
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

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

        // Cloudstream의 URL 훼손을 방지하기 위해 가짜 스킴(kktv://) 적용
        val dataStr = "kktv://${streamId}|$encodedTitle|$encodedPoster|$encodedRef"

        return newMovieSearchResponse(title, dataStr, TvType.Others) { 
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val parts = url.split("|")
        if (parts.size < 4) return null

        val title = URLDecoder.decode(parts[1], "UTF-8")
        val poster = URLDecoder.decode(parts[2], "UTF-8")
        
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
        val parts = data.split("|")
        if (parts.size < 4) return false

        // 안전한 스트림 ID 추출
        val rawIdPart = parts[0].substringAfterLast("/")
        val streamId = Regex("""\d+""").find(rawIdPart)?.value ?: return false
        val parentReferer = URLDecoder.decode(parts[3], "UTF-8")
        
        val playerIframeUrl = "https://wdbroad.com/broadcast/$streamId/kktv"
        println("DEBUG[KKTV v14.4] 웹뷰 스니핑 시작 - 대상 URL: $playerIframeUrl")

        val hookResult = suspendCancellableCoroutine<Pair<String, Map<String, String>>?> { cont ->
            val handler = Handler(Looper.getMainLooper())
            handler.post {
                try {
                    val context = (AcraApplication.context ?: app) as Context
                    val webView = WebView(context)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = DESKTOP_UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    val timeout = Runnable {
                        if (cont.isActive) {
                            println("DEBUG [KKTV v14.4] 15초 타임아웃 발생")
                            try { webView.destroy() } catch (e: Exception) {}
                            cont.resume(null)
                        }
                    }
                    handler.postDelayed(timeout, 15000)

                    var isFound = false

                    webView.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            // 스트리밍 도메인이나 m3u8, chunklist 감지
                            if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8") || reqUrl.contains("chunklist"))) {
                                isFound = true
                                println("DEBUG [KKTV v14.4] 🎯 M3U8 포착: $reqUrl")
                                handler.removeCallbacks(timeout)
                                
                                if (cont.isActive) {
                                    cont.resume(Pair(reqUrl, request?.requestHeaders ?: emptyMap()))
                                }
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    // 쿠키 세팅 (필요시)
                    val cookie = CookieManager.getInstance().getCookie(parentReferer)
                    if (!cookie.isNullOrEmpty()) {
                        CookieManager.getInstance().setCookie("wdbroad.com", cookie)
                    }

                    // ★ 핵심 패치: access-denied 방지를 위해 가상의 Iframe 껍데기를 만들어 로드합니다.
                    // 이렇게 하면 웹뷰 안에서 window.top !== window.self 가 성립하여 차단을 통과합니다.
                    val wrapperHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta name="referrer" content="no-referrer-when-downgrade"></head>
                        <body style="margin:0;padding:0;background:#000;">
                            <iframe src="$playerIframeUrl" style="width:100%;height:100%;border:none;" allow="autoplay; fullscreen"></iframe>
                        </body>
                        </html>
                    """.trimIndent()

                    // loadDataWithBaseURL을 통해 부모(본진)의 주소를 바탕으로 가짜 HTML을 실행합니다.
                    webView.loadDataWithBaseURL(parentReferer, wrapperHtml, "text/html", "UTF-8", null)
                    
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                }
            }
        }

        if (hookResult != null) {
            val (finalUrl, reqHeaders) = hookResult
            val headers = reqHeaders.toMutableMap()
            
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }
            
            // 미디어 서버 인증을 위해 플레이어가 구동되는 wdbroad.com을 레퍼러로 세팅
            headers["Referer"] = "https://wdbroad.com/"
            headers["Origin"] = "https://wdbroad.com"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.headers = headers
                }
            )
            return true
        }
        return false
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
