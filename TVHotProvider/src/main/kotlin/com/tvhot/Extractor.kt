package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor

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
        val response = app.get(url, headers = mapOf("Referer" to "$referer")).text
        
        // 1. 직접 m3u8 찾기 시도
        val m3u8Regex = Regex("""(https?://[^"']*?\.m3u8[^"']*)""")
        val m3u8Match = m3u8Regex.find(response)
        
        if (m3u8Match != null) {
            callback(
                ExtractorLink(
                    source = name,
                    name = "BunnyPoorCdn",
                    url = m3u8Match.value,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return
        }
        
        // 2. 기존 로직
        val htmlMatch = Regex("""(https?://[^"']*?poorcdn\.com[^"']*?\.html\?token=[^"']*)""").find(response)
        val htmlUrl = htmlMatch?.value ?: return

        val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to mainUrl)).text
        val finalM3u8Match = Regex("""(https?://.*?\.m3u8.*?)["']""").find(finalResponse)
        
        finalM3u8Match?.groupValues?.get(1)?.let {
            loadExtractor(it, mainUrl, subtitleCallback, callback)
        }
    }
}
