package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

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

            items.add(
                newLiveSearchResponse(
                    name = title,
                    url = loadUrl
                ) {
                    this.posterUrl = posterUrl
                }
            )
        }

        return newHomePageResponse(
            list = listOf(HomePageList("실시간 라이브 중계", items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val title = url.substringAfter("matchKey=").let { java.net.URLDecoder.decode(it, "UTF-8") }
        
        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        )
    }

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

            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = qualityName,
                    url = cleanLink
                ) {
                    this.quality = Qualities.Unknown.value
                    this.isM3u8 = true
                    // [핵심 수정] headers는 val 이므로 = 로 덮어씌우지 않고 배열처럼 값을 넣어줍니다.
                    this.headers["Referer"] = "https://sol7.galaxy-stream.live/"
                }
            )
        }
        return true
    }
}
