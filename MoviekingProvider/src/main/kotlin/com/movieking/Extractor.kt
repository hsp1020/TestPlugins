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
            // 1. 플레이어 페이지 로드 (WebViewResolver 시도 후 실패 시 일반 요청)
            val playerResponse = try {
                app.get(
                    url,
                    referer = referer, // 사이트에서 넘겨준 Referer 사용
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                // 타임아웃/오류 발생 시 일반 요청으로 재시도
                app.get(url, referer = referer)
            }
            
            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies

            // 2. data-m3u8 주소 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: return // 못 찾으면 조용히 종료 (에러 로그 방지)

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 3. 토큰에서 User-Agent 자동 추출 (중요: 토큰에 박힌 값과 일치시켜야 함)
            // 폰에서 실행하면 폰의 UA가, PC면 PC의 UA가 자동으로 추출됨
            val extractedUA = extractUserAgentFromToken(m3u8Url)
            
            // 추출 실패 시 기본값 (안전빵)
            val userAgent = extractedUA ?: "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

            // 4. 헤더 설정 (비디오 서버에 맞게 조정)
            val videoHost = URI(m3u8Url).host
            val origin = "https://$videoHost"
            
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "$origin/",
                "Origin" to origin,
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
                // 키 파일(JSON) 다운로드
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text
                
                // JSON에서 실제 16바이트 키 해독
                val actualKeyBytes = decryptKeyFromJson(jsonText)

                if (actualKeyBytes != null) {
                    // (1) 해독된 키를 Data URI로 변환
                    val keyBase64 = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    val keyDataUri = "data:application/octet-stream;base64,$keyBase64"

                    // (2) M3U8 내용 수정: 원본 키 주소를 Data URI로 교체
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

                    // (4) 수정된 M3U8을 Data URI로 변환
                    val finalM3u8DataUri = "data:application/vnd.apple.mpegurl;base64," + 
                        Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)

                    // (5) 재생 요청 (빌드 에러 수정: 인자 순서 및 블록 처리)
                    callback(
                        newExtractorLink(name, name, finalM3u8DataUri, ExtractorLinkType.M3U8) {
                            this.referer = "$origin/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return 
                }
            }

            // 키가 없거나 평문인 경우 (Fallback)
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "$origin/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // --- 내부 함수들 ---

    // JSON 형태의 키 파일을 해독하여 실제 16바이트 키를 반환
    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        try {
            // JSON이 Base64로 감싸져 있을 경우 처리
            val decodedJsonStr = try {
                String(Base64.decode(jsonText, Base64.DEFAULT))
            } catch (e: Exception) {
                jsonText 
            }

            // 정규식으로 필요한 정보 추출
            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            // 암호화된 키 디코딩 및 노이즈 제거
            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            val cleanBytes = encryptedBytes.drop(2).toByteArray() // 앞 2바이트 제거

            // 셔플 규칙(permutation) 파싱
            val permRegex = """"permutation"\s*:\s*\[([\d,]+)\]""".toRegex()
            val permString = permRegex.find(ruleJson)?.groupValues?.get(1) ?: "0,1,2,3"
            val permutation = permString.split(",").map { it.trim().toInt() }

            // 키 재조립 (4바이트씩 4덩어리)
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

    // 토큰 내부에 숨겨진 User-Agent 값을 그대로 추출
    private fun extractUserAgentFromToken(m3u8Url: String): String? {
        return try {
            val token = m3u8Url.substringAfterLast("/").split(".").getOrNull(1) ?: return null
            val payload = String(Base64.decode(token, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val uaMatch = Regex(""""ua"\s*:\s*"([^"]+)"""").find(payload)
            uaMatch?.groupValues?.get(1) // Chrome(xxx) 형태 그대로 반환
        } catch (e: Exception) {
            null
        }
    }
}
