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

        // 1. Refetch & 2. Visit (생략 - 동일)
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

            // [꼼수] 절대 발견되지 않을 URL을 추가해서 강제로 타임아웃까지 대기시킴
            // 이렇게 하면 0.1초 만에 종료되는 걸 막고, 20초 동안 JS가 돌 시간을 줌
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""this_will_never_exist_12345""")),
                useOkhttp = false,
                timeout = 20000L // 20초 동안 강제 대기
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

                // 20초 후 타임아웃으로 여기 도달 (혹은 그 전에 m3u8을 찾으면 이득)
                
                var cookie = CookieManager.getInstance().getCookie(response.url)
                if (cookie.isNullOrEmpty()) {
                    cookie = CookieManager.getInstance().getCookie(tokenUrl) ?: ""
                }
                
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to DESKTOP_UA
                )

                // 타임아웃으로 끝났어도 쿠키만 있으면 강제 진행
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
                // 타임아웃 에러가 나도 무시하고 진행 (쿠키 줍기 시도)
                val cookie = CookieManager.getInstance().getCookie(tokenUrl) ?: ""
                if (cookie.isNotEmpty()) {
                     val finalUrl = tokenUrl.replace("c.html", "index.m3u8")
                     val headers = mapOf("Referer" to cleanUrl, "Cookie" to cookie, "User-Agent" to DESKTOP_UA)
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
        }
        return false
    }
}
