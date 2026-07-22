package com.movieking

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume

class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking13.org"
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "MovieKing_Domain_Cache"
        private const val PREF_KEY = "current_domain"

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[MovieKing DBG] 💾 영구 도메인 저장 완료: $newDomain")
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)

    private val cfMutex = Mutex()
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
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
        val ref = referer ?: mainUrl
        headers["Referer"] = if (ref.endsWith("/")) ref else "$ref/"
        return headers
    }

    private fun isChallenge(html: String, code: Int): Boolean {
        val lowerHtml = html.lowercase()
        val cleanHtml = lowerHtml
            .replace("cdnjs.cloudflare.com", "")
            .replace("ajax.cloudflare.com", "")
            .replace("templates.cloudflare.com", "")

        val hasAwsWaf = cleanHtml.contains("aws-waf") || cleanHtml.contains("awswaf")
        val hasCf = cleanHtml.contains("window._cf_chl_opt") || 
                     cleanHtml.contains("cf-challenge") || 
                     cleanHtml.contains("just a moment...") ||
                     cleanHtml.contains("turnstile") ||
                     cleanHtml.contains("cloudflare") ||
                     cleanHtml.contains("blazing") ||
                     cleanHtml.contains("blazingweb")

        return code == 202 || code == 403 || code == 503 || hasAwsWaf || hasCf
    }

    private suspend fun solveWaf(url: String): Pair<String, String>? = withContext(Dispatchers.Main) {
        val rootUrl = Regex("^https?://[^/]+").find(url)?.value ?: mainUrl
        println("[MovieKing DBG] 🌐 solveWaf - 메인주소 쿠키 발급 시작: $rootUrl")

        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) { null } ?: AcraApplication.context ?: return@withContext null

        suspendCancellableCoroutine { continuation ->
            val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())
            var webView: android.webkit.WebView? = null

            fun cleanup() {
                mainHandler.post {
                    try {
                        webView?.stopLoading()
                        webView?.destroy()
                        webView = null
                    } catch (e: Exception) {}
                }
            }

            try {
                val cookieManager = android.webkit.CookieManager.getInstance()
                cookieManager.setAcceptCookie(true)
            } catch (e: Exception) {}

            webView = android.webkit.WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.databaseEnabled = true
                settings.userAgentString = userAgent
            }

            var solved = false

            val checkRunnable = object : Runnable {
                override fun run() {
                    if (solved || !continuation.isActive) return
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie(rootUrl) ?: "" } catch (e: Exception) { "" }

                    val hasToken = cookies.contains("BlazingWebCookie") ||
                                   cookies.contains("aws-waf-token") || 
                                   cookies.contains("cf_clearance") || 
                                   cookies.contains("__cf_bm") || 
                                   cookies.contains("PHPSESSID")

                    if (hasToken) {
                        solved = true
                        println("[MovieKing DBG] 🎉 메인주소 WAF 쿠키 획득 성공! 쿠키: $cookies")
                        continuation.resume(Pair(cookies, userAgent))
                        cleanup()
                    } else {
                        mainHandler.postDelayed(this, 1000)
                    }
                }
            }

            webView?.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageFinished(view: android.webkit.WebView?, finishedUrl: String?) {
                    mainHandler.post(checkRunnable)
                }

                override fun onReceivedSslError(view: android.webkit.WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    handler?.proceed()
                }
            }

            println("[MovieKing DBG] 📡 WebView 메인주소 로드 -> $rootUrl")
            webView?.loadUrl(rootUrl)
            mainHandler.postDelayed(checkRunnable, 1000)

            val timeoutRunnable = Runnable {
                if (!solved && continuation.isActive) {
                    println("[MovieKing DBG] ⏱️ 메인주소 쿠키 발급 타임아웃")
                    continuation.resume(null)
                    cleanup()
                }
            }
            mainHandler.postDelayed(timeoutRunnable, 10000)

            continuation.invokeOnCancellation {
                solved = true
                mainHandler.removeCallbacksAndMessages(null)
                cleanup()
            }
        }
    }

    // 영화/드라마 등 요청 직전에 메인 주소 쿠키 검증 및 구우기
    private suspend fun ensureCookie(): Boolean {
        if (!cfCookies.isNullOrBlank() && (cfCookies!!.contains("BlazingWebCookie") || cfCookies!!.contains("cf_clearance"))) {
            println("[MovieKing DBG] 🔍 쿠키 사전 검증: 기존 쿠키 유효함 -> ${cfCookies!!.take(35)}...")
            return true
        }

        return cfMutex.withLock {
            if (!cfCookies.isNullOrBlank() && (cfCookies!!.contains("BlazingWebCookie") || cfCookies!!.contains("cf_clearance"))) {
                println("[MovieKing DBG] 🔍 쿠키 사전 검증: 기존 쿠키 유효함 (동기화)")
                return@withLock true
            }

            println("[MovieKing DBG] 🔑 쿠키 사전 검증: 유효 쿠키 없음! 메인주소($mainUrl) 쿠키 구우기 실행...")
            val bypass = solveWaf(mainUrl)
            if (bypass != null) {
                cfCookies = bypass.first
                cfUserAgent = bypass.second
                println("[MovieKing DBG] 🟢 메인주소 쿠키 구우기 검증 완료! 쿠키: $cfCookies")
                true
            } else {
                println("[MovieKing DBG] ❌ 메인주소 쿠키 구우기 실패")
                false
            }
        }
    }

    private suspend fun safeGet(url: String, referer: String? = null, timeout: Long = 15L): NiceResponse {
        // 영화/드라마/검색 등 서브 주소를 부르기 전에 메인주소 쿠키 검증
        ensureCookie()

        return try {
            var res = app.get(url, headers = getHeaders(referer), timeout = timeout)

            if (isChallenge(res.text, res.code)) {
                cfMutex.withLock {
                    println("[MovieKing DBG] 🧱 WAF Challenge 감지됨. 메인주소 쿠키 재발급 시도...")
                    val bypass = solveWaf(mainUrl)
                    if (bypass != null) {
                        cfCookies = bypass.first
                        cfUserAgent = bypass.second
                        println("[MovieKing DBG] 🟢 쿠키 재발급 완료. 재요청 진행: $url")
                        res = app.get(url, headers = getHeaders(referer), timeout = timeout)
                    }
                }
            }
            res
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKing DBG] 💥 safeGet 예외 발생: ${e.javaClass.simpleName} - ${e.message}")

            // OkHttp 소켓이 Connection reset 등으로 끊겼을 때 쿠키 재발급 후 1회 자동 재시도
            if (e is java.net.SocketException || e is java.io.IOException) {
                cfMutex.withLock {
                    println("[MovieKing DBG] 🧱 OkHttp 소켓 차단 감지. 쿠키 재발급 및 재요청 진행...")
                    val bypass = solveWaf(mainUrl)
                    if (bypass != null) {
                        cfCookies = bypass.first
                        cfUserAgent = bypass.second
                        return app.get(url, headers = getHeaders(referer), timeout = timeout)
                    }
                }
            }
            throw e
        }
    }

    private val titleRegex = Regex("""\s*\(\d{4}\).*""")

    override val mainPage = mainPageOf(
        "/video/%EC%98%81%ED%99%94" to "영화",
        "/video/%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "/video/TV%EC%98%88%EB%8A%A5" to "예능",
        "/video/%EC%8B%9C%EC%82%AC%EB%8B%A4%ED%81%90" to "시사/다큐"
    )

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)
            
            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }
            
            try {
                val res = safeGet(currentMainUrl, timeout = 5L)
                val finalUrl = res.url.trimEnd('/')
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) {}

            isDomainChecked = true 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!movieking ")) {
            val userDomain = query.substring(11).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true 
                return listOf(newMovieSearchResponse("✅ 무비킹 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        return try {
            val searchUrl = "$mainUrl/search?k=$query"
            val res = safeGet(searchUrl)
            if (!res.url.contains("search")) return emptyList()

            val items = res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
            items
        } catch (e: Exception) { if (e is CancellationException) throw e; emptyList() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing DBG] 📄 메인페이지 로드 요청: 카테고리=${request.name}, URL=$url")
        
        return try {
            val res = safeGet(url)
            val doc = res.document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            println("[MovieKing DBG] 📄 영화/드라마 로드 결과: ${list.size}개 발견 (${request.name})")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e 
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
        
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { 
            newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } 
        }.reversed()

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) { this.posterUrl = fixUrl(poster); this.plot = intro }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster); this.plot = intro }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        var targetData = data
        if (!targetData.startsWith(mainUrl) && (targetData.contains("mvking") || targetData.contains("movieking"))) {
            targetData = mainUrl + targetData.replace(Regex("https?://[^/]+"), "")
        }
        
        try {
            val src = safeGet(targetData).document.selectFirst("iframe#view_iframe")?.attr("src")
            if (src != null) { loadExtractor(fixUrl(src), targetData, subtitleCallback, callback); return true }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return false
    }
}
