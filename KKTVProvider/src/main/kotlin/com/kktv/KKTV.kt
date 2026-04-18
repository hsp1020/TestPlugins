// v8.3 - 메인페이지 경로 단일화(/live/sportstv2), 썸네일 복구, 플롯 수정 및 wdbroadcast 신규 엔진 정규식 강화
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

    // [수정 포인트 v8.3] 사용자 요청에 따라 채널 B(/live/sportstv2)만 남김
    override val mainPage = mainPageOf(
        "/live/sportstv2" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v8.3: 포털 사이트($portalUrl) 접속 시도 중...")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v8.3: 도메인 변경 감지 -> $cleanedUrl")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.3: 도메인 업데이트 중 가설 오류 발생 - ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v8.3: 메인 페이지 요청 시작 - $url")
            val mainDoc = app.get(url).document
            
            // iframe 탐색 로직 (기존 로직 보존)
            var iframeSrc = mainDoc.selectFirst("iframe[src*='wdbroad.com/broadcast']")?.attr("src")
                ?: mainDoc.selectFirst("iframe#broadcastFrame")?.attr("src")
                ?: "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"

            if (iframeSrc.startsWith("//")) iframeSrc = "https:$iframeSrc"

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            
            // 개편된 UI 구조(table.board_list) 우선 탐색
            var rows = contentDoc.select("table.board_list tbody tr")
            if (rows.isEmpty()) rows = contentDoc.select("div.sports_on_list table tbody tr")

            println("DEBUG [KingkongTv] v8.3: 총 ${rows.size}개의 경기 항목을 찾았습니다.")

            val sortedRows = rows.sortedByDescending { it.selectFirst("td.subject")?.hasClass("bold") == true }
            val home = sortedRows.mapNotNull { it.toSearchResult(realUrl) }

            return newHomePageResponse(HomePageList(request.name, home, true), false)
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.3: 메인 페이지 로드 실패 - ${e.message}")
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null // 종료된 방송 제외

        var title: String? = null
        var streamId: String? = null
        var thumbUrlRaw: String? = null

        // 1. 신규 UI (onclick 함수 인자 추출)
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

        // [수정 포인트 v8.3] 썸네일(posterUrl) 복구
        val thumbUrl = if (thumbUrlRaw != null) {
            val fixed = fixUrl(refererUrl, thumbUrlRaw)
            "$fixed${if (fixed.contains("?")) "&" else "?"}t=${System.currentTimeMillis()}"
        } else ""

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        println("DEBUG [KingkongTv] v8.3: 항목 생성 완료 - $title (ID: $streamId)")

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) { 
            this.posterUrl = thumbUrl // [복구 완료]
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "Live Stream", "UTF-8")
        val poster = uri.getQueryParameter("poster")?.let { URLDecoder.decode(it, "UTF-8") }

        println("DEBUG [KingkongTv] v8.3: 상세 페이지 로드 중 - $title")

        // [수정 포인트 v8.3] 플롯(plot) 텍스트를 "실시간 중계"로만 고정
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
        
        println("DEBUG [KingkongTv] v8.3: 링크 분석 시작 ID=$streamId, Referer=$refererUrl")

        // 플레이어 페이지 주소 (wdbroad.com 개편 대응)
        val playerPageUrl = "https://wdbroad.com/broadcast/view.php?stream=$streamId"
        
        try {
            val response = app.get(playerPageUrl, referer = refererUrl).text
            
            // [수정 포인트 v8.3] wdbroadcast.com:8443 주소 추출 정규식 강화 (유연한 매칭)
            // 따옴표 종류에 상관없이 playlist.m3u8이 포함된 주소를 캡처합니다.
            var m3u8Url = Regex("""https?://wdbroadcast\.com:8443/[^"']+\.m3u8[^"']*""").find(response)?.value

            // 1단계 실패 시 일반적인 m3u8 패턴 탐색
            if (m3u8Url == null) {
                m3u8Url = Regex("""['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            }

            // 2단계 실패 시 구형 백업 주소 (보존)
            if (m3u8Url == null) {
                println("DEBUG [KingkongTv] v8.3: 주소 추출 실패, 백업 주소 시도")
                m3u8Url = "https://play.ogtv3.com/live/$streamId.m3u8?site=kktv"
            }

            println("DEBUG [KingkongTv] v8.3: 최종 추출된 영상 주소 - $m3u8Url")

            // v8.2에서 발생했던 빌드 에러 수정을 위해 네임드 파라미터로 명확히 호출
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url!!,
                    referer = "https://wdbroad.com/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "User-Agent" to userAgent,
                        "Origin" to "https://wdbroad.com",
                        "Referer" to "https://wdbroad.com/"
                    )
                )
            )
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v8.3: 링크 추출 중 치명적 오류 - ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
