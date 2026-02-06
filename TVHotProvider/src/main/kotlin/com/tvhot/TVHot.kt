package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
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

    // [핵심 변경] 모바일 UA -> 윈도우 UA로 변경 (BunnyPoorCdn과 일치시킴)
    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    // 메인 화면에 띄울 카테고리 목록 정의 (URL path, 화면 표시 제목)
    // 요청하신 nav 구조에 따라 분류
    private val mainCategories = listOf(
        Pair("movie", "영화"),
        Pair("kor_movie", "한국영화"),
        Pair("drama", "드라마"),
        Pair("ent", "예능프로그램"),
        Pair("sisa", "시사/다큐"),
        Pair("world", "해외드라마"),
        Pair("ott_ent", "해외(예능/다큐)"),
        Pair("ani_movie", "극장판 애니메이션"),
        Pair("animation", "일반 애니메이션"),
        Pair("old_ent", "추억의 예능"),
        Pair("old_drama", "추억의 드라마")
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
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, link, type) {
                this.posterUrl = fixUrl(poster)
            }
            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) {
                this.posterUrl = fixUrl(poster)
            }
            else -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) {
                this.posterUrl = fixUrl(poster)
            }
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
        val home = mutableListOf<HomePageList>()

        // 각 카테고리별로 순회
        mainCategories.forEach { (path, name) ->
            // 1페이지와 2페이지 데이터를 수집 (병렬 처리 없이 순차 처리 예시, 필요시 apmap 사용 가능)
            val listItems = mutableListOf<SearchResponse>()
            
            // 1~2 페이지 반복
            for (p in 1..2) {
                try {
                    val targetUrl = "$mainUrl/$path?page=$p"
                    val doc = app.get(targetUrl, headers = commonHeaders).document
                    
                    // 리스트 셀렉터: 실제 사이트 구조에 맞춰 수정 (보통 ul.list-body li 또는 div.list-box 등)
                    // 기존 search 코드 참고: ul#mov_con_list li 가 검색결과이므로
                    // 카테고리 페이지도 유사 구조일 것으로 추정하여 #mov_con_list 또는 일반 리스트 셀렉터 사용
                    // 여기서는 안전하게 공통적인 구조 확인이 필요하나, 기존 코드 기반으로 추정
                    
                    // 카테고리 목록 페이지의 아이템들이 검색 결과 페이지와 구조가 같다면 아래 셀렉터 사용
                    val items = doc.select("ul#mov_con_list li, .list-box li").mapNotNull { it.toSearchResponse() }
                    listItems.addAll(items)
                } catch (e: Exception) {
                    // 페이지 로드 실패시 무시하고 다음 진행
                    e.printStackTrace()
                }
            }

            if (listItems.isNotEmpty()) {
                // 중복 제거 (혹시 모를 중복 방지)
                val distinctItems = listItems.distinctBy { it.url }
                home.add(HomePageList(name, distinctItems))
            }
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
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim()
                ?: "Unknown"
        }
        title = title!!.replace(Regex("\\\\\\\\\\\\\\\\s*\\\\\\\\\\\\\\\\d+\\\\\\\\\\\\\\\\s*[화회부].*"), "")
            .replace(" 다시보기", "").trim()

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

        val genreFormatted = if (genreList.isNotEmpty()) {
            "장르: ${genreList.joinToString(", ")}"
        } else {
            ""
        }

        val metaParts = mutableListOf<String>()
        if (infoList.isNotEmpty()) {
            metaParts.add(infoList.joinToString(" / "))
        }
        if (genreFormatted.isNotEmpty()) {
            metaParts.add(genreFormatted)
        }
        val metaString = metaParts.joinToString(" / ")

        var story = doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val finalPlot = if (story == "다시보기") {
            "다시보기"
        } else {
            "$metaString / 줄거리: $story".trim()
        }

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

        val thumbnailHint = extractThumbnailHint(doc)

        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")

        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            val extracted = BunnyPoorCdn().extract(
                finalPlayerUrl,
                data,
                subtitleCallback,
                callback,
                thumbnailHint
            )
            if (extracted) return true
        }

        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)

                if (pathMatch != null) {
                    val m3u8Url =
                        thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")

                    callback(
                        newExtractorLink(name, name, fixedM3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = commonHeaders
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        val priorityRegex = Regex("""/v/[a-z]/""")

        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw)
            if (priorityRegex.containsMatchIn(fixed)) {
                return fixed
            }
        }
        return null
    }
}
