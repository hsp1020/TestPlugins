// v8.9 - TVWiki 방식의 Headless WebView 가로채기(Intercept) 로직 적용 (play.js 동적 토큰 발급 완벽 우회)
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

    // --- TVWiki 스타일의 백그라운드 WebView 가로채기(Intercept) 엔진 ---
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
                
                // play.js가 iOS로 착각하여 HLS(.m3u8) 주소와 합법적인 토큰을 조립하도록 유도
                val iosUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"
                
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = iosUserAgent
                    mediaPlaybackRequiresUserGesture = false
                }

                val timeoutRunnable = Runnable {
                    if (cont.isActive) {
                        println("KKTV_DEBUG [WebView] Timeout reached (10s).")
                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(timeoutRunnable, 10000)

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        // play.js가 스스로 만들어낸 합법적인 m3u8 또는 flv 요청을 가로챔
                        if (reqUrl.contains(".m3u8") || reqUrl.contains(".flv")) {
                            println("KKTV_DEBUG [WebView] Intercepted valid stream URL: $reqUrl")
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
                println("KKTV_DEBUG [WebView] Loading URL to run play.js: $url")
                webView?.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                println("KKTV_DEBUG [WebView] Exception: ${e.message}")
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
        
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/theme/kktv/pages/pc/pc_view.php?ch=$streamId&title=$encodedTitle&start=on&num=1"
        println("KKTV_DEBUG [Phase 1] Target Player URL: $playerPageUrl")
        
        val iosUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

        // 1. WebView를 통해 play.js 강제 실행 및 주소 가로채기 (가장 확실한 403 에러 우회법)
        val interceptedUrl = getStreamUrlFromWebView(playerPageUrl, refererUrl)

        if (interceptedUrl != null) {
            println("KKTV_DEBUG [Phase 2] WebView Intercept Success: $interceptedUrl")
            val type = if (interceptedUrl.contains(".flv")) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            
            val finalUrl = if (interceptedUrl.contains("play.ogtv3.com") && interceptedUrl.startsWith("https://")) {
                interceptedUrl.replaceFirst("https://", "http://")
            } else {
                interceptedUrl
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (WebView 우회)",
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
        }

        // 2. WebView 가로채기 실패 시 기존 HTTP 파싱 로직으로 폴백 (안전망)
        println("KKTV_DEBUG [Phase 2] WebView Intercept Failed. Fallback to Http Get.")
        val headers = mapOf(
            "User-Agent" to iosUserAgent,
            "Referer" to refererUrl,
            "Origin" to "https://kktv.speed10-1.com"
        )

        try {
            val response = app.get(playerPageUrl, headers = headers).text
            var flvUrl: String? = null
            var m3u8Url: String? = null
            
            val streamMatch = Regex("""['"](http[^'"]+\.(?:sdp|m3u8)[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            if (streamMatch != null) {
                if (streamMatch.contains(".sdp")) {
                    flvUrl = streamMatch.replace(".sdp", ".flv")
                    m3u8Url = streamMatch.replace(".sdp", ".m3u8")
                } else if (streamMatch.contains(".m3u8")) {
                    m3u8Url = streamMatch
                }
            } else {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    val baseHttpUrl = webrtcMatch.replace("webrtc://", "https://")
                    flvUrl = baseHttpUrl.replace(streamId, "$streamId.flv")
                    m3u8Url = baseHttpUrl.replace(streamId, "$streamId.m3u8")
                }
            }

            if (flvUrl == null && m3u8Url == null) {
                flvUrl = "https://cdnnowstream.com/cdnnow/$streamId.flv?site=kktv"
                m3u8Url = "https://cdnnowstream.com/cdnnow/$streamId.m3u8?site=kktv"
            }

            if (flvUrl != null) {
                val finalFlv = if (flvUrl.contains("play.ogtv3.com") && flvUrl.startsWith("https://")) flvUrl.replaceFirst("https://", "http://") else flvUrl
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (FLV - 백업)",
                        url = finalFlv,
                        type = ExtractorLinkType.VIDEO
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
            }

            if (m3u8Url != null) {
                val finalM3u8 = if (m3u8Url.contains("play.ogtv3.com") && m3u8Url.startsWith("https://")) m3u8Url.replaceFirst("https://", "http://") else m3u8Url
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (M3U8 - 백업)",
                        url = finalM3u8,
                        type = ExtractorLinkType.M3U8
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
            }

        } catch (e: Exception) {
            println("KKTV_DEBUG [Error] loadLinks Fallback Exception: ${e.message}")
            e.printStackTrace()
        }

        return true
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
