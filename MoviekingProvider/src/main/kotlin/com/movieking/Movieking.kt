package com.movieking

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
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

data class SafeDocResponse(
    val url: String,
    val text: String,
    val document: Document,
    val code: Int = 200,
    val isSuccessful: Boolean = true
)

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

        fun clearDomainCache() {
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.remove(PREF_KEY)?.apply()
            println("[MovieKing DBG] 🧹 캐시 도메인 초기화 완료")
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

    private fun unescapeJsString(jsonStr: String): String {
        if (jsonStr.isBlank() || jsonStr == "null") return ""
        return try {
            org.json.JSONTokener(jsonStr).nextValue().toString()
        } catch (e: Exception) {
            jsonStr.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", "\n")
        }
    }

    // 루트 도메인 세션 확보 후 JS fetch로 서브 경로 HTML을 가져오는 최적화 파서
    private suspend fun fetchDocViaWebView(url: String, timeoutMs: Long = 10000L): SafeDocResponse = withContext(Dispatchers.Main) {
        val rootUrl = Regex("^https?://[^/]+").find(url)?.value ?: mainUrl
        println("[MovieKing DBG] 🌐 WebView 세션 생성 - 루트: $rootUrl | 대상: $url")

        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? Context
        } catch (e: Exception) { null } ?: AcraApplication.context ?: run {
            return@withContext SafeDocResponse(url, "", Jsoup.parse(""), 500, false)
        }

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
            var isFailed = false

            fun executeJsFetch(view: android.webkit.WebView, targetUrl: String) {
                val script = """
                    (async function() {
                        try {
                            let resp = await fetch("$targetUrl", {
                                headers: { "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" }
                            });
                            return await resp.text();
                        } catch(e) {
                            return "FETCH_ERR: " + e;
                        }
                    })();
                """.trimIndent()

                view.evaluateJavascript(script) { rawResult ->
                    if (solved || isFailed || !continuation.isActive) return@evaluateJavascript
                    val html = unescapeJsString(rawResult ?: "")

                    if (html.length > 200 && !isChallenge(html, 200) && !html.startsWith("FETCH_ERR:")) {
                        solved = true
                        println("[MovieKing DBG] 🎉 JS fetch로 HTML 획득 성공! (길이: ${html.length})")
                        val doc = Jsoup.parse(html, targetUrl)
                        continuation.resume(SafeDocResponse(targetUrl, html, doc, 200, true))
                        cleanup()
                    } else {
                        println("[MovieKing DBG] ⚠️ JS fetch 응답 미흡 (내용: ${html.take(80)})")
                    }
                }
            }

            webView?.webViewClient = object : android.webkit.WebViewClient() {
                override fun onPageStarted(view: android.webkit.WebView?, pageUrl: String?, favicon: android.graphics.Bitmap?) {
                    println("[MovieKing DBG] 🚀 WebView 루트 접속 시작: $pageUrl")
                }

                override fun onPageFinished(view: android.webkit.WebView?, finishedUrl: String?) {
                    println("[MovieKing DBG] 🏁 WebView 루트 접속 완료: $finishedUrl")
                    if (solved || isFailed || !continuation.isActive || view == null) return

                    val cookies = try { android.webkit.CookieManager.getInstance().getCookie(rootUrl) ?: "" } catch (e: Exception) { "" }
                    if (cookies.isNotBlank()) cfCookies = cookies

                    val cleanTarget = url.trimEnd('/')
                    val cleanRoot = rootUrl.trimEnd('/')

                    if (cleanTarget == cleanRoot) {
                        // 루트 도메인 자체가 대상일 때는 outerHTML 추출
                        view.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { rawHtml ->
                            if (solved || isFailed || !continuation.isActive) return@evaluateJavascript
                            val html = unescapeJsString(rawHtml ?: "")
                            if (html.length > 200 && !isChallenge(html, 200)) {
                                solved = true
                                println("[MovieKing DBG] 🎉 루트 DOM 추출 성공! (길이: ${html.length})")
                                val doc = Jsoup.parse(html, rootUrl)
                                continuation.resume(SafeDocResponse(rootUrl, html, doc, 200, true))
                                cleanup()
                            }
                        }
                    } else {
                        // 서브 경로일 경우 루트 인증 세션 상에서 JS fetch 실행
                        println("[MovieKing DBG] ⚡ 루트 세션 확립 완료 -> 서브 경로 JS fetch 실행: $url")
                        executeJsFetch(view, url)
                    }
                }

                override fun onReceivedSslError(view: android.webkit.WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                    println("[MovieKing DBG] ⚠️ WebView SSL 에러 강제 진행: $error")
                    handler?.proceed()
                }

                override fun onReceivedError(view: android.webkit.WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                    println("[MovieKing DBG] ⚠️ WebView 에러: code=$errorCode, desc=$description, url=$failingUrl")
                    if (errorCode == -6 || errorCode == -2 || errorCode == -101 || 
                        description?.contains("CONNECTION_RESET") == true || 
                        description?.contains("NAME_NOT_RESOLVED") == true) {
                        
                        isFailed = true
                        if (!solved && continuation.isActive) {
                            println("[MovieKing DBG] 🛑 WebView 네트워크 에러 감지 - 중단: $failingUrl")
                            continuation.resume(SafeDocResponse(url, "", Jsoup.parse(""), 500, false))
                            cleanup()
                        }
                    }
                }
            }

            println("[MovieKing DBG] 📡 WebView 루트 도메인 로드 실행 -> $rootUrl")
            webView?.loadUrl(rootUrl)

            val timeoutRunnable = Runnable {
                if (!solved && !isFailed && continuation.isActive) {
                    println("[MovieKing DBG] ⏱️ WebView 타임아웃 (10초)")
                    continuation.resume(SafeDocResponse(url, "", Jsoup.parse(""), 500, false))
                    cleanup()
                }
            }
            mainHandler.postDelayed(timeoutRunnable, timeoutMs)

            continuation.invokeOnCancellation {
                solved = true
                mainHandler.removeCallbacksAndMessages(null)
                cleanup()
            }
        }
    }

    private suspend fun safeGetDoc(url: String, timeout: Long = 10L): SafeDocResponse {
        return cfMutex.withLock {
            fetchDocViaWebView(url, timeoutMs = timeout * 1000L)
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
                val res = safeGetDoc(currentMainUrl, timeout = 5L)
                val finalUrl = res.url.trimEnd('/')
                if (res.isSuccessful && res.text.contains("video-card")) {
                    if (currentMainUrl != finalUrl) {
                        println("[MovieKing DBG] 🔄 리다이렉트 감지됨: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true
                    println("[MovieKing DBG] ✅ [1단계] 현재 도메인 검증 완료: $mainUrl")
                    return@withLock
                }
            } catch (e: Exception) { 
                if (e is CancellationException) throw e
                println("[MovieKing DBG] ❌ [1단계] 현재 도메인 접속 실패: ${e.javaClass.simpleName} - ${e.message}")
            }

            // 2단계: 브루트포스 순차 탐색 (.org 범위 한정)
            val match = Regex("mvking(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 13
            println("[MovieKing DBG] 🚀 [2단계] 브루트포스 탐색 시작 (.org 한정, 시작: $startNum)")

            try {
                coroutineScope {
                    val candidates = (startNum..startNum + 20).map { "https://mvking$it.org" }
                    val chunks = candidates.chunked(5)
                    
                    for (chunk in chunks) {
                        if (isDomainChecked) break
                        println("[MovieKing DBG] 🔎 브루트포스 묶음 검사: ${chunk.first()} ~ ${chunk.last()}")
                        val deferreds = chunk.map { testDomain ->
                            async {
                                if (isDomainChecked) return@async null
                                try {
                                    val res = safeGetDoc(testDomain, timeout = 3L)
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
                println("[MovieKing DBG] 🌐 [3단계] 베이스 도메인(mvking13.org) 폴백 시도")
                val baseRes = safeGetDoc("https://mvking13.org", timeout = 5L)
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
                println("[MovieKing DBG] 🔧 도메인 수동 강제 갱신 명령: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true 
                try {
                    val res = safeGetDoc(mainUrl)
                    if (res.isSuccessful) {
                        return listOf(newMovieSearchResponse("✅ 무비킹 수동 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
                    }
                } catch (e: Exception) {}
                return listOf(newMovieSearchResponse("✅ 무비킹 갱신 완료: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        println("[MovieKing DBG] 🔍 검색 요청 시작: query=\"$query\", targetUrl=$mainUrl")
        
        return try {
            val searchUrl = "$mainUrl/search?k=$query"
            val res = safeGetDoc(searchUrl)

            if (!res.isSuccessful || !res.url.contains("search")) return emptyList()

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
        val url = "$mainUrl${request.data}${separator}page=$page"
        println("[MovieKing DBG] 📄 메인페이지 로드 요청: 카테고리=${request.name}, URL=$url")
        
        return try {
            val res = safeGetDoc(url)
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
        
        val doc = safeGetDoc(finalUrl).document
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
            val src = safeGetDoc(targetData).document.selectFirst("iframe#view_iframe")?.attr("src")
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
