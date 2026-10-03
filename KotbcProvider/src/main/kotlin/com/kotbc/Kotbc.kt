package com.kotbc

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class Kotbc : MainAPI() {
    companion object {
        private const val DEFAULT_DOMAIN = "https://k01.kotbc2.com"
        private const val PREFS_NAME = "Kotbc_Domain_Cache_v2"
        private const val PREF_KEY = "current_domain"
        private const val PLAYER_DATA_PREFIX = "KOTBC_PLAYER:"
        private const val POPULAR_CATALOG = "__popular_top10__"

        var currentMainUrl = DEFAULT_DOMAIN
        var isDomainChecked = false

        private val domainMutex = Mutex()

        fun saveDomain(newDomain: String) {
            val normalized = newDomain.trimEnd('/')
            currentMainUrl = normalized

            CloudStreamApp.context
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(PREF_KEY, normalized)
                ?.apply()
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "KOTBC"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        POPULAR_CATALOG to "KOTBC 인기 TOP 10",
        "%EC%98%81%ED%99%94" to "영화",
        "%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "%EC%98%88%EB%8A%A5%EC%8B%9C%EC%82%AC" to "예능/시사",
        "%ED%95%B4%EC%99%B8%EB%93%9C%EB%9D%BC%EB%A7%88" to "해외드라마"
    )

    private fun isValidSiteDocument(doc: Document): Boolean {
        return doc.selectFirst("script[data-page=app][type=application/json]") != null &&
            (
                doc.selectFirst("input#haru-search-input") != null ||
                    doc.selectFirst("input#haru-search-input-mobile") != null
                )
    }

    private suspend fun probeDomain(url: String): String? {
        return try {
            val response = app.get(url, timeout = 3L)
            if (response.isSuccessful && isValidSiteDocument(response.document)) {
                response.url.trimEnd('/')
            } else {
                null
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            null
        }
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val cached = CloudStreamApp.context
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(PREF_KEY, null)
                ?.trimEnd('/')

            currentMainUrl = cached ?: DEFAULT_DOMAIN
            mainUrl = currentMainUrl

            probeDomain(currentMainUrl)?.let {
                saveDomain(it)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }

            probeDomain("https://kotbc2.com")?.let {
                saveDomain(it)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }

            val startNum = Regex(
                """https?://k(\d+)\.kotbc2\.com""",
                RegexOption.IGNORE_CASE
            ).find(currentMainUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            var found: String? = null

            coroutineScope {
                val candidates = (startNum..startNum + 200).map { number ->
                    "https://k${number.toString().padStart(2, '0')}.kotbc2.com"
                }

                for (chunk in candidates.chunked(5)) {
                    val results = chunk.map { candidate ->
                        async { probeDomain(candidate) }
                    }.awaitAll()

                    found = results.firstOrNull { it != null }
                    if (found != null) return@coroutineScope
                }
            }

            found?.let {
                saveDomain(it)
                mainUrl = currentMainUrl
            }

            isDomainChecked = true
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.lowercase().startsWith("!kotbc ")) {
            val domain = query.substring(7).trim().trimEnd('/')
            if (domain.startsWith("http")) {
                saveDomain(domain)
                mainUrl = currentMainUrl
                isDomainChecked = true

                return listOf(
                    newMovieSearchResponse(
                        "✅ Kotbc 갱신 성공: $domain",
                        domain,
                        TvType.Movie
                    ) {
                        posterUrl = ""
                    }
                )
            }
        } else if (query.startsWith("!")) {
            return emptyList()
        }

        checkAndUpdateDomain()

        return try {
            val normalized = query.trim()
            parseHomeSections(app.get(mainUrl).document)
                .filter { it.name.contains(normalized, ignoreCase = true) }
                .distinctBy { it.url }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        checkAndUpdateDomain()

        if (request.data == POPULAR_CATALOG) {
            val items = if (page == 1) loadPopularTop10() else emptyList()
            return newHomePageResponse(
                request.name,
                items,
                hasNext = false
            )
        }

        val pageUrl = buildString {
            append(mainUrl)
            append("/")
            append(request.data)
            if (page > 1) append("?page=$page")
        }

        return try {
            val doc = app.get(pageUrl).document
            val type = if (request.name == "영화") TvType.Movie else TvType.TvSeries
            val items = doc
                .select("a.group.block")
                .mapNotNull { it.toCardSearchResponse(type) }
                .distinctBy { it.url }

            newHomePageResponse(
                request.name,
                items,
                hasNext = items.isNotEmpty()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    /**
     * 루트 / 의 props.sections에는 최신 카테고리만 있고 인기순위는 없다.
     * 작품 상세 페이지의 props.popular가 사이트 공통 "인기 작품" TOP10이다.
     *
     * 홈의 첫 작품을 seed로 상세 페이지 하나만 열어 popular[0..9]를 가져온다.
     */
    private suspend fun loadPopularTop10(): List<SearchResponse> {
        return try {
            val homeDoc = app.get(mainUrl).document
            val typeMap = parseHomeTypeMap(homeDoc)

            val seedHref = homeDoc
                .selectFirst("a.group.block[href]")
                ?.attr("href")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return emptyList()

            val detailDoc = app.get(resolveUrl(seedHref)).document
            val popular = inertiaProps(detailDoc)
                ?.optJSONArray("popular")
                ?: return emptyList()

            buildList {
                for (index in 0 until minOf(popular.length(), 10)) {
                    val item = popular.optJSONObject(index) ?: continue
                    val title = item.optString("title").trim()
                    val slug = item.optString("slug").trim()
                    if (title.isEmpty() || slug.isEmpty()) continue

                    val url = "$mainUrl/${encodePathSegment(slug)}"
                    val poster = resolveStorageThumbnail(
                        item.optString("thumbnail").trim()
                    )
                    val type = typeMap[slug] ?: TvType.TvSeries

                    add(
                        if (type == TvType.Movie) {
                            newMovieSearchResponse(title, url, TvType.Movie) {
                                posterUrl = poster
                            }
                        } else {
                            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                                posterUrl = poster
                            }
                        }
                    )
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    private fun parseHomeSections(doc: Document): List<SearchResponse> {
        return doc.select("section")
            .flatMap { section ->
                val label = section.selectFirst("h2")?.text()?.trim()
                    ?: return@flatMap emptyList()

                val type = when (label) {
                    "영화" -> TvType.Movie
                    "드라마", "예능시사", "해외드라마" -> TvType.TvSeries
                    else -> return@flatMap emptyList()
                }

                section.select("a.group.block")
                    .mapNotNull { it.toCardSearchResponse(type) }
            }
    }

    /**
     * TOP10 항목에는 movie/series 종류가 없으므로,
     * 메인 props.sections에 같이 노출된 작품은 card_meta.kind로 타입을 보강한다.
     * 메인에 없는 과거 인기작은 TvSeries로 표시되지만 load() 시 실제 타입으로 교정된다.
     */
    private fun parseHomeTypeMap(doc: Document): Map<String, TvType> {
        val props = inertiaProps(doc) ?: return emptyMap()
        val sections = props.optJSONArray("sections") ?: return emptyMap()
        val result = mutableMapOf<String, TvType>()

        for (i in 0 until sections.length()) {
            val section = sections.optJSONObject(i) ?: continue
            val items = section.optJSONArray("items") ?: continue

            for (j in 0 until items.length()) {
                val item = items.optJSONObject(j) ?: continue
                val slug = item.optString("slug").trim()
                if (slug.isEmpty()) continue

                val kind = item
                    .optJSONObject("card_meta")
                    ?.optString("kind")
                    ?.lowercase()

                result[slug] = if (kind == "movie") {
                    TvType.Movie
                } else {
                    TvType.TvSeries
                }
            }
        }

        return result
    }

    private fun Element.toCardSearchResponse(type: TvType): SearchResponse? {
        val href = attr("href").trim().takeIf { it.isNotEmpty() } ?: return null

        val title = selectFirst("h3[title]")
            ?.attr("title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val poster = selectFirst("img[alt]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveUrl(it) }

        val url = resolveUrl(href)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(title, url, TvType.Movie) {
                posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                posterUrl = poster
            }
        }
    }

    private data class ParsedEpisode(
        val name: String,
        val number: Int?,
        val slug: String?,
        val videoUrl: String?
    )

    private data class PlayerPayload(
        val playerUrl: String,
        val refererUrl: String
    )

    private fun inertiaProps(doc: Document): JSONObject? {
        return try {
            val script = doc.selectFirst(
                "script[data-page=app][type=application/json]"
            ) ?: return null

            val raw = script.data().ifBlank { script.html() }.trim()
            if (raw.isEmpty()) return null

            JSONObject(raw).optJSONObject("props")
        } catch (_: Exception) {
            null
        }
    }

    private fun parseInertiaEpisodes(doc: Document): List<ParsedEpisode> {
        val data = inertiaProps(doc)
            ?.optJSONObject("episodes")
            ?.optJSONArray("data")
            ?: return emptyList()

        return buildList {
            for (index in 0 until data.length()) {
                val item = data.optJSONObject(index) ?: continue
                val name = item.optString("title")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: continue

                val number = item.optInt("sort", -1)
                    .takeIf { it >= 0 }
                    ?: Regex("""(\d+)\s*화""")
                        .find(name)
                        ?.groupValues
                        ?.getOrNull(1)
                        ?.toIntOrNull()

                val slug = item.optString("slug")
                    .trim()
                    .takeIf { it.isNotEmpty() }

                val videoUrl = item
                    .optJSONObject("body")
                    ?.optString("url")
                    ?.trim()
                    ?.takeIf {
                        it.startsWith("http://") || it.startsWith("https://")
                    }

                add(ParsedEpisode(name, number, slug, videoUrl))
            }
        }
    }

    private fun parseDomEpisodes(doc: Document): List<ParsedEpisode> {
        val section = doc.select("section")
            .firstOrNull { candidate ->
                candidate.select("span")
                    .any { it.text().trim() == "회차 목록" }
            }
            ?: return emptyList()

        return section
            .select("ul[data-infinite-scroll-page] a[href]")
            .mapNotNull { link ->
                val href = link.attr("href").trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val name = link.text().trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val number = Regex("""(\d+)\s*화""")
                    .find(name)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                ParsedEpisode(name, number, href, null)
            }
            .distinctBy { it.slug ?: it.name }
    }

    private fun packPlayerData(playerUrl: String, refererUrl: String): String {
        return PLAYER_DATA_PREFIX + JSONObject()
            .put("url", playerUrl)
            .put("referer", refererUrl)
            .toString()
    }

    private fun unpackPlayerData(data: String): PlayerPayload? {
        val marker = data.indexOf(PLAYER_DATA_PREFIX)
        if (marker < 0) return null

        return try {
            val json = JSONObject(data.substring(marker + PLAYER_DATA_PREFIX.length))
            val player = json.optString("url").trim()
            val referer = json.optString("referer").trim().ifEmpty { mainUrl }

            if (
                player.startsWith("http://") ||
                player.startsWith("https://")
            ) {
                PlayerPayload(player, referer)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildEpisodeData(
        targetUrl: String,
        episode: ParsedEpisode
    ): String? {
        val pageUrl = when {
            episode.slug.isNullOrBlank() -> targetUrl
            episode.slug.startsWith("/") -> resolveUrl(episode.slug)
            else -> targetUrl.trimEnd('/') + "/" + encodePathSegment(episode.slug)
        }

        return episode.videoUrl?.let {
            packPlayerData(it, pageUrl)
        } ?: pageUrl
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("%2F", "/")
    }

    private fun resolveStorageThumbnail(value: String?): String? {
        val raw = value?.trim().orEmpty()
        if (raw.isEmpty()) return null

        if (
            raw.startsWith("http://") ||
            raw.startsWith("https://") ||
            raw.startsWith("/")
        ) {
            return resolveUrl(raw)
        }

        val path = if (raw.startsWith("storage/")) {
            "/$raw"
        } else {
            "/storage/$raw"
        }

        return resolveUrl(path)
    }

    private fun parseInfoTags(doc: Document): List<String> {
        val infoSection = doc.select("section")
            .firstOrNull { it.selectFirst("h1") != null }

        val metadata = infoSection
            ?.select("dl > div")
            ?.mapNotNull { row ->
                val label = row.selectFirst("dt")?.text()?.trim().orEmpty()
                val value = row.selectFirst("dd")?.text()?.trim().orEmpty()

                if (label.isNotEmpty() && value.isNotEmpty()) {
                    "$label: $value"
                } else {
                    null
                }
            }
            .orEmpty()

        val genres = infoSection
            ?.select("a[href]")
            ?.mapNotNull { link ->
                val href = link.attr("href")
                val text = link.text().trim()

                if (href.startsWith("/?tag=") && text.startsWith("#")) {
                    text.removePrefix("#").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
            .orEmpty()

        return (genres + metadata).distinct()
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()

        val targetUrl = normalizeTargetUrl(url)
        val doc = app.get(targetUrl).document
        val props = inertiaProps(doc)
        val comic = props?.optJSONObject("comic")
        val cardMeta = comic?.optJSONObject("card_meta")

        val title = comic
            ?.optString("title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("h1")
                ?.text()
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        val poster = resolveStorageThumbnail(
            comic?.optString("thumbnail")
        ) ?: doc.selectFirst("meta[property=og:image]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveUrl(it) }

        val description = comic
            ?.optString("description")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:description]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        val summary = doc.select("section")
            .firstOrNull { it.selectFirst("h1") != null }
            ?.select("p")
            ?.map { it.text().trim() }
            ?.firstOrNull {
                it.contains(" · ") &&
                    (
                        it.contains("영화") ||
                            it.contains("드라마") ||
                            it.contains("총 ")
                        )
            }
            .orEmpty()

        val category = comic?.optString("category").orEmpty()
        val isMovie =
            cardMeta?.optString("kind")?.equals("movie", ignoreCase = true) == true ||
                (category.contains("영화") && !category.contains("드라마")) ||
                (summary.contains("영화") && !summary.contains("드라마"))

        val year = comic
            ?.optString("published_at")
            ?.take(4)
            ?.toIntOrNull()
            ?: Regex("""\b(?:19|20)\d{2}\b""")
                .find(summary)
                ?.value
                ?.toIntOrNull()

        val runtime = comic
            ?.optInt("runtime", 0)
            ?.takeIf { it > 0 }

        val tags = parseInfoTags(doc)

        val parsedEpisodes = parseInertiaEpisodes(doc)
            .ifEmpty { parseDomEpisodes(doc) }

        val episodes = parsedEpisodes
            .mapNotNull { parsed ->
                val data = buildEpisodeData(targetUrl, parsed)
                    ?: return@mapNotNull null

                newEpisode(data) {
                    name = parsed.name
                    episode = parsed.number
                    posterUrl = poster
                }
            }
            .distinctBy { it.data }

        return if (isMovie) {
            val playData = episodes.firstOrNull()?.data ?: targetUrl

            newMovieLoadResponse(
                title,
                targetUrl,
                TvType.Movie,
                playData
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
                this.duration = runtime
            }
        } else {
            val sorted = episodes.sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() }
            )

            newTvSeriesLoadResponse(
                title,
                targetUrl,
                TvType.TvSeries,
                sorted
            ) {
                posterUrl = poster
                plot = description
                this.tags = tags
                this.year = year
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val raw = data.trim()
        if (raw.isEmpty()) return false

        unpackPlayerData(raw)?.let { payload ->
            KotbcExtractor().getUrlFromKotbcPage(
                pageUrl = payload.refererUrl,
                expectedPlayerUrl = payload.playerUrl,
                subtitleCallback = subtitleCallback,
                callback = callback
            )
            return true
        }

        if (
            (raw.startsWith("http://") || raw.startsWith("https://")) &&
            !raw.contains("kotbc2.com", ignoreCase = true)
        ) {
            KotbcExtractor().getUrl(
                raw,
                mainUrl,
                subtitleCallback,
                callback
            )
            return true
        }

        KotbcExtractor().getUrlFromKotbcPage(
            pageUrl = normalizeTargetUrl(raw),
            expectedPlayerUrl = null,
            subtitleCallback = subtitleCallback,
            callback = callback
        )

        return true
    }

    private fun normalizeTargetUrl(url: String): String {
        val value = url.trim()
        if (value.isEmpty()) return mainUrl

        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (!value.contains("kotbc2.com", ignoreCase = true)) {
                return value
            }

            val path = value.replace(
                Regex("""https?://[^/]+""", RegexOption.IGNORE_CASE),
                ""
            )

            return mainUrl + path
        }

        return resolveUrl(value)
    }

    private fun resolveUrl(url: String): String {
        val value = url.trim()

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (value.startsWith("/")) {
            return mainUrl + value
        }

        return "$mainUrl/$value"
    }
}
