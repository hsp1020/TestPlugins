package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "씨유티비 (CUTV)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
                    val league = match["leag"] ?: ""
                    val rawTime = match["dttm"] ?: "" // 예: "2026-05-17 19:00:00"
                    
                    val surl1 = match["surl"] ?: ""
                    val surl2 = match["surl2"] ?: ""
                    val surl3 = match["surl3"] ?: ""
                    
                    // 방송시청 버튼이 있는(surl이 존재하는) 경기만 필터링
                    if (ssid.isNotBlank() && name.isNotBlank() && (surl1.isNotBlank() || surl2.isNotBlank() || surl3.isNotBlank())) {
                        
                        // [핵심 1] 날짜를 지우고 AM/PM 시간 형식으로 변환
                        var formattedTime = rawTime
                        if (rawTime.contains(" ")) {
                            try {
                                val timePart = rawTime.substringAfter(" ") // "19:00:00"
                                val hour = timePart.substringBefore(":").toInt()
                                val minute = timePart.substringAfter(":").substringBefore(":")
                                val ampm = if (hour < 12) "AM" else "PM"
                                val displayHour = if (hour % 12 == 0) 12 else hour % 12
                                formattedTime = "$ampm $displayHour:$minute" // 예: "PM 7:00"
                            } catch (e: Exception) {
                                formattedTime = rawTime.substringAfter(" ") // 파싱 실패 시 시간만 출력
                            }
                        }

                        val title = "[$league] $name ($formattedTime)"
                        
                        // [핵심 2] 썸네일 주소에 현재 시간(Timestamp) 파라미터 추가
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
                                // [핵심 3] 이미지 로딩 시 엑박(차단)을 막기 위해 Referer 헤더 강제 주입
                                this.posterHeaders = mapOf(
                                    "Referer" to iframeSrc,
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
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol13.galaxy-stream.live/cli/box"

        // 상세 페이지(LoadResponse)에서도 썸네일이 차단되지 않도록 헤더를 넘겨줍니다.
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
                        val cleanLink = link.replace("\\/", "/") 
                        val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                                          else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                                          else "채널 ${index + 1}"

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = cleanLink,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = iframeUrl
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
