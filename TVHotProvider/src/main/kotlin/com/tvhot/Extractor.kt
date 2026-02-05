package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager // 쿠키 매니저 추가

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

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
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
                    pl("req=$reqId step=iframe_found", "newUrl=$cleanUrl")
                }
            } catch (e: Exception) {}
        }

        // 2. Visit (Path Find)
        var path = ""
        var id = ""
        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer))
            val text = res.text
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)
            if (videoPathMatch != null) {
                path = videoPathMatch.groupValues[1]
                id = videoPathMatch.groupValues[2]
            }
        } catch (e: Exception) {}

        if (path.isNotEmpty()) {
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every$serverNum.poorcdn.com"
            val tokenUrl = "$domain$path$id/c.html"
            
            pl("req=$reqId step=webview_start", "tokenUrl=$tokenUrl")

            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false,
                timeout = 15_000L
            )

            try {
                val response = app.get(
                    url = tokenUrl, 
                    headers = mapOf("Referer" to cleanUrl),
                    interceptor = resolver
                )
                
                pl("req=$reqId step=webview_done", "url=${response.url}")

                // WebView 쿠키 가져오기 (ExoPlayer용)
                val cookie = CookieManager.getInstance().getCookie(response.url) ?: ""
                val headersWithCookie = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )

                // 1. M3U8 URL 직접 포착
                if (response.url.contains(".m3u8")) {
                     pl("req=$reqId step=success", "Captured URL")
                     M3u8Helper.generateM3u8(name, response.url, cleanUrl, headers = headersWithCookie).forEach(callback)
                     return true
                }
                
                // 2. 내용이 M3U8
                if (response.text.contains("#EXTM3U")) {
                     pl("req=$reqId step=success", "Content is M3U8")
                     // c.html 대신 index.m3u8 사용 (확장자 보정)
                     val directUrl = tokenUrl.replace("c.html", "index.m3u8")
                     M3u8Helper.generateM3u8(name, directUrl, cleanUrl, headers = headersWithCookie).forEach(callback)
                     return true
                }

                // 3. 내용에서 추출
                val realM3u8 = extractM3u8FromToken(response.text)
                if (realM3u8 != null) {
                     val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                     pl("req=$reqId step=success", "Extracted: $finalM3u8")
                     M3u8Helper.generateM3u8(name, finalM3u8, cleanUrl, headers = headersWithCookie).forEach(callback)
                     return true
                }

                pl("req=$reqId step=webview_fail", "len=${response.text.length}")

            } catch (e: Exception) {
                pl("req=$reqId step=webview_error", "msg=${e.message}")
            }
        }
        return false
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        val patterns = listOf(
            Regex("""["']([^"']+\.m3u8\?[^"']+)["']"""),
            Regex("""["']([^"']+\.m3u8)["']"""),
            Regex("""location\.href\s*=\s*["']([^"']+)["']"""),
            Regex("""src\s*:\s*["']([^"']+)["']""")
        )
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) return match.groupValues[1]
        }
        return null
    }
}
