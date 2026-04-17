// 버전 정보: v1.3
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

// v1.3 수정: 리다이렉트 의존성 탈피, 포털 내 <a> 태그 직접 추출 로직 추가 및 타임아웃 상향
class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking.net" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        // 무비킹 공식 안내 포털
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
            
            // 1단계: 기존 주소 확인
            try {
                val res = app.get(currentMainUrl, headers = commonHeaders, timeout = 5L)
                if (res.isSuccessful && res.text.contains("video-card")) {
                    println("[MovieKing v1.3 DBG] 기존 주소 유효함: $currentMainUrl")
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                println("[MovieKing v1.3 DBG] 기존 주소 접속 실패, 포털 탐색 시작")
            }

            // 2단계: 포털에서 직접 새 주소 추출 (KKTV 방식 차용)
            try {
                val portalDoc = app.get(PORTAL_URL, timeout = 10L).document
                // 무비킹 포털 내 "접속하기" 혹은 "메인으로" 링크 패턴 추출
                val latestUrl = portalDoc.select("a").firstOrNull { 
                    it.text().contains("무비킹") || it.attr("href").contains("mvking") 
                }?.attr("href")?.trimEnd('/')

                if (!latestUrl.isNullOrBlank()) {
                    val finalUrl = if (latestUrl.startsWith("http")) latestUrl else "https://$latestUrl"
                    println("[MovieKing v1.3 DBG] 포털에서 직접 주소 추출 성공: $finalUrl")
                    currentMainUrl = finalUrl
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    return@withLock
                }
            } catch (e: Exception) {
                println("[MovieKing v1.3 DBG] 포털 내 주소 추출 실패: ${e.message}")
            }
            
            isDomainChecked = true 
        }
    }

    // 이하 getMainPage, load, loadLinks 등 기존 파싱 로직은 v1.2와 동일하게 유지
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing v1.3 DBG] getMainPage: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
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
