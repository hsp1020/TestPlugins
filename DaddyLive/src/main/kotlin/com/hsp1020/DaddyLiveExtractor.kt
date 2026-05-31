package com.hsp1020

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val streamUrls = links?.map { it.second } ?: listOf(url)

        streamUrls.forEach { streamUrl ->
            try {
                Log.d("DDL-DEBUG", "[DDL-DEBUG] WebView 가상 브라우저 해독 시작: $streamUrl")

                // 스마트폰의 가상 웹뷰가 연산을 끝내고 phantemlis.top의 m3u8 주소를 호출하는 순간 가로챔
                val interceptRegex = Regex("""(phantemlis\.top|tracks-v1a1).*?\.m3u8""")
                
                val response = app.get(
                    streamUrl,
                    interceptor = WebViewResolver(interceptRegex)
                )
                
                val finalUrl = response.url

                if (finalUrl.contains(".m3u8")) {
                    Log.d("DDL-DEBUG", "[DDL-DEBUG] 토큰 주소 가로채기 성공: $finalUrl")
                    
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = finalUrl,
                            referer = streamUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf(
                                "Origin" to "https://player.videasy.net",
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                        )
                    )
                } else {
                    Log.e("DDL-DEBUG", "[DDL-DEBUG] m3u8 추출 실패 (타임아웃)")
                }
            } catch (e: Exception) {
                Log.e("DDL-DEBUG", "[DDL-DEBUG] WebView 예외 발생: ${e.message}")
            }
        }
    }
}
