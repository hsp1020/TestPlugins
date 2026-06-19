package com.lagradost.cloudstream3.plugins.tvchak

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.nicehttp.NiceResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.Semaphore         // ⭐ 세마포어 임포트 추가
import kotlinx.coroutines.sync.withPermit       // ⭐ 세마포어 임포트 추가
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
            println("TVCHAK_DEBUG: [Tvchak DBG] 💾 영구 도메인 저장 완료: $newDomain")
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

    // ⭐ 카탈로그 동시 요청을 최대 2개로 제한하여 병목 및 일시 차단을 해소하는 세마포어 선언
    private val mainPageSemaphore = Semaphore(2)

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
        if (isChallenge) println("TVCHAK_DEBUG: [CF Check] 🚨 Cloudflare 챌린지 화면이 감지되었습니다!")
        return isChallenge
    }

    private suspend fun solveCloudflare(url: String): Pair<String, String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) {
            println("TVCHAK_DEBUG: [CF Bypass] ❌ ERROR: Context 획득 실패 (앱 크래시 방지)")
            null
        } ?: return@withContext null

        println("TVCHAK_DEBUG: [CF Bypass] 🛡️ Cloudflare 우회 시작 -> URL: $url")

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            println("TVCHAK_DEBUG: [CF Bypass] 🧹 이전 웹뷰 만료 캐시 쿠키 제거 완료")
        } catch (e: Exception) {
            println("TVCHAK_DEBUG: [CF Bypass] ⚠️ WARNING: 쿠키 비우기 실패: ${e.message}")
        }

        suspendCancellableCoroutine<Pair<String, String>?> { continuation ->
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.userAgentString = userAgent
            
            println("TVCHAK_DEBUG: [CF Bypass] 🌐 WebView User-Agent 세팅 완료: $userAgent")

            webView.webViewClient = object : WebViewClient() {
                var solved = false

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null) return
                    checkCookies(url)
                }

                fun checkCookies(currentUrl: String) {
                    if (solved) return
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(currentUrl) ?: ""
                    println("TVCHAK_DEBUG: [CF Bypass] 🍪 실시간 쿠키 스캔 중... (현재길이: ${cookies.length})")

                    if (cookies.contains("cf_clearance")) {
                        solved = true
                        println("TVCHAK_DEBUG: [CF Bypass] ✅ 성공: cf_clearance 쿠키 획득!")
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

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    println("TVCHAK_DEBUG: [CF Bypass] ⏰ 실패: 15초 타임아웃 초과. 페이지가 로딩되지 않았거나 CF 난이도가 너무 높습니다.")
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        println("TVCHAK_DEBUG: [CF Bypass] 🍀 타임아웃 직전 쿠키 발견됨 반환 시도.")
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
                            val cookieManager = CookieManager.getInstance()
                            val cookies = cookieManager.getCookie(currentUrl) ?: ""
                            if (cookies.contains("cf_clearance")) {
                                println("TVCHAK_DEBUG: [CF Bypass] ✅ 성공 (인터벌 체크): cf_clearance 발견!")
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
                println("TVCHAK_DEBUG: [CF Bypass] 🛑 코루틴 취소됨. 리소스 정리.")
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
        println("TVCHAK_DEBUG: [SafeGet] 📡 GET 요청: $url")
        var res = app.get(url, headers = getHeaders(referer))
        
        if (isCloudflareChallenge(res.text)) {
            println("TVCHAK_DEBUG: [SafeGet] 🧱 Cloudflare 막힘 감지. 우회 대기열 진입 -> $url")
            
            cfMutex.withLock {
                res = app.get(url, headers = getHeaders(referer))
                
                if (!isCloudflareChallenge(res.text)) {
                    println("TVCHAK_DEBUG: [SafeGet] 🚀 앞서 다른 탭이 우회를 완료했습니다! 묻어가기 통과 -> $url")
                    return res
                }

                println("TVCHAK_DEBUG: [SafeGet] 🔒 [대표 지정] 내가 직접 CF 우회 시작...")
                val bypass = solveCloudflare(mainUrl)
                if (bypass != null) {
                    cfCookies = bypass.first
                    cfUserAgent = bypass.second
                    println("TVCHAK_DEBUG: [SafeGet] 🔓 우회 성공! 새로운 쿠키로 재요청: $url")
                    res = app.get(url, headers = getHeaders(referer)) 
                } else {
                    println("TVCHAK_DEBUG: [SafeGet] ❌ 우회 실패. 데이터가 정상적으로 표시되지 않을 수 있습니다.")
                }
            }
        } else {
            println("TVCHAK_DEBUG: [SafeGet] 🟢 정상 응답 획득 (CF 없음 또는 이미 통과)")
        }
        return res
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            println("TVCHAK_DEBUG: [DomainCheck] 🔍 도메인 체크 시작")

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val rawCachedDomain = prefs?.getString(PREF_KEY, null)
            val cachedDomain = rawCachedDomain?.replace("http://", "https://")

            if (cachedDomain != null) {
                mainUrl = cachedDomain
            }

            var internetOk = false
            try {
                val googleRes = app.get("https://www.google.com").code
                if (googleTest == 200) {
                    localInternetActive = true
                }
            } catch (t: Throwable) {}

            try {
                val doc = app.get(mainUrl, headers = commonHeaders).document
                localInternetActive = true
            } catch (e: Exception) {
                // 예외 무시
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl/index.php/vod/show/id/${request.data}/page/$page.html"
        println("TVCHAK_DEBUG: [getMainPage] 요청 페이지 = $page, URL = $url")

        // ⭐ 세마포어 적용: 최대 2개의 카탈로그 요청만 허용하여 백그라운드 우회 안정성 강화
        return mainPageSemaphore.withPermit {
            try {
                val document = safeGet(url).document

                val items = document.select(".movie-list-body .movie-list-item").mapNotNull { element ->
                    parseItem(element, request.data == "1")
                }

                if (items.isEmpty()) {
                    println("TVCHAK_DEBUG: [getMainPage] ⚠️ 경고: 파싱된 아이템이 0개입니다.")
                } else {
                    println("TVCHAK_DEBUG: [getMainPage] 페이지 $page 파싱 성공 - 아이템 수: ${items.size}")
                }
                
                newHomePageResponse(request.name, items, hasNext = items.isNotEmpty())
            } catch (e: Exception) {
                println("TVCHAK_DEBUG: [getMainPage] 에러 발생: ${e.message}")
                e.printStackTrace()
                if (e is CancellationException) throw e
                newHomePageResponse(request.name, emptyList(), hasNext = false)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!tvchak ")) {
            val userDomain = query.substring(8).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                println("TVCHAK_DEBUG: [search] 🔧 백도어 수동 갱신: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ TVChak 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        return try {
            val url = "$mainUrl/index.php/vod/search.html?wd=$query"
            println("TVCHAK_DEBUG: [search] 검색어 = $query, URL = $url")
            
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
            println("TVCHAK_DEBUG: [search] 검색 결과 수: ${items.size}")
            items
        } catch (e: Exception) {
            println("TVCHAK_DEBUG: [search] 검색 에러: ${e.message}")
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        println("TVCHAK_DEBUG: [load] 상세페이지 로드: $url")
        
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

        println("TVCHAK_DEBUG: [load] 상세페이지 파싱 완료. 제목: $title, 에피소드 수: ${episodes.size}")
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
        println("TVCHAK_DEBUG: [loadLinks] 영상 링크 추출 시작: $data")
        
        val document = safeGet(data).document
        
        val scriptContent = document.select("script").find { it.data().contains("player_aaaa") }?.data()
        
        if (scriptContent == null) {
            println("TVCHAK_DEBUG: [loadLinks] ❌ ERROR: player_aaaa 스크립트를 찾을 수 없습니다.")
            return false
        }

        val jsonString = scriptContent.substringAfter("player_aaaa=").substringBefore("</script>").trim().removeSuffix(";")
        
        val urlRegex = Regex(""""url"\s*:\s*"([^"]+)"""")
        val matchUrl = urlRegex.find(jsonString)
        val rawUrl = matchUrl?.groupValues?.get(1)?.replace("\\/", "/") 
        
        if (rawUrl == null) {
            println("TVCHAK_DEBUG: [loadLinks] ❌ ERROR: 스크립트에서 url을 추출하지 못했습니다.")
            return false
        }

        var videoUrl = rawUrl
        var subUrl: String? = null

        val subRegex = Regex("""\?(srt|vtt)(https?://.*)""")
        val subMatch = subRegex.find(rawUrl)
        if (subMatch != null) {
            videoUrl = rawUrl.substring(0, subMatch.range.first)
            subUrl = subMatch.groupValues[2]
            println("TVCHAK_DEBUG: [loadLinks] 자막 감지됨: $subUrl")
        }

        val type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

        println("TVCHAK_DEBUG: [loadLinks] 🎬 최종 비디오 링크 추출 성공: $videoUrl")

        callback.invoke(
            newExtractorLink(
                this.name,
                this.name,
                url = videoUrl,
                type = type
            ) {
                this.referer = mainUrl
                this.headers = getHeaders(mainUrl)
                this.quality = Qualities.P1080.value
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
            println("TVCHAK_DEBUG: [parseItem] 아이템 파싱 중 에러: ${e.message}")
            null
        }
    }
}
