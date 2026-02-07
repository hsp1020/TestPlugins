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
    private val TAG = "TVWIKI_DEBUG" // 로그캣 필터용 태그

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
        Log.e(TAG, "==================================================")
        Log.e(TAG, "[1] Extract 시작됨")
        Log.d(TAG, "[1-1] 입력된 URL: $url")
        Log.d(TAG, "[1-2] 입력된 Referer: $referer")

        // 1. URL 정리 (HTML 엔티티 제거)
        var cleanUrl = url.replace("&amp;", "&").trim()
        Log.d(TAG, "[2] 1차 정리된 URL: $cleanUrl")

        // 기본 리퍼러 설정
        val cleanReferer = "https://tvwiki5.net/"

        // 2. iframe 주소인지 확인 (재탐색 로직)
        // /v/ 만 포함되어 있어도 플레이어 주소로 인정
        val isDirectUrl = cleanUrl.contains("/v/") || cleanUrl.contains("bunny-frame.online")
        
        Log.d(TAG, "[3] 직접 플레이어 URL인가?: $isDirectUrl")

        if (!isDirectUrl) {
            Log.w(TAG, "[3-1] 직접 URL이 아님. HTML에서 iframe src 탐색 시작")
            try {
                // TVWiki 페이지를 긁어옴
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val html = refRes.text
                Log.d(TAG, "[3-2] 페이지 HTML 로드 성공 (길이: ${html.length})")
                
                // 정규식으로 iframe src 추출 (홑따옴표, 쌍따옴표 모두 대응)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(html)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(html)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    Log.d(TAG, "[3-3] Iframe URL 추출 성공: $cleanUrl")
                } else {
                    Log.e(TAG, "[3-3] ⚠️ Iframe URL 추출 실패! 정규식 매칭 안됨.")
                    // 여기서 실패하면 사실상 끝임
                }
            } catch (e: Exception) {
                Log.e(TAG, "[3-Error] 페이지 로드 중 에러 발생", e)
            }
        } else {
            Log.d(TAG, "[3-1] 이미 플레이어 URL임. 재탐색 생략.")
        }

        // 3. WebViewResolver 설정
        var capturedUrl: String? = null
        
        // 인터셉트할 패턴: c.html 또는 m3u8
        val interceptPattern = Regex("""/c\.html""") 
        
        Log.d(TAG, "[4] WebViewResolver 준비. 타겟 패턴: $interceptPattern")

        val resolver = WebViewResolver(
            interceptUrl = interceptPattern, 
            useOkhttp = false,
            timeout = 30000L // 30초 대기
        )

        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA,
                "Upgrade-Insecure-Requests" to "1"
            )

            Log.d(TAG, "[5] WebView 요청 시작. URL: $cleanUrl")
            Log.d(TAG, "[5-1] WebView 헤더: $requestHeaders")

            // cleanUrl(iframe) 접속 -> JS 실행 -> c.html 요청 가로채기
            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            Log.d(TAG, "[6] WebView 응답 수신. 응답 URL: ${response.url}")

            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                Log.i(TAG, "[6-1] ✅ 토큰 URL 획득 성공: $capturedUrl")
            } else {
                Log.e(TAG, "[6-1] ❌ 응답 URL이 c.html 패턴과 다름.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "[WebView-Error] WebView 실행 중 에러 발생", e)
        }

        if (capturedUrl != null) {
            Log.d(TAG, "[7] 최종 링크 추출 단계 진입")
            
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)
            Log.d(TAG, "[7-1] 획득한 쿠키: $cookie")

            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = "$capturedUrl#.m3u8"
            Log.i(TAG, "[Final] 콜백으로 전달할 최종 URL: $finalUrl")
            
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            Log.e(TAG, "================ 성공 종료 ================")
            return true
        } else {
            Log.e(TAG, "[Result] ❌ capturedUrl이 null임. 링크 추출 실패.")
            Log.e(TAG, "================ 실패 종료 ================")
            return false
        }
    }
}
