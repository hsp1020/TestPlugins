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
    override val supportedTypes = setOf(TvType.Anime,TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "bbs/board.php?bo_table=ing" to "방영중",
        "bbs/board.php?bo_table=fin" to "종영",
        "bbs/board.php?bo_table=theater" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl/${request.data}&page=$page"
        println("[OHLI24][MAIN] GET start name=${request.name} page=$page url=$url")

        val response = app.get(
            url,
            timeout = 100,
            headers = browserHeaders()
        )

        val html = response.text
        val document = response.document
        val pageTitle = document.title().trim()
        val lower = html.lowercase()

        val blockedMarkers = listOf(
            "just a moment",
            "attention required",
            "cf-browser-verification",
            "cf-chl-",
            "challenge-platform",
            "checking your browser",
            "access denied",
            "403 forbidden",
            "error 403",
            "captcha"
        ).filter { lower.contains(it) }

        // 2026-10 current site: content pages are /c/<title>.
        // Do not depend on the removed list-row/post-title template.
        val contentAnchors = findContentAnchors(document)
        val home = contentAnchors.mapNotNull { it.toRouteSearchResult() }

        println(
            "[OHLI24][MAIN] response html=${html.length} title='$pageTitle' " +
                "contentAnchors=${contentAnchors.size} parsed=${home.size}"
        )

        if (blockedMarkers.isNotEmpty()) {
            println("[OHLI24][BLOCK] suspected anti-bot page markers=${blockedMarkers.joinToString()}")
        }

        if (home.isEmpty()) {
            val bodySample = document.body()?.text()
                ?.replace(Regex("\\s+"), " ")
                ?.take(700)
                .orEmpty()
            val hrefSamples = document.select("a[href]")
                .map { it.attr("href") }
                .filter { it.isNotBlank() }
                .distinct()
                .take(20)

            println("[OHLI24][DIAG] EMPTY main page")
            println("[OHLI24][DIAG] title='$pageTitle'")
            println("[OHLI24][DIAG] bodySample=$bodySample")
            println("[OHLI24][DIAG] hrefSamples=${hrefSamples.joinToString(" | ")}")
        } else {
            home.take(3).forEachIndexed { index, item ->
                println("[OHLI24][MAIN] sample[$index] name='${item.name}' url='${item.url}'")
            }
        }

        return newHomePageResponse(
            list = HomePageList(
                name = request.name,
                list = home,
                isHorizontalImages = false
            ),
            hasNext = home.isNotEmpty()
        )
    }

    private fun browserHeaders(): Map<String, String> = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Cache-Control" to "no-cache",
        "Pragma" to "no-cache",
        "Referer" to "$mainUrl/"
    )

    private fun findContentAnchors(root: Element): List<Element> {
        return root.select("a[href]")
            .filter { anchor ->
                val href = normalizeSiteUrl(anchor.attr("href"))
                href.startsWith("$mainUrl/c/")
            }
            .distinctBy { normalizeSiteUrl(it.attr("href")) }
    }

    private fun Element.toRouteSearchResult(): SearchResponse? {
        val rawHref = attr("href").trim()
        if (rawHref.isBlank()) return null

        val href = normalizeSiteUrl(rawHref)
        if (!href.startsWith("$mainUrl/c/")) return null

        val title = titleFromContentUrl(href).ifBlank {
            text()
                .replace(Regex("Update!", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\d{4}-\\d{2}-\\d{2}.*$"), "")
                .trim()
        }
        if (title.isBlank()) {
            println("[OHLI24][PARSE] skip route: title missing href=$href")
            return null
        }

        val posterUrl = findPosterAround(this)
        val scoreText = findNearbyScore(this)

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            if (scoreText.isNotBlank()) {
                this.score = Score.from10(scoreText)
            }
        }
    }

    private fun titleFromContentUrl(url: String): String {
        val encoded = url.substringAfter("/c/", "")
            .substringBefore('?')
            .substringBefore('#')
        if (encoded.isBlank()) return ""
        return try {
            URLDecoder.decode(encoded, "UTF-8").trim()
        } catch (_: Exception) {
            encoded.trim()
        }
    }

    private fun findPosterAround(anchor: Element): String? {
        var node: Element? = anchor
        repeat(5) {
            val current = node ?: return null
            val contentUrls = current.select("a[href]")
                .map { normalizeSiteUrl(it.attr("href")) }
                .filter { it.startsWith("$mainUrl/c/") }
                .distinct()

            // Once an ancestor contains multiple cards, stop before borrowing a sibling poster.
            if (contentUrls.size > 1) return null

            val image = current.selectFirst("img")
            if (image != null) {
                val raw = listOf(
                    "src",
                    "data-src",
                    "data-original",
                    "data-lazy-src",
                    "data-ezsrc"
                ).asSequence()
                    .map { image.attr(it).trim() }
                    .firstOrNull { value ->
                        value.isNotBlank() &&
                            !value.startsWith("data:", ignoreCase = true) &&
                            value != "#"
                    }
                if (!raw.isNullOrBlank()) return normalizeSiteUrl(raw)
            }
            node = current.parent()
        }
        return null
    }

    private fun findNearbyScore(anchor: Element): String {
        var node: Element? = anchor
        repeat(4) {
            val score = node?.selectFirst(".rating, [class*=rating]")?.text()?.trim().orEmpty()
            if (score.toDoubleOrNull() != null) return score
            node = node?.parent()
        }
        return ""
    }

    private fun normalizeSiteUrl(rawUrl: String): String {
        val url = rawUrl.trim()
        return when {
            url.startsWith("http://", ignoreCase = true) ||
                url.startsWith("https://", ignoreCase = true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("../") -> "$mainUrl/${url.removePrefix("../")}"
            url.startsWith("./") -> "$mainUrl/bbs/${url.removePrefix("./")}"
            url.startsWith("board.php") || url.startsWith("search.php") -> "$mainUrl/bbs/$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> fixUrl(url)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val url = "$mainUrl/bbs/search.php?srows=24&gr_id=&sfl=wr_subject&stx=$encoded"
        println("[OHLI24][SEARCH] GET query='$query' url=$url")
        val document = app.get(url, timeout = 100, headers = browserHeaders()).document
        val results = findContentAnchors(document).mapNotNull { it.toRouteSearchResult() }
        println("[OHLI24][SEARCH] parsed=${results.size}")
        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 100, headers = browserHeaders()).document
        doc.setBaseUri(url)

        val title = doc.selectFirst("h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                ?.substringBeforeLast(" - 애니24")?.trim()
            ?: titleFromContentUrl(url)
            .ifBlank { "UnKnown" }

        val poster = sequenceOf(
            doc.selectFirst("meta[property=og:image]")?.attr("content"),
            doc.selectFirst("img[itemprop=image]")?.attr("src"),
            doc.select("img").firstOrNull { it.attr("alt").trim() == title }?.attr("src")
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.let(::normalizeSiteUrl)

        val bodyText = doc.body()?.text()?.replace(Regex("\\s+"), " ").orEmpty()
        val genres = Regex("장르\\s+(.+?)\\s+(?:방영일|상영일|등급|총화수|런타임|별점)")
            .find(bodyText)?.groupValues?.getOrNull(1)
            ?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() }
            ?: emptyList()

        val year = Regex("(?:방영일|상영일)\\s+(\\d{4})")
            .find(bodyText)?.groupValues?.getOrNull(1)?.toIntOrNull()

        val descript = Regex("줄거리\\s+(.+?)\\s+에피소드")
            .find(bodyText)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        // 2026-10 current detail pages expose episodes through /e/<episode title>.
        val episodeAnchors = doc.select("a[href]")
            .filter { normalizeSiteUrl(it.attr("href")).startsWith("$mainUrl/e/") }
            .distinctBy { normalizeSiteUrl(it.attr("href")) }

        val hasNumberedEpisodes = episodeAnchors.any { anchor ->
            Regex("\\d+(?:\\.\\d+)?화(?:\\(完\\))?").containsMatchIn(anchor.text()) ||
                Regex("\\d+(?:\\.\\d+)?화").containsMatchIn(decodeEpisodeUrl(anchor.attr("href")))
        }
        val type = if (episodeAnchors.size > 1 || hasNumberedEpisodes) TvType.TvSeries else TvType.Movie

        println(
            "[OHLI24][LOAD] title='$title' type=$type episodes=${episodeAnchors.size} " +
                "poster=${!poster.isNullOrBlank()} genres=${genres.size}"
        )

        if (type == TvType.TvSeries) {
            val episodes = episodeAnchors.map { anchor ->
                val href = normalizeSiteUrl(anchor.attr("href"))
                val label = anchor.text().trim().ifBlank { decodeEpisodeUrl(href) }
                val epno = Regex("(\\d+)(?:\\.\\d+)?화(?:\\(完\\))?")
                    .find(label)?.groupValues?.getOrNull(1)?.toIntOrNull()

                newEpisode(href) {
                    this.name = label.ifBlank { if (epno != null) "Episode $epno" else title }
                    this.episode = epno
                }
            }.sortedBy { it.episode ?: Int.MAX_VALUE }

            return newAnimeLoadResponse(title, url, TvType.Anime) {
                addEpisodes(DubStatus.Subbed, episodes)
                this.year = year
                this.tags = genres
                this.posterUrl = poster
                this.plot = descript
            }
        } else {
            val href = episodeAnchors.firstOrNull()?.attr("href")
                ?.let(::normalizeSiteUrl)
                .orEmpty()

            return newMovieLoadResponse(title, url, TvType.AnimeMovie, href) {
                this.year = year
                this.tags = genres
                this.posterUrl = poster
                this.plot = descript
            }
        }
    }

    private fun decodeEpisodeUrl(rawUrl: String): String {
        val url = normalizeSiteUrl(rawUrl)
        val encoded = url.substringAfter("/e/", "")
            .substringBefore('?')
            .substringBefore('#')
        if (encoded.isBlank()) return ""
        return try {
            URLDecoder.decode(encoded, "UTF-8").trim()
        } catch (_: Exception) {
            encoded.trim()
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 20, headers = browserHeaders()).document
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("src")?.trim()
        val vurl = doc.selectFirst("form.tt input[name=vurl]")?.attr("value")?.trim()
        val playerLink = doc.select("a[href]")
            .firstOrNull { anchor ->
                val href = anchor.attr("href")
                href.contains("cdndania.com/video/", ignoreCase = true) ||
                    href.contains("michealcdn.com", ignoreCase = true)
            }?.attr("href")?.trim()

        val iframe = listOfNotNull(iframeSrc, vurl, playerLink)
            .firstOrNull { it.isNotBlank() }
            ?.let(::normalizeSiteUrl)
            .orEmpty()

        println("[OHLI24][LINK] data=$data player='$iframe'")
        if (iframe.isBlank()) {
            println("[OHLI24][LINK] no iframe/form/player anchor found")
            return false
        }

        if (iframe.contains("cdndania.com/video/", ignoreCase = true) || iframe.contains("/video/")) {
            Cdndania().getUrl(iframe, mainUrl, subtitleCallback, callback)
        } else {
            loadExtractor(iframe, mainUrl, subtitleCallback, callback)
        }
        return true
    }

}
