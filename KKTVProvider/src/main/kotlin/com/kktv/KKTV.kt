package com.kktv

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URLDecoder
import kotlin.coroutines.resume

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    private var cachedAuToken: String? = null

    private fun getNoMatchesResponse(requestName: String, msg: String): HomePageResponse {
        println("[KKTV DEBUG] ❌ 에러/빈화면 반환 사유: $msg")
        val errorItem = newLiveSearchResponse(name = msg, url = mainUrl) {
            // 🚨 하드코딩 로고 삭제, 무조건 빈 값
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    private suspend fun getDynamicTokenViaWebView(url: String): String? = withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
        } catch (e: Exception) { null }

        if (context == null) return@withContext null

        suspendCancellableCoroutine<String?> { continuation ->
            var isResumed = false
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = userAgent

            webView.webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    val msg = consoleMessage?.message() ?: ""
                    if (msg.startsWith("KKTV_TOKEN:")) {
                        val token = msg.substringAfter("KKTV_TOKEN:")
                        if (!isResumed) {
                            println("[KKTV DEBUG] 🔓 API 토큰 스틸 성공! 길이: ${token.length}")
                            isResumed = true
                            continuation.resume(token)
                            try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                        }
                    }
                    return super.onConsoleMessage(consoleMessage)
                }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val jsHook = """
                        javascript:(function() {
                            var originalSend = XMLHttpRequest.prototype.send;
                            XMLHttpRequest.prototype.send = function(data) {
                                if (data && typeof data === 'string') {
                                    var match = data.match(/auToken=([^&]+)/);
                                    if(match && match[1]) {
                                        console.log('KKTV_TOKEN:' + match[1]);
                                    }
                                }
                                return originalSend.apply(this, arguments);
                            };
                            if (typeof loadLivescoreData === 'function') {
                                loadLivescoreData();
                            }
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(jsHook, null)
                }
            }

            webView.loadUrl(url)

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!isResumed) {
                    isResumed = true
                    continuation.resume(null)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                }
            }
            handler.postDelayed(timeoutRunnable, 10000)

            continuation.invokeOnCancellation {
                if (!isResumed) {
                    isResumed = true
                    handler.removeCallbacks(timeoutRunnable)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                }
            }
        }
    }

    private suspend fun getM3u8ViaWebView(seq: String): String? = withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as? android.content.Context
        } catch (e: Exception) { null }

        if (context == null) return@withContext null

        suspendCancellableCoroutine<String?> { continuation ->
            var isResumed = false
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.mediaPlaybackRequiresUserGesture = false 
            webView.settings.userAgentString = userAgent

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    val jsClicker = """
                        javascript:(function() {
                            var attempts = 0;
                            var checkInterval = setInterval(function() {
                                attempts++;
                                var btn = document.querySelector('.livescore_$seq .viewvideo_btn');
                                if (btn) {
                                    clearInterval(checkInterval);
                                    btn.click();
                                } else if (attempts > 30) {
                                    clearInterval(checkInterval);
                                }
                            }, 500);
                        })();
                    """.trimIndent()
                    view?.evaluateJavascript(jsClicker, null)
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    if (reqUrl.contains(".m3u8")) {
                        if (!isResumed) {
                            isResumed = true
                            println("[KKTV DEBUG] 🎥 동적 m3u8 스니핑 성공!: $reqUrl")
                            continuation.resume(reqUrl)
                            Handler(Looper.getMainLooper()).post {
                                try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }

            println("[KKTV DEBUG] 🎬 $seq 경기의 m3u8 스트림 추출 시작...")
            webView.loadUrl("https://fkwdsvop.com/KingKong/livescore/livescore/")

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!isResumed) {
                    isResumed = true
                    println("[KKTV DEBUG] ⏱️ m3u8 추출 타임아웃 (15초)")
                    continuation.resume(null)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                }
            }
            handler.postDelayed(timeoutRunnable, 15000)

            continuation.invokeOnCancellation {
                if (!isResumed) {
                    isResumed = true
                    handler.removeCallbacks(timeoutRunnable)
                    Handler(Looper.getMainLooper()).post {
                        try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                    }
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val iframeUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"
        val apiUrl = "https://fkwdsvop.com/ajax/getLivescoreListV3"

        try {
            val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            val targetIdRegex = Regex("""targetId\s*=\s*['"]?(\d+)['"]?""")
            val dynamicTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1) ?: "469"

            if (cachedAuToken == null) {
                cachedAuToken = getDynamicTokenViaWebView(iframeUrl)
            }

            val currentToken = cachedAuToken
            if (currentToken.isNullOrBlank()) {
                return getNoMatchesResponse(request.name, "동적 토큰 추출 실패 (WebView 타임아웃)")
            }

            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://fkwdsvop.com",
                "Referer" to iframeUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent
            )

            var response = app.post(apiUrl, headers = postHeaders, data = mapOf(
                "sportsType" to "all", "status" to "", "c_targetId" to dynamicTargetId, "auToken" to currentToken
            ))

            if (response.code != 200) {
                val freshToken = getDynamicTokenViaWebView(iframeUrl)
                if (freshToken != null) {
                    cachedAuToken = freshToken
                    response = app.post(apiUrl, headers = postHeaders, data = mapOf(
                        "sportsType" to "all", "status" to "", "c_targetId" to dynamicTargetId, "auToken" to freshToken
                    ))
                } else {
                    return getNoMatchesResponse(request.name, "토큰 재발급 실패")
                }
            }

            if (response.code != 200) return getNoMatchesResponse(request.name, "API 통신 에러 (HTTP ${response.code})")

            val responseText = response.text
            if (!responseText.trim().startsWith("{") && !responseText.trim().startsWith("[")) {
                return getNoMatchesResponse(request.name, "잘못된 응답 데이터 (JSON이 아님)")
            }

            val jsonTree = mapper.readTree(responseText)
            val dataArray = jsonTree.get("data")

            if (dataArray != null && dataArray.isArray) {
                val matchList = dataArray.toList()
                
                val importantMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color != null && color != "N" && color.isNotBlank()
                }
                val normalMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color == null || color == "N" || color.isBlank()
                }
                
                val sortedMatches = importantMatches + normalMatches
                
                sortedMatches.forEach { match ->
                    val states = match.get("states")?.asText() ?: ""
                    
                    if (states == "ING") {
                        val rawTitle = match.get("title")?.asText() ?: ""
                        val cleanTitle = Jsoup.parse(rawTitle).text().ifEmpty {
                            val home = match.get("home_name")?.asText() ?: "홈"
                            val away = match.get("away_name")?.asText() ?: "원정"
                            "$home vs $away"
                        }
                        
                        val league = match.get("leagueName")?.asText() ?: "스포츠"
                        val displayTitle = "[$league] $cleanTitle"

                        val seq = match.get("seq")?.asText() ?: ""
                        val thumbnail = match.get("thumbnail")?.asText() ?: ""
                        
                        // 🚨 하드코딩 로고 삭제, 무조건 빈 값
                        val posterUrlFromJson = if (thumbnail.isNotBlank() && thumbnail.startsWith("//")) {
                            "https:$thumbnail"
                        } else {
                            ""
                        }

                        val encodedTitle = URLEncoder.encode(displayTitle, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrlFromJson, "UTF-8")
                        // 영상 고유 seq를 넘겨서 동적으로 m3u8을 가져옵니다.
                        val fakeUrl = "kktv_seq://$seq&kktv_title=$encodedTitle&kktv_poster=$encodedPoster"

                        items.add(
                            newLiveSearchResponse(name = displayTitle, url = fakeUrl) {
                                this.posterUrl = posterUrlFromJson
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return getNoMatchesResponse(request.name, "파싱 중 치명적 오류 발생")
        }

        if (items.isEmpty()) return getNoMatchesResponse(request.name, "현재 진행중인 중계가 없습니다")

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest("live_matches", "main", false))
        val allMatches = homeResponse.items.flatMap { it.list }
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val titleParam = Regex("""&kktv_title=([^&]+)""").find(url)?.groupValues?.get(1)
        val posterParam = Regex("""&kktv_poster=([^&]+)""").find(url)?.groupValues?.get(1)

        val title = if (titleParam != null) URLDecoder.decode(titleParam, "UTF-8") else "스포츠 실시간 중계"
        // 🚨 하드코딩 로고 삭제, 무조건 빈 값
        val poster = if (posterParam != null) URLDecoder.decode(posterParam, "UTF-8") else ""

        // 찌꺼기를 자르고 "kktv_seq://고유번호" 형태로 만듦
        val cleanUrl = url.substringBefore("&kktv_title=")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = cleanUrl
        ) {
            this.posterUrl = poster
            this.plot = title
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // "kktv_seq://48214" 에서 48214만 빼냄
            val seq = data.substringAfter("kktv_seq://")
            
            // WebView를 열어서 m3u8을 실시간으로 낚아챔 (kcdn/wcdn/scdn 구별 없이 실제 주소 획득)
            val matchUrl = getM3u8ViaWebView(seq)

            if (matchUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live HD",
                        url = matchUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://fkwdsvop.com/"
                        this.headers = mapOf("User-Agent" to userAgent)
                    }
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
