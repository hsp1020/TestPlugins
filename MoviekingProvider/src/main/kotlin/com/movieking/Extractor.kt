package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
        )

        val doc = response.text
        
        // 정규식 수정: raw string(""") 안에서는 이스케이프가 적게 필요합니다.
        // iframe 소스 내의 .m3u8 링크를 찾습니다.
        val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
        val match = m3u8Regex.find(doc)

        if (match != null) {
            // 이스케이프 된 슬래시(\/)를 일반 슬래시(/)로 변환
            val m3u8Url = match.groupValues[1].replace("\\/", "/")
            
            // [수정된 부분] newExtractorLink 사용 방식 변경
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    // referer와 quality는 여기서(람다 블록 내부) 설정합니다.
                    this.referer = url
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
