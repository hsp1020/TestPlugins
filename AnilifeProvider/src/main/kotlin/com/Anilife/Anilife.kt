package com.anilife

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder

class Anilife : MainAPI() {
    companion object {
        private const val BASE = "https://anilife01.tv"
        private const val PREFS = "Anilife_Domain_Cache"
        private val domainMutex = Mutex()
        @Volatile private var currentDomain = BASE
        @Volatile private var domainChecked = false
    }

    override var mainUrl = currentDomain
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val userAgent = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Mobile Safari/537.36"
    private val headers get() = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/")

    override val mainPage = mainPageOf(
        "/directory/format/TV?sort=popular" to "TV 애니메이션",
        "/directory/format/OVA?sort=popular" to "OVA",
        "/directory/format/ONA?sort=popular" to "ONA",
        "/directory/format/SP?sort=popular" to "SP",
        "/directory/format/Movie?sort=popular" to "극장판"
    )

    private suspend fun checkAndUpdateDomain() {
        if (domainChecked) return
        domainMutex.withLock {
            if (domainChecked) return@withLock
            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = prefs?.getString("domain", null)?.trimEnd('/')
                ?.takeIf { Regex("""https://anilife\d+\.tv""").matches(it) }
            val testPath = "/directory/format/TV?sort=popular"
            suspend fun verify(domain: String): String? = try {
                val res = app.get(domain + testPath, headers = mapOf("User-Agent" to userAgent, "Referer" to "$domain/"), timeout = 3L)
                val host = Regex("""^https://anilife\d+\.tv""").find(res.url)?.value
                if (res.isSuccessful && (res.text.contains("catalog-grid") && res.text.contains("anime-card") || res.text.contains("/assets/index-") && res.text.contains("애니라이프"))) host else null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            }

            val start = cached ?: BASE
            var found = verify(start)
            if (found == null && start != BASE) found = verify(BASE)
            if (found == null) {
                val n = Regex("""anilife(\d+)""").find(start)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val candidates = (n..n + 100).map { "https://anilife" + it.toString().padStart(2, '0') + ".tv" }
                for (batch in candidates.chunked(5)) {
                    found = coroutineScope { batch.map { async { verify(it) } }.awaitAll().firstOrNull { it != null } }
                    if (found != null) break
                }
            }
            if (found != null) {
                currentDomain = found
                mainUrl = found
                prefs?.edit()?.putString("domain", found)?.apply()
            }
            domainChecked = true
        }
    }

    private fun absolute(path: String): String = when {
        path.startsWith("https://") || path.startsWith("http://") -> path
        path.startsWith("//") -> "https:$path"
        else -> mainUrl + "/" + path.trimStart('/')
    }

    private fun freshUrl(url: String): String =
        if (Regex("""^https?://anilife\d+\.tv/""").containsMatchIn(url))
            url.replace(Regex("""^https?://anilife\d+\.tv"""), mainUrl)
        else url

