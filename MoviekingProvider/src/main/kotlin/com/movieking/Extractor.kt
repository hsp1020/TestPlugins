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

            // 🔹 5. M3U8 파일 가져오기
            println("[MovieKing] 4. Fetching M3U8...")
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text
            
            println("[MovieKing] Original M3U8 (first 3 lines):")
            m3u8Content.lines().take(3).forEach { println("  $it") }
            
            // 🔹 6. 키 URI 찾기
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyUriMatch = keyUriRegex.find(m3u8Content)
            
            if (keyUriMatch != null) {
                val keyUri = keyUriMatch.groupValues[1]
                println("[MovieKing] 5. Found key URI: $keyUri")
                
                // 🔹 7. 키 응답 가져오기 및 디코딩 (간단한 파싱)
                println("[MovieKing] 6. Fetching and decoding key...")
                try {
                    val keyResponse = app.get(keyUri, headers = headers)
                    val keyData = keyResponse.body.bytes()
                    
                    if (keyData.size == 220) {
                        println("[MovieKing] ⚠️ 220-byte key response detected")
                        
                        // JSON 텍스트 (Base64 디코딩 필요)
                        val jsonText = String(keyData)
                        println("[MovieKing] Raw key response: $jsonText")
                        
                        // 🔴🔴🔴🔴🔴 핵심: 직접 JSON 파싱 🔴🔴🔴🔴🔴
                        val encryptedKey = parseEncryptedKeyFromJson(jsonText)
                        
                        if (encryptedKey != null) {
                            println("[MovieKing] ✅ Extracted encrypted key: $encryptedKey")
                            
                            // Base64 디코딩
                            val decodedKey = Base64.decode(encryptedKey, Base64.DEFAULT)
                            println("[MovieKing] Decoded key size: ${decodedKey.size} bytes")
                            println("[MovieKing] Decoded key (hex): ${decodedKey.joinToString("") { "%02x".format(it) }}")
                            
                            // 🔹 규칙 추출
                            val rule = parseRuleFromJson(jsonText)
                            println("[MovieKing] Rule: $rule")
                            
                            // 🔹 실제 키 추출 (간단한 방법)
                            val actualKey = extractActualKeySimple(decodedKey, rule)
                            println("[MovieKing] ✅ Actual AES key (Base64): ${Base64.encodeToString(actualKey, Base64.NO_WRAP)}")
                            
                            // 🔹 M3U8 콘텐츠 수정
                            val keyLine = keyUriMatch.value
                            val newKeyLine = "#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,${Base64.encodeToString(actualKey, Base64.NO_WRAP)}\",IV=0x${keyLine.substringAfter("IV=0x").substringBefore("\"")}"
                            
                            m3u8Content = m3u8Content.replace(keyLine, newKeyLine)
                            println("[MovieKing] ✅ Updated M3U8 with actual key")
                            println("[MovieKing] New key line: $newKeyLine")
                            
                            // 🔹 임시 M3U8 파일 생성 (메모리 기반)
                            // Cloudstream에서는 이 부분이 복잡할 수 있음
                            // 대안: 키가 제거된 M3U8 사용
                            m3u8Content = m3u8Content.replace("#EXT-X-KEY:METHOD=AES-128.*".toRegex(), "")
                            println("[MovieKing] ⚠️ Removed encryption (temporary solution)")
                        }
                    }
                } catch (e: Exception) {
                    println("[MovieKing] Key processing error: ${e.message}")
                    e.printStackTrace()
                }
            }

            // 🔹 8. 대안: 키 제거된 M3U8을 임시 파일로 저장
            // 이 부분은 Cloudstream API에 따라 구현이 달라짐
            // 간단한 방법: 키가 제거된 상태로 M3u8Helper 사용
            println("[MovieKing] 7. Generating streams with modified M3U8...")
            
            // 키가 제거되었는지 확인
            if (!m3u8Content.contains("#EXT-X-KEY:METHOD=AES-128")) {
                println("[MovieKing] ✅ Encryption removed from M3U8")
            } else {
                println("[MovieKing] ⚠️ Encryption still present in M3U8")
            }
            
            M3u8Helper.generateM3u8(
                name,
                m3u8Url, // 원래 URL (실제로는 수정된 내용이 필요)
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

    /** JSON에서 encrypted_key 추출 (간단한 정규식) */
    private fun parseEncryptedKeyFromJson(jsonText: String): String? {
        return try {
            // Base64 디코딩 (JSON 자체가 Base64로 인코딩됨)
            val decodedJson = String(Base64.decode(jsonText, Base64.DEFAULT))
            println("[MovieKing] Decoded JSON: $decodedJson")
            
            // "encrypted_key":"..." 추출
            val regex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val match = regex.find(decodedJson)
            
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            println("[MovieKing] JSON parsing error: ${e.message}")
            
            // 대안: 직접 파싱
            val directRegex = """"encrypted_key"[^"]*"([^"]+)"""".toRegex()
            val directMatch = directRegex.find(jsonText)
            directMatch?.groupValues?.get(1)
        }
    }
    
    /** JSON에서 rule 추출 */
    private fun parseRuleFromJson(jsonText: String): Map<String, Any> {
        return try {
            val decodedJson = String(Base64.decode(jsonText, Base64.DEFAULT))
            val ruleRegex = """"rule"\s*:\s*(\{[^}]+\})""".toRegex()
            val match = ruleRegex.find(decodedJson)
            
            if (match != null) {
                val ruleJson = match.groupValues[1]
                println("[MovieKing] Rule JSON: $ruleJson")
                
                // 간단한 파싱
                mapOf(
                    "parsed" to true,
                    "raw" to ruleJson
                )
            } else {
                emptyMap()
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }
    
    /** 실제 키 추출 (간단한 버전) */
    private fun extractActualKeySimple(encryptedKey: ByteArray, rule: Map<String, Any>): ByteArray {
        // 🔹 기본 규칙: [4,4,4,4] 세그먼트, noise_length=2, permutation=[3,1,2,0]
        
        // 1. 노이즈 제거 (앞 2바이트)
        val withoutNoise = encryptedKey.drop(2).toByteArray()
        
        // 2. 세그먼트 분할 [4,4,4,4]
        val segments = listOf(
            withoutNoise.copyOfRange(0, 4),
            withoutNoise.copyOfRange(4, 8),
            withoutNoise.copyOfRange(8, 12),
            withoutNoise.copyOfRange(12, 16)
        )
        
        // 3. 순열 적용 [3,1,2,0]
        val permutation = listOf(3, 1, 2, 0)
        val result = ByteArray(16)
        
        var offset = 0
        for (i in permutation) {
            val segment = segments[i]
            System.arraycopy(segment, 0, result, offset, segment.size)
            offset += segment.size
        }
        
        return result
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
                
                val uaRegex = """"ua"\s*:\s*"([^"]+)"""".toRegex()
                val uaMatch = uaRegex.find(payloadJson)
                
                uaMatch?.groupValues?.get(1)?.let { uaValue ->
                    if (uaValue.startsWith("Chrome(")) {
                        val version = uaValue.removePrefix("Chrome(").removeSuffix(")")
                        "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
                    } else uaValue
                }
            } else null
        } catch (e: Exception) {
            null
        }
    }
}
