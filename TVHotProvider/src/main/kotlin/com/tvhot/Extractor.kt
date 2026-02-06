package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 윈도우 크롬 UA (Somansa/Every4 통과용)
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

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
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Refetch
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                if (iframeMatch != null) {
                    cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) {}
        }

        // 2. Visit
        var path = ""
        var id = ""
        try {
            val res = app.get(cleanUrl, headers = mapOf("Referer" to cleanReferer))
            val videoPathMatch = Regex("""(/v/[a-z]/)([a-z0-9]{32,50})""").find(res.text) 
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

            // Somansa가 WebView 통신을 방해해서 SSL 에러가 나더라도
            // 타임아웃까지 버텨서 코드가 'catch' 블록이나 'timeout' 이후로 넘어가게 유도
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""this_will_never_exist_12345""")),
                useOkhttp = false,
                timeout = 10000L // 10초면 충분
            )

            try {
                val response = app.get(
                    url = tokenUrl, 
                    headers = mapOf(
                        "Referer" to cleanUrl,
                        "User-Agent" to DESKTOP_UA
                    ),
                    interceptor = resolver
                )

                var cookie = CookieManager.getInstance().getCookie(response.url)
                if (cookie.isNullOrEmpty()) {
                    cookie = CookieManager.getInstance().getCookie(tokenUrl) ?: ""
                }
                
                val headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "Accept" to "*/*"
                )

                val finalUrl = tokenUrl.replace("c.html", "index.m3u8")
                
                callback(
                    newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = cleanUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                return true

            } catch (e: Exception) {
                // SSL Handshake failed 에러가 나더라도 무조건 링크 생성
                val cookie = CookieManager.getInstance().getCookie(tokenUrl) ?: ""
                val headers = mapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "Accept" to "*/*"
                )
                val finalUrl = tokenUrl.replace("c.html", "index.m3u8")
                 callback(
                    newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = cleanUrl
                        this.quality = Qualities.Unknown.value
                        this.headers = headers
                    }
                )
                return true
            }
        }
        return false
    }
}
