// v7.18 - v7.7 원본 구조 100% 복구 + 실제 플레이어 경로 및 보안 토큰(wsSecret/Time) 동적 파싱 적용
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    // [기존 로직 보존] v7.7의 기본 설정 유지
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    // [기존 로직 보존] 사용자 지침에 따라 TvType.Others 유지
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    // [기존 로직 보존] 안내 포털 주소
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    // [기존 로직 보존] v7.7의 동적 도메인 업데이트 함수
    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.18: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.18: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                } else {
                    println("DEBUG [KingkongTv] v7.18: 기존 주소 유지(변경사항 없음) - $mainUrl")
                }
            } else {
                println("DEBUG [KingkongTv] v7.18: 포털에서 최신 주소를 찾지 못했습니다. 기본 주소를 사용합니다 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.18: 최신 주소 확인 중 오류 발생 - ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [기존 로직 보존] 메인 페이지 로드 전 최신 도메인 업데이트
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v7.18: 메인 페이지 로드 시작 - $url")
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            // [기존 로직 보존] 중요 경기(subject bold) 내림차순 정렬
            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            println("DEBUG [KingkongTv] v7.18: 총 ${rows.size}개 경기 중 중요 경기(bold) 우선 정렬 완료")

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
            println("DEBUG [KingkongTv] v7.18: 메인 페이지 로드 실패 - ${e.message}")
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

        // [기존 로직 보존] 중요 경기 불꽃 이모지 추가
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) {
            title = "🔥 $title"
        }

        // [기존 로직 보존] 썸네일 캐시 무효화 (Cache Busting)
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

        println("DEBUG [KingkongTv] v7.18: 검색 결과 항목 생성 중 - $title")
        
        return newMovieSearchResponse(title, fakeUrl, TvType.Others) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        println("DEBUG [KingkongTv] v7.18: 상세 정보 로드 중 - $title")

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
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "스포츠중계", "UTF-8").replace("🔥 ", "")
        
        println("DEBUG [KingkongTv] v7.18: 링크 로드 시작 ID=$streamId")

        // [수정 포인트 v7.18] 사용자님이 제공해주신 실제 깊은 경로(pc_view.php) 적용
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/theme/kktv/pages/pc/pc_view.php?ch=$streamId&title=${URLEncoder.encode(title, "UTF-8")}&start=on&num=1"
        
        println("DEBUG [KingkongTv] v7.18: 분석 대상 Player URL = $playerPageUrl")

        // [수정 포인트 v7.18] 브라우저 시청과 동일한 정밀 헤더 구성 (Anti-Bot 우회)
        val browserHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "https://kktv.speed10-1.com/kktv/index.php",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-origin",
            "Connection" to "keep-alive"
        )

        try {
            val response = app.get(playerPageUrl, headers = browserHeaders).text
            
            // [수정 포인트 v7.18] wsSecret, wsTime 보안 토큰 및 .sdp 확장자 추출 정규식
            val streamRegex = Regex("""['"]((https?://[^'"]+)(?:\.m3u8|\.sdp|\.mpd)[^'"]*)['"]""")
            var m3u8Url = streamRegex.find(response)?.groupValues?.get(1)?.replace("\\/", "/")

            // 개별 토큰 탐색 및 조합 (URL이 온전하지 않을 경우 대비)
            if (m3u8Url == null || !m3u8Url.contains("wsSecret")) {
                val secret = Regex("""wsSecret\s*[:=]\s*['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                val time = Regex("""wsTime\s*[:=]\s*['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                
                if (secret != null && time != null) {
                    m3u8Url = "https://cdnnowstream.com/cdnnow/$streamId.sdp?wsSecret=$secret&wsTime=$time&site=kktv"
                    println("DEBUG [KingkongTv] v7.18: 보안 토큰 추출 및 URL 조합 성공")
                }
            }

            // [기존 로직 보존] v7.7의 백업 로직 유지
            if (m3u8Url == null) {
                m3u8Url = "https://play.ogtv2.com/live/$streamId.m3u8?site=kktv"
                println("DEBUG [KingkongTv] v7.18: 추출 실패로 백업 도메인 사용")
            }

            println("DEBUG [KingkongTv] v7.18: 최종 M3U8 URL - $m3u8Url")

            // [수정 포인트 v7.18] ExoPlayer가 서버 접속 시 차단당하지 않도록 레퍼러를 제공해주신 전체 경로로 고정
            val playerHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Referer" to playerPageUrl,
                "Origin" to "https://kktv.speed10-1.com",
                "Connection" to "keep-alive"
            )

            // [기존 로직 보존] v7.7의 SSL 우회 및 링크 인계 로직
            if (m3u8Url != null && m3u8Url!!.contains("play.ogtv") && m3u8Url!!.startsWith("https://")) {
                val bypassedUrl = m3u8Url!!.replaceFirst("https://", "http://")
                println("DEBUG [KingkongTv] v7.18: SSL 우회 적용됨 (HTTPS -> HTTP 변경) - $bypassedUrl")
                
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTPS)", url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTP 우회)", url = bypassedUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.18: 에러 - ${e.message}")
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
