package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.trim()

        // [핵심 수정 1] 로그 분석 결과: URL의 src 파라미터 ID가 세션과 직결됨
        // HTML 본문에서 찾는 것보다 URL에서 직접 추출하는 것이 100% 정확함
        val idMatch = Regex("""src=([a-z0-9]{32,})""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""/v/f/([a-z0-9]{32,})""").find(cleanUrl)?.groupValues?.get(1)

        if (idMatch == null) return

        val serverNum = Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"

        // [핵심 수정 2] 헤더 최적화 (실제 브라우저와 동일하게)
        val baseHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9",
            "Connection" to "keep-alive"
        )

        // 1. 플레이어 페이지 접속하여 초기 세션 쿠키 획득
        val playerRes = app.get(cleanUrl, referer = referer, headers = baseHeaders)
        val initialCookies = playerRes.cookies

        // 2. c.html 접속 (인증 쿠키 획득 단계)
        val tokenUrl = "$domain/v/f/$idMatch/c.html"
        
        // c.html 요청 시에는 반드시 cors 모드 헤더와 이전 쿠키가 필요함
        val tokenHeaders = baseHeaders.toMutableMap().apply {
            put("Referer", cleanUrl)
            put("Sec-Fetch-Dest", "empty")
            put("Sec-Fetch-Mode", "cors")
            put("Sec-Fetch-Site", "same-origin")
        }

        var tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = tokenHeaders, cookies = initialCookies)

        // [핵심 수정 3] c.html 응답이 비어있을 경우에만 아주 짧게 대기 (스레드 차단 최소화)
        if (tokenRes.text.isNullOrEmpty()) {
            Thread.sleep(1500) // 10초는 너무 길어서 세션 끊김. 1.5초가 적당함.
            tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = tokenHeaders, cookies = initialCookies)
        }

        val finalCookies = initialCookies + tokenRes.cookies
        
        // c.html 응답에서 m3u8 주소 추출 시도, 없으면 기본값 사용
        val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text ?: "")?.groupValues?.get(1)
            ?: "$domain/v/f/$idMatch/index.m3u8"

        // 3. 최종 링크 생성
        val cookieString = finalCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name = "$name (PoorCDN)",
                url = realM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = cleanUrl
                this.headers = baseHeaders.toMutableMap().apply {
                    put("Cookie", cookieString)
                    put("Referer", cleanUrl)
                    put("Origin", "https://player.bunny-frame.online")
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
