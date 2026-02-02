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

    private fun Element.toSearchResponse(): SearchResponse? {
        val onClick = this.attr("onclick")
        // 정규식 수정: location.href 패턴 추출
        val link = Regex("""location\.href='(.*?)'""").find(onClick)?.groupValues?.get(1) ?: return null
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("div.img img")?.attr("src")
        
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
        // ▼▼▼ 무한 스크롤 방지: 1페이지만 로드 ▼▼▼
        if (page > 1) {
            return newHomePageResponse(emptyList())
        }

        val doc = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()
        
        doc.select("div.mov_type").forEach { section ->
            var title = section.selectFirst("h2 strong")?.text()?.trim() ?: "추천 목록"
            title = title.replace("무료 ", "").replace(" 다시보기", "").trim()
            
            val listItems = section.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
            if (listItems.isNotEmpty()) home.add(HomePageList(title, listItems))
        }
        
        // hasNext = false로 설정
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?stx=$query").document
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
        
        // 3. 회차 정보 제거 (정규식 정리됨)
        // 숫자 + (회|화|부) 제거
        title = title?.replace(Regex("""\s*\d+\s*(?:회|화|부)\s*"""), "")?.trim()
        // "에피소드 숫자" 제거
        title = title?.replace(Regex("""\s*에피소드\s*\d+\s*"""), "")?.trim()
        // 괄호 안의 회차 정보 제거 (예: "(16화)", "(제3회)")
        title = title?.replace(Regex("""\s*\(\s*(?:제?\s*)?\d+\s*(?:회|화|부)\s*\)"""), "")?.trim()
        
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

        val type = determineTypeFromUrl(url)
        val poster = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
        val plotText = doc.selectFirst(".tmdb-overview")?.text()

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                // 영화의 경우 에피소드 리스트의 첫 번째 링크가 본편일 가능성이 높음
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) {
                    this.posterUrl = poster
                    this.plot = plotText
                }
            }
            TvType.Anime -> {
                newAnimeLoadResponse(title, url, TvType.Anime) {
                    this.posterUrl = poster
                    this.plot = plotText
                    addEpisodes(DubStatus.Subbed, episodes)
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = poster
                    this.plot = plotText
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
        // iframe 태그에서 data-player1 속성 추출
        val playerUrl = doc.selectFirst("iframe#view_iframe")?.attr("data-player1") ?: return false
        
        // BunnyPoorCdn을 불러와서 실행
        // loadLinks의 'data' 파라미터(현재 페이지 URL)를 Referer로 사용하기 위해 전달하는 것이 좋음
        BunnyPoorCdn().getUrl(playerUrl, mainUrl, subtitleCallback, callback)
        return true
    }
}
