package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
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
            
            // [핵심 1] 썸네일 베이스 URL 동적 추출 (도메인이 바뀌어도 무적)
            var dynamicThumbBase = "https://lux2.oncahot.com/thumbs" // 파싱 실패 시 최후의 보루
            
            // 메인 페이지(doc) 또는 iframe 내부에서 썸네일 img 태그를 찾음
            val imgTag = doc.selectFirst("img.ssc-thumbnail-icon") 
                ?: Jsoup.parse(iframeHtml).selectFirst("img.ssc-thumbnail-icon")
                
            if (imgTag != null) {
                val src = imgTag.attr("src")
                if (src.startsWith("http")) {
                    // 예: "https://lux1.oncahot.com/thumbs/80c5038c...jpg?a=..." -> "https://lux1.oncahot.com/thumbs"
                    dynamicThumbBase = src.substringBeforeLast("/") 
                }
            } else {
                // 태그 클래스명이 바뀌었을 경우를 대비한 정규식 백업
                val regexMatch = """(https?://[^/]+/thumbs)""".toRegex().find(iframeHtml) 
                    ?: """(https?://[^/]+/thumbs)""".toRegex().find(doc.html())
                if (regexMatch != null) {
                    dynamicThumbBase = regexMatch.groupValues[1]
                }
            }
            
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
                        
                        // 동적으로 찾은 도메인 적용
                        val posterUrl = "$dynamicThumbBase/$thumbId.jpg?a=$timestamp"
                        
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        val encodedThumbBase = URLEncoder.encode(dynamicThumbBase, "UTF-8")
                        
                        // load() 함수로 동적 도메인을 넘겨주기 위해 파라미터 추가
                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe&thumbBase=$encodedThumbBase"

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

        // [핵심 2] getMainPage에서 넘겨준 동적 썸네일 도메인을 받아서 사용
        val rawThumbBase = uri.getQueryParameter("thumbBase")
        val thumbBase = if (!rawThumbBase.isNullOrBlank()) URLDecoder.decode(rawThumbBase, "UTF-8") else "https://lux1.oncahot.com/thumbs"

        val ssid = url.substringBefore("?").substringAfterLast("/")
        val posterUrl = "$thumbBase/$ssid.jpg?a=${System.currentTimeMillis()}"

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

                    val iframeOrigin = "https://${Uri.parse(iframeUrl).host}"

                    urls.forEachIndexed { index, link ->
                        var cleanLink = link.replace("\\/", "/") 
                        
                        // [핵심 3] EMBED# 제거 및 liveUrl 파라미터 추출 (재생 불가 문제 해결)
                        if (cleanLink.startsWith("EMBED#")) {
                            cleanLink = cleanLink.removePrefix("EMBED#")
                        }
                        
                        if (cleanLink.contains("liveUrl=")) {
                            try {
                                val parsedUri = Uri.parse(cleanLink)
                                parsedUri.getQueryParameter("liveUrl")?.let { extractedUrl ->
                                    // Uri.getQueryParameter가 자동으로 디코딩해주므로 바로 사용 가능
                                    cleanLink = extractedUrl
                                }
                            } catch (e: Exception) {
                                // 파싱 실패 시 원본 유지
                            }
                        }

                        val qualityName = if (link.contains("coupangstreaming")) "채널 1" 
                                          else if (link.contains("hudtv")) "채널 2" 
                                          else "채널 ${index + 1}"

                        // .flv 확장자일 경우 ExtractorLinkType.VIDEO로 전달해야 ExoPlayer가 정상 인식함
                        val isM3u8 = cleanLink.contains(".m3u8", ignoreCase = true)
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = cleanLink,
                                type = linkType
                            ) {
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
