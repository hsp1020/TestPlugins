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

// [v115] Extractor.kt: 'app.get()'을 사용한 쿠키 직접 수집 및 Main Referer 적용 (2000 & 2004 동시 해결 시도)
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
        println("[TVWiki v115] [Bunny] getUrl 호출 - url: $url")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[TVWiki v115] [Bunny] extract 시작: $url")
        
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = "https://tvwiki5.net/"

        // 1. iframe 재탐색 로직
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        if (!isDirectUrl) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[TVWiki v115] [성공] 재탐색으로 URL 획득: $cleanUrl")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        var capturedUrl = cleanUrl

        // 2. [v115 핵심] app.get()으로 요청을 보내 쿠키(Set-Cookie)를 직접 수집
        // WebViewResolver는 비동기라 쿠키 수집에 실패했으므로, 동기식 요청으로 변경
        val cookieMap = mutableMapOf<String, String>()
        
        try {
            println("[TVWiki v115] [Bunny] 쿠키 수집 시도 (app.get): $capturedUrl")
            val response = app.get(
                url = capturedUrl,
                headers = mapOf("User-Agent" to DESKTOP_UA, "Referer" to cleanReferer)
            )
            
            // 응답 쿠키 수집
            response.cookies.forEach { (k, v) -> cookieMap[k] = v }
            
            // 만약 URL이 리다이렉트되었다면, 최종 URL 업데이트
            if (response.url != capturedUrl) {
                println("[TVWiki v115] [Bunny] URL 리다이렉트 감지: ${response.url}")
                capturedUrl = response.url
            }
            println("[TVWiki v115] [Bunny] 수집된 쿠키 개수: ${cookieMap.size}")

        } catch (e: Exception) {
            println("[TVWiki v115] [Bunny] 쿠키 수집 중 에러 (무시하고 진행): ${e.message}")
        }

        // CookieManager에서도 혹시 모를 쿠키 긁어오기
        val cookieManager = CookieManager.getInstance()
        val videoCookie = cookieManager.getCookie(capturedUrl) ?: ""
        val playerCookie = cookieManager.getCookie("https://player.bunny-frame.online") ?: ""
        
        // 쿠키 문자열 조립
        val sb = StringBuilder()
        cookieMap.forEach { (k, v) -> sb.append("$k=$v; ") }
        if (videoCookie.isNotEmpty()) sb.append(videoCookie).append("; ")
        if (playerCookie.isNotEmpty()) sb.append(playerCookie).append("; ")
        
        val finalCookie = sb.toString().trim().removeSuffix(";")
        println("[TVWiki v115] [Bunny] 최종 쿠키: ${if(finalCookie.isNotEmpty()) "있음" else "없음"}")

        // 3. 헤더 설정 (Video: Main Referer, Key: Cookie)
        val playbackHeaders = mutableMapOf(
            "User-Agent" to DESKTOP_UA,
            "Referer" to "https://player.bunny-frame.online/", // Main Referer (403 방지)
            "Origin" to "https://player.bunny-frame.online",   // Main Origin
            "Accept" to "*/*"
        )

        if (finalCookie.isNotEmpty()) {
            playbackHeaders["Cookie"] = finalCookie
        }
        
        println("[TVWiki v115] [Bunny] 최종 재생 헤더 설정: $playbackHeaders")
        
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
}
