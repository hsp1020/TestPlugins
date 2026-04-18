// v13.0 - load_video 함수가 로드될 때까지 반복 체크(Polling)하여 실행 보장
package com.KingkongTv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.*
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
            
            if (iframeSrc.isNullOrBlank()) iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            if (iframeSrc!!.startsWith("//")) iframeSrc = "https:$iframeSrc"

            val realUrl = fixUrl(url, iframeSrc!!)
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
            jsCall = onClickAttr.trim()
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

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { this.posterUrl = thumbUrl }
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

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val uri = Uri.parse(data)
        val refererUrl = URLDecoder.decode(uri.getQueryParameter("ref") ?: "", "UTF-8")
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

    suspend fun extract(pageUrl: String, jsCall: String, callback: (ExtractorLink) -> Unit): Boolean {
        val hookResult = runWebViewHook(pageUrl, jsCall)
        if (hookResult != null) {
            val headers = hookResult.headers.toMutableMap()
            headers["User-Agent"] = DESKTOP_UA
            val cookie = CookieManager.getInstance().getCookie(hookResult.url)
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            callback.invoke(newExtractorLink(name, name, hookResult.url, type = ExtractorLinkType.M3U8) { this.headers = headers })
            return true
        }
        return false
    }

    private suspend fun runWebViewHook(pageUrl: String, jsCall: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            try {
                val context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                webView.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(cm: ConsoleMessage?): Boolean {
                        println("DEBUG [KKTV v13.0] 💻 [콘솔]: ${cm?.message()}")
                        return true
                    }
                }

                val timeout = Runnable { if (cont.isActive) { cont.resume(null); try { webView.destroy() } catch (e: Exception) {} } }
                handler.postDelayed(timeout, 20000)

                var isFound = false
                var isInjected = false

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (!isInjected && url == pageUrl) {
                            isInjected = true
                            // [수정 포인트 v13.0] 함수가 로드될 때까지 0.5초마다 체크하는 Polling 로직 주입
                            view?.evaluateJavascript("""
                                (function() {
                                    var checkCount = 0;
                                    var interval = setInterval(function() {
                                        checkCount++;
                                        if (typeof load_video === 'function') {
                                            console.log('🎯 load_video 함수 감지 성공! 실행합니다.');
                                            $jsCall;
                                            clearInterval(interval);
                                        } else if (checkCount > 20) {
                                            console.log('⏳ 함수 로딩 실패 타임아웃');
                                            clearInterval(interval);
                                        }
                                    }, 500);
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (!isFound && (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8"))) {
                            isFound = true
                            handler.removeCallbacks(timeout)
                            if (cont.isActive) cont.resume(InterceptResult(reqUrl, request?.requestHeaders ?: emptyMap()))
                            view?.post { try { webView.destroy() } catch (e: Exception) {} }
                        }
                        return null
                    }
                }
                webView.loadUrl(pageUrl, mapOf("Referer" to pageUrl))
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }
}
