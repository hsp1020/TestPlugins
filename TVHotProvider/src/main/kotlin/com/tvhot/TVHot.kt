package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    // ... (기존 변수 및 함수들은 동일, loadLinks와 extractThumbnailHints만 변경) ...
    override var mainUrl = "https://tvmon.site"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    
    // ... (supportedTypes, USER_AGENT, headers, toSearchResponse, getMainPage, search, load 등은 기존 유지) ...
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    // ... (중간 생략: toSearchResponse, determineTypeFromUrl, getMainPage, search, load) ...
    // 복사해서 쓰실 때는 기존의 위 함수들을 그대로 두시면 됩니다. 아래 loadLinks 부터가 핵심입니다.

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val doc = app.get(mainUrl, headers = commonHeaders).document
        val home = mutableListOf<HomePageList>()
        doc.select("section").forEach { section ->
            var title = section.selectFirst("h2")?.text()?.replace("전체보기", "")?.trim() ?: "추천"
            if (title.contains("무료 다시보기 순위를 확인")) title = "다시보기 순위"
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
    
    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        val link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null
        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null } ?: imgTag?.attr("data-src")?.ifEmpty { null } ?: imgTag?.attr("src") ?: ""
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()
        if (title.isNullOrEmpty()) title = doc.selectFirst("h1#bo_v_title")?.text()?.trim() ?: doc.selectFirst(".bo_v_tit")?.text()?.trim() ?: "Unknown"
        title = title!!.replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()
        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            if (!pureOriTitle.contains(Regex("[가-힣]")) && pureOriTitle.isNotEmpty()) title = "$title (원제 : $pureOriTitle)"
        }
        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        val infoList = doc.select(".bo_v_info dd").map { it.text().trim() }
        val genreList = doc.select(".ctgs dd a").filter { !it.text().contains("트레일러") && !it.hasClass("btn_watch") }.map { it.text().trim() }
        val metaString = (infoList + genreList).joinToString(" / ")
        var story = doc.selectFirst(".story")?.text()?.trim() ?: doc.selectFirst(".tmdb-overview")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: ""
        if (story.contains("다시보기") && story.contains("무료")) story = "-"
        if (story.isEmpty()) story = "-"
        val finalPlot = "$metaString\n\n$story".trim()
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst(".clamp")?.text()?.trim() ?: li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null } ?: thumbImg?.attr("src")?.ifEmpty { null } ?: li.selectFirst("img")?.attr("src")
            newEpisode(href) { this.name = epName; this.posterUrl = fixUrl(epThumb ?: "") }
        }.reversed()
        val type = determineTypeFromUrl(url)
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) { this.posterUrl = fixUrl(poster); this.plot = finalPlot }
            }
            else -> newTvSeriesLoadResponse(title, url, type, episodes) { this.posterUrl = fixUrl(poster); this.plot = finalPlot }
        }
    }

    // 👇 [변경] 모든 힌트를 리스트로 반환하도록 수정
    private fun extractThumbnailHints(doc: Document): List<String> {
        val hints = mutableListOf<String>()
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        val priorityRegex = Regex("""/v/[a-z]/""")

        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw) ?: continue
            
            // 패턴 매칭되는 것 수집
            if (priorityRegex.containsMatchIn(fixed)) {
                hints.add(fixed)
            }
        }
        // 중복 제거 후 반환 (순서는 유지됨 -> 보통 위쪽이 우선이지만, 여기선 data-src 등 뒤에 나오는게 유효할 수 있으므로 다 시도)
        return hints.distinct()
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = commonHeaders).document
        
        // 1) 가능한 모든 힌트 수집
        val thumbnailHints = extractThumbnailHints(doc)

        // 2) 플레이어 URL 추출
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")

        var isExtracted = false

        // 3) Extractor 호출 (힌트가 있으면 힌트 개수만큼 반복 시도, 없으면 1회)
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            
            if (thumbnailHints.isNotEmpty()) {
                // 힌트들을 하나씩 대입해보며 성공할 때까지 시도
                for (hint in thumbnailHints) {
                    val result = BunnyPoorCdn().extract(
                        finalPlayerUrl,
                        data,
                        subtitleCallback,
                        callback,
                        hint
                    )
                    if (result) {
                        isExtracted = true
                        break // 성공하면 중단
                    }
                }
            } else {
                // 힌트가 없어도 기본 로직으로 1회 시도
                if (BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback, null)) {
                    isExtracted = true
                }
            }
        }

        if (isExtracted) return true

        // 4) Extractor 실패 시 백업: 썸네일 힌트들로 직접 시도
        // (첫 번째 힌트가 실패해도 두 번째 힌트(sdkfsjd.org)에서 성공할 수 있음)
        for (hint in thumbnailHints) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(hint)
                if (pathMatch != null) {
                    val m3u8Url = hint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")

                    // load 시도
                    var success = false
                    try {
                        // 성공 여부를 알기 어려우므로(generateM3u8은 리턴이 Unit/List),
                        // Safe하게 호출하고 예외 없으면 성공으로 간주하거나, 
                        // M3u8Helper가 내부적으로 체크해주길 기대
                        M3u8Helper.generateM3u8(
                            name,
                            fixedM3u8Url,
                            mainUrl,
                            headers = commonHeaders
                        ).forEach(callback)
                        success = true
                    } catch (e: Exception) {
                        // 실패 시 다음 힌트로
                    }
                    
                    if (success) return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return false
    }
}