    // Catalog cards are buttons without href. Find the real numeric IDs in the
    // site's own JSON requests; never fabricate a link from a card position.
    private suspend fun catalogItems(url: String): List<SearchResponse> {
        val doc = app.get(url, headers = headers).document
        val cards = doc.select(".anime-card")
        val ids = mutableMapOf<String, String>()
        val apiRequests = WebViewResolver(
            Regex("a^"), additionalUrls = listOf(Regex("""/api/""")),
            useOkhttp = false, timeout = 6500L
        ).resolveUsingWebView(url, headers = headers).second

        fun collect(value: Any?, depth: Int = 0) {
            if (depth > 8) return
            when (value) {
                is JSONObject -> {
                    val id = listOf("id", "animeId", "anime_id", "contentId", "content_id")
                        .firstNotNullOfOrNull { key -> value.opt(key)?.toString()?.toLongOrNull() }
                    val title = listOf("title", "name", "animeTitle", "anime_title")
                        .firstNotNullOfOrNull { key -> value.optString(key).takeIf { it.isNotBlank() } }
                    if (id != null && id > 0 && !title.isNullOrBlank()) ids[title.trim()] = id.toString()
                    val keys = value.keys()
                    while (keys.hasNext()) collect(value.opt(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until value.length()) collect(value.opt(i), depth + 1)
            }
        }
        for (req in apiRequests.distinctBy { it.url.toString() }.take(8)) {
            if (req.method != "GET" || req.url.host != java.net.URI(mainUrl).host) continue
            try {
                val body = app.get(req.url.toString(), headers = headers).text.trim()
                if (body.startsWith("{")) collect(JSONObject(body))
                if (body.startsWith("[")) collect(JSONArray(body))
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }
        }
        return cards.mapNotNull { card ->
            val title = card.selectFirst(".card-title span")?.text()?.trim()
                ?: card.selectFirst(".poster-button")?.attr("aria-label")?.removeSuffix(" 상세 보기")
                ?: return@mapNotNull null
            val href = card.selectFirst("a[href*='/content/']")?.attr("href")
                ?: ids[title]?.let { "/content/$it/1" }
                ?: return@mapNotNull null
            val poster = card.selectFirst(".poster-wrap img")?.attr("src")?.let(::absolute)
            newAnimeSearchResponse(title, absolute(href), TvType.Anime) {
                this.posterUrl = poster
                this.posterHeaders = headers
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = mainUrl + request.data + separator + "page=" + page
        return try {
            val items = catalogItems(url)
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        if (query.startsWith("!anilife ", true)) {
            val domain = query.substringAfter(' ').trim().trimEnd('/')
            if (Regex("""https://anilife\d+\.tv""").matches(domain)) {
                currentDomain = domain
                mainUrl = domain
                domainChecked = true
                CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    ?.edit()?.putString("domain", domain)?.apply()
            }
            return emptyList()
        }
        return try {
            catalogItems(mainUrl + "/search?q=" + URLEncoder.encode(query, "UTF-8"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        val cleanUrl = freshUrl(url.substringBefore("?poster="))
        val doc = app.get(cleanUrl, headers = headers).document
        val seriesId = Regex("""/content/(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: throw IllegalArgumentException("Invalid AniLife content URL: $cleanUrl")
        val title = doc.selectFirst(".tv-work-copy h1")?.text()?.trim()
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore(" | ")
            ?: "Unknown"
        val image = doc.selectFirst("meta[property=og:image]")?.attr("content")
        val plot = doc.selectFirst(".tv-work-copy > p")?.text()
        val tags = doc.select(".tv-work-meta span").drop(1)
            .flatMap { it.text().split("·") }.map { it.trim() }.filter { it.isNotEmpty() }
        val episodes = doc.select(".tv-episode-grid button").mapNotNull { button ->
            val n = Regex("""(\d+)\s*화""").find(button.selectFirst("strong")?.text().orEmpty())
                ?.groupValues?.get(1)?.toIntOrNull() ?: return@mapNotNull null
            val episodeName = button.selectFirst("span")?.text()?.trim().orEmpty()
            newEpisode(mainUrl + "/content/" + seriesId + "/" + n) {
                this.episode = n
                this.name = if (episodeName.isNotBlank() && episodeName != (n.toString() + "화"))
                    (n.toString() + "화 - " + episodeName) else (n.toString() + "화")
            }
        }.sortedBy { it.episode }
        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = image?.let(::absolute)
            this.posterHeaders = headers
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        checkAndUpdateDomain()
        val episodeUrl = freshUrl(data.substringBefore("?ref="))
        return try {
            // ArtPlayer uses a blob: video element: capture its real signed HLS request.
            val match = WebViewResolver(
                Regex("""https?://[^\s]+\.m3u8(?:\?[^\s]*)?$""", RegexOption.IGNORE_CASE),
                userAgent = userAgent, useOkhttp = false, timeout = 18000L
            ).resolveUsingWebView(episodeUrl, headers = headers).first ?: return false
            callback(newExtractorLink(name, name, match.url.toString(), ExtractorLinkType.M3U8) {
                this.referer = mainUrl + "/"
                this.headers = match.headers.toMap() + ("Referer" to (mainUrl + "/"))
            })
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            false
        }
    }
}
