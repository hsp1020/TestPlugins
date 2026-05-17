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
            
            // [핵심 해킹 로직] 로그캣에서 발견한 숨겨진 JSON 데이터를 정규식으로 뜯어냅니다.
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                
                // 뜯어낸 JSON 데이터를 Cloudstream 내장 파서로 Map 리스트로 변환합니다.
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                println("DEBUG [CUTV] ✅ 숨겨진 JSON 데이터 해킹 성공! 총 ${matches.size}개 경기 발견")

                matches.forEach { match ->
                    val ssid = match["ssid"] ?: ""
                    val name = match["name"] ?: ""
                    val league = match["leag"] ?: ""
                    val time = match["dttm"] ?: ""
                    
                    if (ssid.isNotBlank() && name.isNotBlank()) {
                        val title = "[$league] $name ($time)"
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        
                        // ssid를 URL에 박아넣어 나중에 영상을 바로 찾을 수 있게 합니다.
                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl)
                        )
                    }
                }
            } else {
                println("DEBUG [CUTV] 🚨 JSON 데이터를 찾을 수 없습니다.")
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

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        )
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
            
            // 영상 주소도 JSON 안에 다 들어있으므로 다시 JSON을 뜯어옵니다.
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                // 클릭한 경기(ssid)와 일치하는 데이터를 찾습니다.
                val targetMatch = matches.find { it["ssid"] == ssid }
                
                if (targetMatch != null) {
                    // JSON 안에 있는 surl, surl2, surl3 에서 실제 m3u8 주소를 꺼냅니다.
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
