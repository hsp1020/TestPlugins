package com.linkkf

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Element

class Linkkf : MainAPI() {
    companion object {
        private const val TAG = "Linkkf"
    }

    override var mainUrl = "https://linkani.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    private val commonHeaders
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )

    override val mainPage = mainPageOf(
        "/label/topday" to "오늘의 인기순위",
        "/label/week" to "이번주 인기순위",
        "/label/month" to "이번달 인기순위",
        "/label/view" to "전체 인기순위",
        "/list/2/lang/TV" to "TV 애니메이션",
        "/list/2/lang/Movie" to "극장판",
        "/list/2/lang/OVA" to "OVA"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl${request.data}/"
        } else {
            "${mainUrl}${if (request.data.endsWith("/")) request.data else "${request.data}/"}page/$page/"
        }

        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { it.toSearchResponse() }

            Log.d(TAG, "[HOME] page=$page url=$url items=${list.size}")

            val isLabelCatalog = request.data.startsWith("/label/")
            val hasNextPage = if (isLabelCatalog) false else list.isNotEmpty()

            newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = list,
                    isHorizontalImages = true
                ),
                hasNext = hasNextPage
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[HOME_ERROR] url=$url ${e.javaClass.simpleName}: ${e.message}", e)
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[SEARCH_ERROR] url=$url ${e.javaClass.simpleName}: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val fullUrl = absoluteUrl(url) ?: url
            val doc = app.get(fullUrl, headers = commonHeaders).document
            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"

            // Linkani가 /anime_uploads/... 상대경로를 반환하므로 반드시 절대 URL로 변환한다.
            val poster = doc.selectFirst(".detail-img img")?.let { img ->
                val raw = img.attr("data-original").ifBlank { img.attr("src") }
                absoluteUrl(raw)
            }

            var description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
            if (description.isNullOrEmpty()) description = "다시보기"

            val tags = doc.select(".detail-info-desc a[href*='/class/']")
                .map { it.text().trim() }
            val yearStr = doc.selectFirst("a[href*='/year/']")?.text()?.trim()
            val year: Int? = yearStr?.toIntOrNull()

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            val tabs = doc.select(".playlist-tab-box .tab-item")

            fun parseEpisodes(selector: String): List<Episode> {
                return doc.select(selector).mapNotNull { aTag ->
                    val href = aTag.attr("href")
                    val episodeUrl = absoluteUrl(href)
                    val episodeName = aTag.text().trim()

                    if (!episodeUrl.isNullOrBlank()) {
                        newEpisode(episodeUrl) {
                            this.name = episodeName
                            this.episode = episodeName.filter { it.isDigit() }.toIntOrNull()
                        }
                    } else {
                        null
                    }
                }.reversed()
            }

            if (tabs.isNotEmpty()) {
                tabs.forEach { tab ->
                    val tabText = tab.text().lowercase()
                    val targetId = tab.attr("data-target")
                    if (targetId.isNotEmpty()) {
                        val selector = when {
                            targetId.startsWith("#") || targetId.startsWith(".") || targetId.startsWith("[") ->
                                "$targetId .episodelist a"
                            else -> "#$targetId .episodelist a"
                        }
                        val episodes = parseEpisodes(selector)

                        if (tabText.contains("dub") || tabText.contains("더빙")) {
                            dubEpisodes.addAll(episodes)
                        } else {
                            subEpisodes.addAll(episodes)
                        }
                    }
                }
            } else {
                subEpisodes.addAll(parseEpisodes(".episode-box .episodelist a"))
            }

            // DOM 변형으로 tab target 해석에 실패한 경우 전체 episodelist를 한 번 더 탐색한다.
            if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
                subEpisodes.addAll(parseEpisodes(".episodelist a"))
            }

            Log.d(
                TAG,
                "[LOAD] url=$fullUrl title=$title sub=${subEpisodes.size} dub=${dubEpisodes.size} poster=$poster"
            )

            return newAnimeLoadResponse(title, fullUrl, TvType.Anime) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
                this.year = year
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[LOAD_ERROR] url=$url ${e.javaClass.simpleName}: ${e.message}", e)
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = absoluteUrl(data) ?: data
        Log.d(TAG, "[LINKS_START] data=$data fullUrl=$fullUrl")

        return try {
            val extractor = LinkkfExtractor()
            val result = extractor.extract(fullUrl, "$mainUrl/")

            if (result != null) {
                if (result.subtitleUrl.isNotEmpty()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = "Korean",
                            url = result.subtitleUrl
                        )
                    )
                }

                if (result.fallbackSubtitleUrl.isNotEmpty()) {
                    subtitleCallback.invoke(
                        newSubtitleFile(
                            lang = "Korean",
                            url = result.fallbackSubtitleUrl
                        )
                    )
                }
            }

            var finalM3u8Url: String? = null
            var streamReferer = fullUrl

            if (result != null) {
                if (!result.needsWebView && result.m3u8Url.contains(".m3u8", ignoreCase = true)) {
                    finalM3u8Url = result.m3u8Url
                    streamReferer = result.referer
                    Log.d(TAG, "[LINKS_STATIC_OK] m3u8=$finalM3u8Url referer=$streamReferer")
                } else {
                    // actual_url이 player 페이지라면 해당 페이지에서 m3u8 네트워크 요청을 포착한다.
                    Log.d(TAG, "[LINKS_PLAYER_WEBVIEW] player=${result.m3u8Url}")
                    finalM3u8Url = resolveM3u8WithWebView(
                        pageUrl = result.m3u8Url,
                        referer = result.referer
                    )
                    if (!finalM3u8Url.isNullOrBlank()) {
                        streamReferer = result.m3u8Url
                    }
                }
            }

            // 정적 파서 실패 또는 player WebView 실패 시 watch 페이지 자체를 WebView로 최종 재시도.
            if (finalM3u8Url.isNullOrBlank()) {
                Log.w(TAG, "[LINKS_WATCH_WEBVIEW_FALLBACK] url=$fullUrl")
                finalM3u8Url = resolveM3u8WithWebView(
                    pageUrl = fullUrl,
                    referer = "$mainUrl/"
                )
                if (!finalM3u8Url.isNullOrBlank()) {
                    streamReferer = fullUrl
                }
            }

            if (finalM3u8Url.isNullOrBlank()) {
                Log.e(TAG, "[LINKS_FAIL] no m3u8 found for $fullUrl")
                return false
            }

            Log.d(TAG, "[LINKS_OK] m3u8=$finalM3u8Url referer=$streamReferer")

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalM3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = streamReferer
                    this.quality = getQualityFromName("HD")
                }
            )
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[LINKS_ERROR] url=$fullUrl ${e.javaClass.simpleName}: ${e.message}", e)
            false
        }
    }

    private suspend fun resolveM3u8WithWebView(pageUrl: String, referer: String): String? {
        return try {
            val headers = commonHeaders + ("Referer" to referer)
            val response = app.get(
                pageUrl,
                headers = headers,
                interceptor = WebViewResolver(
                    Regex("""\.m3u8(?:\?|$)""", RegexOption.IGNORE_CASE)
                )
            )

            val resolved = response.url
            if (resolved.contains(".m3u8", ignoreCase = true)) {
                Log.d(TAG, "[WEBVIEW_OK] page=$pageUrl m3u8=$resolved")
                resolved
            } else {
                Log.w(TAG, "[WEBVIEW_NO_M3U8] page=$pageUrl final=$resolved")
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(
                TAG,
                "[WEBVIEW_ERROR] page=$pageUrl ${e.javaClass.simpleName}: ${e.message}",
                e
            )
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.vod-item-img") ?: return null
        val title = this.selectFirst(".vod-item-title a")?.text()?.trim() ?: return null
        val href = absoluteUrl(aTag.attr("href")) ?: return null

        val rawPoster = this.selectFirst(".img-wrapper")
            ?.attr("data-original")
            ?.ifBlank { this.selectFirst("img")?.attr("src").orEmpty() }

        val poster = absoluteUrl(rawPoster)

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    /** Linkani의 상대경로(/anime_uploads/..., /watch/...)와 //host/... 모두 정규화 */
    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null

        return when {
            value.startsWith("http://", ignoreCase = true) ||
                value.startsWith("https://", ignoreCase = true) -> value

            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }
}
