// v8.6 - 안드로이드 순정 쌩웹뷰(Raw WebView) 네트워크 스니핑 및 자동 클릭 도입
package com.KingkongTv

import android.annotation.SuppressLint
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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
        "/live/sportstv2" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v8.6: 포털 사이트 접속 시도 중...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v8.6: 도메인 변경 감지 -> $cleanedUrl")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.6: 도메인 업데이트 에러 - ${e.message}")
        }
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
            println("DEBUG [KingkongTv] v8.6: 메인 페이지 로드 실패 - ${e.message}")
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
        println("DEBUG [KingkongTv] v8.6: 쌩웹뷰 스니핑 시작 - $playerPageUrl")

        // 1. 순정 WebView 기반 네트워크 스니핑 실행
        var m3u8Url = getM3u8FromRawWebView(playerPageUrl, refererUrl)

        // 2. 스니핑 실패 시 최후의 백업 정규식 처리
        if (m3u8Url == null) {
            println("DEBUG [KingkongTv] v8.6: WebView 스니핑 실패. 정규식 백업 시도")
            try {
                val response = app.get(playerPageUrl, referer = refererUrl).text
                m3u8Url = Regex("""https?://wdbroadcast\.com:8443/[^"']+\.m3u8[^"']*""").find(response)?.value
                if (m3u8Url == null) m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            } catch (e: Exception) { }
        }

        if (m3u8Url != null) {
            println("DEBUG [KingkongTv] v8.6: 최종 추출된 영상 주소 - $m3u8Url")
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
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
        }

        return true
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getM3u8FromRawWebView(url: String, referer: String): String? {
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { cont ->
                try {
                    val context = KingkongTvPlugin.pluginContext
                    if (context == null) {
                        println("DEBUG [KingkongTv] v8.6: Context가 Null입니다. KKTVPlugin.kt 업데이트 여부를 확인하세요.")
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val webView = WebView(context)
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false // 동영상 자동 재생 허용
                        userAgentString = userAgent
                    }

                    var isFound = false

                    webView.webViewClient = object : WebViewClient() {
                        // 크롬 개발자 도구의 '네트워크(Network)' 탭 역할
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            // 요청되는 모든 주소 중 m3u8이나 스트리밍 관련 키워드가 포함된 것을 로그캣에 출력
                            if (reqUrl.contains("m3u8") || reqUrl.contains("wdbroadcast") || reqUrl.contains("playlist")) {
                                println("DEBUG [KingkongTv] 🌐 [네트워크 포착]: $reqUrl")
                            }

                            if (!isFound && reqUrl.contains(".m3u8")) {
                                isFound = true
                                println("DEBUG [KingkongTv] 🎯 [M3U8 스니핑 성공!]: $reqUrl")
                                view?.post {
                                    if (cont.isActive) cont.resume(reqUrl)
                                    view.destroy()
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        // 페이지 로딩 완료 시 재생 버튼을 누르도록 JS 강제 주입
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            view?.evaluateJavascript("""
                                (function() {
                                    var attemptClick = function() {
                                        var buttons = document.querySelectorAll('button, .play-btn, .vjs-play-control, .jw-icon-display, div[class*="play"]');
                                        for (var i = 0; i < buttons.length; i++) {
                                            buttons[i].click();
                                        }
                                        var video = document.querySelector('video');
                                        if(video) { video.play(); }
                                    };
                                    attemptClick(); // 즉시 클릭
                                    setTimeout(attemptClick, 1000); // 1초 뒤 재시도
                                    setTimeout(attemptClick, 3000); // 3초 뒤 재시도
                                })();
                            """.trimIndent(), null)

                            // 10초 내에 패킷이 안 잡히면 타임아웃
                            view?.postDelayed({
                                if (!isFound && cont.isActive) {
                                    println("DEBUG [KingkongTv] ⏳ [WebView 타임아웃] 10초간 M3U8 요청이 감지되지 않았습니다.")
                                    cont.resume(null)
                                    view.destroy()
                                }
                            }, 10000)
                        }
                    }

                    val headers = mutableMapOf("Referer" to referer)
                    webView.loadUrl(url, headers)

                } catch (e: Exception) {
                    println("DEBUG [KingkongTv] v8.6: WebView 생성 중 에러 - ${e.message}")
                    if (cont.isActive) cont.resume(null)
                }
            }
        }
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
