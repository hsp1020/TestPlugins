package com.kktv // 기존 플러그인 구조 유지를 위해 패키지명은 그대로 둡니다.

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

// 클래스 이름을 CUTVProvider로 변경했습니다. (Plugin.kt 수정 필요 - 하단 설명 참고)
class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "씨유티비 (CUTV)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 사용자님이 알려주신 새로운 메인 페이지 주소
    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            println("DEBUG [CUTV] 1. 메인 페이지 로드 시작: $url")
            
            // 클라우드플레어가 없으므로 일반 get 요청으로 1초 만에 가져옵니다.
            val doc = app.get(url, headers = defaultHeaders).document
            
            // 소스코드에 있는 iframe 주소 추출
            var iframeSrc = doc.selectFirst("div.na-row iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
                ?: doc.selectFirst("iframe[src*='hudtv']")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                // 소스코드에서 확인한 주소로 Fallback
                iframeSrc = "https://sol13.galaxy-stream.live/cli/mobile"
                println("DEBUG [CUTV] 2. Iframe 추출 실패. Fallback 주소 사용: $iframeSrc")
            } else {
                println("DEBUG [CUTV] 2. 정상 추출된 Iframe 주소: $iframeSrc")
            }

            // Iframe 호출 (500 에러 방지를 위해 Referer 필수)
            println("DEBUG [CUTV] 3. Iframe 페이지 로드 시작 (Referer: $url)")
            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            val iframeDoc = Jsoup.parse(iframeHtml)
            
            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [CUTV] 4. 파싱된 경기 개수: ${rows.size}")

            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
                    val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
                    val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
                    val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
                    
                    if (homeTeam.isNotBlank()) {
                        val title = "[$league] $homeTeam vs $awayTeam ($time)"
                        val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src") ?: ""

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
            } else {
                println("DEBUG [CUTV] 🚨 경기 목록이 없습니다. HTML 확인: ${iframeHtml.take(300)}")
            }
        } catch (e: Exception) {
            println("DEBUG [CUTV] 🚨 에러 발생 (getMainPage): ${e.message}")
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

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
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        try {
            // 영상 링크를 딸 때도 500 에러 방지를 위해 Referer를 강제합니다.
            val responseText = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
            val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
            val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

            streamLinks.forEachIndexed { index, link ->
                val cleanLink = link.replace("\\/", "/") 
                val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                                  else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                                  else "채널 ${index + 1}"

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = qualityName,
                        url = cleanLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeUrl
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
}
