// v7.15 - v7.7 원본 로직 및 구조 100% 복구 및 Browser Mode 정밀 헤더만 이식 (코드 누락 방지)
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    // [기존 로직 보존] 기본값 설정 및 동적 변경 유지
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    // [기존 로직 보존] 카테고리 Others 유지
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    // [기존 로직 보존] 고정된 안내 포털 주소
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    // [기존 로직 보존] 포털에서 최신 주소를 동적으로 긁어오는 함수 (로그 포함)
    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.15: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.15: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                } else {
                    println("DEBUG [KingkongTv] v7.15: 기존 주소 유지(변경사항 없음) - $mainUrl")
                }
            } else {
                println("DEBUG [KingkongTv] v7.15: 포털에서 최신 주소를 찾지 못했습니다. 기본 주소를 사용합니다 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.15: 최신 주소 확인 중 오류 발생 - ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // [기존 로직 보존] 메인 페이지 로드 전 최신 도메인 업데이트
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v7.15: 메인 페이지 로드 시작 - $url")
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            // [기존 로직 보존] 중요 경기(subject bold) 정렬 로직 및 로그 복구
            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            println("DEBUG [KingkongTv] v7.15: 총 ${rows.size}개 경기 중 중요 경기(bold) 우선 정렬 완료")

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
            println("DEBUG [KingkongTv] v7.15: 메인 페이지 로드 실패 - ${e.message}")
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

        println("DEBUG [KingkongTv] v7.15: 검색 결과 항목 생성 중 - $title")
        
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

        println("DEBUG [KingkongTv] v7.15: 상세 정보 로드 중 - $title")

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
        
        println("DEBUG [KingkongTv] v7.15: 링크 로드 시작 ID=$streamId, Referer=$refererUrl")

        val playerPageUrl = "https://kktv.speed10-1.com/kktv/pc_view.php?stream=$streamId"
        
        // [핵심 수정 v7.15] 서버의 Anti-Bot을 뚫기 위해 실제 브라우저와 동일한 정밀 지문 헤더 구성
        val browserHeaders = mapOf(
            "User-Agent" to userAgent,
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl,
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Connection" to "keep-alive"
        )

        try {
            // [기존 로직 보존] playerPageUrl 호출
            val response = app.get(playerPageUrl, headers = browserHeaders).text
            
            // 1. m3u8 패턴 찾기 (기존 정규식 보존)
            var m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            // [추가 대응] JSON 내 이스케이프 문자열(\/)까지 고려한 정규식 보강 (필요한 경우에만 작동)
            if (m3u8Url == null) {
                m3u8Url = Regex("""(?i)(?:source|file|url|src|stream)\s*[:=]\s*['"](https?:[\\/]+[^'"]+)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            }

            // 2. webrtc 패턴 찾기 및 변환 (기존 로직 보존)
            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    println("DEBUG [KingkongTv] v7.15: WebRTC 링크 발견 - $webrtcMatch")
                    m3u8Url = webrtcMatch
                        .replace("webrtc://", "https://")
                        .replace(streamId, "$streamId.m3u8") 
                }
            }

            // 3. 실패 시 백업 (기존 로직 보존하되, 로그캣 상의 살아있는 서버 ogtv2로 업데이트)
            if (m3u8Url == null) {
                m3u8Url = "https://play.ogtv2.com/live/$streamId.m3u8?site=kktv"
            }

            println("DEBUG [KingkongTv] v7.15: 원본 M3U8 URL - $m3u8Url")

            // [핵심 수정 v7.15] ExoPlayer가 서버 접속 시 403 에러가 나지 않도록, 리퍼러를 '전체 URL'로 고정한 브라우저용 헤더 인계
            val playerHeaders = mapOf(
                "User-Agent" to userAgent,
                "Accept" to "*/*",
                "Referer" to playerPageUrl, // 도메인이 아닌 전체 경로를 리퍼러로 설정
                "Origin" to "https://kktv.speed10-1.com",
                "Connection" to "keep-alive"
            )

            // [기존 로직 보존] SSL 인증서 우회 로직 유지 (HTTPS와 HTTP 두 개를 모두 제공하여 선택 가능하게 함)
            if (m3u8Url != null && m3u8Url!!.contains("play.ogtv") && m3u8Url!!.startsWith("https://")) {
                val bypassedUrl = m3u8Url!!.replaceFirst("https://", "http://")
                
                // 원본 HTTPS 링크 (브라우저와 동일한 조건)
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTPS)", url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )

                // 기존 우회 HTTP 링크 (SSL 에러 대비)
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
            println("DEBUG [KingkongTv] v7.15: 에러 - ${e.message}")
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
