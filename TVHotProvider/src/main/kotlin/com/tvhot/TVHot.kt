package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    override var mainUrl = "https://tvhot.store"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    // 메인 페이지에 표시할 카테고리 정의
    override val mainPage = mainPageOf(
        "/drama" to "드라마",
        "/movie" to "영화",
        "/kor_movie" to "한국영화",
        "/ent" to "예능",
        "/sisa" to "시사/교양",
        "/old_drama" to "추억의 드라마",
        "/old_ent" to "추억의 예능",
        "/world" to "해외 드라마",
        "/ott_ent" to "해외 예능",
        "/animation" to "애니메이션",
        "/ani_movie" to "애니메이션 영화"
    )

    private fun Element.toSearchResponse(): SearchResponse? {
        val onClick = this.attr("onclick")
        val link = Regex("location\\.href='(.*?)'").find(onClick)?.groupValues?.get(1) ?: return null
        val titleElement = this.selectFirst("div.title")
        val title = titleElement?.text()?.trim() ?: return null
        
        // 이미지 추출 (다양한 선택자 시도)
        val poster = this.selectFirst("div.img img, img.thumb, img[src*='file']")?.attr("src")
        
        // URL을 기반으로 타입 판단
        val type = determineTypeFromUrl(link)
        
        return when (type) {
            TvType.Movie -> newMovieSearchResponse(title, fixUrl(link), TvType.Movie) {
                this.posterUrl = fixUrl(poster ?: "")
            }
            TvType.AnimeMovie -> newMovieSearchResponse(title, fixUrl(link), TvType.AnimeMovie) {
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

    // URL을 기반으로 콘텐츠 타입 판단
    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            url.contains("/drama") || url.contains("/old_drama") || 
            url.contains("/ent") || url.contains("/sisa") || 
            url.contains("/old_ent") || url.contains("/world") || 
            url.contains("/ott_ent") -> TvType.TvSeries
            else -> TvType.TvSeries // 기본값
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 각 카테고리별 페이지 요청 (페이지네이션 지원)
        val categoryPath = request.data
        val url = if (page == 1) {
            "$mainUrl$categoryPath"
        } else {
            "$mainUrl$categoryPath?page=$page"
        }
        
        val doc = app.get(url).document
        
        // 카테고리 페이지에서 아이템 추출
        val items = doc.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
        
        // 페이지네이션 확인: 다음 페이지 버튼이 있는지 확인
        val hasNext = doc.select("a.pg_end, a.next, li.next a").isNotEmpty() ||
                     doc.select("a:contains(다음)").isNotEmpty()
        
        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = hasNext
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?stx=$query").document
        
        // 검색 결과 페이지의 아이템 추출
        return doc.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        
        // 1. 우선적으로 이미지의 alt 속성에서 시리즈 전체 제목 추출
        var title = doc.selectFirst("div.tmdb-card-top img")?.attr("alt")?.trim()
        
        // 2. alt 속성이 없으면 기존 방식으로 폴백
        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("h2#bo_v_title .bo_v_tit")?.text()?.trim() ?: "Unknown"
        }
        
        // 3. 회차 정보 제거 안전장치 (회/화/부 포함)
        title = title?.replace(Regex("\\s*\\d+\\s*(?:회|화|부)\\s*"), "")?.trim()
        title = title?.replace(Regex("\\s*에피소드\\s*\\d+\\s*"), "")?.trim()
        title = title?.replace(Regex("\\s*\\(\\s*(?:제?\\s*)?\\d+\\s*(?:회|화|부)\\s*\\)"), "")?.trim()
        
        // 4. 앞뒤 공백 정리
        title = title?.trim() ?: "Unknown"
        
        // 에피소드 리스트 추출
        val episodes = doc.select("div#other_list ul li").mapNotNull {
            val aTag = it.selectFirst("a") ?: return@mapNotNull null
            val href = aTag.attr("href")
            val epTitle = aTag.selectFirst(".title")?.text()?.trim() ?: "Episode"
            
            newEpisode(fixUrl(href)) {
                this.name = epTitle
            }
        }

        // URL을 기반으로 타입 결정
        val type = determineTypeFromUrl(url)
        
        // 타입별로 다른 LoadResponse 반환
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                // 영화의 경우 첫 번째 링크 사용
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) {
                    this.posterUrl = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
                    this.plot = doc.selectFirst(".tmdb-overview")?.text()
                }
            }
            TvType.Anime -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
                    this.plot = doc.selectFirst(".tmdb-overview")?.text()
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
                    this.plot = doc.selectFirst(".tmdb-overview")?.text()
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
        val doc = app.get(data).document
        val playerUrl = doc.selectFirst("iframe#view_iframe")?.attr("data-player1") ?: return false
        
        // BunnyPoorCdn을 불러와서 실행
        BunnyPoorCdn().getUrl(playerUrl, mainUrl, subtitleCallback, callback)
        return true
    }
}
