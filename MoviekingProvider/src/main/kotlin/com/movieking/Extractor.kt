package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.URI

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
        // iframe 내부의 HTML을 가져옵니다.
        val response = app.get(
            url,
            referer = referer,
            headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            )
        )

        val doc = response.text
        
        // M3U8 URL을 정규식으로 찾습니다.
        // 보통 'file': 'url.m3u8' 또는 source src="url.m3u8" 형태로 존재합니다.
        val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
        val match = m3u8Regex.find(doc)

        if (match != null) {
            val m3u8Url = match.groupValues[1].replace("\\/", "/") // 이스케이프 문자 제거
            
            callback(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = url, // 플레이어 주소를 리퍼러로 사용
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8
                )
            )
        }
    }
}
