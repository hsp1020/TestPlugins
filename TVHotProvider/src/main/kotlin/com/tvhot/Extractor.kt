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
        val playerResponse = app.get(url, headers = mapOf("Referer" to "$referer")).text
        
        // 1. m3u8 직접 찾기
        val m3u8Match = Regex("""(https?://[^"']*?poorcdn\.com[^"']*?\.m3u8[^"']*)""").find(playerResponse)
        if (m3u8Match != null) {
            loadExtractor(m3u8Match.value, mainUrl, subtitleCallback, callback)
            return
        }

        // 2. html 토큰 방식 찾기
        val htmlMatch = Regex("""(https?://[^"']*?poorcdn\.com[^"']*?\.html\?token=[^"']*)""").find(playerResponse)
        val htmlUrl = htmlMatch?.value ?: return

        val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to mainUrl)).text
        val finalM3u8Match = Regex("""(https?://.*?\.m3u8.*?)["']""").find(finalResponse)
        
        finalM3u8Match?.groupValues?.get(1)?.let {
            loadExtractor(it, mainUrl, subtitleCallback, callback)
        }
    }
}
