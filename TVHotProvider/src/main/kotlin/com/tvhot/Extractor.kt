package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.URI

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
        val reqId = System.currentTimeMillis().toDouble()
        
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"

        // 1. Iframe 주소 따기 (Refetch)
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

        // 2. Path & ID 찾기
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
            
            // [성공했던 02:19 설정 복구]
            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false
            )

            try {
                // [성공했던 02:19 요청]
                val response = app.get(
                    url = tokenUrl, 
                    headers = mapOf("Referer" to cleanUrl),
                    interceptor = resolver
                )

                // 쿠키 줍기
                val cookie = CookieManager.getInstance().getCookie(response.url) ?: ""
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )

                // 3. 내용이 M3U8이면 -> M3u8Helper 쓰지 말고 '직접' 파싱
                if (response.text.contains("#EXTM3U")) {
                    val m3u8Content = response.text
                    val baseUrl = response.url.substringBeforeLast("/") // .../v/f/{id}
                    
                    // (1) Master Playlist인 경우 (해상도별 분기)
                    if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                        Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(.*?\.m3u8)""").findAll(m3u8Content).forEach { match ->
                            val res = match.groupValues[1]
                            val subUrl = match.groupValues[2].trim()
                            val fullUrl = if (subUrl.startsWith("http")) subUrl else "$baseUrl/$subUrl"
                            
                            callback(
                                ExtractorLink(
                                    name,
                                    name,
                                    fullUrl,
                                    cleanUrl,
                                    Qualities.Unknown.value,
                                    type = 1, // M3U8 type
                                    headers = headers
                                )
                            )
                        }
                    } 
                    // (2) 단일 Stream인 경우
                    else {
                        // URL 확장자만 .m3u8로 싹 바꿔서 던져줌 (플레이어 인식용)
                        val finalUrl = if (response.url.contains(".m3u8")) response.url 
                                       else tokenUrl.replace("c.html", "index.m3u8")
                        
                        callback(
                            ExtractorLink(
                                name,
                                name,
                                finalUrl,
                                cleanUrl,
                                Qualities.Unknown.value,
                                type = 1,
                                headers = headers
                            )
                        )
                    }
                    return
                }
                
                // 만약 URL 자체가 m3u8로 리다이렉트 되었는데 내용은 못 읽은 경우 (드문 케이스)
                if (response.url.contains(".m3u8")) {
                     callback(
                        ExtractorLink(
                            name,
                            name,
                            response.url,
                            cleanUrl,
                            Qualities.Unknown.value,
                            type = 1,
                            headers = headers
                        )
                    )
                    return
                }

            } catch (e: Exception) {
                // Fail silently
            }
        }
    }
}
