package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager

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

    // [복구] TVHot.kt 등 외부에서 호출할 수 있도록 extract 메서드 복원
    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        val reqId = System.currentTimeMillis().toDouble()
        // pl("req=$reqId step=start", "url=$url")

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
            
            // pl("req=$reqId step=webview_try", "tokenUrl=$tokenUrl")

            val resolver = WebViewResolver(
                interceptUrl = Regex("""(c\.html|index\.m3u8)"""),
                additionalUrls = listOf(Regex("""\.m3u8""")),
                useOkhttp = false
            )

            try {
                // 02:19 성공 헤더 설정
                val response = app.get(
                    url = tokenUrl, 
                    headers = mapOf("Referer" to cleanUrl),
                    interceptor = resolver
                )
                
                // pl("req=$reqId step=webview_done", "code=${response.code}")

                val cookie = CookieManager.getInstance().getCookie(response.url) ?: ""
                val headers = mapOf(
                    "Referer" to cleanUrl,
                    "Cookie" to cookie,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
                )

                if (response.text.contains("#EXTM3U")) {
                     // pl("req=$reqId step=success", "Content is M3U8")
                     
                     // [수동 파싱] M3u8Helper 대신 직접 링크 생성 (403 방지)
                     val m3u8Content = response.text
                     val baseUrl = response.url.substringBeforeLast("/") // .../v/f/{id}
                     
                     if (m3u8Content.contains("#EXT-X-STREAM-INF")) {
                        // Master Playlist 처리
                        Regex("""#EXT-X-STREAM-INF:.*?RESOLUTION=(\d+x\d+).*?\n(.*?\.m3u8)""").findAll(m3u8Content).forEach { match ->
                            // val res = match.groupValues[1] // 해상도 정보 (필요시 사용)
                            val subUrl = match.groupValues[2].trim()
                            val fullUrl = if (subUrl.startsWith("http")) subUrl else "$baseUrl/$subUrl"
                            
                            callback(
                                ExtractorLink(
                                    source = name,
                                    name = name,
                                    url = fullUrl,
                                    referer = cleanUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = true, // [수정] type=1 대신 isM3u8=true 사용
                                    headers = headers
                                )
                            )
                        }
                     } else {
                        // Single Stream 처리
                        // c.html -> index.m3u8 주소 변환 (플레이어 호환성)
                        val finalUrl = if (response.url.contains(".m3u8")) response.url 
                                       else tokenUrl.replace("c.html", "index.m3u8")
                        
                        callback(
                            ExtractorLink(
                                source = name,
                                name = name,
                                url = finalUrl,
                                referer = cleanUrl,
                                quality = Qualities.Unknown.value,
                                isM3u8 = true, // [수정] type=1 대신 isM3u8=true 사용
                                headers = headers
                            )
                        )
                     }
                     return true
                }
            } catch (e: Exception) {
                // pl("req=$reqId step=webview_error", "msg=${e.message}")
            }
        }
        return false
    }
}
