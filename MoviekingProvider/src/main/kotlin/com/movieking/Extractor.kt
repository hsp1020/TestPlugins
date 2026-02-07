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
        // 1. User-Agent 설정 (PC 버전으로 위장)
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 2. iframe 페이지 요청
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to userAgent)
        )

        val doc = response.text
        
        // 3. data-m3u8 속성 추출
        val regex = Regex("""data-m3u8=["']([^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 4. 쿠키 추출 (중요: 세션 유지를 위해 필요)
            val cookiesMap = response.cookies
            val cookieString = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // 5. 헤더 설정 (수정됨)
            // Origin 헤더 제거 (403 에러의 주원인일 가능성 높음)
            // Referer를 iframe 전체 주소로 설정
            val videoHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // 전체 iframe URL 사용
                "Accept" to "*/*"
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
                    this.referer = url // 전체 iframe URL 사용
                    this.quality = Qualities.Unknown.value
                    this.headers = videoHeaders 
                }
            )
        }
    }
}
