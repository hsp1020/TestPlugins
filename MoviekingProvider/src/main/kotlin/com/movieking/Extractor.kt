package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
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
        // 1. User-Agent 고정
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 2. WebViewResolver 실행 (쿠키 생성용)
        // WebView가 실행되면 자동으로 시스템 CookieManager에 쿠키가 저장됩니다.
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to userAgent),
            interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
        )

        val doc = response.text
        
        // 3. data-m3u8 추출
        val regex = Regex("""data-m3u8=["']([^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 4. [핵심] 쿠키 수동 주입 제거 & 헤더 최소화
            // Cookie 헤더를 직접 넣지 않습니다. Cronet/OkHttp가 자동으로 처리하게 둡니다.
            // Referer와 User-Agent만 명시합니다.
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // iframe URL
                "Accept" to "*/*"
            )

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        }
    }
}
