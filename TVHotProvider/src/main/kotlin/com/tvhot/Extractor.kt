package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    // 안전한 로그 출력 헬퍼
    private fun pl(tag: String, msg: String) {
        println("DEBUG_EXTRACTOR $tag $msg")
    }

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
        pl("start", "url=$url")

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer

        return try {
            // 1. 임베드 페이지(player.bunny-frame.online) 가져오기
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url
            pl("fetch_embed", "len=${text.length} finalUrl=$finalUrl")

            // 2. 경로(/v/f/...) 찾기
            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) 
                ?: pathRegex.find(cleanUrl) 
                ?: if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null

            if (pathMatch == null) {
                pl("fail", "No path found")
                return false
            }
            val path = pathMatch.value
            pl("path_found", "path=$path")

            // 3. 도메인(everyX.poorcdn.com) 찾기 (사용자 제보: every4도 토큰 있으면 됨)
            // 우선순위: 본문 매칭 -> URL 매칭 -> 썸네일 힌트 -> 파라미터(s=) -> 기본값(9)
            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)
            
            val domain = when {
                domainMatch != null -> domainMatch.groupValues[1]
                finalUrl.contains(path) -> {
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }
                else -> {
                    // s= 파라미터 확인
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    "https://every${serverNum}.poorcdn.com"
                }
            }
            pl("domain_found", "domain=$domain")

            // 4. [핵심 수정] 토큰 파라미터(token=..., expires=...) 찾기
            // 소스 코드 내에서 'token', 'expires' 키워드 근처의 값을 찾거나, 전체 URL 패턴을 찾음
            var tokenParams = ""
            
            // 패턴 1: var token = "xxx"; var expires = "123";
            val tokenVal = Regex("""["']?token["']?\s*[:=]\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
            val expiresVal = Regex("""["']?expires["']?\s*[:=]\s*["']?(\d+)["']?""").find(text)?.groupValues?.get(1)

            if (tokenVal != null && expiresVal != null) {
                tokenParams = "?token=$tokenVal&expires=$expiresVal"
                pl("token_found_v1", "params=$tokenParams")
            } else {
                // 패턴 2: URL 안에 포함된 경우 (c.html?token=...)
                val urlWithTokenMatch = Regex("""c\.html\?([^"']+)""").find(text)
                if (urlWithTokenMatch != null) {
                    tokenParams = "?" + urlWithTokenMatch.groupValues[1]
                    pl("token_found_v2", "params=$tokenParams")
                } else {
                    pl("token_not_found", "Trying without token (likely to fail on every4)")
                }
            }

            // 5. URL 조립 (토큰 포함)
            val cleanPath = path.replace(Regex("//v/"), "/v/")
            val tokenUrl = "$domain$cleanPath/c.html$tokenParams" // 여기에 토큰 붙임!
            val directM3u8 = "$domain$cleanPath/index.m3u8$tokenParams"
            
            pl("request_token", "url=$tokenUrl")

            // 6. c.html 요청 (토큰 파싱)
            val tokenHeaders = browserHeaders.toMutableMap().apply { put("Referer", cleanUrl) }
            val tokenRes = app.get(tokenUrl, headers = tokenHeaders)
            val tokenText = tokenRes.text
            pl("token_res", "len=${tokenText.length} code=${tokenRes.code}")

            // 쿠키 추출
            val cookieMap = mutableMapOf<String, String>()
            cookieMap.putAll(tokenRes.cookies)
            Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                .findAll(tokenText)
                .forEach { cookieMap[it.groupValues[1]] = it.groupValues[2] }

            // 7. 실제 m3u8 주소 추출 (c.html 안에 리다이렉트나 m3u8 링크가 있을 수 있음)
            val realM3u8Match = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)
            
            var realM3u8 = realM3u8Match?.groupValues?.getOrNull(1)
            
            if (realM3u8 == null) {
                // c.html 본문에 없으면 직접 만든 URL 사용 (토큰 포함)
                realM3u8 = directM3u8
            } else if (!realM3u8.startsWith("http")) {
                // 상대 경로면 절대 경로로 변환
                realM3u8 = "$domain$cleanPath/$realM3u8".replace("$cleanPath/$cleanPath", cleanPath)
                // 만약 추출된 링크에 토큰이 없으면 붙여줌
                if (!realM3u8.contains("token=")) {
                     realM3u8 += tokenParams
                }
            }
            
            pl("final_m3u8", "url=$realM3u8 cookieCount=${cookieMap.size}")

            // 8. M3U8 로드
            val m3u8Headers = tokenHeaders.toMutableMap()
            if (cookieMap.isNotEmpty()) {
                m3u8Headers["Cookie"] = cookieMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            }

            M3u8Helper.generateM3u8(
                name,
                realM3u8,
                cleanUrl,
                headers = m3u8Headers
            ).forEach(callback)

            return true

        } catch (e: Exception) {
            pl("error", "${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
