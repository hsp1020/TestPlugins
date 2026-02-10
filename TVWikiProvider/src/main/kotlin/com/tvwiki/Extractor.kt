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

// [v108] Extractor.kt 수정됨: 2000 에러 해결을 위한 'Referer' 값 수정 (c.html 주소 사용)
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
        println("[TVWiki v108] [Bunny] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v108] [Bunny] extract 시작: $url")
        
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
                    println("[TVWiki v108] [성공] 재탐색으로 URL 획득: $cleanUrl")
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
            println("[TVWiki v108] [Bunny] WebView 요청 시작")
            
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                println("[TVWiki v108] [성공] c.html URL 캡처됨: $capturedUrl")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            
            // 1. 쿠키 수집 (비디오 도메인 + 플레이어 도메인)
            val videoCookie = cookieManager.getCookie(capturedUrl) ?: ""
            val playerCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
            
            val combinedCookies = listOf(videoCookie, playerCookie)
                .filter { it.isNotEmpty() }
                .joinToString("; ") { it.trim().removeSuffix(";") }

            println("[TVWiki v108] [Bunny] 쿠키 병합 완료: ${combinedCookies.isNotEmpty()}")

            // [v108 핵심 수정] Referer를 'capturedUrl'(c.html)로 설정
            // 이전 버전(v107)의 'https://player.bunny-frame.online/'는 잘못된 Referer였습니다.
            // Key Server는 요청이 '토큰이 있는 c.html 페이지'에서 왔는지 검사합니다.
            val playbackHeaders = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to capturedUrl, // [중요] 토큰이 포함된 페이지 URL을 Referer로 사용
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            if (combinedCookies.isNotEmpty()) {
                playbackHeaders["Cookie"] = combinedCookies
            }
            
            println("[TVWiki v108] [Bunny] 최종 재생 헤더 설정: $playbackHeaders")
            
            val finalUrl = "$capturedUrl#.m3u8"
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = capturedUrl // [중요] ExtractorLink의 referer도 일치시킴
                    this.quality = Qualities.Unknown.value
                    this.headers = playbackHeaders
                }
            )
            return true
        } 
        
        println("[TVWiki v108] [Bunny] 최종 실패")
        return false
    }
}
