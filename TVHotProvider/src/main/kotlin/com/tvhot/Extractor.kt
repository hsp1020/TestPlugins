package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import com.lagradost.cloudstream3.utils.suspendSafeApiCall
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private fun pl(tag: String, msg: String) {
        println("DEBUG_EXTRACTOR name=$name $tag $msg")
    }

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
        val reqId = System.currentTimeMillis().toDouble()
        pl("req=$reqId step=start", "url=$url")

        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch (iframe src 찾기)
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

        // 2. Path & ID 찾기
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
            
            pl("req=$reqId step=webview_hack_start", "tokenUrl=$tokenUrl")

            // [최후의 수단] suspendSafeApiCall로 WebView 직접 제어
            // OkHttp 간섭 없이 순수 WebView로 로드 -> 403 회피 확률 높음
            val capturedUrl = suspendSafeApiCall<String?> {
                val webView = WebView(it)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                
                var result: String? = null
                
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val reqUrl = request.url.toString()
                        
                        // 1. m3u8 요청 감지
                        if (reqUrl.contains(".m3u8")) {
                            pl("req=$reqId step=intercept", "found=$reqUrl")
                            result = reqUrl
                            return null // 로드 계속 진행 (쿠키 생성을 위해)
                        }
                        
                        // 2. c.html 요청 감지 (여기서 리다이렉트나 내용 확인)
                        if (reqUrl.contains("c.html")) {
                             // 그냥 통과시킴
                        }
                        
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        // 페이지 로드 완료 시 URL 확인
                        val currentUrl = view?.url ?: ""
                        if (currentUrl.contains(".m3u8")) {
                             result = currentUrl
                        }
                        
                        // 만약 아직 못 찾았다면, 강제로 index.m3u8로 추측해서 리턴할 수도 있음
                        if (result == null && currentUrl.contains("c.html")) {
                             // c.html 로드 완료 -> 내용은 못 보지만 쿠키는 구워졌을 것임.
                             // 강제로 주소 변환
                             result = tokenUrl.replace("c.html", "index.m3u8")
                        }
                    }
                }
                
                val headers = mapOf("Referer" to cleanUrl)
                webView.loadUrl(tokenUrl, headers)
                
                // 15초 대기
                val startTime = System.currentTimeMillis()
                while (result == null && System.currentTimeMillis() - startTime < 15000) {
                    Thread.sleep(200)
                }
                
                result
            }

            if (capturedUrl != null) {
                pl("req=$reqId step=success", "url=$capturedUrl")
                
                // 쿠키 가져오기
                val cookie = CookieManager.getInstance().getCookie(capturedUrl) 
                           ?: CookieManager.getInstance().getCookie(tokenUrl) 
                           ?: ""
                           
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )

                M3u8Helper.generateM3u8(name, capturedUrl, cleanUrl, headers = headers).forEach(callback)
                return true
            } else {
                pl("req=$reqId step=fail", "timeout or blocked")
            }
        }
        return false
    }
}
