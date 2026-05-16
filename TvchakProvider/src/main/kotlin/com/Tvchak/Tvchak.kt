package com.lagradost.cloudstream3.plugins.tvchak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        
        val finalHost = URI(response.url).host
        if (finalHost != null && finalHost !in mainUrl) {
            mainUrl = "https://$finalHost"
        }
        
        val items = response.document.select(".stui-vodlist__box, ul.stui-vodlist li, .vodlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, .thumb a") ?: return@mapNotNull null
            
            val title = anchor.attr("title").takeIf { it.isNotBlank() } ?: anchor.text()
            val link = fixUrl(anchor.attr("href"))
            val poster = fixUrl(anchor.attr("data-original").takeIf { it.isNotBlank() } ?: anchor.attr("src"))
            
            newTvSeriesSearchResponse(title, "$link||$poster||$title", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/index.php/vod/search/wd/$query.html").document
        
        return document.select("ul.stui-vodlist__media li, .stui-vodlist__box, ul.stui-vodlist li, .searchlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, a.thumb") ?: return@mapNotNull null
            
            val title = anchor.attr("title").takeIf { it.isNotBlank() } 
                ?: (it.selectFirst(".title, h3, h4")?.text() ?: anchor.text())
            val link = fixUrl(anchor.attr("href"))
            val poster = fixUrl(anchor.attr("data-original").takeIf { it.isNotBlank() } ?: anchor.attr("src"))
            
            newTvSeriesSearchResponse(title, "$link||$poster||$title", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val payload = url.split("||")
        val targetUrl = payload[0]
        val injectedPoster = payload.getOrNull(1)
        val injectedTitle = payload.getOrNull(2) ?: "Unknown"

        val document = app.get(targetUrl).document
        val title = document.selectFirst(".title, h1, .stui-content__title")?.text() ?: injectedTitle
        
        val episodes = document.select(".stui-content__playlist li a, .playlist li a").map {
            newEpisode(fixUrl(it.attr("href"))) {
                name = it.text().trim()
            }
        }

        return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
            posterUrl = injectedPoster ?: document.selectFirst(".picture img")?.attr("src")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val html = response.text
        
        // 1. CDN 링크 찾기
        val cdnRegex = """(https?://[\w\-.]+\.b-cdn\.net/[^"'\s<>]+)""".toRegex(RegexOption.IGNORE_CASE)
        cdnRegex.find(html)?.let { match ->
            val videoUrl = match.groupValues[1]
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(name, videoUrl, mainUrl).forEach(callback)
            } else {
                // 람다 블록에서 referer, quality, isM3u8 설정
                callback.invoke(
                    newExtractorLink(name, "Direct HD", videoUrl) {
                        referer = mainUrl
                        quality = Qualities.P1080.value
                        isM3u8 = false
                    }
                )
            }
            return true
        }
        
        // 2. player_data JSON 찾기
        val playerDataMatch = """var\s+player_data\s*=\s*(\{.*?\})""".toRegex().find(html)
        if (playerDataMatch != null) {
            runCatching {
                // parseJson은 String의 확장 함수로 호출
                val json = playerDataMatch.groupValues[1].parseJson<Map<String, String>>()
                val videoUrl = json["url"] ?: return@runCatching
                
                if (videoUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(name, videoUrl, mainUrl).forEach(callback)
                } else {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }
        
        // 3. iframe fallback
        response.document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
