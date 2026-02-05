package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import okhttp3.HttpUrl.Companion.toHttpUrl

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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
        
        val cookieJar = mutableMapOf<String, String>()

        // 1. Refetch Logic
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/") && cleanReferer != null) {
            pl("req=$reqId step=refetch_start", "msg=URL seems incomplete, fetching referer")
            try {
                val refRes = app.get(cleanReferer, headers = tvMonHeaders)
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

        // 2. Visit Logic
        pl("req=$reqId step=visit_start", "msg=Visiting player page")
        try {
            val visitHeaders = baseHeaders.toMutableMap().apply {
                put("Referer", cleanReferer ?: "https://tvmon.site/")
            }
            val res = app.get(cleanUrl, headers = visitHeaders, cookies = cookieJar)
            cookieJar.putAll(res.cookies)
            val text = res.text
            pl("req=$reqId step=visit_done", "len=${text.length}")

            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)

            if (videoPathMatch != null) {
                val path = videoPathMatch.groupValues[1]
                val id = videoPathMatch.groupValues[2]
                val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                val domain = "https://every$serverNum.poorcdn.com"
                
                val tokenUrl = "$domain$path$id/c.html"
                val directM3u8 = "$domain$path$id/index.m3u8"
                
                pl("req=$reqId step=path_final", "tokenUrl=$tokenUrl")
                
                // 3. Token 요청 (헤더 강화)
                try {
                    // 크롬 브라우저 헤더 흉내내기
                    val tokenHeaders = mapOf(
                        "Host" to tokenUrl.toHttpUrl().host,
                        "Connection" to "keep-alive",
                        "Upgrade-Insecure-Requests" to "1",
                        "User-Agent" to USER_AGENT,
                        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                        "Sec-Fetch-Site" to "same-site",
                        "Sec-Fetch-Mode" to "navigate",
                        "Sec-Fetch-Dest" to "iframe",
                        "Referer" to cleanUrl,
                        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                    
                    Thread.sleep(100) // 짧은 딜레이
                    
                    val tokenRes = app.get(tokenUrl, headers = tokenHeaders, cookies = cookieJar)
                    cookieJar.putAll(tokenRes.cookies)
                    
                    val jsCookieRegex = Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    jsCookieRegex.findAll(tokenRes.text).forEach { match ->
                        cookieJar[match.groupValues[1]] = match.groupValues[2]
                    }
                    
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
