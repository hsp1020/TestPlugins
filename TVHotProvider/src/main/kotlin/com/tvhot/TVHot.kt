package com.tvhot

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.fixUrl
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class TVHot : MainAPI() {
    override var mainUrl = "https://tvhot.store"
    override var name = "TVHot"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    private fun Element.toSearchResponse(): SearchResponse? {
        val onClick = this.attr("onclick")
        val link = Regex("""location\.href='(.*?)'""").find(onClick)?.groupValues?.get(1) ?: return null
        val title = this.selectFirst("div.title")?.text()?.trim() ?: return null
        val poster = this.selectFirst("div.img img")?.attr("src")
        val type = determineTypeFromUrl(link)
        
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, fixUrl(link), type) { this.posterUrl = fixUrl(poster ?: "") }
            TvType.Anime -> newAnimeSearchResponse(title, fixUrl(link), TvType.Anime) { this.posterUrl = fixUrl(poster ?: "") }
            else -> newAnimeSearchResponse(title, fixUrl(link), TvType.TvSeries) { this.posterUrl = fixUrl(poster ?: "") }
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            else -> TvType.TvSeries
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(emptyList())
        val doc = app.get(mainUrl).document
        val home = mutableListOf<HomePageList>()
        doc.select("div.mov_type").forEach { section ->
            val title = section.selectFirst("h2 strong")?.text()?.trim()?.replace("무료 ", "")?.replace(" 다시보기", "") ?: "추천"
            val listItems = section.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
            if (listItems.isNotEmpty()) home.add(HomePageList(title, listItems))
        }
        return newHomePageResponse(home, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val doc = app.get("$mainUrl/search?stx=$encodedQuery").document
        return doc.select("ul li[onclick]").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
        )
        
        val doc = app.get(url, headers = headers).document
        var title = doc.selectFirst("div.tmdb-card-top img")?.attr("alt")?.trim() 
            ?: doc.selectFirst("h2#bo_v_title .bo_v_tit")?.text()?.trim() ?: "Unknown"
        
        title = title.replace(Regex("""\s*\d+\s*(?:회|화|부)\s*"""), "").trim()
        
        val episodes = doc.select("div#other_list ul li").mapNotNull {
            val aTag = it.selectFirst("a") ?: return@mapNotNull null
            val epTitle = it.selectFirst(".title")?.text()?.trim() ?: "Episode"
            newEpisode(fixUrl(aTag.attr("href"))) { this.name = epTitle }
        }.reversed()

        val type = determineTypeFromUrl(url)
        val poster = fixUrl(doc.selectFirst(".tmdb-card-top img")?.attr("src") ?: "")
        val plot = doc.selectFirst(".tmdb-overview")?.text()

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) { 
                    this.posterUrl = poster
                    this.plot = plot 
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) { 
                    this.posterUrl = poster
                    this.plot = plot 
                }
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Referer" to mainUrl,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br"
        )
        
        val response = app.get(data, headers = headers).text
        
        // 여러 패턴으로 플레이어 URL 찾기
        val playerPatterns = listOf(
            Regex("""https://player\.bunny-frame\.online/[^"']+"""),
            Regex("""src=["'](https://player\.bunny-frame\.online/[^"']+)["']"""),
            Regex("""iframe.*?src=["'](https://player\.bunny-frame\.online/[^"']+)["']"""),
            Regex("""<iframe[^>]+src=["'](https://player\.bunny-frame\.online/[^"']+)["']"""),
            Regex("""player\.bunny-frame\.online/(?:embed/|\\?)[^"']+"""),
            // Base64 인코딩된 URL 찾기
            Regex("""atob\(["']([^"']+)["']\)"""),
            // JSON 내부 URL
            Regex("""["']url["']\s*:\s*["'](https://player\.bunny-frame\.online/[^"']+)["']""")
        )
        
        var playerUrl: String? = null
        
        for (pattern in playerPatterns) {
            try {
                val match = pattern.find(response)?.value
                if (match != null) {
                    playerUrl = match
                    println("DEBUG_MAIN: Found Player URL via pattern ${pattern.pattern.take(30)}...: $playerUrl")
                    break
                }
            } catch (e: Exception) {
                println("DEBUG_MAIN: Error with pattern ${pattern.pattern}: ${e.message}")
            }
        }
        
        // Base64 디코딩 필요한 경우
        if (playerUrl?.contains("atob") == true) {
            val base64Match = Regex("""atob\(["']([A-Za-z0-9+/=]+)["']\)""").find(playerUrl)?.groupValues?.get(1)
            if (base64Match != null) {
                try {
                    val decoded = android.util.Base64.decode(base64Match, android.util.Base64.DEFAULT).toString(Charsets.UTF_8)
                    playerUrl = decoded
                    println("DEBUG_MAIN: Decoded base64 URL: $playerUrl")
                } catch (e: Exception) {
                    println("DEBUG_MAIN: Base64 decode error: ${e.message}")
                }
            }
        }
        
        if (playerUrl == null) {
            // 대체 방법: HTML 전체에서 bunny-frame 검색
            val allMatches = Regex("""bunny-frame[^"']*""").findAll(response).map { it.value }.toList()
            if (allMatches.isNotEmpty()) {
                println("DEBUG_MAIN: Found bunny-frame references: ${allMatches.take(3)}...")
                // 가장 긴 매치 선택 (가장 완전한 URL일 가능성)
                playerUrl = allMatches.maxByOrNull { it.length }
                if (!playerUrl!!.startsWith("http")) {
                    playerUrl = "https://player.$playerUrl"
                }
            }
        }
        
        if (playerUrl == null) {
            println("DEBUG_MAIN: No Player URL found in entire page source (length: ${response.length})")
            
            // 디버깅을 위한 응답 일부 출력
            val sample = response.take(5000)
            println("DEBUG_MAIN: Response sample (first 5000 chars):")
            println(sample)
            
            // iframe src 속성 직접 검색
            val iframeSrc = Regex("""<iframe[^>]+src=["']([^"']+)["']""").find(response)?.groupValues?.get(1)
            if (iframeSrc != null) {
                playerUrl = iframeSrc
                println("DEBUG_MAIN: Found iframe src: $playerUrl")
            }
            
            if (playerUrl == null) {
                return false
            }
        }

        // URL 정리
        var finalPlayerUrl = fixUrl(playerUrl.replace("&amp;", "&").replace("\\/", "/"))
        
        // 상대경로인 경우 절대경로로 변환
        if (finalPlayerUrl.startsWith("//")) {
            finalPlayerUrl = "https:$finalPlayerUrl"
        } else if (finalPlayerUrl.startsWith("/")) {
            finalPlayerUrl = "https://player.bunny-frame.online$finalPlayerUrl"
        }
        
        // URL에 쿼리 파라미터 추가 (필요한 경우)
        if (!finalPlayerUrl.contains("?")) {
            finalPlayerUrl += "?"
        }
        
        if (!finalPlayerUrl.contains("s=")) {
            finalPlayerUrl += if (finalPlayerUrl.endsWith("?")) "s=9" else "&s=9"
        }
        
        println("DEBUG_MAIN: Final Player URL: $finalPlayerUrl")

        // 추출기 호출 (참조자로 현재 페이지 URL 전달)
        try {
            BunnyPoorCdn().getUrl(finalPlayerUrl, data, subtitleCallback, callback)
            return true
        } catch (e: Exception) {
            println("DEBUG_MAIN: Extractor failed: ${e.message}")
            e.printStackTrace()
            return false
        }
    }
}
