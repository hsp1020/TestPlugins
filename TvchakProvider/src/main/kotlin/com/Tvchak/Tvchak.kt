package com.lagradost.cloudstream3.plugins

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.util.Base64

class TvChakProvider : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvchak200.com"
        var isDomainChecked = false
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "TvChak_Domain_Cache"
        private const val PREF_KEY = "current_domain"
    }

    override var mainUrl = currentMainUrl
    override var name = "티비착(TVChak)"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Variety)

    override val mainPage = mainPageOf(
        "1" to "영화",
        "2" to "드라마",
        "3" to "예능",
        "4" to "애니"
    )

    /**
     * 1. 동적 도메인 스캐닝 (TVWiki 방식 완벽 이식)
     * tvchak200.com 부터 숫자를 올려가며 살아있는 도메인을 병렬로 찾아낸다.
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

            val testPath = "/index.php/vod/type/id/1.html"

            // 1차 시도: 현재 도메인이 살아있는지 확인
            try {
                val res = app.get("$currentMainUrl$testPath", timeout = 3L)
                if (res.isSuccessful && res.text.contains("movie-list-item")) {
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            // 2차 시도: 도메인 숫자 증가시키며 병렬 스캐닝
            val match = Regex("tvchak(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 200

            var foundDomain: String? = null

            coroutineScope {
                // 50개 정도의 후보군 생성 (tvchak200 ~ tvchak250)
                val candidates = (startNum..startNum + 50).map { "https://tvchak$it.com" }
                val chunks = candidates.chunked(5) // 5개씩 병렬 처리하여 네트워크 과부하 방지

                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            val testUrl = "$testDomain$testPath"
                            try {
                                val res = app.get(testUrl, timeout = 3L)
                                // 정상적인 페이지인지 검증 (movie-list-item 클래스 존재 여부)
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

            isDomainChecked = true // 실패하더라도 무한 루프 방지
        }
    }

    /**
     * 2. 메인 페이지 로드 및 병렬 처리
     */
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl/index.php/vod/type/id/${request.data}/page/$page.html"
        val document = app.get(url).document

        val items = document.select(".movie-list-item").apmap { element ->
            parseItem(element, request.data == "1")
        }.filterNotNull()

        return newHomePageResponse(request.name, items)
    }

    /**
     * 3. 검색 기능
     */
    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        val url = "$mainUrl/index.php/vod/search.html?wd=$query"
        val document = app.get(url).document

        return document.select(".vod-search-list .box").apmap { element ->
            val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return@apmap null
            val href = fixUrlNull(element.selectFirst("a.cover-link")?.attr("href")) ?: return@apmap null
            val poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
            
            val encodedPoster = Base64.getEncoder().encodeToString(poster.toByteArray())
            val finalUrl = "$href?poster=$encodedPoster"

            newTvSeriesSearchResponse(title, finalUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }.filterNotNull()
    }

    /**
     * 4. 상세 페이지 (아이템 선택 -> 에피소드 목록)
     */
    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        
        val realUrl = url.substringBefore("?poster=")
        val encodedPoster = url.substringAfter("?poster=", "")
        val passedPoster = if (encodedPoster.isNotBlank()) {
            String(Base64.getDecoder().decode(encodedPoster))
        } else null

        val document = app.get(realUrl).document
        val title = document.selectFirst("h1.movie-title")?.text()?.trim() ?: ""
        val plot = document.selectFirst("h2#sum_tag")?.text()?.trim() ?: ""
        val poster = passedPoster ?: document.selectFirst(".poster img")?.attr("src") ?: ""
        
        val episodes = document.select(".content_playlist li a").mapNotNull { element ->
            val epTitle = element.text().trim()
            val epHref = fixUrlNull(element.attr("href")) ?: return@mapNotNull null
            val epNum = Regex("\\d+").find(epTitle)?.value?.toIntOrNull()

            Episode(
                data = epHref,
                name = epTitle,
                episode = epNum
            )
        }.reversed()

        val tvType = if (episodes.size == 1 && title.contains("영화")) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, realUrl, tvType, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    /**
     * 5. 영상 링크 추출 (에피소드 선택 -> 플레이어)
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        checkAndUpdateDomain()
        
        val document = app.get(data).document
        
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data() ?: return false
        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val playerJson = parseJson<PlayerAaaa>(jsonString)
        val rawUrl = playerJson.url ?: return false

        // URL에 자막(?srt)이 포함되어 있는지 확인 및 분리
        val videoUrl = if (rawUrl.contains("?srt")) rawUrl.substringBefore("?srt") else rawUrl
        val subUrl = if (rawUrl.contains("?srt")) rawUrl.substringAfter("?srt") else null

        callback.invoke(
            ExtractorLink(
                name = this.name,
                name = this.name,
                url = videoUrl,
                referer = mainUrl,
                quality = Qualities.P1080.value,
                isM3u8 = videoUrl.contains(".m3u8")
            )
        )

        if (!subUrl.isNullOrBlank()) {
            subtitleCallback.invoke(
                SubtitleFile(
                    lang = "Korean",
                    url = subUrl
                )
            )
        }

        return true
    }

    /**
     * 공통 아이템 파싱 함수 (메인 페이지용)
     */
    private fun parseItem(element: Element, isMovie: Boolean): SearchResponse? {
        val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return null
        val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return null
        
        var poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
        if (poster.isBlank() || poster == "/") {
            poster = element.selectFirst(".movie-post-lazyload")?.attr("style")
                ?.substringAfter("url('")?.substringBefore("')") ?: ""
        }

        val encodedPoster = Base64.getEncoder().encodeToString(poster.toByteArray())
        val finalUrl = "$href?poster=$encodedPoster"
        val type = if (isMovie) TvType.Movie else TvType.TvSeries

        return newTvSeriesSearchResponse(title, finalUrl, type) {
            this.posterUrl = poster
        }
    }

    data class PlayerAaaa(
        @JsonProperty("flag") val flag: String? = null,
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("id") val id: String? = null
    )
}
