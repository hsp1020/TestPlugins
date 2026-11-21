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
    private fun trace(stage: String, detail: String) = android.util.Log.i("OhliPlaybackTrace", "stage=$stage $detail")

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
        val start = android.os.SystemClock.elapsedRealtime()
        trace("CDN_START", "host=${getBaseUrl(url)}")
        val host= getBaseUrl(url)
        
        if (url.contains("/video/")) {
            try {
                // 기존 로직 유지 (자막 추출)
                val pageStart = android.os.SystemClock.elapsedRealtime()
                val doc = app.get(url, referer = referer).document.selectFirst("script:containsData(playerjsSubtitle)")?.data().orEmpty()
                trace("CDN_PAGE_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - pageStart}")
                val srtRegex = Regex("""playerjsSubtitle\s*=\s*"[^"]*(https?://[^"]+\.srt)"""")
                val srtUrl = srtRegex.find(doc)?.groupValues?.get(1) ?: ""

                val extractedHash = url.substringAfterLast("/").substringBefore('?')
                val m3u8Url = "$host/player/index.php?data=$extractedHash&do=getVideo"
                val header= mapOf("x-requested-with" to "XMLHttpRequest")
                
                // referer가 null일 경우 "null" 문자열이 들어가는 것 방지
                val safeReferer = referer ?: ""
                val formdata= mapOf("hash" to extractedHash, "r" to safeReferer)
                
                val apiStart = android.os.SystemClock.elapsedRealtime()
                trace("CDN_API_START", "ms=${apiStart - start}")
                val response = app.post(m3u8Url, headers=header, data = formdata).parsedSafe<Response>()
                
                trace("CDN_API_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - apiStart} found=${response?.videoSource != null}")
                response?.videoSource?.let { m3u8 ->

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
                        val manifestStart = android.os.SystemClock.elapsedRealtime()
                        trace("CDN_MANIFEST_START", "ms=${manifestStart - start}")
                        val m3u8Res = app.get(m3u8, headers = streamHeaders)
                        
                        trace("CDN_MANIFEST_HTTP", "ms=${android.os.SystemClock.elapsedRealtime() - manifestStart} status=${m3u8Res.code}")
                        val content = try {
                            m3u8Res.text.trim()
                        } catch (e: Exception) {
                            m3u8Res.document.text().trim()
                        }
                        
                        // 진짜 M3U8이 아니라면, 내부에 실제 M3U8 링크가 숨겨져 있는지 탐색
                        if (!content.startsWith("#EXTM3U")) {
                            val innerM3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            innerM3u8Regex.find(content)?.let {
                                finalUrl = it.groupValues[1]
                            }
                        }
                    } catch (e: Exception) {
                    }

                    // 추출기 링크 생성 및 콜백
                    trace("CDN_CALLBACK", "ms=${android.os.SystemClock.elapsedRealtime() - start} urlChanged=${finalUrl != m3u8}")
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

                    if (srtUrl.isNotBlank()) {
                        subtitleCallback.invoke(
                            newSubtitleFile(
                                "Korean",
                                srtUrl
                            )
                        )
                    }
                } ?: run {
                }
            } catch (e: Exception) {
                trace("CDN_ERROR", "ms=${android.os.SystemClock.elapsedRealtime() - start} error=${e.javaClass.simpleName}")
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
