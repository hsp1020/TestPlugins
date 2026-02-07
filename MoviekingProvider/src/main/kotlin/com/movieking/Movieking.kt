package com.movieking

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovieKing : MainAPI() {
    override var mainUrl = "https://mvking6.org" // 접속 안 되면 최신 도메인으로 변경
    override var name = "Movieking"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime
    )

    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 제목에서 (2024) 같은 연도 제거를 위한 정규식
    private val titleRegex = Regex("""\s*\(\d{4}\)$""")

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
        
        // [수정 1] 목록 제목에서 연도 제거
        val rawTitle = titleTag.text().trim()
        val title = rawTitle.replace(titleRegex, "").trim()

        val imgTag = this.selectFirst("img")
        val posterUrl = imgTag?.attr("src") ?: imgTag?.attr("data-src")

        val type = if (href.contains("movie")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = fixUrl(posterUrl ?: "")
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = fixUrl(posterUrl ?: "")
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/video/search?keyword=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        return doc.select(".video-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        // [수정 2] 상세 페이지 정보 파싱
        val infoContent = doc.selectFirst(".single-video-info-content")
        
        // 제목 파싱 및 연도 제거
        val rawTitle = infoContent?.selectFirst("h3")?.text()?.trim() ?: "Unknown"
        val title = rawTitle.replace(titleRegex, "").trim()
        
        val poster = doc.selectFirst(".single-video-left img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")

        // 상세 정보 텍스트 추출 함수
        fun getInfoText(keyword: String): String? {
            // "화질 : HD" 형태에서 텍스트만 추출
            return infoContent?.select("p:contains($keyword)")?.text()
                ?.replace(keyword, "")?.replace(":", "")?.trim()
        }

        val quality = getInfoText("화질")
        val genre = getInfoText("장르")
        val country = getInfoText("나라") // HTML에는 '나라'로 되어 있음
        val releaseDate = getInfoText("개봉") // HTML에는 '개봉'으로 되어 있음
        val director = getInfoText("감독")
        val cast = getInfoText("출연")

        // 소개(줄거리) 추출: h6 태그("소개 :") 바로 다음 형제 요소(p태그)
        val intro = infoContent?.selectFirst("h6:contains(소개)")?.nextElementSibling()?.text()?.trim()

        // [수정 2] 요청하신 포맷으로 합치기
        // 포맷: 화질: ... / 장르: ... / 국가: ... / 공개일: ... / 감독: ... / 출연: ... / 줄거리: ...
        val plotText = buildString {
            if (!quality.isNullOrBlank()) append("화질: $quality / ")
            if (!genre.isNullOrBlank()) append("장르: $genre / ")
            if (!country.isNullOrBlank()) append("국가: $country / ") // '나라'를 '국가'로 변경 표시
            if (!releaseDate.isNullOrBlank()) append("공개일: $releaseDate / ") // '개봉'을 '공개일'로 변경 표시
            if (!director.isNullOrBlank()) append("감독: $director / ")
            if (!cast.isNullOrBlank()) append("출연: $cast / ")
            if (!intro.isNullOrBlank()) append("줄거리: $intro") // '소개'를 '줄거리'로 변경 표시
        }

        // 연도 정보 (Cloudstream 메타데이터용)
        val year = releaseDate?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()

        val episodeList = doc.select(".video-slider-right-list .eps_a").map { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.text().trim()
            newEpisode(href) {
                this.name = name
            }
        }.reversed()

        val isMovie = episodeList.isEmpty() || (episodeList.size == 1 && url.contains("type=movie"))

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plotText // 포맷팅된 텍스트 적용
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = plotText // 포맷팅된 텍스트 적용
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
        val doc = app.get(data, headers = commonHeaders).document
        
        val iframe = doc.selectFirst("iframe#view_iframe")
        val src = iframe?.attr("src")

        if (src != null) {
            val fixedSrc = fixUrl(src)
            if (fixedSrc.contains("bcbc.red")) {
                 loadExtractor(fixedSrc, data, subtitleCallback, callback)
            } else {
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
            return true
        }
        return false
    }
}
