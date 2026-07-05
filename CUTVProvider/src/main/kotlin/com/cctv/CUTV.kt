package com.cutv

import android.content.SharedPreferences
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

class CUTVProvider(private val sharedPref: SharedPreferences) : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "CUTV"
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private var cfCookies: String? = null
    private var cfUserAgent: String? = null

    private fun loadSavedCookies() {
        val savedCookies = sharedPref.getString("cf_cookies", null)
        val savedUA = sharedPref.getString("cf_user_agent", null)
        if (!savedCookies.isNullOrBlank()) {
            cfCookies = savedCookies
            cfUserAgent = savedUA ?: userAgent
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
        cfCookies?.let { headers["Cookie"] = it }
        referer?.let { headers["Referer"] = it }
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
            cfCookies?.let { headers["Cookie"] = it }
        }
        return headers
    }

    private val defaultHeaders: Map<String, String> get() = getHeaders()

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
        if (cleanUrl.startsWith("EMBED#")) cleanUrl = cleanUrl.removePrefix("EMBED#")
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
        } catch (e: Exception) { null }
    }

    // 진짜 Cloudflare 우회가 풀렸을 때만 호출 (쿠키 즉시 파기 및 우회 요구)
    private fun getCookieErrorResponse(requestName: String): HomePageResponse {
        sharedPref.edit().remove("cf_cookies").remove("cf_user_agent").apply()
        cfCookies = null
        cfUserAgent = null

        val errorItem = newLiveSearchResponse(name = "쿠키를 최신화해주세요", url = mainUrl) {
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    // [신규] 우회는 성공했으나 단순히 중계 중인 경기가 없을 때 호출 (쿠키는 건드리지 않고 보존)
    private fun getNoMatchesResponse(requestName: String): HomePageResponse {
        val errorItem = newLiveSearchResponse(name = "중계 중인 경기가 없습니다", url = mainUrl) {
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    // 15초 타임아웃 유지 (자동 우회 1회 실행용)
    private suspend fun solveCloudflare(url: String): Pair<String, String>? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
        val context = try {
            Class.forName("android.app.ActivityThread")
                .getMethod("currentApplication")
                .invoke(null) as? android.content.Context
        } catch (e: Exception) { null }

        if (context == null) return@withContext null

        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
        } catch (e: Exception) {}

        suspendCancellableCoroutine<Pair<String, String>?> { continuation ->
            val webView = WebView(context)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = userAgent

            webView.webViewClient = object : WebViewClient() {
                var solved = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    if (url == null) return
                    if (solved) return
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance")) {
                        solved = true
                        if (continuation.isActive) continuation.resume(Pair(cookies, userAgent))
                        try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                    }
                }
            }
            webView.loadUrl(url)

            val handler = Handler(Looper.getMainLooper())
            val timeoutRunnable = Runnable {
                if (continuation.isActive) {
                    val cookies = CookieManager.getInstance().getCookie(url) ?: ""
                    if (cookies.contains("cf_clearance")) continuation.resume(Pair(cookies, userAgent))
                    else continuation.resume(null)
                    try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
                }
            }
            handler.postDelayed(timeoutRunnable, 15000)

            continuation.invokeOnCancellation {
                handler.removeCallbacks(timeoutRunnable)
                try { webView.stopLoading(); webView.destroy() } catch (e: Exception) {}
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            loadSavedCookies()

            var doc = app.get(url, headers = defaultHeaders).document

            // 1. Cloudflare 챌린지 감지 단계
            if (isCloudflareChallenge(doc.html())) {
                cfCookies = null
                cfUserAgent = null
                
                val cfResult = solveCloudflare(mainUrl) // 최대 15초 대기
                if (cfResult != null) {
                    cfCookies = cfResult.first
                    cfUserAgent = cfResult.second
                    
                    doc = app.get(url, headers = defaultHeaders).document
                    
                    // 우회 성공했는데도 또 챌린지 화면이 호출되는 특수 케이스 -> 완전 만료로 판단하고 우회 요구
                    if (isCloudflareChallenge(doc.html())) {
                        return getCookieErrorResponse(request.name)
                    }
                } else {
                    // 우회 자동 해결 실패 시 우회 요구
                    return getCookieErrorResponse(request.name)
                }
            }

            // 2. 파싱 및 스크래핑 단계 (여기 진입했다는 것은 Cloudflare 우회는 정상인 상태)
            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli") }

            if (iframeSrc.isNullOrBlank()) {
                // 우회는 성공했으나 iframe 주소가 비어있는 경우 -> 사이트 구조상 중계가 아예 없는 시점
                return getNoMatchesResponse(request.name)
            }

            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            
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
                    if (!extractedHost.isNullOrBlank()) dynamicThumbBase = "https://$extractedHost/thumbs"
                }

                if (dynamicThumbBase.isBlank()) {
                    val hostRegex = """([a-zA-Z0-9.-]*oncahot\.com)""".toRegex(RegexOption.IGNORE_CASE)
                    val hostMatch = hostRegex.find(iframeHtml)
                    if (hostMatch != null) dynamicThumbBase = "https://${hostMatch.groupValues[1]}/thumbs"
                }

                if (dynamicThumbBase.isBlank()) {
                    val iframeDoc = Jsoup.parse(iframeHtml)
                    val scriptSrc = iframeDoc.select("script").map { it.attr("src") }.firstOrNull { it.contains("script.bootstrap.js") }
                    if (!scriptSrc.isNullOrBlank()) {
                        val iframeUri = Uri.parse(iframeSrc)
                        val scriptUrl = if (scriptSrc.startsWith("http")) scriptSrc else "${iframeUri.scheme}://${iframeUri.host}/${scriptSrc.replace("../", "")}"
                        try {
                            val scriptHtml = app.get(scriptUrl, headers = defaultHeaders, referer = iframeSrc).text
                            val fallbackRegex = """(https?(?::|\\:)(?:/|\\/)+[a-zA-Z0-9.-]+(?:/|\\/)+thumbs)""".toRegex(RegexOption.IGNORE_CASE)
                            val scriptMatch = fallbackRegex.find(scriptHtml)
                            if (scriptMatch != null) dynamicThumbBase = scriptMatch.groupValues[1].replace("\\", "")
                        } catch (e: Exception) {}
                    }
                }

                if (dynamicThumbBase.isBlank()) {
                    val firstUrl = matches.asSequence().map { item -> listOfNotNull(item["surl"], item["surl2"], item["surl3"]) }.flatten().firstOrNull { it.isNotBlank() }
                    if (firstUrl != null) {
                        val extractedHost = extractDomainFromStreamUrl(firstUrl)
                        if (!extractedHost.isNullOrBlank()) dynamicThumbBase = "https://$extractedHost/thumbs"
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
            // 일반 예외 발생 시(네트워크 일시 지연 등) 쿠키를 파기하지 않고 경기 없음 상태로 안전 반환
            return getNoMatchesResponse(request.name)
        }

        // 3. 우회는 성공했으나 실시간으로 스크래핑된 경기가 단 하나도 없을 때
        if (items.isEmpty()) {
            return getNoMatchesResponse(request.name)
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
