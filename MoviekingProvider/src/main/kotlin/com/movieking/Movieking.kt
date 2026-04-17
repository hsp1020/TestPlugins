// 버전 정보: v1.4
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

// v1.4 수정: 카테고리 경로 유실 리다이렉트 방어 및 도메인 동적 재요청 로직 완벽 적용
class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking.net" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        private const val PORTAL_URL = "https://xn--2z1bw8g20f.com" 
    }

    override var mainUrl = currentMainUrl
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    private val commonHeaders = mapOf("Referer" to "$mainUrl/")
    private val titleRegex = Regex("""\s*\(\d{4}\).*""")
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(?\d{4}\)?.*""")

    override val mainPage = mainPageOf(
        "/video/%EC%98%81%ED%99%94" to "영화",
        "/video/%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "/video/TV%EC%98%88%EB%8A%A5" to "예능",
        "/video/%EC%95%A0%EB%8B%88" to "애니",
        "/video/%EC%8B%9C%EC%82%AC%EB%8B%A4%ED%81%90" to "시사/다큐"
    )

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)
            
            if (cachedDomain != null && currentMainUrl == "https://mvking.top") {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }
            
            try {
                val res = app.get(currentMainUrl, headers = commonHeaders, timeout = 5L)
                if (res.isSuccessful && res.text.contains("video-card")) {
                    println("[MovieKing v1.4 DBG] 기존 주소 유효함: $currentMainUrl")
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                println("[MovieKing v1.4 DBG] 기존 주소 접속 실패, 포털 탐색 시작")
            }

            try {
                val portalDoc = app.get(PORTAL_URL, timeout = 10L).document
                val latestUrl = portalDoc.select("a").firstOrNull { 
                    it.text().contains("무비킹") || it.attr("href").contains("mvking") 
                }?.attr("href")?.trimEnd('/')

                if (!latestUrl.isNullOrBlank()) {
                    val finalUrl = if (latestUrl.startsWith("http")) latestUrl else "https://$latestUrl"
                    println("[MovieKing v1.4 DBG] 포털에서 직접 주소 추출 성공: $finalUrl")
                    currentMainUrl = finalUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    return@withLock
                }
            } catch (e: Exception) {
                println("[MovieKing v1.4 DBG] 포털 내 주소 추출 실패: ${e.message}")
            }
            
            isDomainChecked = true 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        var url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing v1.4 DBG] getMainPage 1차 요청: $url")
        
        return try {
            var res = app.get(url, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl
            
            // v1.4 핵심 로직: 사용자가 지적한 "경로 유실 후 메인 도메인 리다이렉트" 감지
            // 원래 요청했던 경로(예: /video/드라마)가 최종 리다이렉트된 URL에 살아있는지 디코딩하여 확인
            val decodedRequestData = URLDecoder.decode(request.data, "UTF-8").substringBefore("?")
            val decodedFinalUrl = URLDecoder.decode(finalUrl, "UTF-8")

            if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                println("[MovieKing v1.4 DBG] 경로 유실 리다이렉트 감지! 기존 도메인: $mainUrl, 최종 도메인: $redirectedDomain")
                
                // 새 도메인으로 갱신
                if (redirectedDomain != mainUrl) {
                    currentMainUrl = redirectedDomain
                    mainUrl = redirectedDomain
                    AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                }

                // 바뀐 도메인에 '원래 요청하려던 카테고리 경로'를 붙여서 2차 재요청
                url = "$mainUrl${request.data}${separator}page=$page"
                println("[MovieKing v1.4 DBG] 새 도메인으로 경로 복구 후 2차 재요청: $url")
                res = app.get(url, headers = commonHeaders)
            }

            val doc = res.document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            
            println("[MovieKing v1.4 DBG] getMainPage 파싱성공 - 아이템 개수: ${list.size}")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            println("[MovieKing v1.4 DBG] getMainPage 에러발생: ${e.message}")
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
        val fixedPoster = fixUrl(imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: "")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            try { finalHref = "$href&poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val type = if (categoryName == "영화" || href.contains("movie")) TvType.Movie else TvType.TvSeries
        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        return try {
            app.get("$mainUrl/search?k=$query", headers = commonHeaders).document.select(".video-card").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        var realUrl = url
        // 구 도메인으로 요청이 들어왔을 경우 신규 도메인으로 치환
        if (!realUrl.startsWith(mainUrl) && realUrl.contains("mvking")) {
            realUrl = mainUrl + realUrl.replace(Regex("https?://[^/]+"), "")
        }
        val doc = app.get(realUrl, headers = commonHeaders).document
        val title = (doc.selectFirst("h3.title")?.text() ?: "Unknown").replace(titleRegex, "").trim()
        val poster = doc.selectFirst(".single-video-left img")?.attr("src") ?: ""
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } }.reversed()

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, realUrl, TvType.Movie, realUrl) { this.posterUrl = fixUrl(poster) }
        } else {
            newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster) }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        try {
            val src = app.get(data, headers = commonHeaders).document.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { loadExtractor(fixUrl(src), data, subtitleCallback, callback); return true }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return false
    }
}
