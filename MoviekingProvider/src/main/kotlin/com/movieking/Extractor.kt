package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.M3u8Helper

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
        // 1. WebViewResolver를 통해 페이지 로드 (쿠키 생성 및 JS 챌린지 통과)
        // User-Agent를 강제하지 않고 앱의 기본 동작에 맡겨 토큰과 UA를 일치시킵니다.
        val response = app.get(
            url,
            referer = referer,
            interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
        )

        val doc = response.text
        
        // 2. data-m3u8 추출
        val regex = Regex("""data-m3u8=["']([^"']+)["']""")
        val match = regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            
            // 3. 쿠키 및 헤더 설정
            // response.cookies가 비어있을 경우를 대비해 요청 당시의 쿠키도 고려해야 하지만,
            // 보통 WebViewResolver 직후에는 response.cookies에 값이 있습니다.
            val cookieMap = response.cookies
            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 4. 헤더 설정
            // Referer는 반드시 iframe URL(현재 함수로 들어온 url)이어야 합니다.
            val headers = mutableMapOf(
                "Referer" to url, 
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*"
            )
            
            if (cookieString.isNotEmpty()) {
                headers["Cookie"] = cookieString
            }

            // 5. M3u8Helper 사용 (중요)
            // 직접 ExtractorLink를 만드는 대신 Helper를 쓰면 키(Key) 요청 시 헤더를 자동으로 적용해줍니다.
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = url,
                headers = headers
            ).forEach(callback)

        } else {
             // 실패 시 로그 출력
             System.out.println("[MovieKingPlayer] data-m3u8 not found")
        }
    }
}
