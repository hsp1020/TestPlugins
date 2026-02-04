package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 안드로이드 실기기 로그와 일치하는 브라우저 정보 (IP 밴 방지 핵심)
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
        // 공백 및 줄바꿈 완전 제거
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        // serverNum 추출 로직
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: "9"
        
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속 및 초기 쿠키 생성
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        // ID 추출
        val idRegex = Regex("""(?i)(?:/v/f/|src=)([a-z0-9]{32,})""")
        val idMatch = idRegex.find(responseText)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 2. c.html 접속 (재시도 로직 적용)
            var attempt = 0
            val maxRetries = 3
            var success = false

            while (attempt < maxRetries && !success) {
                try {
                    // 타임아웃 15초 설정
                    val tokenRes = app.get(
                        tokenUrl, 
                        referer = cleanUrl, 
                        headers = browserHeaders,
                        timeout = 15L 
                    )

                    // 성공 시 쿠키 병합
                    val combinedCookies = cookieMap + tokenRes.cookies
                    
                    // 실제 m3u8 주소 추출
                    val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                        ?: directM3u8
                    
                    // 최종 링크 실행 (쿠키 포함 필수)
                    invokeLink(realM3u8, cleanUrl, combinedCookies, callback)
                    success = true // 성공 시 루프 종료

                } catch (e: Exception) {
                    attempt++
                    if (attempt >= maxRetries) {
                        // 3회 실패 시 예외 던짐 (절대 쿠키 없는 요청으로 넘어가지 않음)
                        throw e 
                    }
                    try {
                        Thread.sleep(1000L) // 1초 대기 후 재시도
                    } catch (e: InterruptedException) {
                        // Ignore
                    }
                }
            }
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

        // ExtractorLink 생성 (표준 생성자 사용)
        callback.invoke(
            newExtractorLink(
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
