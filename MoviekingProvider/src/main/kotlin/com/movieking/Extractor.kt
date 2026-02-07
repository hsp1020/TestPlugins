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
        // 1. WebViewResolver로 페이지 로드
        val response = app.get(
            url,
            referer = referer,
            interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
        )

        val doc = response.text
        
        // 2. data-m3u8 추출 (수정된 정규식)
        // data-m3u8='https://...' 형식 찾기
        val regex = """data-m3u8\s*=\s*['"]([^'"]+)['"]""".toRegex()
        val match = regex.find(doc)

        if (match != null) {
            var m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
            println("[MovieKingPlayer] Found m3u8 URL: $m3u8Url")
            
            // 3. 토큰에서 User-Agent 추출
            val tokenUserAgent = try {
                // JWT 토큰 추출 (마지막 '/' 이후 부분)
                val tokenPart = m3u8Url.substringAfterLast("/")
                val parts = tokenPart.split(".")
                
                if (parts.size >= 2) {
                    // Base64 URL Safe 디코딩
                    val payloadBase64 = parts[1]
                    val payloadJson = String(
                        Base64.decode(payloadBase64, Base64.URL_SAFE or Base64.NO_PADDING),
                        Charsets.UTF_8
                    )
                    
                    println("[MovieKingPlayer] JWT Payload: $payloadJson")
                    
                    // "ua" 필드 추출
                    val uaRegex = """"ua"\s*:\s*"([^"]+)"""".toRegex()
                    val uaMatch = uaRegex.find(payloadJson)
                    
                    if (uaMatch != null) {
                        val uaValue = uaMatch.groupValues[1]
                        println("[MovieKingPlayer] Found UA in token: $uaValue")
                        
                        // Chrome(116.0.0.0) -> Chrome/116.0.0.0 형식으로 변환
                        if (uaValue.startsWith("Chrome(")) {
                            val version = uaValue.removePrefix("Chrome(").removeSuffix(")")
                            // Android Mobile User-Agent 형식
                            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                        } else {
                            // 다른 형식이면 그대로 사용
                            uaValue
                        }
                    } else {
                        println("[MovieKingPlayer] No UA field found in token")
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                    }
                } else {
                    println("[MovieKingPlayer] Invalid JWT format")
                    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
                }
            } catch (e: Exception) {
                e.printStackTrace()
                println("[MovieKingPlayer] Error extracting UA: ${e.message}")
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            }

            println("[MovieKingPlayer] Using UA: $tokenUserAgent")

            // 4. 쿠키 가져오기
            val cookieMap = response.cookies
            val cookieString = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 5. 헤더 설정
            val headers = mutableMapOf(
                "User-Agent" to tokenUserAgent,
                "Referer" to url,
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Accept-Encoding" to "gzip, deflate, br"
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
            println("[MovieKingPlayer] data-m3u8 not found in HTML")
            println("[MovieKingPlayer] HTML snippet: ${doc.take(1000)}")
        }
    }
}
