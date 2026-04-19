// v14.6 - Ghost WebView 완벽 제거 (오디오 겹침 및 백그라운드 재생 버그 해결) & 종목별 커스텀 썸네일 아이콘 적용
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

        // [v14.6 추가] 종목별 커스텀 썸네일 아이콘 매핑 로직
        val sportCategories = listOf("soccer", "baseball", "basketball", "volleyball", "hockey", "tennis", "lol", "football", "mma", "tv")
        var customCategoryIcon: String? = null
        
        // 1. onclick 속성에서 카테고리명(첫 번째 인자) 추출 시도
        if (onClickAttr != null) {
            val cateMatch = Regex("""load_video\('([^']+)'""").find(onClickAttr)
            if (cateMatch != null) {
                val category = cateMatch.groupValues[1].lowercase()
                if (sportCategories.contains(category)) {
                    customCategoryIcon = "/TV/kktv/img/${category}_icon.png"
                    println("DEBUG [KKTV v14.6] onclick 1번째 인자에서 카테고리 추출 성공: ${category}")
                }
            }
        }
        
        // 2. 기존 thumbUrlRaw 문자열에서 종목 키워드 유추 (대비책)
        if (customCategoryIcon == null && thumbUrlRaw != null) {
            val lowerThumb = thumbUrlRaw!!.lowercase()
            for (sport in sportCategories) {
                if (lowerThumb.contains(sport)) {
                    customCategoryIcon = "/TV/kktv/img/${sport}_icon.png"
                    println("DEBUG [KKTV v14.6] 기존 썸네일 URL에서 카테고리 추출 성공: ${sport}")
                    break
                }
            }
        }
        
        // 사용자가 요청한 커스텀 아이콘 경로가 식별되었다면 최우선으로 교체 (기존 로직 제거 방지 원칙 준수)
        val finalThumbUrlRaw = customCategoryIcon ?: thumbUrlRaw
        println("DEBUG [KKTV v14.6] 최종 썸네일 적용 전 Raw Data: $finalThumbUrlRaw")

        val thumbUrl = if (finalThumbUrlRaw != null) {
            val fixed = fixUrl(refererUrl, finalThumbUrlRaw)
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

        val rawIdPart = parts[0].substringAfterLast("/")
        val streamId = Regex("""\d+""").find(rawIdPart)?.value ?: return false
        val parentReferer = URLDecoder.decode(parts[3], "UTF-8")
        
        val playerIframeUrl = "https://wdbroad.com/broadcast/$streamId/kktv"
        println("DEBUG [KKTV v14.6] 웹뷰 스니핑 시작 - 대상 URL: $playerIframeUrl")

        val hookResult = suspendCancellableCoroutine<Pair<String, Map<String, String>>?> { cont ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null

            // ★ 고스트 웹뷰 파괴를 위한 강력한 클린업 로직
            val cleanup = Runnable {
                handler.post {
                    try {
                        webView?.stopLoading()
                        webView?.loadUrl("about:blank") // 현재 페이지 로드를 날려버림 (오디오 즉시 중단)
                        webView?.clearHistory()
                        webView?.removeAllViews()
                        webView?.destroy()
                        webView = null
                        println("DEBUG [KKTV v14.6] 웹뷰 메모리 클린업 완료 (오디오 겹침 방지)")
                    } catch (e: Exception) {}
                }
            }

            val timeout = Runnable {
                if (cont.isActive) {
                    println("DEBUG [KKTV v14.6] 15초 타임아웃 발생 - m3u8 포착 실패")
                    cont.resume(null)
                }
                cleanup.run()
            }
            handler.postDelayed(timeout, 15000)

            // 사용자가 영상 로딩 중에 뒤로가기를 눌러 취소했을 때 확실히 죽임
            cont.invokeOnCancellation {
                handler.removeCallbacks(timeout)
                cleanup.run()
            }

            handler.post {
                try {
                    val context = (AcraApplication.context ?: app) as Context
                    webView = WebView(context)
                    
                    webView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = DESKTOP_UA
                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    }

                    var isFound = false

                    webView?.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8") || reqUrl.contains("chunklist"))) {
                                isFound = true
                                println("DEBUG [KKTV v14.6] 🎯 M3U8 포착: $reqUrl")
                                handler.removeCallbacks(timeout)
                                
                                if (cont.isActive) {
                                    cont.resume(Pair(reqUrl, request?.requestHeaders ?: emptyMap()))
                                }
                                
                                // M3U8 주소를 넘기자마자 백그라운드 웹뷰를 즉시 폭파시킴
                                cleanup.run()
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }

                    val cookie = CookieManager.getInstance().getCookie(parentReferer)
                    if (!cookie.isNullOrEmpty()) {
                        CookieManager.getInstance().setCookie("wdbroad.com", cookie)
                    }

                    val wrapperHtml = """
                        <!DOCTYPE html>
                        <html>
                        <head><meta name="referrer" content="no-referrer-when-downgrade"></head>
                        <body style="margin:0;padding:0;background:#000;">
                            <iframe src="$playerIframeUrl" style="width:100%;height:100%;border:none;" allow="autoplay; fullscreen"></iframe>
                        </body>
                        </html>
                    """.trimIndent()

                    webView?.loadDataWithBaseURL(parentReferer, wrapperHtml, "text/html", "UTF-8", null)
                    
                } catch (e: Exception) {
                    if (cont.isActive) cont.resume(null)
                    cleanup.run()
                }
            }
        }

        if (hookResult != null) {
            val (finalUrl, reqHeaders) = hookResult
            val headers = reqHeaders.toMutableMap()
            
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }
            
            // 미디어 서버 인증을 위해 플레이어가 구동되는 wdbroad.com을 레퍼러로 강제 세팅
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
