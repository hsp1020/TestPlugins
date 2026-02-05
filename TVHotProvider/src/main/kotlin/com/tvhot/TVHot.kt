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
            // 메인화면에서 "무료 다시보기 순위를 확인하세요"를 "다시보기 순위"로 변경
            var title = section.selectFirst("h2")?.text()?.trim() ?: "추천"
            title = title.replace("무료 다시보기 순위를 확인하세요", "다시보기 순위")
                .replace("전체보기", "")
                .trim()
            
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
        
        // 1. 제목: #bo_v_movinfo h3에서 가져오기
        var title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
            ?: "Unknown"
        
        // 2. 포스터: #bo_v_poster img에서 가져오기
        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")?.let { fixUrl(it) }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.let { fixUrl(it) }
            ?: ""

        // 3. 설명: #bo_v_movinfo .story에서 가져오기 (트레일러 보기 제외)
        val plot = doc.selectFirst("#bo_v_movinfo .story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        // 4. 상세 정보 (국가, 언어, 개봉년도, 장르)
        val details = mutableListOf<String>()
        val infoElements = doc.select("#bo_v_movinfo .bo_v_info dd")
        infoElements.forEach { dd ->
            details.add(dd.text().trim())
        }
        
        // 장르 정보 추가
        val genres = doc.select("#bo_v_movinfo .ctgs dd").mapNotNull { it.text().trim() }
            .filter { it != "트레일러 보기" }
        
        val fullPlot = buildString {
            // 기본 정보 추가
            if (details.isNotEmpty()) {
                append(details.joinToString("\n"))
                appendLine()
            }
            // 장르 추가
            if (genres.isNotEmpty()) {
                appendLine(genres.joinToString(", "))
            }
            // 줄거리 추가
            plot?.let {
                if (isNotEmpty()) appendLine()
                append(it)
            }
        }.trim()

        // 5. 에피소드 목록 - thumb.png 썸네일에서 가져오기
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            
            // 에피소드 제목
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            
            // thumb.png 썸네일 가져오기
            val thumbImg = li.selectFirst(".img-container img.lazy")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { thumbImg.attr("src") }
                ?: thumbImg?.attr("data-original")

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
                    this.posterUrl = poster
                    this.plot = fullPlot
                    this.year = details.find { it.contains("개봉년도") }?.substringAfter(":")?.trim()?.substringBefore("-")?.toIntOrNull()
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = poster
                    this.plot = fullPlot
                    this.year = details.find { it.contains("개봉년도") }?.substringAfter(":")?.trim()?.substringBefore("-")?.toIntOrNull()
                    this.tags = genres
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
        
        // 2. Extractor (BunnyPoorCdn) 호출
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            val extracted = BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback)
            if (extracted) return true
        }

        // 3. Extractor 실패 시 백업: thumb.png에서 m3u8 경로 추출
        val videoThumbElements = doc.select("img[src*='thumb.png'], img[data-src*='thumb.png']")
        for (el in videoThumbElements) {
            val src = el.attr("src").ifEmpty { el.attr("data-src") }
            if (src.contains("/v/") && src.contains("thumb.png")) {
                // thumb.png를 index.m3u8로 변환
                val m3u8Url = src.replace("/thumb.png", "/index.m3u8")
                
                try {
                    M3u8Helper.generateM3u8(
                        name,
                        m3u8Url,
                        mainUrl,
                        headers = commonHeaders
                    ).forEach(callback)
                    return true
                } catch (e: Exception) {
                    continue
                }
            }
        }

        return false
    }
}
