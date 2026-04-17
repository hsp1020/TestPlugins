// 버전 정보: v1.2
package com.movieking

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// v1.2 수정: 도메인 자동 갱신(리다이렉트 감지 및 캐싱) 로직 추가
class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking.top" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        private const val PORTAL_URL = "https://xn--2z1bw8g20f.com" // 무비킹.com 퓨니코드
    }

    override var mainUrl = currentMainUrl
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val commonHeaders = mapOf("Referer" to "$mainUrl/")
    private val titleRegex = Regex("""\s*\(\d{4}\).*""")
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(?\d{4}\)?.*""")

    // v1.1 로직 보존: 통신 에러 방지를 위한 카테고리 URL 인코딩 문자열
    override val mainPage = mainPageOf(
        "/video/%EC%98%81%ED%99%94" to "영화",
        "/video/%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "/video/TV%EC%98%88%EB%8A%A5" to "예능",
        "/video/%EC%95%A0%EB%8B%88" to "애니",
        "/video/%EC%8B%9C%EC%82%AC%EB%8B%A4%ED%81%90" to "시사/다큐"
    )

    // v1.2 추가: 리다이렉션 감지 및 최신 도메인 동적 파싱 로직
    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)
            
            if (cachedDomain != null && currentMainUrl == "https://mvking.top") {
                println("[MovieKing v1.2 DBG] 디스크 캐시에서 도메인 로드 완료: $cachedDomain")
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }
            
            // 1. 현재 도메인이 유효한지 확인 (리다이렉트 자동 감지)
            try {
                println("[MovieKing v1.2 DBG] 도메인 유효성 검사 시작: $currentMainUrl")
                val res = app.get(currentMainUrl, headers = commonHeaders, timeout = 3L)
                val finalUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: currentMainUrl
                
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        println("[MovieKing v1.2 DBG] 리다이렉트 감지. 도메인 갱신: $currentMainUrl -> $finalUrl")
                        currentMainUrl = finalUrl
                        mainUrl = currentMainUrl
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    }
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("[MovieKing v1.2 DBG] 현재 도메인 접속 실패: ${e.message}")
            }

            // 2. 포털(무비킹.com)을 통한 우회 주소 획득
            try {
                println("[MovieKing v1.2 DBG] 포털 사이트($PORTAL_URL)를 통해 최신 주소 확인 시도")
                val res = app.get(PORTAL_URL, headers = commonHeaders, timeout = 5L)
                val finalUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: PORTAL_URL
                
                if (res.isSuccessful && res.text.contains("video-card")) {
                    println("[MovieKing v1.2 DBG] 포털 리다이렉션으로 새 도메인 획득: $finalUrl")
                    currentMainUrl = finalUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("[MovieKing v1.2 DBG] 포털 접속 실패: ${e.message}")
            }
            
            println("[MovieKing v1.2 DBG] 도메인 탐색 완료. 갱신 실패 시 기존 주소 유지.")
            isDomainChecked = true 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain() // v1.2 추가: 도메인 검증
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing v1.2 DBG] getMainPage 요청됨 - url: $url, 카테고리: ${request.name}")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            
            println("[MovieKing v1.2 DBG] getMainPage 파싱성공 - 아이템 개수: ${list.size}")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[MovieKing v1.2 DBG] getMainPage 에러발생: ${e.message}")
            if (e is CancellationException) throw e 
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(categoryName: String? = null): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        
        val href = fixUrl(linkTag.attr("href"))
        val title = titleTag.text().trim().replace(titleRegex, "").trim()

        val imgTag = this.selectFirst("img")
        val rawPoster = imgTag?.attr("src") ?: imgTag?.attr("data-src")
        val fixedPoster = fixUrl(rawPoster ?: "")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            try { finalHref = "$href&poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val isMovie = categoryName == "영화" || href.contains("movie") || href.contains("영화")
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        println("[MovieKing v1.2 DBG] SearchResponse 매핑 - 제목: $title, 타입: $type, URL: $finalHref")

        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain() // v1.2 추가: 도메인 검증
        val searchUrl = "$mainUrl/search?k=$query"
        println("[MovieKing v1.2 DBG] search 요청됨 - query: $query, url: $searchUrl")
        
        return try {
            val list = app.get(searchUrl, headers = commonHeaders).document.select(".video-card").mapNotNull { it.toSearchResponse() }
            println("[MovieKing v1.2 DBG] search 파싱성공 - 검색된 아이템 개수: ${list.size}")
            list
        } catch (e: Exception) {
            println("[MovieKing v1.2 DBG] search 에러발생: ${e.message}")
            if (e is CancellationException) throw e; emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain() // v1.2 추가: 도메인 검증
        println("[MovieKing v1.2 DBG] load 요청됨 - url: $url")
        var passedPoster: String? = null
        var realUrl = url
        
        // 동적 도메인이 적용된 경우, 기존 데이터베이스나 캐시에서 넘어온 구 도메인 url을 신 도메인으로 치환
        if (!realUrl.startsWith(mainUrl) && realUrl.contains("mvking")) {
            val path = realUrl.replace(Regex("https?://[^/]+"), "")
            realUrl = mainUrl + path
        }

        try {
            val match = Regex("""[&?]poster_url=([^&]+)""").find(url)
            if (match != null) { passedPoster = URLDecoder.decode(match.groupValues[1], "UTF-8"); realUrl = url.replace(match.value, "") }
        } catch (e: Exception) {}

        try {
            val doc = app.get(realUrl, headers = commonHeaders).document
            val title = (doc.selectFirst("h3.title")?.text()?.trim() ?: doc.selectFirst("h1.text-secondary")?.text()?.trim() ?: "Unknown").replace(titleRegex, "").trim()
            val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            val infoContent = doc.selectFirst(".single-video-info-content")

            fun getInfoText(keyword: String) = infoContent?.select("p:contains($keyword)")?.text()?.replace(keyword, "")?.replace(":", "")?.trim()

            val genre = getInfoText("장르")?.replace(tagCleanRegex, "")?.trim()
            val country = getInfoText("나라")
            val releaseDate = getInfoText("개봉")
            val director = getInfoText("감독")
            val cast = getInfoText("출연")
            val intro = infoContent?.selectFirst("h6:contains(소개)")?.nextElementSibling()?.text()?.trim()

            val tagsList = mutableListOf<String>()
            if (!genre.isNullOrBlank()) tagsList.add("장르: $genre")
            if (!country.isNullOrBlank()) tagsList.add("국가: $country")
            if (!releaseDate.isNullOrBlank()) tagsList.add("공개일: $releaseDate")
            if (!director.isNullOrBlank()) tagsList.add("감독(방송사): $director")
            if (!cast.isNullOrBlank()) tagsList.add("출연: $cast")

            val year = releaseDate?.replace(Regex("[^0-9-]"), "")?.take(4)?.toIntOrNull()
            val episodeList = doc.select(".video-slider-right-list .eps_a").map { newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } }.reversed()

            println("[MovieKing v1.2 DBG] load 파싱성공 - 에피소드 개수: ${episodeList.size}")

            return if (episodeList.isEmpty() || episodeList.size == 1) {
                newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) { this.posterUrl = fixUrl(poster ?: ""); this.plot = intro; this.tags = tagsList; this.year = year }
            } else {
                newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster ?: ""); this.plot = intro; this.tags = tagsList; this.year = year }
            }
        } catch (e: Exception) {
            println("[MovieKing v1.2 DBG] load 에러발생: ${e.message}")
            if (e is CancellationException) throw e; throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain() // v1.2 추가: 도메인 검증
        
        var targetData = data
        if (!targetData.startsWith(mainUrl) && targetData.contains("mvking")) {
            val path = targetData.replace(Regex("https?://[^/]+"), "")
            targetData = mainUrl + path
        }
        
        println("[MovieKing v1.2 DBG] loadLinks 요청됨 - data: $targetData")
        try {
            val doc = app.get(targetData, headers = commonHeaders).document
            val src = doc.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { 
                println("[MovieKing v1.2 DBG] loadLinks 파싱성공 - src: $src")
                loadExtractor(fixUrl(src), targetData, subtitleCallback, callback); return true 
            } else {
                println("[MovieKing v1.2 DBG] loadLinks 실패 - iframe을 찾을 수 없음")
            }
        } catch (e: Exception) {
            println("[MovieKing v1.2 DBG] loadLinks 에러발생: ${e.message}")
            if (e is CancellationException) throw e
        }
        return false
    }
}
