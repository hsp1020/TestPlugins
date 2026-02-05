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
        pl("req=$reqId step=start", "ok=true url=$url referer=$referer")

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer

        return try {
            pl("req=$reqId step=fetch_page_begin", "ok=true GET=$cleanUrl")
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url
            pl("req=$reqId step=page_text_ok", "ok=true textLen=${text.length}")

            // -------------------------------------------------------------------------
            // 2. 🎯 토큰 추출 (빌드 에러 없는 정규식 방식)
            // -------------------------------------------------------------------------
            
            // 공통 정보
            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) 
                ?: pathRegex.find(cleanUrl) 
                ?: if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            
            val path = pathMatch?.value ?: ""
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every${serverNum}.poorcdn.com"

            var finalM3u8Url: String? = null

            // [패턴 1] "token" 키워드 뒤의 긴 문자열 (가장 강력)
            // token: "...", token="...", token = '...' 등 모두 커버
            if (finalM3u8Url == null) {
                val roughTokenMatch = Regex("""token["']?\s*[:=]\s*["']?([a-zA-Z0-9_\-=]{20,})["']?""").find(text)
                val roughExpiresMatch = Regex("""expires["']?\s*[:=]\s*["']?(\d{8,})["']?""").find(text)

                if (roughTokenMatch != null && roughExpiresMatch != null && path.isNotEmpty()) {
                    val tokenVal = roughTokenMatch.groupValues[1]
                    val expiresVal = roughExpiresMatch.groupValues[2]
                    pl("req=$reqId step=token_found_p1", "token=$tokenVal expires=$expiresVal")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$tokenVal&expires=$expiresVal"
                }
            }

            // [패턴 2] URL 쿼리 스트링 (token=...&expires=...)
            if (finalM3u8Url == null) {
                val queryParamsMatch = Regex("""token=([^&"']+)&expires=(\d+)""").find(text)
                if (queryParamsMatch != null && path.isNotEmpty()) {
                    val token = queryParamsMatch.groupValues[1]
                    val expires = queryParamsMatch.groupValues[2]
                    pl("req=$reqId step=token_found_p2", "token=$token expires=$expires")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$token&expires=$expires"
                }
            }

            // [패턴 3] 전체 URL 매칭 (c.html?token=...)
            if (finalM3u8Url == null) {
                val fullUrlPattern = Regex("""(https://every\d+\.poorcdn\.com/v/[a-z]/[a-zA-Z0-9]+/c\.html\?[^"'\s<>]+)""")
                val fullUrlMatch = fullUrlPattern.find(text)
                if (fullUrlMatch != null) {
                    val rawTokenUrl = fullUrlMatch.groupValues[1]
                        .replace("&amp;", "&")
                        .replace(Regex("""expires=[\d.e+E]+""")) { matchResult ->
                            val expiresStr = matchResult.value.substringAfter("=")
                            val expiresInt = if ('e' in expiresStr.lowercase()) { // 빌드에러 수정됨
                                expiresStr.toDoubleOrNull()?.toLong() ?: expiresStr
                            } else {
                                expiresStr
                            }
                            "expires=$expiresInt"
                        }
                    pl("req=$reqId step=token_found_p3", "url=$rawTokenUrl")
                    finalM3u8Url = rawTokenUrl.replace("/c.html", "/index.m3u8")
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
                return true
            } else {
                // ❌ 토큰 못 찾음 -> DUMP 출력 (분석용)
                pl("req=$reqId step=token_not_found", "DUMP=${text.take(1000)}") // 1000자 덤프
                
                if (path.isEmpty()) return false
                
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
