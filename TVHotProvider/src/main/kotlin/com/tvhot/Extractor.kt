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
    
    private val standardHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
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

        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속 및 초기 쿠키 획득
        val playerResponse = app.get(cleanUrl, referer = cleanReferer, headers = standardHeaders)
        val responseText = playerResponse.text
        val cookies = playerResponse.cookies.toMutableMap()
        
        val idMatch = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""").find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            try {
                // 2. c.html 접속하여 세션 쿠키 업데이트 (404 방지의 핵심)
                val tokenResponse = app.get(tokenUrl, referer = cleanUrl, headers = standardHeaders)
                cookies.putAll(tokenResponse.cookies)
                
                val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenResponse.text)?.groupValues?.get(1)
                    ?: directM3u8
                
                invokeLink(realM3u8, cleanUrl, cookies, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, cleanUrl, cookies, callback)
            }
        } else {
            val fallbackMatch = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""").find(responseText)?.value?.replace("\\/", "/")
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, cleanUrl, cookies, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        // 쿠키 문자열 생성
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = standardHeaders.toMutableMap().apply {
                    put("Origin", "https://player.bunny-frame.online")
                    // 수동으로 추출한 쿠키를 헤더에 주입 (404 Not Found 해결책)
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
