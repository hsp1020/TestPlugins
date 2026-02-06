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
import java.net.URI

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    // 윈도우 UA
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

            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""this_will_never_exist_12345""")),
                useOkhttp = false,
                timeout = 15000L
            )

            try {
                // [수정] WebView 요청 시 Referer를 'cleanUrl'(iframe 주소)로 설정
                // tvmon.site가 아니라 이게 맞습니다. 그래야 서버가 iframe 내부 요청으로 인식하고 쿠키를 줍니다.
                val webViewHeaders = mapOf(
                    "Referer" to cleanUrl, 
                    "User-Agent" to DESKTOP_UA
                )

                val response = app.get(
                    url = tokenUrl, 
                    headers = webViewHeaders,
                    interceptor = resolver
                )

                // 쿠키 수집 (URL 및 도메인)
                val cookieManager = CookieManager.getInstance()
                var cookie = cookieManager.getCookie(tokenUrl)
                if (cookie.isNullOrEmpty()) {
                    try {
                        val uri = URI(tokenUrl)
                        val domainUrl = "${uri.scheme}://${uri.host}"
                        cookie = cookieManager.getCookie(domainUrl)
                    } catch (e: Exception) {}
                }

                // [중요] 헤더 맵 생성 (쿠키가 있을 때만 추가)
                val headers = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to cleanUrl,
                    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
                    "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                )

                if (!cookie.isNullOrEmpty()) {
                    headers["Cookie"] = cookie
                }
                // 쿠키가 없으면 아예 키를 넣지 않습니다. (빈 값 전송 방지)

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
                val cookie = CookieManager.getInstance().getCookie(tokenUrl)
                val headers = mutableMapOf(
                    "User-Agent" to DESKTOP_UA,
                    "Referer" to cleanUrl,
                    "Accept" to "*/*"
                )
                if (!cookie.isNullOrEmpty()) {
                    headers["Cookie"] = cookie
                }

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
