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
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.io.ByteArrayInputStream
import java.util.concurrent.CancellationException
import android.os.Handler
import android.os.Looper

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "[Anilife]"
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    companion object {
        // [병렬 렉 차단] 웹뷰가 동시에 가동되어 메인 스레드를 정지시키는 현상 방지용 세마포어
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
     * [하이브리드 폴백 엔진]
     * 쿠키 브릿지를 타는 1차 OkHttp 시도를 우선 수행하고, 실패 시 2차 최적화 쌩웹뷰로 전환하는 우회 코어
     */
    private suspend fun getDocumentWithFallback(url: String, headers: Map<String, String> = commonHeaders): Document {
        Log.d(TAG, "[HybridEngine] 통신 요청 개시 -> $url")

        // 1. 웹뷰가 수집해서 안드로이드 OS 영역에 캐싱해 둔 쿠키가 존재하는지 탐색
        val cookieHeader = try {
            CookieManager.getInstance().getCookie("https://anilife.live")
        } catch (e: Exception) {
            Log.w(TAG, "[HybridEngine] 쿠키 매니저 조회 실패: ${e.message}")
            null
        }

        // 2. 쿠키가 존재한다면 우선적으로 가벼운 OkHttp 직접 통신 시도 (DoH/VPN 환경에서 동작 보장)
        if (!cookieHeader.isNullOrEmpty()) {
            try {
                Log.d(TAG, "[HybridEngine] [1차 시도] 기수확된 쿠키 브릿지 활용 OkHttp 직접 요청 개시")
                val mergedHeaders = headers.toMutableMap()
                mergedHeaders["Cookie"] = cookieHeader
                
                val response = app.get(url, headers = mergedHeaders)
                if (response.code == 200) {
                    Log.d(TAG, "[HybridEngine] [1차 시도 성공] OkHttp 쿠키 통신으로 즉시 완료!")
                    return response.document
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.w(TAG, "[HybridEngine] [1차 시도 실패] 연결 거부됨 (${e.message}). 즉시 2차 쌩웹뷰로 자동 폴백(우회)합니다.")
            }
        }

        // 3. 쿠키가 없거나 OkHttp 연결이 거부당한 경우, 세마포어 정렬을 거쳐 안전하게 최적화 쌩웹뷰 기동 [1.1, 1.3]
        Log.d(TAG, "[HybridEngine] [2차 시도] 세마포어 대기열 등록 (슬롯 잔여: ${webViewSemaphore.availablePermits})")
        return webViewSemaphore.withPermit {
            Log.d(TAG, "[HybridEngine] [2차 시도] 슬롯 확보 -> 초경량 쌩웹뷰 기동")
            getDoc(url, headers)
        }
    }

    /**
     * [최적화 쌩웹뷰 단일 로더]
     * 하드웨어 가속 강제 해제, 리소스 다운로드 차단, 사후 소멸 처리가 적용된 핵심 엔진
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getDoc(url: String, headers: Map<String, String> = commonHeaders): Document {
        Log.d(TAG, "[RawWebView] 웹뷰 기동 -> $url")
        val ctx = CloudStreamApp.context ?: throw Exception("Context가 Null입니다.")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(ctx)
                var isResumed = false

                fun cleanup() {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.clearHistory()
                            webView.loadUrl("about:blank")
                            webView.destroy()
                        } catch (e: Exception) { }
                    }
                }

                class HtmlExtractor {
                    @JavascriptInterface
                    fun processHTML(html: String, finalUrl: String) {
                        if (!isResumed) {
                            isResumed = true
                            cleanup()
                            // Jsoup.parse에 리다이렉트 완료 주소인 finalUrl을 바인딩하여 resumeWith 리턴 처리
                            continuation.resumeWith(Result.success(Jsoup.parse(html, finalUrl)))
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
                    loadsImagesAutomatically = false // 이미지 다운로드 차단 [1.1, 1.3]
                    mediaPlaybackRequiresUserGesture = true
                }

                webView.addJavascriptInterface(HtmlExtractor(), "HTMLOUT")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (isResumed) return
                        view?.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML, window.location.href);")
                    }

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

                // 동적 레퍼러 헤더와 유저에이전트를 탑재해 웹뷰 요청 실행 [1.1]
                webView.loadUrl(url, headers)

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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        return try {
            val doc = getDocumentWithFallback(url) // 가시성 및 렉 제어가 수립된 하이브리드 엔진 호출
            val items = parseCommonList(doc)
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

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
                    this.posterHeaders = commonHeaders // 포스터 403을 전면 회피하기 위해 정밀 레퍼러 헤더 탑재 [1.3]
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = getDocumentWithFallback("$mainUrl/search?keyword=$query")
            parseCommonList(doc)
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
            val finalUrl = doc.location()
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
                this.posterHeaders = commonHeaders
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
        Log.d(TAG, "[DEBUG-loadLinks] === loadLinks 수집 작동 ===")
        println("[DEBUG-loadLinks] 원본 data 인수 수령: $data")

        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                println("[DEBUG-loadLinks] 디코딩된 레퍼러 복원 성공: $detailReferer")
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) {
                println("[DEBUG-loadLinks] 레퍼러 복원 예외 발생: ${e.message}")
            }
        }

        val videoIdMatch = Regex("""(?:id|provider)/([^/?]+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"
        println("[DEBUG-loadLinks] 가공 데이터: $cleanData, 비디오 고유 ID: $videoId")

        try {
            val linkHeaders = commonHeaders.toMutableMap()
            linkHeaders["Referer"] = detailReferer

            println("[DEBUG-loadLinks] 하이브리드 엔진 가동 -> $cleanData")
            val doc = getDocumentWithFallback(cleanData, linkHeaders)
            val docLocation = doc.location()
            val docHtmlLen = doc.outerHtml().length
            println("[DEBUG-loadLinks] 호출 완수. 리다이렉트 도달 주소: $docLocation, 원본 HTML 크기: $docHtmlLen 자")

            // [이중 매칭 연동]: 웹뷰가 알아서 리다이렉트를 끝냈는지 수집하여 낚아챔
            val playerUrl = if (docLocation.contains("h/live") && docLocation.contains("p=")) {
                println("[DEBUG-loadLinks] [분기 1] 웹뷰가 이미 최종 플레이어 타겟 주소($docLocation)에 도착해있어 파싱 생략.")
                docLocation
            } else {
                val extracted = AnilifeProxyExtractor().extractPlayerUrl(doc.outerHtml(), mainUrl)
                println("[DEBUG-loadLinks] [분기 2] 플레이어 주소 정규식 추출 결과: $extracted")
                extracted
            }

            println("[DEBUG-loadLinks] 도출된 playerUrl: $playerUrl")
            if (playerUrl.isNullOrBlank()) {
                println("[DEBUG-loadLinks] 오류: 최종 획득한 playerUrl 주소가 비어있습니다. 종료 처리!")
                return false
            }

            println("[DEBUG-loadLinks] AnilifeProxyExtractor.extractWithProxy 우회 프록시 개시")
            val result = AnilifeProxyExtractor().extractWithProxy(
                m3u8Url = "",
                playerUrl = playerUrl,
                referer = "https://anilife.live/",
                ssid = null,
                cookies = "",
                targetKeyUrl = null,
                videoId = videoId,
                callback = callback
            )
            println("[DEBUG-loadLinks] extractWithProxy 우회 최종 반환결과: $result")
            return result

        } catch (e: Exception) {
            if (e is CancellationException) {
                println("[DEBUG-loadLinks] 코루틴 내부 취소 감지")
                throw e
            }
            println("[DEBUG-loadLinks] 치명적 예외 발생 스택 추적:")
            e.printStackTrace()
        }
        return false
    }
}
