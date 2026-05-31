package com.kktv

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

    private fun getPosterHeaders(referer: String): Map<String, String> {
        val currentUA = cfUserAgent ?: userAgent
        val headers = mutableMapOf(
            "Referer" to referer,
            "User-Agent" to currentUA,
            "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
            "Sec-Fetch-Dest" to "image",
            "Sec-Fetch-Mode" to "no-cors",
            "Sec-Fetch-Site" to "cross-site"
        )
        cfCookies?.let {
            headers["Cookie"] = it
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

        // 기존에 디바이스 내부에 기생하던 효력 없는 만료 쿠키를 완전히 소거하여 새 챌린지 해결을 강제합니다.
        try {
            val cookieManager = CookieManager.getInstance()
            cookieManager.removeAllCookies(null)
            cookieManager.flush()
            println("[CUTV_DEBUG] [Cloudflare Bypass] 이전 웹뷰 만료 캐시 쿠키 제거 완료")
        } catch (e: Exception) {
            println("[CUTV_DEBUG] [Cloudflare Bypass] WARNING: 쿠키 비우기 실패: ${e.message}")
        }

        suspendCancellableCoroutine<Pair<String, String>?> { continuation ->
            val webView = WebView(context)
            
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.databaseEnabled = true
            
            // 일반 모바일 Chrome 브라우저 User-Agent 강제 주입 (; wv 제거)
            webView.settings.userAgentString = userAgent
            println("[CUTV_DEBUG] [Cloudflare Bypass] WebView User-Agent 강제 설정: $userAgent")

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
                    println("[CUTV_DEBUG] [Cloudflare Bypass] 실시간 쿠키 체크: $cookies")

                    if (cookies.contains("cf_clearance")) {
                        solved = true
                        println("[CUTV_DEBUG] [Cloudflare Bypass] 성공: cf_clearance 쿠키 발견!")
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
                    println("[CUTV_DEBUG] [Cloudflare Bypass] 실패: 15초 타임아웃 초과")
                    val cookieManager = CookieManager.getInstance()
                    val cookies = cookieManager.getCookie(url) ?: ""
                    if (cookies.isNotBlank() && cookies.contains("cf_clearance")) {
                        println("[CUTV_DEBUG] [Cloudflare Bypass] 타임아웃되었으나 수집된 cf_clearance 쿠키 반환 시도: $cookies")
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
                                println("[CUTV_DEBUG] [Cloudflare Bypass] 성공 (인터벌 체크): cf_clearance 쿠키 발견!")
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
            if (cfCookies == null) {
                println("[CUTV_DEBUG] [Cloudflare Bypass] 시작: cfCookies가 없으므로 우회 절차를 시도합니다.")
                val cfResult = solveCloudflare(mainUrl)
                if (cfResult != null) {
                    cfCookies = cfResult.first
                    cfUserAgent = cfResult.second
                    println("[CUTV_DEBUG] [Cloudflare Bypass] 성공적으로 우회 완료! cfCookies 수집됨.")
                } else {
                    println("[CUTV_DEBUG] [Cloudflare Bypass] 우회 실패: 결과가 null입니다.")
                }
            }

            var doc = app.get(url, headers = defaultHeaders).document

            if (isCloudflareChallenge(doc.html())) {
                println("[CUTV_DEBUG] [Cloudflare Bypass] Cloudflare 챌린지 감지됨. 우회 절차를 재시도합니다.")
                // 재우회 시 기존 변수를 지워 새 검증을 확실히 보장합니다.
                cfCookies = null
                cfUserAgent = null
                val cfResult = solveCloudflare(mainUrl)
                if (cfResult != null) {
                    cfCookies = cfResult.first
                    cfUserAgent = cfResult.second
                    println("[CUTV_DEBUG] [Cloudflare Bypass] 재시도 성공! defaultHeaders 갱신 완료.")
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
                val preview = if (doc.html().length > 1000) doc.html().substring(0, 1000) else doc.html()
                println("[CUTV_DEBUG] 🔍 [DEBUG PREVIEW] 메인 HTML 앞부분 1000자:\n[PREVIEW_START]\n$preview\n[PREVIEW_END]")
                return newHomePageResponse(request.name, emptyList())
            }

            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            println("[CUTV_DEBUG] 추출된 iframe 주소: $iframeSrc")
            println("[CUTV_DEBUG] iframe HTML 크기: ${iframeHtml.length}자")

            val indexOfOncahot = iframeHtml.indexOf("oncahot", ignoreCase = true)
            if (indexOfOncahot != -1) {
                val start = maxOf(0, indexOfOncahot - 100)
                val end = minOf(iframeHtml.length, indexOfOncahot + 100)
                val context = iframeHtml.substring(start, end)
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] iframe 내 'oncahot' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
            } else {
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] iframe 내부에 'oncahot' 문자열 자체가 존재하지 않습니다.")
            }

            val indexOfThumbsDoc = doc.html().indexOf("thumbs", ignoreCase = true)
            if (indexOfThumbsDoc != -1) {
                val start = maxOf(0, indexOfThumbsDoc - 100)
                val end = minOf(doc.html().length, indexOfThumbsDoc + 100)
                val context = doc.html().substring(start, end)
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] 메인 HTML 내 'thumbs' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
            }

            // 썸네일 베이스 URL 동적 추출
            var dynamicThumbBase = "" 
            
            val hostRegex = """([a-zA-Z0-9.-]*oncahot\.com)""".toRegex(RegexOption.IGNORE_CASE)
            val hostMatch = hostRegex.find(iframeHtml)
            
            if (hostMatch != null) {
                val cleanDomain = hostMatch.groupValues[1]
                dynamicThumbBase = "https://$cleanDomain/thumbs"
                println("[CUTV_DEBUG] SUCCESS: [방안1] 도메인 다이렉트 매칭 성공 -> $dynamicThumbBase")
            } else {
                println("[CUTV_DEBUG] INFO: [방안1] 매칭 실패. [방안2] 자바스크립트 파일 추적으로 진입합니다.")
                
                val iframeDoc = Jsoup.parse(iframeHtml)
                val scriptSrc = iframeDoc.select("script").map { it.attr("src") }
                    .firstOrNull { it.contains("script.bootstrap.js") }

                println("[CUTV_DEBUG] 찾은 script.bootstrap.js 경로: $scriptSrc")

                if (!scriptSrc.isNullOrBlank()) {
                    val iframeUri = Uri.parse(iframeSrc)
                    val scriptUrl = if (scriptSrc.startsWith("http")) {
                        scriptSrc
                    } else {
                        val cleanPath = scriptSrc.replace("../", "")
                        "${iframeUri.scheme}://${iframeUri.host}/$cleanPath"
                    }
                    println("[CUTV_DEBUG] 타겟 스크립트 절대경로: $scriptUrl")
                    
                    try {
                        val scriptHtml = app.get(scriptUrl, headers = defaultHeaders, referer = iframeSrc).text
                        println("[CUTV_DEBUG] 스크립트 내용 수신 완료. 크기: ${scriptHtml.length}자")

                        val indexOfThumbsScript = scriptHtml.indexOf("thumbs", ignoreCase = true)
                        if (indexOfThumbsScript != -1) {
                            val start = maxOf(0, indexOfThumbsScript - 100)
                            val end = minOf(scriptHtml.length, indexOfThumbsScript + 100)
                            val context = scriptHtml.substring(start, end)
                            println("[CUTV_DEBUG] 🔍 [FACT DUMP] 스크립트 내 'thumbs' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
                        }

                        val fallbackRegex = """(https?(?::|\\:)(?:/|\\/)+[a-zA-Z0-9.-]+(?:/|\\/)+thumbs)""".toRegex(RegexOption.IGNORE_CASE)
                        val scriptMatch = fallbackRegex.find(scriptHtml)
                        if (scriptMatch != null) {
                            dynamicThumbBase = scriptMatch.groupValues[1].replace("\\", "")
                            println("[CUTV_DEBUG] SUCCESS: [방안2] 스크립트 내부에서 완전한 주소 추출 성공 -> $dynamicThumbBase")
                        } else {
                            println("[CUTV_DEBUG] FAIL: [방안2] 스크립트는 로드했으나 패턴 매칭 실패")
                        }
                    } catch (e: Exception) {
                        println("[CUTV_DEBUG] FAIL: [방안2] script.bootstrap.js 네트워크 요청 실패: ${e.message}")
                    }
                } else {
                    println("[CUTV_DEBUG] FAIL: iframe 내부에서 script.bootstrap.js 태그 자체를 찾을 수 없음")
                }
            }

            if (dynamicThumbBase.isBlank()) {
                println("[CUTV_DEBUG] WARNING: 모든 동적 추출 알고리즘이 최종 실패했습니다. 빈 주소가 부여됩니다.")
            }
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
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
                        val encodedPoster = URLEncoder.encode(posterUrl, "UTF-8") // 검증된 완전한 포스터 주소를 인코딩하여 전송

                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe&poster=$encodedPoster"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                                this.posterHeaders = getPosterHeaders(iframeSrc)
                            }
                        )
                    }
                }
            } else {
                println("[CUTV_DEBUG] FAIL: window.galaxystream.data2 데이터를 찾지 못했습니다.")
            }
        } catch (e: Exception) {
            println("[CUTV_DEBUG] ERROR 발생: ${e.message}")
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

        // 홈페이지 단계에서 정상 가공된 포스터 주소를 그대로 받아옵니다 (중복 다운로드 방지)
        val rawPoster = uri.getQueryParameter("poster")
        val posterUrl = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else ""

        println("[CUTV_DEBUG] load() 진입 -> 전달받은 posterUrl: $posterUrl")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.plot = "실시간 스포츠 중계" // 플롯 값 고정 지정
            this.posterHeaders = getPosterHeaders(iframeUrl)
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
            val iframeHtml = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
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
