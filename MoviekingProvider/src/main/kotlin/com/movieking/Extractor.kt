package com.movieking

import android.util.Base64
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import java.io.File
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
            // 1. WebViewResolver로 플레이어 페이지 로드
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
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 3. User-Agent 및 헤더 설정
            val userAgent = extractUserAgentFromM3U8Url(m3u8Url)
                ?: "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            
            val cookieString = playerResponse.cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            val headers = mutableMapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://player-v1.bcbc.red",
                "Origin" to "https://player-v1.bcbc.red",
                "Cookie" to cookieString
            )

            // 4. M3U8 원본 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 5. 키 URI 찾기
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            // 6. 키 복호화 및 로컬 M3U8 생성 (File URI 방식)
            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                val keyResponse = app.get(keyUrl, headers = headers)
                val jsonText = keyResponse.text
                
                // 실제 키 해독 (16바이트)
                val actualKeyBytes = decryptKeyFromJson(jsonText)

                if (actualKeyBytes != null) {
                    // (1) 해독된 키를 Base64로 변환하여 Data URI 생성 (키는 Data URI 사용 가능)
                    val keyBase64 = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    val keyDataUri = "data:application/octet-stream;base64,$keyBase64"

                    // (2) M3U8 내용 수정: JSON URL을 실제 키 값을 담은 Data URI로 교체
                    m3u8Content = m3u8Content.replace(
                        keyMatch.groupValues[1], // 원래 URI
                        keyDataUri               // 교체할 Data URI
                    )
                    
                    // (3) M3U8 내용 수정: 상대 경로 세그먼트를 절대 경로(https://...)로 변환
                    // 로컬 파일 재생 시 상대 경로는 file://로 인식되므로 반드시 HTTP 절대 경로로 바꿔야 함
                    val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                    
                    m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                        // 주석(#)이 아니고 http로 시작하지 않는 줄(파일명) 앞에 baseUrl 붙이기
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }

                    // (4) 수정된 M3U8을 임시 파일로 저장하여 file:// URI 생성
                    try {
                        val tempFile = File.createTempFile("movieking_playlist", ".m3u8")
                        tempFile.writeText(m3u8Content)
                        val fileUrl = "file://${tempFile.absolutePath}"

                        callback(
                            newExtractorLink(name, name, fileUrl, ExtractorLinkType.M3U8) {
                                this.referer = url
                                this.quality = 0
                                // 로컬 파일 재생이지만 세그먼트는 원격이므로 헤더 유지
                                this.headers = headers 
                            }
                        )
                        return // 성공적으로 처리했으므로 종료
                    } catch (e: Exception) {
                        e.printStackTrace()
                        // 파일 생성 실패 시 로그 출력 후 Fallback 시도
                        println("[MovieKing] Temp file creation failed: ${e.message}")
                    }
                }
            }

            // 7. 키가 없거나 복호화/파일생성 실패 시 원본 URL 사용 (Fallback)
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = 0 
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
