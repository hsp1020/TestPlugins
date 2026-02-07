package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.SubtitleFile
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
            // 🔹 1. WebViewResolver로 플레이어 페이지 로드 (쿠키/토큰 획득)
            println("[MovieKing] 1. Loading player page with WebViewResolver...")
            val playerResponse = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
            )
            val playerHtml = playerResponse.text
            println("[MovieKing] Player page loaded, HTML length: ${playerHtml.length}")

            // 🔹 2. data-m3u8 URL 추출
            val m3u8UrlRegex = """data-m3u8\s*=\s*['"]([^'"]+)['"]""".toRegex()
            val m3u8Match = m3u8UrlRegex.find(playerHtml)
            if (m3u8Match == null) {
                println("[MovieKing] ❌ ERROR: data-m3u8 attribute not found in HTML")
                println("[MovieKing] HTML sample (first 1500 chars): ${playerHtml.take(1500)}")
                throw ErrorLoadingException("M3U8 URL not found in player page")
            }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }
            println("[MovieKing] 2. Extracted M3U8 URL: $m3u8Url")

            // 🔹 3. JWT 토큰에서 User-Agent 추출
            println("[MovieKing] 3. Extracting User-Agent from JWT token...")
            val userAgentFromToken = extractUserAgentFromM3U8Url(m3u8Url)
            val finalUserAgent = userAgentFromToken ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
            println("[MovieKing] Using User-Agent: $finalUserAgent")

            // 🔹 4. 쿠키 준비
            val cookieString = playerResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            println("[MovieKing] Cookies: $cookieString")

            // 🔹 5. 헤더 구성 (핵심: 토큰의 UA와 일치)
            val headers = mapOf(
                "User-Agent" to finalUserAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Accept" to "*/*",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "Cookie" to cookieString
            )

            // 🔹 6. M3U8 파일 직접 요청 및 분석 (디버깅)
            println("[MovieKing] 4. Fetching M3U8 file directly...")
            val m3u8Response = app.get(m3u8Url, headers = headers)
            
            if (!m3u8Response.isSuccessful) {
                println("[MovieKing] ❌ ERROR: Failed to fetch M3U8. Status: ${m3u8Response.statusCode}")
                throw ErrorLoadingException("Failed to load M3U8 playlist")
            }
            
            val m3u8Content = m3u8Response.text
            println("[MovieKing] M3U8 fetched successfully. Content length: ${m3u8Content.length}")
            
            // M3U8 내용 샘플 출력
            val m3u8Sample = m3u8Content.lines().take(10).joinToString("\n")
            println("[MovieKing] M3U8 sample (first 10 lines):\n$m3u8Sample")

            // 🔹 7. 키 URI 찾기
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyUriMatch = keyUriRegex.find(m3u8Content)
            
            if (keyUriMatch != null) {
                val keyUri = keyUriMatch.groupValues[1]
                println("[MovieKing] 5. Found key URI in M3U8: $keyUri")
                
                // 🔹 8. 키 직접 요청 시도 (핵심 디버깅)
                println("[MovieKing] 6. Attempting to fetch decryption key...")
                try {
                    val keyResponse = app.get(keyUri, headers = headers)
                    val keyData = keyResponse.body.bytes()
                    println("[MovieKing] Key response status: ${keyResponse.statusCode}")
                    println("[MovieKing] Key response size: ${keyData.size} bytes")
                    
                    if (keyData.size == 220) {
                        println("[MovieKing] ⚠️ WARNING: Key response is 220 bytes - likely an error page!")
                        val keyResponseText = String(keyData).take(200)
                        println("[MovieKing] Key response text: $keyResponseText")
                        
                        // 220바이트 오류가 발생하면 여기서 대체 방법 시도
                        println("[MovieKing] Trying alternative approach without helper...")
                        
                        // M3u8Helper 대신 직접 링크 생성
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
                        println("[MovieKing] ✅ Created direct M3U8 link (without M3u8Helper)")
                        return
                    } else if (keyData.size == 16) {
                        println("[MovieKing] ✅ SUCCESS: Got valid 16-byte AES-128 key!")
                    } else {
                        println("[MovieKing] ℹ️ INFO: Key size is ${keyData.size} bytes")
                    }
                } catch (e: Exception) {
                    println("[MovieKing] ❌ ERROR fetching key: ${e.message}")
                }
            } else {
                println("[MovieKing] ℹ️ No key URI found in M3U8 (may be unencrypted)")
            }

            // 🔹 9. M3u8Helper 사용 (키가 정상적이거나 없는 경우)
            println("[MovieKing] 7. Generating stream with M3u8Helper...")
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                url,
                headers = headers
            ).forEach { link ->
                println("[MovieKing] ✅ Generated stream: ${link.name} - ${link.quality}")
                callback(link)
            }

        } catch (e: Exception) {
            println("[MovieKing] ❌ FATAL ERROR in getUrl: ${e.message}")
            e.printStackTrace()
            throw ErrorLoadingException("Failed to extract stream: ${e.message}")
        }
    }

    /** JWT 토큰에서 User-Agent 추출 헬퍼 함수 */
    private fun extractUserAgentFromM3U8Url(m3u8Url: String): String? {
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
                
                println("[MovieKing] JWT Payload: $payloadJson")
                
                // "ua" 필드 추출
                val uaRegex = """"ua"\s*:\s*"([^"]+)"""".toRegex()
                val uaMatch = uaRegex.find(payloadJson)
                
                uaMatch?.groupValues?.get(1)?.let { uaValue ->
                    // Chrome(116.0.0.0) → Chrome/116.0.0.0 변환
                    if (uaValue.startsWith("Chrome(")) {
                        val version = uaValue.removePrefix("Chrome(").removeSuffix(")")
                        val result = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                        println("[MovieKing] Converted UA: $uaValue → $result")
                        return result
                    }
                    println("[MovieKing] Raw UA from token: $uaValue")
                    return uaValue
                }
            }
            println("[MovieKing] Could not extract UA from token")
            null
        } catch (e: Exception) {
            println("[MovieKing] Error extracting UA: ${e.message}")
            null
        }
    }
}
