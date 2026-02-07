package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 1. iframe 페이지 요청
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to userAgent)
        )

        val doc = response.text
        
        // 2. data-m3u8 속성 추출
        val regex = Regex("""data-m3u8=["']([^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 3. [중요] 쿠키 추출 (app.get 응답에서 쿠키를 가져와 문자열로 변환)
            val cookiesMap = response.cookies
            val cookieString = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // 4. 헤더 맵 생성 (Video Player와 Key Request에 모두 적용됨)
            val videoHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://player-v1.bcbc.red/", // 플레이어 도메인 리퍼러 강제
                "Origin" to "https://player-v1.bcbc.red"     // Origin 헤더 추가
            )
            
            if (cookieString.isNotEmpty()) {
                videoHeaders["Cookie"] = cookieString
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    // 여기서 헤더를 주입해야 키 요청 시에도 사용됩니다.
                    this.headers = videoHeaders 
                }
            )
        }
    }
}
