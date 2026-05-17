package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URLDecoder
import java.net.URLEncoder

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV (빠른티비)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // [핵심 수정] 이 부분이 빠져서 앱이 어디로 들어가야 할지 몰라 빈 화면만 떴던 것입니다!
    // 사용자님이 말씀하신 경기목록 URL을 정확히 타겟팅합니다.
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
        // request.data 에는 위에서 설정한 "/bbs/content.php?co_id=livetv9" 가 들어옵니다.
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            println("DEBUG [KKTV] 1. 메인 페이지 로드 시작: $url")
            // Cloudstream의 app.get은 CF(클라우드플레어) 감지 시 자동으로 백그라운드 웹뷰를 띄워 우회합니다.
            val doc = app.get(url, headers = defaultHeaders).document
            
            var iframeSrc = doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src")
            println("DEBUG [KKTV] 2. 추출된 Iframe 원본 주소: $iframeSrc")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://sol7.galaxy-stream.live/cli/box"
            }

            println("DEBUG [KKTV] 3. Iframe 페이지 로드 시작 (Cloudflare 검사 통과 시도)")
            val iframeDoc = app.get(iframeSrc, headers = defaultHeaders + mapOf("Referer" to url)).document
            
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

                    // 원본 코드의 방식대로 데이터 전달을 위해 URL 인코딩
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
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol7.galaxy-stream.live/cli/box"

        println("DEBUG [KKTV] 6. 링크 로드 시작. 타겟 Iframe: $iframeUrl")

        try {
            val responseText = app.get(iframeUrl, headers = defaultHeaders).text
            
            // 말씀해주신 영상 URL 형식(hudtv, coupangstreaming)을 모두 잡아내는 정규식
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
                        this.referer = "https://sol7.galaxy-stream.live/"
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
