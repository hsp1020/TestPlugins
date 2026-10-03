package com.ohli24

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class MichealCDN : Cdndania() {
    override val name = "MichealCDN"
    override val mainUrl = "https://michealcdn.com"
}

open class Cdndania : ExtractorApi() {
    override val name = "CDNdania"
    override val mainUrl = "https://cdndania.com"
    override val requiresReferer = true
    
    // [v1.1 추가] 완벽한 위장을 위한 브라우저 User-Agent
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[OHLI24 v1.1] getUrl 호출됨 - url: $url, referer: $referer")
        val host= getBaseUrl(url)
        
        if (url.contains("/video/")) {
            try {
                // 기존 로직 유지 (자막 추출)
                val doc = app.get(url, referer = referer).document.selectFirst("script:containsData(playerjsSubtitle)")?.data().orEmpty()
                val srtRegex = Regex("""playerjsSubtitle\s*=\s*"[^"]*(https?://[^"]+\.srt)"""")
                val srtUrl = srtRegex.find(doc)?.groupValues?.get(1) ?: ""
                println("[OHLI24 v1.1] 자막 URL 추출: $srtUrl")

                val extractedHash = url.substringAfterLast("/")
                val m3u8Url = "$host/player/index.php?data=$extractedHash&do=getVideo"
                val header= mapOf("x-requested-with" to "XMLHttpRequest")
                
                // referer가 null일 경우 "null" 문자열이 들어가는 것 방지
                val safeReferer = referer ?: ""
                val formdata= mapOf("hash" to extractedHash, "r" to safeReferer)
                
                println("[OHLI24 v1.1] 비디오 소스 API 요청: $m3u8Url")
                val response = app.post(m3u8Url, headers=header, data = formdata).parsedSafe<Response>()
                
                response?.videoSource?.let { m3u8 ->
                    println("[OHLI24 v1.1] API 응답 비디오 소스 획득: $m3u8")

                    // [v1.1 수정] ExoPlayer 3002 에러 방지를 위한 강력한 위장 헤더 구성
                    val streamHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to url, // 빈값("") 대신 실제 비디오 iframe 주소를 삽입
                        "Origin" to host,
                        "Accept" to "*/*",
                        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                    )

                    var finalUrl = m3u8

                    // [v1.1 수정] 텍스트 파일 용량 초과 예외(OOM) 방어 및 내용 안전 파싱
                    try {
                        println("[OHLI24 v1.1] M3U8(.txt) 안전 검증 시작...")
                        val m3u8Res = app.get(m3u8, headers = streamHeaders)
                        
                        val content = try {
                            m3u8Res.text.trim()
                        } catch (e: Exception) {
                            println("[OHLI24 v1.1] text 추출 오류 발생(용량 초과 가능성). document.text() 로 대체합니다.")
                            m3u8Res.document.text().trim()
                        }
                        
                        if (content.length < 500) {
                            println("[OHLI24 v1.1] 응답 내용(미리보기): ${content.take(200)}")
                        }

                        // 진짜 M3U8이 아니라면, 내부에 실제 M3U8 링크가 숨겨져 있는지 탐색
                        if (!content.startsWith("#EXTM3U")) {
                            println("[OHLI24 v1.1] ❌ 주의: 응답이 #EXTM3U로 시작하지 않습니다. (에러 3002 원인)")
                            val innerM3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            innerM3u8Regex.find(content)?.let {
                                finalUrl = it.groupValues[1]
                                println("[OHLI24 v1.1] 내용에서 실제 M3U8 추출 성공: $finalUrl")
                            } ?: println("[OHLI24 v1.1] 내부에 M3U8 주소가 없습니다. 원본 URL($finalUrl)을 유지합니다.")
                        } else {
                            println("[OHLI24 v1.1] ✅ 정상적인 M3U8 텍스트로 확인됨.")
                        }
                    } catch (e: Exception) {
                        println("[OHLI24 v1.1] M3U8 검증 중 에러 발생 (재생 시도 속행): ${e.message}")
                    }

                    // 추출기 링크 생성 및 콜백
                    callback(
                        newExtractorLink(
                            "CDN",
                            "CDN",
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    println("[OHLI24 v1.1] ExtractorLink 콜백 전달 완료")

                    if (srtUrl.isNotBlank()) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                "Korean",
                                srtUrl
                            )
                        )
                        println("[OHLI24 v1.1] SubtitleFile 콜백 전달 완료")
                    }
                } ?: run {
                    println("[OHLI24 v1.1] API 응답에서 videoSource가 null입니다.")
                }
            } catch (e: Exception) {
                println("[OHLI24 v1.1] 전체 파싱 중 예외 발생: ${e.message}")
            }
        }
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}

data class Response(
    val hls: Boolean,
    val videoImage: String,
    val videoSource: String,
    val securedLink: String,
    val downloadLinks: List<Any?>,
    val attachmentLinks: List<Any?>,
    val ck: String,
)
