package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient

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
        // WebView를 사용하여 페이지 로딩 및 m3u8 요청 가로채기
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        println("DEBUG_EXTRACTOR name=$name step=webview_start url=$cleanUrl")

        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Referer" to (referer ?: "https://tvmon.site/")
        )

        // WebViewResolver 사용 (CloudStream 내장 기능)
        WebViewResolver(
            Regex("""\.m3u8""") // .m3u8로 끝나는 요청을 잡음
        ).resolveUsingWebView(
            request = WebViewResolver.Url(cleanUrl, headers),
            // WebView 설정
            extraWebViewOptions = { webView ->
                webView.settings.domStorageEnabled = true
                webView.settings.javaScriptEnabled = true
            }
        ) { capturedUrl ->
            // 찾은 URL (토큰 포함됨)
            println("DEBUG_EXTRACTOR step=webview_found url=$capturedUrl")
            
            // 토큰이 포함된 m3u8 주소를 바로 사용
            M3u8Helper.generateM3u8(
                name,
                capturedUrl.url,
                cleanUrl,
                headers = capturedUrl.headers.toMap()
            ).forEach(callback)
            
            // 하나만 찾으면 종료 (true 반환)
            true 
        }
    }
}
