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
    private fun traceSource(id: String, source: String, content: String) {
        try {
            val bytes = content.toByteArray(Charsets.UTF_8)
            val captured = bytes.copyOfRange(0, minOf(bytes.size, 262144))
            val stream = java.io.ByteArrayOutputStream()
            java.util.zip.GZIPOutputStream(stream).use { it.write(captured) }
            val encoded = android.util.Base64.encodeToString(
                stream.toByteArray(), android.util.Base64.NO_WRAP
            )
            val parts = encoded.chunked(1200)
            tracePlayback(id, "SOURCE_BEGIN", "source=$source bytes=${bytes.size}" +
                " captured=${captured.size} parts=${parts.size} format=gzip-base64")
            parts.forEachIndexed { index, part ->
                android.util.Log.i("TvchakHttpCapture",
                    "id=$id source=$source part=${index + 1}/${parts.size} data=$part")
            }
            tracePlayback(id, "SOURCE_END", "source=$source")
        } catch (e: Exception) {
            tracePlayback(id, "SOURCE_ERROR", "source=$source error=${e.javaClass.simpleName}")
        }
    }

    private suspend fun traceHttpSnapshot(
        traceId: String,
        sequence: Int,
        requestUrl: String,
        requestHeaders: Map<String, String>
    ) {
        val started = android.os.SystemClock.elapsedRealtime()
        val label = "resource_$sequence"
        try {
            val headers = mutableMapOf<String, String>()
            for (key in listOf("Referer", "Origin", "User-Agent", "Accept-Language", "Accept")) {
                val value = requestHeaders.entries.firstOrNull {
                    it.key.equals(key, true)
                }?.value
                if (!value.isNullOrBlank()) headers[key] = value
            }
            if (!headers.containsKey("User-Agent")) headers["User-Agent"] = cfUserAgent ?: userAgent
            if (!headers.containsKey("Referer")) headers["Referer"] = mainUrl
            val cookie = try {
                android.webkit.CookieManager.getInstance().getCookie(requestUrl)
            } catch (_: Exception) { null }
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
            tracePlayback(traceId, "SNAPSHOT_REQUEST",
                "source=$label ${traceUrl(requestUrl)} headerKeys=${headers.keys.sorted()}" +
                " cookiePresent=${!cookie.isNullOrEmpty()}")
            val response = withTimeout(7000L) {
                app.get(requestUrl, headers = headers, timeout = 6L, cacheTime = 0)
            }
            val body = response.text
            tracePlayback(traceId, "SNAPSHOT_RESPONSE",
                "source=$label code=${response.code} elapsedMs=" +
                (android.os.SystemClock.elapsedRealtime() - started) +
                " final=${traceUrl(response.url)} chars=${body.length}" +
                " containsHls=${body.contains(".m3u8", true)}" +
                " containsWiselife=${body.contains("wiselife", true)}")
            traceSource(traceId, label, body)
        } catch (e: Exception) {
            tracePlayback(traceId, "SNAPSHOT_ERROR",
                "source=$label elapsedMs=" +
                (android.os.SystemClock.elapsedRealtime() - started) +
                " error=${e.javaClass.simpleName} message=${e.message?.take(100)}")
        }
    }

    // Only used when the direct CDN MP4 has not become available yet.
    private suspend fun findWiselifeHls(pageUrl: String, traceId: String): Pair<String, Map<String, String>>? =
        withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val started = android.os.SystemClock.elapsedRealtime()
                val requestCount = java.util.concurrent.atomic.AtomicInteger(0)
                val snapshotCount = java.util.concurrent.atomic.AtomicInteger(0)
                val snapshotJob = SupervisorJob()
                val snapshotScope = CoroutineScope(Dispatchers.IO + snapshotJob)
                tracePlayback(traceId, "WEB_START", traceUrl(pageUrl))
                val context = CloudStreamApp.context
                if (context == null) {
                    tracePlayback(traceId, "WEB_NO_CONTEXT")
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                val finished = java.util.concurrent.atomic.AtomicBoolean(false)
                val webView = try {
                    android.webkit.WebView(context)
                } catch (e: Exception) {
                    tracePlayback(traceId, "WEB_CREATE_ERROR", e.javaClass.simpleName)
                    continuation.resume(null)
                    return@suspendCancellableCoroutine
                }

                fun complete(result: Pair<String, Map<String, String>>?, reason: String) {
                    if (!finished.compareAndSet(false, true)) return
                    snapshotJob.complete()
                    tracePlayback(traceId, "WEB_FINISH", "reason=" + reason +
                        " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started) +
                        " requests=" + requestCount.get())
                    handler.post {
                        handler.removeCallbacksAndMessages(null)
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (_: Exception) {}
                        if (continuation.isActive) continuation.resume(result)
                    }
                }

                continuation.invokeOnCancellation {
                    snapshotJob.cancel()
                    complete(null, "cancelled")
                }

                try {
                    tracePlayback(traceId, "WEB_CREATED")
                    webView.settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        mediaPlaybackRequiresUserGesture = false
                        userAgentString = cfUserAgent ?: userAgent
                    }
                    android.webkit.CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
                    webView.webChromeClient = object : android.webkit.WebChromeClient() {
                        override fun onConsoleMessage(message: android.webkit.ConsoleMessage?): Boolean {
                            val value = message?.message().orEmpty()
                            if (value.contains("error", true) || value.contains("hls", true) ||
                                value.contains("player", true) || value.contains("wiselife", true)) {
                                val filtered = value.replace(Regex("""https?://[^\s'"]+"""), "[url]")
                                tracePlayback(traceId, "WEB_CONSOLE", "level=" + message?.messageLevel() +
                                    " text=" + filtered.take(160))
                            }
                            return false
                        }
                    }
                    webView.webViewClient = object : android.webkit.WebViewClient() {
                        override fun onPageStarted(view: android.webkit.WebView?, url: String?,
                                                   favicon: android.graphics.Bitmap?) {
                            tracePlayback(traceId, "WEB_PAGE_START", traceUrl(url.orEmpty()))
                        }

                        override fun shouldInterceptRequest(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                        ): android.webkit.WebResourceResponse? {
                            val uri = request?.url ?: return null
                            val host = uri.host.orEmpty()
                            val number = requestCount.incrementAndGet()
                            val isHls = uri.path?.endsWith(".m3u8", ignoreCase = true) == true
                            val isWiselife = host == "wiselife.blog" || host.endsWith(".wiselife.blog")
                            if (number <= 120 || isWiselife || isHls) {
                                tracePlayback(traceId, "WEB_REQUEST", "seq=" + number +
                                    " method=" + request.method + " mainFrame=" + request.isForMainFrame +
                                    " " + traceUrl(uri.toString()))
                            }
                            val path = uri.path.orEmpty()
                            val captureTarget =
                                (isWiselife &&
                                    (path.endsWith("/chak/chaktt.php") ||
                                     path.endsWith("/chak/common.js") ||
                                     path.endsWith(".json", true) ||
                                     path.contains("/api/") ||
                                     path.contains("/ajax/") ||
                                     path.endsWith(".php", true))) ||
                                (host == android.net.Uri.parse(mainUrl).host &&
                                    (path.endsWith("/static/player/parse.js") ||
                                     path.endsWith("/static/js/player.js") ||
                                     path.endsWith("/static/js/playerconfig2.js")))
                            if (captureTarget && snapshotCount.incrementAndGet() <= 12) {
                                val requestUrl = uri.toString()
                                val requestHeaders = request.requestHeaders.toMap()
                                snapshotScope.launch {
                                    traceHttpSnapshot(traceId, number, requestUrl, requestHeaders)
                                }
                            }
                            if (isWiselife && isHls) {
                                val headers = request.requestHeaders.filterKeys {
                                    it.equals("Referer", true) ||
                                        it.equals("User-Agent", true) ||
                                        it.equals("Origin", true)
                                }.toMutableMap()
                                if (headers.keys.none { it.equals("User-Agent", true) }) {
                                    headers["User-Agent"] = cfUserAgent ?: userAgent
                                }
                                tracePlayback(traceId, "WEB_HLS_CAPTURED", "seq=" + number +
                                    " " + traceUrl(uri.toString()) +
                                    " headerKeys=" + headers.keys.sorted().joinToString(","))
                                complete(uri.toString() to headers, "hls_captured")
                            }
                            return null
                        }

                        override fun onReceivedHttpError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            errorResponse: android.webkit.WebResourceResponse?
                        ) {
                            tracePlayback(traceId, "WEB_HTTP_ERROR", "status=" + errorResponse?.statusCode +
                                " " + traceUrl(request?.url?.toString().orEmpty()))
                        }

                        override fun onReceivedError(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?,
                            error: android.webkit.WebResourceError?
                        ) {
                            tracePlayback(traceId, "WEB_NET_ERROR", "code=" + error?.errorCode +
                                " " + traceUrl(request?.url?.toString().orEmpty()))
                        }

                        override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                            tracePlayback(traceId, "WEB_PAGE_DONE", traceUrl(url.orEmpty()))
                            view?.evaluateJavascript(
                                """(function(){var v=document.querySelector('video');
                                var summary='videos='+document.querySelectorAll('video').length+
                                ',iframes='+document.querySelectorAll('iframe').length+
                                ',scripts='+document.querySelectorAll('script').length+
                                ',videoPresent='+(!!v);
                                if(v){try{var p=v.play();if(p&&p.catch)p.catch(function(){});}catch(e){}}
                                return summary;})()"""
                            ) { result ->
                                tracePlayback(traceId, "WEB_DOM_PLAY", result.take(160))
                            }
                        }
                    }
                    handler.postDelayed({ complete(null, "timeout") }, 10000L)
                    tracePlayback(traceId, "WEB_LOAD_URL", traceUrl(pageUrl))
                    webView.loadUrl(pageUrl, getHeaders(mainUrl))
                } catch (e: Exception) {
                    tracePlayback(traceId, "WEB_EXCEPTION", e.javaClass.simpleName)
                    complete(null, "exception")
                }
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
        traceSource(traceId, "player_aaaa", scriptContent)
        document.select("script").forEachIndexed { index, element ->
            val inline = element.data()
            if (inline.isNotBlank() && inline != scriptContent &&
                (inline.contains("player_aaaa") || inline.contains("parse.js") ||
                 inline.contains("wiselife", true) || inline.contains("iframe", true))) {
                traceSource(traceId, "inline_$index", inline)
            }
        }
        tracePlayback(traceId, "PAGE_SCRIPT_URLS",
            document.select("script[src]").map { traceUrl(it.absUrl("src")) }
                .take(25).joinToString(" | ").take(1400))
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
                val hls = findWiselifeHls(data, traceId)
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
