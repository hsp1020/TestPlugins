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
        var currentMainUrl = "https://mvking.top"
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        private const val PORTAL_URL = "https://linkroom.top" 

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[MovieKing DBG] 💾 영구 도메인 저장 완료: $newDomain")
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
            
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)
            
            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
                println("[MovieKing DBG] 📂 캐시된 도메인 로드: $currentMainUrl")
            }
            
            try {
                val res = app.get(currentMainUrl, headers = commonHeaders, timeout = 5L)
                val finalUrl = res.url.trimEnd('/')
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        println("[MovieKing DBG] 🔄 리다이렉트 감지됨: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) { println("[MovieKing DBG] ❌ 현재 도메인 접속 실패") }

            try {
                println("[MovieKing DBG] 🌐 베이스 도메인(mvking.top) 폴백 시도")
                val baseRes = app.get("https://mvking.top", headers = commonHeaders, timeout = 5L)
                val baseFinalUrl = baseRes.url.trimEnd('/')
                if (baseRes.isSuccessful && baseRes.text.contains("video-card")) {
                    println("[MovieKing DBG] ✨ 베이스 도메인 폴백 성공: $baseFinalUrl")
                    saveDomain(baseFinalUrl)
                    mainUrl = currentMainUrl
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}

            try {
                println("[MovieKing DBG] 🕵️ 링크룸 대피소 포털 스크래핑 시도")
                // 1단계: 링크룸 메인페이지 호출
                val mainDoc = app.get(PORTAL_URL, timeout = 10L).document
                
                // 2단계: 정확히 "무비킹" 텍스트를 가진 상세페이지 상대 경로 탐색
                val relativePath = mainDoc.select("a").firstOrNull { 
                    it.text().trim() == "무비킹" 
                }?.attr("href")

                if (!relativePath.isNullOrBlank()) {
                    val detailUrl = if (relativePath.startsWith("http")) relativePath else "$PORTAL_URL$relativePath"
                    println("[MovieKing DBG] 📂 무비킹 상세페이지 주소 발견: $detailUrl")
                    
                    // 3단계: 상세페이지 호출
                    val detailDoc = app.get(detailUrl, timeout = 10L).document
                    
                    // 4단계: 상세페이지 리스트 중 최신주소에 할당된 최종 도메인 추출
                    val latestUrl = detailDoc.selectFirst("ul.paybody-zbinfo span.data a")?.attr("href")?.trimEnd('/')
                        ?: detailDoc.selectFirst("ul.paybody-zbinfo a")?.attr("href")?.trimEnd('/')

                    if (!latestUrl.isNullOrBlank()) {
                        val newTarget = if (latestUrl.startsWith("http")) latestUrl else "https://$latestUrl"
                        println("[MovieKing DBG] ✨ 링크룸 대피소 최종 도메인 파싱 성공: $newTarget")
                        saveDomain(newTarget)
                        mainUrl = currentMainUrl
                        isDomainChecked = true; return@withLock
                    }
                }
            } catch (e: Exception) { 
                println("[MovieKing DBG] ❌ 링크룸 대피소 파싱 실패: ${e.message}") 
            }
            
            isDomainChecked = true 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!movieking ")) {
            val userDomain = query.substring(11).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                println("[MovieKing DBG] 🔧 백도어 도메인 수동 갱신: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true 
                return listOf(newMovieSearchResponse("✅ 무비킹 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔍 검색 요청: $query")
        
        return try {
            var searchUrl = "$mainUrl/search?k=$query"
            var res = app.get(searchUrl, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            if (redirectedDomain != mainUrl || !finalUrl.contains("search")) {
                if (redirectedDomain != mainUrl) {
                    println("[MovieKing DBG] 🔄 검색 중 리다이렉트 감지: $redirectedDomain")
                    saveDomain(redirectedDomain)
                    mainUrl = currentMainUrl
                }
                searchUrl = "$mainUrl/search?k=$query"
                res = app.get(searchUrl, headers = commonHeaders)
            }

            if (!res.url.contains("search")) return emptyList()

            val items = res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
            println("[MovieKing DBG] 🔍 검색 결과 수: ${items.size}")
            items
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        var url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing DBG] 📄 메인페이지 로드: 카테고리=${request.name}, URL=$url")
        
        return try {
            var res = app.get(url, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl
            
            val decodedRequestData = URLDecoder.decode(request.data, "UTF-8").substringBefore("?")
            val decodedFinalUrl = URLDecoder.decode(finalUrl, "UTF-8")

                if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                    if (redirectedDomain != mainUrl) {
                        println("[MovieKing DBG] 🔄 메인페이지 로드 중 리다이렉트 감지: $redirectedDomain")
                        saveDomain(redirectedDomain)
                        mainUrl = currentMainUrl
                    }
                    url = "$mainUrl${request.data}${separator}page=$page"
                    res = app.get(url, headers = commonHeaders)
                }

            val doc = res.document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            println("[MovieKing DBG] 📄 메인페이지 아이템 수: ${list.size}")
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
        println("[MovieKing DBG] 🎬 상세페이지 로드: $url")
        
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

        println("[MovieKing DBG] 🎬 파싱 완료: 에피소드 수=${episodeList.size}")

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) { this.posterUrl = fixUrl(poster); this.plot = intro }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster); this.plot = intro }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔗 영상 링크 추출 시작: $data")
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
