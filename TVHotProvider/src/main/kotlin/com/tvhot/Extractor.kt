package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 성공했던 윈도우 UA 사용
    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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
                }
            } catch (e: Exception) {}
        }

        // 2. Visit (Path Find)
        var path = ""
        var id = ""
        try {
            // 여기서는 모바일 UA를 쓰는 게 나을 수도 있지만, 일단 통일
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

            // [수정] 정규식 단순화 (m3u8만 찾음)
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false,
                timeout = 20_000L // 타임아웃 20초로 증가
            )

            try {
                // [수정] 헤더에 User-Agent 명시
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "User-Agent" to USER_AGENT
                )

                val response = app.get(
                    url = tokenUrl, 
                    headers = headers,
                    interceptor = resolver
                )
                
                pl("req=$reqId step=webview_done", "code=${response.code} url=${response.url}")
                
                // 쿠키 획득
                val cookie = CookieManager.getInstance().getCookie(response.url) ?: ""
                val m3u8Headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to USER_AGENT
                )

                // 1. URL이 m3u8인 경우 (리다이렉트 됨)
                if (response.url.contains(".m3u8")) {
                     pl("req=$reqId step=success", "Captured URL")
                     M3u8Helper.generateM3u8(name, response.url, cleanUrl, headers = m3u8Headers).forEach(callback)
                     return true
                }
                
                // 2. 내용이 M3U8인 경우
                val text = response.text
                if (text.contains("#EXTM3U")) {
                     pl("req=$reqId step=success", "Content is M3U8")
                     // [중요] tokenUrl이 아니라 response.url을 기반으로 교체하거나
                     // 그냥 안전하게 index.m3u8 주소 생성
                     val directUrl = "$domain$path$id/index.m3u8"
                     M3u8Helper.generateM3u8(name, directUrl, cleanUrl, headers = m3u8Headers).forEach(callback)
                     return true
                }

                pl("req=$reqId step=webview_fail", "len=${text.length} dump=${text.take(100)}")

            } catch (e: Exception) {
                pl("req=$reqId step=webview_error", "msg=${e.message}")
            }
        }
        return false
    }
}
