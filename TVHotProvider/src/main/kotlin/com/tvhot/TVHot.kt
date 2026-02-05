package com.tvhot

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    // TVMon 주소로 변경
    override var mainUrl = "https://tvmon.site"
    override var name = "TVHot" // 기존 이름 유지
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie, TvType.Variety)

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    // TVMon의 메인/검색 결과 아이템을 CloudStream 객체로 변환
    private fun Element.toSearchResponse(): SearchResponse? {
        // 메인화면 (.item) 및 검색화면 (li .box) 공통 처리
        
        // 1. 링크와 포스터 추출 (a 태그 중 class="img" 인 것)
        val imgTag = this.selectFirst("a.img") ?: return null
        val link = fixUrl(imgTag.attr("href"))
        val poster = fixUrl(imgTag.selectFirst("img")?.attr("src") ?: "")
        
        // 2. 제목 추출
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null
        
        // 3. 타입 결정
        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, link, type) { this.posterUrl = poster }
            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) { this.posterUrl = poster }
            else -> newAnimeSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = poster } // 드라마/예능은 TvSeries로 처리
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            url.contains("/ent") || url.contains("/old_ent") -> TvType.Variety
            else -> TvType.TvSeries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        
        val doc = app.get(mainUrl, headers = commonHeaders).document
        val home = mutableListOf<HomePageList>()

        // TVMon 메인 페이지의 섹션 파싱 (<section> 태그 사용)
        doc.select("section").forEach { section ->
            // 섹션 제목 추출 (h2 텍스트)
            val titleText = section.selectFirst("h2")?.text()?.replace("전체보기", "")?.trim() ?: "추천"
            
            // OwlCarousel 아이템 추출
            val listItems = section.select(".owl-carousel .item").mapNotNull { it.toSearchResponse() }
            
            if (listItems.isNotEmpty()) {
                home.add(HomePageList(titleText, listItems))
            }
        }
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        // 검색 결과는 ul#mov_con_list 아래 li 태그들임
        return doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        // 제목 파싱
        var title = doc.selectFirst("h1#bo_v_title")?.text()?.trim() 
            ?: doc.selectFirst(".bo_v_tit")?.text()?.trim() 
            ?: "Unknown"
        
        // "다시보기" 등 불필요한 접미사 제거
        title = title.replace(" 다시보기", "").trim()

        // 포스터 파싱 (상세 페이지에 포스터가 없을 경우 OpenGraph 태그 사용)
        val poster = doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: doc.selectFirst(".img-container img")?.attr("data-src") // 에피소드 썸네일 fallback
            ?: ""

        // 줄거리 파싱
        val plot = doc.selectFirst(".bo_v_con")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content")

        // 에피소드 파싱
        // TVMon은 #other_list ul li.searchText 구조를 가짐
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val linkTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(linkTag.attr("href"))
            
            val epTitle = li.selectFirst("a.title")?.text()?.trim() 
                ?: li.selectFirst(".ep-item-title")?.text()?.trim()
                ?: "Episode"
            
            // 썸네일
            val thumb = li.selectFirst("img")?.let { 
                it.attr("data-src").ifEmpty { it.attr("src") } 
            }

            newEpisode(href) {
                this.name = epTitle
                this.posterUrl = fixUrl(thumb ?: "")
            }
        }.reversed() // 최신화가 위에 있으므로 역순 정렬 (필요 시 조정)

        val type = determineTypeFromUrl(url)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                // 영화의 경우 에피소드가 따로 없으면 현재 URL 사용
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
        val response = app.get(data, headers = commonHeaders).document
        
        // Iframe 찾기 (id="view_iframe")
        val iframe = response.selectFirst("iframe#view_iframe")
        
        // data-player1 또는 src 속성에서 URL 추출
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")
            ?: return false

        val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")

        // BunnyPoorCdn 추출기 호출
        BunnyPoorCdn().getUrl(finalPlayerUrl, "$mainUrl/", subtitleCallback, callback)
        return true
    }
}
