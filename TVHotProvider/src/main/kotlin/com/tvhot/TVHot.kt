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

        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        val infoList = doc.select(".bo_v_info dd").map { it.text().trim() }
        val genreList = doc.select(".ctgs dd a").filter { 
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch") 
        }.map { it.text().trim() }

        val metaString = (infoList + genreList).joinToString(" / ")

        var story = doc.selectFirst(".story")?.text()?.trim() 
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content") 
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) {
            story = "-"
        }
        if (story.isEmpty()) {
            story = "-"
        }

        val finalPlot = "$metaString\n\n$story".trim()

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            
            val epName = li.selectFirst(".clamp")?.text()?.trim() 
                ?: li.selectFirst("a.title")?.text()?.trim() 
                ?: "Episode"
            
            // 304화 문제 해결: data-original 속성 확인 추가
            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-original")?.ifEmpty { null }
                ?: thumbImg?.attr("data-src")?.ifEmpty { null }
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
        
        // 1. Iframe 정보 추출
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")
        
        // 서버 번호 파싱 (예: ?s=4 -> 4)
        var serverNum = "9"
        if (playerUrl != null) {
            val sMatch = Regex("""[?&]s=(\d+)""").find(playerUrl)
            if (sMatch != null) {
                serverNum = sMatch.groupValues[1]
            }
        }

        // 2. Extractor (BunnyPoorCdn) 시도
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            val extracted = BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback)
            if (extracted) return true
        }

        // 3. 백업 전략: 페이지 내 썸네일에서 경로 추출
        // 304화 문제 해결: data-original 속성 스캔 추가
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/'], img[data-original*='/v/']")
        
        for (el in videoThumbElements) {
            // 속성 우선순위: data-original (304화) > data-src (305화) > src
            val src = el.attr("data-original").ifEmpty { 
                el.attr("data-src").ifEmpty { 
                    el.attr("src") 
                } 
            }

            // 경로 패턴 (/v/f/ 또는 /v/e/) 확인
            if (src.contains("/v/f/") || src.contains("/v/e/")) {
                try {
                    // src 예시: https://img-requset4...//v/f/ID/thumb.png
                    // 추출할 경로: /v/f/ID
                    val pathRegex = Regex("""(/v/[ef]/[a-zA-Z0-9]+)""")
                    val match = pathRegex.find(src) ?: continue
                    val path = match.value.replace("//", "/")
                    
                    // 도메인 생성 전략
                    // 전략 A: s 파라미터를 기반으로 everyX.poorcdn.com 도메인 생성 (가장 확실)
                    val targetDomain = "https://every$serverNum.poorcdn.com"
                    val targetUrl = "$targetDomain$path/index.m3u8"

                    M3u8Helper.generateM3u8(
                        name,
                        targetUrl,
                        mainUrl,
                        headers = commonHeaders
                    ).forEach(callback)

                    // 전략 B: 썸네일 도메인이 혹시 작동할 경우를 대비해 추가
                    val thumbDomainUrl = src.substringBeforeLast("/") + "/index.m3u8"
                    val fixedThumbUrl = thumbDomainUrl.replace("//v/", "/v/")
                    
                    if (!fixedThumbUrl.contains("poorcdn")) {
                         M3u8Helper.generateM3u8(
                            "$name Alt",
                            fixedThumbUrl,
                            mainUrl,
                            headers = commonHeaders
                        ).forEach(callback)
                    }

                    return true 
                } catch (e: Exception) {
                    continue
                }
            }
        }

        return false
    }
}
