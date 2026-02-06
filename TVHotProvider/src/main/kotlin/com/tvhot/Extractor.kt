package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
// [필수] ExtractorLinkType 및 newExtractorLink 사용
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

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

            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false
            )

            try {
                val response = app.get(
                    url = tokenUrl, 
                    headers = mapOf("Referer" to cleanUrl),
                    interceptor = resolver
                )

                val cookie = CookieManager.getInstance().getCookie(response.url) ?: ""
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )

                // 1. 이미 .m3u8로 리다이렉트 된 경우
                if (response.url.contains(".m3u8")) {
                    callback(
                        newExtractorLink(name, name, response.url, ExtractorLinkType.M3U8) {
                            this.referer = cleanUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = headers
                        }
                    )
                    return true
                }

                // 2. c.html 내용을 읽은 경우
                if (response.text.contains("#EXTM3U")) {
                    val m3u8Content = response.text
                    val baseUrl = response.url.substringBeforeLast("/")
                    
                    if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                         Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(.*?\.m3u8)""").findAll(m3u8Content).forEach { match ->
                            val subUrl = match.groupValues[2].trim()
                            val fullUrl = if (subUrl.startsWith("http")) subUrl else "$baseUrl/$subUrl"
                            callback(
                                newExtractorLink(name, name, fullUrl, ExtractorLinkType.M3U8) {
                                    this.referer = cleanUrl
                                    this.quality = Qualities.Unknown.value
                                    this.headers = headers
                                }
                            )
                        }
                    } else {
                        val finalUrl = tokenUrl.replace("c.html", "index.m3u8")
                        callback(
                            newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                                this.referer = cleanUrl
                                this.quality = Qualities.Unknown.value
                                this.headers = headers
                            }
                        )
                    }
                    return true
                }
                
                // 3. [최후의 수단] 내용 못 읽었어도(Timeout 등), 쿠키만 있으면 강제 생성
                if (cookie.isNotEmpty()) {
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

            } catch (e: Exception) {
            }
        }
        return false
    }
}
