package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import org.jsoup.nodes.Element

class Linkkf : MainAPI() {
    override var mainUrl = "https://linkani.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    private val commonHeaders
        get() = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
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
        val path = request.data.trimEnd('/')
        val url = if (page == 1) "$mainUrl$path/" else "$mainUrl$path/page/$page/"

        return try {
            val list = app.get(url, headers = commonHeaders)
                .document
                .select(".vod-item")
                .mapNotNull { it.toSearchResponse() }

            newHomePageResponse(
                HomePageList(request.name, list, isHorizontalImages = false),
                hasNext = !request.data.startsWith("/label/") && list.isNotEmpty()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            app.get("$mainUrl/view/wd/$query/", headers = commonHeaders)
                .document
                .select(".vod-item")
                .mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val fullUrl = absoluteUrl(url) ?: url
        val doc = app.get(fullUrl, headers = commonHeaders).document
        val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
        val poster = doc.selectFirst(".detail-img img")?.let {
            absoluteUrl(it.attr("data-original").ifBlank { it.attr("src") })
        }
        val description = doc.selectFirst(".detail-desc-content")?.text()?.trim().orEmpty()
            .ifBlank { "다시보기" }
        val tags = doc.select(".detail-info-desc a[href*='/class/']").map { it.text().trim() }
        val year = doc.selectFirst("a[href*='/year/']")?.text()?.trim()?.toIntOrNull()

        fun parseEpisodes(selector: String): List<Episode> = doc.select(selector).mapNotNull { a ->
            val episodeUrl = absoluteUrl(a.attr("href")) ?: return@mapNotNull null
            val episodeName = a.text().trim()
            newEpisode(episodeUrl) {
                name = episodeName
                episode = episodeName.filter(Char::isDigit).toIntOrNull()
            }
        }.reversed()

        val subEpisodes = mutableListOf<Episode>()
        val dubEpisodes = mutableListOf<Episode>()
        val tabs = doc.select(".playlist-tab-box .tab-item")

        if (tabs.isEmpty()) {
            subEpisodes += parseEpisodes(".episode-box .episodelist a")
        } else {
            tabs.forEach { tab ->
                val target = tab.attr("data-target")
                if (target.isBlank()) return@forEach
                val selector = if (target.startsWith("#")) "$target .episodelist a" else "#$target .episodelist a"
                val episodes = parseEpisodes(selector)
                if (tab.text().contains("dub", true) || tab.text().contains("더빙")) {
                    dubEpisodes += episodes
                } else {
                    subEpisodes += episodes
                }
            }
        }

        if (subEpisodes.isEmpty() && dubEpisodes.isEmpty()) {
            subEpisodes += parseEpisodes(".episodelist a")
        }

        return newAnimeLoadResponse(title, fullUrl, TvType.Anime) {
            posterUrl = poster
            plot = description
            this.tags = tags
            this.year = year
            if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
            if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val watchUrl = absoluteUrl(data) ?: data

        return try {
            val result = LinkkfExtractor().extract(watchUrl, "$mainUrl/")
            val stream = when {
                result == null -> captureUrl(watchUrl, "$mainUrl/", M3U8_REGEX)?.let { it to watchUrl }
                result.needsWebView -> captureUrl(result.url, result.referer, M3U8_REGEX)?.let { it to result.url }
                else -> result.url to result.referer
            } ?: return false

            captureUrl(watchUrl, "$mainUrl/", VTT_REGEX)?.let { subtitleUrl ->
                subtitleCallback(newSubtitleFile(lang = "Korean", url = subtitleUrl))
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = stream.first,
                    type = ExtractorLinkType.M3U8
                ) {
                    referer = stream.second
                    quality = getQualityFromName("HD")
                }
            )

            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }

    private suspend fun captureUrl(pageUrl: String, referer: String, pattern: Regex): String? {
        return try {
            val response = app.get(
                pageUrl,
                headers = commonHeaders + ("Referer" to referer),
                interceptor = WebViewResolver(pattern)
            )
            response.url.takeIf { pattern.containsMatchIn(it) }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a.vod-item-img") ?: return null
        val title = selectFirst(".vod-item-title a")?.text()?.trim() ?: return null
        val href = absoluteUrl(link.attr("href")) ?: return null
        val poster = absoluteUrl(
            selectFirst(".img-wrapper")?.attr("data-original")
                ?.ifBlank { selectFirst("img")?.attr("src").orEmpty() }
        )

        return newTvSeriesSearchResponse(title, href, TvType.Anime) {
            posterUrl = poster
        }
    }

    private fun absoluteUrl(raw: String?): String? {
        val value = raw?.trim().orEmpty()
        if (value.isBlank()) return null
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/${value.trimStart('/')}"
        }
    }

    private companion object {
        val M3U8_REGEX = Regex("""\.m3u8(?:\?|$)""", RegexOption.IGNORE_CASE)
        val VTT_REGEX = Regex("""\.vtt(?:\?|$)""", RegexOption.IGNORE_CASE)
    }
}
