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
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class OHLI24 : MainAPI() {
    override var mainUrl = "https://ani.ohli24.com"
    override var name = "OHLI24"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
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
        val doc = app.get(
            "$mainUrl/${request.data}&page=$page",
            headers = headers,
            timeout = 100
        ).document
        val items = routeAnchors(doc, "c").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = items,
                isHorizontalImages = false
            ),
            hasNext = items.isNotEmpty()
        )
    }

    private fun routeAnchors(root: Element, route: String): List<Element> =
        root.select("a[href]")
            .filter { normalizeUrl(it.attr("href")).startsWith("$mainUrl/$route/") }
            .distinctBy { normalizeUrl(it.attr("href")) }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = normalizeUrl(attr("href"))
        val title = decodeRoute(href, "c").ifBlank { text().trim() }
        if (title.isBlank()) return null

        val card = cardRoot(this)
        val poster = imageUrl(card.selectFirst("img"))
        val score = card.selectFirst(".rating, [class*=rating]")?.text()?.trim().orEmpty()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            posterUrl = poster
            if (score.toDoubleOrNull() != null) this.score = Score.from10(score)
        }
    }

    private fun cardRoot(anchor: Element): Element {
        var current = anchor
        repeat(5) {
            val parent = current.parent() ?: return current
            val contentLinks = parent.select("a[href]").count {
                normalizeUrl(it.attr("href")).startsWith("$mainUrl/c/")
            }
            if (contentLinks > 1) return current
            current = parent
        }
        return current
    }

    private fun imageUrl(element: Element?): String? {
        if (element == null) return null
        val raw = listOf("src", "data-src", "data-original", "data-lazy-src", "data-ezsrc", "content")
            .asSequence()
            .map { element.attr(it).trim() }
            .firstOrNull { it.isNotBlank() && it != "#" && !it.startsWith("data:", true) }
        return raw?.let(::normalizeUrl)
    }

    private fun normalizeUrl(raw: String): String {
        val url = raw.trim()
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("../") -> "$mainUrl/${url.removePrefix("../")}" 
            url.startsWith("/") -> "$mainUrl$url"
            else -> fixUrl(url)
        }
    }

    private fun decodeRoute(raw: String, route: String): String {
        val encoded = normalizeUrl(raw).substringAfter("/$route/", "")
            .substringBefore('?').substringBefore('#')
        if (encoded.isBlank()) return ""
        return runCatching { URLDecoder.decode(encoded, "UTF-8").trim() }
            .getOrDefault(encoded.trim())
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

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 100).document
        doc.setBaseUri(url)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBeforeLast(" - 애니24")?.trim()
            ?: decodeRoute(url, "c").ifBlank { "UnKnown" }

        // OHLI24의 og:image는 공통 배너일 수 있으므로 실제 작품 이미지에서만 선택한다.
        val poster = sequenceOf(
            doc.selectFirst("img[itemprop=image]"),
            doc.selectFirst("meta[itemprop=image]"),
            doc.selectFirst(".view-wrap img"),
            doc.selectFirst(".serial-movie-wrap img"),
            doc.selectFirst(".image img"),
            doc.select("img").firstOrNull { it.attr("alt").trim().equals(title, true) }
        ).mapNotNull(::imageUrl).firstOrNull()

        val body = doc.body()?.text()?.replace(Regex("\\s+"), " ").orEmpty()
        val genres = Regex("장르\\s+(.+?)\\s+(?:방영일|상영일|등급|총화수|런타임|별점)")
            .find(body)?.groupValues?.getOrNull(1)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()
        val year = Regex("(?:방영일|상영일)\\s+(\\d{4})")
            .find(body)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = Regex("줄거리\\s+(.+?)\\s+에피소드")
            .find(body)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val episodeAnchors = routeAnchors(doc, "e")
        val isSeries = episodeAnchors.size > 1 || episodeAnchors.any {
            episodeNumber(it.text().ifBlank { decodeRoute(it.attr("href"), "e") }) != null
        }

        if (isSeries) {
            val episodes = episodeAnchors.map { anchor ->
                val href = normalizeUrl(anchor.attr("href"))
                val label = anchor.text().trim().ifBlank { decodeRoute(href, "e") }
                val number = episodeNumber(label)
                newEpisode(href) {
                    name = label.ifBlank { number?.let { "Episode $it" } ?: title }
                    episode = number
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.year = year
                this.tags = genres
                this.posterUrl = poster
                this.plot = plot
            }
        }

        val data = episodeAnchors.firstOrNull()?.attr("href")?.let(::normalizeUrl).orEmpty()
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
            this.year = year
            this.tags = genres
            this.posterUrl = poster
            this.plot = plot
        }
    }

    private fun episodeNumber(text: String): Int? =
        Regex("(\\d+)(?:\\.\\d+)?화(?:\\(完\\))?")
            .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull()

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, headers = headers, timeout = 20).document
        val player = sequenceOf(
            doc.selectFirst("iframe[src]")?.attr("src"),
            doc.selectFirst("form.tt input[name=vurl]")?.attr("value"),
            doc.select("a[href]").firstOrNull {
                val href = it.attr("href")
                href.contains("cdndania.com/video/", true) || href.contains("michealcdn.com", true)
            }?.attr("href")
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.let(::normalizeUrl).orEmpty()

        if (player.isBlank()) return false

        if (player.contains("cdndania.com/video/", true)) {
            Cdndania().getUrl(player, mainUrl, subtitleCallback, callback)
        } else {
            loadExtractor(player, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
