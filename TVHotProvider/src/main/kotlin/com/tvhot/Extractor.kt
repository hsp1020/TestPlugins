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

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
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

        // 1. Refetch Logic
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && cleanReferer != null) {
            pl("req=$reqId step=refetch_start", "msg=URL seems incomplete, fetching referer")
            try {
                val refRes = app.get(cleanReferer, headers = tvMonHeaders)
                val refText = refRes.text
                
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refText)
                
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&")
                    pl("req=$reqId step=iframe_found", "newUrl=$cleanUrl")
                }
            } catch (e: Exception) {
                pl("req=$reqId step=refetch_error", "msg=${e.message}")
            }
        }

        // 2. Visit Logic
        var videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
        
        if (videoPathMatch == null) {
            pl("req=$reqId step=visit_start", "msg=Path not in URL, visiting page")
            try {
                val res = app.get(cleanUrl, headers = browserHeaders.toMutableMap().apply {
                    put("Referer", cleanReferer ?: "https://tvmon.site/")
                })
                val text = res.text
                pl("req=$reqId step=visit_done", "len=${text.length}")
                
                videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(text)
                if (videoPathMatch != null) {
                    pl("req=$reqId step=path_found_in_source", "path=${videoPathMatch.value}")
                }
            } catch (e: Exception) {
                 pl("req=$reqId step=visit_error", "msg=${e.message}")
            }
        }

        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        
        if (videoPathMatch != null) {
            val path = videoPathMatch.groupValues[1]
            val id = videoPathMatch.groupValues[2]
            
            val tokenUrl = "$domain$path$id/c.html"
            val directM3u8 = "$domain$path$id/index.m3u8"
            
            pl("req=$reqId step=path_final", "tokenUrl=$tokenUrl")

            try {
                // 1차 시도: Referer = cleanUrl
                var tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                
                // [신규] 403 Forbidden이면 재시도 (Referer 변경)
                if (tokenRes.code == 403 || tokenRes.text.contains("Forbidden")) {
                    pl("req=$reqId step=retry_403", "msg=Got 403, retrying with main referer")
                    tokenRes = app.get(tokenUrl, referer = "https://tvmon.site/", headers = browserHeaders)
                }

                val cookieMap = tokenRes.cookies.toMutableMap()
                
                val jsCookieRegex = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                jsCookieRegex.findAll(tokenRes.text).forEach { match ->
                    cookieMap[match.groupValues[1]] = match.groupValues[2]
                }
                
                // 응답이 이미 M3U8 파일 내용인 경우
                if (tokenRes.text.trim().startsWith("#EXTM3U")) {
                    pl("req=$reqId step=direct_m3u8_content", "msg=Content is M3U8")
                    invokeLink(tokenUrl, cleanUrl, cookieMap, callback)
                    return true
                }

                val realM3u8 = extractM3u8FromToken(tokenRes.text)
                
                if (realM3u8 != null) {
                    val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                    pl("req=$reqId step=success", "m3u8=$finalM3u8")
                    invokeLink(finalM3u8, cleanUrl, cookieMap, callback)
                } else {
                    pl("req=$reqId step=token_parse_fail", "DUMP=${tokenRes.text.take(500)}")
                    // Fallback to directM3u8
                    invokeLink(directM3u8, cleanUrl, cookieMap, callback)
                }
                return true
            } catch (e: Exception) {
                pl("req=$reqId step=error", "msg=${e.message}")
                invokeLink(directM3u8, cleanUrl, emptyMap(), callback)
                return true
            }
        } else {
            pl("req=$reqId step=fail", "msg=No video path found")
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
            headers = browserHeaders.toMutableMap().apply {
                if (cookieString.isNotEmpty()) {
                    put("Cookie", cookieString)
                }
            }
        ).forEach(callback)
    }
}
