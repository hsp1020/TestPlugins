package com.kktv

import android.content.Context
import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import com.fasterxml.jackson.module.kotlin.readValue

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "CUTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private var cfCookies: String? = null
    private var cfUserAgent: String? = null

    // SharedPreferences에 저장된 수동 우회 쿠키 가져오기
    private fun getSharedPrefs(): android.content.SharedPreferences? {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) {
            null
        }
        return context?.getSharedPreferences("CUTV_PREFS", Context.MODE_PRIVATE)
    }

    private fun loadSavedCookies() {
        val prefs = getSharedPrefs() ?: return
        val savedCookies = prefs.getString("cf_cookies", null)
        val savedUA = prefs.getString("cf_user_agent", null)
        if (!savedCookies.isNullOrBlank()) {
            cfCookies = savedCookies
            cfUserAgent = savedUA ?: userAgent
            println("[CUTV_DEBUG] SharedPreferences에서 우회 쿠키 로딩 성공: $cfCookies")
        }
    }

    private fun showToastOnMain(message: String) {
        try {
            Handler(Looper.getMainLooper()).post {
                com.lagradost.cloudstream3.CommonActivity.showToast(message)
            }
        } catch (e: Exception) {
            println("[CUTV_DEBUG] Toast 출력 실패: ${e.message}")
        }
    }

    private fun getHeaders(referer: String? = null): Map<String, String> {
        val currentUA = cfUserAgent ?: userAgent
        val headers = mutableMapOf(
            "User-Agent" to currentUA,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Upgrade-Insecure-Requests" to "1"
        )
        cfCookies?.let {
            headers["Cookie"] = it
        }
        referer?.let {
            headers["Referer"] = it
        }
        return headers
    }

    private fun getPosterHeaders(posterUrl: String, referer: String): Map<String, String> {
        val currentUA = cfUserAgent ?: userAgent
        val headers = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to currentUA,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Sec-Fetch-Dest" to "image",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site"
        )
        if (posterUrl.contains("cu-tv.com")) {
            cfCookies?.let {
                headers["Cookie"] = it
            }
        }
        return headers
    }

    private val defaultHeaders: Map<String, String>
        get() = getHeaders()

    private fun isCloudflareChallenge(html: String): Boolean {
        val lowerHtml = html.lowercase()
        return (lowerHtml.contains("window._cf_chl_opt") || 
                lowerHtml.contains("cf-challenge-running") || 
                lowerHtml.contains("id=\"challenge-form\"") ||
                lowerHtml.contains("id=\"cf-challenge-form\"") ||
                lowerHtml.contains("just a moment...")) && 
               lowerHtml.contains("cloudflare")
    }

    private fun extractDomainFromStreamUrl(url: String): String? {
        var cleanUrl = url
        if (cleanUrl.startsWith("EMBED#")) {
            cleanUrl = cleanUrl.removePrefix("EMBED#")
        }
        if (cleanUrl.contains("liveUrl=")) {
            try {
                cleanUrl.substringAfter("liveUrl=").substringBefore("&").let {
                    cleanUrl = URLDecoder.decode(it, "UTF-8")
                }
            } catch (e: Exception) {}
        }
        return try {
            val uri = Uri.parse(cleanUrl)
            uri.host
        } catch (e: Exception) {
            null
        }
    }

    // 자동 우회 로직 (폴백용으로 보존)
    private suspend fun solveCloudflare(url: String): Pair<String, String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) {
            null
        }

        if (context == null) {
            println("[CUTV_DEBUG] [Cloudflare Bypass] ERROR: Context를 획득하지 못했습니다.")
            return@withContext null
        }

        println("[CUTV_DEBUG] [Cloudflare Bypass] 진입 -> URL: $url")

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (e: Exception) {}

        suspendCancellableCoroutine<Pair<String, String>?> { continuation ->
            val webView = WebView(context)
            
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            webView.settings.userAgentString = userAgent

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

                    if (cookies.contains("cf_clearance")) {
                        solved = true
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
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.isNotBlank() && cookies.contains("cf_clearance")) {
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
                handler.removeCallbacks(timeoutRunnable)
                handler.removeCallbacks(intervalRunnable)
                try {
                    webView.stopLoading()
                    webView.destroy()
                } catch (e: Exception) {}
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            // 1. SharedPreferences에 저장된 수동 우회 쿠키 우선 적재
            loadSavedCookies()

            // 2. 수동 저장 쿠키가 없을 때만 자동 우회 시도
            if (cfCookies == null) {
                println("[CUTV_DEBUG] [Cloudflare Bypass] 시작: 수동 쿠키가 없으므로 자동 우회 절차를 시도합니다.")
                val cfResult = solveCloudflare(mainUrl)
                if (cfResult != null) {
                    cfCookies = cfResult.first
                    cfUserAgent = cfResult.second
                }
            }

            var doc = app.get(url, headers = defaultHeaders).document

            // 3. 만약 챌린지가 다시 감지되면 수동 우회 쿠키 리로드 진행 및 안내 문구 활성화
            if (isCloudflareChallenge(doc.html())) {
                println("[CUTV_DEBUG] [Cloudflare Bypass] Cloudflare 챌린지 감지됨.")
                cfCookies = null
                cfUserAgent = null
                
                loadSavedCookies() // SharedPreferences 확인
                
                if (cfCookies == null) {
                    // 수동 쿠키도 없으면 마지막으로 자동 우회 재시도
                    val cfResult = solveCloudflare(mainUrl)
                    if (cfResult != null) {
                        cfCookies = cfResult.first
                        cfUserAgent = cfResult.second
                        doc = app.get(url, headers = defaultHeaders).document
                    } else {
                        showToastOnMain("안전한 접근을 위해 플러그인 설정 화면에서 수동 우회(체크박스 확인)를 진행해 주세요.")
                        return newHomePageResponse(request.name, emptyList())
                    }
                } else {
                    doc = app.get(url, headers = defaultHeaders).document
                }
            }

            println("[CUTV_DEBUG] === START CUTV 팩트 체크 디버깅 ===")
            println("[CUTV_DEBUG] 접속 메인 URL: $url")
            println("[CUTV_DEBUG] 메인 HTML 크기: ${doc.html().length}자")

            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli") }

            if (iframeSrc.isNullOrBlank()) {
                println("[CUTV_DEBUG] FAIL: iframeSrc를 찾을 수 없습니다.")
                return newHomePageResponse(request.name, emptyList())
            }

            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            
            // 썸네일 베이스 URL 동적 추출
            var dynamicThumbBase = "" 
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = mapper.readValue<List<Map<String, String>>>(jsonData)
                
                val firstOncahotUrl = matches.asSequence()
                    .map { item -> listOfNotNull(item["surl"], item["surl2"], item["surl3"]) }
                    .flatten()
                    .firstOrNull { it.isNotBlank() && it.contains("oncahot.com", ignoreCase = true) }

                if (firstOncahotUrl != null) {
                    val extractedHost = extractDomainFromStreamUrl(firstOncahotUrl)
                    if (!extractedHost.isNullOrBlank()) {
                        dynamicThumbBase = "https://$extractedHost/thumbs"
                    }
                }

                if (dynamicThumbBase.isBlank()) {
                    val hostRegex = """([a-zA-Z0-9.-]*oncahot\.com)""".toRegex(RegexOption.IGNORE_CASE)
                    val hostMatch = hostRegex.find(iframeHtml)
                    if (hostMatch != null) {
                        val cleanDomain = hostMatch.groupValues[1]
                        dynamicThumbBase = "https://$cleanDomain/thumbs"
                    }
                }

                if (dynamicThumbBase.isBlank()) {
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val scriptSrc = iframeDoc.select("script").map { it.attr("src") }
                        .firstOrNull { it.contains("script.bootstrap.js") }

                    if (!scriptSrc.isNullOrBlank()) {
                        val iframeUri = Uri.parse(iframeSrc)
                        val scriptUrl = if (scriptSrc.startsWith("http")) {
                            scriptSrc
                        } else {
                            val cleanPath = scriptSrc.replace("../", "")
                            "${iframeUri.scheme}://${iframeUri.host}/$cleanPath"
                        }
                        
                        try {
                            val scriptHtml = app.get(scriptUrl, headers = defaultHeaders, referer = iframeSrc).text
                            val fallbackRegex = """(https?(?::|\\:)(?:/|\\/)+[a-zA-Z0-9.-]+(?:/|\\/)+thumbs)""".toRegex(RegexOption.IGNORE_CASE)
                            val scriptMatch = fallbackRegex.find(scriptHtml)
                            if (scriptMatch != null) {
                                dynamicThumbBase = scriptMatch.groupValues[1].replace("\\", "")
                            }
                        } catch (e: Exception) {}
                    }
                }

                if (dynamicThumbBase.isBlank()) {
                    val firstUrl = matches.asSequence()
                        .map { item -> listOfNotNull(item["surl"], item["surl2"], item["surl3"]) }
                        .flatten()
                        .firstOrNull { it.isNotBlank() }

                    if (firstUrl != null) {
                        val extractedHost = extractDomainFromStreamUrl(firstUrl)
                        if (!extractedHost.isNullOrBlank()) {
                            dynamicThumbBase = "https://$extractedHost/thumbs"
                        }
                    }
                }
            }

            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = mapper.readValue<List<Map<String, String>>>(jsonData)
                
                matches.forEach { matchItem ->
                    val ssid = matchItem["ssid"] ?: ""
                    val name = matchItem["name"] ?: ""
                    val surl1 = matchItem["surl"] ?: ""
                    val surl2 = matchItem["surl2"] ?: ""
                    val surl3 = matchItem["surl3"] ?: ""
                    
                    if (ssid.isNotBlank() && name.isNotBlank() && (surl1.isNotBlank() || surl2.isNotBlank() || surl3.isNotBlank())) {
                        val title = name 
                        val srcy = matchItem["srcy"] ?: ""
                        val thumbId = if (srcy.isNotBlank()) srcy else ssid
                        val timestamp = System.currentTimeMillis()
                        val posterUrl = if (dynamicThumbBase.isNotBlank()) "$dynamicThumbBase/$thumbId.jpg?a=$timestamp" else ""
                        
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrl, "UTF-8")

                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe&poster=$encodedPoster"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                                this.posterHeaders = getPosterHeaders(posterUrl, iframeSrc)
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol13.galaxy-stream.live/cli/box"

        val rawPoster = uri.getQueryParameter("poster")
        val posterUrl = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else ""

        loadSavedCookies()

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.plot = "실시간 스포츠 중계"
            this.posterHeaders = getPosterHeaders(posterUrl, iframeUrl)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val ssid = data.substringBefore("?").substringAfterLast("/")
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        try {
            loadSavedCookies()
            val iframeHtml = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = mapper.readValue<List<Map<String, String>>>(jsonData)
                val targetMatch = matches.find { it["ssid"] == ssid }
                
                if (targetMatch != null) {
                    val urls = listOfNotNull(targetMatch["surl"], targetMatch["surl2"], targetMatch["surl3"])
                        .filter { it.isNotBlank() }
                        .distinct()

                    urls.forEachIndexed { index, link ->
                        var actualUrl = link.replace("\\/", "/") 
                        var targetReferer = iframeUrl
                        var targetOrigin = "https://${Uri.parse(iframeUrl).host}"
                        
                        if (actualUrl.startsWith("EMBED#")) {
                            actualUrl = actualUrl.removePrefix("EMBED#")
                        }
                        
                        if (actualUrl.contains("liveUrl=")) {
                            targetReferer = actualUrl 
                            try {
                                targetOrigin = "https://${Uri.parse(actualUrl).host}" 
                                Uri.parse(actualUrl).getQueryParameter("liveUrl")?.let { extractedUrl ->
                                    actualUrl = extractedUrl 
                                }
                            } catch (e: Exception) {}
                        }

                        if (actualUrl.contains("hudtv", ignoreCase = true)) {
                            val hudtvRegex = """hudtv[a-zA-Z0-9]*\.[a-zA-Z]{2,4}""".toRegex(RegexOption.IGNORE_CASE)
                            val replacedUrl = actualUrl.replace(hudtvRegex, "oncahot.com")
                            actualUrl = replacedUrl
                        }

                        val qualityName = if (link.contains("coupangstreaming")) "채널 1" 
                                          else if (link.contains("hudtv")) "채널 2" 
                                          else "채널 ${index + 1}"

                        val isM3u8 = actualUrl.contains(".m3u8", ignoreCase = true)
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = actualUrl,
                                type = linkType
                            ) {
                                this.referer = targetReferer
                                this.headers = mapOf(
                                    "Origin" to targetOrigin,
                                    "Accept" to "*/*",
                                    "Sec-Fetch-Dest" to "empty",
                                    "Sec-Fetch-Mode" to "cors",
                                    "Sec-Fetch-Site" to "cross-site",
                                    "User-Agent" to (cfUserAgent ?: userAgent)
                                )
                            }
                        )
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }        
        return true
    }
}
