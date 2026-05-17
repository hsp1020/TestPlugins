package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

// 클래스명을 에러 로그의 플러그인에 맞추기 위해 KKTVProvider 로 통일했습니다.
class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val mainPageUrl = "$mainUrl/bbs/content.php?co_id=livetv9"
        val doc = app.get(mainPageUrl, headers = defaultHeaders).document

        val iframeSrc = doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src") 
            ?: "https://sol7.galaxy-stream.live/cli/box"

        val iframeDoc = app.get(iframeSrc, headers = defaultHeaders + mapOf("Referer" to mainUrl)).document

        iframeDoc.select("tr.ssc-row-candy").forEach { row ->
            val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
            val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
            
            val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
            val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
            val title = "[$league] $homeTeam vs $awayTeam ($time)"
            
            val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src")

            val matchIdentifier = homeTeam
            val loadUrl = "$iframeSrc?matchKey=$matchIdentifier"

            // [에러 223 해결] LiveSearchResponse 대신 newLiveSearchResponse 빌더 사용
            items.add(
                newLiveSearchResponse(
                    name = title,
                    url = loadUrl,
                    tvType = TvType.Live
                ) {
                    this.posterUrl = posterUrl
                }
            )
        }

        // [에러 225 해결] HomePageResponse 대신 newHomePageResponse 빌더 사용
        return newHomePageResponse(
            items = listOf(HomePageList("실시간 라이브 중계", items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val title = url.substringAfter("matchKey=").let { java.net.URLDecoder.decode(it, "UTF-8") }
        
        // [에러 227 해결] LiveStreamLoadResponse 대신 newLiveStreamLoadResponse 빌더 사용
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        )
    }

    // [에러 228, 229 해결] loadLinks 오버라이드 에러 
    // 이유: Cloudstream 최신 버전에서 subtitleCallback과 callback의 순서가 뒤바뀌었습니다!
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val iframeUrl = data.substringBefore("?matchKey=")
        val responseText = app.get(iframeUrl, headers = defaultHeaders).text

        val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
        val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

        if (streamLinks.isEmpty()) return false

        streamLinks.forEachIndexed { index, link ->
            val cleanLink = link.replace("\\/", "/") 
            
            val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                              else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                              else "채널 ${index + 1}"

            // [에러 230 해결] ExtractorLink 대신 newExtractorLink 함수 사용
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = qualityName,
                    url = cleanLink,
                    referer = "https://sol7.galaxy-stream.live/",
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        return true
    }
}
