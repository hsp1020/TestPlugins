package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import okhttp3.Interceptor
import okhttp3.Response

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 사용자 로그 기반 (Chrome 144는 아니지만 최신 안정 버전으로 맞춤)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        // cleanUrl = iframe 주소 (https://player.bunny-frame.online/...)
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch (iframe 주소 확보)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // [핵심 변경]
        // 우리가 캡처하고 싶은 건 "c.html?token=..." 입니다.
        // 이걸 얻으려면 WebView는 "iframe 주소(cleanUrl)"를 로딩해야 합니다.
        
        var capturedUrl: String? = null

        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), // c.html 요청이 발생하면 낚아채라
            useOkhttp = false,
            timeout = 15000L
        )
        
        try {
            // [중요] WebView에게 iframe 페이지를 열게 시킴
            // Referer는 tvmon.site여야 함 (iframe을 부른 곳이니까)
            val requestHeaders = mapOf(
                "Referer" to "https://tvmon.site/", 
                "User-Agent" to DESKTOP_UA
            )

            val response = app.get(
                url = cleanUrl, // <-- 여기가 수정됨 (tokenUrl이 아니라 cleanUrl)
                headers = requestHeaders,
                interceptor = resolver
            )
            
            // WebViewResolver가 가로챈 최종 URL 확인
            // response.url에 token=... 이 포함되어 있어야 성공
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
            }
            
        } catch (e: Exception) {
            // 에러가 나더라도 Interceptor가 URL을 캡처했을 수 있음
            // 하지만 CloudStream 구조상 response 객체를 못 받으면 확인 불가
            // 로그를 보면 WebViewResolver가 URL을 뱉어내는 방식이므로 response.url에 있을 것임
        }

        if (capturedUrl != null) {
            // capturedUrl: https://every4.poorcdn.com/.../c.html?token=XXX&expires=YYY
            
            // c.html -> index.m3u8 교체 (뒤에 붙은 ?token=... 파라미터는 그대로 둠!)
            val m3u8Url = capturedUrl.replace("/c.html", "/index.m3u8")

            // 사용자 로그 기반 헤더 (100% 일치)
            val headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } 
        
        return false
    }
}
