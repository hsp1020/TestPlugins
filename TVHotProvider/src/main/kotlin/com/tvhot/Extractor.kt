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

        // 1. 플레이어 페이지 접속 (초기 세션 쿠키 획득)
        val playerRes = app.get(cleanUrl, referer = referer, headers = mapOf("User-Agent" to USER_AGENT))
        val html = playerRes.text
        val initialCookies = playerRes.cookies

        // [핵심 수정] upnext 리스트에 있는 ID에 낚이지 않도록 upnext 이전까지만 자름
        val currentVideoHtml = if (html.contains("upnext")) {
            html.substringBefore("upnext")
        } else {
            html
        }

        // 현재 재생 중인 영상의 32자리 ID만 정확히 추출
        val idRegex = Regex("""/v/f/([a-z0-9]{32,})""")
        val idMatch = idRegex.find(currentVideoHtml)?.groupValues?.get(1)
            ?: idRegex.find(cleanUrl)?.groupValues?.get(1) // URL에도 있을 수 있으니 fallback

        if (idMatch != null) {
            // 2. 서버 도메인 결정 (로그 상의 every5, every9 등)
            val serverNum = Regex("""every(\d+)""").find(html)?.groupValues?.get(1)
                ?: Regex("""s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
                ?: "9"
            val domain = "https://every$serverNum.poorcdn.com"

            // 3. c.html 호출 (쿠키 인증 단계)
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            
            // c.html은 빈 응답일 때가 많으므로, 쿠키 연동이 생명입니다.
            val tokenRes = app.get(
                tokenUrl,
                referer = cleanUrl,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Accept" to "*/*",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Dest" to "empty"
                ),
                cookies = initialCookies // 중요: 이전 단계의 쿠키를 수동으로 전달
            )

            // 만약 응답이 비어있다면 아주 짧게만 대기 후 재시도 (Thread.sleep 1초 미만)
            var finalTokenRes = tokenRes
            if (finalTokenRes.text.isNullOrEmpty()) {
                Thread.sleep(800) 
                finalTokenRes = app.get(tokenUrl, referer = cleanUrl, headers = mapOf("User-Agent" to USER_AGENT), cookies = initialCookies)
            }

            // 4. 모든 쿠키 병합 및 링크 전송
            val combinedCookies = initialCookies + finalTokenRes.cookies
            val cookieString = combinedCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val m3u8Url = "$domain/v/f/$idMatch/index.m3u8"

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = "$name (PoorCDN)",
                    url = m3u8Url,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = cleanUrl
                    this.headers = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Cookie" to cookieString,
                        "Referer" to cleanUrl,
                        "Origin" to "https://player.bunny-frame.online"
                    ).toMutableMap()
                    this.quality = Qualities.Unknown.value
                }
            )
        }
    }
}
