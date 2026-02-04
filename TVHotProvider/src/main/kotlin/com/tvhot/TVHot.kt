package com.tvhot

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    override var mainUrl = "https://tvhot.store"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    private fun Element.toSearchResponse(): SearchResponse? {
        val onClick = this.attr("onclick")
        val link = Regex("location\\.href='(.*?)'").find(onClick)?.groupValues?.get(1) ?: return null
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("div.img img")?.attr("src")
        
        val type = determineTypeFromUrl(link)
        
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, fixUrl(link), type) {
                this.posterUrl = fixUrl(poster ?: "")
            }
            TvType.Anime -> newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) {
                this.posterUrl = fixUrl(poster ?: "")
            }
            else -> newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) {
                this.posterUrl = fixUrl(poster ?: "")
            }
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            else -> TvType.TvSeries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())

        val doc = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()
        
        doc.select("div.mov_type").forEach { section ->
            var title = section.selectFirst("h2 strong")?.text()?.trim() ?: "추천 목록"
            title = title.replace("무료 ", "").replace(" 다시보기", "").trim()
            
            val listItems = section.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
            
            if (listItems.isNotEmpty()) {
                home.add(HomePageList(title, listItems))
            }
        }
        
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?stx=$query").document
        return doc.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        var title = doc.selectFirst("div.tmdb-card-top img")?.attr("alt")?.trim()
        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("h2#bo_v_title .bo_v_tit")?.text()?.trim() ?: "Unknown"
        }
        
        title = title?.replace(Regex("\\s*\\d+\\s*(?:회|화|부)\\s*"), "")?.trim()
        title = title?.replace(Regex("\\s*에피소드\\s*\\d+\\s*"), "")?.trim()
        title = title?.replace(Regex("\\s*\\(\\s*(?:제?\\s*)?\\d+\\s*(?:회|화|부)\\s*\\)"), "")?.trim()
        title = title?.trim() ?: "Unknown"
        
        val episodes = doc.select("div#other_list ul li").mapNotNull {
            val aTag = it.selectFirst("a") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val epTitle = it.selectFirst(".title")?.text()?.trim() ?: "Episode"
            
            newEpisode(fixUrl(href)) {
                this.name = epTitle
            }
        }.reversed() // 보통 최신화가 위이므로 아래부터 재생되게 반전(선택)

        val type = determineTypeFromUrl(url)
        val poster = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
        val plot = doc.selectFirst(".tmdb-overview")?.text()

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
            TvType.Anime -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = plot
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val response = app.get(data)
        val doc = response.document
        
        // iframe#view_iframe 태그를 찾음
        val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
        
        // 중요: data-player1의 &amp;를 일반 &로 변환해야 파라미터가 정상 인식됨
        val playerUrl = iframe.attr("data-player1").replace("&amp;", "&")
        
        if (playerUrl.isEmpty()) return false
        
        // Extractor 호출 (data는 현재 에피소드 URL이며 Referer로 사용됨)
        BunnyPoorCdn().getUrl(fixUrl(playerUrl), data, subtitleCallback, callback)
        
        return true
    }
}
