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
                    
                    // 🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴
                    // 🔴 여기가 핵심: 220바이트 응답 전체를 출력하는 부분 🔴
                    // 🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴
                    if (keyData.size == 220) {
                        println("════════════════════════════════════════════════════════")
                        println("[MovieKing] ⚠️  WARNING: Key response is 220 bytes")
                        println("════════════════════════════════════════════════════════")
                        
                        // 1. 전체 220바이트를 Base64로 출력
                        val fullBase64 = Base64.encodeToString(keyData, Base64.NO_WRAP)
                        println("[MovieKing] 🔑 FULL 220-BYTE RESPONSE (Base64):")
                        println(fullBase64)
                        println("Base64 길이: ${fullBase64.length} 문자")
                        println()
                        
                        // 2. 전체 220바이트를 문자열로 변환해서 출력
                        val fullText = String(keyData)
                        println("[MovieKing] 📄 FULL 220-BYTE RESPONSE (Text):")
                        println(fullText)
                        println("텍스트 길이: ${fullText.length} 문자")
                        println()
                        
                        // 3. HEX 형식으로도 출력 (디버깅용)
                        println("[MovieKing] 🔢 FIRST 50 BYTES (HEX):")
                        println(keyData.take(50).joinToString(" ") { "%02x".format(it) })
                        println()
                        
                        // 4. 각 바이트의 ASCII 값 출력
                        println("[MovieKing] 🔤 FIRST 50 BYTES (ASCII):")
                        for (i in 0 until minOf(50, keyData.size)) {
                            val byte = keyData[i]
                            if (byte >= 32 && byte <= 126) {
                                print(String(byteArrayOf(byte)))
                            } else {
                                print(".")
                            }
                        }
                        println()
                        println("════════════════════════════════════════════════════════")
                        
                        // 5. 대체 처리: M3u8Helper 대신 직접 링크 생성
                        println("[MovieKing] Trying alternative approach without M3u8Helper...")
                        
                        // M3u8Helper 대신 직접 ExtractorLink 생성
                        // 참고: newExtractorLink는 ExtractorApi의 메서드입니다
                        val extractorLink = newExtractorLink(
                            source = name,
                            name = name,
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                        
                        callback(extractorLink)
                        println("[MovieKing] ✅ Created direct M3U8 link (without M3u8Helper)")
                        return  // 여기서 종료
                    }
                    // 🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴🔴
                    
                } catch (e: Exception) {
                    println("[MovieKing] Key request error: ${e.message}")
                }
            }

            // 🔹 7. 키가 220바이트가 아니거나 없는 경우: M3u8Helper로 최종 스트림 생성
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
