// 버전 정보: v2.0 (검색창 수동 도메인 갱신 + 영구 저장소 + 리다이렉트 폴백 추가)
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

class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://movieking.net" // 최상단 기준 최초 도메인
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        private const val PORTAL_URL = "https://xn--2z1bw8g20f.com" 

        // 성공한 도메인 영구 저장 헬퍼 함수
        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[MovieKing v2.0 DBG] 영구 도메인 저장 완료: $newDomain")
        }
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
            
            // 1. 기기에 캐시된 도메인이 있다면 최초 도메인을 덮어씌우고 시작
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)
            
            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }
            
            // 2. 현재 도메인이 정상 작동(리다이렉트 포함)하는지 확인
            try {
                val res = app.get(currentMainUrl, headers = commonHeaders, timeout = 5L)
                val finalUrl = res.url.trimEnd('/')
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        saveDomain(finalUrl) // 리다이렉트 발생 시 즉시 저장
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}

            // 3. [신규 폴백] 하드코딩된 베이스 도메인(movieking.net) 리다이렉트 추적
            try {
                val baseRes = app.get("https://movieking.net", headers = commonHeaders, timeout = 5L)
                val baseFinalUrl = baseRes.url.trimEnd('/')
                if (baseRes.isSuccessful && baseRes.text.contains("video-card")) {
                    saveDomain(baseFinalUrl)
                    mainUrl = currentMainUrl
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}

            // 4. 최후의 수단: 대피소(포털) 파싱 스크래핑
            try {
                val portalDoc = app.get(PORTAL_URL, timeout = 10L).document
                val latestUrl = portalDoc.select("a").firstOrNull { 
                    it.text().contains("무비킹") || it.attr("href").contains("mvking") || it.attr("href").contains("movieking")
                }?.attr("href")?.trimEnd('/')

                if (!latestUrl.isNullOrBlank()) {
                    val newTarget = if (latestUrl.startsWith("http")) latestUrl else "https://$latestUrl"
                    saveDomain(newTarget)
                    mainUrl = currentMainUrl
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}
            
            isDomainChecked = true 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()

        // =========================================================
        // [백도어 트릭] 검색창을 통한 수동 도메인 관리 로직
        // =========================================================
        if (qLower.startsWith("!movieking ")) {
            val userDomain = query.substring(11).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                saveDomain(userDomain) // 사용자가 입력한 값으로 덮어쓰기 및 영구저장
                mainUrl = currentMainUrl
                isDomainChecked = true // 변경 즉시 검증 우회
                
                return listOf(
                    newMovieSearchResponse("✅ 무비킹 도메인 강제 갱신 성공: $userDomain", userDomain, TvType.Movie) {
                        this.posterUrl = ""
                    }
                )
            }
        }
        
        // 사용자가 다른 플러그인 명령어를 쳤을 경우 불필요한 요청 방지 및 충돌 차단
        if (qLower.startsWith("!")) {
            return emptyList()
        }
        // =========================================================

        checkAndUpdateDomain()
        
        return try {
            var searchUrl = "$mainUrl/search?k=$query"
            var res = app.get(searchUrl, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            // 검색 중 예기치 못한 리다이렉트 도메인 변경 감지 시 영구 저장
            if (redirectedDomain != mainUrl || !finalUrl.contains("search")) {
                if (redirectedDomain != mainUrl) {
                    saveDomain(redirectedDomain)
                    mainUrl = currentMainUrl
                }
                searchUrl = "$mainUrl/search?k=$query"
                res = app.get(searchUrl, headers = commonHeaders)
            }

            if (!res.url.contains("search")) return emptyList()

            res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        var url = "$mainUrl${request.data}${separator}page=$page"
        
        return try {
            var res = app.get(url, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl
            
            val decodedRequestData = URLDecoder.decode(request.data, "UTF-8").substringBefore("?")
            val decodedFinalUrl = URLDecoder.decode(finalUrl, "UTF-8")

            // 메인 페이지 로드 중 리다이렉트 발생 시 도메인 영구 저장
            if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                if (redirectedDomain != mainUrl) {
                    saveDomain(redirectedDomain)
                    mainUrl = currentMainUrl
                }
                url = "$mainUrl${request.data}${separator}page=$page"
                res = app.get(url, headers = commonHeaders)
            }

            val doc = res.document
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
            val separator = if (href.contains("?")) "&" else "?"
            try { finalHref = "$href${separator}poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val type = if (categoryName == "영화" || href.contains("movie")) TvType.Movie else TvType.TvSeries
        
        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        
        var passedPoster: String? = null
        var cleanUrl = url

        try {
            val posterRegex = Regex("""([&?])poster_url=[^&]+""")
            val match = posterRegex.find(url)
            if (match != null) {
                val param = match.value
                passedPoster = URLDecoder.decode(param.substringAfter("="), "UTF-8")
                cleanUrl = url.replace(param, "").replace("?&", "?").trimEnd('?', '&')
            }
        } catch (e: Exception) {}

        val finalUrl = if (!cleanUrl.startsWith(mainUrl) && (cleanUrl.contains("mvking") || cleanUrl.contains("movieking"))) {
            mainUrl + cleanUrl.replace(Regex("^https?://[^/]+"), "")
        } else {
            cleanUrl
        }
        
        val doc = app.get(finalUrl, headers = commonHeaders).document
        val title = (doc.selectFirst("h3.title")?.text() ?: doc.selectFirst("h1.text-secondary")?.text() ?: "Unknown").replace(titleRegex, "").trim()
        val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: ""
        val intro = doc.selectFirst(".single-video-info-content h6:contains(소개)")?.nextElementSibling()?.text()?.trim() ?: "설명이 없습니다."
        
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { 
            newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } 
        }.reversed()

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) { this.posterUrl = fixUrl(poster); this.plot = intro }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster); this.plot = intro }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        var targetData = data
        if (!targetData.startsWith(mainUrl) && (targetData.contains("mvking") || targetData.contains("movieking"))) {
            targetData = mainUrl + targetData.replace(Regex("https?://[^/]+"), "")
        }
        
        try {
            val src = app.get(targetData, headers = commonHeaders).document.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { loadExtractor(fixUrl(src), targetData, subtitleCallback, callback); return true }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return false
    }
}
