package com.movieking

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class MovieKing : MainAPI() {
    // 제공된 소스 기준 주소
    override var mainUrl = "https://mvking6.org" 
    override var name = "무비킹"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime
    )

    // 헤더 설정
    private val commonHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    // 메인 페이지 카테고리 매핑 (제공된 HTML 사이드바 기준)
    override val mainPage = mainPageOf(
        "/video?type=movie" to "무료영화",
        "/video?type=drama" to "무료드라마",
        "/video?type=enter" to "무료예능",
        "/video?type=ani" to "무료애니",
        "/video?type=docu" to "무료시사/다큐"
    )

    // 메인 페이지 파싱
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        // 무비킹은 페이지네이션이 page 파라미터를 사용하는 것으로 추정 (HTML상 확인 불가하나 일반적 관례 따름)
        // 만약 무한 스크롤 방식이라면 URL 패턴 확인 필요. 여기선 기본 page 파라미터 사용.
        val url = "$mainUrl${request.data}&page=$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            // .video-card 요소 파싱
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    // 검색 결과 파싱 요소 변환 함수
    private fun Element.toSearchResponse(): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        
        val href = fixUrl(linkTag.attr("href"))
        val title = titleTag.text().trim()
        val imgTag = this.selectFirst("img")
        val posterUrl = imgTag?.attr("src") ?: imgTag?.attr("data-src")

        // 타입 추론 (URL이나 태그 등으로 구분, 여기선 기본값으로 설정 후 상세에서 보정)
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

    // 검색 기능
    override suspend fun search(query: String): List<SearchResponse> {
        // 검색 URL: /video/search?keyword={query}
        val searchUrl = "$mainUrl/video/search?keyword=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        
        return doc.select(".video-card").mapNotNull { it.toSearchResponse() }
    }

    // 상세 페이지 로드 (Load)
    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        // 제목 및 정보 파싱
        val infoContent = doc.selectFirst(".single-video-info-content")
        val title = infoContent?.selectFirst("h3")?.text()?.trim() ?: "Unknown"
        
        // 포스터 파싱 (HTML상 좌측 혹은 메타데이터)
        val poster = doc.selectFirst(".single-video-left img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        // 줄거리 파싱 (HTML상 '소개 :' 아래)
        // h6 태그 다음 요소들을 찾거나 텍스트 노드를 찾음
        val description = infoContent?.select("h6")?.next()?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")

        // 메타 정보 파싱 (장르, 개봉일 등)
        val tags = infoContent?.select("p")?.map { it.text() } ?: emptyList()
        val yearData = tags.find { it.contains("개봉") || it.contains("날짜") }
        val year = yearData?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()

        // 에피소드 파싱 (제공된 굿닥터 소스 기준)
        // .video-slider-right-list 내의 a.eps_a 태그들
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { element ->
            val href = fixUrl(element.attr("href"))
            val name = element.text().trim()
            // href 예시: /video/view?id=6651&eid=44204
            
            newEpisode(href) {
                this.name = name
                // 에피소드별 이미지가 따로 없으므로 메인 포스터 사용 혹은 null
            }
        }.reversed() // 보통 최신화가 위에 있으므로 역순 정렬 (1화부터 보려면)

        // 타입 결정 (에피소드가 1개면 영화, 여러개면 드라마로 취급)
        val isMovie = episodeList.isEmpty() || (episodeList.size == 1 && url.contains("type=movie"))
        val tvType = if (isMovie) TvType.Movie else TvType.TvSeries

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = description
                this.year = year
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrl(poster ?: "")
                this.plot = description
                this.year = year
            }
        }
    }

    // 영상 링크 추출 (LoadLinks)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // data는 load()에서 넘겨준 href (예: /video/view?id=...&eid=...)
        val doc = app.get(data, headers = commonHeaders).document
        
        // iframe ID="view_iframe" 찾기
        val iframe = doc.selectFirst("iframe#view_iframe")
        val src = iframe?.attr("src")

        if (src != null) {
            // src 예시: https://player-v1.bcbc.red/player/v1/...
            val fixedSrc = fixUrl(src)
            
            // Extractor 호출
            if (fixedSrc.contains("bcbc.red")) {
                // BcbcRedExtractor 사용
                 loadExtractor(fixedSrc, data, subtitleCallback, callback)
            } else {
                // 그 외 일반적인 iframe인 경우 기본 로직 시도
                loadExtractor(fixedSrc, data, subtitleCallback, callback)
            }
            return true
        }

        return false
    }
}
