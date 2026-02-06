package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import kotlinx.coroutines.delay

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // [중요] 사용자 로그 기반: 윈도우 크롬 UA
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
        // url은 iframe 주소(player.bunny-frame.online)여야 함
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

        // [핵심 변경] WebViewResolver 대신 직접 제어하여 "토큰이 포함된 URL"을 캡처
        val capturedUrl = suspendSafeApiCall<String?> {
            val webView = WebView(it)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = DESKTOP_UA
            
            // [중요] iframe 주소를 Referer로 설정하면 안 됨 (부모가 없으므로)
            // 그냥 iframe 주소를 로딩하면 됨
            
            var result: String? = null
            
            webView.webViewClient = object : WebViewClient() {
                // 리소스 로딩을 감시하다가 c.html이 보이면 납치
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val reqUrl = request?.url?.toString() ?: ""
                    
                    // [핵심] c.html 요청을 발견하면, 그 URL(토큰 포함)을 저장
                    if (reqUrl.contains("/c.html")) {
                        result = reqUrl
                        return null // 계속 진행시켜도 되고, 여기서 끊어도 됨
                    }
                    return super.shouldInterceptRequest(view, request)
                }
            }
            
            // iframe 페이지 로딩
            webView.loadUrl(cleanUrl, mapOf("Referer" to "https://tvmon.site/"))
            
            // 최대 15초 대기 (토큰 계산 시간 확보)
            val startTime = System.currentTimeMillis()
            while (result == null && System.currentTimeMillis() - startTime < 15000) {
                delay(200)
            }
            
            result
        }

        if (capturedUrl != null) {
            // capturedUrl은 "https://every4.poorcdn.com/.../c.html?token=XXX&expires=YYY" 형태임
            
            // 1. c.html -> index.m3u8 로 변경 (파라미터 유지)
            val m3u8Url = capturedUrl.replace("/c.html", "/index.m3u8")
            
            // 2. 사용자 로그 기반 "성공 헤더" 복제
            // 쿠키는 없음!
            val headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/", // [중요] iframe 주소
                "Origin" to "https://player.bunny-frame.online",   // [중요]
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            )

            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        } else {
            // 토큰 캡처 실패 시 (백업: 썸네일 힌트가 있다면 시도해보지만, 토큰 없인 힘들 것임)
            // 그래도 혹시 모르니 기본 URL 로직은 남겨둠
            var path = ""
            var id = ""
            try {
                // 단순히 텍스트 파싱으로 URL을 만들면 토큰이 없어서 실패할 확률 99%
                val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
                if (videoPathMatch != null) {
                    path = videoPathMatch.groupValues[1]
                    id = videoPathMatch.groupValues[2]
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    val fallbackUrl = "https://every$serverNum.poorcdn.com$path$id/index.m3u8"
                    
                    // 헤더라도 맞춰서 보내봄
                    val headers = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to "https://player.bunny-frame.online/",
                        "Origin" to "https://player.bunny-frame.online"
                    )
                    
                    callback(
                        newExtractorLink(name, name, fallbackUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://player.bunny-frame.online/"
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return true
                }
            } catch (e: Exception) {}
        }
        
        return false
    }
}
