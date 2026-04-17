// v7.8 - 스트림 도메인 동적 파싱, iframe 중첩 탐지 및 썸네일 기반 백업 도메인 추론 추가, HTTP/HTTPS 다중 링크 제공
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    // 기본값으로 설정해두지만, 실행 시 updateMainUrlIfNeeded()에 의해 동적으로 변경됩니다.
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    // [기존 로직 보존] 사용자님의 의도대로 카테고리는 원래의 Others로 유지합니다.
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    // 고정된 안내 포털 주소
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    // 메인 페이지 로드 전, 포털에서 최신 주소를 동적으로 긁어오는 함수
    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.8: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            // class="nav-cta" 인 a 태그의 href 속성 추출
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                // 끝에 붙은 '/' 제거 (기존 mainUrl 형식과 맞추기 위함)
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.8: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                } else {
                    println("DEBUG [KingkongTv] v7.8: 기존 주소 유지(변경사항 없음) - $mainUrl")
                }
            } else {
                println("DEBUG [KingkongTv] v7.8: 포털에서 최신 주소를 찾지 못했습니다. 기본 주소를 사용합니다 - $mainUrl")
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.8: 최신 주소 확인 중 오류 발생 - ${e.message}")
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 메인 페이지 로드 전 최신 도메인으로 업데이트
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            println("DEBUG [KingkongTv] v7.8: 메인 페이지 로드 시작 - $url")
            val mainDoc = app.get(url).document
            
            // [수정 포인트 v7.8] iframe id가 변경될 경우를 대비해 다중 CSS 선택자로 탐색 범위를 확장
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame, iframe[src*='index.php'], iframe[src*='kktv']")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
                println("DEBUG [KingkongTv] v7.8: iframe 탐색 실패, 기본 iframe 주소 사용")
            }

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            // [기존 로직 보존] 중요 경기(subject bold)를 먼저 오도록(true) 내림차순 우선 정렬
            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            println("DEBUG [KingkongTv] v7.8: 총 ${rows.size}개 경기 중 중요 경기(bold) 우선 정렬 완료")

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
            println("DEBUG [KingkongTv] v7.8: 메인 페이지 로드 실패 - ${e.message}")
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

        // [기존 로직 보존] 중요 경기인 경우 시각적 식별을 위해 제목에 불꽃 이모지 추가
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

        println("DEBUG [KingkongTv] v7.8: 검색 결과 항목 생성 중 - $title")
        
        // 객체 껍데기는 VOD용(MovieSearch)을 쓰되, 실제 카테고리는 사용자님이 원하시는 TvType.Others로 전달
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

        println("DEBUG [KingkongTv] v7.8: 상세 정보 로드 중 - $title")

        // LiveStream 구조 대신 시청 기록이 남는 단일 영상 구조(MovieLoad)를 차용하며, 타입은 Others로 유지
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
        
        println("DEBUG [KingkongTv] v7.8: 링크 로드 시작 ID=$streamId, Referer=$refererUrl")

        // [수정 포인트 v7.8] 무작정 하드코딩된 도메인(speed10-1)을 쓰지 않고, refererUrl의 실제 도메인을 추출하여 pc_view.php 파싱
        val playerPageUrl = if (refererUrl.contains("index.php")) {
            refererUrl.replace("index.php", "pc_view.php").substringBefore("?") + "?stream=$streamId"
        } else {
            try {
                val baseUri = java.net.URI(refererUrl)
                "${baseUri.scheme}://${baseUri.host}/kktv/pc_view.php?stream=$streamId"
            } catch (e: Exception) {
                "https://kktv.speed10-1.com/kktv/pc_view.php?stream=$streamId"
            }
        }
        
        // 동적 도메인을 Origin과 Referer 헤더에도 적용
        val baseDomain = try {
            val u = java.net.URI(playerPageUrl)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://kktv.speed10-1.com"
        }

        println("DEBUG [KingkongTv] v7.8: 플레이어 URL - $playerPageUrl")

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to refererUrl,
            "Origin" to baseDomain
        )

        try {
            val response = app.get(playerPageUrl, headers = headers).text
            
            // 1. m3u8 패턴 찾기
            var m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            // 2. webrtc 패턴 찾기 및 변환
            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    println("DEBUG [KingkongTv] v7.8: WebRTC 링크 발견 - $webrtcMatch")
                    m3u8Url = webrtcMatch
                        .replace("webrtc://", "https://")
                        .replace(streamId, "$streamId.m3u8") 
                }
            }

            // [추가 포인트 v7.8] 3. pc_view.php 내부에 플레이어 iframe이 한 번 더 중첩된 경우 탐색
            if (m3u8Url == null) {
                val iframeSrc = Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (iframeSrc != null) {
                    val realIframeSrc = fixUrl(playerPageUrl, iframeSrc)
                    println("DEBUG [KingkongTv] v7.8: 중첩 iframe 플레이어 탐색 시도 - $realIframeSrc")
                    try {
                        val iframeResponse = app.get(realIframeSrc, headers = headers).text
                        m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(iframeResponse)?.groupValues?.get(1)
                        if (m3u8Url == null) {
                            val webrtc2 = Regex("""['"](webrtc://[^'"]+)['"]""").find(iframeResponse)?.groupValues?.get(1)
                            if (webrtc2 != null) {
                                m3u8Url = webrtc2.replace("webrtc://", "https://").replace(streamId, "$streamId.m3u8")
                            }
                        }
                    } catch (e: Exception) {
                        println("DEBUG [KingkongTv] v7.8: 중첩 iframe 로드 에러 - ${e.message}")
                    }
                }
            }

            // 4. 실패 시 최후의 수단 백업
            if (m3u8Url == null) {
                var ogtvDomain = "play.ogtv3.com"
                val rawPoster = uri.getQueryParameter("poster")
                // [수정 포인트 v7.8] 썸네일 파라미터에서 현재 실제 서비스 중인 ogtv 서버(예: ogtv2) 번호를 동적으로 추론
                if (!rawPoster.isNullOrBlank()) {
                    val decodedPoster = URLDecoder.decode(rawPoster, "UTF-8")
                    val match = Regex("""ogtv(\d+)""").find(decodedPoster)
                    if (match != null) {
                        ogtvDomain = "play.ogtv${match.groupValues[1]}.com"
                        println("DEBUG [KingkongTv] v7.8: 썸네일 URL을 통한 도메인 동적 추론 적용 - $ogtvDomain")
                    }
                }
                m3u8Url = "https://$ogtvDomain/live/$streamId.m3u8?site=kktv"
            }

            println("DEBUG [KingkongTv] v7.8: 최종 확정 M3U8 URL - $m3u8Url")

            // [기존 로직 보존 및 개선 v7.8] HTTPS/HTTP 분기: 기존 HTTP 변환 시 80 포트 막힘으로 인한 타임아웃이 발생하고 있으므로 HTTPS 원본과 HTTP 우회 링크를 '동시에' 추출하여 플레이어에 제공
            if (m3u8Url != null && m3u8Url!!.contains("play.ogtv") && m3u8Url!!.startsWith("https://")) {
                
                // (1) 포트 막힘 회피를 위한 원본 HTTPS 링크
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (HTTPS)",
                        url = m3u8Url!!,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to baseDomain,
                            "Referer" to "$baseDomain/",
                            "User-Agent" to userAgent
                        )
                    }
                )
                
                // (2) SSL 인증서 만료 에러 우회를 위한 기존 로직(HTTP 다운그레이드) 유지
                val bypassedUrl = m3u8Url!!.replaceFirst("https://", "http://")
                println("DEBUG [KingkongTv] v7.8: SSL 우회 적용 (HTTPS -> HTTP) - $bypassedUrl")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (HTTP 우회)",
                        url = bypassedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to baseDomain,
                            "Referer" to "$baseDomain/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            } else {
                // ogtv 도메인이 아닐 경우 단일 처리
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url!!,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to baseDomain,
                            "Referer" to "$baseDomain/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            }

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.8: 에러 발생 - ${e.message}")
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
