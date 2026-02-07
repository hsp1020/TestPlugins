package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager

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
        // PC User-Agent 사용
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 1. WebViewResolver를 사용하여 페이지 로드 (JS 실행 및 쿠키 생성)
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf("User-Agent" to userAgent),
            // iframe 도메인에 매칭되면 WebView를 실행하도록 설정
            interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
        )

        val doc = response.text
        
        // 2. data-m3u8 속성 추출
        val regex = Regex("""data-m3u8=["']([^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 3. [핵심] WebView가 생성한 쿠키 가져오기
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie(url) ?: ""

            // 4. 헤더 설정
            val videoHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url,
                "Accept" to "*/*"
                // Origin 헤더는 제거 (키 요청 시 충돌 방지)
            )
            
            // 쿠키가 있으면 헤더에 추가
            if (cookies.isNotEmpty()) {
                videoHeaders["Cookie"] = cookies
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
                    this.headers = videoHeaders // 키 요청 시에도 이 헤더(쿠키 포함)가 사용됨
                }
            )
        }
    }
}
