package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
// 코루틴 딜레이 함수 임포트 (빌드 에러 해결)
import kotlinx.coroutines.delay 

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 안드로이드 실기기 로그와 일치하는 브라우저 정보 (IP 밴 방지)
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
            // 쿠키 없이 요청하면 403이 뜨므로, 성공할 때까지 재시도함.
            var tokenFetched = false
            
            // 최대 5회 재시도 (Exponential Backoff)
            for (attempt in 1..5) {
                try {
                    val tokenRes = app.get(
                        tokenUrl, 
                        referer = cleanUrl, 
                        headers = browserHeaders, 
                        cookies = initialCookies,
                        timeout = 30L // 타임아웃을 30초로 늘림
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
                
                // 실패 시 대기: 1초, 2초... 늘려감 (서버 부하 방지)
                delay(1000L * attempt)
            }
            
            // 5번 시도 후에도 실패한 경우, 쿠키 없는 요청은 403이 확실하므로 보내지 않거나 에러 처리
        }
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        
        // 헤더 맵 생성
        val finalHeaders = browserHeaders.toMutableMap().apply {
            if (cookieString.isNotEmpty()) put("Cookie", cookieString)
            put("Referer", referer)
        }

        // 빌드 에러 해결: 생성자를 직접 사용하여 객체 생성
        callback.invoke(
            ExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                referer = referer,
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = finalHeaders
            )
        )
    }
}
