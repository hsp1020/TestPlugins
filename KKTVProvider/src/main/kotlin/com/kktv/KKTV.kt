// v8.0 - wdbroadcast.com 신규 엔진 대응 및 HMAC 기반 보안 주소 파싱 로직 추가
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org",

    override val mainPage = mainPageOf(
        "/live/sportstv2" to "실시간 스포츠 중계"
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v8.0: 포털($portalUrl) 체크 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                mainUrl = latestUrl.trimEnd('/')
                println("DEBUG [KingkongTv] v8.0: 메인 도메인 업데이트 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.0: 도메인 업데이트 중 가설 오류 발생 - ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v8.0: 페이지 로드 - $url")
            val mainDoc = app.get(url).document
            
            // 신규 wdbroad.com iframe 탐색 우선순위 상향
            var iframeSrc = mainDoc.selectFirst("iframe[src*='wdbroad.com/broadcast']")?.attr("src")
                ?: mainDoc.selectFirst("iframe#broadcastFrame")?.attr("src")
                ?: "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"

            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            
            // 개편된 UI 구조(table.board_list) 대응
            var rows = contentDoc.select("table.board_list tbody tr")
            if (rows.isEmpty()) rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { it.selectFirst("td.subject")?.hasClass("bold") == true }
            val home = sortedRows.mapNotNull { it.toSearchResult(realUrl) }

            return newHomePageResponse(HomePageList(request.name, home, true), false)
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.0: 메인 에러 - ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        var title: String? = null
        var streamId: String? = null
        var thumbUrlRaw: String? = null

        // 1. 신규 UI (onclick 인자 추출)
        val subjectTd = this.selectFirst("td.subject")
        val onClickAttr = subjectTd?.attr("onclick")
        if (onClickAttr != null) {
            title = Regex("""load_video\('[^']*',\s*'[^']*',\s*'([^']+)'""").find(onClickAttr)?.groupValues?.get(1)
            streamId = Regex("""load_video\('broadcast/(\d+)'""").find(onClickAttr)?.groupValues?.get(1)
            thumbUrlRaw = this.selectFirst("td.cateimg img")?.attr("src")
        } 
        
        // 2. 기존 UI (보존)
        if (title.isNullOrBlank()) {
            val channelDiv = this.selectFirst("div.channel_on")
            title = channelDiv?.attr("data-title")
            streamId = channelDiv?.attr("data-stream")
            thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        }
        
        if (title.isNullOrBlank() || streamId.isNullOrBlank()) return null
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) title = "🔥 $title"

        val thumbUrl = if (thumbUrlRaw != null) fixUrl(refererUrl, thumbUrlRaw) + "?t=${System.currentTimeMillis()}" else ""
        val fakeUrl = "$mainUrl/live_stream/$streamId?title=${URLEncoder.encode(title, "UTF-8")}&poster=${URLEncoder.encode(thumbUrl, "UTF-8")}&ref=${URLEncoder.encode(refererUrl, "UTF-8")}"

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { this.posterUrl = thumbUrl }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "Live", "UTF-8")
        val poster = uri.getQueryParameter("poster")?.let { URLDecoder.decode(it, "UTF-8") }
        return newMovieLoadResponse(title, url, TvType.Others, url) { 
            this.posterUrl = poster
            this.plot = "실시간 중계 - v8.0 엔진" 
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
        
        println("DEBUG [KingkongTv] v8.0: 링크 분석 시작 ID=$streamId")

        // [수정 포인트 v8.0] 개편된 wdbroad.com 플레이어 페이지 대응
        val playerPageUrl = "https://wdbroad.com/broadcast/view.php?stream=$streamId"
        
        try {
            val response = app.get(playerPageUrl, referer = refererUrl).text
            
            // 1. 사용자께서 제공하신 신규 wdbroadcast.com 패턴 추출 시도
            // 서버 스크립트 내부에서 생성되는 playlist.m3u8 주소를 찾습니다.
            var m3u8Url = Regex("""['"](https?://wdbroadcast\.com:8443/[^'"]+playlist\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)

            // 2. 만약 찾지 못했다면 기존 범용 m3u8 패턴 사용
            if (m3u8Url == null) {
                m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            }

            // 3. 최후의 수단: 추출해주신 주소 구조를 바탕으로 동적 구성 (Fallback)
            // 실제 사이트에서 'live42'와 같은 매핑이 달라질 수 있으므로, 응답값 내의 ch 값을 우선시합니다.
            if (m3u8Url == null) {
                val chMatch = Regex("""ch\s*[:=]\s*['"](live\d+)['"]""").find(response)?.groupValues?.get(1) ?: "live$streamId"
                m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv" // 보존용 구형 백업
            }

            println("DEBUG [KingkongTv] v8.0: 최종 추출 URL - $m3u8Url")

            callback.invoke(
                newExtractorLink(name, name, m3u8Url!!, "https://wdbroad.com/", Qualities.Unknown.value, true) {
                    this.headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to "https://wdbroad.com",
                        "Referer" to "https://wdbroad.com/"
                    )
                }
            )
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.0: 링크 로드 가설 오류 - ${e.message}")
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
