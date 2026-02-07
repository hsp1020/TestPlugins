package com.tvwiki

import android.util.Log
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.*

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 최신 Chrome UA
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    private val TAG = "TVWIKI_DEBUG"

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
        Log.d(TAG, "[Bunny] Extract Start: $url")
        
        var cleanUrl = url.replace("&amp;", "&").trim()
        // referer가 null이거나 bunny-frame 주소면 메인 사이트 주소로 대체
        val targetPage = if (referer.isNullOrEmpty() || referer.contains("bunny-frame")) "https://tvwiki5.net/" else referer
        
        val isDirectUrl = cleanUrl.contains("bunny-frame.online") || cleanUrl.contains("/v/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(targetPage, headers = mapOf("User-Agent" to DESKTOP_UA))
                val html = refRes.text
                val iframeMatch = Regex("""src=['"](https://player\\.bunny-frame\\.online/[^"']+)['"]""").find(html)
                    ?: Regex("""data-player\\d*=['"](https://player\\.bunny-frame\\.online/[^"']+)['"]""").find(html)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Bunny] Page Load Error", e)
            }
        }

        var capturedUrl: String? = null
        val interceptPattern = Regex("""/c\\.html""") 
        val resolver = WebViewResolver(
            interceptUrl = interceptPattern, 
            useOkhttp = false,
            timeout = 30000L
        )

        try {
            val requestHeaders = mapOf(
                "Referer" to targetPage, 
                "User-Agent" to DESKTOP_UA,
                "Upgrade-Insecure-Requests" to "1"
            )

            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
            }

        } catch (e: Exception) {
            Log.e(TAG, "[Bunny] WebView Error", e)
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie
            
            val finalUrl = "$capturedUrl#.m3u8"
            
            // ExtractorLink 생성자 직접 사용 (안전)
            callback(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    referer = "https://player.bunny-frame.online/",
                    quality = Qualities.Unknown.value,
                    type = ExtractorLinkType.M3U8,
                    headers = headers
                )
            )
            return true
        }
        return false
    }
}
