package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import java.net.URLDecoder
import java.net.URLEncoder

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/bbs/content.php?co_id=livetv9" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

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
            println("DEBUG [KKTV] 1. 메인 페이지 로드 시작: $url")
            
            // [핵심 해결책] 사용자님 지시대로 WebViewResolver를 사용하여 클라우드플레어 JS 챌린지를 강제로 뚫습니다.
            val mainInterceptor = WebViewResolver(Regex("""quick-tv\.com"""))
            val doc = app.get(url, headers = defaultHeaders, interceptor = mainInterceptor).document
            
            // 도메인이 바뀌었을 경우를 대비해, iframe src에 의존하지 않고 div#player_field 안의 iframe을 무조건 가져옵니다.
            var iframeSrc = doc.selectFirst("div#player_field iframe")?.attr("src")
            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
            }
            
            println("DEBUG [KKTV] 2. 추출된 Iframe 원본 주소: $iframeSrc")

            if (iframeSrc.isNullOrBlank()) {
                println("DEBUG [KKTV] 🚨 메인 페이지에서 Iframe을 찾지 못했습니다. CF 우회 실패 또는 DOM 변경 의심.")
                return newHomePageResponse(request.name, emptyList())
            }

            println("DEBUG [KKTV] 3. Iframe 페이지 로드 시작 (CF 우회 및 Referer 적용)")
            val iframeDomain = Uri.parse(iframeSrc).host ?: ""
            val iframeInterceptor = WebViewResolver(Regex(iframeDomain))
            
            // 정상적인 경로(Referer)와 쿠키를 가지고 Iframe에 접근해야 500 에러가 뜨지 않습니다.
            val iframeDoc = app.get(iframeSrc, headers = defaultHeaders + mapOf("Referer" to url), interceptor = iframeInterceptor).document
            
            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [KKTV] 4. 파싱된 경기 개수: ${rows.size}")

            rows.forEach { row ->
                val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
                val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
                val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
                val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
                
                if (homeTeam.isNotBlank()) {
                    val title = "[$league] $homeTeam vs $awayTeam ($time)"
                    val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src") ?: ""

                    val encodedTitle = URLEncoder.encode(title, "UTF-8")
                    val encodedPoster = URLEncoder.encode(posterUrl, "UTF-8")
                    val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                    
                    val fakeUrl = "$mainUrl/live_stream/dummy?title=$encodedTitle&poster=$encodedPoster&iframe=$encodedIframe"

                    items.add(
                        newLiveSearchResponse(
                            name = title,
                            url = fakeUrl
                        ) {
                            this.posterUrl = posterUrl
                        }
                    )
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KKTV] 🚨 에러 발생 (getMainPage): ${e.message}")
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
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        println("DEBUG [KKTV] 5. 상세 정보 로드 중: $title")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        println("DEBUG [KKTV] 6. 링크 로드 시작. 타겟 Iframe: $iframeUrl")

        try {
            val iframeDomain = Uri.parse(iframeUrl).host ?: ""
            val iframeInterceptor = WebViewResolver(Regex(iframeDomain))
            
            val responseText = app.get(iframeUrl, headers = defaultHeaders, interceptor = iframeInterceptor).text
            
            val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
            val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

            println("DEBUG [KKTV] 7. 추출된 m3u8 링크 개수: ${streamLinks.size}")

            streamLinks.forEachIndexed { index, link ->
                val cleanLink = link.replace("\\/", "/") 
                println("DEBUG [KKTV] 8. 스트림 링크 추가: $cleanLink")
                
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
                        // 동적으로 추출된 Iframe 주소를 Referer로 박아넣어 재생 차단을 막습니다.
                        this.referer = iframeUrl
                    }
                )
            }
        } catch (e: Exception) {
            println("DEBUG [KKTV] 🚨 에러 발생 (loadLinks): ${e.message}")
            e.printStackTrace()
            return false
        }

        return true
    }
}
