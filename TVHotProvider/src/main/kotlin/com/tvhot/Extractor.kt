package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerResponse = app.get(url, headers = mapOf("Referer" to "$referer")).text

        var regex = Regex("""(https?://[^"']*?poorcdn\.com[^"']*?\.m3u8[^"']*)""")
        var match = regex.find(playerResponse)

        if (match == null) {
            regex = Regex("""(https?://[^"']*?poorcdn\.com[^"']*?\.html\?token=[^"']*)""")
            match = regex.find(playerResponse)
        }

        if (match != null) {
            var streamUrl = match.value
            
            if (streamUrl.contains(".html")) {
                val finalResponse = app.get(
                    streamUrl, 
                    headers = mapOf("Referer" to mainUrl)
                ).text
                
                val m3u8Match = Regex("""(https?://.*?\.m3u8.*?)["']""").find(finalResponse)
                if (m3u8Match != null) {
                    streamUrl = m3u8Match.groupValues[1]
                }
            }

            M3u8Helper.generateM3u8(
                source = "TVHot",
                streamUrl = streamUrl,
                referer = mainUrl
            ).forEach(callback)
            
            return true
        }
        return false
    }
}
