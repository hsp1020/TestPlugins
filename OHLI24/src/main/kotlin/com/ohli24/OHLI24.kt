package com.ohli24

import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.os.Handler
import android.os.Looper
import com.lagradost.cloudstream3.CloudStreamApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import org.json.JSONTokener
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

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
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

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

        return newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
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

        val episodes = loadAllEpisodes(url, doc).map { item ->
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
        return runCatching { URI("$mainUrl/").resolve(value).toString() }.getOrDefault(value)
    }

    private fun routeText(raw: String, route: String): String {
        val encoded = absoluteUrl(raw).substringAfter("/$route/", "").substringBefore('?').substringBefore('#')
        if (encoded.isBlank()) return ""
        return runCatching { URLDecoder.decode(encoded, "UTF-8").trim() }.getOrDefault(encoded.trim())
    }

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    private suspend fun findRenderedVideoFrame(url: String, trace: Long): String? =
        withContext(Dispatchers.Main) {
            val context = CloudStreamApp.context ?: return@withContext null
            suspendCancellableCoroutine { cont ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var completed = false
                var poll: Runnable? = null
                var timeout: Runnable? = null

                fun complete(result: String?) {
                    if (completed) return
                    completed = true
                    poll?.let(handler::removeCallbacks)
                    timeout?.let(handler::removeCallbacks)
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                    if (cont.isActive) cont.resume(result)
                }

                fun isVideoFrame(candidate: String): Boolean = runCatching {
                    val uri = URI(candidate)
                    (uri.host.equals("michealcdn.com", true) || uri.host.equals("cdndania.com", true)) &&
                        uri.path.orEmpty().startsWith("/video/")
                }.getOrDefault(false)

                try {
                    val view = WebView(context)
                    webView = view
                    view.settings.javaScriptEnabled = true
                    view.settings.domStorageEnabled = true
                    view.settings.userAgentString = view.settings.userAgentString
                        .replace("Version/4.0 ", "").replace("; wv", "")
                    poll = object : Runnable {
                        override fun run() {
                            if (completed) return
                            view.evaluateJavascript(
                                """(function(){
                                    var frame = document.querySelector('iframe[data-player-frame], iframe[src*="michealcdn.com/video/"], iframe[src*="cdndania.com/video/"]');
                                    return frame ? (frame.src || frame.getAttribute('src') || '') : '';
                                })()"""
                            ) { raw ->
                                if (completed) return@evaluateJavascript
                                val candidate = runCatching {
                                    JSONTokener(raw).nextValue() as? String
                                }.getOrNull().orEmpty()
                                if (isVideoFrame(candidate)) {
                                    Log.i("OHLI24Trace", "phase=WEBVIEW_DOM_FOUND trace=" + trace +
                                        " frame=" + ohliSafeUrl(candidate))
                                    complete(candidate)
                                } else handler.postDelayed(this, 350L)
                            }
                        }
                    }

                    view.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                            if (message != null && (
                                message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.ERROR ||
                                message.messageLevel() == android.webkit.ConsoleMessage.MessageLevel.WARNING
                            )) {
                                val detail = message.message().replace(Regex("""https?://[^\s"']+"""), "[URL]")
                                    .replace(Regex("""(?i)(?:token|md5|signature|hash|expires|t)=[^\s&]+"""), "[REDACTED]")
                                    .take(180)
                                Log.w("OHLI24Trace", "phase=WEBVIEW_JS_CONSOLE trace=" + trace +
                                    " severity=" + message.messageLevel() + " line=" + message.lineNumber() +
                                    " source=" + ohliSafeUrl(message.sourceId()) + " detail=" + detail)
                            }
                            return false
                        }
                    }
                    view.webViewClient = object : WebViewClient() {
                        override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: android.graphics.Bitmap?) {
                            Log.i("OHLI24Trace", "phase=WEBVIEW_NAV_START trace=" + trace +
                                " url=" + ohliSafeUrl(startedUrl))
                        }
                        override fun onReceivedError(
                            view: WebView?, request: WebResourceRequest?, error: android.webkit.WebResourceError?
                        ) {
                            if (request?.isForMainFrame == true || request?.url?.host?.contains("ohli24") == true) {
                                Log.w("OHLI24Trace", "phase=WEBVIEW_RESOURCE_ERROR trace=" + trace +
                                    " url=" + ohliSafeUrl(request?.url?.toString()) +
                                    " mainFrame=" + (request?.isForMainFrame == true) +
                                    " code=" + error?.errorCode)
                            }
                        }
                        override fun onReceivedHttpError(
                            view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?
                        ) {
                            if (request?.isForMainFrame == true || response?.statusCode != 404) {
                                Log.w("OHLI24Trace", "phase=WEBVIEW_HTTP_ERROR trace=" + trace +
                                    " url=" + ohliSafeUrl(request?.url?.toString()) +
                                    " status=" + response?.statusCode + " mainFrame=" + (request?.isForMainFrame == true))
                            }
                        }
                        override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                            if (!completed) {
                                Log.i("OHLI24Trace", "phase=WEBVIEW_DOM_READY trace=" + trace +
                                    " url=" + ohliSafeUrl(finishedUrl))
                                view?.evaluateJavascript(
                                    """document.readyState + '|frames=' + document.querySelectorAll('iframe').length +
                                       '|scripts=' + document.scripts.length"""
                                ) { raw ->
                                    Log.i("OHLI24Trace", "phase=WEBVIEW_DOM_STATE trace=" + trace +
                                        " summary=" + raw.take(100))
                                }
                                poll?.let { handler.removeCallbacks(it); handler.post(it) }
                            }
                        }
                        override fun shouldInterceptRequest(
                            view: WebView?, request: WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val candidate = request?.url?.toString().orEmpty()
                            if (isVideoFrame(candidate)) {
                                Log.i("OHLI24Trace", "phase=WEBVIEW_REQUEST_OBSERVED trace=" + trace +
                                    " method=" + request?.method + " frame=" + ohliSafeUrl(candidate) +
                                    " headerKeys=" + request?.requestHeaders?.keys?.joinToString(","))
                                handler.post {
                                    Log.i("OHLI24Trace", "phase=WEBVIEW_REQUEST_FOUND trace=" + trace +
                                        " frame=" + ohliSafeUrl(candidate))
                                    complete(candidate)
                                }
                            }
                            return null
                        }
                    }
                    timeout = Runnable {
                        Log.w("OHLI24Trace", "phase=WEBVIEW_DOM_TIMEOUT trace=" + trace)
                        complete(null)
                    }
                    timeout?.let { handler.postDelayed(it, 10000L) }
                    view.loadUrl(url, headers)
                } catch (error: Exception) {
                    Log.w("OHLI24Trace", "phase=WEBVIEW_DOM_EXCEPTION trace=" + trace, error)
                    complete(null)
                }
                cont.invokeOnCancellation { handler.post { complete(null) } }
            }
        }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val trace = android.os.SystemClock.elapsedRealtimeNanos()
        val started = android.os.SystemClock.elapsedRealtime()
        var links = 0
        var subtitles = 0
        Log.i("OHLI24Trace", "phase=LINK_START trace=" + trace + " input=" + ohliSafeUrl(data) + " casting=" + isCasting)
        try {
            if (data.isBlank()) {
                Log.w("OHLI24Trace", "phase=LINK_EMPTY_INPUT trace=" + trace)
                return false
            }
            val pageResponse = app.get(data, headers = headers, timeout = 20)
            val doc = pageResponse.document
            val iframe = doc.selectFirst("iframe[src]")?.attr("src")
            val vurl = doc.selectFirst("form.tt input[name=vurl]")?.attr("value")
            val cdnAnchor = doc.select("a[href]").firstOrNull {
                val href = it.attr("href")
                href.contains("cdndania.com/video/", true) || href.contains("michealcdn.com", true)
            }?.attr("href")
            Log.i("OHLI24Trace", "phase=EPISODE_HTML trace=" + trace +
                " http=" + pageResponse.code + " length=" + pageResponse.text.length +
                " iframeCount=" + doc.select("iframe[src]").size +
                " inertia=" + (doc.selectFirst("script[data-page=app]") != null) +
                " iframe=" + ohliSafeUrl(iframe) +
                " vurl=" + ohliSafeUrl(vurl) + " cdnAnchor=" + ohliSafeUrl(cdnAnchor))
            var player = sequenceOf(iframe, vurl, cdnAnchor)
                .filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }
                ?.let(::absoluteUrl).orEmpty()
            if (player.isBlank()) {
                Log.i("OHLI24Trace", "phase=WEBVIEW_IFRAME_START trace=" + trace)
                player = try {
                    findRenderedVideoFrame(data, trace).orEmpty()
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("OHLI24Trace", "phase=WEBVIEW_IFRAME_ERROR trace=" + trace, e)
                    ""
                }
                Log.i("OHLI24Trace", "phase=WEBVIEW_IFRAME_RESULT trace=" + trace +
                    " selected=" + ohliSafeUrl(player))
            }
            Log.i("OHLI24Trace", "phase=PLAYER_SELECTED trace=" + trace +
                " selected=" + ohliSafeUrl(player) +
                " mode=" + if (player.contains("cdndania.com/video/", true)) "CdndaniaDirect" else "loadExtractor")
            if (player.isBlank()) {
                Log.w("OHLI24Trace", "phase=NO_PLAYER trace=" + trace)
                return false
            }
            val trackSubtitle: (SubtitleFile) -> Unit = { item ->
                subtitles++
                Log.i("OHLI24Trace", "phase=SUBTITLE_CALLBACK trace=" + trace + " count=" + subtitles)
                subtitleCallback(item)
            }
            val trackLink: (ExtractorLink) -> Unit = { link ->
                links++
                Log.i("OHLI24Trace", "phase=STREAM_CALLBACK trace=" + trace +
                    " count=" + links + " link=" + ohliSafeUrl(link.url))
                callback(link)
            }
            if (player.contains("cdndania.com/video/", true)) {
                Log.i("OHLI24Trace", "phase=DISPATCH_DIRECT trace=" + trace)
                Cdndania().getUrl(player, mainUrl, trackSubtitle, trackLink)
            } else {
                Log.i("OHLI24Trace", "phase=DISPATCH_GENERIC trace=" + trace)
                loadExtractor(player, mainUrl, trackSubtitle, trackLink)
            }
            if (links == 0 && (player.contains("cdndania.com/video/", true) || player.contains("michealcdn.com/video/", true))) {
                Log.i("OHLI24Trace", "phase=WEBVIEW_HLS_START trace=" + trace)
                try {
                    val (request, observedGetVideo) = WebViewResolver(
                        Regex("""https?://[^\s]+\.m3u8(?:\?[^\s]*)?""", RegexOption.IGNORE_CASE),
                        additionalUrls = listOf(
                            Regex("""https?://(?:cdndania|michealcdn)\.com/player/index\.php\?[^\s]*do=getVideo""", RegexOption.IGNORE_CASE)
                        ),
                        userAgent = null, useOkhttp = false, timeout = 12000L
                    ).resolveUsingWebView(player, referer = data)
                    observedGetVideo.forEach { seen ->
                        Log.i("OHLI24Trace", "phase=BROWSER_GETVIDEO_REQUEST trace=" + trace +
                            " method=" + seen.method +
                            " url=" + ohliSafeUrl(seen.url.toString()) +
                            " dataLength=" + (seen.url.queryParameter("data")?.length ?: -1) +
                            " headerKeys=" + seen.headers.names().joinToString(",") +
                            " refererPresent=" + (seen.header("Referer") != null) +
                            " originPresent=" + (seen.header("Origin") != null))
                    }
                    Log.i("OHLI24Trace", "phase=BROWSER_REQUEST_SUMMARY trace=" + trace +
                        " getVideoCount=" + observedGetVideo.size +
                        " hlsFound=" + (request != null))
                    if (request != null) {
                        val origin = URI(player).let { "${it.scheme}://${it.host}" }
                        val stream = request.url.toString()
                        Log.i("OHLI24Trace", "phase=BROWSER_HLS_REQUEST trace=" + trace +
                            " method=" + request.method + " url=" + ohliSafeUrl(stream) +
                            " headerKeys=" + request.headers.names().joinToString(","))
                        trackLink(newExtractorLink("CDN", "CDN", stream, ExtractorLinkType.M3U8) {
                            this.headers = request.headers.toMap() + mapOf("Referer" to player, "Origin" to origin)
                            this.referer = player
                            this.quality = Qualities.P1080.value
                        })
                    }
                    Log.i("OHLI24Trace", "phase=WEBVIEW_HLS_RESULT trace=" + trace +
                        " found=" + (request != null) + " links=" + links)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w("OHLI24Trace", "phase=WEBVIEW_HLS_ERROR trace=" + trace, e)
                }
            }
            if (links == 0) Log.w("OHLI24Trace", "phase=EXTRACTOR_NO_LINK trace=" + trace)
            Log.i("OHLI24Trace", "phase=LINK_RETURN trace=" + trace + " result=true links=" + links + " subtitles=" + subtitles)
            return true
        } catch (error: Throwable) {
            Log.e("OHLI24Trace", "phase=LINK_EXCEPTION trace=" + trace, error)
            throw error
        } finally {
            Log.i("OHLI24Trace", "phase=LINK_END trace=" + trace +
                " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started) +
                " links=" + links + " subtitles=" + subtitles)
        }
    }
}
