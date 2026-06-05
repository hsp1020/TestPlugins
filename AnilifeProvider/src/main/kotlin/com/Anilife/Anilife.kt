package com.anilife

import android.annotation.SuppressLint // 누락된 수입(Import) 정상 추가
import android.util.Base64
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.CloudStreamApp // 전역 컨텍스트 액세스용 패키지 수입
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CancellationException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "AnilifeProvider"
    
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; SM-S901B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    /**
     * 쌩웹뷰를 백그라운드에 생성하여 페이지를 완벽 로드한 뒤, 
     * Cloudflare가 검증 완료한 최종 완성 HTML을 자바스크립트로 직접 안전하게 추출하는 로더
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun getDocumentWithRawWebView(url: String): Document {
        Log.d(TAG, "[RawWebView] === 쌩웹뷰 HTML 로딩 프로세스 시작 ===")
        Log.d(TAG, "[RawWebView] 대상 주소: $url")
        
        // CloudStreamApp 전역 Companion 컨텍스트를 안전하게 호출하여 참조 정합성 보장
        val ctx = CloudStreamApp.context ?: throw Exception("Android Context가 Null 상태입니다. 로더를 가동할 수 없습니다.")

        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine { continuation ->
                val webView = WebView(ctx)
                var isResumed = false

                // 리소스 누수 방지용 웹뷰 파괴 헬퍼
                fun cleanup() {
                    Handler(Looper.getMainLooper()).post {
                        try {
                            webView.stopLoading()
                            webView.destroy()
                            Log.d(TAG, "[RawWebView] 사용 완료된 쌩웹뷰 정상 파괴 완료")
                        } catch (e: Exception) {
                            Log.e(TAG, "[RawWebView] 웹뷰 파괴 중 오류 발생", e)
                        }
                    }
                }

                // 자바스크립트 통신 인터페이스 클래스 정의
                class HtmlExtractor {
                    @JavascriptInterface
                    fun processHTML(html: String) {
                        if (!isResumed) {
                            isResumed = true
                            Log.d(TAG, "[RawWebView] 자바스크립트로 HTML 수집 성공 (추출 크기: ${html.length}자)")
                            cleanup()
                            continuation.resume(Jsoup.parse(html, url))
                        }
                    }
                }

                webView.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    userAgentString = mobileUserAgent
                }

                webView.addJavascriptInterface(HtmlExtractor(), "HTMLOUT")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "[RawWebView] 웹뷰 로딩 완료됨 -> 수집 스크립트 실행")
                        // 렌더링이 완전히 끝난 돔 트리의 HTML 원본을 자바스크립트로 안전하게 낚아챕니다.
                        view?.loadUrl("javascript:window.HTMLOUT.processHTML(document.documentElement.outerHTML);")
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e(TAG, "[RawWebView] 에러 발생 코드: $errorCode, 내용: $description")
                    }
                }

                Log.d(TAG, "[RawWebView] 쌩웹뷰에 URL 주입 및 호출 시작")
                webView.loadUrl(url)

                // 타임아웃 백업 강제 탈출 구성 (30초 만료 시)
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isResumed) {
                        isResumed = true
                        cleanup()
                        continuation.resumeWith(Result.failure(Exception("[RawWebView] 쌩웹뷰 응답 대기시간 30초 초과")))
                    }
                }, 30000)

                continuation.invokeOnCancellation {
                    cleanup()
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d(TAG, "[getMainPage] =========================================")
        Log.d(TAG, "[getMainPage] 카테고리: ${request.name}, 페이지: $page")

        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        Log.d(TAG, "[getMainPage] 쌩웹뷰 대상 URL 생성 완료: $url")
        
        return try {
            val doc = getDocumentWithRawWebView(url)
            val items = parseCommonList(doc)
            Log.d(TAG, "[getMainPage] 최종 수집 성공 수량: ${items.size}개")
            
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[getMainPage] 페이지 최종 수집 실패 스택:\n${Log.getStackTraceString(e)}")
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        Log.d(TAG, "[parseCommonList] 파싱 가공 절차 개시")
        val elements = doc.select(".listupd > article.bs")
        Log.d(TAG, "[parseCommonList] 검색된 요소 (.listupd > article.bs) 개수: ${elements.size}개")
        
        val parsedList = elements.mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a")
                if (aTag == null) {
                    Log.w(TAG, "[parseCommonList] div.bsx > a 태그를 누락했습니다. HTML:\n${element.outerHtml()}")
                    return@mapNotNull null
                }
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
                
                Log.d(TAG, "[parseCommonList] 요소 획득 -> 제목: $title, 경로: $finalHref")
                
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = mapOf("User-Agent" to mobileUserAgent)
                }
            } catch (e: Exception) {
                Log.e(TAG, "[parseCommonList] 가공 도중 예외 발생", e)
                null
            }
        }
        return parsedList
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        Log.d(TAG, "[search] =========================================")
        Log.d(TAG, "[search] 검색 요청어: $query, 요청 URL: $url")
        return try {
            val doc = getDocumentWithRawWebView(url)
            val results = parseCommonList(doc)
            Log.d(TAG, "[search] 검색 매칭 수량: ${results.size}개")
            results
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[search] 검색 실패 트레이스:\n${Log.getStackTraceString(e)}")
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        Log.d(TAG, "[load] =========================================")
        Log.d(TAG, "[load] 진입 URL: $url")

        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            Log.d(TAG, "[load] 포스터 복원 성공: $tunnelingPoster")
            url.substringBefore("?poster=")
        } else url

        Log.d(TAG, "[load] 파싱 타겟 정제 URL: $cleanUrl")

        try {
            val doc = getDocumentWithRawWebView(cleanUrl)
            val finalUrl = doc.location()
            val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

            val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
            val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            val tags = doc.select(".genxed a").map { it.text() }
            Log.d(TAG, "[load] 상세 메타 가공 -> 타이틀: $title, 줄거리 글자수: ${plot?.length ?: 0}, 태그목록: $tags")

            var currentEpisodeNumber = 0
            val numRegex = Regex("[^0-9.]") 

            val elements = doc.select(".eplister > ul > li > a").reversed()
            Log.d(TAG, "[load] 에피소드 DOM 영역 획득 수량: ${elements.size}개")

            val episodes = elements.mapNotNull { element ->
                try {
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
                } catch (e: Exception) {
                    Log.e(TAG, "[load] 개별 에피소드 가공 예외 발생", e)
                    null
                }
            }

            return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
                this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
                this.posterHeaders = mapOf("User-Agent" to mobileUserAgent)
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[load] 상세 정보 획득 중단 오류:\n${Log.getStackTraceString(e)}")
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d(TAG, "[loadLinks] =========================================")
        Log.d(TAG, "[loadLinks] 링크 파라미터 수령: $data")

        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                Log.d(TAG, "[loadLinks] 복원된 연결 레퍼러: $detailReferer")
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) {
                Log.e(TAG, "[loadLinks] 레퍼러 디코딩 에러 발생", e)
            }
        }

        val videoIdMatch = Regex("""(?:id|provider)/([^/?]+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"
        Log.d(TAG, "[loadLinks] 정제 주소: $cleanData, 비디오 ID: $videoId")

        try {
            Log.d(TAG, "[loadLinks] 쌩웹뷰로 플레이어 프레임 페이지 로딩 요청 시작...")
            val doc = getDocumentWithRawWebView(cleanData)
            
            Log.d(TAG, "[loadLinks] 로드된 본문을 기준으로 프레임 소스 추출 진행...")
            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(doc.outerHtml(), mainUrl)
            Log.d(TAG, "[loadLinks] 최종 확정 플레이어 내부 타겟 주소: $playerUrl")

            if (playerUrl == null) {
                Log.w(TAG, "[loadLinks] 플레이어 주소 획득 실패")
                return false
            }

            Log.d(TAG, "[loadLinks] ProxyExtractor 연동 분석 프로세스 실행")
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
            Log.e(TAG, "[loadLinks] 링크 수집 실패 스택:\n${Log.getStackTraceString(e)}")
        }
        return false
    }
}
