package com.lagradost.cloudstream3.plugins.tvchak

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlin.coroutines.resume

class TvchakProvider : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvchak200.com"
        var isDomainChecked = false
        private val domainMutex = Mutex()
        private val cfMutex = Mutex()
        private const val PREFS_NAME = "TvChak_Domain_Cache"
        private const val PREF_KEY = "current_domain"

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "TVChak"
    override val hasMainPage = true
    override var lang = "ko"
    override val hasDownloadSupport = true
    
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "1" to "영화",
        "2" to "드라마",
        "3" to "예능",
        "4" to "애니"
    )

    private val trailingYear = Regex("""\s*[\(（]\s*\d{4}\s*[\)）]""")
    private fun cleanTitle(title: String) = title.trim().replace(trailingYear, "").trim()

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private var cfCookies: String? = null
    private var cfUserAgent: String? = null

    private fun getHeaders(referer: String? = null): Map<String, String> {
        val currentUA = cfUserAgent ?: userAgent
        val headers = mutableMapOf(
            "User-Agent" to currentUA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Upgrade-Insecure-Requests" to "1"
        )
        cfCookies?.let { headers["Cookie"] = it }
        referer?.let { headers["Referer"] = it }
        return headers
    }

    private fun isChallenge(html: String, code: Int): Boolean {
        val lowerHtml = html.lowercase()
        val cleanHtml = lowerHtml
            .replace("cdnjs.cloudflare.com", "")
            .replace("ajax.cloudflare.com", "")
            .replace("templates.cloudflare.com", "")

        val hasAwsWaf = cleanHtml.contains("aws-waf") || 
                        cleanHtml.contains("awswaf") || 
                        cleanHtml.contains("awswafintegration") || 
                        cleanHtml.contains("awswafcookiedomainlist")
                             
        val hasCf = cleanHtml.contains("window._cf_chl_opt") || 
                     cleanHtml.contains("cf-challenge") || 
                     cleanHtml.contains("just a moment...") ||
                     cleanHtml.contains("turnstile") ||
                     cleanHtml.contains("cloudflare")

        return code == 202 || hasAwsWaf || hasCf
    }

    private suspend fun solveWaf(url: String): Pair<String, String>? = kotlinx.coroutines.withContext(Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) { null } ?: return@withContext null

        suspendCancellableCoroutine { continuation ->
            // [안전장치 복구]: 이전에 구워진 만료 쿠키 찌꺼기 제거
            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } catch (_: Exception) {
            }

            val webView = android.webkit.WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = userAgent
            }
            
            var solved = false
            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            var destroyed = false
            fun cleanup() {
                if (destroyed) return
                destroyed = true
                solved = true
                handler.removeCallbacksAndMessages(null)
                webView.destroy()
            }
            
            val checkRunnable = object : Runnable {
                override fun run() {
                    if (solved || !continuation.isActive) return
                    val cookies = android.webkit.CookieManager.getInstance().getCookie(url) ?: ""
                    if (cookies.contains("aws-waf-token") || cookies.contains("cf_clearance")) {
                        solved = true
                        continuation.resume(Pair(cookies, userAgent))
                        cleanup()
                    } else {
                        handler.postDelayed(this, 1000)
                    }
                }
            }

            webView.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    handler.post(checkRunnable)
                }
            }

            webView.loadUrl(url)
            handler.postDelayed(checkRunnable, 1000)

            val timeoutRunnable = Runnable {
                if (!solved && continuation.isActive) {
                    continuation.resume(null)
                    cleanup()
                }
            }
            handler.postDelayed(timeoutRunnable, 10000)

            continuation.invokeOnCancellation {
                handler.post { cleanup() }
            }
        }
    }

    private suspend fun safeGet(url: String, referer: String? = null): NiceResponse {
        var res = try { app.get(url, headers = getHeaders(referer)) } catch (e: Exception) {
            throw e
        }
        
        if (isChallenge(res.text, res.code)) {
            cfMutex.withLock {
                res = app.get(url, headers = getHeaders(referer))
                if (!isChallenge(res.text, res.code)) return res

                val bypass = solveWaf(url)
                if (bypass != null) {
                    cfCookies = bypass.first
                    cfUserAgent = bypass.second
                    res = app.get(url, headers = getHeaders(referer))
                }
            }
        }
        
        return res
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentMainUrl = prefs?.getString(PREF_KEY, null)?.replace("http://", "https://") ?: currentMainUrl
            mainUrl = currentMainUrl

            val testPath = "/"

            try {
                val res = safeGet("$currentMainUrl$testPath")
                val finalUrl = res.url.trimEnd('/')
                
                val hasSignature = res.text.contains("movie-list-item") || 
                                   res.text.contains("movie-title") || 
                                   res.text.contains("movie-list-body") || 
                                   res.text.contains("vod-search-list")
                                   
                if (res.isSuccessful && hasSignature) {
                    if (currentMainUrl != finalUrl && finalUrl.startsWith("http")) {
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
            }

            val match = Regex("tvchak(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 200

            var foundDomain: String? = null
            coroutineScope {
                val candidates = (startNum..startNum + 100).map { "https://tvchak$it.com" }
                val chunks = candidates.chunked(5)

                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            try {
                                val res = app.get("$testDomain$testPath", headers = getHeaders(), timeout = 3L)
                                val finalUrl = res.url.trimEnd('/')
                                val testSignature = res.text.contains("movie-list-item") || 
                                                    res.text.contains("movie-title") || 
                                                    res.text.contains("movie-list-body") || 
                                                    res.text.contains("vod-search-list")
                                                    
                                if ((res.isSuccessful && testSignature) || isChallenge(res.text, res.code)) {
                                    return@async finalUrl
                                }
                            } catch (e: Exception) {}
                            null
                        }
                    }

                    foundDomain = deferreds.awaitAll().firstOrNull { it != null }
                    if (foundDomain != null) break
                }
            }

            if (foundDomain != null) {
                saveDomain(foundDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }
            isDomainChecked = true
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/show/id/${request.data}/page/$page.html"
            val document = safeGet(url).document
            val items = document.select(".movie-list-body .movie-list-item").mapNotNull { element ->
                parseItem(element, request.data == "1")
            }
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!tvchak ")) {
            val userDomain = query.substring(8).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ TVChak 도메인 갱신: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/search.html?wd=$query"
            val document = safeGet(url).document
            val items = document.select(".vod-search-list .box").mapNotNull { element ->
                val title = element.selectFirst(".movie-title")?.text()?.let(::cleanTitle) ?: return@mapNotNull null
                val href = fixUrlNull(element.selectFirst("a.cover-link")?.attr("href")) ?: return@mapNotNull null
                val poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
                val encodedPoster = URLEncoder.encode(poster, "UTF-8")
                val finalUrl = "$href?poster=$encodedPoster"

                newTvSeriesSearchResponse(title, finalUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        
        val realUrl = url.substringBefore("?poster=")
        val encodedPoster = url.substringAfter("?poster=", "")
        val passedPoster = if (encodedPoster.isNotBlank()) {
            URLDecoder.decode(encodedPoster, "UTF-8")
        } else null

        val document = safeGet(realUrl).document
        val title = document.selectFirst("h1.movie-title")?.text()?.let(::cleanTitle) ?: ""
        val plot = document.selectFirst("h2#sum_tag")?.text()?.trim() ?: ""
        val poster = passedPoster ?: document.selectFirst(".poster img")?.attr("src") ?: ""
        
        val episodeRegex = Regex("""^\s*(?:제\s*)?(\d+)\s*(?:화|회|작|부)(?![가-힣\d])""")
        val episodes = document.select(".content_playlist li a").mapIndexedNotNull { index, element ->
            val epTitle = element.text().trim()
            val epHref = fixUrlNull(element.attr("href")) ?: return@mapIndexedNotNull null
            val epNumber = episodeRegex.find(epTitle)?.groupValues?.get(1)?.toIntOrNull() ?: index + 1

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNumber
            }
        }.sortedBy { it.episode }

        if (episodes.size == 1 && episodes[0].name == "바로보기") {
            return newMovieLoadResponse(title, realUrl, TvType.Movie, episodes[0].data) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
        return newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot
        }
    }

    // Temporary playback diagnostics: URLs are reduced to host, path shape and query keys.
    private fun tracePlayback(id: String, stage: String, info: String = "") {
        android.util.Log.i("TvchakPlaybackTrace", "id=$id stage=$stage $info")
    }

    private fun traceUrl(url: String): String = try {
        val uri = android.net.Uri.parse(url)
        val path = uri.path.orEmpty()
            .replace(Regex("[A-Za-z0-9_-]{24,}"), "{token}")
            .take(100)
        "host=" + uri.host + " path=" + path +
            " queryKeys=" + uri.queryParameterNames.sorted().joinToString(",")
    } catch (_: Exception) {
        "unparseable"
    }


    // Temporary one-session diagnostic snapshot. Chunks can be reconstructed from logcat.
    // Resolve newly uploaded titles over HTTP when their B-CDN MP4 returns 404.
    private suspend fun findWiselifeHls(
        pageUrl: String,
        videoUrl: String,
        playerType: String,
        traceId: String
    ): Pair<String, Map<String, String>>? {
        val started = android.os.SystemClock.elapsedRealtime()
        return try {
            val config = app.get(
                "$mainUrl/static/js/playerconfig2.js",
                headers = getHeaders(pageUrl),
                timeout = 6L
            ).text
            val playerConfig = Regex("\"${Regex.escape(playerType)}\"\\s*:\\s*\\{([^}]*)\\}")
                .find(config)?.groupValues?.get(1) ?: return null
            val parseUrl = Regex(""""parse"\s*:\s*"([^"]+)"""")
                .find(playerConfig)?.groupValues?.get(1)?.replace("\\/", "/") ?: return null
            if (!parseUrl.startsWith("https://") && !parseUrl.startsWith("http://")) return null

            val handshakeUrl = parseUrl + URLEncoder.encode(videoUrl, "UTF-8")
            tracePlayback(traceId, "HTTP_HLS_REQUEST", traceUrl(handshakeUrl))
            val response = app.get(
                handshakeUrl,
                headers = getHeaders(pageUrl),
                timeout = 6L,
                cacheTime = 0
            )
            tracePlayback(traceId, "HTTP_HLS_RESPONSE", "code=${response.code}")
            if (!response.isSuccessful) return null
            val encrypted = Regex("""phpsiteEc\s*=\s*["']([^"']+)["']""")
                .find(response.text)?.groupValues?.get(1) ?: return null
            val key = Regex("""phpsitekey\s*=\s*["']([^"']+)["']""")
                .find(response.text)?.groupValues?.get(1)?.toByteArray(Charsets.UTF_8) ?: return null
            if (key.isEmpty()) return null

            val encoded = android.util.Base64.decode(encrypted, android.util.Base64.DEFAULT)
            val inner = ByteArray(encoded.size) { index ->
                (encoded[index].toInt() xor key[index % key.size].toInt()).toByte()
            }
            val hlsUrl = String(
                android.util.Base64.decode(inner, android.util.Base64.DEFAULT),
                Charsets.UTF_8
            )
            val hlsUri = android.net.Uri.parse(hlsUrl)
            if (hlsUri.scheme !in listOf("http", "https") ||
                !hlsUri.path.orEmpty().endsWith(".m3u8", ignoreCase = true)) return null

            val parseUri = android.net.Uri.parse(parseUrl)
            val origin = "${parseUri.scheme}://${parseUri.authority}"
            tracePlayback(traceId, "HTTP_HLS_DECODED", traceUrl(hlsUrl) +
                " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started))
            hlsUrl to mapOf(
                "User-Agent" to (cfUserAgent ?: userAgent),
                "Referer" to "$origin/",
                "Origin" to origin
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            tracePlayback(traceId, "HTTP_HLS_ERROR", e.javaClass.simpleName)
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val traceId = android.os.SystemClock.elapsedRealtime().toString(36)
        val started = android.os.SystemClock.elapsedRealtime()
        tracePlayback(traceId, "LINK_START", "casting=" + isCasting + " " + traceUrl(data))
        checkAndUpdateDomain()
        tracePlayback(traceId, "DOMAIN_READY", "mainHost=" + mainUrl)

        val response = safeGet(data)
        val document = response.document
        tracePlayback(traceId, "PAGE_RECEIVED", "status=" + response.code +
            " scripts=" + document.select("script").size +
            " iframes=" + document.select("iframe").size +
            " wiselifeReferenced=" + response.text.contains("wiselife", true) +
            " chakttReferenced=" + response.text.contains("chaktt", true))
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data() ?: run {
            tracePlayback(traceId, "NO_PLAYER_SCRIPT")
            return false
        }
        tracePlayback(traceId, "PLAYER_SCRIPT", "bytes=" + scriptContent.length)
        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val matchUrl = urlRegex.find(jsonString)
        val rawUrl = matchUrl?.groupValues?.get(1)?.replace("\\/", "/") ?: run {
            tracePlayback(traceId, "NO_PLAYER_URL")
            return false
        }
        tracePlayback(traceId, "PLAYER_URL", traceUrl(rawUrl))

        var videoUrl = rawUrl
        var refererUrl = mainUrl

        val isChakttWrapper = rawUrl.contains("chaktt.php?url=")
        tracePlayback(traceId, "LINK_KIND", "wrapper=" + isChakttWrapper +
            " directCdn=" + rawUrl.contains("b-cdn.net"))

        if (isChakttWrapper) {
            var detectDomain = "https://ckp.wiselife.blog" 

            if (rawUrl.contains("chaktt.php") || rawUrl.contains("wiselife")) {
                try {
                    val parsedUri = java.net.URL(rawUrl)
                    detectDomain = "${parsedUri.protocol}://${parsedUri.host}"
                } catch (e: Exception) {}
            } else {
                val iframeSrc = document.select("iframe").map { it.attr("src") }
                    .firstOrNull { it.contains("chaktt.php") || it.contains("wiselife") }
                
                if (iframeSrc != null) {
                    try {
                        val parsedUri = java.net.URL(iframeSrc)
                        detectDomain = "${parsedUri.protocol}://${parsedUri.host}"
                    } catch (e: Exception) {}
                } else {
                    val scriptWithWiselife = document.select("script").map { it.html() }
                        .firstOrNull { it.contains("wiselife") || it.contains("chaktt.php") }
                    if (scriptWithWiselife != null) {
                        val match = Regex("""https://[a-zA-Z0-9.-]+""").find(scriptWithWiselife)
                        if (match != null) detectDomain = match.value
                    }
                }
            }

            val handshakeUrl = if (isChakttWrapper) {
                val realEncodedUrl = rawUrl.substringAfter("url=")
                videoUrl = URLDecoder.decode(realEncodedUrl, "UTF-8")
                rawUrl
            } else {
                val encodedVideoUrl = URLEncoder.encode(rawUrl, "UTF-8")
                "$detectDomain/chak/chaktt.php?url=$encodedVideoUrl"
            }

            refererUrl = "$detectDomain/"
            tracePlayback(traceId, "HANDSHAKE_START", traceUrl(handshakeUrl))
            val handshakeStarted = android.os.SystemClock.elapsedRealtime()
            try {
                val handshake = safeGet(handshakeUrl, referer = mainUrl)
                tracePlayback(traceId, "HANDSHAKE_RESPONSE", "status=" + handshake.code +
                    " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - handshakeStarted) +
                    " bytes=" + handshake.text.length)
            } catch (e: Exception) {
                tracePlayback(traceId, "HANDSHAKE_FAILED", "error=" + e.javaClass.simpleName)
            }
        }

        val subMatch = Regex("""\?(srt|vtt)(https?://.*)""").find(videoUrl)
        val subUrl = subMatch?.groupValues?.get(2)

        // New uploads can still point to a CDN MP4 that returns 404.
        // Probe only direct B-CDN links; leave all working and other sources unchanged.
        if (!isChakttWrapper && videoUrl.contains("b-cdn.net") &&
            videoUrl.substringBefore('?').endsWith(".mp4", ignoreCase = true)) {
            tracePlayback(traceId, "MP4_HEAD_START", traceUrl(videoUrl))
            val headStarted = android.os.SystemClock.elapsedRealtime()
            val isMissing = try {
                val head = app.head(
                    videoUrl,
                    headers = mapOf(
                        "User-Agent" to (cfUserAgent ?: userAgent),
                        "Referer" to refererUrl
                    ),
                    timeout = 5L,
                    cacheTime = 0
                )
                tracePlayback(traceId, "MP4_HEAD_RESULT", "status=" + head.code +
                    " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - headStarted))
                head.code == 404
            } catch (e: Exception) {
                tracePlayback(traceId, "MP4_HEAD_ERROR", "error=" + e.javaClass.simpleName)
                false
            }
            if (isMissing) {
                tracePlayback(traceId, "FALLBACK_START")
                val playerType = Regex(""""from"\s*:\s*"([^"]+)"""")
                    .find(jsonString)?.groupValues?.get(1) ?: "mp4"
                val sourceUrl = subMatch?.let { videoUrl.substring(0, it.range.first) } ?: videoUrl
                val hls = findWiselifeHls(data, sourceUrl, playerType, traceId)
                if (hls != null) {
                    val (hlsUrl, hlsHeaders) = hls
                    tracePlayback(traceId, "HLS_PUBLISH", traceUrl(hlsUrl))
                    callback.invoke(newExtractorLink(name, name, hlsUrl, ExtractorLinkType.M3U8) {
                        referer = hlsHeaders.entries.firstOrNull {
                            it.key.equals("Referer", true)
                        }?.value ?: data
                        headers = hlsHeaders
                        quality = Qualities.P1080.value
                    })
                    if (!subUrl.isNullOrBlank()) {
                        subtitleCallback.invoke(newSubtitleFile("Korean", subUrl))
                    }
                    tracePlayback(traceId, "LINK_SUCCESS", "route=hls elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - started))
                    return true
                }
                tracePlayback(traceId, "LINK_FAILURE", "reason=cdn404_no_hls")
                return false
            }
        }

        if (subMatch != null) {
            videoUrl = videoUrl.substring(0, subMatch.range.first)
        }

        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        tracePlayback(traceId, "DIRECT_PUBLISH", "type=" + type + " " + traceUrl(videoUrl) +
            " subtitle=" + (subUrl != null))

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = videoUrl,
                type = type
            ) {
                this.referer = refererUrl
                this.quality = Qualities.P1080.value
                this.headers = mapOf(
                    "User-Agent" to (cfUserAgent ?: userAgent),
                    "Referer" to refererUrl
                )
            }
        )

        if (!subUrl.isNullOrBlank()) {
            subtitleCallback.invoke(
                newSubtitleFile("Korean", subUrl)
            )
        }

        tracePlayback(traceId, "LINK_SUCCESS", "route=direct elapsedMs=" +
            (android.os.SystemClock.elapsedRealtime() - started))
        return true
    }

    private fun parseItem(element: Element, isMovie: Boolean): SearchResponse? {
        return try {
            val title = element.selectFirst(".movie-title")?.text()?.let(::cleanTitle) ?: return null
            val href = fixUrlNull(element.selectFirst("a")?.attr("href")) ?: return null
            
            var poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
            if (poster.isBlank() || poster == "/") {
                poster = element.selectFirst(".movie-post-lazyload")?.attr("style")
                    ?.substringAfter("url('")?.substringBefore("')") ?: ""
            }

            val encodedPoster = URLEncoder.encode(poster, "UTF-8")
            val finalUrl = "$href?poster=$encodedPoster"
            val type = if (isMovie) TvType.Movie else TvType.TvSeries

            newTvSeriesSearchResponse(title, finalUrl, type) {
                this.posterUrl = poster
            }
        } catch (e: Exception) {
            null
        }
    }
}
