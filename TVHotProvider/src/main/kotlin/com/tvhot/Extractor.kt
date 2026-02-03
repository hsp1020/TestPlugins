package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// ▼▼▼ 중요: 이 import가 필요할 수 있습니다 (없으면 추가하세요) ▼▼▼
import com.lagradost.cloudstream3.utils.loadExtractor 
// 만약 newExtractorLink가 자동 import 안되면 아래처럼 직접 쓰거나 utils.* 를 확인하세요.
// 보통 ExtractorApi를 상속받으면 보이거나, utils 패키지 안에 있습니다.

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
                // ▼▼▼ 수정됨: 생성자 대신 newExtractorLink 사용 ▼▼▼
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Match.value,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
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
                // ▼▼▼ 수정됨: 생성자 대신 newExtractorLink 사용 ▼▼▼
                newExtractorLink(
                    source = name,
                    name = name,
                    url = m3u8Url,
                    referer = mainUrl,
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }
}
