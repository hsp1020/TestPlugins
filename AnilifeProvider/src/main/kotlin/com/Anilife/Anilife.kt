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

    private data class AnimeRecord(val id: String, val title: String?, val poster: String?)

    private fun parseRecords(body: String): List<AnimeRecord> {
        val found = mutableListOf<AnimeRecord>()
        val idKeys = listOf("id", "animeId", "anime_id", "contentId", "content_id", "animeIdx")
        val titleKeys = listOf("title", "name", "animeTitle", "anime_title", "title_ko", "titleKo", "koreanTitle")
        val posterKeys = listOf("poster", "posterUrl", "poster_url", "thumbnail", "thumbnailUrl", "thumbnail_url", "image", "imageUrl", "image_url", "cover")
        fun titleOf(o: JSONObject): String? {
            for (key in titleKeys) {
                val v = o.opt(key)
                val title = when (v) {
                    is String -> v
                    is JSONObject -> listOf("ko", "korean", "native", "english")
                        .firstNotNullOfOrNull { v.optString(it).takeIf(String::isNotBlank) }
                    else -> null
                }
                if (!title.isNullOrBlank()) return title.trim()
            }
            return null
        }
        fun visit(v: Any?, depth: Int = 0) {
            if (depth > 8 || found.size >= 500) return
            when (v) {
                is JSONObject -> {
                    val id = idKeys.firstNotNullOfOrNull { v.opt(it)?.toString()?.toLongOrNull()?.takeIf { n -> n > 0 }?.toString() }
                    if (id != null) {
                        if (found.size < 3) println("$tag api recordKeys=${v.keys().asSequence().toList()}")
                        val poster = posterKeys.firstNotNullOfOrNull { v.optString(it).takeIf { x -> x.isNotBlank() && x != "null" } }
                        found.add(AnimeRecord(id, titleOf(v), poster))
                    }
                    val keys = v.keys()
                    while (keys.hasNext()) visit(v.opt(keys.next()), depth + 1)
                }
                is JSONArray -> for (i in 0 until v.length()) visit(v.opt(i), depth + 1)
            }
        }
        when {
            body.startsWith("{") -> JSONObject(body).also {
                println("$tag api rootKeys=${it.keys().asSequence().toList()}")
                visit(it)
            }
            body.startsWith("[") -> JSONArray(body).also {
                println("$tag api rootArraySize=${it.length()}")
                visit(it)
            }
            else -> println("$tag api NON_JSON prefix=${body.take(400)}")
        }
        val distinct = found.distinctBy { it.id }
        println("$tag api candidates=${found.size} ids=${distinct.size} titles=${distinct.count { it.title != null }} posters=${distinct.count { it.poster != null }} first=${distinct.take(3)}")
        return distinct
    }

    private suspend fun catalogItems(pageUrl: String, apiUrl: String): List<SearchResponse> {
        println("$tag catalog START page=$pageUrl api=$apiUrl")
        val response = app.get(apiUrl, headers = headers, timeout = 10L)
        val body = response.text.trim()
        println("$tag api response ok=${response.isSuccessful} final=${response.url} bytes=${body.length} sample=${body.take(1600).replace("\n", " ")}")
        if (!response.isSuccessful) return emptyList()
        val records = parseRecords(body)
        val document = app.get(pageUrl, headers = headers).document
        val cards = document.select(".anime-card")
        println("$tag catalog htmlCards=${cards.size} apiRecords=${records.size} pageTitle=${document.title()}")
        val titleMap = records.filter { !it.title.isNullOrBlank() }.associateBy { it.title!!.trim() }
        val imageMap = records.filter { !it.poster.isNullOrBlank() }
            .associateBy { it.poster!!.substringBefore("?").substringAfterLast("/") }
        var byTitle = 0
        var byImage = 0
        var byOrder = 0
        var missing = 0
        val items = cards.mapIndexedNotNull { index, card ->
            val title = card.selectFirst(".card-title span")?.text()?.trim()
                ?: card.selectFirst(".poster-button")?.attr("aria-label")?.removeSuffix(" 상세 보기")?.trim()
                ?: return@mapIndexedNotNull null
            val poster = card.selectFirst(".poster-wrap img")?.attr("src")?.let(::absolute)
            val titleMatch = titleMap[title]
            val imageMatch = poster?.substringBefore("?")?.substringAfterLast("/")?.let(imageMap::get)
            val orderMatch = records.getOrNull(index).takeIf { records.size == cards.size }
            val record = when {
                titleMatch != null -> { byTitle++; titleMatch }
                imageMatch != null -> { byImage++; imageMatch }
                orderMatch != null -> { byOrder++; orderMatch }
                else -> { missing++; null }
            }
            if (record == null) {
                println("$tag catalog UNMATCHED index=$index title=$title poster=$poster")
                null
            } else {
                if (titleMatch == null && index < 8) println("$tag catalog FALLBACK index=$index htmlTitle=$title apiId=${record.id} apiTitle=${record.title}")
                newAnimeSearchResponse(title, "$mainUrl/content/${record.id}/1", TvType.Anime) {
                    this.posterUrl = poster ?: record.poster?.let(::absolute)
                    this.posterHeaders = headers
                }
            }
        }
        println("$tag catalog RESULT items=${items.size} title=$byTitle image=$byImage order=$byOrder missing=$missing")
        if (items.isEmpty()) println("$tag catalog EMPTY apiIds=${records.size} htmlCards=${cards.size} firstHtml=${cards.firstOrNull()?.selectFirst(".card-title span")?.text()}")
        return items
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val format = Regex("""/format/([^?&/]+)""").find(request.data)?.groupValues?.get(1) ?: "TV"
        val pageUrl = "$mainUrl/directory/format/$format?sort=popular&page=$page"
        val apiUrl = "$mainUrl/api/anime?page=$page&limit=24&sort=popular&format=$format&v=catalog-media-2"
        return try {
            val items = catalogItems(pageUrl, apiUrl)
            println("$tag mainPage RESULT category=${request.name} page=$page count=${items.size}")
            newHomePageResponse(request.name, items, hasNext = items.size >= 24)
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
            else catalogItems(url, req.url.toString()).also { println("$tag search RESULT count=${it.size}") }
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
