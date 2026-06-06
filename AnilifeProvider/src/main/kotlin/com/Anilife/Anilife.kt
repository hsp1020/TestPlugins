package com.anilife

import android.annotation.SuppressLint
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import android.webkit.CookieManager // 쿠키 제어용 패키지 수입
import android.net.http.SslError
import android.webkit.SslErrorHandler
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.CloudStreamApp
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore // 코루틴 세마포어 수입
import kotlinx.coroutines.sync.withPermit
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"
    // 구버전 고유 UA 사용
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    // 이미지 403 차단 및 Cloudflare Hotlink 방지를 원천 우회하는 레퍼러 결합 헤더 [1.1, 1.3]
    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    companion object {
        // [병렬 렉 차단] 메인 페이지 최초 진입 시 동시에 여러 웹뷰가 켜져 메인 스레드가 버벅이는 현상을 방지
        private val webViewSemaphore = Semaphore(2)
    }

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    /**
     * [하이브리드 통신 브릿지]
     * 쿠키가 있고 DNS가 우회된 기기에서는 1차 OkHttp 초고속 통신 수행,
     * 그렇지 않고 Handshake가 차단당한 기기에서는 즉각 가벼운 쌩웹뷰로 자동 전향(Fallback)하는 고성능 엔진
     */
    private suspend fun getDocumentWithFallback(url: String): Document {
        Log.d(TAG, "[HybridEngine] 통신 요청 개시 -> $url")

        // 1. 웹뷰가 구해서 캐싱해 둔 cf_clearance 세션 쿠키 추출
        val cookieHeader = try {
            CookieManager.getInstance().getCookie("https://anilife.live")
        } catch (e: Exception) {
            Log.w(TAG, "[HybridEngine] 쿠키 매니저 조회 실패: ${e.message}")
            null
        }

        // 2. 쿠키가 존재한다면 우선적으로 아주 가벼운 순정 OkHttp 연결 시도
        if (!cookieHeader.isNullOrEmpty()) {
            try {
                Log.d(TAG, "[HybridEngine] [1차 시도] 기수확된 쿠키 브릿지 활용 OkHttp 직접 요청 개시")
                val response = app.get(
                    url,
                    headers = mapOf(
                        "User-Agent" to pcUserAgent,
                        "Cookie" to cookieHeader,
                        "Referer" to "https://anilife.live/"
                    )
                )
                if (response.code == 200) {
                    Log.d(TAG, "[HybridEngine] [1차 시도 성공] OkHttp 쿠키 통신으로 즉시 완료!")
                    return response.document
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "[HybridEngine] [1차 시도 실패] 연결 거부됨 (${e.message}). 즉시 쌩웹뷰 가동 준비")
            }
        }

        // 3. 쿠키가 없거나 OkHttp가 취소/차단된 경우 세마포어 대기열을 타고 정교하게 쌩웹뷰 기동 [1.1, 1.3]
        Log.d(TAG, "[HybridEngine] [2차 시도] 세마포어 가동 (대기열 잔여 슬롯: ${webViewSemaphore.availablePermits})")
        return webViewSemaphore.withPermit {
            Log.d(TAG, "[HybridEngine] [2차 시도] 슬롯 확보 -> 초경량 쌩웹뷰 기동")
            getDoc(url)
        }
    }

    /**
     * [극효율 최적화 쌩웹뷰 단일 로더]
     * 하드웨어 가속 강제 해제, 리소스 다운로드 차단, 사후 소멸 처리가 적용된 핵심 엔진
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getDoc(url: String): Document {
        Log.d(TAG, "[RawWebView] 웹뷰 기동 -> $url")
        val ctx = CloudStreamApp.context ?: throw Exception("Context가 Null입니다.")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(ctx)
                var isResumed = false

                // 렉, 발열, 메모리 누수를 완전히 방지하는 웹뷰 강제 소멸자 함수
                fun cleanup() {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading() // 네트워크 세션 즉시 타절 [1.1, 1.3]
                            webView.clearHistory()
                            webView.loadUrl("about:blank") // 백그라운드 돌발 루프/타이머 강제 사살
                            webView.destroy() // 메모리 OS 즉각 반환 [1.1, 1.3]
                            Log.d(TAG, "[RawWebView] 사용 완료된 웹뷰 자원 반환 완료")
                        } catch (e: Exception) { }
                    }
                }

                class HtmlExtractor {
                    @JavascriptInterface
                    fun processHTML(html: String) {
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resume(Jsoup.parse(html, url))
                        }
                    }
                }

                // [렌더링 최적화] GPU 가속을 완전히 해제하여 CPU/GPU 가용 자원 절약 [1.1, 1.3]
                webView.setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = pcUserAgent
                    loadsImagesAutomatically = false // [대역폭 최적화] 무단 이미지 로딩 자동 거부 [1.1, 1.3]
                    mediaPlaybackRequiresUserGesture = true
                }

                webView.addJavascriptInterface(HtmlExtractor(), "HTMLOUT")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isResumed) return
                        view?.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML);")
                    }

                    // [데이터 다이어트 최적화] 파싱에 필요없는 미디어, 이미지, 폰트, CSS 수신 즉시 폐기 [1.1, 1.2, 1.3]
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val resourceUrl = request?.url?.toString()?.lowercase() ?: return null
                        val isHeavy = resourceUrl.endsWith(".png") || resourceUrl.endsWith(".jpg") ||
                                      resourceUrl.endsWith(".jpeg") || resourceUrl.endsWith(".webp") ||
                                      resourceUrl.endsWith(".gif") || resourceUrl.endsWith(".svg") ||
                                      resourceUrl.endsWith(".woff") || resourceUrl.endsWith(".woff2") ||
                                      resourceUrl.endsWith(".ttf") || resourceUrl.endsWith(".css") ||
                                      resourceUrl.endsWith(".mp4") || resourceUrl.contains("analytics") ||
                                      resourceUrl.contains("google")

                        if (isHeavy) {
                            // 빈 가상 응답 객체를 반환하여 실제 통신 전송 및 다운로드를 취소 [1.1]
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        if (failingUrl == url && !isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("접속 실패: $description ($errorCode)")))
                        }
                    }

                    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                        if (request?.isForMainFrame == true && !isResumed) {
                            val errorCode = error?.errorCode ?: -1
                            val description = error?.description?.toString() ?: "Unknown"
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("접속 실패: $description ($errorCode)")))
                        }
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        handler?.cancel()
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            continuation.resumeWith(Result.failure(Exception("SSL 핸드셰이크 실패: ${error?.toString()}")))
                        }
                    }
                }

                webView.loadUrl(url)

                // 30초 대기 안전 강제 탈출 장치
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        continuation.resumeWith(Result.failure(Exception("응답 시간 초과 (30초)")))
                    }
                }, 30000)

                continuation.invokeOnCancellation {
                    cleanup()
                }
            }
        }
    }

    // 구버전 고유 파싱 로직 및 호출 방식 완벽 유지
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        return try {
            val doc = getDocumentWithFallback(url) // 1차 브릿지 시도 및 2차 웹뷰 하이브리드 엔진 주입
            val items = parseCommonList(doc)
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    // 구버전 가공 로직 100% 동일하게 유지
    private fun parseCommonList(doc: Document): List<SearchResponse> {
        return doc.select(".listupd > article.bs").mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)
                
                if (poster.isEmpty()) {
                    poster = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
                
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref
                
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders // 레퍼러가 보완된 공용 헤더로 이미지 403을 전면 차단 [1.1, 1.3]
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            parseCommonList(getDocumentWithFallback("$mainUrl/search?keyword=$query"))
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        try {
            val doc = getDocumentWithFallback(cleanUrl)
            val finalUrl = doc.location() // 리다이렉트가 끝난 실제 HTML 최종 주소 획득
            val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

            val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
            val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            val tags = doc.select(".genxed a").map { it.text() }

            var currentEpisodeNumber = 0
            val numRegex = Regex("[^0-9.]") 

            val elements = doc.select(".eplister > ul > li > a").reversed()
            val episodes = elements.mapNotNull { element ->
                val rawHref = fixUrl(element.attr("href"))
                val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
                val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
                
                val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
                val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"
                
                val parsedNum = numText.replace(numRegex, "").toFloatOrNull()?.toInt() ?: 0

                if (currentEpisodeNumber == 0) {
                    currentEpisodeNumber = if (parsedNum > 0) parsedNum else 1
                } else {
                    if (parsedNum > currentEpisodeNumber) {
                        currentEpisodeNumber = parsedNum
                    } else {
                        currentEpisodeNumber++
                    }
                }

                newEpisode(finalHref) {
                    this.name = fullName
                    this.episode = currentEpisodeNumber
                }
            }

            return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
                this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
                this.posterHeaders = commonHeaders // 상세 화면 내의 포스터 403 차단 전면 해결 [1.3]
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { }
        }

        val videoIdMatch = Regex("""(?:id|provider)/([^/?]+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"

        try {
            val doc = getDocumentWithFallback(cleanData)

            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(doc.outerHtml(), mainUrl) ?: return false

            return AnilifeProxyExtractor().extractWithProxy(
                m3u8Url = "",
                playerUrl = playerUrl,
                referer = "https://anilife.live/",
                ssid = null,
                cookies = "",
                targetKeyUrl = null,
                videoId = videoId,
                callback = callback
            )

        } catch (e: Exception) {
            if (e is CancellationException) throw e
        }
        return false
    }
}
