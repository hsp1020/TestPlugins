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
            
            // [완벽 수정] 썸네일 베이스 URL 동적 추출 (이스케이프된 슬래시 완벽 대응)
            var dynamicThumbBase = "https://lux2.oncahot.com/thumbs" // 선생님이 테스트하신 실패 도메인 (코드가 스스로 진짜 도메인을 찾아 덮어씌웁니다)
            
            val thumbRegex = """(https?://[a-zA-Z0-9.-]+(?:\\?/)+thumbs?)""".toRegex(RegexOption.IGNORE_CASE)
            val match = thumbRegex.find(iframeHtml) ?: thumbRegex.find(doc.html())
            
            if (match != null) {
                dynamicThumbBase = match.groupValues[1].replace("\\", "").removeSuffix("/")
            } else {
                // 최후의 보루: lux, oncahot, hudtv 등의 키워드가 포함된 도메인 추출
                val fallbackRegex = """(https?://(?:lux|hudtv|oncahot)[a-zA-Z0-9.-]+)""".toRegex(RegexOption.IGNORE_CASE)
                val fallbackMatch = fallbackRegex.find(iframeHtml) ?: fallbackRegex.find(doc.html())
                if (fallbackMatch != null) {
                    dynamicThumbBase = fallbackMatch.groupValues[1].replace("\\", "").removeSuffix("/") + "/thumbs"
                }
            }
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                matches.forEach { matchItem ->
                    val ssid = matchItem["ssid"] ?: ""
                    val name = matchItem["name"] ?: ""
                    
                    val surl1 = matchItem["surl"] ?: ""
                    val surl2 = matchItem["surl2"] ?: ""
                    val surl3 = matchItem["surl3"] ?: ""
                    
                    if (ssid.isNotBlank() && name.isNotBlank() && (surl1.isNotBlank() || surl2.isNotBlank() || surl3.isNotBlank())) {
                        
                        val title = name 
                        val srcy = matchItem["srcy"] ?: ""
                        val thumbId = if (srcy.isNotBlank()) srcy else ssid
                        val timestamp = System.currentTimeMillis()
                        
                        // 동적으로 찾아낸 진짜 도메인 적용
                        val posterUrl = "$dynamicThumbBase/$thumbId.jpg?a=$timestamp"
                        
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        val encodedThumbBase = URLEncoder.encode(dynamicThumbBase, "UTF-8")
                        
                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe&thumbBase=$encodedThumbBase"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                                this.posterHeaders = mapOf(
                                    "Referer" to "$mainUrl/",
                                    "User-Agent" to userAgent
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
                "Referer" to "$mainUrl/",
                "User-Agent" to userAgent
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

                    urls.forEachIndexed { index, link ->
                        var actualUrl = link.replace("\\/", "/") 
                        
                        var targetReferer = iframeUrl
                        var targetOrigin = "https://${Uri.parse(iframeUrl).host}"
                        
                        // [완벽 수정] EMBED# 제거 및 스크린샷과 100% 동일한 Referer/Origin 세팅
                        if (actualUrl.startsWith("EMBED#")) {
                            actualUrl = actualUrl.removePrefix("EMBED#")
                        }
                        
                        if (actualUrl.contains("liveUrl=")) {
                            targetReferer = actualUrl // 스크린샷의 Referer (https://player.up909.com...)
                            try {
                                targetOrigin = "https://${Uri.parse(actualUrl).host}" // 스크린샷의 Origin (https://player.up909.com)
                                
                                Uri.parse(actualUrl).getQueryParameter("liveUrl")?.let { extractedUrl ->
                                    actualUrl = extractedUrl // 실제 .flv 주소
                                }
                            } catch (e: Exception) {
                                // 파싱 실패 시 무시
                            }
                        } else if (actualUrl.contains("uu6611.com")) {
                            // 만약 서버가 EMBED 없이 다이렉트 링크를 줬을 경우를 대비한 안전장치
                            targetReferer = "https://player.up909.com/"
                            targetOrigin = "https://player.up909.com"
                        }

                        val qualityName = if (link.contains("coupangstreaming")) "채널 1" 
                                          else if (link.contains("hudtv")) "채널 2" 
                                          else "채널 ${index + 1}"

                        // .flv 확장자 처리
                        val isM3u8 = actualUrl.contains(".m3u8", ignoreCase = true)
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = actualUrl,
                                type = linkType
                            ) {
                                // 계산된 Referer와 Origin을 정확히 주입 (403 에러 원천 차단)
                                this.referer = targetReferer
                                this.headers = mapOf(
                                    "Origin" to targetOrigin,
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
