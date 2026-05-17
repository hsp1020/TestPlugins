package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.coroutines.delay

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 사용자님 지시대로 무조건 이 주소로 들어갑니다.
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
            
            // 웹뷰리졸버 실행
            var doc = app.get(url, headers = defaultHeaders, interceptor = WebViewResolver(Regex("""quick-tv\.com"""))).document
            var retries = 0
            
            // [핵심 로직] 사용자님 지시대로 대기시간을 줍니다.
            // CF 챌린지 화면(Just a moment)이 뜨면 3초 대기 후 다시 웹뷰를 열어 쿠키를 획득합니다.
            while ((doc.title().contains("Just a moment") || doc.title().contains("Cloudflare")) && retries < 5) {
                println("DEBUG [KKTV] 🚨 클라우드플레어 감지됨. 30초 대기 후 웹뷰리졸버 재실행... (${retries + 1}/5)")
                delay(30000) // 3초 대기
                doc = app.get(url, headers = defaultHeaders, interceptor = WebViewResolver(Regex("""quick-tv\.com"""))).document
                retries++
            }
            
            println("DEBUG [KKTV] 2. 메인 페이지 최종 응답 타이틀: [${doc.title()}]")

            // 메인 페이지에서 Iframe 주소 추출 (Fallback 없이 무조건 여기서 찾습니다)
            val iframeSrc = doc.selectFirst("div#player_field iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
                ?: doc.selectFirst("iframe[src*='hudtv']")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                println("DEBUG [KKTV] 🚨 메인 페이지에서 Iframe을 찾지 못했습니다. 사이트 구조 변경 의심.")
                return newHomePageResponse(request.name, emptyList())
            }

            println("DEBUG [KKTV] 3. 정상 추출된 Iframe 주소: $iframeSrc")

            // Iframe 호출 (500 에러 방지를 위해 Referer 필수)
            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            val iframeDoc = Jsoup.parse(iframeHtml)
            
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
                        newLiveSearchResponse(name = title, url = fakeUrl) {
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

        try {
            // 영상 링크를 딸 때도 500 에러 방지를 위해 Referer를 강제합니다.
            val responseText = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/bbs/content.php?co_id=livetv9").text
            
            val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
            val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

            streamLinks.forEachIndexed { index, link ->
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
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
}
