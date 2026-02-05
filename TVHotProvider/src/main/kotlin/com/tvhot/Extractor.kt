package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.debug

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    // 디버깅용 로그 함수
    private fun logDebug(tag: String, message: String, data: Any? = null) {
        val logMessage = "[DEBUG] $tag: $message" + if (data != null) " | Data: $data" else ""
        debug(logMessage) // CloudStream의 debug 함수 사용
        println(logMessage) // Logcat에도 출력
    }

    // 요청 디버깅 함수
    private suspend fun debugRequest(url: String, headers: Map<String, String>, step: String) {
        logDebug("REQUEST-$step", "URL: $url")
        logDebug("REQUEST-$step", "Headers: ${headers.entries.joinToString("; ") { "${it.key}=${it.value}" }}")
        
        try {
            val response = app.get(url, headers = headers)
            logDebug("RESPONSE-$step", "Status: ${response.code}")
            logDebug("RESPONSE-$step", "Final URL: ${response.url}")
            logDebug("RESPONSE-$step", "Headers: ${response.headers}")
            
            if (response.code != 200) {
                logDebug("ERROR-$step", "HTTP Error ${response.code}")
            }
            
            return response
        } catch (e: Exception) {
            logDebug("ERROR-$step", "Exception: ${e.message}")
            logDebug("ERROR-$step", "Stack Trace: ${e.stackTraceToString()}")
            throw e
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        logDebug("EXTRACT-START", "Starting extraction process")
        logDebug("EXTRACT-INPUT", "URL: $url")
        logDebug("EXTRACT-INPUT", "Referer: $referer")
        logDebug("EXTRACT-INPUT", "Thumbnail Hint: $thumbnailHint")

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        logDebug("EXTRACT-CLEAN", "Cleaned URL: $cleanUrl")

        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer
        logDebug("EXTRACT-HEADERS", "Final headers: ${headers.keys}")

        return try {
            // Step 1: 플레이어 페이지 요청
            logDebug("STEP-1", "Fetching player page")
            val response = debugRequest(cleanUrl, headers, "PLAYER-PAGE")
            val text = response.text
            val finalUrl = response.url
            
            logDebug("STEP-1", "Response length: ${text.length} chars")
            logDebug("STEP-1", "Response first 500 chars: ${text.take(500)}...")

            // Step 2: 경로 패턴 검색
            logDebug("STEP-2", "Searching for path patterns")
            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
            
            val pathInResponse = pathRegex.find(text)
            val pathInUrl = pathRegex.find(cleanUrl)
            val pathInThumb = if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            
            logDebug("STEP-2", "Path in response: ${pathInResponse?.value}")
            logDebug("STEP-2", "Path in URL: ${pathInUrl?.value}")
            logDebug("STEP-2", "Path in thumbnail: ${pathInThumb?.value}")

            val finalPathMatch = pathInResponse ?: pathInUrl ?: pathInThumb
            
            if (finalPathMatch == null) {
                logDebug("ERROR-STEP2", "No path pattern found!")
                logDebug("ERROR-STEP2", "Sample of response for manual search:")
                logDebug("ERROR-STEP2", text.take(1000))
                return false
            }
            
            val path = finalPathMatch.value
            logDebug("STEP-2-SUCCESS", "Found path: $path")

            // Step 3: 도메인 추출
            logDebug("STEP-3", "Extracting domain")
            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)
            
            val domain = when {
                domainMatch != null -> {
                    logDebug("STEP-3-METHOD", "Found domain in response")
                    domainMatch.groupValues[1]
                }
                finalUrl.contains(path) -> {
                    logDebug("STEP-3-METHOD", "Extracting domain from final URL")
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }
                thumbnailHint != null && thumbnailHint.contains(path) -> {
                    logDebug("STEP-3-METHOD", "Extracting domain from thumbnail hint")
                    try {
                        val uri = java.net.URI(thumbnailHint)
                        if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                    } catch (e: Exception) {
                        logDebug("STEP-3-ERROR", "Failed to parse thumbnail URI: ${e.message}")
                        null
                    }
                }
                else -> {
                    logDebug("STEP-3-METHOD", "Using fallback server")
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    "https://every${serverNum}.poorcdn.com"
                }
            }
            
            logDebug("STEP-3-SUCCESS", "Domain: $domain")

            // Step 4: URL 정리
            val cleanPath = path.replace(Regex("//v/"), "/v/")
            val tokenUrl = "$domain$cleanPath/c.html"
            val directM3u8 = "$domain$cleanPath/index.m3u8"
            
            logDebug("STEP-4", "Clean path: $cleanPath")
            logDebug("STEP-4", "Token URL: $tokenUrl")
            logDebug("STEP-4", "Direct m3u8 URL: $directM3u8")

            // Step 5: 토큰 페이지 요청
            logDebug("STEP-5", "Fetching token page")
            val tokenHeaders = browserHeaders.toMutableMap().apply {
                put("Referer", cleanUrl)
                logDebug("STEP-5-HEADERS", "Token headers: ${this.keys}")
            }

            try {
                val tokenRes = debugRequest(tokenUrl, tokenHeaders, "TOKEN-PAGE")
                val tokenText = tokenRes.text
                
                logDebug("STEP-5", "Token response length: ${tokenText.length} chars")
                logDebug("STEP-5", "Token response snippet: ${tokenText.take(300)}...")

                // Step 6: 쿠키 수집
                logDebug("STEP-6", "Collecting cookies")
                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                
                val jsCookies = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    .findAll(tokenText)
                    .toList()
                
                logDebug("STEP-6", "Found ${jsCookies.size} JS cookies")
                jsCookies.forEachIndexed { index, match ->
                    val key = match.groupValues[1]
                    val value = match.groupValues[2]
                    cookieMap[key] = value
                    logDebug("STEP-6-COOKIE-$index", "$key=$value")
                }

                // Step 7: 실제 m3u8 URL 찾기
                logDebug("STEP-7", "Searching for real m3u8 URL")
                val realM3u8Match =
                    Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                        ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)

                var realM3u8 = realM3u8Match?.groupValues?.get(1) ?: directM3u8
                
                logDebug("STEP-7", "Found m3u8 pattern: ${realM3u8Match?.value}")
                logDebug("STEP-7", "Initial real m3u8: $realM3u8")

                if (!realM3u8.startsWith("http")) {
                    realM3u8 = "$domain$cleanPath/$realM3u8"
                        .replace("$cleanPath/$cleanPath", cleanPath)
                    logDebug("STEP-7-FIXED", "Fixed m3u8 URL: $realM3u8")
                }

                // Step 8: m3u8 스트림 생성
                logDebug("STEP-8", "Generating m3u8 stream")
                logDebug("STEP-8-DETAILS", "URL: $realM3u8")
                logDebug("STEP-8-DETAILS", "Referer: $cleanUrl")
                logDebug("STEP-8-DETAILS", "Cookies: ${cookieMap.size} cookies")

                loadM3u8(realM3u8, cleanUrl, tokenHeaders, cookieMap, callback)
                logDebug("EXTRACT-SUCCESS", "Extraction completed successfully")
                true
            } catch (e: Exception) {
                logDebug("ERROR-TOKEN", "Token page failed, trying direct m3u8")
                logDebug("ERROR-TOKEN-DETAILS", "Exception: ${e.message}")
                
                try {
                    logDebug("FALLBACK", "Attempting direct m3u8: $directM3u8")
                    loadM3u8(directM3u8, cleanUrl, tokenHeaders, emptyMap(), callback)
                    logDebug("FALLBACK-SUCCESS", "Direct m3u8 succeeded")
                    true
                } catch (e2: Exception) {
                    logDebug("FALLBACK-FAILED", "Direct m3u8 also failed: ${e2.message}")
                    false
                }
            }
        } catch (e: Exception) {
            logDebug("EXTRACT-FAILED", "Extraction failed completely")
            logDebug("EXTRACT-FAILED-DETAILS", "Exception: ${e.message}")
            logDebug("EXTRACT-FAILED-DETAILS", "Stack trace: ${e.stackTraceToString()}")
            false
        }
    }

    private suspend fun loadM3u8(
        url: String,
        referer: String,
        baseHeaders: Map<String, String>,
        cookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        logDebug("M3U8-START", "Loading m3u8 from: $url")
        
        val headers = baseHeaders.toMutableMap()
        if (cookies.isNotEmpty()) {
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            headers["Cookie"] = cookieString
            logDebug("M3U8-COOKIES", "Cookie header set (${cookies.size} cookies)")
        }

        try {
            // 먼저 m3u8 파일 접근 가능성 테스트
            logDebug("M3U8-TEST", "Testing m3u8 URL accessibility")
            val testResponse = app.get(url, headers = headers)
            logDebug("M3U8-TEST-RESULT", "Test status: ${testResponse.code}")
            logDebug("M3U8-TEST-RESULT", "Content type: ${testResponse.headers["Content-Type"]}")
            logDebug("M3U8-TEST-RESULT", "Content length: ${testResponse.text.length} chars")
            
            if (testResponse.code != 200) {
                logDebug("M3U8-ERROR", "m3u8 URL returned HTTP ${testResponse.code}")
            }

            val links = M3u8Helper.generateM3u8(
                name,
                url,
                referer,
                headers = headers
            ).toList()

            logDebug("M3U8-SUCCESS", "Generated ${links.size} quality links")
            links.forEachIndexed { index, link ->
                logDebug("M3U8-LINK-$index", "Quality: ${link.quality}, URL: ${link.url?.take(100)}...")
                callback(link)
            }
            
        } catch (e: Exception) {
            logDebug("M3U8-FAILED", "Failed to generate m3u8: ${e.message}")
            logDebug("M3U8-FAILED-DETAILS", "URL: $url")
            logDebug("M3U8-FAILED-DETAILS", "Headers: ${headers.keys}")
            throw e
        }
    }
}
