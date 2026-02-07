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
        println("[MovieKingPlayer] getUrl 시작 ===============================")
        println("[MovieKingPlayer] 요청 URL: $url")

        try {
            val response = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
            )

            val doc = response.text
            println("[MovieKingPlayer] HTML 응답 길이: ${doc.length}")

            // [수정 핵심] data-m3u8 속성값을 직접 찾습니다.
            // 예: data-m3u8="https://..."
            val regex = Regex("""data-m3u8=["']([^"']+)["']""")
            val match = regex.find(doc)

            if (match != null) {
                // 공백 제거 및 이스케이프 문자 처리
                val m3u8Url = match.groupValues[1].replace("\\/", "/").trim()
                
                println("[MovieKingPlayer] URL 추출 성공: $m3u8Url")

                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = m3u8Url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            } else {
                println("[MovieKingPlayer] 실패: data-m3u8 속성을 찾을 수 없습니다.")
                // 혹시 모르니 HTML 일부를 출력해 확인 (너무 길면 잘림)
                println("[MovieKingPlayer] HTML 일부: ${doc.take(500)}")
            }

        } catch (e: Exception) {
            println("[MovieKingPlayer] 에러 발생: ${e.message}")
            e.printStackTrace()
        }
    }
}
