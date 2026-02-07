package com.movieking

import android.webkit.CookieManager // 쿠키 매니저 임포트 필수
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
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 1. WebViewResolver 실행
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to userAgent),
            interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red""")) // 정규식 백슬래시 수정
        )

        // 2. [중요] WebView가 생성한 쿠키를 수동으로 가져옵니다.
        // ExoPlayer는 기본적으로 CookieManager의 쿠키를 공유받지 못할 수 있으므로 헤더에 직접 넣어야 합니다.
        val cookies = CookieManager.getInstance().getCookie(url) ?: ""

        val doc = response.text
        val regex = Regex("""data-m3u8=["'](https://[^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 3. 헤더 맵에 'Cookie' 추가
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // 보통 iframe 주소가 Referer로 먹힙니다.
                "Accept" to "*/*",
                "Origin" to "https://player-v1.bcbc.red" // Origin 헤더 추가 권장
            )
            
            if (cookies.isNotEmpty()) {
                headers["Cookie"] = cookies
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                    this.headers = headers // 여기에 쿠키가 포함된 헤더가 들어갑니다.
                }
            )
        }
    }
}
