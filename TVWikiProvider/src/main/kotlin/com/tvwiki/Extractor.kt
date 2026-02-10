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

// [v113] Extractor.kt: '완벽한 브라우저 모사' (UA 복구, Main Referer, Main Origin) + 쿠키 필수
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVWiki v113] [Bunny] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v113] [Bunny] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v113] [성공] 재탐색으로 URL 획득: $cleanUrl")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var capturedUrl: String? = null

        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA 
            )
            println("[TVWiki v113] [Bunny] WebView 요청 시작")
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                println("[TVWiki v113] [성공] c.html URL 캡처됨: $capturedUrl")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            
            // 쿠키 수집
            val videoCookie = cookieManager.getCookie(capturedUrl) ?: ""
            val playerCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
            
            val combinedCookies = listOf(videoCookie, playerCookie)
                .filter { it.isNotEmpty() }
                .joinToString("; ") { it.trim().removeSuffix(";") }

            println("[TVWiki v113] [Bunny] 쿠키 병합 완료: ${combinedCookies.isNotEmpty()}")

            // [v113 수정] UA 복구 + 표준 헤더
            val playbackHeaders = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            if (combinedCookies.isNotEmpty()) {
                playbackHeaders["Cookie"] = combinedCookies
            }
            
            println("[TVWiki v113] [Bunny] 최종 재생 헤더 설정: $playbackHeaders")
            
            val finalUrl = "$capturedUrl#.m3u8"
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                }
            )
            return true
        } 
        
        println("[TVWiki v113] [Bunny] 최종 실패")
        return false
    }
}
