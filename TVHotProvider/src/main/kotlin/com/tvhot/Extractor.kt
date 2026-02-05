package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver 
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.Protocol
import okhttp3.Request

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

        // 1. Refetch Logic (iframe 주소 찾기)
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            pl("req=$reqId step=refetch_start", "msg=Fetching referer")
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").replace(Regex("[\\r\\n\\s]"), "").trim()
                    pl("req=$reqId step=iframe_found", "newUrl=$cleanUrl")
                }
            } catch (e: Exception) {
                pl("req=$reqId step=refetch_error", "msg=${e.message}")
            }
        }

        // 2. Visit Logic (경로 찾기)
        // 여기서는 일반 app.get을 써도 되지만, 안전하게 WebViewResolver를 쓸 수도 있습니다.
        // 일단 경로 찾는 건 일반 요청으로 시도 (여긴 403 잘 안 뜸)
        var path: String = ""
        var id: String = ""
        
        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer))
            val text = res.text
            
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(text) 
                ?: Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(cleanUrl)

            if (videoPathMatch != null) {
                path = videoPathMatch.groupValues[1]
                id = videoPathMatch.groupValues[2]
            }
        } catch (e: Exception) {
             pl("req=$reqId step=visit_error", "msg=${e.message}")
        }

        if (path.isNotEmpty() && id.isNotEmpty()) {
            val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
            val domain = "https://every$serverNum.poorcdn.com"
            val tokenUrl = "$domain$path$id/c.html"
            
            pl("req=$reqId step=webview_start", "tokenUrl=$tokenUrl")

            // [핵심] WebViewResolver 사용
            // 페이지 소스에서 "m3u8" 또는 "#EXTM3U"가 발견될 때까지 기다림
            val resolver = WebViewResolver(Regex("""(m3u8|#EXTM3U)"""))
            
            try {
                // WebView를 통해 요청 (Method: GET)
                // resolveUsingWebView 함수 사용 (CloudStream 3.x 표준)
                val request = Request.Builder()
                    .url(tokenUrl)
                    .addHeader("Referer", cleanUrl)
                    .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36")
                    .build()

                val response = resolver.intercept(object : Interceptor.Chain {
                    override fun call(): okhttp3.Call = throw NotImplementedError()
                    override fun connectTimeoutMillis(): Int = 30000
                    override fun connection(): okhttp3.Connection? = null
                    override fun proceed(request: Request): Response = throw NotImplementedError()
                    override fun readTimeoutMillis(): Int = 30000
                    override fun request(): Request = request
                    override fun writeTimeoutMillis(): Int = 30000
                })

                pl("req=$reqId step=webview_done", "code=${response.code}")
                
                // WebView가 가져온 내용 (HTML 소스 또는 M3U8 내용)
                val responseText = response.body?.string() ?: ""

                // 1. 내용 자체가 M3U8인 경우
                if (responseText.contains("#EXTM3U")) {
                     pl("req=$reqId step=success", "Direct M3U8 content")
                     M3u8Helper.generateM3u8(name, tokenUrl, cleanUrl).forEach(callback)
                     return true
                }
                
                // 2. 내용 안에 m3u8 링크가 있는 경우
                val realM3u8 = extractM3u8FromToken(responseText)
                if (realM3u8 != null) {
                     val finalM3u8 = if (realM3u8.startsWith("http")) realM3u8 else "$domain$realM3u8"
                     pl("req=$reqId step=success", "Extracted: $finalM3u8")
                     
                     // m3u8 요청 시에도 쿠키가 필요할 수 있으므로 헤더 전달
                     M3u8Helper.generateM3u8(
                        name, 
                        finalM3u8, 
                        cleanUrl,
                        headers = mapOf("Cookie" to (response.header("Set-Cookie") ?: ""))
                     ).forEach(callback)
                     return true
                }
                
                pl("req=$reqId step=webview_fail", "len=${responseText.length}")

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
}
