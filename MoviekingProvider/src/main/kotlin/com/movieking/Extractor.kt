package com.movieking

import android.util.Base64
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
        // 1. WebViewResolver로 페이지 로드 (쿠키 생성 및 토큰 발급)
        // 이때 기기의 WebView User-Agent가 사용되며, 서버는 이를 토큰에 기록합니다.
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
            
            // 3. [핵심] 토큰(JWT)에서 서버가 기대하는 User-Agent 추출
            // URL 구조: https://.../m3u8/HEADER.PAYLOAD.SIGNATURE
            val tokenUserAgent = try {
                val parts = m3u8Url.split(".")
                if (parts.size >= 2) {
                    // URL Safe Base64 디코딩
                    val payload = String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
                    
                    // JSON 파싱 없이 Regex로 "ua":"Chrome(xxx)" 찾기
                    // 예: "ua":"Chrome(116.0.0.0)"
                    val uaMatch = Regex(""""ua"\s*:\s*"Chrome\(([^)]+)\)"""").find(payload)
                    if (uaMatch != null) {
                        val version = uaMatch.groupValues[1]
                        // 안드로이드 기본 User-Agent 포맷으로 재구성
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                    } else {
                        // ua 필드가 없으면 기본값 (최신 버전)
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                    }
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            } ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

            println("[MovieKingPlayer] Detected UA from Token: $tokenUserAgent")

            // 4. 쿠키 가져오기
            val cookieMap = response.cookies
            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 5. 헤더 설정 (추출한 User-Agent 강제 적용)
            val headers = mutableMapOf(
                "User-Agent" to tokenUserAgent, // M3u8Helper가 이 UA를 사용하여 키를 요청함
                "Referer" to url,               // Iframe 주소
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*"
            )
            
            if (cookieString.isNotEmpty()) {
                headers["Cookie"] = cookieString
            }

            // 6. M3u8Helper 실행
            M3u8Helper.generateM3u8(
                source = name,
                streamUrl = m3u8Url,
                referer = url,
                headers = headers
            ).forEach(callback)

        } else {
             System.out.println("[MovieKingPlayer] data-m3u8 not found")
        }
    }
}
