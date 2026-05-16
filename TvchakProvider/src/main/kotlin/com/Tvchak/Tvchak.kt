package com.lagradost.cloudstream3.plugins.tvchak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import java.net.URI

class TvchakProvider : MainAPI() {
    override var mainUrl = "https://tvchak200.com"
    override var name = "Tvchak"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.Anime)

    override val mainPage = mainPageOf(
        "1" to "영화",
        "2" to "드라마",
        "3" to "예능",
        "4" to "애니"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val response = app.get("$mainUrl/index.php/vod/type/id/${request.data}/page/$page.html")
        
        // 동적 도메인 감지 및 원자적 갱신
        val finalHost = URI(response.url).host
        if (finalHost != null && finalHost !in mainUrl) {
            mainUrl = "https://$finalHost"
        }
        
        val items = response.document.select(".stui-vodlist__box, ul.stui-vodlist li, .vodlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, .thumb a") ?: return@mapNotNull null
            
            val titleAttr = anchor.attr("title")
            val title = if (titleAttr.isNotBlank()) titleAttr else anchor.text()
            val link = fixUrl(anchor.attr("href"))
            
            val originalAttr = anchor.attr("data-original")
            val poster = fixUrl(if (originalAttr.isNotBlank()) originalAttr else anchor.attr("src"))
            
            val payloadUrl = "$link||$poster||$title"
            
            newTvSeriesSearchResponse(title, payloadUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/index.php/vod/search/wd/$query.html").document
        
        return document.select("ul.stui-vodlist__media li, .stui-vodlist__box, ul.stui-vodlist li, .searchlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, a.thumb") ?: return@mapNotNull null
            
            val titleAttr = anchor.attr("title")
            val title = if (titleAttr.isNotBlank()) titleAttr else (it.selectFirst(".title, h3, h4")?.text() ?: anchor.text())
            val link = fixUrl(anchor.attr("href"))
            
            val originalAttr = anchor.attr("data-original")
            val poster = fixUrl(if (originalAttr.isNotBlank()) originalAttr else anchor.attr("src"))
            
            newTvSeriesSearchResponse(title, "$link||$poster||$title", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = url.split("||")
        val targetUrl = payload.first()
        val injectedPoster = payload.getOrNull(1)
        val injectedTitle = payload.getOrNull(2) ?: "Unknown"

        val document = app.get(targetUrl).document
        val title = document.selectFirst(".title, h1, .stui-content__title")?.text() ?: injectedTitle
        
        val episodes = document.select(".stui-content__playlist li a, .playlist li a").map {
            // Deprecation 대응: newEpisode 빌더 패턴 사용
            newEpisode(data = fixUrl(it.attr("href"))) {
                this.name = it.text().trim()
            }
        }

        return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
            this.posterUrl = injectedPoster ?: document.selectFirst(".picture img")?.attr("src")
        }
    }

    // 시그니처 강제 대응: subtitleCallback이 callback보다 선행
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text
        
        val cdnRegex = """(https?://[\w\-.]+\.b-cdn\.net/[^"'\s<>]+)""".toRegex(RegexOption.IGNORE_CASE)
        val match = cdnRegex.find(html)
        
        if (match != null) {
            val videoUrl = match.groupValues[1]
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(this.name, videoUrl, mainUrl).forEach(callback)
            } else {
                // Deprecation 대응: newExtractorLink 팩토리 메서드 사용
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "Direct HD",
                        url = videoUrl,
                        referer = mainUrl,
                        quality = Qualities.P1080.value,
                        isM3u8 = false
                    )
                )
            }
            return true
        }
        
        val playerDataMatch = """var\s+player_data\s*=\s*(\{.*?\})""".toRegex().find(html)
        if (playerDataMatch != null) {
            runCatching {
                val json = parseJson<Map<String, String>>(playerDataMatch.groupValues[1])
                val videoUrl = json["url"] ?: return@runCatching
                
                if (videoUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(this.name, videoUrl, mainUrl).forEach(callback)
                } else {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }
        
        // document 객체를 응답에서 직접 추출하여 select 연산 수행
        response.document.select("iframe").map { it.attr("src") }.forEach { src ->
            if (src.isNotBlank()) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
