package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// ▼▼▼ 핵심 수정: utils 아래의 모든 함수(newExtractorLink 포함)를 가져옴 ▼▼▼
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
        // 1. iframe 소스 가져오기
        val playerResponse = app.get(url, headers = mapOf("Referer" to "$referer")).text

        // 2. m3u8 직접 찾기 시도
        val m3u8Match = Regex("""(https?://[^"']*?poorcdn\\.com[^"']*?\\.m3u8[^"']*)""").find(playerResponse)
        if (m3u8Match != null) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match.value,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true 
                    // 만약 여기서 "isM3u8 파라미터를 찾을 수 없다"는 오류가 나면 
                    // isM3u8 = true 줄을 지우고 아래 줄의 주석을 해제하세요:
                    // type = ExtractorLinkType.M3U8
                )
            )
            return
        }

        // 3. .html?token=... 형태 찾기
        val htmlMatch = Regex("""(https?://[^"']*?poorcdn\\.com[^"']*?\\.html\\?token=[^"']*)""").find(playerResponse)
        val htmlUrl = htmlMatch?.value ?: return

        // .html 페이지 안에 있는 진짜 m3u8 찾기
        val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to mainUrl)).text
        
        // 최종 m3u8 주소 추출
        val finalM3u8Match = Regex("""(https?://[^"']*?\\.m3u8[^"']*)""").find(finalResponse)
        
        finalM3u8Match?.value?.let { m3u8Url ->
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                    // 만약 여기서 "isM3u8 파라미터를 찾을 수 없다"는 오류가 나면 
                    // isM3u8 = true 줄을 지우고 아래 줄의 주석을 해제하세요:
                    // type = ExtractorLinkType.M3U8
                )
            )
        }
    }
}
