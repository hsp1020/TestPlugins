package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    // ExtractorApi 규칙 준수 (Unit 반환)
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    // 실제 추출 로직 (Boolean 반환)
    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer

        return try {
            // 1. 플레이어 페이지 접속
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url

            // 2. 비디오 경로 추출 (/v/f/ID)
            val pathRegex = Regex("""/v/[ef]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) ?: pathRegex.find(cleanUrl)
            
            if (pathMatch == null) return false
            val path = pathMatch.value // ex: /v/f/27378773d575...

            // 3. 도메인 찾기 (소스코드 내 썸네일이나 var 변수에서)
            // 소스코드 예시: https://img-requset99.digitalorio3nx.com//v/f/.../thumb.png
            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)
            
            // 도메인 우선순위: 1.소스에서 찾은 도메인 2.현재 URL 도메인 3.PoorCDN 기본 도메인
            val domain = when {
                domainMatch != null -> domainMatch.groupValues[1]
                finalUrl.contains(path) -> {
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }
                else -> {
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    "https://every$serverNum.poorcdn.com"
                }
            }

            // 4. c.html 접속 및 토큰 추출 (핵심 로직 복구)
            // iframe/img src에 더블 슬래시(//v/)가 있는 경우가 많아 정규화
            val cleanPath = path.replace("//v/", "/v/")
            val tokenUrl = "$domain$cleanPath/c.html"
            val directM3u8 = "$domain$cleanPath/index.m3u8"

            // 토큰 요청용 헤더 (Referer가 중요)
            val tokenHeaders = browserHeaders.toMutableMap().apply {
                put("Referer", cleanUrl)
            }

            try {
                val tokenRes = app.get(tokenUrl, headers = tokenHeaders)
                val tokenText = tokenRes.text
                
                // 쿠키 획득 (Javascript document.cookie 파싱)
                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""").findAll(tokenText).forEach {
                    cookieMap[it.groupValues[1]] = it.groupValues[2]
                }

                // 토큰이 포함된 m3u8 주소 추출 (?h=...)
                // 패턴: location.href = "..." 또는 "index.m3u8?..."
                val realM3u8Match = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                    ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)
                
                var realM3u8 = realM3u8Match?.groupValues?.get(1) ?: directM3u8
                
                // 상대 주소일 경우 도메인 붙이기
                if (!realM3u8.startsWith("http")) {
                    realM3u8 = "$domain$cleanPath/$realM3u8".replace("$cleanPath/$cleanPath", cleanPath) // 중복 경로 방지
                }

                // 최종 M3U8 로드 (쿠키 포함)
                loadM3u8(realM3u8, cleanUrl, tokenHeaders, cookieMap, callback)
                return true

            } catch (e: Exception) {
                // c.html 실패 시 다이렉트 주소 시도
                loadM3u8(directM3u8, cleanUrl, tokenHeaders, emptyMap(), callback)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
        val headers = baseHeaders.toMutableMap()
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        ).forEach(callback)
    }
}
