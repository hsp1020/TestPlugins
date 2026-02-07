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
        Log.e(TAG, "[Bunny] Extract 시작됨")
        Log.d(TAG, "[Bunny] 입력된 URL: $url")
        Log.d(TAG, "[Bunny] 입력된 Referer: $referer")

        // 1. URL 정리 (HTML 엔티티 제거)
        var cleanUrl = url.replace("&amp;", "&").trim()
        Log.d(TAG, "[Bunny] 1차 정리된 URL: $cleanUrl")

        // Referer 설정: 입력값이 없으면 기본값, 있으면 입력값 사용
        // [중요] 만약 url 자체가 드라마 페이지라면 그것을 리퍼러로 사용
        val targetPage = if (referer.isNullOrEmpty()) "https://tvwiki5.net/" else referer
        Log.d(TAG, "[Bunny] 사용할 Target Page (Referer): $targetPage")

        // 2. iframe 주소인지 확인
        val isDirectUrl = cleanUrl.contains("bunny-frame.online") || cleanUrl.contains("/v/")
        Log.d(TAG, "[Bunny] 직접 플레이어 URL인가?: $isDirectUrl")

        if (!isDirectUrl) {
            Log.w(TAG, "[Bunny] 직접 URL이 아님. HTML에서 iframe 탐색 시작. 타겟: $targetPage")
            try {
                // TVWiki 페이지를 긁어옴
                val refRes = app.get(targetPage, headers = mapOf("User-Agent" to DESKTOP_UA))
                val html = refRes.text
                Log.d(TAG, "[Bunny] 페이지 HTML 로드 성공 (길이: ${html.length})")
                
                // 정규식으로 iframe src 추출 (홑따옴표, 쌍따옴표 모두 대응)
                val iframeMatch = Regex("""src=['"](https://player\\.bunny-frame\\.online/[^"']+)['"]""").find(html)
                    ?: Regex("""data-player\\d*=['"](https://player\\.bunny-frame\\.online/[^"']+)['"]""").find(html)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                    Log.d(TAG, "[Bunny] Iframe URL 추출 성공: $cleanUrl")
                } else {
                    Log.e(TAG, "[Bunny] ⚠️ Iframe URL 추출 실패! 정규식 매칭 안됨.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "[Bunny-Error] 페이지 로드 중 에러 발생", e)
            }
        }

        // 3. WebViewResolver 설정
        var capturedUrl: String? = null
        val interceptPattern = Regex("""/c\\.html""") 
        Log.d(TAG, "[Bunny] WebViewResolver 준비. 타겟 패턴: $interceptPattern")

        val resolver = WebViewResolver(
            interceptUrl = interceptPattern, 
            useOkhttp = false,
            timeout = 30000L // 30초 대기
        )

        try {
            val requestHeaders = mapOf(
                "Referer" to targetPage, 
                "User-Agent" to DESKTOP_UA,
                "Upgrade-Insecure-Requests" to "1"
            )

            Log.d(TAG, "[Bunny] WebView 요청 시작. URL: $cleanUrl")

            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            Log.d(TAG, "[Bunny] WebView 응답 수신. 응답 URL: ${response.url}")

            if (response.url.contains("/c.html") && response.url.contains("token=")) {
                capturedUrl = response.url
                Log.i(TAG, "[Bunny] ✅ 토큰 URL 획득 성공: $capturedUrl")
            } else {
                Log.e(TAG, "[Bunny] ❌ 응답 URL이 c.html 패턴과 다름.")
            }

        } catch (e: Exception) {
            Log.e(TAG, "[Bunny-Error] WebView 실행 중 에러 발생", e)
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

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = "$capturedUrl#.m3u8"
            Log.i(TAG, "[Bunny] 최종 콜백 URL: $finalUrl")
            
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
            Log.e(TAG, "================ 성공 종료 ================")
            return true
        } else {
            Log.e(TAG, "[Bunny] ❌ capturedUrl이 null임. 실패.")
            return false
        }
    }
}
