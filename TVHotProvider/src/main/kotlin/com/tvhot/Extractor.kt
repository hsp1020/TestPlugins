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

            // -------------------------------------------------------------------------
            // 2. 🎯 토큰 추출 시도 (다양한 패턴 적용)
            // -------------------------------------------------------------------------
            
            // 공통 변수 (경로 및 서버번호 추출)
            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) 
                ?: pathRegex.find(cleanUrl) 
                ?: if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
                
            val path = pathMatch?.value ?: ""
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every${serverNum}.poorcdn.com"

            // 결과 URL (찾으면 여기에 저장)
            var finalM3u8Url: String? = null

            // [패턴 1] 전체 URL 매칭 (c.html?token=...)
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
                pl("req=$reqId step=token_url_found_p1", "ok=true url=$tokenUrl")
                finalM3u8Url = tokenUrl.replace("/c.html", "/index.m3u8")
            }

            // [패턴 2] 쿼리 스트링 매칭 (token=xxx&expires=yyy) - URL 없이 파라미터만 있는 경우
            if (finalM3u8Url == null) {
                val queryParamsMatch = Regex("""token=([^&"']+)&expires=(\d+)""").find(text)
                if (queryParamsMatch != null && path.isNotEmpty()) {
                    val token = queryParamsMatch.groupValues[1]
                    val expires = queryParamsMatch.groupValues[2]
                    pl("req=$reqId step=token_url_found_p2", "token=$token expires=$expires")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$token&expires=$expires"
                }
            }

            // [패턴 3] 개별 변수 매칭 (var token = "xxx";)
            if (finalM3u8Url == null) {
                val tokenVal = Regex("""["']?token["']?\s*[:=]\s*["']([^"']+)["']""").find(text)?.groupValues?.get(1)
                val expiresVal = Regex("""["']?expires["']?\s*[:=]\s*["']?(\d+)["']?""").find(text)?.groupValues?.get(1)
                
                if (tokenVal != null && expiresVal != null && path.isNotEmpty()) {
                    pl("req=$reqId step=token_url_found_p3", "token=$tokenVal expires=$expiresVal")
                    finalM3u8Url = "$domain$path/index.m3u8?token=$tokenVal&expires=$expiresVal"
                }
            }

            // -------------------------------------------------------------------------
            // 3. 결과 처리
            // -------------------------------------------------------------------------
            if (finalM3u8Url != null) {
                // ✅ 토큰 찾음 -> 바로 m3u8 생성
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
                // ❌ 토큰 못 찾음 -> Fallback (기존 방식, 403 가능성 높음)
                pl("req=$reqId step=token_url_not_found", "ok=false")
                
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
