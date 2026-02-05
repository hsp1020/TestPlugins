package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    override var mainUrl = "https://tvmon.site"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    
    // Variety 타입 제거 -> TvSeries로 통합
    override val supportedTypes = setOf(
        TvType.TvSeries, 
        TvType.Movie, 
        TvType.AsianDrama, 
        TvType.Anime, 
        TvType.AnimeMovie
    )

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        val link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null
        
        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val type = determineTypeFromUrl(link)
        
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, link, type) { this.posterUrl = fixUrl(poster) }
            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) { this.posterUrl = fixUrl(poster) }
            else -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = fixUrl(poster) }
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            url.contains("/ent") || url.contains("/old_ent") -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val doc = app.get(mainUrl, headers = commonHeaders).document
        val home = mutableListOf<HomePageList>()
        
        doc.select("section").forEach { section ->
            val title = section.selectFirst("h2")?.text()?.replace("전체보기", "")?.trim() ?: "추천"
            val listItems = section.select(".owl-carousel .item").mapNotNull { it.toSearchResponse() }
            if (listItems.isNotEmpty()) home.add(HomePageList(title, listItems))
        }
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        return doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document
        
        var title = doc.selectFirst("h1#bo_v_title")?.text()?.trim() 
            ?: doc.selectFirst(".bo_v_tit")?.text()?.trim() 
            ?: "Unknown"
        title = title.replace(" 다시보기", "").trim()
        
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".bo_v_file img")?.attr("src") 
            ?: ""
            
        val plot = doc.selectFirst("meta[name='description']")?.attr("content")

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = aTag.selectFirst("img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { thumbImg.attr("src") }

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed()

        val type = determineTypeFromUrl(url)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = plot
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = fixUrl(poster)
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
        val doc = app.get(data, headers = commonHeaders).document
        
        // 1. Iframe에서 플레이어 주소 추출
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")
        
        // 2. 현재 페이지의 썸네일에서 직접 CDN 정보 추출 (백업용)
        val thumbUrl = doc.selectFirst("#other_list ul li.current img")?.let { 
            it.attr("data-src").ifEmpty { it.attr("src") }
        } ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        // 3. 우선 Extractor 시도
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            // 여기서 getUrl 대신 extract 호출
            val extracted = BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback)
            if (extracted) return true
        }

        // 4. Extractor 실패 시 썸네일 정보를 기반으로 직접 주소 생성 시도
        if (thumbUrl != null && thumbUrl.contains("/v/")) {
            try {
                // thumbUrl 예: https://domain.com//v/f/VIDEO_ID/thumb.png
                // m3u8 주소 변환: https://domain.com/v/f/VIDEO_ID/index.m3u8
                val m3u8Url = thumbUrl.substringBeforeLast("/") + "/index.m3u8"
                // 더블 슬래시(//v/) 보정
                val fixedM3u8Url = m3u8Url.replace("//v/", "/v/")
                
                M3u8Helper.generateM3u8(
                    "TVHot Backup",
                    fixedM3u8Url,
                    data,
                    headers = commonHeaders
                ).forEach(callback)
                return true
            } catch (e: Exception) {
                return false
            }
        }

        return false
    }
}
