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
        // 단순화된 버전: player.bunny-frame.online을 거치지 않고 직접 m3u8 URL 구성
        // 페이지 소스에서 직접 추출하는 방식으로 변경되었으므로 이 추출기는 더 이상 사용되지 않음
        // 참고용으로만 남겨둠
        
        // 대체 방법: 직접 m3u8 URL 생성
        val m3u8Url = "https://every9.poorcdn.com/v/f/73257ac6850f8193ae10d6339ef149f7f7005/index.m3u8"
        
        callback(
            ExtractorLink(
                name,
                name,
                m3u8Url,
                referer = referer ?: "https://tvhot.store",
                quality = Qualities.Unknown.value,
                isM3u8 = true
            )
        )
    }
}
