package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.* // suspendSafeApiCall 포함
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
// [필수] Dispatchers 사용을 위해
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 윈도우 UA 상수
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
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch & 2. Visit (URL 추출)
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

        var path = ""
        var id = ""
        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer))
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(res.text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
            if (videoPathMatch != null) {
                path = videoPathMatch.groupValues[1]
                id = videoPathMatch.groupValues[2]
            }
        } catch (e: Exception) {}

        if (path.isNotEmpty()) {
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every$serverNum.poorcdn.com"
            val tokenUrl = "$domain$path$id/c.html"

            // [직접 구현] WebViewResolver 대신 suspendSafeApiCall 사용
            val captured = suspendSafeApiCall<String?> {
                val webView = WebView(it)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = DESKTOP_UA
                
                var foundUrl: String? = null
                
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 페이지 로딩이 끝나도 바로 리턴하지 않고, 쿠키가 생길 때까지 대기하도록 함
                    }
                    
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString()
                        if (reqUrl?.contains(".m3u8") == true) {
                            foundUrl = reqUrl
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView.loadUrl(tokenUrl, mapOf("Referer" to cleanUrl))
                
                // 최대 20초 대기
                val startTime = System.currentTimeMillis()
                while (foundUrl == null && System.currentTimeMillis() - startTime < 20000) {
                    // 쿠키가 들어왔는지 확인
                    val cookie = CookieManager.getInstance().getCookie(tokenUrl)
                    if (!cookie.isNullOrEmpty()) {
                         // 쿠키가 들어왔으면 m3u8을 못 찾았어도 강제로 진행 가능
                         // 하지만 조금 더 기다려봄
                    }
                    delay(500)
                }
                
                foundUrl ?: tokenUrl.replace("c.html", "index.m3u8")
            }

            if (captured != null) {
                // 쿠키 가져오기
                val cookie = CookieManager.getInstance().getCookie(tokenUrl) ?: ""
                
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie, // 이제 쿠키가 확실히 있을 것임
                    "User-Agent" to DESKTOP_UA
                )
                
                callback(
                    newExtractorLink(name, name, captured, ExtractorLinkType.M3U8) {
                        this.referer = cleanUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                return true
            }
        }
        return false
    }
}
