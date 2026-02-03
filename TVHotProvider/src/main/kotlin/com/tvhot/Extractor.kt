package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// ▼▼▼ 모든 유틸리티 및 타입(ExtractorLinkType)을 가져오기 위해 * 사용 ▼▼▼
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
        val playerResponse = app.get(url, headers = mapOf("Referer" to "$referer")).text

        // 2. m3u8 직접 찾기
        val m3u8Match = Regex("""(https?://[^"']*?poorcdn\\.com[^"']*?\\.m3u8[^"']*)""").find(playerResponse)
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match.value,
                    referer = mainUrl, // 일부 버전에서는 이 인자가 유효할 수 있으나, 에러 시 제거하고 아래 headers에 포함하세요.
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8 // isM3u8 = true 대체
                )
                // 만약 위 코드로도 'referer'나 'quality' 에러가 계속된다면,
                // 아래 '안전한 최신 방식' 블록을 사용하세요.
            )
            return
        }

        // ... (중간 생략) ...

        val finalM3u8Match = Regex("""(https?://[^"']*?\\.m3u8[^"']*)""").find(finalResponse)
        finalM3u8Match?.value?.let { m3u8Url ->
            callback.invoke(
                // ▼▼▼ 가장 안전한 최신 문법 (빌더 패턴) ▼▼▼
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    // isM3u8 대신 type 사용
                    type = ExtractorLinkType.M3U8
                ) {
                    // referer와 quality는 여기서 설정
                    this.referer = mainUrl
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
