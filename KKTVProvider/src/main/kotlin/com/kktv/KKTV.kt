package com.kktv

import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
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
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    private suspend fun getDynamicTokenViaWebView(url: String): String? = withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) { null }

        if (context == null) {
            println("[KKTV DEBUG] ❌ Android Context를 가져올 수 없습니다.")
            return@withContext null
        }

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
                            println("[KKTV DEBUG] 🔓 WebView에서 동적 토큰 스틸 성공! 길이: ${token.length}")
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

            println("[KKTV DEBUG] 🔍 WebView 로드 시작: $url")
            webView.loadUrl(url)

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (!isResumed) {
                    println("[KKTV DEBUG] ⏱️ WebView 토큰 추출 타임아웃 (10초)")
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

            // 🚨 하드코딩 찌꺼기 완벽 제거! 토큰 못 훔쳐왔으면 강제 종료.
            val currentToken = cachedAuToken
            if (currentToken.isNullOrBlank()) {
                println("[KKTV DEBUG] ❌ 하드코딩 없음. 동적 토큰을 추출하지 못해 파싱을 중단합니다.")
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

            // 🚨 토큰이 만료되어 거부당했다면 즉시 새로 뽑아옴 (하드코딩 X)
            if (response.code != 200) {
                println("[KKTV DEBUG] ⚠️ 토큰 만료 감지됨 (HTTP ${response.code}). WebView로 토큰 100% 재생성 시도!")
                val freshToken = getDynamicTokenViaWebView(iframeUrl)
                if (freshToken != null) {
                    cachedAuToken = freshToken
                    println("[KKTV DEBUG] 🔄 신규 토큰으로 API 재요청...")
                    response = app.post(apiUrl, headers = postHeaders, data = mapOf(
                        "sportsType" to "all", "status" to "", "c_targetId" to dynamicTargetId, "auToken" to freshToken
                    ))
                } else {
                    return getNoMatchesResponse(request.name, "토큰 재발급 실패")
                }
            }

            if (response.code != 200) {
                return getNoMatchesResponse(request.name, "API 통신 에러 (HTTP ${response.code})")
            }

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
                        
                        val posterUrlFromJson = if (thumbnail.isNotBlank() && thumbnail.startsWith("//")) {
                            "https:$thumbnail"
                        } else {
                            "$mainUrl/assets/Pc/img/hd_logo.png"
                        }

                        val playerUrl = "$iframeUrl?id=$seq"

                        val encodedTitle = URLEncoder.encode(displayTitle, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrlFromJson, "UTF-8")
                        val fakeUrl = "$playerUrl&kktv_title=$encodedTitle&kktv_poster=$encodedPoster"

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

        if (items.isEmpty()) {
            return getNoMatchesResponse(request.name, "현재 진행중인 중계가 없습니다")
        }

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
        val poster = if (posterParam != null) URLDecoder.decode(posterParam, "UTF-8") else "$mainUrl/assets/Pc/img/hd_logo.png"

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
            val playerHtml = app.get(data, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            val m3u8Regex = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex()
            var matchUrl = m3u8Regex.find(playerHtml)?.value

            if (matchUrl == null) {
                val iframeSrc = Jsoup.parse(playerHtml).select("iframe").attr("src")
                if (iframeSrc.isNotBlank()) {
                    val iframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https://fkwdsvop.com$iframeSrc"
                    val iframeHtml2 = app.get(iframeUrl, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).text
                    matchUrl = m3u8Regex.find(iframeHtml2)?.value
                }
            }

            if (matchUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live HD",
                        url = matchUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
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
