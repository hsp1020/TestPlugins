package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 안드로이드 실기기 로그와 일치하는 브라우저 정보
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: cleanUrl

        // serverNum 추출 로직
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: "9"
        
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속 및 초기 쿠키 생성
        val playerRes = try {
            app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        } catch (e: Exception) {
            return 
        }

        val responseText = playerRes.text
        val initialCookies = playerRes.cookies

        // ID 추출
        val idRegex = Regex("""(?i)(?:/v/f/|src=)([a-z0-9]{32,})""")
        val idMatch = idRegex.find(responseText)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 2. c.html 접속하여 보안 세션 쿠키 업데이트 (재시도 로직)
            var tokenFetched = false
            
            // 최대 5회 재시도 (Exponential Backoff)
            for (attempt in 1..5) {
                try {
                    val tokenRes = app.get(
                        tokenUrl, 
                        referer = cleanUrl, 
                        headers = browserHeaders, 
                        cookies = initialCookies,
                        timeout = 30L // 타임아웃 30초
                    )

                    if (tokenRes.code == 200) {
                        // 성공 시 쿠키 병합 후 링크 생성
                        val finalCookies = initialCookies + tokenRes.cookies
                        
                        // c.html 내부에 m3u8 주소가 변경되었는지 확인
                        val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                            ?: directM3u8
                        
                        invokeLink(realM3u8, cleanUrl, finalCookies, callback)
                        tokenFetched = true
                        break 
                    }
                } catch (e: Exception) {
                    // 실패 시 로그만 남기지 않고 대기 후 재시도
                }
                
                // [빌드 수정] kotlinx.coroutines.delay 대신 Java 표준 Thread.sleep 사용
                // 플러그인 환경에서 라이브러리 의존성 문제를 피하기 위함
                try {
                    Thread.sleep(1000L * attempt)
                } catch (e: InterruptedException) {
                    // Ignore
                }
            }
        }
    }

    private fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        
        val finalHeaders = browserHeaders.toMutableMap().apply {
            if (cookieString.isNotEmpty()) put("Cookie", cookieString)
            put("Referer", referer)
        }

        // [빌드 수정] 생성자에 headers를 직접 넣지 않고 .apply 블록에서 설정
        // Prerelease/Stable 버전 호환성 문제 해결
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                referer = referer,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8
            ).apply {
                this.headers = finalHeaders
            }
        )
    }
}
