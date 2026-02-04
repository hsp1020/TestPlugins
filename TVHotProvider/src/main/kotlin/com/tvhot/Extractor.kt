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
        // 1. 플레이어 페이지 요청 (Referer: 에피소드 페이지 주소)
        val playerResponse = app.get(url, referer = referer).text

        // m3u8 주소 매칭용 정규식 (이스케이프된 \/ 포함 대응)
        val m3u8Regex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.m3u8[^"' ]*""")
        
        // 2. m3u8 주소가 소스에 바로 있는지 확인
        val m3u8Match = m3u8Regex.find(playerResponse)?.value?.replace("\\/", "/")
        if (m3u8Match != null) {
            invokeLink(m3u8Match, callback)
            return
        }

        // 3. 사용자가 확인한 .html?token=... 형태의 주소 추출
        // 도메인을 특정하지 않고 확장자와 파라미터로 검색
        val htmlRegex = Regex("""https?[:\\]+[/\\/]+[^"' ]+?\.html\?token=[^"' ]+""")
        val htmlMatch = htmlRegex.find(playerResponse)?.value?.replace("\\/", "/")

        if (htmlMatch != null) {
            // .html?token= 주소로 2차 요청 (Referer: 1차 플레이어 주소)
            val finalPageResponse = app.get(htmlMatch, referer = url).text
            
            // 최종 페이지 본문에서 진짜 m3u8 주소 추출
            val finalM3u8 = m3u8Regex.find(finalPageResponse)?.value?.replace("\\/", "/")
            
            finalM3u8?.let { realLink ->
                invokeLink(realLink, callback)
            }
        }
    }

    private fun invokeLink(m3u8Url: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // 이 계열 서버는 플레이어 도메인을 Referer로 주지 않으면 403 에러 발생 가능성 높음
                this.referer = "https://player.bunny-frame.online/"
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
