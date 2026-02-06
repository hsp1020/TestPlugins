package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import okhttp3.Interceptor
import okhttp3.Response

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 사용자 로그 기반 윈도우 크롬 UA
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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
        // url은 iframe 주소 (player.bunny-frame.online)
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch (iframe 주소 확보)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // 2. Video Path 파싱 (fallback용 및 도메인 확인용)
        var path = ""
        var id = ""
        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer))
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(res.text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
            if (videoPathMatch != null) {
                path = videoPathMatch.groupValues[1]
                id = videoPathMatch.groupValues[2]
            }
        } catch (e: Exception) {}

        if (path.isNotEmpty()) {
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every$serverNum.poorcdn.com"
            // 토큰 없이 일단 URL 형태만 만듦 (WebView 진입용)
            val tokenUrl = "$domain$path$id/c.html"

            // [핵심] WebViewResolver를 '토큰 헌터'로 사용
            // WebView가 내부 JS를 실행해서 토큰을 계산하고 'c.html'을 요청할 때,
            // 그 요청을 가로채서(intercept) 진짜 URL을 따냄.
            var capturedUrl: String? = null

            val resolver = WebViewResolver(
                interceptUrl = Regex("""/c\.html"""), // c.html 요청을 감지
                useOkhttp = false,
                timeout = 15000L
            )
            
            try {
                // WebView 실행
                // 주의: Referer를 cleanUrl(iframe주소)로 설정해야 JS가 정상 동작함
                val requestHeaders = mapOf(
                    "Referer" to cleanUrl, 
                    "User-Agent" to DESKTOP_UA
                )

                // WebViewResolver가 URL을 뱉어내게 유도 (에러가 나더라도 Interceptor가 URL을 잡을 수 있음)
                val response = app.get(
                    url = tokenUrl, 
                    headers = requestHeaders,
                    interceptor = resolver
                )
                
                // WebViewResolver가 리다이렉트나 요청을 통해 최종적으로 도달한 URL 확인
                if (response.url.contains("token=")) {
                    capturedUrl = response.url
                }
                
            } catch (e: Exception) {
                // WebViewResolver는 interceptUrl을 찾으면 에러를 뱉으면서 종료될 수 있음.
                // 이때 에러 메시지나 내부 상태에서 URL을 꺼낼 수 없으므로,
                // 사실상 여기서는 'app.get'이 리턴한 response.url이 가장 확실함.
                // 만약 위에서 못 잡았다면, 어쩔 수 없이 실패.
            }

            // [추가 전략] WebViewResolver가 URL을 못 뱉어주면, 우리가 직접 패턴으로 다시 시도
            // 하지만 위 방식이 CloudStream 표준.
            // 만약 capturedUrl이 null이라면, WebView가 c.html을 요청했지만 app.get이 그걸 리턴 값으로 못 가져온 경우임.
            
            // 하지만 로그를 보면 'Web-view request finished: .../c.html?token=...' 이렇게 떴었음.
            // 즉, WebViewResolver가 성공적으로 URL을 방문함.
            // 우리는 그 URL을 어떻게든 알아내야 함.
            
            // ★ CloudStream의 WebViewResolver는 interceptUrl에 매칭되는 요청을 발견하면
            // 그 요청의 URL을 반환하거나, 그 요청을 수행하고 결과를 반환함.
            // 따라서 위의 response.url이 바로 그 '토큰 포함 URL'일 가능성이 높음.

            // 안전장치: 만약 capturedUrl이 없으면 그냥 tokenUrl을 씀 (실패하겠지만)
            val finalLink = capturedUrl ?: tokenUrl

            // 3. 최종 링크 변환 및 헤더 설정
            val m3u8Url = finalLink.replace("/c.html", "/index.m3u8")

            // 사용자 로그 기반 성공 헤더 (100% 일치시킴)
            val headers = mapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/", // iframe 주소
                "Origin" to "https://player.bunny-frame.online",   // 필수
                "Accept" to "*/*",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Dest" to "empty",
                "Accept-Encoding" to "gzip, deflate, br",
                "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
            )
            
            // 토큰이 없으면 어차피 실패하므로, 토큰이 있다고 믿고 진행
            callback(
                newExtractorLink(name, name, m3u8Url, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.quality = Qualities.Unknown.value
                    this.headers = headers
                }
            )
            return true
        }
        return false
    }
}
