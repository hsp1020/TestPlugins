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
        // 1. 플레이어 페이지 로드
        val playerResponse = app.get(url, referer = referer).text
        
        // 서버 번호(s=5 등)를 추출하여 도메인 생성 (every5.poorcdn.com 형태)
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"

        // 2. 상대 경로(/v/f/...) 또는 절대 경로(http...) 추출 정규식
        // 따옴표 안에 있는 /v/f/ 로 시작하는 모든 문자열을 찾음
        val pathRegex = Regex("""["']((?:https?://[^"']*)?/v/f/[^"']+(?:\.m3u8|\.html\?token=)[^"']*)["']""")
        
        val matches = pathRegex.findAll(playerResponse).map { it.groupValues[1].replace("\\/", "/") }.toList()

        // 3. m3u8 우선 탐색
        val m3u8Path = matches.find { it.contains(".m3u8") }
        if (m3u8Path != null) {
            val finalUrl = if (m3u8Path.startsWith("http")) m3u8Path else domain + m3u8Path
            invokeLink(finalUrl, callback)
            return
        }

        // 4. Token HTML 탐색 (.html?token=)
        val tokenPath = matches.find { it.contains("token=") }
        if (tokenPath != null) {
            val tokenUrl = if (tokenPath.startsWith("http")) tokenPath else domain + (if (tokenPath.startsWith("/")) "" else "/") + tokenPath
            
            // 토큰 페이지 접속해서 진짜 m3u8 찾기
            val tokenPageResponse = app.get(tokenUrl, referer = url).text
            val finalM3u8Match = pathRegex.find(tokenPageResponse)?.groupValues?.get(1)?.replace("\\/", "/")
            
            finalM3u8Match?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                invokeLink(finalUrl, callback)
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
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
