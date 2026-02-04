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

    // 실제 윈도우 크롬 브라우저와 100% 일치하는 UA 및 핑거프린트
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 로드 시 브라우저 헤더 전송
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = mapOf("User-Agent" to USER_AGENT))
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        // [로그 기반 수정] ID 정규식에 z 및 기타 문자 허용하도록 [a-z0-9]로 확장
        val idRegex = Regex("""(?i)(?:/v/f/|src=)([a-z0-9]{32,})""")
        val idMatch = idRegex.find(responseText)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            try {
                // 2. 인증 페이지 접속 및 쿠키 누적
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val combinedCookies = cookieMap + tokenRes.cookies
                
                val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                    ?: directM3u8
                
                invokeLink(realM3u8, cleanUrl, combinedCookies, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, cleanUrl, cookieMap, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                // [IP 밴 방지 핵심] 재생기 헤더에 쿠키와 브라우저 특성 헤더를 강제 주입
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
