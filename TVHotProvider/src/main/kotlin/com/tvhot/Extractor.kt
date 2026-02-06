package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import com.lagradost.cloudstream3.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

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
            // 메인 스레드에서 WebView 생성 -> 로딩 대기 -> 쿠키 추출
            val captured = suspendSafeApiCall<String?> {
                val webView = WebView(it)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                // 사용자 에이전트 고정 (성공했던 설정)
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                
                var targetUrl: String? = null
                var pageFinished = false
                
                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        pageFinished = true
                        if (url?.contains(".m3u8") == true) {
                            targetUrl = url
                        }
                    }
                    
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString()
                        if (reqUrl?.contains(".m3u8") == true) {
                            targetUrl = reqUrl
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView.loadUrl(tokenUrl, mapOf("Referer" to cleanUrl))
                
                // 최대 15초 대기 (0.1초 광속 종료 방지)
                val startTime = System.currentTimeMillis()
                while (targetUrl == null && System.currentTimeMillis() - startTime < 15000) {
                    delay(200)
                }
                
                // 찾았으면 그거 반환, 못 찾았어도 c.html 로딩이 끝났으면 강제 변환 URL 반환
                targetUrl ?: if (pageFinished) tokenUrl.replace("c.html", "index.m3u8") else null
            }

            if (captured != null) {
                val cookie = CookieManager.getInstance().getCookie(captured) 
                           ?: CookieManager.getInstance().getCookie(tokenUrl) 
                           ?: ""
                           
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
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
