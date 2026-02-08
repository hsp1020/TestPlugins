package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
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
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Cookie" to cookieString
            )

            // 4. M3U8 원본 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"

            // 5. [핵심] 키 URI 찾아서 실제 키로 교체
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                
                // 키 JSON 다운로드
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text // 여기서 220byte JSON이 들어옴

                // JSON 파싱 및 복호화
                val actualKeyBytes = decryptKeyFromJson(jsonText)
                
                if (actualKeyBytes != null) {
                    // 실제 키를 Base64 Data URI로 변환
                    val b64Key = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    val newKeyLine = """#EXT-X-KEY:METHOD=AES-128,URI="data:text/plain;base64,$b64Key""""
                    
                    // M3U8 내용 수정 (원본 Key URI -> Data URI)
                    m3u8Content = m3u8Content.replace(keyMatch.value, newKeyLine)
                    println("[MovieKing] Key replaced successfully.")
                }
            }

            // 6. [핵심] 세그먼트(.ts) 경로 절대경로화
            // M3U8 전체를 Data URI로 만들 것이므로 상대경로는 깨짐. 따라서 절대경로로 수정해야 함.
            val newM3u8Lines = m3u8Content.lines().map { line ->
                val trimLine = line.trim()
                if (trimLine.isNotEmpty() && !trimLine.startsWith("#")) {
                    // http로 시작하지 않는 세그먼트 URL 앞에 baseUrl 붙이기
                    if (!trimLine.startsWith("http")) {
                        try {
                            URI(baseUrl).resolve(trimLine).toString()
                        } catch (e: Exception) {
                            "$baseUrl$trimLine"
                        }
                    } else {
                        trimLine
                    }
                } else {
                    line
                }
            }
            val finalM3u8Content = newM3u8Lines.joinToString("\n")

            // 7. 수정된 M3U8 전체를 Data URI로 변환하여 전달
            // 이렇게 하면 ExoPlayer는 서버에 접속 안 하고 이 문자열을 재생 목록으로 씀
            val base64M3u8 = Base64.encodeToString(finalM3u8Content.toByteArray(), Base64.NO_WRAP)
            val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64M3u8"

            // 8. newExtractorLink 사용 (예시 코드 참고)
            callback(
                newExtractorLink(name, name, dataUri, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = 0 // Unknown
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            // 에러 시 로그만 남김 (Cloudstream 특성상 에러 throw하면 전체가 죽을 수 있음)
            println("[MovieKing] Error: ${e.message}")
        }
    }

    // --- Helper Functions ---

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        try {
            // 1. JSON 자체가 Base64일 수 있으므로 디코딩 시도 (로그 기반 추론)
            // 로그: Raw key response: eyJlbmNyeXB0... (Base64된 JSON)
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
