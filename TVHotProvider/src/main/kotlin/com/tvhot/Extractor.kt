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

        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val cookieMap = playerRes.cookies.toMutableMap()

        // 2. 비디오 경로 추출 (패턴 강화)
        val videoPath = extractVideoPath(playerRes.text, cleanUrl)
        
        if (videoPath != null) {
            val (path, id) = videoPath
            val tokenUrl = "$domain$path$id/c.html"
            val directM3u8 = "$domain$path$id/index.m3u8"

            try {
                // 3. c.html 접속 (Referer를 플레이어 URL로 고정)
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                
                // 헤더 쿠키 추가
                cookieMap.putAll(tokenRes.cookies)
                
                // [핵심] 자바스크립트로 구워지는 쿠키 파싱 (document.cookie)
                val jsCookieRegex = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                jsCookieRegex.findAll(tokenRes.text).forEach { match ->
                    cookieMap[match.groupValues[1]] = match.groupValues[2]
                }

                // 4. 토큰(?h=) 주소 추출
                val realM3u8 = extractM3u8FromToken(tokenRes.text) ?: directM3u8
                val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                
                invokeLink(finalM3u8, cleanUrl, cookieMap, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, cleanUrl, cookieMap, callback)
            }
        }
    }

    private fun extractVideoPath(text: String, url: String): Pair<String, String>? {
        // 주석이나 스크립트 내의 경로 추출 (/v/e/ID 또는 /v/f/ID)
        val pattern = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""")
        val match = pattern.find(text) ?: pattern.find(url)
        return match?.let { Pair(it.groupValues[1], it.groupValues[2]) }
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        val patterns = listOf(
            Regex("""["']([^"']+\.m3u8\?[^"']+)["']"""), // 토큰 포함 주소
            Regex("""["']([^"']+\.m3u8)["']"""),         // 일반 주소
            Regex("""location\.href\s*=\s*["']([^"']+)["']""")
        )
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        // 모든 쿠키를 세미콜론으로 연결
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) {
                        put("Cookie", cookieString)
                    }
                    put("Referer", referer) // 키 요청 시 인증용으로 필수
                }
            }
        )
    }
}
