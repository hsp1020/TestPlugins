// v10.0 - 사용자님의 Kotbc 플러그인 아키텍처(정답지)를 완벽히 이식한 전면 수정본
package com.KingkongTv

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
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

    private val portalUrl = "https://kingkongtv.org"

    // 단일 메인 페이지 유지
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
        
        // Kotbc 스타일로 분리된 Extractor 호출
        val playerPageUrl = "https://wdbroad.com/broadcast/view.php?stream=$streamId"
        return KKTvExtractor().extract(playerPageUrl, refererUrl, callback)
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}

// [정답지 이식] KotbcExtractor의 완벽한 순정 웹뷰 훅 아키텍처
class KKTvExtractor : ExtractorApi() {
    override val name = "KKTV"
    override val mainUrl = "https://wdbroad.com"
    override val requiresReferer = true

    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    data class InterceptResult(val url: String, val headers: Map<String, String>)

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        println("[KKTV v10.0] WebView 훅 실행 준비: $url")
        val safeReferer = referer ?: "$mainUrl/"
        
        val hookResult = runWebViewHook(url, safeReferer)
        
        if (hookResult != null) {
            val finalUrl = hookResult.url
            println("[KKTV v10.0] WebView 캡처 성공: $finalUrl")
            
            val headers = hookResult.headers.toMutableMap()
            
            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = DESKTOP_UA
            }

            // 웹뷰에서 생성된 쿠키(인증 토큰 등) 추출 및 병합
            val cookie = CookieManager.getInstance().getCookie(finalUrl)
            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
                println("[KKTV v10.0] 쿠키 셋팅 완료: $cookie")
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
            println("[KKTV v10.0] 최종 ExtractorLink 전달 완료")
            return true
        } else {
            println("[KKTV v10.0] WebView 캡처 실패 또는 타임아웃")
            return false
        }
    }

    private suspend fun runWebViewHook(url: String, referer: String) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        
        handler.post {
            try {
                // 플러그인 전역 Context (Kotbc 방식)
                val context: Context = (AcraApplication.context ?: app) as Context
                val webView = WebView(context)
                
                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        println("[KKTV v10.0] WebView Timeout (15초 초과)")
                        try { webView.destroy() } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        
                        // 봇 차단을 피하기 위해 JS 주입 없이 순수하게 지나가는 패킷만 감시합니다.
                        // 사용자님이 브라우저에서 직접 확인하셨던 wdbroadcast.com 포트 8443 또는 playlist.m3u8을 타겟으로 잡습니다.
                        if (reqUrl.contains("wdbroadcast.com") || reqUrl.contains(".m3u8") || reqUrl.contains("playlist")) {
                            
                            println("[KKTV v10.0] Target URL Intercepted: $reqUrl")
                            handler.removeCallbacks(discoveryTimeout)
                            
                            if (cont.isActive) {
                                val requestHeaders = request?.requestHeaders ?: emptyMap()
                                println("[KKTV v10.0] 브라우저 원본 요청 헤더 캡처 성공")
                                
                                view?.post { try { webView.destroy() } catch (e: Exception) {} }
                                cont.resume(InterceptResult(reqUrl, requestHeaders))
                            }
                            return null
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }

                webView.loadUrl(url, mapOf("Referer" to referer))

            } catch (e: Exception) {
                println("[KKTV v10.0] WebView Init Error: ${e.message}")
                if (cont.isActive) cont.resume(null)
            }
        }
    }
}
