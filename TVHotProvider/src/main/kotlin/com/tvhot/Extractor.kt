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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 서버 번호 추출 (s=5 -> every5)
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 2. 플레이어 페이지 로드
        val response = app.get(url, referer = referer).text
        
        // 3. 모든 경로 탐색 (난독화 대응 정규식)
        // /v/f/ 로 시작하는 모든 32자리 이상의 ID를 가진 경로를 찾음
        val pathRegex = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""")
        val idMatch = pathRegex.find(response)?.groupValues?.get(1)
            ?: pathRegex.find(url)?.groupValues?.get(1) // URL 자체에서도 시도

        if (idMatch != null) {
            println("DEBUG_EXTRACTOR: Found ID: $idMatch")
            
            // BunnyCDN/PoorCDN의 표준 경로 생성
            // 1순위: c.html (인증 페이지)
            // 2순위: index.m3u8 (직접 주소)
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 4. 토큰 페이지 접속 시도
            try {
                val tokenResponse = app.get(tokenUrl, referer = url).text
                // 토큰 페이지 내부에서 진짜 재생 주소 추출
                val realM3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val realM3u8 = realM3u8Regex.find(tokenResponse)?.groupValues?.get(1)
                    ?: directM3u8 // 실패시 직접 주소 사용
                
                invokeLink(realM3u8, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, callback)
            }
        } else {
            // 만약 위 방법으로도 못 찾았다면, 66KB 데이터 전체에서 m3u8 패턴을 강제로 긁어옴
            val fallbackRegex = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            val fallbackMatch = fallbackRegex.find(response)?.value?.replace("\\/", "/")
            
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        println("DEBUG_EXTRACTOR: Final URL: $m3u8Url")
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = "https://player.bunny-frame.online/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
