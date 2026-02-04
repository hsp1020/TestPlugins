package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay

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
        // 공백 및 줄바꿈 완전 제거 (로그 에러 0x0d 해결)
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        // 서버 번호 추출
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: "9"
        
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속 및 쿠키 생성
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        // ID 추출 (a-z 포함 정규식)
        val idRegex = Regex("""(?i)(?:/v/f/|src=)([a-z0-9]{32,})""")
        val idMatch = idRegex.find(responseText)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // c.html 접속 시도 (재시도 로직 포함)
            var tokenRes: Response? = null
            var lastException: Exception? = null
            
            // 최대 3회 재시도
            repeat(3) { attempt ->
                try {
                    tokenRes = app.get(
                        tokenUrl, 
                        referer = cleanUrl, 
                        headers = browserHeaders,
                        timeout = 30_000 // 30초 타임아웃
                    )
                    return@repeat // 성공 시 루프 종료
                } catch (e: Exception) {
                    lastException = e
                    if (attempt < 2) {
                        delay(1000L * (attempt + 1)) // 1초, 2초 지연
                    }
                }
            }

            // c.html 접속 실패 시 에러 처리
            if (tokenRes == null) {
                throw Exception("Failed to access c.html after 3 attempts: ${lastException?.message}")
            }

            // c.html 성공 시 쿠키 병합
            val combinedCookies = cookieMap + tokenRes!!.cookies
            
            // m3u8 URL 추출
            val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                .find(tokenRes.text)?.groupValues?.get(1)
                ?: directM3u8
            
            invokeLink(realM3u8, cleanUrl, combinedCookies, callback)
        } else {
            throw Exception("Video ID not found in response")
        }
    }

    private suspend fun invokeLink(
        m3u8Url: String, 
        referer: String, 
        cookies: Map<String, String>, 
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                // Referer와 헤더 설정
                this.referer = referer
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
