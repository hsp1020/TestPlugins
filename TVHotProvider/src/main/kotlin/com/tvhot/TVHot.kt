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
        // 메인/검색 화면에서는 a.title 사용
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
            
            // 요청사항: 메인화면 타이틀 변경
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
        
        // 1. 제목 추출 (요청사항: 상세페이지 제목만 깔끔하게)
        // h3 태그 바로 아래의 텍스트만 가져옴 (span.ori_title 제외)
        var title = doc.selectFirst("#bo_v_movinfo h3")?.ownText()?.trim()
        
        // 백업: 만약 위 구조가 아니면 기존 방식 시도
        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("h1#bo_v_title")?.text()?.trim() 
                ?: doc.selectFirst(".bo_v_tit")?.text()?.trim() 
                ?: "Unknown"
            // "37화", "다시보기" 등 제거
            title = title!!.replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()
        }

        // 2. 포스터 추출 (요청사항: 상세페이지 포스터 로드)
        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // 3. 줄거리 및 정보 추출 (요청사항: 정보 한줄 + 설명)
        // 정보: 국가, 언어, 개봉년도
        val infoText = doc.select(".bo_v_info dd").joinToString(" ") { it.text() }
        
        // 장르 (트레일러 보기 제외)
        val genreText = doc.select(".ctgs dd a").filter { 
            !it.text().contains("트레일러") && !it.hasClass("btn_watch") 
        }.joinToString(", ") { it.text() }
        
        // 줄거리 본문
        val story = doc.selectFirst(".story")?.text()?.trim() 
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content") 
            ?: ""

        val finalPlot = "$infoText\n$genreText\n\n$story".trim()

        // 4. 에피소드 리스트 추출 (요청사항: 썸네일 긁어오기)
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            
            // 에피소드 제목 (clamp div 안의 텍스트)
            val epName = li.selectFirst(".clamp")?.text()?.trim() 
                ?: li.selectFirst("a.title")?.text()?.trim() 
                ?: "Episode"
            
            // 썸네일 (요청사항: thumb.png 링크)
            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed() // 최신화가 위에 있으므로 역순 정렬 (1화부터 정렬)

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
        
        // [전략 1] Iframe URL (data-player1) 추출 후 Extractor 실행
        // TVMon은 data-player1에 실제 플레이어 주소가 있음
        val iframe = doc.selectFirst("iframe#view_iframe")
        val playerUrl = iframe?.attr("data-player1")?.ifEmpty { null }
            ?: iframe?.attr("data-player2")?.ifEmpty { null }
            ?: iframe?.attr("src")
        
        if (playerUrl != null) {
            val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
            // Extractor 호출 (성공 시 true 반환)
            val extracted = BunnyPoorCdn().extract(finalPlayerUrl, data, subtitleCallback, callback)
            if (extracted) return true
        }

        // [전략 2] Extractor 실패 시 백업: 페이지 내 썸네일 이미지에서 m3u8 경로 직접 유추
        // 소스코드: <img src=".../v/f/ID/thumb.png"> -> .../v/f/ID/index.m3u8 변환
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        for (el in videoThumbElements) {
            val src = el.attr("src").ifEmpty { el.attr("data-src") }
            if (src.contains("/v/f/") || src.contains("/v/e/")) {
                try {
                    // 예: https://img.domain.com//v/f/VIDEO_ID/thumb.png
                    // 목표: https://img.domain.com/v/f/VIDEO_ID/index.m3u8
                    
                    // 마지막 '/' 이전까지만 자르고 index.m3u8 붙이기
                    val basePath = src.substringBeforeLast("/")
                    val m3u8Url = "$basePath/index.m3u8"
                    
                    // 더블 슬래시 보정 (//v/ -> /v/)
                    val fixedM3u8Url = m3u8Url.replace("//v/", "/v/")
                    
                    M3u8Helper.generateM3u8(
                        name,
                        fixedM3u8Url,
                        mainUrl, // referer
                        headers = commonHeaders
                    ).forEach(callback)
                    
                    return true // 하나라도 찾으면 성공 처리
                } catch (e: Exception) {
                    continue
                }
            }
        }

        return false
    }
}
