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
            // 1. 플레이어 페이지 로드 (WebViewResolver 사용)
            // 타임아웃 방지를 위해 User-Agent를 고정하고, 실패 시에도 진행하도록 try-catch 감쌈
            val playerResponse = try {
                app.get(
                    url,
                    referer = referer,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                // WebView 실패시 일반 요청 시도
                app.get(url, referer = referer)
            }
            
            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies // 쿠키 확보

            // 2. data-m3u8 URL 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: throw Exception("data-m3u8 not found")

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 3. 헤더 설정 (쿠키 포함 필수)
            val userAgent = extractUserAgentFromM3U8Url(m3u8Url)
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red",
                "Cookie" to cookieString
            )

            // 4. M3U8 원본 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 5. 키 URI 찾기 및 변조
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                // 키 다운로드 (JSON 형태)
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text
                
                // 실제 키 해독 (16바이트)
                val actualKeyBytes = decryptKeyFromJson(jsonText)

                if (actualKeyBytes != null) {
                    // (1) 해독된 키를 Base64로 변환하여 Data URI 생성
                    val keyBase64 = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    // 중요: MIME type을 정확히 지정
                    val keyDataUri = "data:application/octet-stream;base64,$keyBase64"

                    // (2) M3U8 내용 수정: JSON URL을 Data URI로 교체
                    m3u8Content = m3u8Content.replace(
                        keyMatch.groupValues[1], 
                        keyDataUri
                    )
                    
                    // (3) 세그먼트 경로 절대 경로화 (Data URI 재생 시 필수)
                    val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                    m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http") && !line.startsWith("data:")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }

                    // (4) 수정된 M3U8을 Data URI로 변환 (base64 인코딩)
                    // 중요: ExtractorLinkType.M3U8을 사용하더라도 data URI는 인식이 잘 안될 수 있으므로
                    // 명시적으로 type을 넘겨주는 것이 좋습니다.
                    val finalM3u8DataUri = "data:application/vnd.apple.mpegurl;base64," + 
                        Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)

                    callback(
                        newExtractorLink(name, name, finalM3u8DataUri, referer, 0, ExtractorLinkType.M3U8) {
                            this.headers = headers
                        }
                    )
                    return 
                }
            }

            // 실패 시 Fallback
            callback(
                newExtractorLink(name, name, m3u8Url, referer, 0, ExtractorLinkType.M3U8) {
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
            // Noise (앞 2바이트) 제거
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
