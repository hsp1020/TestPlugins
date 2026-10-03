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
import com.lagradost.cloudstream3.fixUrlNull
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

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
            headers = mapOf(
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cache-Control" to "no-cache",
                "Pragma" to "no-cache",
                "Referer" to "$mainUrl/"
            )
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
            "captcha",
            "enable javascript"
        ).filter { lower.contains(it) }

        val rows = document.select("div.list-row")
        val postTitles = document.select("div.post-title, .post-title")
        val itemSubjects = document.select("a.item-subject")

        println(
            "[OHLI24][MAIN] response html=${html.length} title='$pageTitle' " +
                "listRows=${rows.size} postTitles=${postTitles.size} itemSubjects=${itemSubjects.size}"
        )

        if (blockedMarkers.isNotEmpty()) {
            println("[OHLI24][BLOCK] suspected anti-bot page markers=${blockedMarkers.joinToString()}")
        }

        val home = rows.mapNotNull { it.toSearchResult() }
        println("[OHLI24][MAIN] parsed=${home.size}/${rows.size}")

        if (rows.isEmpty() || home.isEmpty()) {
            val bodySample = document.body()?.text()
                ?.replace(Regex("\\s+"), " ")
                ?.take(500)
                .orEmpty()
            val listLikeClasses = document.select("div[class*=list]")
                .map { it.className() }
                .filter { it.isNotBlank() }
                .distinct()
                .take(15)

            println("[OHLI24][DIAG] EMPTY main page")
            println("[OHLI24][DIAG] title='$pageTitle'")
            println("[OHLI24][DIAG] bodySample=$bodySample")
            println("[OHLI24][DIAG] listLikeClasses=${listLikeClasses.joinToString()}")
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


    private fun Element.toSearchResult(): SearchResponse? {
        val title = selectFirst(
            "div.post-title, .post-title, a.item-subject, div.list-desc a"
        )?.text()?.trim().orEmpty()

        if (title.isBlank()) {
            println(
                "[OHLI24][PARSE] skip row: title missing " +
                    "class='${className()}' text='${text().take(120)}'"
            )
            return null
        }

        val rawHref = selectFirst(
            "div.list-desc a[href], a.item-subject[href], a[href*='board.php'][href], a[href]"
        )?.attr("href")?.trim().orEmpty()

        if (rawHref.isBlank()) {
            println("[OHLI24][PARSE] skip '$title': href missing")
            return null
        }

        val href = normalizeSiteUrl(rawHref)

        val img = selectFirst("img")
        val rawPoster = img?.let { image ->
            listOf("src", "data-src", "data-original", "data-lazy-src", "data-ezsrc")
                .asSequence()
                .map { image.attr(it).trim() }
                .firstOrNull { value ->
                    value.isNotBlank() &&
                        !value.startsWith("data:", ignoreCase = true) &&
                        value != "#"
                }
        }.orEmpty()

        val posterUrl = rawPoster.takeIf { it.isNotBlank() }?.let(::normalizeSiteUrl)
        val score = select("div.rating, .rating").text().trim()

        return newMovieSearchResponse(title, href, TvType.Movie) {
            this.posterUrl = posterUrl
            if (score.isNotBlank()) {
                this.score = Score.from10(score)
            }
        }
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
        return app.get("$mainUrl/bbs/search.php?srows=24&gr_id=&sfl=wr_subject&stx=$query", timeout = 100).document
            .select("div.list-row").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, timeout = 100).document
        doc.setBaseUri(url)
        val title = doc.selectFirst("div.view-title h1")?.text() ?: "UnKnown"
        val poster = fixUrlNull(doc.selectFirst("div.image img")?.attr("src") ?: doc.selectFirst("meta[property=og:image]")?.attr("content"))
        val genres = doc.select("p:contains(장르)").first()?.select("span:nth-of-type(2)")?.text()?.split(",")?.map { it.trim() } ?: emptyList()
        val year = title.substringAfterLast("(").substringBefore(")").toIntOrNull()
        val descript = doc.select("div.view-stocon,div.view-cont").html().split("<br>").map { Jsoup.parse(it).text().trim() }.filter { it.isNotEmpty() }.joinToString("\n")

        val items = doc.select("li.list-item a")

        val hasEpisodeText = items.any {
            it.text().contains("episode", ignoreCase = true) ||
                    it.text().contains("회") ||
                    it.text().contains("화")
        }

        val type = when {
            items.size > 1 -> TvType.TvSeries
            hasEpisodeText -> TvType.TvSeries
            else -> TvType.Movie
        }

        val href= fixUrl(doc.selectFirst("li.list-item a")?.absUrl("href").orEmpty())


        if (type == TvType.TvSeries)
        {
            val episodes= doc.select("li.list-item").map {
                val epno = it.select("div.wr-num").text().toIntOrNull()
                val href = it.selectFirst("a")?.absUrl("href").orEmpty()

                newEpisode(href)
                {
                    this.name = "Episode $epno"
                    this.episode = epno
                }

            }
            return newAnimeLoadResponse(title, url, TvType.Anime)
            {
                addEpisodes(DubStatus.Subbed, episodes.reversed())
                this.year = year
                this.tags = genres
                this.posterUrl = poster
                this.plot = descript
            }
        }
        else
        {
            return newMovieLoadResponse(title, url, TvType.AnimeMovie, href)
            {
                this.year = year
                this.tags = genres
                this.posterUrl = poster
                this.plot = descript
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data, timeout = 20).document
        val iframeSrc = doc.selectFirst("iframe")?.attr("src")
        val vurl = doc.selectFirst("form.tt input[name=vurl]")?.attr("value")
        val iframe = iframeSrc ?: vurl.orEmpty()
        if (iframe.contains("/video/"))
        {
            Cdndania().getUrl(iframe,mainUrl,subtitleCallback,callback)
        }
        else loadExtractor(iframe,mainUrl,subtitleCallback,callback)
        return true
    }

}
