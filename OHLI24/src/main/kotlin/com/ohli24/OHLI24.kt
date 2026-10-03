package com.ohli24

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class OHLI24 : MainAPI() {
    override var mainUrl = "https://ani.ohli24.com"
    override var name = "OHLI24"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    override val mainPage = mainPageOf(
        "__popular__" to "인기 작품 TOP 10",
        "bbs/board.php?bo_table=ing" to "방영중",
        "bbs/board.php?bo_table=fin" to "종영",
        "bbs/board.php?bo_table=theater" to "극장판"
    )

    private val headers = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == "__popular__") {
            val items = if (page == 1) getPopular() else emptyList()
            return newHomePageResponse(
                HomePageList(request.name, items, isHorizontalImages = false),
                hasNext = false
            )
        }

        val doc = app.get("$mainUrl/${request.data}&page=$page", headers = headers, timeout = 100).document
        val items = routeAnchors(doc, "c").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
    }

    private suspend fun getPopular(): List<SearchResponse> {
        val home = app.get(mainUrl, headers = headers, timeout = 100).document
        val detailUrl = routeAnchors(home, "c").firstOrNull()?.attr("href")
            ?.let(::absoluteUrl) ?: return emptyList()
        val detail = app.get(detailUrl, headers = headers, timeout = 100).document

        return detail.select("#sidebar-tabpanel-popular li > a[href]")
            .take(10)
            .mapNotNull { it.toPopularResult() }
    }

    private fun Element.toPopularResult(): SearchResponse? {
        val href = absoluteUrl(attr("href"))
        val title = selectFirst("img[alt]")?.attr("alt")?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: routeText(href, "c").ifBlank { return null }
        val poster = imageUrl(selectFirst("img"))
        val rating = select("span.font-semibold.tabular-nums.text-foreground")
            .map { it.text().trim() }
            .firstOrNull { it.toDoubleOrNull() != null }

        return newAnimeSearchResponse(title, href) {
            posterUrl = poster
            rating?.toDoubleOrNull()?.let { score = Score.from10(it.toString()) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val doc = app.get(
            "$mainUrl/bbs/search.php?srows=24&gr_id=&sfl=wr_subject&stx=$encoded",
            headers = headers,
            timeout = 100
        ).document
        return routeAnchors(doc, "c").mapNotNull { it.toSearchResult() }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = absoluteUrl(attr("href"))
        val title = routeText(href, "c").ifBlank { text().trim() }
        if (title.isBlank()) return null

        val card = cardRoot(this)
        val poster = imageUrl(card.selectFirst("img"))
        val rating = card.selectFirst(".rating, [class*=rating]")?.text()?.trim().orEmpty()
        return newAnimeSearchResponse(title, href) {
            posterUrl = poster
            if (rating.toDoubleOrNull() != null) score = Score.from10(rating)
        }
    }

    private fun cardRoot(anchor: Element): Element {
        var current = anchor
        repeat(5) {
            val parent = current.parent() ?: return current
            if (parent.select("a[href]").count {
                    absoluteUrl(it.attr("href")).startsWith("$mainUrl/c/")
                } > 1
            ) return current
            current = parent
        }
        return current
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 100).document
        doc.setBaseUri(url)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBeforeLast(" - 애니24")?.trim()
            ?: routeText(url, "c").ifBlank { "UnKnown" }
        val poster = imageUrl(doc.selectFirst(
            ".view-info .image img, img[itemprop=image], .serial-movie-wrap img, .image img"
        ))
        val info = doc.select(".view-info .list > p").mapNotNull { row ->
            val values = row.select("span.block").map { it.text().trim() }
            if (values.size >= 2) values[0] to values[1] else null
        }.toMap()
        val genres = info["장르"]?.split(',')
            ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val year = info["방영일"]?.let {
            Regex("\\b(\\d{4})\\.").find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
        }
        val ageRating = info["등급"]?.takeIf { it.isNotBlank() }
        val plot = doc.selectFirst(".view-story .view-stocon")?.text()?.trim().orEmpty()

        val episodes = loadAllEpisodeAnchors(url, doc).map { anchor ->
            val href = absoluteUrl(anchor.attr("href"))
            val routeLabel = routeText(href, "e")
            val visibleLabel = anchor.text().trim()
            val number = episodeNumber(routeLabel) ?: episodeNumber(visibleLabel)
            newEpisode(href) {
                name = routeLabel.ifBlank { visibleLabel.ifBlank { number?.let { "Episode $it" } ?: title } }
                episode = number
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.Subbed, episodes)
            this.year = year
            tags = genres
            contentRating = ageRating
            posterUrl = poster
            this.plot = plot
        }
    }

    private suspend fun loadAllEpisodeAnchors(url: String, firstDoc: Element): List<Element> {
        val pageData = firstDoc.selectFirst("script[data-page=app]")?.data().orEmpty()
        val lastPage = Regex("\"last_page\":(\\d+)")
            .find(pageData)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 1

        val all = routeAnchors(firstDoc, "e").toMutableList()
        if (lastPage <= 1) return all

        coroutineScope {
            for (batch in (2..lastPage).chunked(4)) {
                batch.map { page ->
                    async {
                        val pageUrl = "${url.substringBefore('?')}?page=$page"
                        app.get(pageUrl, headers = headers, timeout = 100).document
                    }
                }.awaitAll().forEach { pageDoc ->
                    all += routeAnchors(pageDoc, "e")
                }
            }
        }

        return all.distinctBy { absoluteUrl(it.attr("href")) }
    }

    private fun episodeNumber(text: String): Int? =
        Regex("(\\d+)(?:\\.\\d+)?화(?:\\(完\\))?").find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    private fun routeAnchors(root: Element, route: String): List<Element> =
        root.select("a[href]").filter {
            absoluteUrl(it.attr("href")).startsWith("$mainUrl/$route/")
        }.distinctBy { absoluteUrl(it.attr("href")) }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null
        return listOf("src", "data-src", "data-original", "data-lazy-src", "data-ezsrc", "content")
            .firstNotNullOfOrNull { key ->
                element.attr(key).trim().takeIf { it.isNotBlank() && it != "#" && !it.startsWith("data:", true) }
            }?.let(::absoluteUrl)
    }

    private fun absoluteUrl(raw: String): String {
        val value = raw.trim()
        if (value.isBlank()) return ""
        return runCatching { URI("$mainUrl/").resolve(value).toString() }.getOrDefault(value)
    }

    private fun routeText(raw: String, route: String): String {
        val encoded = absoluteUrl(raw).substringAfter("/$route/", "").substringBefore('?').substringBefore('#')
        if (encoded.isBlank()) return ""
        return runCatching { URLDecoder.decode(encoded, "UTF-8").trim() }.getOrDefault(encoded.trim())
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        val doc = app.get(data, headers = headers, timeout = 20).document
        val player = sequenceOf(
            doc.selectFirst("iframe[src]")?.attr("src"),
            doc.selectFirst("form.tt input[name=vurl]")?.attr("value"),
            doc.select("a[href]").firstOrNull {
                val href = it.attr("href")
                href.contains("cdndania.com/video/", true) || href.contains("michealcdn.com", true)
            }?.attr("href")
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.let(::absoluteUrl).orEmpty()
        if (player.isBlank()) return false

        if (player.contains("cdndania.com/video/", true)) {
            Cdndania().getUrl(player, mainUrl, subtitleCallback, callback)
        } else {
            loadExtractor(player, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
