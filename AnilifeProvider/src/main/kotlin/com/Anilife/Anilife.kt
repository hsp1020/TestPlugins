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

    private val tag = "[Anilife DBG]"
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
        println("$tag domain START active=$mainUrl")
        domainMutex.withLock {
            if (domainChecked) return@withLock
            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val cached = prefs?.getString("domain", null)?.trimEnd('/')
                ?.takeIf { Regex("""https://anilife\d+\.tv""").matches(it) }
            val testPath = "/directory/format/TV?sort=popular"
            suspend fun verify(domain: String): String? = try {
                val res = app.get(domain + testPath, headers = mapOf("User-Agent" to userAgent, "Referer" to "$domain/"), timeout = 3L)
                val host = Regex("""^https://anilife\d+\.tv""").find(res.url)?.value
                val signature = res.text.contains("catalog-grid") && res.text.contains("anime-card") || res.text.contains("/assets/index-") && res.text.contains("애니라이프")
                println("$tag domain probe=$domain ok=${res.isSuccessful} signature=$signature redirect=${res.url}")
                if (res.isSuccessful && signature) host else null
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("$tag domain probe FAILED domain=$domain exception=${e.javaClass.simpleName}:${e.message}")
                null
            }

            val start = cached ?: BASE
            println("$tag domain cached=$cached start=$start")
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
                println("$tag domain validated=$found")
                currentDomain = found
                mainUrl = found
                prefs?.edit()?.putString("domain", found)?.apply()
            }
            if (found == null) println("$tag domain FAILED retaining=$mainUrl")
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

    private data class CatalogResult(val items: List<SearchResponse>, val hasNext: Boolean)

    private suspend fun catalogItems(pageUrl: String, apiUrl: String): CatalogResult {
        println("$tag catalog START page=$pageUrl api=$apiUrl")
        val response = app.get(apiUrl, headers = headers, timeout = 10L)
        println("$tag catalog HTTP ok=${response.isSuccessful} final=${response.url}")
        if (!response.isSuccessful) return CatalogResult(emptyList(), false)

        val root = JSONObject(response.text)
        val data = root.optJSONArray("items") ?: run {
            println("$tag catalog FAILED no items array; keys=${root.keys().asSequence().toList()} body=${response.text.take(800)}")
            return CatalogResult(emptyList(), false)
        }
        val pagination = root.optJSONObject("pagination")
        val page = pagination?.optInt("page") ?: 1
        val pages = pagination?.optInt("pages") ?: 1
        var invalid = 0
        val items = (0 until data.length()).mapNotNull { index ->
            val anime = data.optJSONObject(index) ?: return@mapNotNull null
            val id = anime.optString("id")
            val title = anime.optString("title").trim()
            if (id.isBlank() || id == "null" || title.isBlank()) {
                invalid++
                println("$tag catalog SKIP index=$index id=$id title=$title keys=${anime.keys().asSequence().toList()}")
                return@mapNotNull null
            }
            val image = anime.optString("image").takeIf { it.isNotBlank() && it != "null" }
            newAnimeSearchResponse(title, "$mainUrl/content/$id/1", TvType.Anime) {
                this.posterUrl = image?.let(::absolute)
                this.posterHeaders = headers
            }
        }
        println("$tag catalog RESULT records=${data.length()} items=${items.size} invalid=$invalid page=$page pages=$pages total=${pagination?.optInt("total")} first=${items.firstOrNull()?.name}")
        return CatalogResult(items, page < pages)
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val format = Regex("""/format/([^?&/]+)""").find(request.data)?.groupValues?.get(1) ?: "TV"
        val pageUrl = "$mainUrl/directory/format/$format?sort=popular&page=$page"
        val apiUrl = "$mainUrl/api/anime?page=$page&limit=24&sort=popular&format=$format&v=catalog-media-2"
        return try {
            val result = catalogItems(pageUrl, apiUrl)
            println("$tag mainPage RESULT category=${request.name} page=$page count=${result.items.size} hasNext=${result.hasNext}")
            newHomePageResponse(request.name, result.items, hasNext = result.hasNext)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("$tag mainPage FAILED category=${request.name} exception=${e.stackTraceToString()}")
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
            val url = "$mainUrl/search?q=${URLEncoder.encode(query, "UTF-8")}"
            println("$tag search START query=$query url=$url")
            val req = WebViewResolver(
                Regex("""https?://anilife\d+\.tv/api/(?:anime|search)[^\s]*"""),
                userAgent = userAgent, useOkhttp = false, timeout = 7000L
            ).resolveUsingWebView(url, headers = headers).first
            println("$tag search apiRequest=${req?.url} method=${req?.method}")
            if (req == null || req.method != "GET") emptyList()
            else catalogItems(url, req.url.toString()).items.also { println("$tag search RESULT count=${it.size}") }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("$tag search FAILED exception=${e.stackTraceToString()}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        try {
        val cleanUrl = freshUrl(url.substringBefore("?poster="))
        println("$tag detail START url=$cleanUrl")
        val doc = app.get(cleanUrl, headers = headers).document
        println("$tag detail html title=${doc.title()} episodeButtons=${doc.select(".tv-episode-grid button").size}")
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
        println("$tag detail RESULT series=$seriesId title=$title episodes=${episodes.size} first=${episodes.firstOrNull()?.episode}")
        return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
            this.posterUrl = image?.let(::absolute)
            this.posterHeaders = headers
            this.plot = plot
            this.tags = tags
            addEpisodes(DubStatus.Subbed, episodes)
        }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("$tag detail FAILED url=$url exception=${e.stackTraceToString()}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        checkAndUpdateDomain()
        val episodeUrl = freshUrl(data.substringBefore("?ref="))
        println("$tag links START episode=$episodeUrl casting=$isCasting")
        return try {
            // ArtPlayer uses a blob: video element: capture its real signed HLS request.
            val match = WebViewResolver(
                Regex("""https?://[^\s]+\.m3u8(?:\?[^\s]*)?$""", RegexOption.IGNORE_CASE),
                userAgent = userAgent, useOkhttp = false, timeout = 18000L
            ).resolveUsingWebView(episodeUrl, headers = headers).first ?: run {
                println("$tag links FAILED no HLS request observed episode=$episodeUrl")
                return false
            }
            println("$tag links HLS host=${match.url.host} path=${match.url.encodedPath} queryKeys=${match.url.queryParameterNames} headers=${match.headers.names()}")
            callback(newExtractorLink(name, name, match.url.toString(), ExtractorLinkType.M3U8) {
                this.referer = mainUrl + "/"
                this.headers = match.headers.toMap() + ("Referer" to (mainUrl + "/"))
            })
            println("$tag links callback RESULT delivered")
            true
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("$tag links FAILED exception=${e.stackTraceToString()}")
            false
        }
    }
}
