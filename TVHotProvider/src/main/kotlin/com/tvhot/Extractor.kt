package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    // [수정] Origin 제거, 최소한의 헤더만 사용
    private val baseHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    )

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

        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()
        
        // 쿠키 저장소 (모든 요청에서 공유)
        val cookieJar = mutableMapOf<String, String>()

        // 1. Refetch Logic
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && cleanReferer != null) {
            pl("req=$reqId step=refetch_start", "msg=URL seems incomplete, fetching referer")
            try {
                val refRes = app.get(cleanReferer, headers = tvMonHeaders)
                // 여기서 얻은 쿠키도 저장
                cookieJar.putAll(refRes.cookies)
                
                val refText = refRes.text
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
                    pl("req=$reqId step=iframe_found", "newUrl=$cleanUrl")
                }
            } catch (e: Exception) {
                pl("req=$reqId step=refetch_error", "msg=${e.message}")
            }
        }

        // 2. Visit Logic (쿠키 획득 목적 포함)
        // 무조건 한 번 방문해서 쿠키를 구워야 함 (Cloudflare 등)
        pl("req=$reqId step=visit_start", "msg=Visiting player page to get cookies")
        try {
            val visitHeaders = baseHeaders.toMutableMap().apply {
                put("Referer", cleanReferer ?: "https://tvmon.site/")
            }
            // 쿠키 적용
            val res = app.get(cleanUrl, headers = visitHeaders, cookies = cookieJar)
            cookieJar.putAll(res.cookies) // 쿠키 업데이트
            
            val text = res.text
            pl("req=$reqId step=visit_done", "len=${text.length}")
            
            // 경로 찾기
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl) // URL에도 있을 수 있음

            if (videoPathMatch != null) {
                val path = videoPathMatch.groupValues[1]
                val id = videoPathMatch.groupValues[2]
                
                val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                val domain = "https://every$serverNum.poorcdn.com"
                
                val tokenUrl = "$domain$path$id/c.html"
                val directM3u8 = "$domain$path$id/index.m3u8"
                
                pl("req=$reqId step=path_final", "tokenUrl=$tokenUrl")
                
                // 3. Token 요청 (쿠키 필수!)
                try {
                    val tokenHeaders = baseHeaders.toMutableMap().apply {
                        put("Referer", cleanUrl) // Referer는 플레이어 주소
                        put("Origin", "https://player.bunny-frame.online") // 여기선 Origin 필요할 수도
                    }
                    
                    val tokenRes = app.get(tokenUrl, headers = tokenHeaders, cookies = cookieJar)
                    cookieJar.putAll(tokenRes.cookies)
                    
                    // JS 쿠키 파싱
                    val jsCookieRegex = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    jsCookieRegex.findAll(tokenRes.text).forEach { match ->
                        cookieJar[match.groupValues[1]] = match.groupValues[2]
                    }
                    
                    // #EXTM3U 체크
                    if (tokenRes.text.trim().startsWith("#EXTM3U")) {
                        pl("req=$reqId step=direct_m3u8_content", "msg=Content is M3U8")
                        invokeLink(tokenUrl, cleanUrl, cookieJar, callback)
                        return true
                    }

                    val realM3u8 = extractM3u8FromToken(tokenRes.text)
                    
                    if (realM3u8 != null) {
                        val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                        pl("req=$reqId step=success", "m3u8=$finalM3u8")
                        invokeLink(finalM3u8, cleanUrl, cookieJar, callback)
                    } else {
                        pl("req=$reqId step=token_parse_fail", "DUMP=${tokenRes.text.take(500)}")
                        invokeLink(directM3u8, cleanUrl, cookieJar, callback)
                    }
                    return true

                } catch (e: Exception) {
                    pl("req=$reqId step=token_error", "msg=${e.message}")
                    invokeLink(directM3u8, cleanUrl, cookieJar, callback)
                    return true
                }
            } else {
                pl("req=$reqId step=fail", "msg=No video path found")
                return false
            }

        } catch (e: Exception) {
             pl("req=$reqId step=visit_error", "msg=${e.message}")
             return false
        }
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        val patterns = listOf(
            Regex("""["']([^"']+\.m3u8\?[^"']+)["']"""),
            Regex("""["']([^"']+\.m3u8)["']"""),
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""source\s*:\s*["']([^"']+)["']"""),
            Regex("""file\s*:\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+)["']"""),
            Regex("""["'](https?://[^"'\s]{50,})["']""")
        )
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) {
                val found = match.groupValues[1]
                if (found.startsWith("http") && !found.contains("<") && !found.contains(";")) {
                    return found
                }
            }
        }
        return null
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        M3u8Helper.generateM3u8(
            name,
            cleanM3u8,
            referer,
            headers = baseHeaders.toMutableMap().apply {
                if (cookieString.isNotEmpty()) {
                    put("Cookie", cookieString)
                }
            }
        ).forEach(callback)
    }
}
