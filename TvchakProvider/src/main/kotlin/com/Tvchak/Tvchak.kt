package com.lagradost.cloudstream3.plugins.tvchak

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor

class TvchakProvider : MainAPI() {
    override var mainUrl = "https://tvchak200.com"
    override var name = "Tvchak"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.AsianDrama, TvType.Movie, TvType.Anime)

    // 동적 도메인 추적: 리다이렉트 또는 호스트 변경 자동 감지
    private val client = app.newBuilder()
        .addInterceptor { chain ->
            val request = chain.request()
            val response = chain.proceed(request)
            val currentHost = response.request.url.host
            if (currentHost != request.url.host && !mainUrl.contains(currentHost)) {
                mainUrl = "https://$currentHost"
            }
            response
        }.build()

    override val mainPage = mainPageOf(
        "1" to "영화",
        "2" to "드라마",
        "3" to "예능",
        "4" to "애니"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = client.get("$mainUrl/index.php/vod/type/id/${request.data}/page/$page.html").document
        
        // 메인 페이지 전용 그리드 형태 선택자 통합
        val items = document.select(".stui-vodlist__box, ul.stui-vodlist li, .vodlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, .thumb a") ?: return@mapNotNull null
            val title = anchor.attr("title").ifEmpty { anchor.text() }
            val link = fixUrl(anchor.attr("href"))
            val poster = fixUrl(anchor.attr("data-original").ifEmpty { anchor.attr("src") })
            
            // 메타데이터 소실 방지: URL에 Payload 인젝션
            val payloadUrl = "$link||$poster||$title"
            
            newTvSeriesSearchResponse(title, payloadUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        return newHomePageResponse(request.name, items)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = client.get("$mainUrl/index.php/vod/search/wd/$query.html").document
        
        // 검색 페이지 전용 리스트/미디어 형태 다형성 선택자 적용
        val items = document.select("ul.stui-vodlist__media li, .stui-vodlist__box, ul.stui-vodlist li, .searchlist li").mapNotNull {
            val anchor = it.selectFirst("a.stui-vodlist__thumb, a.v-thumb, a.thumb") ?: return@mapNotNull null
            
            val title = anchor.attr("title").ifEmpty {
                it.selectFirst(".title, h3, h4")?.text() ?: anchor.text()
            }
            val link = fixUrl(anchor.attr("href"))
            val poster = fixUrl(anchor.attr("data-original").ifEmpty { anchor.attr("src") })
            
            newTvSeriesSearchResponse(title, "$link||$poster||$title", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
        
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        // Payload 파싱을 통한 O(1) 상태 복원 (인메모리 캐시 회피)
        val payload = url.split("||")
        val targetUrl = payload.first()
        val injectedPoster = payload.getOrNull(1)
        val injectedTitle = payload.getOrNull(2) ?: "Unknown"

        val document = client.get(targetUrl).document
        val title = document.selectFirst(".title, h1, .stui-content__title")?.text() ?: injectedTitle
        
        val episodes = document.select(".stui-content__playlist li a, .playlist li a").map {
            Episode(
                data = fixUrl(it.attr("href")),
                name = it.text().trim()
            )
        }

        return newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes) {
            this.posterUrl = injectedPoster ?: document.selectFirst(".picture img")?.attr("src")
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        callback: (ExtractorLink) -> Unit,
        subtitleCallback: (SubtitleFile) -> Unit
    ): Boolean {
        val html = client.get(data).text
        
        // 1. 유연한 미디어 정규식 추출 (.mp4 하드코딩 배제, 백트래킹 제한)
        val cdnRegex = """(https?://[\w\-.]+\.b-cdn\.net/[^"'\s<>]+)""".toRegex(RegexOption.IGNORE_CASE)
        val match = cdnRegex.find(html)
        
        if (match != null) {
            val videoUrl = match.groupValues[1]
            val isM3u8 = videoUrl.contains(".m3u8", ignoreCase = true)
            
            if (isM3u8) {
                M3u8Helper.generateM3u8(this.name, videoUrl, mainUrl).forEach(callback)
            } else {
                callback.invoke(
                    ExtractorLink(
                        this.name,
                        "Direct HD",
                        videoUrl,
                        "",
                        Qualities.P1080.value,
                        isM3u8 = false
                    )
                )
            }
            return true
        }
        
        // 2. 범용 플레이어 데이터 파싱 (MacCMS JSON Fallback)
        val playerDataMatch = """var\s+player_data\s*=\s*(\{.*?\})""".toRegex().find(html)
        if (playerDataMatch != null) {
            runCatching {
                val json = parseJson<Map<String, String>>(playerDataMatch.groupValues[1])
                val videoUrl = json["url"] ?: return@runCatching
                
                if (videoUrl.contains(".m3u8", true)) {
                    M3u8Helper.generateM3u8(this.name, videoUrl, mainUrl).forEach(callback)
                } else {
                    loadExtractor(videoUrl, mainUrl, subtitleCallback, callback)
                }
            }
            return true
        }
        
        // 3. 최후 수단: iframe 우회
        val document = org.jsoup.Jsoup.parse(html)
        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) loadExtractor(src, mainUrl, subtitleCallback, callback)
        }

        return true
    }
}
