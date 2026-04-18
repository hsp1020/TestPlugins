// v11.0 - 404 에러 원인 해결: 가짜 URL(view.php) 폐기 및 실제 메인 페이지에서 load_video() 텍스트를 추출하여 직접 실행
package com.KingkongTv

import android.content.Context
import android.net.Uri
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

    override val mainPage = mainPageOf(
        "/live/sportstv2" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v11.0: 포털 사이트 접속 시도 중...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v11.0: 도메인 갱신 -> $cleanedUrl")
                    mainUrl = cleanedUrl
                }
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
        var jsCall = ""

        val subjectTd = this.selectFirst("td.subject")
        val onClickAttr = subjectTd?.attr("onclick")
        
        if (onClickAttr != null) {
            title = Regex("""load_video\('[^']*',\s*'[^']*',\s*'([^']+)'""").find(onClickAttr)?.groupValues?.get(1)
            streamId = Regex("""load_video\('broadcast/(\d+)'""").find(onClickAttr)?.groupValues?.get(1)
            thumbUrlRaw = this.selectFirst("td.cateimg img")?.attr("src")
            
            // [핵심 변경 v11.0] onclick 속성 안에 있는 load_video(...) 함수 구문 전체를 그대로 복사해옵니다.
            val jsMatch = Regex("""load_video\([^)]+\)""").find(onClickAttr)
            if (jsMatch != null) {
                jsCall = jsMatch.value
                println("DEBUG [KingkongTv] v11.0: 추출된 자바스크립트 호출문 -> $jsCall")
            }
        } 
        
        // 구형 버전 대응
        if (title.isNullOrBlank()) {
            val channelDiv = this.selectFirst("div.channel_on")
            title = channelDiv?.attr("data-title")
            streamId = channelDiv?.attr("data-stream")
            thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        }

        // 혹시 load_video를 못 찾았을 경우 최후의 수단으로 백업 생성
        if (jsCall.isEmpty() && streamId != null) {
            jsCall = "load_video('broadcast/$streamId', '1', '${title ?: ""}', '', 'kktv');"
        }
        
        if (title.isNullOrBlank() || streamId.isNullOrBlank()) return null
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) title = "🔥 $title"

        val thumbUrl = if (thumbUrlRaw != null) {
            val fixed = fixUrl(refererUrl, thumbUrlRaw)
            "$fixed${if (fixed.contains("?")) "&" else "?"}t=${System.currentTimeMillis()}"
        } else ""

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8") // 여기가 실제 목록 페이지(live/sportstv2) 주소
        val encodedJsCall = URLEncoder.encode(jsCall, "UTF-8")

        // loadLinks로 전달할 파라미터에 jsCall을 같이 실어 보냅니다.
        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef&jsCall=$encodedJsCall"

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
        
        // 404가 뜨는 가짜 URL은 이제 완전히 버리고, 사용자가 머물던 목록 페이지를 베이스로 씁니다.
        val refererUrl = URLDecoder.decode(uri.getQueryParameter("ref") ?: "$mainUrl/live/sportstv2", "UTF-8")
        val jsCall = URLDecoder.decode(uri.getQueryParameter("jsCall") ?: "", "UTF-8")
        
        return KKTvExtractor().extract(refererUrl, jsCall, callback)
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}

// [모듈화] 쌩웹뷰 전담 Extractor
class KKTvExtractor : ExtractorApi() {
    override val name = "KKTV"
    override val mainUrl = "https://wdbroad.com"
    override val requiresReferer = true

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}

    suspend fun extract(
        pageUrl: String,
        jsCall: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("DEBUG [KKTV v11.0] WebView 훅 시작. 바탕 페이지: $pageUrl | 실행할 JS: $jsCall")
        
        val hookResult = runWebViewHook(pageUrl, jsCall)
        
        if (hookResult != null) {
            val finalUrl = hookResult.url
            println("DEBUG [KKTV v11.0] WebView 캡처 최종 성공: $finalUrl")
            
            val headers = hookResult.headers.toMutableMap()
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }

            val cookie = CookieManager.getInstance().getCookie(finalUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }

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
        } else {
            println("DEBUG [KKTV v11.0] WebView 캡처 실패 또는 타임아웃 발생")
            return false
        }
    }

    private suspend fun runWebViewHook(pageUrl: String, jsCall: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    mediaPlaybackRequiresUserGesture = false 
                    userAgentString = DESKTOP_UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("DEBUG [KKTV v11.0] 15초 타임아웃 초과. M3U8을 찾지 못했습니다.")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                var isFound = false
                var isInjected = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // [핵심 변경 v11.0] 엉뚱한 페이지가 아니라, 킹콩티비의 실제 목록 페이지가 뜨면
                        // 우리가 훔쳐온 load_video() 함수를 자바스크립트로 직접 실행해서 클릭을 완벽하게 재현합니다.
                        if (!isInjected && url == pageUrl && jsCall.isNotEmpty()) {
                            isInjected = true
                            println("DEBUG [KKTV v11.0] 메인 페이지 로드 완료. 사이트 자체 JS 클릭 함수 강제 발동 -> $jsCall")
                            view?.evaluateJavascript("$jsCall;", null)
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // [핵심 변경 v11.0] 사용자님께서 찾아주신 JW Player 도메인 및 추출 주소 패턴 반영
                        if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8"))) {
                            isFound = true
                            println("DEBUG [KKTV v11.0] 🎯 M3U8 통신 포착 완료: $reqUrl")
                            
                            handler.removeCallbacks(discoveryTimeout)
                            
                            if (cont.isActive) {
                                val requestHeaders = request?.requestHeaders ?: emptyMap()
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(InterceptResult(reqUrl, requestHeaders))
                            }
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                // 가짜 view.php 접속을 폐기하고, 진짜 메인 페이지를 로딩합니다.
                webView.loadUrl(pageUrl, mapOf("Referer" to pageUrl))

            } catch (e: Exception) {
                println("DEBUG [KKTV v11.0] WebView 초기화 에러: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
