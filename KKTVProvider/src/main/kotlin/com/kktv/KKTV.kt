package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val mainPageUrl = "$mainUrl/bbs/content.php?co_id=livetv9"
        
        try {
            println("DEBUG [KKTV] 1. 메인 페이지 로드 시작: $mainPageUrl")
            val doc = app.get(mainPageUrl, headers = defaultHeaders).document
            
            // 텍스트 파일 소스 기반 iframe 추출
            var iframeSrc = doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
            println("DEBUG [KKTV] 2. 추출된 Iframe 원본 주소: $iframeSrc")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://sol7.galaxy-stream.live/cli/box"
                println("DEBUG [KKTV] 2-1. Iframe 주소 추출 실패로 인한 Fallback 적용: $iframeSrc")
            }

            // Iframe 페이지 호출 (Cloudflare 우회를 위해 Referer 추가)
            println("DEBUG [KKTV] 3. Iframe 페이지 로드 시작 (Cloudflare 검사 통과 시도)")
            val iframeResponse = app.get(iframeSrc, headers = defaultHeaders + mapOf("Referer" to mainUrl))
            val iframeDoc = iframeResponse.document
            
            // Cloudflare에 막혔는지 확인용 로그
            val pageTitle = iframeDoc.title()
            println("DEBUG [KKTV] 4. Iframe 페이지 타이틀: $pageTitle")
            if (pageTitle.contains("Just a moment") || pageTitle.contains("Cloudflare")) {
                println("DEBUG [KKTV] 🚨 경고: Cloudflare 챌린지에 막혔습니다! 웹뷰 우회가 정상 작동하지 않았습니다.")
                // Cloudstream 코어의 웹뷰 인터셉터가 작동하지 않은 상태입니다.
            }

            // 이미지 파일(Image 0) 기반 DOM 파싱
            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [KKTV] 5. 파싱된 경기(tr.ssc-row-candy) 개수: ${rows.size}")

            if (rows.isEmpty()) {
                println("DEBUG [KKTV] 🚨 경고: 경기 목록을 찾을 수 없습니다. HTML 스니펫 출력:")
                println(iframeDoc.html().take(1000)) // 원인 파악을 위해 HTML 앞부분 출력
            }

            rows.forEach { row ->
                val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
                val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
                val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
                val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
                
                if (homeTeam.isNotBlank()) {
                    val title = "[$league] $homeTeam vs $awayTeam ($time)"
                    val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src") ?: ""

                    // 원본 코드(KingkongTv)의 아키텍처 차용: fakeUrl 생성
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedPoster = URLEncoder.encode(posterUrl, "UTF-8")
                    val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                    
                    val fakeUrl = "$mainUrl/live_stream/dummy?title=$encodedTitle&poster=$encodedPoster&iframe=$encodedIframe"

                    items.add(
                        newLiveSearchResponse(
                            name = title,
                            url = fakeUrl
                        ) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }

            println("DEBUG [KKTV] 6. 메인 페이지 파싱 완료. 총 ${items.size}개 항목 추가됨.")

        } catch (e: Exception) {
            println("DEBUG [KKTV] 🚨 에러 발생 (getMainPage): ${e.message}")
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList("실시간 라이브 중계", items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        println("DEBUG [KKTV] 7. 상세 정보 로드 중: $title")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol7.galaxy-stream.live/cli/box"

        println("DEBUG [KKTV] 8. 링크 로드 시작. 타겟 Iframe: $iframeUrl")

        try {
            val responseText = app.get(iframeUrl, headers = defaultHeaders).text
            
            // 말씀해주신 영상 URL 형식(hudtv, coupangstreaming)을 모두 잡아내는 정규식
            val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
            val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

            println("DEBUG [KKTV] 9. 추출된 m3u8 링크 개수: ${streamLinks.size}")

            if (streamLinks.isEmpty()) {
                println("DEBUG [KKTV] 🚨 경고: m3u8 링크를 찾지 못했습니다. HTML 스니펫 출력:")
                println(responseText.take(1000))
                return false
            }

            streamLinks.forEachIndexed { index, link ->
                val cleanLink = link.replace("\\/", "/") 
                println("DEBUG [KKTV] 10. 스트림 링크 추가: $cleanLink")
                
                val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                                  else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                                  else "채널 ${index + 1}"

                // 원본 코드 구조 완벽 반영 (headers 에러 방지)
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = qualityName,
                        url = cleanLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://sol7.galaxy-stream.live/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (e: Exception) {
            println("DEBUG [KKTV] 🚨 에러 발생 (loadLinks): ${e.message}")
            e.printStackTrace()
            return false
        }

        return true
    }
}
