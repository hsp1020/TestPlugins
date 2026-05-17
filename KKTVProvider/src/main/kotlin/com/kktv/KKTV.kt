package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/bbs/content.php?co_id=livetv9" to "실시간 스포츠 중계"
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
            println("DEBUG [KKTV] 1. 메인 페이지 로드 시작: $url")
            
            // [수정 1] 어설픈 WebViewResolver를 제거하고 Cloudstream의 내장 CF 우회 기능에 맡깁니다.
            val doc = app.get(url, headers = defaultHeaders).document
            println("DEBUG [KKTV] 2. 메인 페이지 응답 타이틀: [${doc.title()}]")

            var iframeSrc = doc.selectFirst("div#player_field iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
                ?: doc.selectFirst("iframe[src*='hudtv']")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://sol7.galaxy-stream.live/cli/box"
                println("DEBUG [KKTV] 3. Iframe 추출 실패. Fallback 주소 사용: $iframeSrc")
            } else {
                println("DEBUG [KKTV] 3. 정상 추출된 Iframe 주소: $iframeSrc")
            }

            // [수정 2] 500 에러 방지를 위해 Referer를 반드시 포함하여 Iframe을 호출합니다.
            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            val iframeDoc = Jsoup.parse(iframeHtml)
            
            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [KKTV] 4. Jsoup 파싱된 경기 개수: ${rows.size}")

            if (rows.isNotEmpty()) {
                // 정상적인 DOM 구조일 경우
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
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
            } else {
                // [수정 3] Jsoup이 DOM을 못 읽을 경우를 대비한 2중 안전장치 (정규식 강제 추출)
                println("DEBUG [KKTV] 5. DOM 파싱 실패. 정규식(Regex)으로 강제 추출 시도합니다.")
                val titleRegex = """title=["']([^"']+\s+vs\s+[^"']+)["']""".toRegex()
                val titles = titleRegex.findAll(iframeHtml).map { it.groupValues[1] }.distinct().toList()
                
                println("DEBUG [KKTV] 6. 정규식 추출된 경기 개수: ${titles.size}")
                
                titles.forEach { title ->
                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                    val fakeUrl = "$mainUrl/live_stream/dummy?title=$encodedTitle&iframe=$encodedIframe"
                    
                    items.add(newLiveSearchResponse(name = title, url = fakeUrl))
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KKTV] 🚨 에러 발생 (getMainPage): ${e.message}")
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
            // 재생 링크를 딸 때도 500 에러 방지를 위해 Referer를 강제합니다.
            val responseText = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/bbs/content.php?co_id=livetv9").text
            
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
