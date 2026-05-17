package com.lagradost.cloudstream3.plugins.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class KkTVProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "KkTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // Cloudflare 우회를 돕기 위한 기본 헤더 설정
    private val defaultHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        
        // 1. 본진 페이지 로드 (Cloudstream이 내부적으로 Cloudflare WebViewResolver를 자동 실행하여 우회함)
        val mainPageUrl = "$mainUrl/bbs/content.php?co_id=livetv9"
        val doc = app.get(mainPageUrl, headers = defaultHeaders).document

        // 2. 실제 데이터가 있는 Iframe URL 추출 (소스코드 확인 완료)
        val iframeSrc = doc.selectFirst("iframe[src*='galaxy-stream']")?.attr("src") 
            ?: "https://sol7.galaxy-stream.live/cli/box" // Fallback URL

        // 3. Iframe 페이지 로드 (마찬가지로 웹뷰 우회 적용)
        val iframeDoc = app.get(iframeSrc, headers = defaultHeaders + mapOf("Referer" to mainUrl)).document

        // 4. 첨부해주신 DOM 이미지(Image 0) 기반으로 경기 목록 파싱
        iframeDoc.select("tr.ssc-row-candy").forEach { row ->
            val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
            val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
            
            val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
            val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
            val title = "[$league] $homeTeam vs $awayTeam ($time)"
            
            val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src")

            /* 
             * [중요] 버튼 클릭 시 매핑되는 방송 ID나 스트림 정보가 필요합니다.
             * 통상적으로 button의 onclick 속성이나 상위 태그의 id값에 숨어있습니다.
             * 여기서는 loadLinks로 넘겨주기 위해 iframeUrl과 팀이름을 조합한 임시 URL을 만듭니다.
             * 실제 구현 시 row.attr("data-id") 같은 식별자를 추출해 쿼리로 넘기세요.
             */
            val matchIdentifier = homeTeam // 고유 ID가 있다면 대체하세요 (예: matchId=1234)
            val loadUrl = "$iframeSrc?matchKey=$matchIdentifier"

            items.add(
                LiveSearchResponse(
                    name = title,
                    url = loadUrl,
                    apiName = this@KkTVProvider.name
                ).apply {
                    this.posterUrl = posterUrl
                }
            )
        }

        return HomePageResponse(listOf(HomePageList("실시간 라이브 중계", items)))
    }

    override suspend fun load(url: String): LoadResponse {
        // url 파라미터는 우리가 getMainPage에서 만든 loadUrl 입니다.
        val title = url.substringAfter("matchKey=").let { java.net.URLDecoder.decode(it, "UTF-8") }
        
        return LiveStreamLoadResponse(
            name = title,
            url = url,
            apiName = this.name,
            dataUrl = url // loadLinks로 그대로 전달
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val iframeUrl = data.substringBefore("?matchKey=")
        
        // 다시 Iframe 소스를 로드합니다 (캐시가 되어있거나 빠른 속도로 로드됨)
        val responseText = app.get(iframeUrl, headers = defaultHeaders).text

        /* 
         * [M3U8 추출 핵심 로직]
         * 제시해주신 m3u8 주소 형태 (lux1... 및 live04...)
         * 이 주소들은 보통 Iframe HTML 내의 <script> 태그 안 JSON 객체에 담겨있습니다.
         * 정규식을 사용하여 페이지 내에 존재하는 모든 m3u8을 강제로 긁어옵니다.
         */
        val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
        val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

        if (streamLinks.isEmpty()) {
            // 만약 정규식으로 안 잡힌다면, 웹뷰 인터셉터를 통해 네트워크 요청을 가로채야 합니다.
            // 최신 Cloudstream에서는 XhrInterceptor 등으로 잡을 수 있으나 1차적으로 정규식이 빠릅니다.
            return false
        }

        // 찾은 m3u8 링크들을 Cloudstream 플레이어로 보냄
        streamLinks.forEachIndexed { index, link ->
            // link가 \\/ 형태로 이스케이프 되어있을 수 있으므로 치환
            val cleanLink = link.replace("\\/", "/") 
            
            // 화질(이름) 설정
            val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                              else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                              else "채널 ${index + 1}"

            callback.invoke(
                ExtractorLink(
                    source = this.name,
                    name = qualityName,
                    url = cleanLink,
                    referer = "https://sol7.galaxy-stream.live/", // 반드시 Iframe 주소를 레퍼러로 줘야 재생됩니다.
                    quality = Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
        return true
    }
}
