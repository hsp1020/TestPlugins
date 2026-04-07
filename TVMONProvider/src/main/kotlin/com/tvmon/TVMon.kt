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

class TVMon : MainAPI() {
    override var mainUrl = "https://tvmon.site"
    override var name = "TVMON"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    private val commonHeaders = mapOf("User-Agent" to USER_AGENT, "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8", "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7", "Referer" to "$mainUrl/", "Upgrade-Insecure-Requests" to "1")
    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(\d{4}\).*""")
    private val requestMutex = Mutex()

    data class SessionResponse(@JsonProperty("success") val success: Boolean, @JsonProperty("player_url") val playerUrl: String?, @JsonProperty("t") val t: String?, @JsonProperty("sig") val sig: String?)

    override val mainPage = mainPageOf("/kor_movie" to "영화", "/drama" to "드라마", "/ent" to "예능", "/sisa" to "시사/다큐", "/movie" to "해외영화", "/world" to "해외드라마", "/animation" to "애니메이션", "/ani_movie" to "극장판 애니", "/old_drama" to "추억의 드라마", "/old_ent" to "추억의 예능")

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}?page=$page"
        return try {
            val doc = requestMutex.withLock { delay((200..800).random().toLong()); app.get(url, headers = commonHeaders).document }
            var elements = doc.select(".mov_list ul li")
            if (elements.isEmpty()) elements = doc.select(".item")
            if (elements.isEmpty()) elements = doc.select("li, div.item")
            val list = elements.mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e; newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img, a.poster") ?: this.selectFirst("a:has(img)")
        var link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() ?: return null

        var rawPoster = aTag.selectFirst("img")?.let { it.attr("data-original").ifEmpty { it.attr("data-src") }.ifEmpty { it.attr("src") } } ?: ""
        if (rawPoster.startsWith("/")) rawPoster = "$mainUrl$rawPoster"
        val fixedPoster = fixUrl(rawPoster)

        if (fixedPoster.isNotEmpty() && !fixedPoster.contains("no3.png")) {
            try { link = "$link${if (link.contains("?")) "&" else "?"}cw_poster=${URLEncoder.encode(fixedPoster, "UTF-8")}" } catch (e: Exception) {}
        }

        val isMovie = link.contains("/movie") || link.contains("/kor_movie") || link.contains("/ani_movie")
        return if (isMovie) newMovieSearchResponse(title, link, TvType.Movie) { this.posterUrl = fixedPoster }
               else newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = fixedPoster }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("$mainUrl/search?stx=$query", headers = commonHeaders).document
            var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) items = doc.select(".mov_list ul li, .item").mapNotNull { it.toSearchResponse() }
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e; emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var passedPoster: String? = null; var realUrl = url
        try {
            val match = Regex("[?&]cw_poster=([^&]+)").find(url)
            if (match != null) { passedPoster = URLDecoder.decode(match.groupValues[1], "UTF-8"); realUrl = url.replace(match.value, "").trimEnd('?', '&') }
        } catch (e: Exception) {}

        val doc = app.get(realUrl, headers = commonHeaders).document
        var title = (doc.selectFirst("#bo_v_movinfo h3")?.ownText()?.trim() ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim() ?: "Unknown").replace(Regex("\\s*\\d+\\s*[화회부].*"), "").replace(" 다시보기", "").trim()
        val oriTitleFull = doc.selectFirst(".ori_title")?.text()?.trim()?.replace("원제 :", "")?.replace("원제:", "")?.trim()
        if (!oriTitleFull.isNullOrEmpty() && !oriTitleFull.contains(Regex("[가-힣]"))) title = "$title (원제 : $oriTitleFull)"

        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""
        if ((poster.isEmpty() || poster.contains("no3.png") || poster == mainUrl || poster.endsWith("/")) && passedPoster != null) poster = passedPoster

        val tagsList = mutableListOf<String>()
        doc.select(".bo_v_info dd").forEach {
            val text = it.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) tagsList.add(text.replace("개봉년도:", "공개일:").replace(tagCleanRegex, "").trim())
        }
        val genres = doc.select(".ctgs dd a").filter { !it.text().contains("트레일러") && !it.hasClass("btn_watch") }.map { it.text().trim().replace(tagCleanRegex, "") }
        if (genres.isNotEmpty()) tagsList.add("장르: ${genres.joinToString(", ")}")

        var story = doc.selectFirst(".story")?.text()?.trim() ?: doc.selectFirst(".tmdb-overview")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: "다시보기"
        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val href = fixUrl(li.selectFirst("a.ep-link")?.attr("href") ?: return@mapNotNull null)
            val epName = li.selectFirst(".clamp")?.text()?.trim() ?: li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val epThumb = li.selectFirst(".img-container img")?.let { it.attr("data-src").ifEmpty { it.attr("src") } }
            newEpisode(href) { this.name = epName; this.posterUrl = fixUrl(epThumb ?: "") }
        }.reversed()

        val isMovie = realUrl.contains("/movie") || realUrl.contains("/kor_movie") || realUrl.contains("/ani_movie")
        return if (isMovie) newMovieLoadResponse(title, realUrl, TvType.Movie, episodes.firstOrNull()?.data ?: realUrl) { this.posterUrl = fixUrl(poster); this.plot = story; this.tags = tagsList.filter{ it.isNotEmpty()} }
               else newTvSeriesLoadResponse(title, realUrl, TvType.TvSeries, episodes) { this.posterUrl = fixUrl(poster); this.plot = story; this.tags = tagsList.filter{it.isNotEmpty()} }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val doc = try { app.get(data, headers = commonHeaders).document } catch (e: Exception) { if (e is CancellationException) throw e; return false }

        // [고유 개선] Fast-Fail: 죽은 영상에 대한 불필요한 15초 대기 방지
        if (doc.text().contains("삭제된 영상") || doc.text().contains("존재하지 않는") || doc.text().contains("404")) return false

        if (extractFromApi(doc, data, subtitleCallback, callback)) return true

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("data-player1").ifEmpty { iframe.attr("data-player2") }.ifEmpty { iframe.attr("src") }
            if (playerUrl.contains("player.bunny-frame.online")) if(BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)) return true
        }

        try {
            val webViewDoc = app.get(data, headers = commonHeaders, interceptor = WebViewResolver(Regex("bunny-frame|googleapis"), timeout = 15000L)).document
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                if (playerUrl.contains("player.bunny-frame.online")) if(BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)) return true
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }

        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            try {
                val pathMatch = Regex("""/v/[a-z]/[a-zA-Z0-9]+""").find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    callback(newExtractorLink(name, name, m3u8Url.replace(Regex("//v/"), "/v/"), ExtractorLinkType.M3U8) { this.referer = mainUrl; this.headers = commonHeaders })
                    return true
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }
        }
        return false
    }

    private suspend fun extractFromApi(doc: Document, refererUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val sessionData = doc.selectFirst("iframe#view_iframe")?.let { it.attr("data-session1").ifEmpty { it.attr("data-session2") } }
            if (sessionData.isNullOrEmpty()) return false

            val headers = commonHeaders.toMutableMap()
            headers["Content-Type"] = "application/json"; headers["X-Requested-With"] = "XMLHttpRequest"; headers["Referer"] = refererUrl 

            val json = app.post("$mainUrl/api/create_session.php", headers = headers, requestBody = sessionData.toRequestBody("application/json".toMediaTypeOrNull())).parsedSafe<SessionResponse>()
            if (json != null && json.success && !json.playerUrl.isNullOrEmpty()) {
                val fullUrl = "${json.playerUrl}?t=${json.t}&sig=${json.sig}"
                if (fullUrl.contains("player.bunny-frame.online")) return BunnyPoorCdn().extract(fullUrl, refererUrl, subtitleCallback, callback, null)
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
        return false
    }

    private fun extractThumbnailHint(doc: Document) = doc.select("img[src*='/v/'], img[data-src*='/v/']").map { fixUrl(it.attr("src").ifEmpty { it.attr("data-src") }) }.firstOrNull { it.contains(Regex("""/v/[a-z]/""")) }
}
