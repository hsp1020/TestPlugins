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

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // Fiddler로 검증된 최신 Windows Chrome User-Agent (WebView용)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[TVWiki v103] [Bunny] getUrl 호출 - url: $url, referer: $referer")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v103] [Bunny] extract 시작 ===================================")
        println("[TVWiki v103] [Bunny] 입력 URL: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"
        println("[TVWiki v103] [Bunny] cleanUrl: $cleanUrl")

        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        println("[TVWiki v103] [Bunny] 직접 URL 여부: $isDirectUrl")
        
        if (!isDirectUrl) {
            println("[TVWiki v103] [Bunny] 직접 URL 아님 -> 재탐색 시작")
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                println("[TVWiki v103] [Bunny] 리퍼러 페이지 응답 코드: ${refRes.code}")
                
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v103] [성공] 재탐색으로 URL 획득: $cleanUrl")
                } else {
                    println("[TVWiki v103] [실패] 재탐색으로 URL 획득 실패 (iframe 패턴 미일치)")
                }
            } catch (e: Exception) {
                println("[TVWiki v103] [에러] 재탐색 중 예외 발생: ${e.message}")
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
            println("[TVWiki v103] [Bunny] WebView 요청 시작. Headers: $requestHeaders")
            
            // WebView는 Desktop UA를 사용하여 봇 탐지 우회
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            println("[TVWiki v103] [Bunny] WebView 응답 코드: ${response.code}")
            println("[TVWiki v103] [Bunny] WebView 응답 URL: ${response.url}")

            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                println("[TVWiki v103] [성공] c.html URL 캡처됨: $capturedUrl")
            } else {
                println("[TVWiki v103] [실패] c.html 캡처 실패. (URL 패턴 불일치)")
            }
        } catch (e: Exception) {
            println("[TVWiki v103] [에러] WebView 실행 중 오류: ${e.message}")
            e.printStackTrace()
        }

        if (capturedUrl != null) {
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            println("[TVWiki v103] [Bunny] 쿠키 획득 여부: ${!cookie.isNullOrEmpty()}")

            // [수정] 재생용 헤더(playbackHeaders)는 심플하게 설정
            // 2000 Error(Protocol Error) 방지: Windows UA 강제 제거
            val playbackHeaders = mutableMapOf(
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )

            if (!cookie.isNullOrEmpty()) {
                playbackHeaders["Cookie"] = cookie
            }
            
            println("[TVWiki v103] [Bunny] 최종 재생 헤더 설정: $playbackHeaders")
            
            val finalUrl = "$capturedUrl#.m3u8"
            println("[TVWiki v103] [Bunny] 최종 URL: $finalUrl")
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = playbackHeaders // 수정된 헤더 적용
                }
            )
            println("[TVWiki v103] [Bunny] Callback 호출 완료 (성공)")
            println("[TVWiki v103] [Bunny] extract 종료 ===================================")
            return true
        } 
        
        println("[TVWiki v103] [Bunny] 최종 실패 (capturedUrl is null)")
        println("[TVWiki v103] [Bunny] extract 종료 ===================================")
        return false
    }
}
