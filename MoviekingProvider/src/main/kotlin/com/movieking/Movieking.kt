package com.movieking

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.nicehttp.NiceResponse
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

class MovieKing : MainAPI() {
    companion object {
        var currentMainUrl = "https://mvking7.org"
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

        fun clearDomainCache() {
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(PREF_KEY)?.apply()
            println("[MovieKing DBG] 🧹 도메인 캐시 삭제 완료")
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "MovieKing"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime)
    
    private val cfMutex = Mutex()
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

    // WAF 쿠키 발급용 함수: 반드시 루트 도메인(mainUrl)으로만 접근
    private suspend fun solveWaf(targetUrl: String = mainUrl): Pair<String, String>? = withContext(Dispatchers.Main) {
        val rootUrl = Regex("^https?://[^/]+").find(targetUrl)?.value ?: mainUrl
        println("[MovieKing DBG] 🌐 solveWaf 실행 (루트 도메인 쿠키 획득): $rootUrl")
        
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
                    println("[MovieKing DBG] 🔍 [WebView 쿠키 확인] \"$cookies\"")

                    val hasToken = cookies.contains("BlazingWebCookie") ||
                                   cookies.contains("aws-waf-token") || 
                                   cookies.contains("cf_clearance") || 
                                   cookies.contains("__cf_bm") || 
                                   cookies.contains("PHPSESSID")

                    if (hasToken) {
                        solved = true
                        println("[MovieKing DBG] 🎉 루트 도메인 WAF 쿠키 획득 성공! 쿠키: $cookies")
                        continuation.resume(Pair(cookies, userAgent))
                        cleanup()
                    } else {
                        mainHandler.postDelayed(this, 1000)
                    }
                }
            }

