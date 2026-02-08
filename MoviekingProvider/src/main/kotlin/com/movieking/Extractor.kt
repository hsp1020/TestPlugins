package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json

@Serializable
data class KeyResponse(
    val encrypted_key: String,
    val rule: KeyRule
)

@Serializable
data class KeyRule(
    val segment_sizes: List<Int>,
    val noise_length: Int,
    val permutation: List<Int>,
    val segments_count: Int,
    val key_length: Int
)

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
            
            // 🔹 6. 키 URI 찾기
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyUriMatch = keyUriRegex.find(m3u8Content)
            
            if (keyUriMatch != null) {
                val keyUri = keyUriMatch.groupValues[1]
                println("[MovieKing] 5. Found key URI: $keyUri")
                
                // 🔹 7. 키 응답 가져오기 및 디코딩
                println("[MovieKing] 6. Fetching and decoding key...")
                try {
                    val keyResponse = app.get(keyUri, headers = headers)
                    val keyData = keyResponse.body.bytes()
                    
                    if (keyData.size == 220) {
                        println("[MovieKing] ⚠️ 220-byte key response detected")
                        
                        // JSON 파싱
                        val jsonText = String(keyData)
                        println("[MovieKing] Key JSON: $jsonText")
                        
                        val json = Json { ignoreUnknownKeys = true }
                        val keyResponseObj = json.decodeFromString<KeyResponse>(jsonText)
                        
                        // 🔴🔴🔴🔴🔴 핵심: 키 추출 및 변환 🔴🔴🔴🔴🔴
                        val encryptedKeyBase64 = keyResponseObj.encrypted_key
                        println("[MovieKing] Encrypted key (Base64): $encryptedKeyBase64")
                        
                        // Base64 디코딩
                        val encryptedKey = Base64.decode(encryptedKeyBase64, Base64.DEFAULT)
                        println("[MovieKing] Encrypted key bytes: ${encryptedKey.size}")
                        
                        // 🔹 실제 AES 키 추출 (규칙에 따른 변환)
                        val actualKey = extractActualKey(encryptedKey, keyResponseObj.rule)
                        println("[MovieKing] Actual AES key (hex): ${actualKey.joinToString("") { "%02x".format(it) }}")
                        println("[MovieKing] Actual AES key (Base64): ${Base64.encodeToString(actualKey, Base64.NO_WRAP)}")
                        
                        // 🔹 M3U8 콘텐츠 수정: 키 URI를 실제 키로 대체
                        val keyLine = "#EXT-X-KEY:METHOD=AES-128,URI=\"$keyUri\""
                        val newKeyLine = "#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,${Base64.encodeToString(actualKey, Base64.NO_WRAP)}\""
                        
                        m3u8Content = m3u8Content.replace(keyLine, newKeyLine)
                        println("[MovieKing] Replaced key URI with actual key")
                        
                        // 🔹 수정된 M3U8을 임시 URL로 제공 (Cloudstream 방식)
                        // 참고: 실제 구현에서는 메모리나 임시 파일에 저장해야 함
                    }
                } catch (e: Exception) {
                    println("[MovieKing] Key processing error: ${e.message}")
                }
            }

            // 🔹 8. 수정된 M3U8으로 스트림 생성
            println("[MovieKing] 7. Generating streams...")
            
            // M3U8 콘텐츠가 수정되었으면 새 M3U8 URL 필요
            // 임시로 원본 URL 사용 (테스트용)
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

    /** 실제 AES 키 추출 (규칙에 따른 변환) */
    private fun extractActualKey(encryptedKey: ByteArray, rule: KeyRule): ByteArray {
        // 🔹 간단한 변환: 노이즈 제거 및 순열 적용
        // 실제 구현은 서버의 정확한 알고리즘에 따라 달라짐
        
        val segmentSizes = rule.segment_sizes
        val permutation = rule.permutation
        val noiseLength = rule.noise_length
        
        // 1. 노이즈 제거 (앞에서 noise_length 바이트 제거)
        val keyWithoutNoise = encryptedKey.drop(noiseLength).toByteArray()
        
        // 2. 세그먼트로 분할
        val segments = mutableListOf<ByteArray>()
        var offset = 0
        for (size in segmentSizes) {
            segments.add(keyWithoutNoise.copyOfRange(offset, offset + size))
            offset += size
        }
        
        // 3. 순열 적용 (원래 순서로 재배열)
        val reorderedSegments = Array(segments.size) { ByteArray(0) }
        for ((i, pos) in permutation.withIndex()) {
            reorderedSegments[pos] = segments[i]
        }
        
        // 4. 병합
        val result = ByteArray(rule.key_length)
        var resultOffset = 0
        for (segment in reorderedSegments) {
            System.arraycopy(segment, 0, result, resultOffset, segment.size)
            resultOffset += segment.size
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
