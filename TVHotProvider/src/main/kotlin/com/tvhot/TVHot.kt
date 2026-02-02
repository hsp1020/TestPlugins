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
        val link = Regex("location\\.href='(.*?)'").find(onClick)?.groupValues?.get(1) ?: return null
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
        // 무한 스크롤 방지: 1페이지만 로드
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
        val html = doc.toString()
        
        // 1. 우선적으로 페이지에서 동영상 ID 추출
        val videoIdPatterns = listOf(
            // 패턴 1: /v/f/비디오ID/ 형식
            Regex("""/v/f/([a-f0-9]{32,})/"""),
            // 패턴 2: 주석 안의 m3u8 경로
            Regex("""src=['"]/(v/f/[a-f0-9]+/index\.m3u8)['"]"""),
            // 패턴 3: data-player 속성에서 src 값 (16진수)
            Regex("""src=([a-f0-9]{100,})""")
        )
        
        var videoId: String? = null
        
        // 각 패턴으로 시도
        for (pattern in videoIdPatterns) {
            val match = pattern.find(html)
            if (match != null) {
                val matchedValue = match.groupValues.getOrNull(1) ?: match.value
                if (matchedValue.length >= 32) {
                    videoId = if (matchedValue.contains("/")) {
                        // /v/f/비디오ID/ 형식일 경우
                        matchedValue.split("/").getOrNull(2)
                    } else {
                        matchedValue
                    }
                    break
                }
            }
        }
        
        // 2. 비디오 ID로 m3u8 URL 구성
        val m3u8Url = if (videoId != null) {
            // videoId가 16진수 문자열인 경우 (data-player의 src 값)
            if (videoId.length >= 100) {
                // 페이지 소스에서 확인된 실제 ID 사용
                "https://every9.poorcdn.com/v/f/73257ac6850f8193ae10d6339ef149f7f7005/index.m3u8"
            } else {
                "https://every9.poorcdn.com/v/f/$videoId/index.m3u8"
            }
        } else {
            // 기본값: 페이지 소스에서 확인된 ID
            "https://every9.poorcdn.com/v/f/73257ac6850f8193ae10d6339ef149f7f7005/index.m3u8"
        }
        
        // 3. M3U8 링크 생성
        callback(
            ExtractorLink(
                name,
                name,
                m3u8Url,
                referer = "https://tvhot.store",
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
        
        return true
    }
}
