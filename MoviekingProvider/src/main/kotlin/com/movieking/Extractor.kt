package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URI

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
            // 1. WebViewResolver로 플레이어 페이지 로드 (쿠키/세션 생성)
            val playerResponse = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
            )
            val playerHtml = playerResponse.text

            // 2. data-m3u8 URL 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: throw Exception("data-m3u8 not found")

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 3. User-Agent 및 헤더 설정
            val userAgent = extractUserAgentFromM3U8Url(m3u8Url)
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            
            val cookieString = playerResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 4. 키 정보 추출 (복호화된 키를 헤더에 포함하기 위해)
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Cookie" to cookieString
            )

            // 5. M3U8 원본 다운로드 (키 정보 확인용)
            val m3u8Response = app.get(m3u8Url, headers = headers)
            val m3u8Content = m3u8Response.text

            // 6. 키 URI 찾기
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            // 7. 키 복호화 및 Base64 인코딩
            val keyB64 = if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text
                val actualKeyBytes = decryptKeyFromJson(jsonText)
                
                if (actualKeyBytes != null) {
                    Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                } else {
                    null
                }
            } else {
                null
            }

            // 8. 복호화된 키가 있으면 특별 헤더 추가
            if (keyB64 != null) {
                // CloudStream ExoPlayer가 인식할 수 있는 특별 헤더 추가
                // (실제 구현은 CloudStream의 ExoPlayer 커스텀 지원 여부에 달림)
                headers["X-M3U8-Key-B64"] = keyB64
            }

            // 9. 원본 M3U8 URL을 사용하되, 추가 헤더와 함께 전달
            // CloudStream의 ExoPlayer 구현이 이 헤더를 인식하고 키 교체를 해야 함
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = 0 // Unknown
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing] Error: ${e.message}")
        }
    }

    // --- Helper Functions ---

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        try {
            // 1. JSON 자체가 Base64일 수 있으므로 디코딩 시도
            val decodedJsonStr = try {
                String(Base64.decode(jsonText, Base64.DEFAULT))
            } catch (e: Exception) {
                jsonText // Base64 아님
            }

            // 2. Regex로 encrypted_key와 rule 추출
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            // 3. Encrypted Key 디코딩 (Base64 -> Bytes)
            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            // 앞 2바이트(Noise) 제거
            val cleanBytes = encryptedBytes.drop(2).toByteArray()

            // 4. Rule 파싱 (permutation 추출)
            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            // 5. 셔플 복구 (Segment Size는 4로 고정된 것으로 보임)
            // 16바이트를 4개 덩어리(4바이트씩)로 나눔
            val segments = listOf(
                cleanBytes.copyOfRange(0, 4),
                cleanBytes.copyOfRange(4, 8),
                cleanBytes.copyOfRange(8, 12),
                cleanBytes.copyOfRange(12, 16)
            )

            val resultKey = ByteArray(16)
            var offset = 0
            
            // permutation 순서대로 재조립
            for (idx in permutation) {
                val seg = segments[idx]
                System.arraycopy(seg, 0, resultKey, offset, 4)
                offset += 4
            }

            return resultKey

        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }

    private fun extractUserAgentFromM3U8Url(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"Chrome\(([^)]+)\)"""").find(payload)
            val version = uaMatch?.groupValues?.get(1)
            
            if (version != null) {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$version Mobile Safari/537.36"
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
