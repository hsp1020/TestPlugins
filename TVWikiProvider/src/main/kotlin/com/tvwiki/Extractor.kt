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

// [v112] Extractor.kt: '/v/e/' 패턴(보안 영상)은 API 호출로 얻었더라도 WebView로 쿠키 생성 필수
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
        println("[TVWiki v112] [Bunny] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v112] [Bunny] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        
        // 1. iframe src가 아닌 경우 재탐색
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v112] [성공] 재탐색으로 URL 획득: $cleanUrl")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var capturedUrl: String? = null

        // 2. [v112 핵심] '/v/e/' (보안 영상)는 쿠키가 필수이므로 무조건 WebView를 거쳐야 함.
        // API로 링크를 얻었더라도, WebView로 접속하지 않으면 Key 서버 쿠키가 없음.
        val needWebView = cleanUrl.contains("/v/e/") || !cleanUrl.contains("/c.html")
        
        if (needWebView) {
            println("[TVWiki v112] [Bunny] '/v/e/' 패턴 감지됨. 쿠키 생성을 위해 WebView 실행")
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
                
                val response = app.get(
                    url = cleanUrl,
                    headers = requestHeaders,
                    interceptor = resolver
                )
                
                if (response.url.contains("/c.html") && response.url.contains("token=")) {
                    capturedUrl = response.url
                    println("[TVWiki v112] [성공] c.html URL 캡처됨: $capturedUrl")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        } else {
            // 이미 c.html 링크라면 그대로 사용 (단, 이 경우에도 쿠키가 있는지는 보장 못함)
            capturedUrl = cleanUrl
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            // 쿠키 동기화 시도
            cookieManager.flush() 
            
            // 쿠키 수집
            val videoCookie = cookieManager.getCookie(capturedUrl) ?: ""
            val playerCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
            
            val combinedCookies = listOf(videoCookie, playerCookie)
                .filter { it.isNotEmpty() }
                .joinToString("; ") { it.trim().removeSuffix(";") }

            println("[TVWiki v112] [Bunny] 쿠키 병합 완료: ${combinedCookies.isNotEmpty()}")

            // 헤더 설정 (v111의 성공 설정 유지: No UA, Main Referer, No Origin)
            val playbackHeaders = mutableMapOf(
                "Referer" to "https://player.bunny-frame.online/",
                "Accept" to "*/*"
            )

            if (combinedCookies.isNotEmpty()) {
                playbackHeaders["Cookie"] = combinedCookies
            }
            
            println("[TVWiki v112] [Bunny] 최종 재생 헤더 설정: $playbackHeaders")
            
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
        
        println("[TVWiki v112] [Bunny] 최종 실패")
        return false
    }
}
