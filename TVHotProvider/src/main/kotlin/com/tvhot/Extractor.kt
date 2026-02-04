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
        val playerResponse = app.get(url, referer = referer).text
        
        // 1. 모든 형태의 .m3u8 주소 추출 (상대경로 포함)
        val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
        val m3u8Match = m3u8Regex.find(playerResponse)?.groupValues?.get(1)?.replace("\\/", "/")
        
        if (m3u8Match != null) {
            val finalM3u8 = if (m3u8Match.startsWith("http")) m3u8Match else "https://every9.poorcdn.com" + m3u8Match
            invokeLink(finalM3u8, callback)
            return
        }

        // 2. 모든 형태의 .html?token= 주소 추출 (따옴표 사이의 값을 가져옴)
        val htmlTokenRegex = Regex("""["']([^"']+\.html\?token=[^"']+)["']""")
        val htmlMatch = htmlTokenRegex.find(playerResponse)?.groupValues?.get(1)?.replace("\\/", "/")

        if (htmlMatch != null) {
            // 상대 경로인 경우 기본 도메인(every9.poorcdn.com)을 붙여줌
            val finalHtmlUrl = if (htmlMatch.startsWith("http")) {
                htmlMatch
            } else {
                "https://every9.poorcdn.com" + if (htmlMatch.startsWith("/")) htmlMatch else "/$htmlMatch"
            }

            println("DEBUG_EXTRACTOR: Requesting Token URL: $finalHtmlUrl")
            
            val finalPageResponse = app.get(finalHtmlUrl, referer = url).text
            val finalM3u8Match = m3u8Regex.find(finalPageResponse)?.groupValues?.get(1)?.replace("\\/", "/")
            
            finalM3u8Match?.let {
                val absoluteM3u8 = if (it.startsWith("http")) it else "https://every9.poorcdn.com" + it
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
                this.referer = "https://player.bunny-frame.online/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
