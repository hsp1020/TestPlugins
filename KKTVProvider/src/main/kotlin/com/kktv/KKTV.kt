// v8.5 - TVWiki/Kotbc 구조 벤치마킹: WebViewResolver를 활용한 동적 영상 주소(m3u8) 인터셉트 도입
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver // [추가 포인트 v8.5] 웹뷰 리졸버 임포트
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException

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
            println("DEBUG [KingkongTv] v8.5: 포털 사이트($portalUrl) 접속 시도 중...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v8.5: 도메인 변경 감지 -> $cleanedUrl")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.5: 도메인 업데이트 중 오류 발생 - ${e.message}")
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
            if (e is CancellationException) throw e
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
        var m3u8Url: String? = null

        try {
            // [수정 포인트 v8.5] 1단계: 기존처럼 정적 HTML을 먼저 빠르게 확인합니다.
            val staticResponse = app.get(playerPageUrl, referer = refererUrl).text
            m3u8Url = Regex("""https?://wdbroadcast\.com:8443/[^"']+\.m3u8[^"']*""").find(staticResponse)?.value
                ?: Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(staticResponse)?.groupValues?.get(1)

            // [수정 포인트 v8.5] 2단계: 정적 추출 실패 시 WebViewResolver 투입
            if (m3u8Url == null) {
                println("DEBUG [KingkongTv] v8.5: 정적 추출 실패. WebViewResolver를 통해 동적 네트워크 인터셉트 시작")
                
                // ".m3u8"이 포함된 네트워크 리소스 요청을 백그라운드에서 낚아채는(Intercept) 웹뷰 인터셉터 생성
                val webViewInterceptor = WebViewResolver(
                    Regex("""\.m3u8"""),
                    timeout = 15000L // 렌더링 대기 시간
                )
                
                // 웹뷰를 통해 플레이어 페이지 렌더링 진행
                val wvResponse = app.get(playerPageUrl, referer = refererUrl, interceptor = webViewInterceptor)
                
                // 인터셉터가 지정한 정규식(.m3u8)에 해당하는 요청을 낚아챘다면 url에 그 주소가 할당됩니다.
                if (wvResponse.url.contains(".m3u8")) {
                    m3u8Url = wvResponse.url
                    println("DEBUG [KingkongTv] v8.5: WebView 네트워크 인터셉트 성공 - $m3u8Url")
                } else {
                    // 인터셉트에 실패했더라도 렌더링이 완료된 DOM 트리에서 다시 한번 주소를 찾아봅니다.
                    val renderedHtml = wvResponse.document.html()
                    m3u8Url = Regex("""https?://wdbroadcast\.com:8443/[^"']+\.m3u8[^"']*""").find(renderedHtml)?.value
                        ?: Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(renderedHtml)?.groupValues?.get(1)
                    println("DEBUG [KingkongTv] v8.5: WebView DOM 탐색 결과 - ${m3u8Url ?: "실패"}")
                }
            }

            if (m3u8Url == null) {
                println("DEBUG [KingkongTv] v8.5: 모든 추출 방식 실패, 구형 백업 주소(ogtv3) 대체 시도")
                m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            }

            // v8.4에서 수정한 안전한 람다(Lambda) 빌드 문법 유지
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url!!,
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
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("DEBUG [KingkongTv] v8.5: 링크 추출 에러 - ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
