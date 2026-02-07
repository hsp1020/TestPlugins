package com.movieking

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    // JWT 토큰 디코딩을 위한 데이터 클래스
    data class JwtPayload(
        @JsonProperty("ua") val ua: String?,
        @JsonProperty("ip") val ip: String?,
        @JsonProperty("path") val path: String?,
        @JsonProperty("id") val id: Int?,
        @JsonProperty("iat") val iat: Long?,
        @JsonProperty("exp") val exp: Long?
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. WebViewResolver로 플레이어 페이지 로드
            val playerResponse = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
            )

            val playerHtml = playerResponse.text

            // 2. data-m3u8 속성 추출 (여러 패턴 시도)
            var m3u8Url: String? = null
            val patterns = listOf(
                """data-m3u8\s*=\s*['"]([^'"]+)['"]""",
                """data-m3u8\s*=\s*['"](https?://[^'"]+)['"]""",
                """data-m3u8\s*=\s*['"]((?:https?://)?player\.bcbc\.red[^'"]+)['"]"""
            )

            for (pattern in patterns) {
                val regex = pattern.toRegex()
                val match = regex.find(playerHtml)
                if (match != null) {
                    m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
                    if (!m3u8Url.startsWith("http")) {
                        m3u8Url = "https://$m3u8Url"
                    }
                    break
                }
            }

            if (m3u8Url == null) {
                throw ErrorLoadingException("M3U8 URL not found")
            }

            // 3. JWT 토큰에서 User-Agent 추출
            val tokenUa = extractUserAgentFromToken(m3u8Url)
            val finalUserAgent = tokenUa ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"

            // 4. 쿠키 준비
            val cookies = playerResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            // 5. 헤더 구성
            val headers = mapOf(
                "User-Agent" to finalUserAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cookie" to cookies
            ).toMutableMap()

            // 6. M3U8 파일 가져와서 분석
            val m3u8Response = app.get(m3u8Url, headers = headers)
            val m3u8Content = m3u8Response.text
            
            // 디버그: M3U8 내용 확인
            println("=== M3U8 Content (first 500 chars) ===")
            println(m3u8Content.take(500))
            println("=====================================")

            // 7. 키 URI 추출 시도
            val keyUri = extractKeyUri(m3u8Content)
            
            if (keyUri != null) {
                println("Found key URI: $keyUri")
                // 키 URI에 대한 추가 헤더 설정
                headers["Referer"] = m3u8Url
            }

            // 8. M3u8Helper로 스트림 생성 (Cloudstream 3 방식)
            val qualities = Qualities.values()
            M3u8Helper.generateM3u8(
                name = name,
                streamUrl = m3u8Url,
                referer = url,
                headers = headers,
                quality = qualities.find { it.value <= 1080 } ?: Qualities.Unknown.value
            ).forEach { link ->
                // ExtractorLink를 Cloudstream 3 형식으로 변환
                callback(
                    ExtractorLink(
                        name = name,
                        url = link.url,
                        referer = link.referer,
                        quality = link.quality,
                        isM3u8 = true
                    )
                )
            }

        } catch (e: Exception) {
            e.printStackTrace()
            throw ErrorLoadingException("Failed to load stream: ${e.message}")
        }
    }

    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            // JWT 토큰 추출 (URL의 마지막 부분)
            val token = m3u8Url.substringAfterLast("/")
            val parts = token.split(".")
            
            if (parts.size >= 2) {
                // Base64 URL Safe 디코딩
                val payloadJson = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING),
                    Charsets.UTF_8
                )
                
                // JSON 파싱
                val payload = tryParseJson<JwtPayload>(payloadJson)
                payload?.ua?.let { uaValue ->
                    // Chrome(116.0.0.0) 형식 처리
                    if (uaValue.startsWith("Chrome(")) {
                        val version = uaValue.removePrefix("Chrome(").removeSuffix(")")
                        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                    }
                    return uaValue
                }
            }
            null
        } catch (e: Exception) {
            null
        }
    }

    private fun extractKeyUri(m3u8Content: String): String? {
        val patterns = listOf(
            """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""",
            """URI="([^"]+)"""",
            """KEY.*URI="([^"]+)""""
        )
        
        for (pattern in patterns) {
            val regex = pattern.toRegex()
            val match = regex.find(m3u8Content)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }
}
