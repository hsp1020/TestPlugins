package com.linkkf

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Element
import kotlinx.coroutines.CancellationException

class Linkkf : MainAPI() {
    override var mainUrl = "https://linkkf.tv"
    override var name = "Linkkf"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.Anime, TvType.OVA)

    private val commonHeaders = mapOf(
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
        val url = if (page == 1) "$mainUrl${request.data}/" else "${mainUrl}${if(request.data.endsWith("/")) request.data else "${request.data}/"}page/$page/"
        
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val list = doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
            
            val isLabelCatalog = request.data.startsWith("/label/")
            val hasNextPage = if (isLabelCatalog) false else list.isNotEmpty()
            
            return newHomePageResponse(
                list = HomePageList(name = request.name, list = list, isHorizontalImages = true),
                hasNext = hasNextPage
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/view/wd/$query/"
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            doc.select(".vod-item").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        try {
            val doc = app.get(url, headers = commonHeaders).document
            val title = doc.selectFirst(".detail-info-title")?.text()?.trim() ?: "Unknown Title"
            
            val poster = doc.selectFirst(".detail-img img")?.let { img ->
                val original = img.attr("data-original")
                val src = img.attr("src")
                if (original.isNotEmpty()) original else src
            }

            var description = doc.selectFirst(".detail-desc-content")?.text()?.trim()
            if (description.isNullOrEmpty()) description = "다시보기"
            
            val tags = doc.select(".detail-info-desc a[href*='/class/']").map { it.text().trim() }
            val yearStr = doc.selectFirst("a[href*='/year/']")?.text()?.trim()
            val year: Int? = yearStr?.toIntOrNull()

            val subEpisodes = mutableListOf<Episode>()
            val dubEpisodes = mutableListOf<Episode>()

            val tabs = doc.select(".playlist-tab-box .tab-item")

            fun parseEpisodes(selector: String): List<Episode> {
                return doc.select(selector).mapNotNull { aTag ->
                    val href = aTag.attr("href")
                    val name = aTag.text().trim()
                    if (href.isNotEmpty()) {
                        newEpisode(href) {
                            this.name = name
                            this.episode = name.filter { it.isDigit() }.toIntOrNull()
                        }
                    } else null
                }.reversed()
            }

            if (tabs.isNotEmpty()) {
                tabs.forEach { tab ->
                    val tabText = tab.text().lowercase()
                    val targetId = tab.attr("data-target")
                    if (targetId.isNotEmpty()) {
                        val episodes = parseEpisodes("$targetId .episodelist a")
                        if (tabText.contains("dub") || tabText.contains("더빙")) dubEpisodes.addAll(episodes)
                        else subEpisodes.addAll(episodes)
                    }
                }
            } else {
                subEpisodes.addAll(parseEpisodes(".episode-box .episodelist a"))
            }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                this.posterUrl = poster; this.plot = description; this.tags = tags; this.year = year
                if (subEpisodes.isNotEmpty()) addEpisodes(DubStatus.Subbed, subEpisodes)
                if (dubEpisodes.isNotEmpty()) addEpisodes(DubStatus.Dubbed, dubEpisodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val fullUrl = if (data.startsWith("http")) data else "$mainUrl$data"
        
        try {
            val result = LinkkfExtractor().extract(fullUrl, "$mainUrl/") ?: return false

            if (result.subtitleUrl.isNotEmpty()) subtitleCallback.invoke(newSubtitleFile("Korean", result.subtitleUrl))

            val playerPageUrl = result.m3u8Url
            val targetReferer = if (result.needsWebView) playerPageUrl else "$mainUrl/"
            var finalM3u8Url = result.m3u8Url

            if (result.needsWebView) {
                val response = app.get(result.m3u8Url, headers = commonHeaders, interceptor = WebViewResolver(Regex("""\.m3u8""")))
                finalM3u8Url = response.url
            }

            callback.invoke(newExtractorLink(source = name, name = name, url = finalM3u8Url, type = ExtractorLinkType.M3U8) {
                this.referer = targetReferer
                this.quality = getQualityFromName("HD")
            })
            return true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.vod-item-img") ?: return null
        val title = this.selectFirst(".vod-item-title a")?.text()?.trim() ?: return null
        val href = aTag.attr("href")
        val poster = this.selectFirst(".img-wrapper")?.attr("data-original")?.ifEmpty { this.selectFirst("img")?.attr("src") }
        return newTvSeriesSearchResponse(title, href, TvType.Anime) { this.posterUrl = poster }
    }
}
