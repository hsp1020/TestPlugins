package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// 모든 유틸리티(newExtractorLink, ExtractorLinkType 등) import
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
        // 1. 초기 요청
        val playerResponse = app.get(url, headers = mapOf("Referer" to "$referer")).text

        // 2. m3u8 직접 찾기 시도
        val m3u8Match = Regex("""(https?://[^"']*?poorcdn\\.com[^"']*?\\.m3u8[^"']*)""").find(playerResponse)
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match.value,
                    // 인자에는 type만 넣습니다.
                    type = ExtractorLinkType.M3U8
                ) {
                    // referer와 quality는 여기서 설정해야 합니다 (중요)
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
            return
        }

        // 3. .html?token=... 형태 찾기
        val htmlMatch = Regex("""(https?://[^"']*?poorcdn\\.com[^"']*?\\.html\\?token=[^"']*)""").find(playerResponse)
        val htmlUrl = htmlMatch?.value ?: return

        // ▼▼▼ 누락되어 에러났던 부분: finalResponse 변수 선언 복구 ▼▼▼
        val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to mainUrl)).text
        
        // 최종 m3u8 주소 추출
        val finalM3u8Match = Regex("""(https?://[^"']*?\\.m3u8[^"']*)""").find(finalResponse)
        
        finalM3u8Match?.value?.let { m3u8Url ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    // 인자에는 type만 넣습니다.
                    type = ExtractorLinkType.M3U8
                ) {
                    // referer와 quality는 여기서 설정해야 합니다 (중요)
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
