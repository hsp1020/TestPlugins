package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    // Referer 긁어오기용 헤더 (tvmon.site)
    private val tvMonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Referer" to "https://tvmon.site/",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Upgrade-Insecure-Requests" to "1"
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
        val reqId = System.currentTimeMillis().toDouble()
        pl("req=$reqId step=start", "ok=true url=$url referer=$referer")

        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        // -------------------------------------------------------------------------
        // 1. [Refetch Logic] URL이 부실하면 Referer에서 iframe src 다시 긁어오기
        // -------------------------------------------------------------------------
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && cleanReferer != null) {
            pl("req=$reqId step=refetch_start", "msg=URL seems incomplete, fetching referer")
            try {
                val refRes = app.get(cleanReferer, headers = tvMonHeaders)
                val refText = refRes.text
                
                // iframe src 추출 (유연한 정규식)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    pl("req=$reqId step=iframe_found", "newUrl=$cleanUrl")
                } else {
                    pl("req=$reqId step=iframe_not_found", "msg=Using original url")
                }
            } catch (e: Exception) {
                pl("req=$reqId step=refetch_error", "msg=${e.message}")
            }
        }

        // -------------------------------------------------------------------------
        // 2. 서버 정보 및 비디오 경로 추출
        // -------------------------------------------------------------------------
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 경로 추출 (/v/f/ID 또는 /v/e/ID)
        val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
        
        if (videoPathMatch != null) {
            val path = videoPathMatch.groupValues[1]
            val id = videoPathMatch.groupValues[2]
            
            val tokenUrl = "$domain$path$id/c.html"
            val directM3u8 = "$domain$path$id/index.m3u8"
            
            pl("req=$reqId step=path_found", "tokenUrl=$tokenUrl")

            try {
                // 3. c.html 접속 (Referer를 iframe 주소로 고정)
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                val cookieMap = tokenRes.cookies.toMutableMap()
                
                // [핵심] 자바스크립트 쿠키 파싱
                val jsCookieRegex = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                jsCookieRegex.findAll(tokenRes.text).forEach { match ->
                    cookieMap[match.groupValues[1]] = match.groupValues[2]
                }

                // 4. c.html 내용에서 진짜 m3u8 주소 추출
                val realM3u8 = extractM3u8FromToken(tokenRes.text)
                
                if (realM3u8 != null) {
                    val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                    pl("req=$reqId step=success", "m3u8=$finalM3u8")
                    
                    // 쿠키와 함께 m3u8 호출
                    invokeLink(finalM3u8, cleanUrl, cookieMap, callback)
                } else {
                    pl("req=$reqId step=token_parse_fail", "msg=Fallback to direct")
                    invokeLink(directM3u8, cleanUrl, cookieMap, callback)
                }

            } catch (e: Exception) {
                pl("req=$reqId step=error", "msg=${e.message}")
                invokeLink(directM3u8, cleanUrl, emptyMap(), callback)
            }
        } else {
            pl("req=$reqId step=fail", "msg=No video path found in URL")
        }
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        val patterns = listOf(
            Regex("""["']([^"']+\.m3u8\?[^"']+)["']"""), // 토큰 포함 주소
            Regex("""["']([^"']+\.m3u8)["']"""),         // 일반 주소
            Regex("""location\.href\s*=\s*["']([^"']+)["']""")
        )
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) return match.groupValues[1]
        }
        return null
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        
        // 쿠키 문자열 생성
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        M3u8Helper.generateM3u8(
            name,
            cleanM3u8,
            referer,
            headers = browserHeaders.toMutableMap().apply {
                if (cookieString.isNotEmpty()) {
                    put("Cookie", cookieString)
                }
            }
        ).forEach(callback)
    }
}
