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
            // 1. 플레이어 페이지 로드
            val playerResponse = try {
                app.get(
                    url,
                    referer = referer, 
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, referer = referer)
            }
            
            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies

            // 2. data-m3u8 주소 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: return

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 3. User-Agent 자동 추출
            // 토큰에 박힌 UA가 있다면(예: Chrome 116...) 그 값을 써야 403을 안 맞습니다.
            val extractedUA = extractUserAgentFromToken(m3u8Url)
            val userAgent = extractedUA ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

            // 4. 헤더 설정 [수정됨: Referer를 player-v1으로 고정]
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 주의: 영상은 player.bcbc.red지만, Referer는 반드시 player-v1.bcbc.red여야 함
            val correctReferer = "https://player-v1.bcbc.red/" 
            val correctOrigin = "https://player-v1.bcbc.red"

            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to correctReferer,
                "Origin" to correctOrigin,
                "Cookie" to cookieString
            )

            // 5. M3U8 원본 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 6. 암호화 키(JSON) 처리 및 M3U8 변조
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                // 키 파일 다운로드 (이때도 수정된 헤더 사용 필수)
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text
                
                // 실제 키 해독
                val actualKeyBytes = decryptKeyFromJson(jsonText)

                if (actualKeyBytes != null) {
                    val keyBase64 = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    val keyDataUri = "data:application/octet-stream;base64,$keyBase64"

                    // 키 주소 교체
                    m3u8Content = m3u8Content.replace(
                        keyMatch.groupValues[1], 
                        keyDataUri
                    )
                    
                    // 세그먼트 주소 절대 경로화 (영상 파일은 player.bcbc.red에 있으므로 m3u8Url 기반으로)
                    val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                    m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http") && !line.startsWith("data:")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }

                    // Data URI 생성
                    val finalM3u8DataUri = "data:application/vnd.apple.mpegurl;base64," + 
                        Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)

                    callback(
                        newExtractorLink(name, name, finalM3u8DataUri, ExtractorLinkType.M3U8) {
                            this.referer = correctReferer
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return 
                }
            }

            // Fallback
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = correctReferer
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- 내부 함수 ---

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        try {
            val decodedJsonStr = try {
                String(Base64.decode(jsonText, Base64.DEFAULT))
            } catch (e: Exception) {
                jsonText 
            }

            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            val cleanBytes = encryptedBytes.drop(2).toByteArray()

            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            val segments = listOf(
                cleanBytes.copyOfRange(0, 4),
                cleanBytes.copyOfRange(4, 8),
                cleanBytes.copyOfRange(8, 12),
                cleanBytes.copyOfRange(12, 16)
            )

            val resultKey = ByteArray(16)
            var offset = 0
            
            for (idx in permutation) {
                val seg = segments[idx]
                System.arraycopy(seg, 0, resultKey, offset, 4)
                offset += 4
            }

            return resultKey

        } catch (e: Exception) {
            return null
        }
    }

    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"([^"]+)"""").find(payload)
            uaMatch?.groupValues?.get(1)
        } catch (e: Exception) {
            null
        }
    }
}
