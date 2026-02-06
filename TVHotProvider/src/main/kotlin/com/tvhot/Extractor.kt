package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.URI

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // Chrome 121 (표준)
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

        var capturedUrl: String? = null

        // 2. c.html 요청을 낚아채서 '진짜 도메인'과 '토큰'을 확보
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 15000L
        )
        
        try {
            val requestHeaders = mapOf(
                "Referer" to "https://tvmon.site/", 
                "User-Agent" to DESKTOP_UA
            )

            // cleanUrl(iframe)을 로딩하면 내부에서 c.html을 호출함
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            // 낚아챈 URL (리다이렉트가 있었다면 pixelstorm 도메인일 수도 있음)
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
            }
            
        } catch (e: Exception) {}

        if (capturedUrl != null) {
            // capturedUrl: https://[every4 or pixelstorm...]/v/f/.../c.html?token=...
            
            // 1. 쿠키 확보 (가장 중요)
            val cookieManager = CookieManager.getInstance()
            var cookie = cookieManager.getCookie(capturedUrl)
            
            // 쿠키가 없으면 도메인 단위로 빡세게 뒤짐
            if (cookie.isNullOrEmpty()) {
                try {
                    val uri = URI(capturedUrl)
                    val host = uri.host // every4.poorcdn.com or c4.pixelstormh7q.com
                    cookie = cookieManager.getCookie("https://$host")
                    
                    // 그래도 없으면 상위 도메인 시도 (poorcdn.com)
                    if (cookie.isNullOrEmpty() && host.count { it == '.' } > 1) {
                         val rootDomain = host.substring(host.indexOf('.') + 1)
                         cookie = cookieManager.getCookie("https://$rootDomain") ?: ""
                    }
                } catch (e: Exception) {}
            }

            // 2. c.html -> index.m3u8 변환
            val m3u8Url = capturedUrl.replace("/c.html", "/index.m3u8")

            // 3. 헤더 구성 (Fiddler 완벽 복제 + 쿠키)
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
                "sec-ch-ua" to "\"Not A(Brand\";v=\"99\", \"Google Chrome\";v=\"121\", \"Chromium\";v=\"121\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\""
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
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
