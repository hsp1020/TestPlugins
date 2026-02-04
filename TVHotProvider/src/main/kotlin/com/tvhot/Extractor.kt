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
        url: String, // 이것이 전체 플레이어 주소 (토큰 포함)
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 서버 번호 추출
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 2. 플레이어 페이지 로드
        // 여기서 referer는 TVHot 사이트 주소가 됨
        val response = app.get(url, referer = referer).text
        
        // 3. 경로 탐색 (ID 추출)
        val pathRegex = Regex("""(?i)(?:/v/f/|src=)([a-f0-9]{32,})""")
        val idMatch = pathRegex.find(response)?.groupValues?.get(1)
            ?: pathRegex.find(url)?.groupValues?.get(1)

        if (idMatch != null) {
            println("DEBUG_EXTRACTOR: Found ID: $idMatch")
            
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 4. 토큰 페이지 접속 시도
            try {
                // 토큰 페이지 접속시에도 referer는 전체 플레이어 URL을 사용
                val tokenResponse = app.get(tokenUrl, referer = url).text
                val realM3u8Regex = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""")
                val realM3u8 = realM3u8Regex.find(tokenResponse)?.groupValues?.get(1)
                    ?: directM3u8 
                
                // [핵심 수정] invokeLink에 url(전체 주소)을 refererUrl로 넘김
                invokeLink(realM3u8, url, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, url, callback)
            }
        } else {
            // ID 못 찾았을 때 fallback
            val fallbackRegex = Regex("""[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            val fallbackMatch = fallbackRegex.find(response)?.value?.replace("\\/", "/")
            
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, url, callback)
            }
        }
    }

    // [핵심 수정] refererUrl을 인자로 받아서 처리
    private suspend fun invokeLink(m3u8Url: String, refererUrl: String, callback: (ExtractorLink) -> Unit) {
        println("DEBUG_EXTRACTOR: Final URL: $m3u8Url")
        println("DEBUG_EXTRACTOR: Using Referer: $refererUrl") // 로그로 확인 가능하게 추가
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // 여기가 문제였음. 고정 주소가 아니라, 파라미터가 포함된 전체 주소를 넣어야 함.
                this.referer = refererUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
