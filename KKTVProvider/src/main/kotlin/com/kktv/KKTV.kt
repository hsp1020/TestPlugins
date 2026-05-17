// v7.8 - 텔레그램 채널(t.me/s/kktvinfo) 기반 동적 도메인 파싱 추가 및 기존 포털 로직 Fallback 강등
package com.KingkongTv

// v1.2
package com.lagradost.cloudstream3.plugins

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class QuickTvProvider : MainAPI() {
    override var mainUrl = "https://quick-tv.com"
    override var name = "빠른티비"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Live, TvType.Sports)

    // v1.2: 부모 페이지 호출 후 iframe 추적 로직 추가
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        Log.d("QuickTv", "getMainPage 진입 - page: $page")
        val items = mutableListOf<SearchResponse>()
        
        // 사용자가 명시한 경기 목록 부모 페이지
        val parentUrl = "$mainUrl/bbs/content.php?co_id=livetv9"
        val parentDoc = app.get(parentUrl).document
        
        // iframe src 추출
        val iframeUrl = parentDoc.selectFirst("iframe[src*='galaxy-stream.live']")?.attr("src")
            ?: "https://sol7.galaxy-stream.live/cli/box" // 폴백(Fallback) URL
        
        Log.d("QuickTv", "추출된 iframe URL: $iframeUrl")
        
        // iframe 내부 문서 요청
        val document = app.get(iframeUrl, referer = parentUrl).document
        
        val rows = document.select("tr.ssc-row-candy")
        if (rows.isEmpty()) {
            Log.d("QuickTv", "파싱 대상 요소(tr.ssc-row-candy) 없음. DOM 구조 변경 확인 필요.")
        }
        
        rows.forEach { element ->
            val time = element.selectFirst(".ssc-schedule-td")?.text() ?: ""
            val league = element.selectFirst(".ssc-league-td")?.text() ?: ""
            val thumb = element.selectFirst("img.ssc-thumbnail-icon")?.attr("src") ?: ""
            val team1 = element.selectFirst(".ssc-title-team-1-text")?.text() ?: ""
            val team2 = element.selectFirst(".ssc-title-team-2-text")?.text() ?: ""
            
            if (team1.isNotBlank() && team2.isNotBlank()) {
                val title = "[$league] $team1 vs $team2 ($time)"
                val targetUrl = "$iframeUrl?match=${team1}_$team2" // 가설 기반 라우팅 URL
                
                Log.d("QuickTv", "매치 추가: $title")
                items.add(
                    LiveSearchResponse(
                        name = title,
                        url = targetUrl,
                        apiName = this@QuickTvProvider.name,
                        type = TvType.Live,
                        posterUrl = thumb
                    )
                )
            }
        }
        
        return HomePageResponse(listOf(HomePageList("실시간 스포츠 중계", items)))
    }

    // v1.2: 로드 로직
    override suspend fun load(url: String): LoadResponse {
        Log.d("QuickTv", "load 진입 - url: $url")
        return LiveStreamLoadResponse(
            name = "실시간 스트리밍",
            url = url,
            apiName = this.name,
            dataUrl = url
        )
    }

    // v1.2: 링크 추출 로직 (WebView 스니핑 및 정규식 교정 적용)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("QuickTv", "loadLinks 진입 - data: $data")
        
        // 동적 서버 넘버링 대응 정규식
        val targetRegex = Regex("""(lux\d+\.hudtv\d+\.com|live\d+\.coupangstreaming\.com).*?\.m3u8""")
        
        try {
            Log.d("QuickTv", "WebViewResolver 인터셉터 실행")
            app.get(
                url = data,
                interceptor = WebViewResolver(targetRegex) { request ->
                    val detectedUrl = request.url.toString()
                    Log.d("QuickTv", "m3u8 패킷 스니핑 성공: $detectedUrl")
                    
                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "QuickTV HLS",
                            url = detectedUrl,
                            referer = "https://sol7.galaxy-stream.live/",
                            quality = Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                }
            )
            Log.d("QuickTv", "loadLinks 정상 종료")
            return true
        } catch (e: Exception) {
            Log.e("QuickTv", "WebViewResolver 실행 중 예외 발생", e)
            return false
        }
    }
}
