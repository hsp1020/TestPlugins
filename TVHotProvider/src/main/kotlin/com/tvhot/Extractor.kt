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
    
    private val headers = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to mainUrl,
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val requestHeaders = headers.toMutableMap()
        if (referer != null) {
            requestHeaders["Referer"] = referer
        }

        // 1. 플레이어 페이지 HTML 가져오기
        val res = app.get(cleanUrl, headers = requestHeaders)
        val text = res.text
        
        // 2. 비디오 ID 및 경로 추출 ( /v/f/ID 형태 )
        // TVMon 소스 예시: https://germany-cdn9398.bunny-cdn-player.online//v/f/...
        val pattern = Regex("""/v/([ef])/([a-zA-Z0-9]+)""")
        val match = pattern.find(text) ?: pattern.find(cleanUrl)
        
        if (match != null) {
            val type = match.groupValues[1] // e or f
            val id = match.groupValues[2]
            
            // 도메인 추출 (germany-cdn... 또는 every9.poorcdn 등 유동적)
            // URL 자체에서 도메인을 가져오거나 text에서 추출
            val urlObj = java.net.URI(cleanUrl)
            val domain = "${urlObj.scheme}://${urlObj.host}"
            
            val directM3u8 = "$domain/v/$type/$id/index.m3u8"
            
            // M3U8Helper를 이용해 자동/해상도별 링크 생성
            M3u8Helper.generateM3u8(
                name,
                directM3u8,
                cleanUrl, // referer for m3u8 request
                headers = requestHeaders
            ).forEach(callback)
        }
    }
}
