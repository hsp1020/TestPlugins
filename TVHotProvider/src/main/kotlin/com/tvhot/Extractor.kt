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

    // 실제 최신 크롬 브라우저와 동일한 UA 사용
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    private val playHeaders = mapOf(
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
        
        // 1. 플레이어 페이지 접속하여 쿠키 확보
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = mapOf("User-Agent" to USER_AGENT))
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        val idMatch = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""").find(responseText)?.groupValues?.get(1)
            ?: Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""").find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            try {
                // 2. c.html 접속 (여기서 세션 쿠키가 완성됨)
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = mapOf("User-Agent" to USER_AGENT))
                val finalCookies = cookieMap + tokenRes.cookies // 쿠키 합치기
                
                val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                    ?: directM3u8
                
                invokeLink(realM3u8, cleanUrl, finalCookies, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, cleanUrl, cookieMap, callback)
            }
        } else {
            val fallbackMatch = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""").find(responseText)?.value?.replace("\\/", "/")
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, cleanUrl, cookieMap, callback)
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
                // 모든 요청 헤더를 브라우저와 동일하게 설정하여 IP 차단 우회
                this.headers = playHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
