package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import android.webkit.WebView
import android.webkit.WebSettings
import kotlinx.coroutines.delay

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
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        println("DEBUG_EXTRACTOR name=$name step=webview_start url=$cleanUrl")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://tvmon.site/")
        )

        val resolver = WebViewResolver(
            Regex("""\.m3u8""") // .m3u8 요청을 가로챔
        )

        // WebViewResolver 호출 (CloudStream 버전에 따라 시그니처가 다를 수 있어 가장 안전한 방법 사용)
        val requestUrl = WebViewResolver.Url(cleanUrl, headers)
        
        resolver.resolveUsingWebView(
            request = requestUrl,
            requestCallBack = { request ->
                // 가로챈 요청(m3u8)이 들어오면 여기로 옴
                val capturedUrl = request.url.toString()
                println("DEBUG_EXTRACTOR step=webview_found url=$capturedUrl")
                
                // 찾은 URL로 M3U8 생성
                M3u8Helper.generateM3u8(
                    name,
                    capturedUrl,
                    cleanUrl,
                    headers = request.headers.toMap()
                ).forEach(callback)
                
                true // true를 리턴하면 WebView 종료
            }
        )
    }
}
