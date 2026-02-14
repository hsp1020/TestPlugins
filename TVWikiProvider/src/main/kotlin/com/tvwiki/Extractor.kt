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
    
    // Fiddler로 검증된 최신 Windows Chrome User-Agent
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[BunnyPoorCdn] getUrl 호출 - url: $url, referer: $referer")
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        println("[BunnyPoorCdn] extract 시작 ===================================")
        println("[BunnyPoorCdn] 입력 URL: $url")
        println("[BunnyPoorCdn] 입력 referer: $referer")
        println("[BunnyPoorCdn] thumbnailHint: $thumbnailHint")
        
        // 1. URL 디코딩 및 공백 제거 (HTML 엔티티 &amp; 처리 필수)
        var cleanUrl = url.replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
        println("[BunnyPoorCdn] cleanUrl 처리 후: $cleanUrl")
        
        // [중요] 리퍼러를 tvwiki로 강제 고정
        val cleanReferer = "https://tvwiki5.net/"
        println("[BunnyPoorCdn] 고정 referer: $cleanReferer")

        // 2. iframe 주소 따기 (재탐색 로직)
        // [수정] /v/ 만 있어도 유효한 주소로 인정하여 불필요한 재탐색 스킵
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("/e/") || cleanUrl.contains("/f/")
        println("[BunnyPoorCdn] 직접 URL 여부(isDirectUrl): $isDirectUrl")
        
        if (!isDirectUrl) {
            println("[BunnyPoorCdn] 직접 URL이 아님 - 재탐색 시작")
            try {
                // 직접 링크가 아닌 경우에만 페이지를 다시 긁어옴
                println("[BunnyPoorCdn] 리퍼러 페이지 요청: $cleanReferer")
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                println("[BunnyPoorCdn] 리퍼러 페이지 응답 코드: ${refRes.code}")
                
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    println("[BunnyPoorCdn] 재탐색 성공 - 새로운 cleanUrl: $cleanUrl")
                } else {
                    println("[BunnyPoorCdn] 재탐색 실패 - iframe을 찾을 수 없음")
                }
            } catch (e: Exception) {
                // 재탐색 실패 시 로그만 남기고 원래 URL로 시도
                println("[BunnyPoorCdn] 재탐색 중 오류: ${e.message}")
                e.printStackTrace()
            }
        } else {
            println("[BunnyPoorCdn] 직접 URL이므로 재탐색 생략")
        }

        var capturedUrl: String? = null

        // 3. c.html 요청 납치 (WebViewResolver)
        // 타임아웃을 30초로 넉넉하게 설정
        println("[BunnyPoorCdn] WebViewResolver 초기화")
        val resolver = WebViewResolver(
            interceptUrl = Regex("""/c\.html"""), 
            useOkhttp = false,
            timeout = 30000L
        )
        
        try {
            // [중요] WebView 요청 시 리퍼러와 UA를 정확하게 설정해야 서버가 403을 뱉지 않음
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )
            
            println("[BunnyPoorCdn] WebView 요청 시작 - URL: $cleanUrl")
            println("[BunnyPoorCdn] 요청 헤더: $requestHeaders")

            // cleanUrl(iframe) 접속 -> JS 실행 -> c.html 요청 가로채기
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            println("[BunnyPoorCdn] WebView 응답 받음")
            println("[BunnyPoorCdn] 최종 응답 URL: ${response.url}")
            println("[BunnyPoorCdn] 응답 코드: ${response.code}")

            // 토큰이 포함된 URL 획득 확인
            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                println("[BunnyPoorCdn] c.html URL 캡처 성공: $capturedUrl")
            } else {
                println("[BunnyPoorCdn] c.html URL 캡처 실패 - URL 패턴 불일치")
                println("[BunnyPoorCdn] response.text 길이: ${response.text.length}")
                // 응답 내용의 일부 로깅
                if (response.text.length > 500) {
                    println("[BunnyPoorCdn] response.text 첫 500자: ${response.text.substring(0, 500)}")
                } else {
                    println("[BunnyPoorCdn] response.text: ${response.text}")
                }
            }
        } catch (e: Exception) {
            println("[BunnyPoorCdn] WebViewResolver 실행 중 오류: ${e.message}")
            e.printStackTrace()
        }

        if (capturedUrl != null) {
            // [핵심] 획득한 c.html URL을 그대로 사용하되 끝에 #.m3u8을 붙여서 플레이어가 HLS로 인식하게 함
            
            // 쿠키 동기화
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            println("[BunnyPoorCdn] 쿠키 획득: ${cookie?.take(100)}...")

            // Fiddler 로그 기반 헤더 설정
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
                "sec-ch-ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
                "sec-ch-ua-mobile" to "?0",
                "sec-ch-ua-platform" to "\"Windows\""
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = "$capturedUrl#.m3u8"
            println("[BunnyPoorCdn] 최종 재생 URL 생성: $finalUrl")
            println("[BunnyPoorCdn] 헤더 설정: $headers")
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            println("[BunnyPoorCdn] callback 호출 완료 - 성공")
            println("[BunnyPoorCdn] extract 종료 ===================================")
            return true
        } 
        
        println("[BunnyPoorCdn] capturedUrl이 null - 실패")
        println("[BunnyPoorCdn] extract 종료 ===================================")
        return false
    }
}
