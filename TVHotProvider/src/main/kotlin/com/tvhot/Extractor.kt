package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.delay
import java.net.URI

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 안드로이드 실기기 로그와 일치하는 브라우저 정보 (IP 밴 방지 및 세션 유지)
    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        // 중요: c.html 요청 시 Referer는 반드시 Player URL이어야 함
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: cleanUrl

        // serverNum 추출 로직 강화: s= 파라미터 또는 도메인에서 숫자 추출
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: "9" 
        
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속 및 초기 쿠키 생성
        // 이 단계는 보통 성공하므로 1회 시도
        val playerRes = try {
            app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        } catch (e: Exception) {
            return // 플레이어 접속조차 안되면 종료
        }

        val responseText = playerRes.text
        val initialCookies = playerRes.cookies

        // ID 추출: src 파라미터 또는 URL 경로에서 32자 이상 ID 추출
        val idRegex = Regex("""(?i)(?:/v/f/|src=)([a-z0-9]{32,})""")
        val idMatch = idRegex.find(responseText)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            // 2. c.html 접속하여 보안 세션 쿠키 업데이트 (핵심 재시도 로직)
            // 실패 시 쿠키 없이 요청하는 Fallback을 제거하고, 성공할 때까지 재시도함.
            var finalCookies: Map<String, String> = initialCookies
            var tokenFetched = false
            
            // 최대 5회 재시도, 실패 시마다 대기 시간 증가 (Exponential Backoff)
            for (attempt in 1..5) {
                try {
                    // c.html 요청 시에도 이전 단계의 쿠키를 포함해야 세션이 유지될 수 있음
                    val tokenRes = app.get(
                        tokenUrl, 
                        referer = cleanUrl, 
                        headers = browserHeaders, 
                        cookies = initialCookies,
                        timeout = 15L // 타임아웃을 넉넉하게 설정
                    )

                    if (tokenRes.code == 200) {
                        // 성공 시 쿠키 병합
                        finalCookies = initialCookies + tokenRes.cookies
                        
                        // c.html 내부에서 실제 m3u8 주소가 변경되었는지 확인 (리다이렉트 등)
                        val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                            ?: directM3u8
                        
                        invokeLink(realM3u8, cleanUrl, finalCookies, callback)
                        tokenFetched = true
                        break // 성공하면 루프 탈출
                    }
                } catch (e: Exception) {
                    // 로그에만 남기고 재시도 대기
                    // e.printStackTrace() 
                }
                
                // 실패 시 대기: 1초, 2초, 3초... 늘려감
                delay(1000L * attempt)
            }

            // 5번 시도 후에도 실패했다면, 로그 상 403이 확정이므로
            // 억지로 요청을 보내지 않고 종료하거나, 최후의 수단으로 원본만 시도 (선택 사항)
            // 여기서는 사용자 요청대로 "응답하는 일이 없도록" 확실하지 않으면 보내지 않음.
            if (!tokenFetched) {
                // 정말 만약의 경우를 대비해 마지막으로 한 번 시도하되, 기대는 하지 않음.
                // 하지만 로그 분석 결과 쿠키 없는 요청은 100% 403이므로 생략하는 것이 깔끔함.
                // System.out.println("TVHot: Token fetch failed after 5 attempts.")
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                // Referer와 Cookie가 정확해야 403을 피함
                this.referer = referer
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
