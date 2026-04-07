package com.tvmon

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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException

/**
 * TVMon Provider v1.7
 * [변경 이력]
 * - v1.4: 상세 정보 태그화 도입
 * - v1.5: "공개일" 값이 사라지는 버그 수정 (정규식 정교화 및 태그 정제 로직 보완)
 * - v1.6: TVWiki와 동일하게 API 기반 세션 처리(create_session.php) 및 WebViewResolver 폴백 적용 (c.html 파싱 실패 오류 수정)
 * - v1.7: TVWiki의 안티봇 우회 로직(Mutex 직렬화 및 랜덤 지연) 및 코루틴 취소(CancellationException) 방어 로직 적용
 */
class TVMon : MainAPI() {
    override var mainUrl = "https://tvmon.site"
    override var name = "TVMON"
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(\d{4}\).*""")
    private val requestMutex = Mutex()

    data class SessionResponse(
        @JsonProperty("success") val success: Boolean,
        @JsonProperty("player_url") val playerUrl: String?,
        @JsonProperty("t") val t: String?,
        @JsonProperty("sig") val sig: String?
    )

    override val mainPage = mainPageOf(
        "/kor_movie" to "영화",
        "/drama" to "드라마",
        "/ent" to "예능",
        "/sisa" to "시사/다큐",
        "/movie" to "해외영화",
        "/world" to "해외드라마",
        "/animation" to "애니메이션",
        "/ani_movie" to "극장판 애니",
        "/old_drama" to "추억의 드라마",
        "/old_ent" to "추억의 예능"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        println("[TVMon][v1.7] getMainPage 요청: $url")
        
        return try {
            val doc = requestMutex.withLock {
                val randomDelay = (200..800).random().toLong()
                delay(randomDelay)
                app.get(url, headers = commonHeaders).document
            }
            var elements = doc.select(".mov_list ul li")
            if (elements.isEmpty()) elements = doc.select(".item")
            if (elements.isEmpty()) elements = doc.select("li, div.item")

            val list = elements.mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.poster") ?: return null
        var link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null

        val imgTag = aTag.selectFirst("img")
        var rawPoster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        if (rawPoster.startsWith("/")) rawPoster = "$mainUrl$rawPoster"
        val fixedPoster = fixUrl(rawPoster)

        if (fixedPoster.isNotEmpty() && !fixedPoster.contains("no3.png")) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                val separator = if (link.contains("?")) "&" else "?"
                link = "$link${separator}cw_poster=$encodedPoster"
            } catch (e: Exception) { e.printStackTrace() }
        }

        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, link, type) { this.posterUrl = fixedPoster }
            else -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = fixedPoster }
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
        println("[TVMon][v1.7] 검색 실행: $query")
        val searchUrl = "$mainUrl/search?stx=$query"
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            
            var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) {
                 items = doc.select(".mov_list ul li, .item").mapNotNull { it.toSearchResponse() }
            }
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[TVMon][v1.7] 상세 페이지 load 진입: $url")

        var passedPoster: String? = null
        var realUrl = url

        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = url.replace(match.value, "")
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) realUrl = realUrl.dropLast(1)
            }
        } catch (e: Exception) { println("[TVMon] 포스터 파라미터 복원 실패: ${e.message}") }

        val doc = app.get(realUrl, headers = commonHeaders).document

        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim() ?: "Unknown"
        }
        title = title!!.replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            if (!pureOriTitle.contains(Regex("[가-힣]")) && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""

        val isInvalidPoster = poster.isEmpty() || poster.contains("no3.png") || poster == mainUrl || poster.endsWith("/")
        if (isInvalidPoster && passedPoster != null) poster = passedPoster

        val tagsList = mutableListOf<String>()
        
        doc.select(".bo_v_info dd").forEach { dd ->
            var text = dd.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) {
                val renamedText = text.replace("개봉년도:", "공개일:")
                val cleanedText = renamedText.replace(tagCleanRegex, "").trim()
                if (cleanedText.isNotEmpty()) {
                    tagsList.add(cleanedText)
                }
            }
        }

        val genres = doc.select(".ctgs dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim().replace(tagCleanRegex, "") }

        if (genres.isNotEmpty()) {
            val genreTag = "장르: ${genres.joinToString(", ")}"
            tagsList.add(genreTag)
        }

        var story = doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst(".tmdb-overview")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: "다시보기"

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst(".clamp")?.text()?.trim() ?: li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = li.selectFirst(".img-container img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null } ?: thumbImg?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed()

        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                newMovieLoadResponse(title, realUrl, type, episodes.firstOrNull()?.data ?: realUrl) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story
                    this.tags = tagsList
                }
            }
            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = story
                    this.tags = tagsList
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
        println("[TVMon][v1.7] loadLinks 진입")
        val doc = try {
            app.get(data, headers = commonHeaders).document
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return false
        }

        // [v1.6 핵심 추가] TVWiki와 동일한 API 세션 파싱 시도
        if (extractFromApi(doc, data, subtitleCallback, callback)) {
            return true
        }

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("data-player1").ifEmpty { iframe.attr("data-player2") }.ifEmpty { iframe.attr("src") }
            if (playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) return true
            }
        }

        // [v1.6 핵심 추가] WebViewResolver를 통한 동적 Iframe 파싱 시도
        try {
            println("[TVMon][v1.7] WebViewResolver를 통한 동적 iframe 추출 시도")
            val webViewInterceptor = WebViewResolver(
                Regex("bunny-frame|googleapis"), 
                timeout = 15000L
            )
            val response = app.get(data, headers = commonHeaders, interceptor = webViewInterceptor)
            val webViewDoc = response.document
            
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                println("[TVMon][v1.7] WebViewResolver 추출 성공: $playerUrl")
                if (playerUrl.contains("player.bunny-frame.online")) {
                     val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                     if(extracted) return true
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[TVMon][v1.7] WebViewResolver 추출 예외: ${e.message}")
        }

        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            try {
                println("[TVMon][v1.7] thumbnailHint를 이용한 우회 재생 시도")
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    callback(newExtractorLink(name, name, m3u8Url.replace(Regex("//v/"), "/v/"), ExtractorLinkType.M3U8) {
                        this.referer = mainUrl
                        this.headers = commonHeaders
                    })
                    return true
                }
            } catch (e: Exception) { 
                if (e is CancellationException) throw e
                e.printStackTrace() 
            }
        }
        return false
    }

    private suspend fun extractFromApi(
        doc: Document,
        refererUrl: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
            val sessionData = iframe.attr("data-session1").ifEmpty { iframe.attr("data-session2") }

            if (sessionData.isNullOrEmpty()) return false

            println("[TVMon][v1.7] 세션 API 요청 시도")
            val apiUrl = "$mainUrl/api/create_session.php"
            val headers = commonHeaders.toMutableMap()
            headers["Content-Type"] = "application/json"
            headers["X-Requested-With"] = "XMLHttpRequest"
            headers["Referer"] = refererUrl 

            val requestBody = sessionData.toRequestBody("application/json".toMediaTypeOrNull())
            val response = app.post(apiUrl, headers = headers, requestBody = requestBody)
            val json = response.parsedSafe<SessionResponse>()

            if (json != null && json.success && !json.playerUrl.isNullOrEmpty()) {
                val fullUrl = "${json.playerUrl}?t=${json.t}&sig=${json.sig}"
                println("[TVMon][v1.7] API 세션 획득 성공. Player URL: $fullUrl")
                if (fullUrl.contains("player.bunny-frame.online")) {
                    return BunnyPoorCdn().extract(fullUrl, refererUrl, subtitleCallback, callback, null)
                }
            }
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
            e.printStackTrace() 
        }
        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw)
            if (fixed.contains(Regex("""/v/[a-z]/"""))) return fixed
        }
        return null
    }
}
