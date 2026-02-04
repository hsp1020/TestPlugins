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
        val response = app.get(url, referer = referer).text
        
        // 서버 번호(s=7 등) 추출하여 기본 도메인 생성
        val sNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$sNum.poorcdn.com"

        // 2. 따옴표 내부의 모든 문자열을 추출 (JSON/JS 대응)
        val allStrings = Regex("""["']([^"']+)["']""").findAll(response).map { it.groupValues[1] }.toList()

        // 3. m3u8 주소 찾기
        val m3u8Path = allStrings.find { it.contains("index.m3u8") }?.replace("\\/", "/")
        if (m3u8Path != null) {
            val finalM3u8 = if (m3u8Path.startsWith("http")) m3u8Path else domain + (if (m3u8Path.startsWith("/")) "" else "/") + m3u8Path
            invokeLink(finalM3u8, callback)
            return
        }

        // 4. Token HTML 찾기
        val tokenPath = allStrings.find { it.contains(".html?token=") }?.replace("\\/", "/")
        if (tokenPath != null) {
            val finalHtmlUrl = if (tokenPath.startsWith("http")) tokenPath else domain + (if (tokenPath.startsWith("/")) "" else "/") + tokenPath
            
            // 토큰 페이지 접속 (Referer 필수)
            val tokenPageResponse = app.get(finalHtmlUrl, referer = url).text
            
            // 토큰 페이지 안에서 다시 m3u8 찾기
            val finalM3u8 = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenPageResponse)
                ?.groupValues?.get(1)?.replace("\\/", "/")

            finalM3u8?.let {
                val absoluteM3u8 = if (it.startsWith("http")) it else domain + (if (it.startsWith("/")) "" else "/") + it
                invokeLink(absoluteM3u8, callback)
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
                // 중요: 403 Forbidden 에러 방지를 위해 Referer를 고정함
                this.referer = "https://player.bunny-frame.online/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
