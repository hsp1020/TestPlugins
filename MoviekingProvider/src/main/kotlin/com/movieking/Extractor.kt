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
import kotlinx.coroutines.delay

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
        println("[MovieKingPlayer] getUrl 시작: $url")

        // 1. User-Agent 고정 (PC 크롬으로 위장)
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 2. WebViewResolver 실행 (쿠키 생성 목적)
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
            println("[MovieKingPlayer] M3U8 발견: $m3u8Url")
            
            // 4. [핵심 수정] 쿠키 강제 추출 시도 (최대 2.5초 대기)
            val cookieManager = CookieManager.getInstance()
            var cookies = ""
            
            // WebView가 쿠키를 디스크/메모리에 쓸 때까지 약간의 딜레이가 필요할 수 있음
            for (i in 1..5) {
                cookies = cookieManager.getCookie(url) ?: ""
                if (cookies.isNotEmpty()) {
                    println("[MovieKingPlayer] 쿠키 획득 성공 ($i/5): $cookies")
                    break
                }
                println("[MovieKingPlayer] 쿠키 없음, 대기 중... ($i/5)")
                delay(500L) 
            }

            // 5. 헤더 구성
            val videoHeaders = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // iframe 주소 자체를 리퍼러로
                "Accept" to "*/*"
            )
            
            if (cookies.isNotEmpty()) {
                videoHeaders["Cookie"] = cookies
            } else {
                println("[MovieKingPlayer] 경고: 쿠키를 획득하지 못했습니다. 재생 실패 가능성 높음.")
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
                    this.headers = videoHeaders
                }
            )
        } else {
             println("[MovieKingPlayer] M3U8 URL 패턴을 찾지 못했습니다.")
        }
    }
}
