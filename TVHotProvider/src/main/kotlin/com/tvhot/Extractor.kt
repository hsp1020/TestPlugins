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
        // 1. 플레이어 페이지 요청 (Referer 필수)
        val response = app.get(url, referer = referer).text

        // 2. m3u8 직접 찾기 (도메인 제약 제거)
        // 역슬래시 이스케이프(\/) 처리된 URL까지 고려한 정규식
        val m3u8Regex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.m3u8[^"' ]*""")
        val m3u8Match = m3u8Regex.find(response)?.value?.replace("\\/", "/")

        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 3. .html?token=... 형태 찾기 (도메인 제약 제거)
        val htmlRegex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.html\?token=[^"' ]*""")
        val htmlUrl = htmlRegex.find(response)?.value?.replace("\\/", "/")

        if (htmlUrl != null) {
            val finalResponse = app.get(htmlUrl, referer = url).text
            val finalM3u8 = m3u8Regex.find(finalResponse)?.value?.replace("\\/", "/")
            
            finalM3u8?.let { link ->
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = link,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        }
    }
}
