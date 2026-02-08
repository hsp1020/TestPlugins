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

    // [전략] 추정 금지: 최신 안드로이드 크롬 UA를 하나 정해서 모든 곳에 강제 적용
    private val FORCED_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("=== [MovieKing] getUrl Start ===")
        println("[MovieKing] Target URL: $url")
        
        try {
            // 1. 헤더 설정
            val headers = mutableMapOf(
                "User-Agent" to FORCED_UA,
                "Referer" to mainUrl
            )
            println("[MovieKing] Step 1: Headers prepared. UA=${FORCED_UA.take(30)}...")

            // 2. 플레이어 페이지 로드 (WebViewResolver)
            println("[MovieKing] Step 2: Requesting Player Page with WebViewResolver...")
            val playerResponse = try {
                app.get(
                    url,
                    headers = headers,
                    interceptor = WebViewResolver(Regex("""player-v1\.bcbc\.red"""))
                )
            } catch (e: Exception) {
                println("[MovieKing] Step 2 Failed (WebView): ${e.message}")
                println("[MovieKing] Trying Fallback (Normal Request)...")
                app.get(url, headers = headers)
            }

            if (playerResponse.code != 200) {
                println("[MovieKing] Step 2 Failed: Response Code ${playerResponse.code}")
                return
            }
            println("[MovieKing] Step 2 Success: Page Loaded (${playerResponse.text.length} chars)")

            // 쿠키 확인
            val cookies = playerResponse.cookies
            val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            println("[MovieKing] Cookies obtained: ${if (cookieString.isNotBlank()) "Yes" else "No"}")
            
            // 헤더에 쿠키 추가 (이후 요청에 필수)
            headers["Cookie"] = cookieString

            // 3. data-m3u8 추출
            val playerHtml = playerResponse.text
            val m3u8Match = Regex("""data-m3u8\s*=\s*['"]([^'"]+)['"]""").find(playerHtml)
            
            if (m3u8Match == null) {
                println("[MovieKing] Step 3 Failed: data-m3u8 regex mismatch")
                return
            }
            
            var m3u8Url = m3u8Match.groupValues[1].replace("\\/", "/")
            if (m3u8Url.startsWith("//")) {
                m3u8Url = "https:$m3u8Url"
            } else if (!m3u8Url.startsWith("http")) {
                m3u8Url = "https://$m3u8Url"
            }
            println("[MovieKing] Step 3 Success: Found M3U8 URL -> $m3u8Url")

            // 4. M3U8 다운로드
            println("[MovieKing] Step 4: Downloading M3U8...")
            // Referer를 플레이어 주소로 명시
            headers["Referer"] = "https://player-v1.bcbc.red/"
            headers["Origin"] = "https://player-v1.bcbc.red"

            val m3u8Response = app.get(m3u8Url, headers = headers)
            if (m3u8Response.code != 200) {
                println("[MovieKing] Step 4 Failed: HTTP ${m3u8Response.code}")
                return
            }
            var m3u8Content = m3u8Response.text
            println("[MovieKing] Step 4 Success: M3U8 Downloaded")

            // 5. 키(Key) 확인 및 처리
            val keyUriRegex = """#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""".toRegex()
            val keyMatch = keyUriRegex.find(m3u8Content)

            if (keyMatch != null) {
                val keyUrl = keyMatch.groupValues[1]
                println("[MovieKing] Step 5: Encrypted Key Found -> $keyUrl")
                
                // 키 다운로드
                println("[MovieKing] Step 5-1: Downloading Key JSON...")
                val keyResponse = app.get(keyUrl, headers = headers)
                if (keyResponse.code != 200) {
                    println("[MovieKing] Step 5-1 Failed: HTTP ${keyResponse.code}")
                    return
                }
                
                // 키 해독
                println("[MovieKing] Step 5-2: Decrypting Key...")
                val actualKeyBytes = decryptKeyFromJson(keyResponse.text)
                
                if (actualKeyBytes != null) {
                    println("[MovieKing] Step 5-2 Success: Key Decrypted (${actualKeyBytes.size} bytes)")
                    
                    // Data URI 변환
                    val keyBase64 = Base64.encodeToString(actualKeyBytes, Base64.NO_WRAP)
                    val keyDataUri = "data:application/octet-stream;base64,$keyBase64"

                    // M3U8 내용 수정 (키 주소 교체)
                    m3u8Content = m3u8Content.replace(keyUrl, keyDataUri)
                    
                    // 세그먼트 주소 절대경로화
                    val baseUrl = m3u8Url.substringBeforeLast("/") + "/"
                    m3u8Content = m3u8Content.lines().joinToString("\n") { line ->
                        if (line.isNotBlank() && !line.startsWith("#") && !line.startsWith("http") && !line.startsWith("data:")) {
                            "$baseUrl$line"
                        } else {
                            line
                        }
                    }
                    println("[MovieKing] Step 5-3: M3U8 Modified (Key replaced & Paths fixed)")

                    // 최종 Data URI 생성
                    val finalM3u8DataUri = "data:application/vnd.apple.mpegurl;base64," + 
                        Base64.encodeToString(m3u8Content.toByteArray(), Base64.NO_WRAP)

                    // 6. 재생 요청
                    println("[MovieKing] Step 6: Callback with modified Data URI")
                    callback(
                        newExtractorLink(name, name, finalM3u8DataUri, ExtractorLinkType.M3U8) {
                            this.referer = "https://player-v1.bcbc.red/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers // 중요: UA와 쿠키가 포함된 헤더 전달
                        }
                    )
                    return
                } else {
                    println("[MovieKing] Step 5-2 Failed: Decryption returned null")
                    // 복호화 실패해도 일단 진행해볼지 결정 (여기선 중단하지 않고 Fallback으로 넘길 수도 있음)
                }
            } else {
                println("[MovieKing] Step 5: No Encryption Key found (Cleartext?)")
            }

            // Fallback (키가 없거나 복호화 실패 시 원본 링크 사용)
            println("[MovieKing] Step 6: Callback with Original URL (Fallback)")
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player-v1.bcbc.red/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )

        } catch (e: Exception) {
            println("[MovieKing] CRITICAL ERROR: ${e.message}")
            e.printStackTrace()
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
            // 로그로 JSON 구조 확인 (너무 길면 잘라서)
            // println("[MovieKing] Key JSON: ${decodedJsonStr.take(50)}...")

            val encKeyRegex = """"encrypted_key"\s*:\s*"([^"]+)"""".toRegex()
            val encKeyB64 = encKeyRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null
            
            val ruleRegex = """"rule"\s*:\s*(\{.*?\})""".toRegex()
            val ruleJson = ruleRegex.find(decodedJsonStr)?.groupValues?.get(1) ?: return null

            val encryptedBytes = Base64.decode(encKeyB64, Base64.DEFAULT)
            // 앞 2바이트 제거
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
            println("[MovieKing] Decrypt Error: ${e.message}")
            null
        }
    }
}
