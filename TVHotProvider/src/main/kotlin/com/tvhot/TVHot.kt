package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
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

    private val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

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

            // 메인화면 타이틀 변경 요청 반영
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
        var title = h3Element?.ownText()?.trim() // <h3> 바로 밑의 텍스트만 가져옴 (span 제외)
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim() // 원제 : ...

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("h1#bo_v_title")?.text()?.trim()
                ?: doc.selectFirst(".bo_v_tit")?.text()?.trim()
                ?: "Unknown"
        }

        // 불필요한 접미사 제거
        title = title!!.replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()

        // 원제 처리 로직
        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            // 한국어가 포함되어 있는지 확인
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

        // 장르
        val genreList = doc.select(".ctgs dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim() }

        // 구분자 " / " 로 합치기
        val metaString = (infoList + genreList).joinToString(" / ")

        // 4. 줄거리 본문 추출 및 필터링
        var story = doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        // [요청사항] "다시보기"와 "무료"가 모두 포함되면 SEO 텍스트로 간주하여 "-"로 변경
        if (story.contains("다시보기") && story.contains("무료")) {
            story = "-"
        }

        // 내용이 없는 경우에도 "-"로 표시
        if (story.isEmpty()) {
            story = "-"
        }

        // 최종 설명: 메타정보 + 줄바꿈 + 줄거리
        val finalPlot = "$metaString\n\n$story".trim()

        // 5. 에피소드 리스트 추출
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))

            // 에피소드 제목
            val epName = li.selectFirst(".clamp")?.text()?.trim()
                ?: li.selectFirst("a.title")?.text()?.trim()
                ?: "Episode"

            // 썸네일
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

        // 1) 썸네일에서 힌트 추출 (도메인/경로 추정용)
        val thumbnailHint = extractThumbnailHint(doc)

        // 2) Iframe에서 플레이어 주소 추출
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")

        // 3) Extractor 호출 (썸네일 힌트를 도메인 결정 로직에 보조 입력으로 전달)
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

        // 4) Extractor 실패 시 백업: 썸네일에서 직접 index.m3u8 유추
        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[ef]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)

                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace("//v/", "/v/")

                    M3u8Helper.generateM3u8(
                        name,
                        fixedM3u8Url,
                        mainUrl,
                        headers = commonHeaders
                    ).forEach(callback)
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        // /v/f/ 또는 /v/e/ 패턴을 포함한 이미지 찾기
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")

        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            if (raw.contains("/v/f/") || raw.contains("/v/e/")) {
                // 상대경로일 가능성까지 고려해 절대 URL로 정규화
                return fixUrl(raw)
            }
        }
        return null
    }
}
