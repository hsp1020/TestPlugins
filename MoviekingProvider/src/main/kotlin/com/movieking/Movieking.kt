package com.movieking

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class MovieKing : MainAPI() {
    override var mainUrl = "https://mvking6.org" // 접속 안 되면 최신 도메인으로 변경
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val commonHeaders = mapOf(
        "Referer" to "$mainUrl/"
    )

    // 제목에서 (2024) 같은 연도 제거를 위한 정규식
    private val titleRegex = Regex("""\s*\(\d{4}\)$""")

    // 2. 카테고리 명칭 변경 ("무료" 제거)
    override val mainPage = mainPageOf(
        "/video?type=movie" to "영화",
        "/video?type=drama" to "드라마",
        "/video?type=enter" to "예능",
        "/video?type=ani" to "애니",
        "/video?type=docu" to "시사/다큐"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}&page=$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        
        val href = fixUrl(linkTag.attr("href"))
        
        // 목록 제목에서 연도 제거
        val rawTitle = titleTag.text().trim()
        val title = rawTitle.replace(titleRegex, "").trim()

        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("src") ?: imgTag?.attr("data-src")
        val fixedPoster = fixUrl(rawPoster ?: "")

        // 1. 포스터 URL 전달 꼼수 (URL 인코딩 후 파라미터로 추가)
        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                // 기존 url에 파라미터가 이미 있으므로 &로 연결
                finalHref = "$href&poster_url=$encodedPoster"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val type = if (href.contains("movie")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, finalHref, TvType.Movie) {
                this.posterUrl = fixedPoster
            }
        } else {
            newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) {
                this.posterUrl = fixedPoster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?keyword=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        return doc.select(".video-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        // 1. URL에서 poster_url 파라미터 추출 및 제거
        var passedPoster: String? = null
        var realUrl = url

        try {
            // poster_url=... 패턴 찾기
            val match = Regex("""[&?]poster_url=([^&]+)""").find(url)
            if (match != null) {
                val encodedPoster = match.groupValues[1]
                passedPoster = URLDecoder.decode(encodedPoster, "UTF-8")
                // 실제 통신용 URL에서 해당 파라미터 제거
                realUrl = url.replace(match.value, "")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val doc = app.get(realUrl, headers = commonHeaders).document

        // 상세 페이지 정보 파싱
        val infoContent = doc.selectFirst(".single-video-info-content")
        
        // 제목 파싱 및 연도 제거
        val rawTitle = infoContent?.selectFirst("h3")?.text()?.trim() ?: "Unknown"
        val title = rawTitle.replace(titleRegex, "").trim()
        
        // 포스터 우선순위: 전달받은 포스터 > 상세페이지 이미지 > 메타태그
        var poster = passedPoster
        if (poster.isNullOrEmpty()) {
            poster = doc.selectFirst(".single-video-left img")?.attr("src")
                ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        }

        // 상세 정보 텍스트 추출 함수
        fun getInfoText(keyword: String): String? {
            return infoContent?.select("p:contains($keyword)")?.text()
                ?.replace(keyword, "")?.replace(":", "")?.trim()
        }

        val quality = getInfoText("화질")
        val genre = getInfoText("장르")
        val country = getInfoText("나라")
        val releaseDate = getInfoText("개봉")
        val director = getInfoText("감독")
        val cast = getInfoText("출연")

        // 소개(줄거리) 추출
        val intro = infoContent?.selectFirst("h6:contains(소개)")?.nextElementSibling()?.text()?.trim()

        // 메타데이터 텍스트 조합
        val plotText = buildString {
            if (!quality.isNullOrBlank()) append("화질: $quality / ")
            if (!genre.isNullOrBlank()) append("장르: $genre / ")
            if (!country.isNullOrBlank()) append("국가: $country / ")
            if (!releaseDate.isNullOrBlank()) append("공개일: $releaseDate / ")
            // 3. 감독 라벨 변경
            if (!director.isNullOrBlank()) append("감독(방송사): $director / ")
            if (!cast.isNullOrBlank()) append("출연: $cast / ")
            if (!intro.isNullOrBlank()) append("줄거리: $intro")
        }

        // 연도 정보
        val year = releaseDate?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()

        // 에피소드 파싱
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.text().trim()
            newEpisode(href) {
                this.name = name
            }
        }.reversed()

        val isMovie = episodeList.isEmpty() || (episodeList.size == 1 && realUrl.contains("type=movie"))

        return if (isMovie) {
            newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plotText
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plotText
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data에는 poster_url이 포함되지 않은 순수 URL이 들어올 수도 있고 포함될 수도 있음 (load에서 에피소드 링크 생성 시점에 따라 다름)
        // 안전하게 처리하려면 여기서도 poster_url 제거 로직이 필요할 수 있으나, 
        // 보통 loadLinks는 iframe src를 찾기 위해 HTML을 긁는 용도이므로 파라미터가 있어도 무방함.
        
        val doc = app.get(data, headers = commonHeaders).document
        
        val iframe = doc.selectFirst("iframe#view_iframe")
        val src = iframe?.attr("src")

        if (src != null) {
            val fixedSrc = fixUrl(src)
            loadExtractor(fixedSrc, data, subtitleCallback, callback)
            return true
        }
        return false
    }
}
