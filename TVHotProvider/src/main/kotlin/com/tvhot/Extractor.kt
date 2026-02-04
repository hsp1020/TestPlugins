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

    // 브라우저처럼 보이게 하기 위한 헤더
    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36",
        "Accept" to "*/*",
        "Cache-Control" to "no-cache"
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("DEBUG_EXTRACTOR: Start - URL: $url, Referer: $referer")

        // 1. 플레이어 페이지 요청
        val playerResponse = app.get(url, referer = referer, headers = headers).text
        println("DEBUG_EXTRACTOR: Player Response Length: ${playerResponse.length}")

        // m3u8 및 html?token 정규식 (더 유연하게 수정)
        val m3u8Regex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.m3u8[^"' ]*""")
        val htmlRegex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.html\?token=[^"' ]+""")

        // 2. m3u8 주소가 본문에 있는지 확인
        val m3u8Match = m3u8Regex.find(playerResponse)?.value?.replace("\\/", "/")
        if (m3u8Match != null) {
            println("DEBUG_EXTRACTOR: Direct m3u8 Found: $m3u8Match")
            invokeLink(m3u8Match, callback)
            return
        }

        // 3. .html?token= 주소 찾기
        var htmlMatch = htmlRegex.find(playerResponse)?.value?.replace("\\/", "/")
        
        // 만약 상대경로라면 도메인 붙여줌
        if (htmlMatch == null && playerResponse.contains(".html?token=")) {
            val relativeMatch = Regex("""(/[^\s"'<>]+?\.html\?token=[^\s"'<>]+)""").find(playerResponse)
            relativeMatch?.let {
                val domain = "https://" + (Regex("""https?://([^/]+)""").find(url)?.groupValues?.get(1) ?: "")
                htmlMatch = domain + it.value
            }
        }

        if (htmlMatch != null) {
            println("DEBUG_EXTRACTOR: Token HTML Found: $htmlMatch")
            // 2차 요청 (Referer는 1차 플레이어 주소여야 함)
            val finalPageResponse = app.get(htmlMatch!!, referer = url, headers = headers).text
            println("DEBUG_EXTRACTOR: Final Page Response Length: ${finalPageResponse.length}")

            val finalM3u8 = m3u8Regex.find(finalPageResponse)?.value?.replace("\\/", "/")
            if (finalM3u8 != null) {
                println("DEBUG_EXTRACTOR: Final m3u8 Found: $finalM3u8")
                invokeLink(finalM3u8, callback)
            } else {
                println("DEBUG_EXTRACTOR: Failed to find m3u8 in final page")
            }
        } else {
            println("DEBUG_EXTRACTOR: No m3u8 or Token HTML found in player response")
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
