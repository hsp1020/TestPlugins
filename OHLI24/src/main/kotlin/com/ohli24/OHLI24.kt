package com.ohli24

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newTvSeriesSearchResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

class OHLI24 : MainAPI() {

    companion object {
        private const val DEFAULT_DOMAIN = "https://a101.ohli24.com"
        private const val PREFS_NAME = "OHLI24_Domain_Cache"
        private const val PREF_KEY = "current_domain"
        private val domainPattern = Regex("^https?://(?:ani|a\\d+)\\.ohli24\\.com(?=/|\\?|#|$)", RegexOption.IGNORE_CASE)
        private val domainMutex = Mutex()
        private var currentDomain = DEFAULT_DOMAIN
        private var isDomainChecked = false
    }

    override var mainUrl = DEFAULT_DOMAIN
    override var name = "OHLI24"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasChromecastSupport = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf(
        "__popular__" to "인기 작품 TOP 10",
        "bbs/board.php?bo_table=ing" to "방영중",
        "bbs/board.php?bo_table=fin" to "종영",
        "bbs/board.php?bo_table=theater" to "극장판"
    )

    private val headers get() = mapOf(
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    private suspend fun checkAndUpdateDomain() = domainMutex.withLock {
        if (!isDomainChecked) {
            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentDomain = prefs?.getString(PREF_KEY, null)
                ?.takeIf { domainPattern.matches(it) } ?: DEFAULT_DOMAIN

            var found = probeDomain(currentDomain, 5L)
            if (found == null) {
                val start = Regex("a(\\d+)\\.ohli24\\.com").find(currentDomain)
                    ?.groupValues?.get(1)?.toIntOrNull() ?: 101
                for (batch in (start + 1..start + 100).chunked(5)) {
                    found = coroutineScope {
                        batch.map { number ->
                            async { probeDomain("https://a$number.ohli24.com", 3L) }
                        }.awaitAll().firstOrNull { it != null }
                    }
                    if (found != null) break
                }
            }
            if (found != null) {
                currentDomain = found
                prefs?.edit()?.putString(PREF_KEY, found)?.apply()
            }
            isDomainChecked = true
        }
        mainUrl = currentDomain
    }

    private suspend fun probeDomain(domain: String, timeout: Long): String? {
        return try {
            val res = app.get("$domain/", headers = headers + ("Referer" to "$domain/"), timeout = timeout)
            val finalDomain = domainPattern.find(res.url)?.value?.replace("http://", "https://")
            if (res.isSuccessful && finalDomain != null &&
                res.document.selectFirst("meta[property=og:site_name]")?.attr("content").equals("OHLI24", true) &&
                res.document.selectFirst("script[data-page=app]") != null
            ) finalDomain else null
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
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

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
            posterUrl = poster
            rating?.toDoubleOrNull()?.let { score = Score.from10(it.toString()) }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
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
        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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
        checkAndUpdateDomain()
        val targetUrl = absoluteUrl(url)
        val doc = app.get(targetUrl, headers = headers, timeout = 100).document
        doc.setBaseUri(targetUrl)

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

        val episodes = loadAllEpisodes(targetUrl, doc).map { item ->
            newEpisode(item.url) {
                name = item.name
                episode = item.number
            }
        }.sortedBy { it.episode ?: Int.MAX_VALUE }

        val props = parseInertiaPage(doc)?.props
        val isMovieCatalog = props?.comic?.category == "극장판" ||
            doc.select("nav[aria-label=breadcrumb] a").any { it.text().trim() == "극장판" }
        val isMovie = isMovieCatalog && episodes.size == 1 && (props?.episodes?.total ?: episodes.size) == 1
        val result: LoadResponse = if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.single().data)
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes)
        }
        return result.apply {
            this.year = year
            tags = genres
            contentRating = ageRating
            posterUrl = poster
            this.plot = plot
        }
    }

    private data class InertiaPage(
        val component: String? = null,
        val props: InertiaProps? = null,
        val version: String? = null
    )

    private data class InertiaProps(
        val comic: ComicInfo? = null,
        val episodes: EpisodePage? = null
    )

    private data class ComicInfo(
        val category: String? = null
    )

    private data class EpisodePage(
        val current_page: Int? = null,
        val last_page: Int? = null,
        val total: Int? = null,
        val data: List<EpisodeItem> = emptyList()
    )

    private data class EpisodeItem(
        val title: String? = null,
        val slug: String? = null
    )

    private data class ParsedEpisode(
        val url: String,
        val name: String,
        val number: Int?
    )

    private suspend fun loadAllEpisodes(url: String, firstDoc: Element): List<ParsedEpisode> {
        val firstInertia = parseInertiaPage(firstDoc)
        val firstPage = firstInertia?.props?.episodes
        val firstItems = firstPage?.let(::toParsedEpisodes).orEmpty()
            .ifEmpty { routeEpisodes(firstDoc) }
        val lastPage = firstPage?.last_page ?: 1
        if (lastPage <= 1) return firstItems

        val all = firstItems.toMutableList()
        coroutineScope {
            for (batch in (2..lastPage).chunked(4)) {
                batch.map { page ->
                    async { fetchEpisodePage(url, page, firstInertia?.version) }
                }.awaitAll().forEach { pageData ->
                    if (pageData != null) all += toParsedEpisodes(pageData)
                }
            }
        }

        return all.distinctBy { it.url }
    }

    private suspend fun fetchEpisodePage(url: String, page: Int, version: String?): EpisodePage? {
        val pageUrl = "${url.substringBefore('?')}?page=$page"
        val inertiaHeaders = headers.toMutableMap().apply {
            this["X-Inertia"] = "true"
            this["X-Inertia-Partial-Component"] = "contents/List"
            this["X-Inertia-Partial-Data"] = "episodes"
            this["X-Requested-With"] = "XMLHttpRequest"
            this["Referer"] = url.substringBefore('?')
            if (!version.isNullOrBlank()) this["X-Inertia-Version"] = version
        }

        repeat(2) {
            val response = runCatching {
                app.get(pageUrl, headers = inertiaHeaders, timeout = 100)
            }.getOrNull() ?: return@repeat
            val episodePage = tryParseJson<InertiaPage>(response.text)?.props?.episodes
            if (episodePage != null && episodePage.current_page == page) return episodePage
        }

        val fullResponse = runCatching {
            app.get(pageUrl, headers = headers, timeout = 100)
        }.getOrNull() ?: return null
        val fullPage = parseInertiaPage(fullResponse.document)?.props?.episodes
        return fullPage?.takeIf { it.current_page == page }
    }

    private fun parseInertiaPage(doc: Element): InertiaPage? {
        val json = doc.selectFirst("script[data-page=app]")?.data()?.trim().orEmpty()
        if (json.isBlank()) return null
        return tryParseJson<InertiaPage>(json)
    }

    private fun toParsedEpisodes(page: EpisodePage): List<ParsedEpisode> =
        page.data.mapNotNull { item ->
            val label = item.slug?.trim().takeUnless { it.isNullOrBlank() }
                ?: item.title?.trim().takeUnless { it.isNullOrBlank() }
                ?: return@mapNotNull null
            ParsedEpisode(
                url = "$mainUrl/e/${encodeRoute(label)}",
                name = label,
                number = episodeNumber(label)
            )
        }

    private fun routeEpisodes(doc: Element): List<ParsedEpisode> =
        routeAnchors(doc, "e").map { anchor ->
            val href = absoluteUrl(anchor.attr("href"))
            val routeLabel = routeText(href, "e")
            val visibleLabel = anchor.text().trim()
            val label = routeLabel.ifBlank { visibleLabel }
            ParsedEpisode(href, label, episodeNumber(label))
        }

    private fun encodeRoute(value: String): String =
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")

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
        return runCatching { URI("$mainUrl/").resolve(value).toString() }
            .getOrDefault(value).replaceFirst(domainPattern, mainUrl)
    }

    private fun routeText(raw: String, route: String): String {
        val encoded = absoluteUrl(raw).substringAfter("/$route/", "").substringBefore('?').substringBefore('#')
        if (encoded.isBlank()) return ""
        return runCatching { URLDecoder.decode(encoded, "UTF-8").trim() }.getOrDefault(encoded.trim())
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun findVideoIframe(url: String): String? = withContext(Dispatchers.Main) {
        val context = CloudStreamApp.context ?: return@withContext null
        suspendCancellableCoroutine { continuation ->
            val handler = Handler(Looper.getMainLooper())
            var webView: WebView? = null
            var finished = false
            var timeout: Runnable? = null

            fun finish(result: String?) {
                if (finished) return
                finished = true
                timeout?.let(handler::removeCallbacks)
                webView?.stopLoading()
                webView?.destroy()
                webView = null
                if (continuation.isActive) continuation.resume(result)
            }

            try {
                val view = WebView(context)
                webView = view
                view.settings.javaScriptEnabled = true
                view.settings.domStorageEnabled = true
                view.settings.userAgentString = view.settings.userAgentString
                    .replace("Version/4.0 ", "").replace("; wv", "")
                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?, request: WebResourceRequest?
                    ): android.webkit.WebResourceResponse? {
                        val uri = request?.url ?: return null
                        if (uri.path?.startsWith("/video/") == true &&
                            (uri.host.equals("michealcdn.com", true) ||
                                uri.host.equals("cdndania.com", true))
                        ) {
                            handler.post { finish(uri.toString()) }
                        }
                        return null
                    }
                }
                timeout = Runnable { finish(null) }
                handler.postDelayed(timeout, 10000L)
                view.loadUrl(url, headers)
            } catch (_: Exception) {
                finish(null)
            }
            continuation.invokeOnCancellation { handler.post { finish(null) } }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        checkAndUpdateDomain()
        val targetData = absoluteUrl(data)
        val doc = app.get(targetData, headers = headers, timeout = 20).document
        var player = sequenceOf(
            doc.selectFirst("iframe[src]")?.attr("src"),
            doc.selectFirst("form.tt input[name=vurl]")?.attr("value"),
            doc.select("a[href]").firstOrNull {
                val href = it.attr("href")
                href.contains("cdndania.com/video/", true) || href.contains("michealcdn.com", true)
            }?.attr("href")
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
            ?.let(::absoluteUrl).orEmpty()
        if (player.isBlank()) player = findVideoIframe(targetData).orEmpty()
        if (player.isBlank()) return false

        if (player.contains("cdndania.com/video/", true)) {
            Cdndania().getUrl(player, mainUrl, subtitleCallback, callback)
        } else {
            loadExtractor(player, mainUrl, subtitleCallback, callback)
        }
        return true
    }
}
