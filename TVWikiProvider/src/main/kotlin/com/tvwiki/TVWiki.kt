package com.tvwiki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder
import com.fasterxml.jackson.annotation.JsonProperty
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * TVWiki Provider
 * Version: 2026-02-08 (Refined)
 * - API Session Logic Enhanced
 * - Extractor Fallback Improved
 */
class TVWiki : MainAPI() {
    // [설정] 도메인이 막히면 이곳을 최신 도메인으로 변경하세요.
    override var mainUrl = "https://tvwiki5.net"
    override var name = "TVWiki"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama,
        TvType.Anime,
        TvType.AnimeMovie
    )

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    data class SessionResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("player_url") val playerUrl: String?,
        @JsonProperty("t") val t: String?,
        @JsonProperty("sig") val sig: String?
    )

    override val mainPage = mainPageOf(
        "/popular" to "인기순위",
        "/kor_movie" to "영화",
        "/drama" to "드라마",
        "/ent" to "예능",
        "/sisa" to "시사/다큐",
        "/movie" to "해외영화",
        "/world" to "해외드라마",
        "/ott_ent" to "해외예능/다큐",
        "/animation" to "일반 애니메이션",
        "/ani_movie" to "극장판 애니",
        "/old_ent" to "추억의 예능",
        "/old_drama" to "추억의 드라마"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            // ID 선택자가 변경될 수 있으므로 여러 케이스 대응
            val list = doc.select("#list_type ul li, .mov_list ul li").mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        var link = fixUrl(aTag.attr("href"))
        
        val title = this.selectFirst("a.title")?.text()?.trim() 
            ?: this.selectFirst("a.title2")?.text()?.trim() 
            ?: this.selectFirst(".subject")?.text()?.trim()
            ?: return null

        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val fixedPoster = fixUrl(poster)

        // 포스터 URL을 상세 페이지로 넘기기 위한 트릭
        if (fixedPoster.isNotEmpty()) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                val separator = if (link.contains("?")) "&" else "?"
                link = "$link${separator}cw_poster=$encodedPoster"
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(
                title,
                link,
                type
            ) { this.posterUrl = fixedPoster }

            else -> newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) { this.posterUrl = fixedPoster }
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

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?stx=$query"
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            val items = doc.select("ul#mov_con_list li, #list_type ul li, .mov_list ul li").mapNotNull { it.toSearchResponse() }
            items
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var passedPoster: String? = null
        var realUrl = url

        // URL 파라미터에서 포스터 추출
        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = url.replace(match.value, "")
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) {
                    realUrl = realUrl.dropLast(1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val doc = app.get(realUrl, headers = commonHeaders).document

        // 제목 추출
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim()
                ?: "Unknown Title"
        }
        
        // 불필요한 텍스트 제거 (예: 1화, 다시보기 등)
        title = title!!.replace(Regex("\\\\s*\\\\d+[화회부].*"), "").replace(" 다시보기", "").trim()

        // 원제 추가
        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            if (pureOriTitle.isNotEmpty() && !pureOriTitle.contains(Regex("[가-힣]"))) {
                title = "$title ($pureOriTitle)"
            }
        }

        // 포스터
        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        if (poster.isNullOrEmpty() && passedPoster != null) {
            poster = passedPoster
        }
        poster = fixUrl(poster ?: "")

        // 메타데이터 추출
        val infoList = doc.select(".bo_v_info dd").map { it.text().trim().replace("개봉년도:", "공개일:") }
        val genreList = doc.select(".tags dd a").map { it.text().trim() }.filter { !it.contains("트레일러") }
        val castList = doc.select(".slider_act .item .name").map { it.text().trim() }

        val metaString = buildString {
            if (infoList.isNotEmpty()) append(infoList.joinToString(" / ")).append("\n")
            if (genreList.isNotEmpty()) append("장르: ").append(genreList.joinToString(", ")).append("\n")
            if (castList.isNotEmpty()) append("출연: ").append(castList.joinToString(", "))
        }

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""
        
        if (story.contains("다시보기") && story.contains("무료")) story = "줄거리 정보 없음"

        val finalPlot = "$metaString\n\n$story".trim()

        // 에피소드 목록 추출
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            
            val thumbImg = li.selectFirst("a.img img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("data-original")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed() // 보통 최신화가 위에 있으므로 역순 정렬

        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                // 영화는 에피소드가 없거나 1개
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) {
                    this.posterUrl = poster
                    this.plot = finalPlot
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = poster
                    this.plot = finalPlot
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
        val doc = app.get(data, headers = commonHeaders).document
        
        println("[TVWiki] Parsing Links for: $data")

        // 1. data-session을 이용한 API 호출 (가장 빠르고 정확함)
        if (extractFromApi(doc, data, subtitleCallback, callback)) {
            return true
        }

        // 2. 정적 파싱 (iframe src 직접 찾기)
        if (findAndExtract(doc, data, subtitleCallback, callback)) {
            return true
        }
        
        // 3. WebView Fallback (최후의 수단)
        println("[TVWiki] Attempting WebView Fallback")
        return try {
            val webViewInterceptor = WebViewResolver(
                Regex("""bunny-frame|googleapis|player\.php"""), 
                timeout = 20000L // 타임아웃 20초로 증가
            )
            val response = app.get(data, headers = commonHeaders, interceptor = webViewInterceptor)
            val webViewDoc = response.document
            
            // WebView 로딩 후 다시 찾기
            findAndExtract(webViewDoc, data, subtitleCallback, callback)
        } catch (e: Exception) {
            println("[TVWiki] WebView Error: ${e.message}")
            false
        }
    }

    private suspend fun extractFromApi(
        doc: Document,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
            
            // data-session1, data-session2 등 여러 속성 시도
            val sessionData = iframe.attr("data-session1").ifEmpty { 
                iframe.attr("data-session2") 
            }.ifEmpty { 
                 iframe.attr("data-session")
            }

            if (sessionData.isNullOrEmpty()) return false

            val apiUrl = "$mainUrl/api/create_session.php"
            val headers = commonHeaders.toMutableMap()
            headers["Content-Type"] = "application/json"
            headers["X-Requested-With"] = "XMLHttpRequest"

            // JSON String 생성 (수동 생성으로 라이브러리 의존성 문제 최소화)
            val requestBody = sessionData.toRequestBody("application/json".toMediaTypeOrNull())
            
            val response = app.post(apiUrl, headers = headers, requestBody = requestBody)
            
            // 응답이 JSON이 아닐 수도 있으므로 Try-Catch
            val json = response.parsedSafe<SessionResponse>()

            if (json != null && json.success && !json.playerUrl.isNullOrEmpty()) {
                val fullUrl = "${json.playerUrl}?t=${json.t}&sig=${json.sig}"
                println("[TVWiki] API URL Found: $fullUrl")
                
                // BunnyPoorCdn 추출기 사용
                return BunnyPoorCdn().extract(fullUrl, referer, subtitleCallback, callback, null)
            }
        } catch (e: Exception) {
            println("[TVWiki] API Extract Failed: ${e.message}")
        }
        return false
    }

    private suspend fun findAndExtract(
        doc: Document,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Iframe Src 찾기
        var iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe == null) {
            iframe = doc.selectFirst("iframe[src*='bunny-frame']")
        }

        if (iframe != null) {
            val playerUrl = iframe.attr("src").ifEmpty { iframe.attr("data-url") }
            if (playerUrl.isNotEmpty()) {
                val fixedUrl = fixUrl(playerUrl).replace("&amp;", "&")
                if (BunnyPoorCdn().extract(fixedUrl, data, subtitleCallback, callback, null)) return true
            }
        }

        // Script 태그 내 URL 찾기 (obfuscated code 대응)
        val scriptTags = doc.select("script")
        val urlRegex = Regex("""https://(player\.bunny-frame\.online|vid\.\w+)/[^"'\s]+""")
        
        for (script in scriptTags) {
            val content = script.html()
            if (urlRegex.containsMatchIn(content)) {
                val match = urlRegex.find(content)
                if (match != null) {
                    val foundUrl = match.value.replace("&amp;", "&")
                    if (BunnyPoorCdn().extract(foundUrl, data, subtitleCallback, callback, null)) return true
                }
            }
        }

        return false
    }
}
