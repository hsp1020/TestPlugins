package com.lagradost.cloudstream3.plugins.tvchak

import android.content.Context
import com.lagradost.cloudstream3.*
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
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[ChakPlay] 💾 도메인 로컬 저장 완료 -> $newDomain")
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

    private fun isCloudflareChallenge(html: String): Boolean {
        val lowerHtml = html.lowercase()
        val isChallenge = (lowerHtml.contains("window._cf_chl_opt") || 
                lowerHtml.contains("cf-challenge-running") || 
                lowerHtml.contains("id=\"challenge-form\"") ||
                lowerHtml.contains("id=\"cf-challenge-form\"") ||
                lowerHtml.contains("just a moment...")) && 
               lowerHtml.contains("cloudflare")
        if (isChallenge) {
            println("[ChakPlay] 🚨 Cloudflare 보안 챌린지 감지됨")
        }
        return isChallenge
    }

    private suspend fun solveCloudflare(url: String): Pair<String, String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) {
            println("[ChakPlay] ❌ CF 우회 시도 중 Context 획득 실패")
            null
        } ?: return@withContext null

        println("[ChakPlay] 🛡️ Cloudflare 우회 웹뷰 기동 -> URL: $url")

        try {
            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            println("[ChakPlay] 🧹 우회용 웹뷰 기존 캐시 쿠키 제거 완료")
        } catch (e: Exception) {
            println("[ChakPlay] ⚠️ 쿠키 초기화 실패: ${e.message}")
        }

        suspendCancellableCoroutine<Pair<String, String>?> { continuation ->
            val webView = android.webkit.WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.userAgentString = userAgent
            
            webView.webViewClient = object : android.webkit.WebViewClient() {
                var solved = false

                override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null) return
                    checkCookies(url)
                }

                fun checkCookies(currentUrl: String) {
                    if (solved) return
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    println("[ChakPlay] 🍪 실시간 쿠키 수집 중... (길이: ${cookies.length})")

                    if (cookies.contains("cf_clearance")) {
                        solved = true
                        println("[ChakPlay] ✅ 성공: cf_clearance 쿠키 획득 완료!")
                        if (continuation.isActive) {
                            continuation.resume(Pair(cookies, userAgent))
                        }
                        try {
                            webView.stopLoading()
                            webView.destroy()
                        } catch (e: Exception) {}
                    }
                }
            }

            webView.loadUrl(url)

            val handler = android.os.Handler(android.os.Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    println("[ChakPlay] ⏰ CF 우회 웹뷰 15초 타임아웃 발생")
                    val cookieManager = android.webkit.CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        println("[ChakPlay] 🍀 타임아웃 도달 직전 수집된 쿠키 반환 시도")
                        continuation.resume(Pair(cookies, userAgent))
                    } else {
                        continuation.resume(null)
                    }
                    try {
                        webView.stopLoading()
                        webView.destroy()
                    } catch (e: Exception) {}
                }
            }
            handler.postDelayed(timeoutRunnable, 15000)

            val intervalRunnable = object : Runnable {
                override fun run() {
                    if (continuation.isActive) {
                        webView.url?.let { currentUrl ->
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            val cookies = cookieManager.getCookie(currentUrl) ?: ""
                            if (cookies.contains("cf_clearance")) {
                                println("[ChakPlay] ✅ 성공 (인터벌 체크): cf_clearance 검출 완료")
                                handler.removeCallbacks(timeoutRunnable)
                                continuation.resume(Pair(cookies, userAgent))
                                try {
                                    webView.stopLoading()
                                    webView.destroy()
                                } catch (e: Exception) {}
                                return
                            }
                        }
                        handler.postDelayed(this, 1500)
                    }
                }
            }
            handler.postDelayed(intervalRunnable, 1500)

            continuation.invokeOnCancellation {
                println("[ChakPlay] 🛑 CF 코루틴 취소됨 -> 자원 정리")
                handler.removeCallbacks(timeoutRunnable)
                handler.removeCallbacks(intervalRunnable)
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (e: Exception) {}
            }
        }
    }

    private suspend fun safeGet(url: String, referer: String? = null): NiceResponse {
        println("[ChakPlay] 📡 GET 요청 전송: $url")
        var res = app.get(url, headers = getHeaders(referer))
        
        if (isCloudflareChallenge(res.text)) {
            println("[ChakPlay] 🧱 Cloudflare 장벽 도달. 우회 대기열 진입")
            
            cfMutex.withLock {
                res = app.get(url, headers = getHeaders(referer))
                if (!isCloudflareChallenge(res.text)) {
                    println("[ChakPlay] 🚀 타 스레드 우회 완료 대기열 통과 -> $url")
                    return res
                }

                println("[ChakPlay] 🔒 대표 대기열 진입: 우회 직접 처리 시작")
                val bypass = solveCloudflare(mainUrl)
                if (bypass != null) {
                    cfCookies = bypass.first
                    cfUserAgent = bypass.second
                    println("[ChakPlay] 🔓 우회 성공. 헤더 갱신 후 재요청")
                    res = app.get(url, headers = getHeaders(referer))
                } else {
                    println("[ChakPlay] ❌ 우회 처리 실패")
                }
            }
        } else {
            println("[ChakPlay] 🟢 HTTP 응답 상태 정상")
        }
        return res
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            println("[ChakPlay] 🔍 액티브 도메인 유효성 검증")

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawCachedDomain = prefs?.getString(PREF_KEY, null)
            val cachedDomain = rawCachedDomain?.replace("http://", "https://")

            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
                println("[ChakPlay] 📂 캐시 도메인 정보 로드: $currentMainUrl")
            }

            val testPath = "/index.php/vod/show/id/1/page/1.html"

            try {
                val res = app.get("$currentMainUrl$testPath", headers = getHeaders(), timeout = 3L)
                val finalUrl = res.url.substringBefore("/index.php").trimEnd('/')
                
                if (res.isSuccessful && res.text.contains("movie-list-item") || isCloudflareChallenge(res.text)) {
                    if (currentMainUrl != finalUrl && finalUrl.startsWith("http")) {
                        println("[ChakPlay] 🔄 리다이렉트 도메인 검출: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true
                    println("[ChakPlay] ✅ 도메인 유지 확정")
                    return@withLock
                }
            } catch (e: Exception) { 
                println("[ChakPlay] ❌ 접속 확인 불가: ${e.message}") 
            }

            val match = Regex("tvchak(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 200
            println("[ChakPlay] 🚀 신규 도메인 브루트포스 개시 (시작 범위: $startNum)")

            var foundDomain: String? = null

            coroutineScope {
                val candidates = (startNum..startNum + 50).map { "https://tvchak$it.com" }
                val chunks = candidates.chunked(5)

                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            val testUrl = "$testDomain$testPath"
                            try {
                                val res = app.get(testUrl, headers = getHeaders(), timeout = 3L)
                                val extractedFinalUrl = res.url.substringBefore("/index.php").trimEnd('/')
                                if (res.isSuccessful && res.text.contains("movie-list-item")) {
                                    println("[ChakPlay] 🎯 활성 도메인 확보: $extractedFinalUrl")
                                    return@async extractedFinalUrl
                                } else if (isCloudflareChallenge(res.text)) {
                                    println("[ChakPlay] 🧱 Cloudflare 탐지 완료 도메인 확보: $extractedFinalUrl")
                                    return@async extractedFinalUrl
                                }
                            } catch (e: Exception) {}
                            null
                        }
                    }

                    val results = deferreds.awaitAll()
                    foundDomain = results.firstOrNull { it != null }

                    if (foundDomain != null) break
                }
            }

            if (foundDomain != null) {
                println("[ChakPlay] ✨ 신규 도메인 확정 저장: $foundDomain")
                saveDomain(foundDomain!!)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }

            println("[ChakPlay] 💀 탐색 범주 내 모든 탐색 실패. 기본 도메인 유지")
            isDomainChecked = true
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/show/id/${request.data}/page/$page.html"
            println("[ChakPlay] [getMainPage] 요청 페이지: $page, 대상: $url")
            
            val document = safeGet(url).document
            val items = document.select(".movie-list-body .movie-list-item").mapNotNull { element ->
                parseItem(element, request.data == "1")
            }

            println("[ChakPlay] [getMainPage] 페이지 $page 파싱 완료 (목록 수: ${items.size})")
            newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
        } catch (e: Exception) {
            println("[ChakPlay] [getMainPage] 에러 발생: ${e.message}")
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!tvchak ")) {
            val userDomain = query.substring(8).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                println("[ChakPlay] [search] 수동 도메인 백도어 갱신 지시: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ TVChak 도메인 갱신: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/search.html?wd=$query"
            println("[ChakPlay] [search] 검색 수행 -> 단어: $query, URL: $url")
            
            val document = safeGet(url).document
            val items = document.select(".vod-search-list .box").mapNotNull { element ->
                val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return@mapNotNull null
                val href = fixUrlNull(element.selectFirst("a.cover-link")?.attr("href")) ?: return@mapNotNull null
                val poster = element.selectFirst(".movie-post-lazyload")?.attr("data-original") ?: ""
                
                val encodedPoster = URLEncoder.encode(poster, "UTF-8")
                val finalUrl = "$href?poster=$encodedPoster"

                newTvSeriesSearchResponse(title, finalUrl, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
            println("[ChakPlay] [search] 검색 결과 확보 수: ${items.size}")
            items
        } catch (e: Exception) {
            println("[ChakPlay] [search] 검색 도중 에러: ${e.message}")
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        println("[ChakPlay] [load] 정보 로딩 시도 -> 대상: $url")
        
        val realUrl = url.substringBefore("?poster=")
        val encodedPoster = url.substringAfter("?poster=", "")
        val passedPoster = if (encodedPoster.isNotBlank()) {
            URLDecoder.decode(encodedPoster, "UTF-8")
        } else null

        val document = safeGet(realUrl).document
        
        val title = document.selectFirst("h1.movie-title")?.text()?.trim() ?: ""
        val plot = document.selectFirst("h2#sum_tag")?.text()?.trim() ?: ""
        val poster = passedPoster ?: document.selectFirst(".poster img")?.attr("src") ?: ""
        
        val episodes = document.select(".content_playlist li a").reversed().mapIndexedNotNull { index, element ->
            val epTitle = element.text().trim()
            val epHref = fixUrlNull(element.attr("href")) ?: return@mapIndexedNotNull null

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = index + 1
            }
        }

        println("[ChakPlay] [load] 상세 정보 획득 완료 (에피소드: ${episodes.size})")
        val tvType = if (episodes.size == 1 && title.contains("영화")) TvType.Movie else TvType.TvSeries

        return newTvSeriesLoadResponse(title, realUrl, tvType, episodes) {
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
        checkAndUpdateDomain()
        println("[ChakPlay] [loadLinks] 정적 무음 핸드쉐이크 기반 탐색 개시: $data")
        
        // 1. 디테일 페이지 획득 및 player_aaaa 파싱
        val document = safeGet(data).document
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data()
        
        if (scriptContent == null) {
            println("[ChakPlay] [loadLinks] ❌ 에러: player_aaaa 스크립트를 찾지 못했습니다.")
            return false
        }

        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val matchUrl = urlRegex.find(jsonString)
        val rawUrl = matchUrl?.groupValues?.get(1)?.replace("\\/", "/") 
        
        if (rawUrl == null) {
            println("[ChakPlay] [loadLinks] ❌ 에러: JSON에서 원본 url 추출 실패")
            return false
        }

        println("[ChakPlay] [loadLinks] 🔍 스크립트 추출 원본 주소: $rawUrl")

        var videoUrl = rawUrl
        var refererUrl = mainUrl // 기본 레퍼러 지정

        // 2. 만약 주소가 검증 페이지(.php / wiselife.blog)를 우회하는 중계 주소인 경우
        if (rawUrl.contains("chaktt.php?url=")) {
            // 중계 주소 뒤에 숨겨진 실제 CDN 주소 추출
            val realEncodedUrl = rawUrl.substringAfter("url=")
            videoUrl = URLDecoder.decode(realEncodedUrl, "UTF-8")
            
            // 검증 서버의 도메인을 동적으로 감지하여 올바른 Referer 지정 (핫링크 해제용)
            try {
                val parsedUri = java.net.URL(rawUrl)
                refererUrl = "${parsedUri.protocol}://${parsedUri.host}/"
                println("[ChakPlay] [loadLinks] 🎯 중계 서버 감지 -> 동적 Referer 수립: $refererUrl")
            } catch (e: Exception) {
                refererUrl = "https://ckp.wiselife.blog/"
            }

            // [ChakPlay] 중요: 백엔드 IP 자동 승인을 위한 무음 사전 노크 (Silent Handshake)
            // 웹뷰를 켜지 않고 가벼운 정적 백그라운드 HTTP 요청만으로 세션을 활성화합니다.
            println("[ChakPlay] [loadLinks] 🔓 백엔드 IP 승인을 유도하기 위해 중계 서버 사전 접속 시도 (Silent Handshake)")
            try {
                val handshakeResponse = safeGet(rawUrl, referer = mainUrl)
                println("[ChakPlay] [loadLinks] 🟢 사전 접속 승인 성공 (상태코드: ${handshakeResponse.code})")
            } catch (e: Exception) {
                println("[ChakPlay] [loadLinks] ⚠️ 사전 접속 중 에러 발생 (재생 시도 지속): ${e.message}")
            }
        }

        // 자막 분리 처리
        var subUrl: String? = null
        val subRegex = Regex("""\?(srt|vtt)(https?://.*)""")
        val subMatch = subRegex.find(videoUrl)
        if (subMatch != null) {
            videoUrl = videoUrl.substring(0, subMatch.range.first)
            subUrl = subMatch.groupValues[2]
            println("[ChakPlay] [loadLinks] 📝 내장 자막 감지: $subUrl")
        }

        println("[ChakPlay] [loadLinks] 🎬 최종 비디오 주소: $videoUrl")
        println("[ChakPlay] [loadLinks] 📡 최종 헤더 Referer: $refererUrl")

        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = videoUrl,
                type = type
            ) {
                this.referer = refererUrl
                this.quality = Qualities.P1080.value
                
                // CDN 방화벽 패스용 헤더 구성 (동적 Referer 및 User-Agent 매칭)
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

        return true
    }

    private fun parseItem(element: Element, isMovie: Boolean): SearchResponse? {
        return try {
            val title = element.selectFirst(".movie-title")?.text()?.trim() ?: return null
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
            println("[ChakPlay] [parseItem] 아이템 추출 예외: ${e.message}")
            null
        }
    }
}
