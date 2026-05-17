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
            println("DEBUG [KKTV] ==========================================")
            println("DEBUG [KKTV] 1. 메인 페이지 로드 시작: $url")
            
            val mainInterceptor = WebViewResolver(Regex("""quick-tv\.com"""))
            val doc = app.get(url, headers = defaultHeaders, interceptor = mainInterceptor).document
            
            // [명확한 로그 1] 메인 페이지 타이틀 확인
            val pageTitle = doc.title()
            println("DEBUG [KKTV] 2. 메인 페이지 응답 타이틀: [$pageTitle]")
            
            if (pageTitle.contains("Just a moment") || pageTitle.contains("Cloudflare")) {
                println("DEBUG [KKTV] 🚨 [원인 100% 특정] 클라우드플레어(CF) 방어에 막혔습니다. 웹뷰 우회가 실패했습니다.")
            } else {
                println("DEBUG [KKTV] ✅ 메인 페이지 CF 통과 성공.")
            }

            var iframeSrc = doc.selectFirst("div#player_field iframe")?.attr("src")
                ?: doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                println("DEBUG [KKTV] 🚨 [원인 100% 특정] CF는 통과했으나 iframe 태그가 없습니다. 사이트 DOM 구조가 변경되었습니다.")
                println("DEBUG [KKTV] HTML 미리보기: ${doc.html().take(300)}")
                
                // 하드코딩 Fallback 복구
                iframeSrc = "https://sol7.galaxy-stream.live/cli/box"
                println("DEBUG [KKTV] 💡 Fallback Iframe 주소로 강제 진행합니다: $iframeSrc")
            } else {
                println("DEBUG [KKTV] 3. 정상 추출된 Iframe 주소: $iframeSrc")
            }

            // [핵심 수정] Iframe은 WebViewResolver를 쓰면 Referer가 누락되어 500 에러가 납니다. 순수 get 요청으로 Referer만 확실히 넘깁니다.
            println("DEBUG [KKTV] 4. Iframe 페이지 로드 시작 (Referer: $url)")
            val iframeDoc = app.get(iframeSrc, headers = defaultHeaders, referer = url).document
            
            // [명확한 로그 2] Iframe 타이틀 및 500 에러 확인
            val iframeTitle = iframeDoc.title()
            println("DEBUG [KKTV] 5. Iframe 응답 타이틀: [$iframeTitle]")
            
            if (iframeTitle.contains("500") || iframeTitle.contains("Error")) {
                println("DEBUG [KKTV] 🚨 [원인 100% 특정] Iframe 서버에서 500 에러를 뱉었습니다. Referer 차단이거나 서버가 죽었습니다.")
            }

            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [KKTV] 6. 파싱된 경기 개수: ${rows.size}")

            if (rows.isEmpty()) {
                println("DEBUG [KKTV] 🚨 [원인 100% 특정] 500 에러는 아니지만 경기 목록(tr.ssc-row-candy)이 없습니다.")
                println("DEBUG [KKTV] Iframe HTML 미리보기: ${iframeDoc.html().take(500)}")
            }

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
            println("DEBUG [KKTV] ==========================================")
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
            // 재생 링크를 딸 때도 500 에러 방지를 위해 WebViewResolver를 빼고 Referer를 강제합니다.
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
