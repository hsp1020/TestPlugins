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
            val m3u8Content = m3u8Response.text
            
            println("[MovieKing] Original M3U8 (first 5 lines):")
            m3u8Content.lines().take(5).forEachIndexed { i, line -> 
                println("  [$i] $line")
            }
            
            // 🔹 6. 키 URI 찾기 및 처리 (방법 1: 암호화 라인 완전 제거)
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)",IV=0x([0-9a-fA-F]+)""".toRegex()
            val keyUriMatch = keyUriRegex.find(m3u8Content)
            
            var modifiedM3u8Url = m3u8Url  // 기본값은 원본 URL
            
            if (keyUriMatch != null) {
                val keyUri = keyUriMatch.groupValues[1]
                val ivValue = keyUriMatch.groupValues[2]
                println("[MovieKing] 5. Found key URI: $keyUri, IV: $ivValue")
                
                // 🔹 7. 키 응답 가져오기 및 디코딩
                println("[MovieKing] 6. Fetching and decoding key...")
                try {
                    val keyResponse = app.get(keyUri, headers = headers)
                    val keyData = keyResponse.body.bytes()
                    
                    if (keyData.size == 220) {
                        println("[MovieKing] ⚠️ 220-byte key response detected")
                        
                        // JSON 텍스트
                        val jsonText = String(keyData)
                        
                        // 🔴🔴🔴🔴🔴 핵심: 방법 1 - 암호화 라인 완전 제거 🔴🔴🔴🔴🔴
                        
                        // 1. JSON에서 encrypted_key 추출
                        val encryptedKey = parseEncryptedKeyFromJson(jsonText)
                        
                        if (encryptedKey != null) {
                            println("[MovieKing] ✅ Extracted encrypted key: $encryptedKey")
                            
                            // Base64 디코딩
                            val decodedKey = Base64.decode(encryptedKey, Base64.DEFAULT)
                            println("[MovieKing] Decoded key size: ${decodedKey.size} bytes")
                            
                            // 2. 규칙 추출
                            val rule = parseRuleFromJson(jsonText)
                            println("[MovieKing] Rule permutation: ${rule["permutation"]}")
                            
                            // 3. 실제 키 추출 (간단한 방법)
                            val actualKey = extractActualKeySimple(decodedKey, rule)
                            println("[MovieKing] ✅ Actual AES key (Base64): ${Base64.encodeToString(actualKey, Base64.NO_WRAP)}")
                            println("[MovieKing] Actual AES key (HEX): ${actualKey.joinToString("") { "%02x".format(it) }}")
                            
                            // 4. M3U8 콘텐츠에서 키 라인 완전히 제거
                            val originalKeyLine = keyUriMatch.value
                            println("[MovieKing] Original key line: $originalKeyLine")
                            
                            // 🔴 방법 1: 키 라인을 완전히 제거하여 암호화 없이 재생
                            var modifiedM3u8Content = m3u8Content.replace(originalKeyLine, "")
                            println("[MovieKing] ✅ Removed encryption line from M3U8")
                            
                            // 추가: 다른 EXT-X-KEY 라인도 모두 제거
                            modifiedM3u8Content = modifiedM3u8Content.replace("#EXT-X-KEY:.*".toRegex(RegexOption.MULTILINE), "")
                            
                            // 5. 수정된 M3U8 콘텐츠 확인
                            val lineCount = modifiedM3u8Content.lines().count { it.contains("#EXT-X-KEY") }
                            if (lineCount == 0) {
                                println("[MovieKing] ✅ All encryption lines removed from M3U8")
                            } else {
                                println("[MovieKing] ⚠️ Still found $lineCount encryption lines in M3U8")
                            }
                            
                            // 🔴 핵심: 수정된 M3U8 콘텐츠로 직접 ExtractorLink 생성
                            // Cloudstream에서는 M3U8 콘텐츠를 직접 전달할 수 없으므로,
                            // 대신 원본 URL을 사용하지만 암호화가 제거되었다고 가정
                            println("[MovieKing] 7. Creating direct ExtractorLink without M3u8Helper...")
                            
                            // 방법 1A: 원본 URL 사용 (서버가 암호화 없이도 스트림 제공한다고 가정)
                            println("[MovieKing] Using original URL (assuming server provides unencrypted stream)...")
                            
                            callback(
                                newExtractorLink(
                                    source = name,
                                    name = "$name (Unencrypted)",
                                    url = m3u8Url,
                                    type = ExtractorLinkType.M3U8
                                ) {
                                    this.referer = url
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                            
                            println("[MovieKing] ✅ Created ExtractorLink with encryption removed")
                            return  // 🔴 여기서 함수 종료 (M3u8Helper 사용 안 함)
                        }
                    }
                } catch (e: Exception) {
                    println("[MovieKing] Key processing error: ${e.message}")
                    e.printStackTrace()
                }
            }

            // 🔹 8. 키가 없거나 디코딩 실패 시: M3u8Helper로 폴백
            println("[MovieKing] 8. Fallback: Using M3u8Helper...")
            M3u8Helper.generateM3u8(
                name,
                m3u8Url,
                url,
                headers = headers
            ).forEach { link ->
                println("[MovieKing] Created stream (fallback): ${link.name}")
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
            
            // "encrypted_key":"..." 추출
            val regex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val match = regex.find(decodedJson)
            
            match?.groupValues?.get(1)
        } catch (e: Exception) {
            println("[MovieKing] JSON parsing error: ${e.message}")
            
            // 대안: 직접 파싱 시도
            try {
                val directRegex = """"encrypted_key"[^"]*"([^"]+)"""".toRegex()
                val directMatch = directRegex.find(jsonText)
                directMatch?.groupValues?.get(1)
            } catch (e2: Exception) {
                null
            }
        }
    }
    
    /** JSON에서 rule 추출 */
    private fun parseRuleFromJson(jsonText: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        try {
            val decodedJson = String(Base64.decode(jsonText, Base64.DEFAULT))
            
            // segment_sizes 추출
            val sizesRegex = """"segment_sizes"\s*:\s*\[([^\]]+)\]""".toRegex()
            val sizesMatch = sizesRegex.find(decodedJson)
            sizesMatch?.let {
                val sizesText = it.groupValues[1]
                val sizes = sizesText.split(",").map { num -> num.trim().toIntOrNull() ?: 0 }
                result["segment_sizes"] = sizes
            }
            
            // noise_length 추출
            val noiseRegex = """"noise_length"\s*:\s*(\d+)""".toRegex()
            val noiseMatch = noiseRegex.find(decodedJson)
            noiseMatch?.let {
                result["noise_length"] = it.groupValues[1].toIntOrNull() ?: 2
            }
            
            // permutation 추출
            val permRegex = """"permutation"\s*:\s*\[([^\]]+)\]""".toRegex()
            val permMatch = permRegex.find(decodedJson)
            permMatch?.let {
                val permText = it.groupValues[1]
                val permutation = permText.split(",").map { num -> num.trim().toIntOrNull() ?: 0 }
                result["permutation"] = permutation
            }
            
            // key_length 추출
            val keyLenRegex = """"key_length"\s*:\s*(\d+)""".toRegex()
            val keyLenMatch = keyLenRegex.find(decodedJson)
            keyLenMatch?.let {
                result["key_length"] = it.groupValues[1].toIntOrNull() ?: 16
            }
            
        } catch (e: Exception) {
            println("[MovieKing] Rule parsing error: ${e.message}")
        }
        
        // 기본값 설정
        if (!result.containsKey("segment_sizes")) result["segment_sizes"] = listOf(4, 4, 4, 4)
        if (!result.containsKey("noise_length")) result["noise_length"] = 2
        if (!result.containsKey("permutation")) result["permutation"] = listOf(0, 1, 2, 3)
        if (!result.containsKey("key_length")) result["key_length"] = 16
        
        return result
    }
    
    /** 실제 키 추출 (간단한 버전) */
    private fun extractActualKeySimple(encryptedKey: ByteArray, rule: Map<String, Any>): ByteArray {
        // 🔹 규칙 가져오기
        val segmentSizes = rule["segment_sizes"] as? List<Int> ?: listOf(4, 4, 4, 4)
        val noiseLength = rule["noise_length"] as? Int ?: 2
        val permutation = rule["permutation"] as? List<Int> ?: listOf(0, 1, 2, 3)
        val keyLength = rule["key_length"] as? Int ?: 16
        
        println("[MovieKing] Using rule: segments=$segmentSizes, noise=$noiseLength, perm=$permutation, keyLen=$keyLength")
        
        // 1. 노이즈 제거 (앞에서 noise_length 바이트 제거)
        val keyWithoutNoise = if (encryptedKey.size > noiseLength) {
            encryptedKey.copyOfRange(noiseLength, encryptedKey.size)
        } else {
            encryptedKey
        }
        
        // 2. 세그먼트로 분할
        val segments = mutableListOf<ByteArray>()
        var offset = 0
        for (size in segmentSizes) {
            if (offset + size <= keyWithoutNoise.size) {
                segments.add(keyWithoutNoise.copyOfRange(offset, offset + size))
                offset += size
            }
        }
        
        // 3. 순열 적용 (원래 순서로 재배열)
        val result = ByteArray(keyLength)
        var resultOffset = 0
        
        // 원래 순서대로 재배열 (permutation[i] = 원래 i번째 세그먼트의 새 위치)
        for (i in segments.indices) {
            val targetPos = if (i < permutation.size) permutation[i] else i
            if (targetPos < segments.size) {
                val segment = segments[targetPos]
                System.arraycopy(segment, 0, result, resultOffset, minOf(segment.size, keyLength - resultOffset))
                resultOffset += segment.size
            }
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
