// v8.1 - 작은 카테고리 아이콘이 강제 확대되어 깨지는 현상을 방지하기 위해 썸네일(poster) 전달 로직 비활성화
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
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv2" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v8.1: 포털($portalUrl) 체크 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                mainUrl = latestUrl.trimEnd('/')
                println("DEBUG [KingkongTv] v8.1: 메인 도메인 업데이트 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.1: 도메인 업데이트 중 가설 오류 발생 - ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v8.1: 페이지 로드 - $url")
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
            println("DEBUG [KingkongTv] v8.1: 메인 에러 - ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        var title: String? = null
        var streamId: String? = null
        
        // [기존 로직 보존] 썸네일 변수는 남겨두지만 할당 과정에서 사용하지 않습니다.
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

        // [수정 포인트 v8.1] 썸네일 파라미터를 fakeUrl 생성에서 제외
        // val thumbUrl = if (thumbUrlRaw != null) fixUrl(refererUrl, thumbUrlRaw) + "?t=${System.currentTimeMillis()}" else ""
        val fakeUrl = "$mainUrl/live_stream/$streamId?title=${URLEncoder.encode(title, "UTF-8")}&ref=${URLEncoder.encode(refererUrl, "UTF-8")}"

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { 
            // [수정 포인트 v8.1] Cloudstream이 아이콘을 확대하지 못하도록 포스터 URL 할당 해제 (텍스트로만 표시됨)
            // this.posterUrl = thumbUrl 
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "Live", "UTF-8")
        
        // [수정 포인트 v8.1] fakeUrl에서 썸네일을 받지 않으므로 처리 생략
        // val poster = uri.getQueryParameter("poster")?.let { URLDecoder.decode(it, "UTF-8") }
        
        return newMovieLoadResponse(title, url, TvType.Others, url) { 
            // this.posterUrl = poster
            this.plot = "실시간 중계 - v8.1 엔진 (썸네일 비활성화)" 
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
        
        println("DEBUG [KingkongTv] v8.1: 링크 분석 시작 ID=$streamId")

        val playerPageUrl = "https://wdbroad.com/broadcast/view.php?stream=$streamId"
        
        try {
            val response = app.get(playerPageUrl, referer = refererUrl).text
            
            var m3u8Url = Regex("""['"](https?://wdbroadcast\.com:8443/[^'"]+playlist\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)

            if (m3u8Url == null) {
                m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            }

            if (m3u8Url == null) {
                val chMatch = Regex("""ch\s*[:=]\s*['"](live\d+)['"]""").find(response)?.groupValues?.get(1) ?: "live$streamId"
                m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv" 
            }

            println("DEBUG [KingkongTv] v8.1: 최종 추출 URL - $m3u8Url")

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
            println("DEBUG [KingkongTv] v8.1: 링크 로드 가설 오류 - ${e.message}")
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
