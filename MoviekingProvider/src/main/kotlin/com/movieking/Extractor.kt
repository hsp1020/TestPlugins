package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
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
        try {
            // 🔹 1. WebViewResolver로 플레이어 페이지 로드
            println("[MovieKing] 1. Loading player page...")
            val playerResponse = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
            )
            val playerHtml = playerResponse.text

            // 🔹 2. data-m3u8 URL 추출
            val m3u8UrlRegex = """data-m3u8\s*=\s*['"]([^'"]+)['"]""".toRegex()
            val m3u8Match = m3u8UrlRegex.find(playerHtml)
            if (m3u8Match == null) {
                println("[MovieKing] ERROR: data-m3u8 not found")
                throw ErrorLoadingException("M3U8 URL not found")
            }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }
            println("[MovieKing] 2. M3U8 URL: $m3u8Url")

            // 🔹 3. JWT 토큰에서 User-Agent 추출
            println("[MovieKing] 3. Extracting User-Agent from JWT...")
            val userAgentFromToken = extractUserAgentFromM3U8Url(m3u8Url)
            val finalUserAgent = userAgentFromToken ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            println("[MovieKing] Using UA: $finalUserAgent")

            // 🔹 4. 쿠키 및 헤더 준비
            val cookieString = playerResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val headers = mapOf(
                "User-Agent" to finalUserAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*",
                "Cookie" to cookieString
            )

            // 🔹 5. M3U8 파일 직접 요청 (디버깅)
            println("[MovieKing] 4. Testing M3U8 request...")
            val testResponse = app.get(m3u8Url, headers = headers)
            println("[MovieKing] M3U8 response code: ${testResponse.code}")

            val m3u8Content = testResponse.text
            println("[MovieKing] M3U8 content sample:\n${m3u8Content.lines().take(5).joinToString("\n")}")

            // 🔹 6. 키 URI 확인
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyUriMatch = keyUriRegex.find(m3u8Content)
            
            if (keyUriMatch != null) {
                val keyUri = keyUriMatch.groupValues[1]
                println("[MovieKing] 5. Found key URI: $keyUri")
                
                // 키 직접 요청 테스트
                println("[MovieKing] 6. Testing key request...")
                try {
                    val keyResponse = app.get(keyUri, headers = headers)
                    val keyData = keyResponse.body.bytes()
                    println("[MovieKing] Key response code: ${keyResponse.code}")
                    println("[MovieKing] Key response size: ${keyData.size} bytes")
                    
                    if (keyData.size == 220) {
                        println("[MovieKing] WARNING: 220-byte error detected!")
                        val errorText = String(keyData).take(220)
                        println("[MovieKing] Error content: $errorText")
                    }
                } catch (e: Exception) {
                    println("[MovieKing] Key request error: ${e.message}")
                }
            }

            // 🔹 7. M3u8Helper로 최종 스트림 생성
            println("[MovieKing] 7. Generating streams with M3u8Helper...")
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                url,
                headers = headers
            ).forEach { link ->
                println("[MovieKing] Created stream: ${link.name}")
                callback(link)
            }

        } catch (e: Exception) {
            println("[MovieKing] ERROR: ${e.message}")
            throw ErrorLoadingException("Failed to extract: ${e.message}")
        }
    }

    /** JWT 토큰에서 User-Agent 추출 */
    private fun extractUserAgentFromM3U8Url(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/")
            val parts = token.split(".")
            
            if (parts.size >= 2) {
                val payloadJson = String(
                    Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING),
                    Charsets.UTF_8
                )
                
                println("[MovieKing] JWT payload: $payloadJson")
                
                val uaRegex = """"ua"\s*:\s*"([^"]+)"""".toRegex()
                val uaMatch = uaRegex.find(payloadJson)
                
                uaMatch?.groupValues?.get(1)?.let { uaValue ->
                    if (uaValue.startsWith("Chrome(")) {
                        val version = uaValue.removePrefix("Chrome(").removeSuffix(")")
                        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                    }
                    return uaValue
                }
            }
            null
        } catch (e: Exception) {
            println("[MovieKing] UA extraction error: ${e.message}")
            null
        }
    }
}
