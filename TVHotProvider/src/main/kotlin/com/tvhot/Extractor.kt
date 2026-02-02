package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    override var mainUrl = "https://tvhot.store"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    private fun Element.toSearchResponse(): SearchResponse? {
        val onClick = this.attr("onclick")
        val link = Regex("location\\.href='(.*?)'").find(onClick)?.groupValues?.get(1) ?: return null
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("div.img img")?.attr("src")

        return newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) {
            this.posterUrl = fixUrl(poster ?: "")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()
        doc.select("div.mov_type").forEach { section ->
            val title = section.selectFirst("h2 strong")?.text()?.trim()?.replace(" 다시보기", "") ?: "추천"
            val listItems = section.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
            if (listItems.isNotEmpty()) home.add(HomePageList(title, listItems))
        }
        return newHomePageResponse(home)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?stx=$query").document
        return doc.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h2#bo_v_title .bo_v_tit")?.text()?.trim() ?: "Unknown"
        
        val episodes = doc.select("div#other_list ul li").mapNotNull {
            val aTag = it.selectFirst("a") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val epTitle = aTag.selectFirst(".title")?.text()?.trim() ?: "Episode"
            
            // Episode(...) 대신 newEpisode(...) 사용
            newEpisode(fixUrl(href)) {
                this.name = epTitle
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
            this.plot = doc.selectFirst(".tmdb-overview")?.text()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        val playerUrl = doc.selectFirst("iframe#view_iframe")?.attr("data-player1") ?: return false
        BunnyPoorCdn().getUrl(playerUrl, mainUrl, subtitleCallback, callback)
        return true
    }
}
