// v12.0 - install_iframe() 등 후속 함수 잘림 버그 수정 및 WebChromeClient 콘솔 로깅 추가
package com.KingkongTv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
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
            println("DEBUG [KKTV v12.0] 포털 접속 시도...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KKTV v12.0] 도메인 갱신 -> $cleanedUrl")
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
            
            // [핵심 변경 v12.0] 정규식으로 자르지 않고 onclick 안의 코드를 100% 통째로 가져옵니다.
            jsCall = onClickAttr.trim()
            println("DEBUG [KKTV v12.0] 추출된 전체 JS -> $jsCall")
        } 
        
        if (title.isNullOrBlank()) {
            val channelDiv = this.selectFirst("div.channel_on")
            title = channelDiv?.attr("data-title")
            streamId = channelDiv?.attr("data-stream")
            thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        }

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
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8") 
        val encodedJsCall = URLEncoder.encode(jsCall, "UTF-8")

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
        
        val refererUrl = URLDecoder.decode(uri.getQueryParameter("ref") ?: "$mainUrl/live/sportstv2", "UTF-8")
        val jsCall = URLDecoder.decode(uri.getQueryParameter("jsCall") ?: "", "UTF-8")
        
        return KKTvExtractor().extract(refererUrl, jsCall, callback)
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}

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
        println("DEBUG [KKTV v12.0] WebView 훅 시작. 페이지: $pageUrl")
        
        val hookResult = runWebViewHook(pageUrl, jsCall)
        
        if (hookResult != null) {
            val finalUrl = hookResult.url
            println("DEBUG [KKTV v12.0] WebView 캡처 최종 성공: $finalUrl")
            
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
            println("DEBUG [KKTV v12.0] WebView 캡처 실패 또는 타임아웃")
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

                // [추가 v12.0] 웹뷰 내부 콘솔(JS 에러 등)을 로그캣으로 출력하여 Cloudflare 막힘 여부 등 파악
                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        println("DEBUG [KKTV v12.0] 💻 [브라우저 콘솔]: ${consoleMessage?.message()}")
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("DEBUG [KKTV v12.0] ⏳ 15초 타임아웃 초과. [브라우저 콘솔] 에러를 확인하세요.")
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
                        if (!isInjected && url == pageUrl && jsCall.isNotEmpty()) {
                            isInjected = true
                            println("DEBUG [KKTV v12.0] 메인 페이지 로드 완료. 1.5초 대기 후 전체 JS 실행 대기...")
                            
                            // [핵심 변경 v12.0] DOM과 Iframe이 완벽히 준비될 시간을 주고, 에러를 Catch하도록 안전하게 감싸서 실행합니다.
                            view?.postDelayed({
                                println("DEBUG [KKTV v12.0] 전체 JS 강제 실행 발동!")
                                view.evaluateJavascript("""
                                    (function() {
                                        try {
                                            if (typeof load_video !== 'function') {
                                                console.log('에러: load_video 함수가 존재하지 않습니다! (Cloudflare 캡챠 페이지에 막혔을 확률이 높습니다.)');
                                            } else {
                                                $jsCall
                                                console.log('전체 JS 함수 실행 성공!');
                                            }
                                        } catch(e) {
                                            console.log('JS 실행 중 크래시 발생: ' + e);
                                        }
                                    })();
                                """.trimIndent(), null)
                            }, 1500)
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // 디버깅을 위해 HTTP 통신을 모두 로그캣에 출력합니다.
                        if (reqUrl.startsWith("http")) {
                            // 너무 많은 이미지 요청이 로그를 도배하지 않도록 가볍게 필터링
                            if (!reqUrl.contains(".png") && !reqUrl.contains(".jpg")) {
                                println("DEBUG [KKTV v12.0] 🌐 [네트워크]: $reqUrl")
                            }
                        }
                        
                        if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8") || reqUrl.contains("playlist"))) {
                            isFound = true
                            println("DEBUG [KKTV v12.0] 🎯 M3U8 통신 포착 완료: $reqUrl")
                            
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

                webView.loadUrl(pageUrl, mapOf("Referer" to pageUrl))

            } catch (e: Exception) {
                println("DEBUG [KKTV v12.0] WebView 초기화 에러: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
