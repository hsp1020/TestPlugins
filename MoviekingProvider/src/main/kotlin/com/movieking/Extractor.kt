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

    // [전략] 서버가 모바일로 인식하도록 최신 모바일 Chrome UA 고정
    private val FORCED_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing] getUrl Start (File Mode) ===")
        
        try {
            // 1. 헤더 설정
            val headers = mutableMapOf(
                "User-Agent" to FORCED_UA,
                "Referer" to "https://player-v1.bcbc.red/",
                "Origin" to "https://player-v1.bcbc.red"
            )

            // 2. 플레이어 페이지 로드
            val playerResponse = try {
                app.get(
                    url,
                    headers = headers,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                app.get(url, headers = headers)
            }

            val playerHtml = playerResponse.text
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            
            // 쿠키 헤더 추가
            headers["Cookie"] = cookieString

            // 3. data-m3u8 추출
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
                ?: run {
                    println("[MovieKing] data-m3u8 not found")
                    return
                }

            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }

            // 4. M3U8 원본 다운로드
            val m3u8Response = app.get(m3u8Url, headers = headers)
            var m3u8Content = m3u8Response.text

            // 5. 키(Key) 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                
                // 키 다운로드
                val keyResponse = app.get(keyUrl, headers = headers)
                // 키 해독
                val actualKeyBytes = decryptKeyFromJson(keyResponse.text)

                if (actualKeyBytes != null) {
                    println("[MovieKing] Key decrypted successfully")
                    
                    // [핵심 변경] 키를 로컬 임시 파일로 저장 (Data URI 사용 불가 회피)
                    val keyFile = File.createTempFile("key_", ".bin")
                    keyFile.writeBytes(actualKeyBytes)
                    keyFile.deleteOnExit() // 앱 종료 시 삭제되도록 설정
                    val keyFileUri = "file://${keyFile.absolutePath}"

                    // M3U8 내용 수정: 키 경로를 로컬 파일 경로로 교체
                    m3u8Content = m3u8Content.replace(keyMatch.groupValues[1], keyFileUri)
                }
            }

            // 6. 세그먼트 경로 절대경로화 (필수)
            // 로컬 파일로 재생할 때 상대 경로(.ts)를 찾지 못하므로 https 주소로 바꿔야 함
            val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
            m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http") && !line.startsWith("file:") && !line.startsWith("data:")) {
                    "$baseUrl$line"
                } else {
                    line
                }
            }

            // 7. 수정된 M3U8을 로컬 파일로 저장
            val playlistFile = File.createTempFile("video_", ".m3u8")
            playlistFile.writeText(m3u8Content)
            playlistFile.deleteOnExit()
            
            val playlistFileUri = "file://${playlistFile.absolutePath}"
            println("[MovieKing] Created local playlist: $playlistFileUri")

            // 8. 재생 요청
            callback(
                newExtractorLink(name, name, playlistFileUri, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    // 플레이어가 세그먼트(https) 요청할 때 쓸 헤더 전달
                    this.headers = headers 
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
            println("[MovieKing] Critical Error: ${e.message}")
        }
    }

    // --- Helper Functions ---

    private fun decryptKeyFromJson(jsonText: String): ByteArray? {
        return try {
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
            resultKey
        } catch (e: Exception) {
            null
        }
    }
}
