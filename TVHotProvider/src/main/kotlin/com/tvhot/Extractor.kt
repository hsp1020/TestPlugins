package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 모바일 UA가 아닌 PC UA를 사용해야 차단되는 경우가 적음 (상황에 따라 조정)
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val playerHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "cross-site",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        // TVMon에서 넘어온 Referer 사용
        val initialHeaders = playerHeaders.toMutableMap()
        if (referer != null) {
            initialHeaders["Referer"] = referer
        }

        // 1. 플레이어 페이지 접속 (쿠키 및 정보 획득)
        val playerRes = app.get(cleanUrl, headers = initialHeaders)
        val doc = playerRes.document
        val text = playerRes.text
        
        // 서버 번호 추출 (?s=9 등)
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com" // or possibly "https://germany-cdn..." based on HTML

        // 2. 비디오 ID 및 경로 추출
        // HTML 소스에서 /v/f/ID 형태 찾기 (TVMon 소스 예시: https://germany-cdn9398.bunny-cdn-player.online//v/f/...)
        val videoPathMatch = Regex("""/v/[a-z]/([a-zA-Z0-9]+)""").find(text) 
                             ?: Regex("""/v/[a-z]/([a-zA-Z0-9]+)""").find(cleanUrl)
        
        if (videoPathMatch != null) {
            val videoId = videoPathMatch.groupValues[1]
            // 경로가 /v/f/ 인지 /v/e/ 인지 확인 (보통 f나 e)
            val pathType = if (text.contains("/v/e/")) "e" else "f"
            
            // 토큰(c.html) url 구성
            val tokenUrl = "$domain/v/$pathType/$videoId/c.html"
            val directM3u8 = "$domain/v/$pathType/$videoId/index.m3u8"

            // 헤더 설정 (Origin이 플레이어 도메인이어야 함)
            val apiHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Referer" to cleanUrl,
                "Origin" to "https://player.bunny-frame.online"
            )

            try {
                // 3. 토큰 페이지 접속 시도
                // PoorCDN 계열은 c.html 접속 시 JS로 쿠키를 설정하거나 m3u8 주소를 리턴함
                val tokenRes = app.get(tokenUrl, headers = apiHeaders)
                
                // 쿠키 파싱 (Set-Cookie 및 JS document.cookie)
                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                
                Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""").findAll(tokenRes.text).forEach {
                    cookieMap[it.groupValues[1]] = it.groupValues[2]
                }

                // 토큰(?h=)이 포함된 실제 m3u8 주소 추출
                val realM3u8 = extractM3u8FromToken(tokenRes.text) ?: directM3u8
                val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                
                // M3U8 로드
                loadM3u8(finalM3u8, cleanUrl, apiHeaders, cookieMap, callback)

            } catch (e: Exception) {
                // 토큰 접속 실패 시 다이렉트 주소 시도
                loadM3u8(directM3u8, cleanUrl, apiHeaders, emptyMap(), callback)
            }
        }
    }

    private suspend fun loadM3u8(
        url: String, 
        referer: String, 
        baseHeaders: Map<String, String>, 
        cookies: Map<String, String>, 
        callback: (ExtractorLink) -> Unit
    ) {
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        val headers = baseHeaders.toMutableMap()
        if (cookieString.isNotEmpty()) headers["Cookie"] = cookieString

        // M3u8Helper를 사용하여 다중 화질 처리
        M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        ).forEach(callback)
    }

    private fun extractM3u8FromToken(text: String): String? {
        // c.html 응답 내에서 .m3u8 주소 찾기
        return Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(text)?.groupValues?.get(1)
            ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
    }
}