            webView?.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: android.webkit.WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                    println("[MovieKing DBG] 🚀 WebView 로딩 시작: $url")
                }

                override fun onPageFinished(view: android.webkit.WebView?, finishedUrl: String?) {
                    println("[MovieKing DBG] 🏁 WebView 로딩 완료: $finishedUrl")
                    mainHandler.post(checkRunnable)
                }

                override fun onReceivedError(view: android.webkit.WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    println("[MovieKing DBG] ⚠️ WebView 에러: code=$errorCode, desc=$description, url=$failingUrl")
                }
            }

            println("[MovieKing DBG] 📡 WebView loadUrl 실행 -> $rootUrl")
            webView?.loadUrl(rootUrl)
            mainHandler.postDelayed(checkRunnable, 1000)

            val timeoutRunnable = Runnable {
                if (!solved && continuation.isActive) {
                    println("[MovieKing DBG] ⏱️ WebView 타임아웃 (10초)")
                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie(rootUrl) ?: "" } catch (e: Exception) { "" }
                    if (cookies.isNotBlank()) {
                        continuation.resume(Pair(cookies, userAgent))
                    } else {
                        continuation.resume(null)
                    }
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

    private suspend fun safeGet(url: String, referer: String? = null, timeout: Long = 15L): NiceResponse {
        println("[MovieKing DBG] 🌐 safeGet 요청 시작: $url")
        
        // 쿠키가 없으면 서브 경로 요청 전에 루트 도메인에서 먼저 쿠키 발급
        if (cfCookies.isNullOrBlank()) {
            cfMutex.withLock {
                if (cfCookies.isNullOrBlank()) {
                    println("[MovieKing DBG] 🔑 쿠키 없음. 루트 도메인($mainUrl)에서 WAF 쿠키 먼저 발급 시도...")
                    val bypass = solveWaf(mainUrl)
                    if (bypass != null) {
                        cfCookies = bypass.first
                        cfUserAgent = bypass.second
                        println("[MovieKing DBG] 🟢 루트 도메인 WAF 쿠키 획득 성공")
                    } else {
                        println("[MovieKing DBG] ❌ 루트 도메인 WAF 쿠키 획득 실패")
                    }
                }
            }
        }

        return try {
            var res = app.get(url, headers = getHeaders(referer), timeout = timeout)
            println("[MovieKing DBG] 📥 safeGet 1차 응답: HTTP ${res.code}, URL=${res.url}")

            if (isChallenge(res.text, res.code)) {
                cfMutex.withLock {
                    println("[MovieKing DBG] 🧱 WAF Challenge 감지 (HTTP ${res.code}). 루트 쿠키 재발급 시도...")
                    val bypass = solveWaf(mainUrl)
                    if (bypass != null) {
                        cfCookies = bypass.first
                        cfUserAgent = bypass.second
                        println("[MovieKing DBG] 🟢 쿠키 재발급 성공. 재요청 중...")
                        res = app.get(url, headers = getHeaders(referer), timeout = timeout)
                    }
                }
            }
            res
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[MovieKing DBG] 💥 safeGet 예외 발생: ${e.javaClass.simpleName} - ${e.message}")
            
            cfMutex.withLock {
                println("[MovieKing DBG] 🧱 네트워크 예외로 인해 루트 도메인($mainUrl) 쿠키 재발급 시도...")
                val bypass = solveWaf(mainUrl)
                if (bypass != null) {
                    cfCookies = bypass.first
                    cfUserAgent = bypass.second
                    println("[MovieKing DBG] 🟢 쿠키 재발급 완료. OkHttp 재요청 시도...")
                    return app.get(url, headers = getHeaders(referer), timeout = timeout)
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
                println("[MovieKing DBG] 📂 캐시된 도메인 로드: $currentMainUrl")
            }
            
            // 1단계: 현재/캐시된 도메인 검사
            try {
                println("[MovieKing DBG] 🔍 [1단계] 현재 도메인 체크 -> $currentMainUrl")
                val res = safeGet(currentMainUrl, timeout = 5L)
                val finalUrl = res.url.trimEnd('/')
                println("[MovieKing DBG] 📥 [1단계] 결과: HTTP ${res.code}, 최종URL=$finalUrl")
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        println("[MovieKing DBG] 🔄 리다이렉트 감지됨: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true; return@withLock
                } else {
                    println("[MovieKing DBG] ⚠️ 현재 도메인 접속 실패. 캐시 삭제 후 브루트포스 진행")
                    clearDomainCache()
                }
            } catch (e: Exception) { 
                if (e is CancellationException) throw e
                println("[MovieKing DBG] ❌ [1단계] 현재 도메인 접속 실패: ${e.javaClass.simpleName} - ${e.message}")
                clearDomainCache()
            }

            // 2단계: 브루트포스 순차 탐색 적용
            val match = Regex("mvking(\\d+)\\.(\\w+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 7
            val tld = match?.groupValues?.get(2) ?: "org"
            println("[MovieKing DBG] 🚀 [2단계] 브루트포스 탐색 시작 (시작번호: $startNum, TLD: $tld)")

            try {
                coroutineScope {
                    val candidates = (startNum..startNum + 200).map { "https://mvking$it.$tld" }
                    val chunks = candidates.chunked(5)
                    
                    for (chunk in chunks) {
                        println("[MovieKing DBG] 🔎 브루트포스 묶음 검사: ${chunk.first()} ~ ${chunk.last()}")
                        val deferreds = chunk.map { testDomain ->
                            async {
                                try {
                                    val res = safeGet(testDomain, timeout = 3L)
                                    val extractedTestUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: testDomain
                                    val testFinalUrl = extractedTestUrl.replace("http://", "https://")
                                    
                                    if (res.isSuccessful && res.text.contains("video-card")) {
                                        println("[MovieKing DBG] 🎯 후보 도메인 유효 확인: $testFinalUrl")
                                        return@async testFinalUrl
                                    }
                                } catch (e: Exception) {}
                                null
                            }
                        }
                        
                        val foundDomain = deferreds.awaitAll().firstOrNull { it != null }
                        if (foundDomain != null) {
                            println("[MovieKing DBG] ✨ [2단계] 브루트포스 탐색 성공: $foundDomain")
                            saveDomain(foundDomain)
                            mainUrl = currentMainUrl
                            isDomainChecked = true
                            return@coroutineScope
                        }
                    }
                }
                if (isDomainChecked) return@withLock
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            // 3단계: 베이스 주소 폴백 시도
            try {
                println("[MovieKing DBG] 🌐 [3단계] 베이스 도메인(mvking7.org) 폴백 시도")
                val baseRes = safeGet("https://mvking7.org", timeout = 5L)
                val baseFinalUrl = baseRes.url.trimEnd('/')
                if (baseRes.isSuccessful && baseRes.text.contains("video-card")) {
                    println("[MovieKing DBG] ✨ [3단계] 베이스 도메인 폴백 성공: $baseFinalUrl")
                    saveDomain(baseFinalUrl)
                    mainUrl = currentMainUrl
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
                println("[MovieKing DBG] 🔧 도메인 수동 갱신 명령: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true 
                return listOf(newMovieSearchResponse("✅ 무비킹 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔍 검색 요청 시작: query=\"$query\", targetUrl=$mainUrl")
        
        return try {
            var searchUrl = "$mainUrl/search?k=$query"
            var res = safeGet(searchUrl)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl

            if (redirectedDomain != mainUrl || !finalUrl.contains("search")) {
                if (redirectedDomain != mainUrl) {
                    println("[MovieKing DBG] 🔄 검색 중 리다이렉트 감지: $redirectedDomain")
                    saveDomain(redirectedDomain)
                    mainUrl = currentMainUrl
                }
                searchUrl = "$mainUrl/search?k=$query"
                res = safeGet(searchUrl)
            }

            if (!res.url.contains("search")) return emptyList()

            val items = res.document.select(".video-card").mapNotNull { it.toSearchResponse() }
            println("[MovieKing DBG] 🔍 검색 결과 수: ${items.size}개")
            items
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
            emptyList() 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val separator = if (request.data.contains("?")) "&" else "?"
        var url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing DBG] 📄 메인페이지 로드 요청: 카테고리=${request.name}, URL=$url")
        
        return try {
            var res = safeGet(url)
            val finalUrl = res.url
            val redirectedDomain = Regex("^https?://[^/]+").find(finalUrl)?.value ?: mainUrl
            
            val decodedRequestData = URLDecoder.decode(request.data, "UTF-8").substringBefore("?")
            val decodedFinalUrl = URLDecoder.decode(finalUrl, "UTF-8")

            if (redirectedDomain != mainUrl || !decodedFinalUrl.contains(decodedRequestData)) {
                if (redirectedDomain != mainUrl) {
                    println("[MovieKing DBG] 🔄 메인페이지 로드 중 리다이렉트 감지: $redirectedDomain")
                    saveDomain(redirectedDomain)
                    mainUrl = currentMainUrl
                }
                url = "$mainUrl${request.data}${separator}page=$page"
                res = safeGet(url)
            }

            val doc = res.document
            val list = doc.select(".video-card").mapNotNull { it.toSearchResponse(request.name) }
            println("[MovieKing DBG] 📄 메인페이지 아이템 파싱 완료: ${list.size}개 발견 (${request.name})")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e 
            println("[MovieKing DBG] ❌ 메인페이지 로드 예외: ${e.javaClass.simpleName} - ${e.message}")
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
        println("[MovieKing DBG] 🎬 정제된 상세페이지 URL: $finalUrl")
        
        val doc = safeGet(finalUrl).document
        val title = (doc.selectFirst("h3.title")?.text() ?: doc.selectFirst("h1.text-secondary")?.text() ?: "Unknown").replace(titleRegex, "").trim()
        val poster = passedPoster ?: doc.selectFirst(".single-video-left img")?.attr("src") ?: ""
        val intro = doc.selectFirst(".single-video-info-content h6:contains(소개)")?.nextElementSibling()?.text()?.trim() ?: "설명이 없습니다."
        
        val episodeList = doc.select(".video-slider-right-list .eps_a").map { 
            newEpisode(fixUrl(it.attr("href"))) { this.name = it.text().trim() } 
        }.reversed()

        println("[MovieKing DBG] 🎬 파싱 완료: 제목=\"$title\", 에피소드 수=${episodeList.size}")

        return if (episodeList.size <= 1) {
            newMovieLoadResponse(title, finalUrl, TvType.Movie, finalUrl) { this.posterUrl = fixUrl(poster); this.plot = intro }
        } else {
            newTvSeriesLoadResponse(title, finalUrl, TvType.TvSeries, episodeList) { this.posterUrl = fixUrl(poster); this.plot = intro }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔗 영상 링크 추출 시작: $data")
        var targetData = data
        if (!targetData.startsWith(mainUrl) && (targetData.contains("mvking") || targetData.contains("movieking"))) {
            targetData = mainUrl + targetData.replace(Regex("https?://[^/]+"), "")
        }
        println("[MovieKing DBG] 🔗 타겟 URL: $targetData")
        
        try {
            val src = safeGet(targetData).document.selectFirst("iframe#view_iframe")?.attr("src")
            println("[MovieKing DBG] 🔗 추출된 iframe src: $src")
            if (src != null) { 
                loadExtractor(fixUrl(src), targetData, subtitleCallback, callback)
                println("[MovieKing DBG] 🟢 loadExtractor 실행 완료")
                return true 
            } else {
                println("[MovieKing DBG] ⚠️ iframe#view_iframe 태그를 찾지 못함")
            }
        } catch (e: Exception) { 
            if (e is CancellationException) throw e 
            println("[MovieKing DBG] ❌ loadLinks 예외: ${e.javaClass.simpleName} - ${e.message}")
        }
        return false
    }
}
