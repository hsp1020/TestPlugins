package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.getPacked
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
        // 1. 플레이어 페이지 요청 (리퍼러는 메인 사이트)
        val response = app.get(url, headers = mapOf("Referer" to "$referer")).text

        // 2. 난독화된 JS가 있다면 해제 (Packed JS 처리)
        val unpackedBody = if (response.contains("eval(function(p,a,c,k,e,d)")) {
            getPacked(response) ?: response
        } else {
            response
        }

        // 3. m3u8 직접 찾기 (이스케이프 문자 \/ 대응을 위해 정규식 수정)
        // https:\/\/every9.poorcdn.com... 형태도 잡을 수 있도록 수정
        val m3u8Regex = Regex("""(https?:\\?\/\\?\/[^"']*?poorcdn\.com[^"']*?\.m3u8[^"']*)""")
        val m3u8Match = m3u8Regex.find(unpackedBody)
        
        if (m3u8Match != null) {
            // URL에 있는 역슬래시(\) 제거
            val cleanUrl = m3u8Match.value.replace("\\/", "/")
            loadExtractor(cleanUrl, url, subtitleCallback, callback)
            return
        }

        // 4. html 토큰 방식 찾기 (c.html)
        // 예: https://every9.poorcdn.com/v/f/.../c.html?token=...
        val htmlRegex = Regex("""(https?:\\?\/\\?\/[^"']*?poorcdn\.com[^"']*?\.html\?token=[^"']*)""")
        val htmlMatch = htmlRegex.find(unpackedBody)
        val rawHtmlUrl = htmlMatch?.value ?: return
        
        // URL 클리닝
        val htmlUrl = rawHtmlUrl.replace("\\/", "/")

        // 5. c.html 요청 (중요: Referer는 메인사이트가 아니라 '플레이어 URL(url)'이어야 함)
        val finalResponse = app.get(htmlUrl, headers = mapOf("Referer" to url)).text
        
        // 최종 m3u8 추출
        val finalM3u8Match = Regex("""(https?://.*?\.m3u8.*?)["']""").find(finalResponse)
        
        finalM3u8Match?.groupValues?.get(1)?.let {
            // 마지막 m3u8 로드 시에도 Referer는 플레이어 URL을 유지하는 것이 안전함
            loadExtractor(it, url, subtitleCallback, callback)
        }
    }
}
