package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        // 1. 서버 번호 추출 (기본값 9)
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 2. 플레이어 페이지 접속 (쿠키 및 소스코드 획득)
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val responseText = playerRes.text
        var combinedCookies = playerRes.cookies

        // 3. 비디오 ID 및 경로 추출 (/v/e/ 또는 /v/f/ 패턴)
        val videoPath = extractVideoPath(responseText, cleanUrl)
        
        if (videoPath != null) {
            val (path, id) = videoPath
            
            // c.html은 세션 인증 및 토큰 발급을 위해 필수
            val tokenUrl = "$domain$path$id/c.html"
            val directM3u8 = "$domain$path$id/index.m3u8"

            try {
                // 4. c.html 접속 (여기서 발급되는 쿠키가 .key 파일 접근 권한을 결정함)
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                combinedCookies = combinedCookies + tokenRes.cookies
                
                // 5. 토큰이 포함된 진짜 m3u8 주소 추출 (?h= 토큰 포함 확인)
                val realM3u8 = extractM3u8FromToken(tokenRes.text) ?: directM3u8
                
                // M3U8 주소가 상대 경로일 경우 도메인 결합
                val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                
                invokeLink(finalM3u8, cleanUrl, combinedCookies, callback)
            } catch (e: Exception) {
                // 실패 시 기본 주소로 시도
                invokeLink(directM3u8, cleanUrl, combinedCookies, callback)
            }
        } else {
            // 대체 방법: src 파라미터 직접 사용
            val m3u8FromSrc = extractM3u8FromSrcParam(cleanUrl, domain)
            if (m3u8FromSrc != null) {
                invokeLink(m3u8FromSrc, cleanUrl, combinedCookies, callback)
            }
        }
    }

    private fun extractVideoPath(text: String, url: String): Pair<String, String>? {
        // 패턴 1: HTML 내 주석이나 JS 변수에서 /v/e/ID 또는 /v/f/ID 추출
        val pattern1 = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""")
        val match = pattern1.find(text) ?: pattern1.find(url)
        
        if (match != null) {
            return Pair(match.groupValues[1], match.groupValues[2])
        }

        // 패턴 2: HEX 문자열 파싱 시도 (히트 아일랜드 src 대응)
        // src=0652d7... 형태는 암호화된 값일 가능성이 높으므로 
        // 굳이 디코딩하지 않고 텍스트 전체에서 위 ID 패턴을 찾는 것이 더 안전함.
        
        return null
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        // c.html 응답에서 토큰(?h=)이 붙은 m3u8 주소를 정교하게 추출
        val patterns = listOf(
            Regex("""["']([^"']+\.m3u8\?[^"']+)["']"""), // 토큰 포함
            Regex("""["']([^"']+\.m3u8)["']"""),         // 일반 경로
            Regex("""location\.href\s*=\s*["']([^"']+)["']""") // 리다이렉트 형태
        )
        
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun extractM3u8FromSrcParam(url: String, domain: String): String? {
        val srcParam = Regex("""[?&]src=([^&]+)""").find(url)?.groupValues?.get(1)
        if (srcParam != null) {
            // src가 이미 m3u8 경로인 경우
            if (srcParam.contains(".m3u8")) {
                return if (srcParam.startsWith("http")) srcParam 
                       else if (srcParam.startsWith("/")) "$domain$srcParam"
                       else "$domain/$srcParam"
            }
        }
        return null
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        // 중요: .key 파일 요청 시 세션 쿠키가 반드시 필요하므로 헤더에 포함
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer // 리퍼러 고정
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer) // CDN의 암호화 키 요청 검증용
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
