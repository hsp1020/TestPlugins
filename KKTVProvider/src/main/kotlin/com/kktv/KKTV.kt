// v8.7 - 화면이 없는 쌩웹뷰(Headless WebView)의 렌더링 중단 방지, 메인 루퍼 타임아웃 적용 및 전면 네트워크 스니핑
package com.KingkongTv

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Handler
import android.os.Looper
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
            println("DEBUG [KingkongTv] v8.7: 포털 사이트 접속 시도 중...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v8.7: 도메인 변경 감지 -> $cleanedUrl")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.7: 도메인 업데이트 에러 - ${e.message}")
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
            println("DEBUG [KingkongTv] v8.7: 메인 페이지 로드 실패 - ${e.message}")
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
        println("DEBUG [KingkongTv] v8.7: 쌩웹뷰 스니핑 시작 - $playerPageUrl")

        // 1. 메인 루퍼가 안정적으로 확보된 쌩웹뷰 스니핑 실행
        var m3u8Url = getM3u8FromRawWebView(playerPageUrl, refererUrl)

        // 2. 스니핑 실패 시 최후의 구형 정규식 백업 (보존)
        if (m3u8Url == null) {
            println("DEBUG [KingkongTv] v8.7: WebView 스니핑 실패. 정규식 백업 시도")
            try {
                val response = app.get(playerPageUrl, referer = refererUrl).text
                m3u8Url = Regex("""https?://wdbroadcast\.com:8443/[^"']+\.m3u8[^"']*""").find(response)?.value
                if (m3u8Url == null) m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            } catch (e: Exception) { }
        }

        if (m3u8Url != null) {
            println("DEBUG [KingkongTv] v8.7: 최종 추출된 영상 주소 - $m3u8Url")
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
                        println("DEBUG [KingkongTv] v8.7: Context가 Null입니다. KKTVPlugin.kt가 업데이트되었는지 확인하세요.")
                        if (cont.isActive) cont.resume(null)
                        return@suspendCancellableCoroutine
                    }

                    val webView = WebView(context)
                    
                    // [수정 포인트 v8.7] 쌩웹뷰가 백그라운드에서도 크기를 갖도록 강제하여 렌더링 중단(Pause) 방지
                    webView.layout(0, 0, 1920, 1080)
                    
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false // 동영상 강제 자동 재생 허용
                        userAgentString = userAgent
                    }

                    var isFound = false

                    webView.webViewClient = object : WebViewClient() {
                        // 크롬 개발자 도구의 '네트워크(Network)' 탭을 완벽하게 모사
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            // [수정 포인트 v8.7] 사이트가 영상을 어떻게 숨겼는지 파악하기 위해 모든 네트워크 트래픽 무조건 출력
                            if (reqUrl.startsWith("http")) {
                                println("DEBUG [KingkongTv] 🌐 [네트워크 포착]: $reqUrl")
                            }

                            // .m3u8 확장자가 발견되면 즉시 탈출
                            if (!isFound && reqUrl.contains(".m3u8")) {
                                isFound = true
                                println("DEBUG [KingkongTv] 🎯 [M3U8 스니핑 성공!]: $reqUrl")
                                if (cont.isActive) {
                                    cont.resume(reqUrl)
                                }
                                
                                // 안전한 웹뷰 파괴를 위해 메인 루퍼로 넘김
                                Handler(Looper.getMainLooper()).post {
                                    try { view?.destroy() } catch (e: Exception) {}
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            println("DEBUG [KingkongTv] v8.7: 페이지 로딩 완료, 영상 강제 재생을 위한 자바스크립트 주입")
                            
                            view?.evaluateJavascript("""
                                (function() {
                                    var attemptClick = function() {
                                        var buttons = document.querySelectorAll('button, .play-btn, .vjs-play-control, .jw-icon-display, div[class*="play"], .vjs-big-play-button');
                                        for (var i = 0; i < buttons.length; i++) {
                                            buttons[i].click();
                                        }
                                        var video = document.querySelector('video');
                                        if(video) { video.play(); }
                                    };
                                    attemptClick(); 
                                    setTimeout(attemptClick, 1000); 
                                    setTimeout(attemptClick, 3000); 
                                })();
                            """.trimIndent(), null)
                        }
                    }

                    // [수정 포인트 v8.7] 화면에 붙어있지 않은 뷰의 view.postDelayed는 작동하지 않으므로 순정 메인 루퍼 Handler 사용
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (!isFound && cont.isActive) {
                            println("DEBUG [KingkongTv] ⏳ [WebView 타임아웃] 15초간 M3U8 요청이 감지되지 않았습니다. 로그캣의 [네트워크 포착] 내역을 확인해주세요.")
                            cont.resume(null)
                            try { webView.destroy() } catch (e: Exception) {}
                        }
                    }, 15000)

                    val headers = mutableMapOf("Referer" to referer)
                    webView.loadUrl(url, headers)

                } catch (e: Exception) {
                    println("DEBUG [KingkongTv] v8.7: WebView 엔진 가동 중 에러 - ${e.message}")
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
