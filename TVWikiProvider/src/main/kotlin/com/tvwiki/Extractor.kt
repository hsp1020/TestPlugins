package com.tvwiki

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

/**
 * BunnyPoorCdn Extractor
 * Version: 2026-02-08
 * - Optimized for 'player.bunny-frame.online' and 'poorcdn'
 * - Uses WebViewResolver to handle obfuscated requests
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // PC User Agent (Chrome Windows)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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
        var cleanUrl = url.replace("&amp;", "&").trim()
        val cleanReferer = referer ?: "https://tvwiki5.net/"

        // 1. Cloudstream Built-in Extractor 우선 시도 (가장 안정적)
        // loadExtractor는 재귀적으로 알려진 URL 패턴을 처리합니다.
        try {
            // M3U8 주소인 경우 바로 리턴
            if (cleanUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(name, name, cleanUrl, ExtractorLinkType.M3U8) {
                        this.referer = cleanReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {}

        // 2. WebViewResolver를 이용한 네트워크 트래픽 인터셉트
        // Bunny/PoorCDN은 JS로 토큰을 생성하여 /c.html 또는 특정 .m3u8을 호출합니다.
        var capturedUrl: String? = null
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") // 감지 범위 확대

        val resolver = WebViewResolver(
            interceptUrl = interceptRegex, 
            useOkhttp = false, // WebView 쿠키 및 세션 사용을 위해 false 권장
            timeout = 15000L
        )
        
        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )

            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            // 인터셉트된 URL 확인
            if (interceptRegex.containsMatchIn(response.url)) {
                capturedUrl = response.url
            }

        } catch (e: Exception) {
            println("[BunnyPoorCdn] WebView failed: ${e.message}")
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)

            // Fiddler/Chrome DevTools 기반 헤더
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty"
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
            // c.html로 끝나는 경우 토큰이 포함된 URL이므로 .m3u8 힌트를 붙여줌
            // 실제 .m3u8 파일이 아니더라도 Cloudstream 플레이어가 HLS로 인식하게 함
            val finalUrl = if (capturedUrl.contains("c.html")) "$capturedUrl#.m3u8" else capturedUrl
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        } 
        
        return false
    }
}
