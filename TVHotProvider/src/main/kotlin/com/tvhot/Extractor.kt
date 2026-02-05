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

    private fun pl(tag: String, msg: String) {
        println("DEBUG_EXTRACTOR name=$name $tag $msg")
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
        val reqId = System.currentTimeMillis().toDouble()
        pl("req=$reqId step=start", "ok=true url=$url referer=$referer thumbnailHint=$thumbnailHint")

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer

        return try {
            // 1. 임베드 페이지 가져오기
            pl("req=$reqId step=fetch_page_begin", "ok=true GET=$cleanUrl")
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url
            pl("req=$reqId step=page_text_ok", "ok=true textLen=${text.length}")

            // -------------------------------------------------------------------------
            // 2. 🎯 토큰 추출 시도 (Ultra Pattern Mode)
            // -------------------------------------------------------------------------
            
            // 공통 정보 추출
            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) 
                ?: pathRegex.find(cleanUrl) 
                ?: if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            
            val path = pathMatch?.value ?: ""
            // s 파라미터가 없으면 9번 서버 기본값
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every${serverNum}.poorcdn.com"

            var finalM3u8Url: String? = null

            // [패턴 1] URL 통째로 찾기 (가장 정확)
            val fullUrlPattern = Regex("""(https://every\d+\.poorcdn\.com/v/[a-z]/[a-zA-Z0-9]+/c\.html\?[^"'\s<>]+)""")
            val fullUrlMatch = fullUrlPattern.find(text)

            if (fullUrlMatch != null) {
                val rawTokenUrl = fullUrlMatch.groupValues[1]
                val tokenUrl = rawTokenUrl
                    .replace("&amp;", "&")
                    .replace(Regex("""expires=[\d.e+E]+""")) { matchResult ->
                        val expiresStr = matchResult.value.substringAfter("=")
                        val expiresInt = if ('e' in expiresStr.lowercase()) {
                            expiresStr.toDoubleOrNull()?.toLong() ?: expiresStr
                        } else {
                            expiresStr
                        }
                        "expires=$expiresInt"
                    }
                pl("req=$reqId step=token_found_ultra_p1", "url=$tokenUrl")
                finalM3u8Url = tokenUrl.replace("/c.html", "/index.m3u8")
            }

            // [패턴 2] "token" 키워드 뒤의 긴 문자열 무조건 잡기 (범용성 ↑)
            if (finalM3u8Url == null) {
                // token 혹은 _token 뒤에 오는 =, :, 공백 등을 건너뛰고 20자 이상의 문자열 추출
                val roughTokenMatch = Regex("""(?:token|_token)["']?\s*[:=]\s*["']?([a-zA-Z0-9_\-=]{20,})["']?""").find(text)
                // expires 혹은 _expires 뒤에 오는 숫자 추출
                val roughExpiresMatch = Regex("""(?:expires|_expires)["']?\s*[:=]\s*["']?(\d{8,})["']?""").find(text)

                if (roughTokenMatch != null && roughExpiresMatch != null && path.isNotEmpty()) {
                    val tokenVal = roughTokenMatch.groupValues[1]
                    val expiresVal = roughExpiresMatch.groupValues[2]
                    pl("req=$reqId step=token_found_ultra_p2", "token=$tokenVal expires=$expiresVal")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$tokenVal&expires=$expiresVal"
                }
            }

            // [패턴 3] 쿼리 스트링 파싱 (token=...&expires=...)
            if (finalM3u8Url == null) {
                val queryParamsMatch = Regex("""token=([^&"']+)&expires=(\d+)""").find(text)
                if (queryParamsMatch != null && path.isNotEmpty()) {
                    val token = queryParamsMatch.groupValues[1]
                    val expires = queryParamsMatch.groupValues[2]
                    pl("req=$reqId step=token_found_ultra_p3", "token=$token expires=$expires")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$token&expires=$expires"
                }
            }

            // -------------------------------------------------------------------------
            // 3. 결과 처리
            // -------------------------------------------------------------------------
            if (finalM3u8Url != null) {
                pl("req=$reqId step=m3u8_url_built", "ok=true url=$finalM3u8Url")
                
                val m3u8Headers = browserHeaders.toMutableMap().apply { put("Referer", cleanUrl) }
                M3u8Helper.generateM3u8(
                    name,
                    finalM3u8Url,
                    cleanUrl,
                    headers = m3u8Headers
                ).forEach(callback)
                
                pl("req=$reqId step=success", "ok=true method=token")
                return true
            } else {
                // [최후의 수단] 페이지 덤프 (500자) - 패턴 못 찾았을 때 분석용
                pl("req=$reqId step=token_url_not_found", "ok=false DUMP=${text.take(500)}")
                
                if (path.isEmpty()) {
                    pl("req=$reqId step=fail", "ok=false reason=no_path")
                    return false
                }
                
                val directM3u8 = "$domain$path/index.m3u8"
                pl("req=$reqId step=fallback_m3u8", "ok=true url=$directM3u8")
                
                val m3u8Headers = browserHeaders.toMutableMap().apply { put("Referer", cleanUrl) }
                M3u8Helper.generateM3u8(
                    name,
                    directM3u8,
                    cleanUrl,
                    headers = m3u8Headers
                ).forEach(callback)
                
                return true
            }

        } catch (e: Exception) {
            pl("req=$reqId step=error", "ok=false error=${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
