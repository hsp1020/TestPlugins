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
        println("[MovieKingPlayer] getUrl 시작: $url")

        // 1. User-Agent 및 헤더 설정
        // PC User-Agent 사용 (모바일 차단 방지)
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 2. WebViewResolver를 사용하여 요청 (JS 실행 및 쿠키 굽기)
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
            
            // 4. [핵심] CookieManager 대신 response.cookies 사용
            // app.get이 성공했다면 OkHttp 내부 CookieJar에 쿠키가 있습니다.
            val cookieMap = response.cookies
            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            println("[MovieKingPlayer] 추출된 쿠키: $cookieString")

            // 5. [핵심] 헤더 강제 설정
            // 220 Byte 에러(403 Forbidden)를 피하기 위해 Referer를 iframe 주소로 고정합니다.
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to url, // iframe 전체 주소 (매우 중요)
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*"
            )
            
            if (cookieString.isNotEmpty()) {
                headers["Cookie"] = cookieString
            }

            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    // M3U8 요청 및 내부 KEY 요청 시 이 헤더가 사용됩니다.
                    this.referer = url 
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
        } else {
             println("[MovieKingPlayer] 실패: data-m3u8 패턴을 찾지 못했습니다.")
        }
    }
}
