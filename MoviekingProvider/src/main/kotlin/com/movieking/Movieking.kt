package com.movieking

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.resume

class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking17.org"
        var isDomainChecked = false
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[MovieKing DBG] 💾 영구 도메인 저장 완료: $newDomain")
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    private val wafMutex = Mutex()
    private val browserUserAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    @Volatile private var sessionCookies: String? = null
    @Volatile private var wafGeneration = 0
    @Volatile private var nextWafAttemptAt = 0L

    private class WafChallengeException(message: String) : Exception(message)

    private fun origin(url: String): String = Regex("^https?://[^/]+")
        .find(url)?.value ?: mainUrl

    private fun requestHeaders(url: String, referer: String = origin(url)): Map<String, String> {
        val headers = mutableMapOf(
            "User-Agent" to browserUserAgent,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "${referer.trimEnd('/')}/"
        )
        if (URI(origin(url)).host.equals(URI(origin(mainUrl)).host, ignoreCase = true)) {
            sessionCookies?.let { headers["Cookie"] = it }
        }
        return headers
    }

    private fun isChallenge(html: String, code: Int): Boolean {
        val text = html.lowercase()
        val bunny = text.contains("bunny.net") &&
            (text.contains("hold tight") || text.contains("establishing a secure connection") ||
             text.contains("challenge loading"))
        val cloudflare = text.contains("window._cf_chl_opt") || text.contains("cf-challenge") ||
            text.contains("just a moment...") || text.contains("cf-turnstile")
        val otherWaf = text.contains("aws-waf") || text.contains("awswaf") ||
            text.contains("blazingweb")
        return bunny || cloudflare || otherWaf ||
            ((code == 202 || code == 403 || code == 503) &&
                (text.contains("security service") || text.contains("verify you are human")))
    }

    // WebView가 실제 목록까지 로드한 뒤에만 쿠키를 요청에 재사용한다.
    private suspend fun obtainBrowserCookies(url: String): Boolean = withContext(Dispatchers.Main) {
        val context = CloudStreamApp.context ?: return@withContext false
        val rootUrl = origin(url)
        withTimeoutOrNull(25000L) {
            suspendCancellableCoroutine<Boolean> { continuation ->
                val handler = Handler(Looper.getMainLooper())
                var webView: WebView? = null
                var completed = false
                lateinit var poll: Runnable

                fun cleanup() {
                    handler.removeCallbacks(poll)
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                }

                fun finish(success: Boolean) {
                    if (completed) return
                    completed = true
                    cleanup()
                    if (continuation.isActive) continuation.resume(success)
                }

                poll = object : Runnable {
                    override fun run() {
                        if (completed || !continuation.isActive) return
                        try {
                            webView?.evaluateJavascript(
                                "document.querySelectorAll('.video-card').length > 0"
                            ) { ready ->
                                if (completed || !continuation.isActive) return@evaluateJavascript
                                val cookies = CookieManager.getInstance().getCookie(rootUrl)
                                if (ready == "true" && !cookies.isNullOrBlank()) {
                                    sessionCookies = cookies
                                    CookieManager.getInstance().flush()
                                    finish(true)
                                } else {
                                    handler.postDelayed(this, 750L)
                                }
                            }
                        } catch (e: Exception) {
                            handler.postDelayed(this, 750L)
                        }
                    }
                }

                continuation.invokeOnCancellation {
                    handler.post { finish(false) }
                }
                try {
                    CookieManager.getInstance().setAcceptCookie(true)
                    webView = WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.userAgentString = browserUserAgent
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                                handler.removeCallbacks(poll)
                                handler.post(poll)
                            }
                        }
                        loadUrl(rootUrl)
                    }
                    handler.postDelayed(poll, 750L)
                } catch (e: Exception) {
                    println("[MovieKing DBG] WebView 시작 실패: ${e.javaClass.simpleName}")
                    finish(false)
                }
            }
        } ?: false
    }

    private suspend fun safeGet(url: String, timeout: Long = 15L): NiceResponse {
        var response = app.get(url, headers = requestHeaders(url), timeout = timeout)
        if (!isChallenge(response.text, response.code)) return response

        val generation = wafGeneration
        wafMutex.withLock {
            if (generation == wafGeneration && System.currentTimeMillis() >= nextWafAttemptAt) {
                println("[MovieKing DBG] WAF 확인 화면 감지: ${origin(url)}; WebView 쿠키 요청")
                val obtained = obtainBrowserCookies(url)
                nextWafAttemptAt = System.currentTimeMillis() + if (obtained) 5000L else 60000L
                if (obtained) wafGeneration++
                println("[MovieKing DBG] WebView 쿠키 획득: $obtained")
            }
        }
        response = app.get(url, headers = requestHeaders(url), timeout = timeout)
        if (isChallenge(response.text, response.code)) {
            throw WafChallengeException("WAF 확인 화면이 계속 반환됨: ${origin(url)} (HTTP ${response.code})")
        }
        return response
    }

    private fun updateDomain(newDomain: String) {
        if (mainUrl != newDomain) {
            sessionCookies = null
            nextWafAttemptAt = 0L
        }
        saveDomain(newDomain)
        mainUrl = currentMainUrl
    }

    private val titleRegex = Regex("""\s*\(\d{4}\).*""")
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(?\d{4}\)?.*""")

    override val mainPage = mainPageOf(
        "/video/%EC%98%81%ED%99%94" to "영화",
        "/video/%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "/video/TV%EC%98%88%EB%8A%A5" to "예능",
        "/video/%EC%95%A0%EB%8B%88" to "애니",
        "/video/%EC%8B%9C%EC%82%AC%EB%8B%A4%ED%81%90" to "시사/다큐"
    )

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = CloudStreamApp.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)

            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
                println("[MovieKing DBG] 📂 캐시된 도메인 로드: $currentMainUrl")
            }

            // 1단계: 현재/캐시된 도메인 유효성 체크
            try {
                val res = safeGet(currentMainUrl, timeout = 5L)
                val finalUrl = origin(res.url).trimEnd('/')
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        println("[MovieKing DBG] 🔄 리다이렉트 감지됨: $currentMainUrl -> $finalUrl")
                        updateDomain(finalUrl)
                    }
                    isDomainChecked = true; return@withLock
                }
            } catch (e: WafChallengeException) {
                // 확인 화면은 도메인이 사라졌다는 증거가 아니다.
                println("[MovieKing DBG] 현재 주소 확인 보류: ${e.message}")
                isDomainChecked = true
                return@withLock
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("[MovieKing DBG] ❌ 현재 도메인 접속 실패")
            }

            // 2단계: TVWiki 스타일 브루트포스 순차 탐색 적용
            val match = Regex("mvking(\\d+)\\.(\\w+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 7
            val tld = match?.groupValues?.get(2) ?: "org"
            println("[MovieKing DBG] 🚀 브루트포스 탐색 시작 (시작번호: $startNum, TLD: $tld)")

            try {
                coroutineScope {
                    val candidates = (startNum..startNum + 200).map { "https://mvking$it.$tld" }
                    val chunks = candidates.chunked(5)

                    for (chunk in chunks) {
                        val deferreds = chunk.map { testDomain ->
                            async {
                                try {
                                    val res = app.get(testDomain, headers = requestHeaders(testDomain), timeout = 3L)
                                    val extractedTestUrl = origin(res.url)
                                    val testFinalUrl = extractedTestUrl.replace("http://", "https://")

                                    if (res.isSuccessful && !isChallenge(res.text, res.code) && res.text.contains("video-card")) {
                                        return@async testFinalUrl
                                    }
                                } catch (e: Exception) { if (e is CancellationException) throw e }
                                null
                            }
                        }

                        val foundDomain = deferreds.awaitAll().firstOrNull { it != null }
                        if (foundDomain != null) {
                            println("[MovieKing DBG] ✨ 브루트포스 성공: $foundDomain")
                            updateDomain(foundDomain)
                            isDomainChecked = true
                            return@coroutineScope
                        }
                    }
                }
                if (isDomainChecked) return@withLock
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("[MovieKing DBG] ❌ 브루트포스 탐색 오류: ${e.message}")
            }

            // 3단계: 베이스 주소 폴백 시도
            try {
                println("[MovieKing DBG] 🌐 베이스 도메인(mvking7.org) 폴백 시도")
                val baseRes = app.get("https://mvking7.org", headers = requestHeaders("https://mvking7.org"), timeout = 5L)
                val baseFinalUrl = origin(baseRes.url).trimEnd('/')
                if (baseRes.isSuccessful && !isChallenge(baseRes.text, baseRes.code) && baseRes.text.contains("video-card")) {
                    println("[MovieKing DBG] ✨ 베이스 도메인 폴백 성공: $baseFinalUrl")
                    updateDomain(baseFinalUrl)
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }

            isDomainChecked = true
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!movieking ")) {
            val userDomain = query.substring(11).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                println("[MovieKing DBG] 🔧 백도어 도메인 수동 갱신: $userDomain")
                updateDomain(userDomain)
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ 무비킹 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔍 검색 요청: $query")

        return try {
            var searchUrl = "$mainUrl/search?k=$query"
            var res = safeGet(searchUrl)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            if (redirectedDomain != mainUrl || !finalUrl.contains("search")) {
                if (redirectedDomain != mainUrl) {
                    println("[MovieKing DBG] 🔄 검색 중 리다이렉트 감지: $redirectedDomain")
                    updateDomain(redirectedDomain)
                }
                searchUrl = "$mainUrl/search?k=$query"
                res = safeGet(searchUrl)
            }

            if (!res.url.contains("search")) return emptyList()

            val items = res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
            println("[MovieKing DBG] 🔍 검색 결과 수: ${items.size}")
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKing DBG] 검색 실패: ${e.javaClass.simpleName} - ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        var url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing DBG] 📄 메인페이지 로드: 카테고리=${request.name}, URL=$url")

        return try {
            var res = safeGet(url)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            val decodedRequestData = URLDecoder.decode(request.data, "UTF-8").substringBefore("?")
            val decodedFinalUrl = URLDecoder.decode(finalUrl, "UTF-8")

            if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                if (redirectedDomain != mainUrl) {
                    println("[MovieKing DBG] 🔄 메인페이지 로드 중 리다이렉트 감지: $redirectedDomain")
                    updateDomain(redirectedDomain)
                }
                url = "$mainUrl${request.data}${separator}page=$page"
                res = safeGet(url)
            }

            val doc = res.document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            println("[MovieKing DBG] 📄 메인페이지 아이템 수: ${list.size}")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKing DBG] 메인페이지 실패 (${request.name}): ${e.javaClass.simpleName} - ${e.message}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(categoryName: String? = null): SearchResponse? {
        val linkTag = this.selectFirst(".video-card-image a") ?: return null
        val titleTag = this.selectFirst(".video-title a") ?: return null
        val href = fixUrl(linkTag.attr("href"))
        val title = titleTag.text().trim().replace(titleRegex, "").trim()
        val imgTag = this.selectFirst("img")
        val fixedPoster = fixUrl(imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: "")

        var finalHref = href
        if (fixedPoster.isNotEmpty()) {
            val separator = if (href.contains("?")) "&" else "?"
            try { finalHref = "$href${separator}poster_url=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val type = if (categoryName == "영화" || href.contains("movie")) TvType.Movie else TvType.TvSeries

        return if (type == TvType.Movie) newMovieSearchResponse(title, finalHref, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, finalHref, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        println("[MovieKing DBG] 🎬 상세페이지 로드: $url")

        var passedPoster: String? = null
        var cleanUrl = url

        try {
            val posterRegex = Regex("""([&?])poster_url=[^&]+""")
            val match = posterRegex.find(url)
            if (match != null) {
                val param = match.value
                passedPoster = URLDecoder.decode(param.substringAfter("="), "UTF-8")
                cleanUrl = url.replace(param, "").replace("?&", "?").trimEnd('?', '&')
            }
        } catch (e: Exception) {}

        val finalUrl = if (!cleanUrl.startsWith(mainUrl) && (cleanUrl.contains("mvking") || cleanUrl.contains("movieking"))) {
            mainUrl + cleanUrl.replace(Regex("^https?://[^/]+"), "")
        } else {
            cleanUrl
        }

        val doc = safeGet(finalUrl).document
        val title = (doc.selectFirst("h3.title")?.text() ?: doc.selectFirst("h1.text-secondary")?.text() ?: "Unknown").replace(titleRegex, "").trim()
        val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: ""
        val intro = doc.selectFirst(".single-video-info-content h6:contains(소개)")?.nextElementSibling()?.text()?.trim() ?: "설명이 없습니다."

        val detailInfo = doc.select(".single-video-info-content p.text-secondary")
            .mapNotNull { element ->
                val key = element.selectFirst("b")?.text()?.trim() ?: return@mapNotNull null
                val value = element.text().substringAfter(":", "").trim()
                if (value.isBlank()) null else key to value
            }
            .toMap()

        val year = detailInfo["개봉"]
            ?.let { Regex("""\b\d{4}\b""").find(it)?.value?.toIntOrNull() }

        val tags = mutableListOf<String>().apply {
            detailInfo["장르"]
                ?.split(",")
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?.let { addAll(it) }

            detailInfo["나라"]
                ?.takeIf { it.isNotBlank() }
                ?.let { add("나라: $it") }

            detailInfo["출연"]
                ?.takeIf { it.isNotBlank() }
                ?.let { add("출연: $it") }

            detailInfo["감독"]
                ?.takeIf { it.isNotBlank() }
                ?.let { add("감독: $it") }
        }

        val episodeList = doc.select(".video-slider-right-list .eps_a").map {
            newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() }
        }.reversed()

        println("[MovieKing DBG] 🎬 파싱 완료: 에피소드 수=${episodeList.size}, 연도=${year ?: "없음"}, 태그=${tags.size}개")

        val isSeries = episodeList.size > 1 || episodeList.any {
            it.name?.contains(Regex("회|화|부|ep|episode", RegexOption.IGNORE_CASE)) == true
        }

        return if (!isSeries) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = intro
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) {
                this.posterUrl = fixUrl(poster)
                this.year = year
                this.plot = intro
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔗 영상 링크 추출 시작: $data")

        var targetData = data
        if (!targetData.startsWith(mainUrl) && (targetData.contains("mvking") || targetData.contains("movieking"))) {
            targetData = mainUrl + targetData.replace(Regex("https?://[^/]+"), "")
            println("[MovieKing DBG] 🔄 상세 URL 도메인 보정: $targetData")
        }

        return try {
            val detailRes = safeGet(targetData)
            println("[MovieKing DBG] 📄 상세페이지 HTTP=${detailRes.code} final=${detailRes.url} len=${detailRes.text.length}")

            val iframe = detailRes.document.selectFirst("iframe#view_iframe")
            val rawSrc = iframe?.attr("src")?.trim().orEmpty()
            println("[MovieKing DBG] 🧩 iframe 존재=${iframe != null}, rawSrc=${rawSrc.ifBlank { "<empty>" }}")

            if (rawSrc.isBlank()) {
                println("[MovieKing DBG] ❌ iframe#view_iframe 또는 src를 찾지 못함")
                return false
            }

            val playerUrl = when {
                rawSrc.startsWith("http://") || rawSrc.startsWith("https://") -> rawSrc
                rawSrc.startsWith("//") -> "https:$rawSrc"
                else -> fixUrl(rawSrc)
            }
            println("[MovieKing DBG] ▶ player URL=$playerUrl")

            var emittedLinks = 0
            val extractorMatched = loadExtractor(
                playerUrl,
                targetData,
                subtitleCallback
            ) { link ->
                emittedLinks++
                println(
                    "[MovieKing DBG] ✅ ExtractorLink#$emittedLinks " +
                        "source=${link.source} name=${link.name} type=${link.type} " +
                        "url=${link.url.take(300)}"
                )
                callback(link)
            }

            println("[MovieKing DBG] 🧪 loadExtractor matched=$extractorMatched emittedLinks=$emittedLinks")
            if (!extractorMatched) {
                println("[MovieKing DBG] ❌ player URL에 매칭되는 ExtractorApi가 없음: $playerUrl")
            } else if (emittedLinks == 0) {
                println("[MovieKing DBG] ❌ Extractor는 매칭됐지만 실제 재생 링크 callback이 0개")
            }

            emittedLinks > 0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKing DBG] ❌ loadLinks 예외: ${e.javaClass.simpleName}: ${e.message}")
            e.printStackTrace()
            false
        }
    }
}
