// v7.19 - v7.7 원본 로직 100% 복구 및 .sdp 파싱 에러(ParserException) 해결을 위한 확장자 변환 및 보안 토큰 추출 적용
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    // [v7.7 로직 보존] 기본값 설정 및 동적 업데이트 기반
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    // [v7.7 로직 보존] 사용자 지침에 따라 TvType.Others 유지
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    // [v7.7 로직 보존] 고정된 안내 포털 주소
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    // [v7.7 로직 보존] 메인 페이지 로드 전, 포털에서 최신 주소를 동적으로 긁어오는 함수
    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.19: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            // class="nav-cta" 인 a 태그의 href 속성 추출
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                // 끝에 붙은 '/' 제거 (기존 mainUrl 형식과 맞추기 위함)
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.19: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                } else {
                    println("DEBUG [KingkongTv] v7.19: 기존 주소 유지(변경사항 없음) - $mainUrl")
                }
            } else {
                println("DEBUG [KingkongTv] v7.19: 포털에서 최신 주소를 찾지 못했습니다. 기본 주소를 사용합니다 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.19: 최신 주소 확인 중 오류 발생 - ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [v7.7 로직 보존] 메인 페이지 로드 전 최신 도메인으로 업데이트
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v7.19: 메인 페이지 로드 시작 - $url")
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            // [v7.7 로직 보존] 중요 경기(subject bold)를 먼저 오도록(true) 내림차순 우선 정렬
            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            println("DEBUG [KingkongTv] v7.19: 총 ${rows.size}개 경기 중 중요 경기(bold) 우선 정렬 완료")

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
            println("DEBUG [KingkongTv] v7.19: 메인 페이지 로드 실패 - ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        var title = channelDiv.attr("data-title") // var로 변경하여 수정 가능하게 함
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        // [v7.7 로직 보존] 중요 경기인 경우 시각적 식별을 위해 제목에 불꽃 이모지 추가
        if (this.selectFirst("td.subject")?.hasClass("bold") == true) {
            title = "🔥 $title"
        }

        // [v7.7 로직 보존] 썸네일 캐시 무효화 (Cache Busting)
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

        println("DEBUG [KingkongTv] v7.19: 검색 결과 항목 생성 중 - $title")
        
        // [v7.7 로직 보존] Others 타입 유지
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

        println("DEBUG [KingkongTv] v7.19: 상세 정보 로드 중 - $title")

        // [v7.7 로직 보존] 타입 Others 유지
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
        val rawRef = uri.getQueryParameter("ref")
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/kktv/index.php"
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8").replace("🔥 ", "") else "스포츠중계"

        println("DEBUG [KingkongTv] v7.19: === [STAGE 1] 링크 로드 시작 ID=$streamId ===")

        // [수정 포인트 v7.19] 사용자 제공 실제 깊은 경로 적용
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/theme/kktv/pages/pc/pc_view.php?ch=$streamId&title=${URLEncoder.encode(title, "UTF-8")}&start=on&num=1"
        println("DEBUG [KingkongTv] v7.19: 분석 대상 Player URL = $playerPageUrl")

        val browserHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to refererUrl
        )

        try {
            val response = app.get(playerPageUrl, headers = browserHeaders).text
            
            // [v7.7 정규식 보존 + v7.19 신규 대응]
            // .sdp, .m3u8, wsSecret 토큰을 모두 포함하는 확장 정규식
            val streamRegex = Regex("""['"]((https?://[^'"]+)(?:\.m3u8|\.sdp|\.mpd)[^'"]*)['"]""")
            var extractedUrl = streamRegex.find(response)?.groupValues?.get(1)?.replace("\\/", "/")

            // 보안 토큰 개별 추출 로직 (v7.19 추가)
            if (extractedUrl == null || !extractedUrl.contains("wsSecret")) {
                val secret = Regex("""wsSecret\s*[:=]\s*['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                val time = Regex("""wsTime\s*[:=]\s*['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (secret != null && time != null) {
                    extractedUrl = "https://cdnnowstream.com/cdnnow/$streamId.sdp?wsSecret=$secret&wsTime=$time&site=kktv"
                    println("DEBUG [KingkongTv] v7.19: 개별 토큰 기반 URL 조합 성공")
                }
            }

            // [v7.7 백업 로직 보존]
            if (extractedUrl == null) {
                extractedUrl = "https://play.ogtv2.com/live/$streamId.m3u8?site=kktv"
                println("DEBUG [KingkongTv] v7.19: 추출 실패로 백업 도메인 사용")
            }

            println("DEBUG [KingkongTv] v7.19: [STAGE 2] 최종 추출 결과 - $extractedUrl")

            // [v7.19 핵심 수정] ParserException 방어를 위해 SDP를 M3U8로 변환한 링크도 함께 제공
            val finalLinks = mutableListOf<Pair<String, String>>()
            finalLinks.add(extractedUrl!! to "KKTV (원본)")
            
            if (extractedUrl.contains(".sdp")) {
                val m3u8Converted = extractedUrl.replace(".sdp", ".m3u8")
                finalLinks.add(m3u8Converted to "KKTV (HLS 변환모드)")
            }

            // [v7.7 SSL 우회 로직 보존] ogtv 도메인일 경우 HTTP 다운그레이드 버전 추가
            if (extractedUrl.contains("play.ogtv") && extractedUrl.startsWith("https://")) {
                val bypassed = extractedUrl.replaceFirst("https://", "http://")
                finalLinks.add(bypassed to "KKTV (SSL 우회)")
            }

            // [v7.19 핵심 수정] 플레이어 헤더 강화 (Referer를 전체 경로로 고정)
            val playerHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Referer" to playerPageUrl,
                "Origin" to "https://kktv.speed10-1.com",
                "Connection" to "keep-alive"
            )

            // 추출된 모든 가능한 링크 인계
            for (linkInfo in finalLinks) {
                callback.invoke(
                    newExtractorLink(source = name, name = linkInfo.second, url = linkInfo.first, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
            println("DEBUG [KingkongTv] v7.19: === 모든 처리 정상 종료 및 링크 인계 완료 ===")

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.19: 에러 발생 - ${e.message}")
            e.printStackTrace()
        }

        return true
    }

    // [v7.7 로직 보존] fixUrl 함수 유지
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
