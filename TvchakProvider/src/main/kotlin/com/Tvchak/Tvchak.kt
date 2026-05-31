package com.lagradost.cloudstream3.plugins.tvchak

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class TvchakProvider : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvchak200.com"
        var isDomainChecked = false
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "TvChak_Domain_Cache"
        private const val PREF_KEY = "current_domain"
    }

    override var mainUrl = currentMainUrl
    override var name = "TVChak"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "1" to "영화",
        "2" to "드라마",
        "3" to "예능",
        "4" to "애니"
    )

    /**
     * 동적 도메인 스캐닝 (TVWiki 방식)
     */
    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawCachedDomain = prefs?.getString(PREF_KEY, null)
            val cachedDomain = rawCachedDomain?.replace("http://", "https://")

            if (cachedDomain != null && currentMainUrl == "https://tvchak200.com") {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }

            val testPath = "/index.php/vod/show/id/1/page/1.html"

            try {
                val res = app.get("$currentMainUrl$testPath", timeout = 3L)
                if (res.isSuccessful && res.text.contains("movie-list-item")) {
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            val match = Regex("tvchak(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 200

            var foundDomain: String? = null

            coroutineScope {
                val candidates = (startNum..startNum + 50).map { "https://tvchak$it.com" }
                val chunks = candidates.chunked(5)

                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            val testUrl = "$testDomain$testPath"
                            try {
                                val res = app.get(testUrl, timeout = 3L)
                                if (res.isSuccessful && res.text.contains("movie-list-item")) {
                                    return@async testDomain
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                            }
                            null
                        }
                    }

                    val results = deferreds.awaitAll()
                    foundDomain = results.firstOrNull { it != null }

                    if (foundDomain != null) break
                }
            }

            if (foundDomain != null) {
                currentMainUrl = foundDomain!!
                mainUrl = currentMainUrl
                isDomainChecked = true
                prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                return@withLock
            }

            isDomainChecked = true
        }
    }

    /**
     * 메인 페이지 로드
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/show/id/${request.data}/page/$page.html"
            println("TVCHAK_DEBUG: [getMainPage] 요청 페이지 = $page, 카테고리 = ${request.name}, URL = $url")
            
            val document = app.get(url).document

            // 사이드바(.movie-list-body2)를 완벽히 우회하고 본문 목록(.movie-list-body)만 저격하여 수집
            val items = document.select(".movie-list-body .movie-list-item").mapNotNull { element ->
                parseItem(element, request.data == "1")
            }

            println("TVCHAK_DEBUG: [getMainPage] 페이지 $page 로드 성공 - 중복 배제된 본문 아이템 수: ${items.size}")
            
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            println("TVCHAK_DEBUG: [getMainPage] 페이지 $page 로드 중 에러 발생: ${e.message}")
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    /**
     * 검색 기능
     */
    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/search.html?wd=$query"
            println("TVCHAK_DEBUG: [search] 검색어 = $query, URL = $url")
            
            val document = app.get(url).document

            val items = document.select(".vod-search-list .box").mapNotNull { element ->
                val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return@mapNotNull null
                val href = fixUrlNull(element.selectFirst("a.cover-link")?.attr("href")) ?: return@mapNotNull null
                val poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
                
                val encodedPoster = URLEncoder.encode(poster, "UTF-8")
                val finalUrl = "$href?poster=$encodedPoster"

                newTvSeriesSearchResponse(title, finalUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            println("TVCHAK_DEBUG: [search] 검색 결과 수: ${items.size}")
            items
        } catch (e: Exception) {
            println("TVCHAK_DEBUG: [search] 검색 실패: ${e.message}")
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    /**
     * 상세 페이지
     */

     override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        
        val realUrl = url.substringBefore("?poster=")
        val encodedPoster = url.substringAfter("?poster=", "")
        val passedPoster = if (encodedPoster.isNotBlank()) {
            URLDecoder.decode(encodedPoster, "UTF-8")
        } else null

        val document = app.get(realUrl).document
        val title = document.selectFirst("h1.movie-title")?.text()?.trim() ?: ""
        val plot = document.selectFirst("h2#sum_tag")?.text()?.trim() ?: ""
        val poster = passedPoster ?: document.selectFirst(".poster img")?.attr("src") ?: ""
        
        val episodes = document.select(".content_playlist li a").reversed().mapIndexedNotNull { index, element ->
            val epTitle = element.text().trim()
            val epHref = fixUrlNull(element.attr("href")) ?: return@mapIndexedNotNull null

            newEpisode(epHref) {
                this.name = epTitle
                // 정규식 매칭을 제거하고 수집된 순서대로 1, 2, 3, 4... 에피소드 번호를 부여합니다.
                this.episode = index + 1
            }
        }

        val tvType = if (episodes.size == 1 && title.contains("영화")) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, realUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }



    /**
     * 영상 링크 추출
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        checkAndUpdateDomain()
        
        val document = app.get(data).document
        
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data() ?: return false
        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val matchUrl = urlRegex.find(jsonString)
        val rawUrl = matchUrl?.groupValues?.get(1)?.replace("\\/", "/") ?: return false

        var videoUrl = rawUrl
        var subUrl: String? = null

        val subRegex = Regex("""\?(srt|vtt)(https?://.*)""")
        val subMatch = subRegex.find(rawUrl)
        if (subMatch != null) {
            videoUrl = rawUrl.substring(0, subMatch.range.first)
            subUrl = subMatch.groupValues[2]
        }

        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = videoUrl,
                type = type
            ) {
                this.referer = mainUrl
                this.quality = Qualities.P1080.value
            }
        )

        if (!subUrl.isNullOrBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile(
                    "Korean",
                    subUrl
                )
            )
        }

        return true
    }

    /**
     * 공통 아이템 파싱 함수 (오류 복구를 위해 동기식 try-catch 장치 도입)
     */
    private fun parseItem(element: Element, isMovie: Boolean): SearchResponse? {
        return try {
            val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return null
            val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return null
            
            var poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
            if (poster.isBlank() || poster == "/") {
                poster = element.selectFirst(".movie-post-lazyload")?.attr("style")
                    ?.substringAfter("url('")?.substringBefore("')") ?: ""
            }

            val encodedPoster = URLEncoder.encode(poster, "UTF-8")
            val finalUrl = "$href?poster=$encodedPoster"
            val type = if (isMovie) TvType.Movie else TvType.TvSeries

            newTvSeriesSearchResponse(title, finalUrl, type) {
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            null
        }
    }
}
