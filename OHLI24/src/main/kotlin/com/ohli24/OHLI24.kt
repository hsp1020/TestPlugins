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
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
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
        val doc = app.get("$mainUrl/${request.data}&page=$page", headers = headers, timeout = 100).document
        val items = routeAnchors(doc, "c").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            HomePageList(request.name, items, isHorizontalImages = false),
            hasNext = items.isNotEmpty()
        )
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
        return newMovieSearchResponse(title, href, TvType.Anime) {
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
        val body = doc.body()?.text()?.replace(Regex("\\s+"), " ").orEmpty()
        val genres = Regex("장르\\s+(.+?)\\s+(?:방영일|상영일|등급|총화수|런타임|별점)")
            .find(body)?.groupValues?.getOrNull(1)?.split(',')
            ?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        val year = Regex("(?:방영일|상영일)\\s+(\\d{4})").find(body)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
        val plot = Regex("줄거리\\s+(.+?)(?:\\s+에피소드|$)").find(body)
            ?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val episodeAnchors = routeAnchors(doc, "e")
        val isSeries = when (declaredType(doc.selectFirst("script[data-page=app]")?.data().orEmpty())) {
            TvType.Anime -> true
            TvType.AnimeMovie -> false
            else -> episodeAnchors.size > 1 || episodeAnchors.any {
                episodeNumber(routeText(it.attr("href"), "e")) != null || episodeNumber(it.text()) != null
            }
        }

        if (isSeries) {
            val episodes = episodeAnchors.map { anchor ->
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
                posterUrl = poster
                this.plot = plot
            }
        }

        val data = episodeAnchors.firstOrNull()?.attr("href")?.let(::absoluteUrl).orEmpty()
        return newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
            this.year = year
            tags = genres
            posterUrl = poster
            this.plot = plot
        }
    }

    private fun declaredType(data: String): TvType? {
        val kind = Regex("\\\"card_meta\\\"\\s*:\\s*\\{[^}]*\\\"kind\\\"\\s*:\\s*\\\"(series|movie)\\\"", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.getOrNull(1)
        val role = Regex("\\\"role\\\"\\s*:\\s*\\\"(series|movie)\\\"", RegexOption.IGNORE_CASE)
            .find(data)?.groupValues?.getOrNull(1)
        return when ((kind ?: role)?.lowercase()) {
            "series" -> TvType.Anime
            "movie" -> TvType.AnimeMovie
            else -> null
        }
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
