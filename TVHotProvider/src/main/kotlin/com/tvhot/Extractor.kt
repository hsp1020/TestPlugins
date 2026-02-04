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

    // 브라우저처럼 보이기 위한 고정 헤더 (IP 밴 방지 핵심)
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val standardHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Connection" to "keep-alive"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 서버 번호 추출
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 2. 플레이어 페이지 로드 (쿠키 확보를 위해 referer 필수 전달)
        val playerResponse = app.get(
            url, 
            referer = referer, 
            headers = standardHeaders
        )
        val responseText = playerResponse.text
        
        // 3. ID 추출
        val pathRegex = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""")
        val idMatch = pathRegex.find(responseText)?.groupValues?.get(1)
            ?: pathRegex.find(url)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            try {
                // 4. c.html (인증 페이지) 호출 - 세션/쿠키를 굽는 단계
                // 여기서 Referer는 반드시 player.bunny-frame.online/ 이어야 함
                val tokenResponse = app.get(
                    tokenUrl, 
                    referer = url, 
                    headers = standardHeaders
                ).text
                
                val realM3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val realM3u8 = realM3u8Regex.find(tokenResponse)?.groupValues?.get(1)
                    ?: directM3u8
                
                invokeLink(realM3u8, url, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, url, callback)
            }
        } else {
            // Fallback: m3u8 패턴 강제 추출
            val fallbackRegex = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            val fallbackMatch = fallbackRegex.find(responseText)?.value?.replace("\\/", "/")
            
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, url, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                // TS 파일 요청 시에도 동일한 브라우저 헤더를 사용하도록 강제
                // 이 설정이 없으면 재생 도중 IP가 차단됨
                this.headers = standardHeaders.toMutableMap().apply {
                    put("Origin", "https://player.bunny-frame.online")
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
