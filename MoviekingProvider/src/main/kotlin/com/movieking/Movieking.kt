// 버전 정보: v1.6 (원본 로직 + 디버깅 프린트 추가)
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
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}

            try {
                val portalDoc = app.get(PORTAL_URL, timeout = 10L).document
                val latestUrl = portalDoc.select("a").firstOrNull { 
                    it.text().contains("무비킹") || it.attr("href").contains("mvking") 
                }?.attr("href")?.trimEnd('/')

                if (!latestUrl.isNullOrBlank()) {
                    currentMainUrl = if (latestUrl.startsWith("http")) latestUrl else "https://$latestUrl"
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                }
            } catch (e: Exception) {}
            isDomainChecked = true 
        }
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

            if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                if (redirectedDomain != mainUrl) {
                    currentMainUrl = redirectedDomain; mainUrl = redirectedDomain
                    AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
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

        println("[MovieKing v1.6 DBG] toSearchResponse 파싱 중: 제목=[$title], 전달된 카테고리=[$categoryName], href=[$href]")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            val separator = if (href.contains("?")) "&" else "?"
            try { finalHref = "$href${separator}poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val type = if (categoryName == "영화" || href.contains("movie")) TvType.Movie else TvType.TvSeries
        println("[MovieKing v1.6 DBG] toSearchResponse 최종 판별 타입: $type")
        
        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        return try {
            var searchUrl = "$mainUrl/search?k=$query"
            var res = app.get(searchUrl, headers = commonHeaders)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            if (redirectedDomain != mainUrl || !finalUrl.contains("search")) {
                if (redirectedDomain != mainUrl) {
                    currentMainUrl = redirectedDomain; mainUrl = redirectedDomain
                    AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                }
                searchUrl = "$mainUrl/search?k=$query"
                res = app.get(searchUrl, headers = commonHeaders)
            }

            if (!res.url.contains("search")) return emptyList()

            res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    
    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        println("[MovieKing v1.6 DBG] load 진입: $url")
        
        var passedPoster: String? = null
        var cleanUrl = url

        try {
            val posterRegex = Regex("""([&?])poster_url=[^&]+""")
            val match = posterRegex.find(url)
            if (match != null) {
                val param = match.value
                passedPoster = URLDecoder.decode(param.substringAfter("="), "UTF-8")
                cleanUrl = url.replace(param, "").replace("?&", "?").trimEnd('?', '&')
                println("[MovieKing v1.6 DBG] URL 클리닝 성공: $cleanUrl")
            }
        } catch (e: Exception) {
            println("[MovieKing v1.6 DBG] URL 클리닝 중 오류: ${e.message}")
        }

        val finalUrl = if (!cleanUrl.startsWith(mainUrl) && cleanUrl.contains("mvking")) {
            mainUrl + cleanUrl.replace(Regex("^https?://[^/]+"), "")
        } else {
            cleanUrl
        }

        println("[MovieKing v1.6 DBG] 최종 상세페이지 요청: $finalUrl")
        
        val doc = app.get(finalUrl, headers = commonHeaders).document
        val title = (doc.selectFirst("h3.title")?.text() ?: doc.selectFirst("h1.text-secondary")?.text() ?: "Unknown").replace(titleRegex, "").trim()
        val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: ""
        val intro = doc.selectFirst(".single-video-info-content h6:contains(소개)")?.nextElementSibling()?.text()?.trim() ?: "설명이 없습니다."
        
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { 
            newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } 
        }.reversed()

        println("[MovieKing v1.6 DBG] 파싱 완료: 제목=$title, 에피소드 수=${episodeList.size}")

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) { this.posterUrl = fixUrl(poster); this.plot = intro }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster); this.plot = intro }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        var targetData = data
        if (!targetData.startsWith(mainUrl) && targetData.contains("mvking")) {
            targetData = mainUrl + targetData.replace(Regex("https?://[^/]+"), "")
        }
        
        try {
            val src = app.get(targetData, headers = commonHeaders).document.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { loadExtractor(fixUrl(src), targetData, subtitleCallback, callback); return true }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return false
    }
}
