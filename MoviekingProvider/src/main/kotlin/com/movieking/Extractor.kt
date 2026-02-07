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
        println("[MovieKingPlayer] Referer: $referer")

        try {
            println("[MovieKingPlayer] HTTP 요청 시도...")
            val response = app.get(
                url,
                referer = referer,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
                )
            )

            println("[MovieKingPlayer] HTTP 응답 수신 완료")
            println("[MovieKingPlayer] 응답 코드: ${response.code}")
            
            val doc = response.text
            println("[MovieKingPlayer] 응답 본문 길이: ${doc.length}")
            println("[MovieKingPlayer] 응답 본문 미리보기 (앞 1000자):")
            println(doc.take(1000)) // 본문 내용을 로그로 확인하여 m3u8이 있는지, 다른 방식인지 분석

            // 정규식 매칭 시도
            println("[MovieKingPlayer] M3U8 패턴 매칭 시도...")
            
            // 기존 정규식 외에 JSON 형태나 다른 패턴일 수 있으므로 로그 확인 후 수정 필요할 수 있음
            val m3u8Regex = Regex("""["']([^"']+\.m3u8[^"']*)["']""")
            val match = m3u8Regex.find(doc)

            if (match != null) {
                val rawUrl = match.groupValues[1]
                val m3u8Url = rawUrl.replace("\\/", "/") // 이스케이프 문자 제거
                
                println("[MovieKingPlayer] M3U8 URL 발견함!")
                println("[MovieKingPlayer] 원본 추출 URL: $rawUrl")
                println("[MovieKingPlayer] 가공된 URL: $m3u8Url")

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
                println("[MovieKingPlayer] Callback 호출 완료")

            } else {
                println("[MovieKingPlayer] 경고: 응답 본문에서 .m3u8 패턴을 찾을 수 없습니다.")
                // 혹시 모르니 file: "..." 패턴도 로그로 확인해볼 필요가 있음
            }

        } catch (e: Exception) {
            println("[MovieKingPlayer] 에러 발생: ${e.message}")
            e.printStackTrace()
        }

        println("[MovieKingPlayer] getUrl 종료 ===============================")
    }
}
