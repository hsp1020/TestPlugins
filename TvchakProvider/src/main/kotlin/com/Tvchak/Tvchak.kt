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

    private fun trace(stage: String, detail: String) {
        android.util.Log.i("TvchakPlaybackTrace", "stage=$stage $detail")
    }

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
            } catch (e: Exception) {
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
        val start = android.os.SystemClock.elapsedRealtime()
        val host = runCatching { java.net.URL(url).host }.getOrDefault("unknown")
        trace("HTTP_START", "host=$host referer=${referer != null}")
        val resStart = android.os.SystemClock.elapsedRealtime()
        var res = try { app.get(url, headers = getHeaders(referer)) } catch (e: Exception) {
            trace("HTTP_ERROR", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} error=${e.javaClass.simpleName} message=${e.message}")
            throw e
        }
        trace("HTTP_RESPONSE", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - resStart} status=${res.code} bytes=${res.text.length} challenge=${isChallenge(res.text, res.code)}")
        
        if (isChallenge(res.text, res.code)) {
            trace("WAF_LOCK_WAIT", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            cfMutex.withLock {
                trace("WAF_LOCK_ACQUIRED", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
                res = app.get(url, headers = getHeaders(referer))
                trace("WAF_RETRY", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} status=${res.code}")
                if (!isChallenge(res.text, res.code)) return res

                trace("WAF_SOLVE_START", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
                val bypass = solveWaf(url)
                trace("WAF_SOLVE_END", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} success=${bypass != null}")
                if (bypass != null) {
                    cfCookies = bypass.first
                    cfUserAgent = bypass.second
                    res = app.get(url, headers = getHeaders(referer))
                }
            }
        }
        
        val hasSignature = res.text.contains("movie-list-item") || 
                           res.text.contains("movie-title") || 
                           res.text.contains("movie-list-body") || 
                           res.text.contains("vod-search-list")
                           
        if (res.isSuccessful && hasSignature) {
        } else if (res.isSuccessful) {
            // [로그 버그 수정]: 성공(200)을 텍스트로 하드코딩하지 않고 실제 응답 코드를 유연하게 출력하도록 수정
        }
        trace("HTTP_END", "host=$host elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} status=${res.code} signature=$hasSignature")
        return res
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) { trace("DOMAIN_CACHED", "host=$mainUrl"); return }
        val start = android.os.SystemClock.elapsedRealtime()
        trace("DOMAIN_START", "host=$currentMainUrl")
        domainMutex.withLock {
            if (isDomainChecked) { trace("DOMAIN_LOCK_CACHED", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}"); return@withLock }

            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            currentMainUrl = prefs?.getString(PREF_KEY, null)?.replace("http://", "https://") ?: currentMainUrl
            mainUrl = currentMainUrl

            val testPath = "/"

            try {
                val res = safeGet("$currentMainUrl$testPath")
                trace("DOMAIN_PROBE", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} status=${res.code}")
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
                trace("DOMAIN_PROBE_ERROR", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} error=${e.javaClass.simpleName}")
            }

            val match = Regex("tvchak(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 200

            var foundDomain: String? = null
            coroutineScope {
                val candidates = (startNum..startNum + 100).map { "https://tvchak$it.com" }
                val chunks = candidates.chunked(5)

                for ((chunkIndex, chunk) in chunks.withIndex()) {
                    trace("DOMAIN_SCAN_CHUNK", "index=$chunkIndex elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
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
            trace("DOMAIN_END", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} found=${foundDomain != null}")
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

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val start = android.os.SystemClock.elapsedRealtime()
        val episodeHost = runCatching { java.net.URL(data).host }.getOrDefault("unknown")
        trace("LINK_START", "host=$episodeHost casting=$isCasting")
        checkAndUpdateDomain()
        trace("LINK_DOMAIN_DONE", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} mainHost=$mainUrl")

        val document = safeGet(data).document
        trace("LINK_PAGE_DONE", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data() ?: run {
            trace("LINK_NO_SCRIPT", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            return false
        }
        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val matchUrl = urlRegex.find(jsonString)
        val rawUrl = matchUrl?.groupValues?.get(1)?.replace("\\/", "/") ?: run {
            trace("LINK_NO_URL", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
            return false
        }

        var videoUrl = rawUrl
        var refererUrl = mainUrl

        val isChakttWrapper = rawUrl.contains("chaktt.php?url=")
        val isDirectBcdn = rawUrl.contains("b-cdn.net")
        trace("LINK_PARSED", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} wrapper=$isChakttWrapper bcdn=$isDirectBcdn rawHost=${runCatching { java.net.URL(rawUrl).host }.getOrDefault("unknown")}")
        if (isDirectBcdn && !isChakttWrapper) trace("HANDSHAKE_SKIPPED", "reason=direct_bcdn")

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
            trace("HANDSHAKE_START", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} host=${runCatching { java.net.URL(handshakeUrl).host }.getOrDefault("unknown")} wrapper=$isChakttWrapper")
            val handshakeStart = android.os.SystemClock.elapsedRealtime()
            try {
                val handshakeResponse = safeGet(handshakeUrl, referer = mainUrl)
                trace("HANDSHAKE_DONE", "durationMs=${android.os.SystemClock.elapsedRealtime() - handshakeStart} status=${handshakeResponse.code}")
            } catch (e: Exception) {
                trace("HANDSHAKE_ERROR", "durationMs=${android.os.SystemClock.elapsedRealtime() - handshakeStart} error=${e.javaClass.simpleName} message=${e.message}")
            }
        }

        var subUrl: String? = null
        val subRegex = Regex("""\?(srt|vtt)(https?://.*)""")
        val subMatch = subRegex.find(videoUrl)
        if (subMatch != null) {
            videoUrl = videoUrl.substring(0, subMatch.range.first)
            subUrl = subMatch.groupValues[2]
        }

        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        trace("LINK_CALLBACK_START", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start} videoHost=${runCatching { java.net.URL(videoUrl).host }.getOrDefault("unknown")} type=$type subtitles=${subUrl != null}")
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

        trace("LINK_END", "elapsedMs=${android.os.SystemClock.elapsedRealtime() - start}")
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
