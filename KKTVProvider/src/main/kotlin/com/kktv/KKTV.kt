package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "CUTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    // [핵심 1] 스크린샷에 찍힌 User-Agent와 100% 동일하게 맞춰서 봇 의심을 원천 차단합니다.
    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            val doc = app.get(url, headers = defaultHeaders).document
            
            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli") }

            if (iframeSrc.isNullOrBlank()) {
                return newHomePageResponse(request.name, emptyList())
            }

            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                matches.forEach { match ->
                    val ssid = match["ssid"] ?: ""
                    val name = match["name"] ?: ""
                    
                    val surl1 = match["surl"] ?: ""
                    val surl2 = match["surl2"] ?: ""
                    val surl3 = match["surl3"] ?: ""
                    
                    if (ssid.isNotBlank() && name.isNotBlank() && (surl1.isNotBlank() || surl2.isNotBlank() || surl3.isNotBlank())) {
                        
                        val title = name 
                        
                        val srcy = match["srcy"] ?: ""
                        val thumbId = if (srcy.isNotBlank()) srcy else ssid
                        val timestamp = System.currentTimeMillis()
                        val posterUrl = "https://lux1.hudtv01.com/thumbs/$thumbId.jpg?a=$timestamp"
                        
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        
                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                                this.posterHeaders = mapOf(
                                    "Referer" to iframeSrc,
                                    "User-Agent" to userAgent,
                                    "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                                    "Sec-Fetch-Dest" to "image",
                                    "Sec-Fetch-Mode" to "no-cors",
                                    "Sec-Fetch-Site" to "cross-site"
                                )
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol13.galaxy-stream.live/cli/box"

        val ssid = url.substringBefore("?").substringAfterLast("/")
        val posterUrl = "https://lux1.hudtv01.com/thumbs/$ssid.jpg?a=${System.currentTimeMillis()}"

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf(
                "Referer" to iframeUrl,
                "User-Agent" to userAgent,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Sec-Fetch-Dest" to "image",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site"
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val ssid = data.substringBefore("?").substringAfterLast("/")
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        try {
            val iframeHtml = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                val targetMatch = matches.find { it["ssid"] == ssid }
                
                if (targetMatch != null) {
                    val urls = listOfNotNull(targetMatch["surl"], targetMatch["surl2"], targetMatch["surl3"])
                        .filter { it.isNotBlank() }
                        .distinct()

                    // [핵심 2] Iframe 주소에서 Origin(출처) 도메인을 동적으로 추출합니다. (예: https://sol13.galaxy-stream.live)
                    val iframeOrigin = "https://${Uri.parse(iframeUrl).host}"

                    urls.forEachIndexed { index, link ->
                        val cleanLink = link.replace("\\/", "/") 
                        val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1" 
                                          else if (cleanLink.contains("hudtv")) "채널 2" 
                                          else "채널 ${index + 1}"

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = cleanLink,
                                type = ExtractorLinkType.M3U8
                            ) {
                                // [핵심 3] 스크린샷에 찍힌 브라우저 요청 헤더를 100% 똑같이 플레이어에 주입합니다.
                                this.referer = "$iframeOrigin/"
                                this.headers = mapOf(
                                    "Origin" to iframeOrigin,
                                    "Accept" to "*/*",
                                    "Sec-Fetch-Dest" to "empty",
                                    "Sec-Fetch-Mode" to "cors",
                                    "Sec-Fetch-Site" to "cross-site",
                                    "User-Agent" to userAgent
                                )
                            }
                        )
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
}
