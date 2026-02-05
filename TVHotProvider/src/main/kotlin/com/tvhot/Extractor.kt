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
        pl("req=$reqId step=clean_url", "ok=true cleanUrl=$cleanUrl")

        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer
        pl("req=$reqId step=headers_ready", "ok=true hasReferer=${referer != null}")

        return try {
            // 1. 임베드 페이지(player.bunny-frame.online) 가져오기
            pl("req=$reqId step=fetch_page_begin", "ok=true GET=$cleanUrl")
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url
            pl("req=$reqId step=fetch_page_ok", "ok=true finalUrl=$finalUrl")
            pl("req=$reqId step=page_text_ok", "ok=true textLen=${text.length}")

            // 2. 🎯 poorcdn.com c.html URL (토큰 포함) 직접 추출
            val fullUrlPattern = Regex("""(https://every\d+\.poorcdn\.com/v/[a-z]/[a-zA-Z0-9]+/c\.html\?[^"'\s<>]+)""")
            val fullUrlMatch = fullUrlPattern.find(text)
            
            if (fullUrlMatch != null) {
                // ✅ 완전한 URL (토큰 포함) 발견!
                val tokenUrl = fullUrlMatch.groupValues[1]
                    .replace("&amp;", "&") // HTML 엔티티 디코딩
                    .replace(Regex("""expires=[\d.e+E]+""")) { matchResult ->
                        // 과학적 표기법(1.77e+09)을 정수로 변환
                        val expiresStr = matchResult.value.substringAfter("=")
                        val expiresInt = if ('e' in expiresStr.lowerCase()) {
                            expiresStr.toDoubleOrNull()?.toLong() ?: expiresStr
                        } else {
                            expiresStr
                        }
                        "expires=$expiresInt"
                    }
                
                pl("req=$reqId step=token_url_found", "ok=true url=$tokenUrl")
                
                // index.m3u8도 같은 토큰으로 만들기
                val m3u8Url = tokenUrl.replace("/c.html", "/index.m3u8")
                pl("req=$reqId step=m3u8_url_built", "ok=true url=$m3u8Url")
                
                // 3. M3U8 로드
                val m3u8Headers = browserHeaders.toMutableMap()
                m3u8Headers["Referer"] = cleanUrl
                
                pl("req=$reqId step=m3u8_generate_begin", "ok=true url=$m3u8Url referer=$cleanUrl")
                
                M3u8Helper.generateM3u8(
                    name,
                    m3u8Url,
                    cleanUrl,
                    headers = m3u8Headers
                ).forEach(callback)
                
                pl("req=$reqId step=m3u8_generate_ok", "ok=true linkCount=1")
                return true
                
            } else {
                // ❌ 토큰이 포함된 전체 URL을 찾지 못함 → 기존 방식으로 fallback
                pl("req=$reqId step=token_url_not_found", "ok=false")
                
                // 기존 경로 추출 로직
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(text) 
                    ?: pathRegex.find(cleanUrl) 
                    ?: if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
                
                if (pathMatch == null) {
                    pl("req=$reqId step=fail", "ok=false reason=no_path")
                    return false
                }
                
                val path = pathMatch.value
                val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                val domain = "https://every${serverNum}.poorcdn.com"
                val directM3u8 = "$domain$path/index.m3u8"
                
                pl("req=$reqId step=fallback_m3u8", "ok=true url=$directM3u8")
                
                val m3u8Headers = browserHeaders.toMutableMap()
                m3u8Headers["Referer"] = cleanUrl
                
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
