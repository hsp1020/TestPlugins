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
            var title = section.selectFirst("h2")?.text()?.replace("전체보기", "")?.trim() ?: "추천"
            
            if (title.contains("무료 다시보기 순위를 확인")) {
                title = "다시보기 순위"
            }

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
        
        // 1. 제목 추출 및 원제 처리
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("h1#bo_v_title")?.text()?.trim() 
                ?: doc.selectFirst(".bo_v_tit")?.text()?.trim() 
                ?: "Unknown"
        }
        
        title = title!!.replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        // 2. 포스터 추출
        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // 3. 상세 정보 구성 (국가 / 언어 / 개봉년도 / 장르)
        val infoList = doc.select(".bo_v_info dd").map { it.text().trim() }
        val genreList = doc.select(".ctgs dd a").filter { 
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch") 
        }.map { it.text().trim() }

        val metaString = (infoList + genreList).joinToString(" / ")

        // 줄거리 본문
        var story = doc.selectFirst(".story")?.text()?.trim() 
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content") 
            ?: ""

        // [요청사항 반영] "다시보기"와 "무료" 키워드가 모두 있으면 줄거리 내용을 비움 (SEO 텍스트 제거)
        if (story.contains("다시보기") && story.contains("무료")) {
            story = ""
        }

        // 최종 설명: 메타정보 + 줄바꿈 + 줄거리 (줄거리가 없으면 메타정보만 표시)
        val finalPlot = if (story.isNotEmpty()) "$metaString\n\n$story".trim() else metaString

        // 4. 에피소드 리스트 추출
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            
            val epName = li.selectFirst(".clamp")?.text()?.trim() 
                ?: li.selectFirst("a.title")?.text()?.trim() 
                ?: "Episode"
            
            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

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
                    this.plot = finalPlot
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, type, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = finalPlot
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
        
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")
        
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            val extracted = BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback)
            if (extracted) return true
        }

        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        for (el in videoThumbElements) {
            val src = el.attr("src").ifEmpty { el.attr("data-src") }
            if (src.contains("/v/f/") || src.contains("/v/e/")) {
                try {
                    val m3u8Url = src.substringBeforeLast("/") + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace("//v/", "/v/")
                    
                    M3u8Helper.generateM3u8(
                        name,
                        fixedM3u8Url,
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
