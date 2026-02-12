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
 * Version: 2026-02-12-Fix-v2
 */
class TVWiki : MainAPI() {
    // [설정] 도메인 변경 시 여기 수정
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
            val list = doc.select("#list_type ul li, .mov_list ul li").mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
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
        if (fixedPoster.isNotEmpty()) {
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
        val searchUrl = "$mainUrl/search?stx=$query"
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            doc.select("ul#mov_con_list li, #list_type ul li, .mov_list ul li").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) { emptyList() }
    }

    override suspend fun load(url: String): LoadResponse {
        var passedPoster: String? = null
        var realUrl = url

        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                passedPoster = URLDecoder.decode(match.groupValues[1], "UTF-8")
                realUrl = url.replace(match.value, "")
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) realUrl = realUrl.dropLast(1)
            }
        } catch (e: Exception) { e.printStackTrace() }

        val doc = app.get(realUrl, headers = commonHeaders).document
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim() 
            ?: doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim() 
            ?: "Unknown Title"
        
        title = title.replace(Regex("\\\\s*\\\\d+[화회부].*"), "").replace(" 다시보기", "").trim()
        
        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        if (poster.isNullOrEmpty() && passedPoster != null) poster = passedPoster
        poster = fixUrl(poster ?: "")

        val story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: ""

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            newEpisode(href) { this.name = epName }
        }.reversed()

        val type = determineTypeFromUrl(realUrl)
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieLoadResponse(title, realUrl, type, episodes.firstOrNull()?.data ?: realUrl) {
                this.posterUrl = poster; this.plot = story
            }
            else -> newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                this.posterUrl = poster; this.plot = story
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
        println("[TVWiki] loadLinks: $data")

        if (extractFromApi(doc, data, subtitleCallback, callback)) return true
        if (findAndExtract(doc, data, subtitleCallback, callback)) return true
        
        println("[TVWiki] Fallback to WebView")
        return try {
            val webViewInterceptor = WebViewResolver(
                Regex("""bunny-frame|googleapis|player\.php"""), 
                timeout = 20000L
            )
            val response = app.get(data, headers = commonHeaders, interceptor = webViewInterceptor)
            findAndExtract(response.document, data, subtitleCallback, callback)
        } catch (e: Exception) { false }
    }

    private suspend fun extractFromApi(doc: Document, referer: String, subCb: (SubtitleFile)->Unit, cb: (ExtractorLink)->Unit): Boolean {
        try {
            val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
            val sessionData = iframe.attr("data-session1").ifEmpty { iframe.attr("data-session") }
            if (sessionData.isEmpty()) return false

            val headers = commonHeaders.toMutableMap().apply {
                put("Content-Type", "application/json")
                put("X-Requested-With", "XMLHttpRequest")
            }
            
            val response = app.post(
                "$mainUrl/api/create_session.php", 
                headers = headers, 
                requestBody = sessionData.toRequestBody("application/json".toMediaTypeOrNull())
            )
            val json = response.parsedSafe<SessionResponse>()

            if (json?.success == true && !json.playerUrl.isNullOrEmpty()) {
                val fullUrl = "${json.playerUrl}?t=${json.t}&sig=${json.sig}"
                return BunnyPoorCdn().extract(fullUrl, referer, subCb, cb)
            }
        } catch (e: Exception) { e.printStackTrace() }
        return false
    }

    private suspend fun findAndExtract(doc: Document, data: String, subCb: (SubtitleFile)->Unit, cb: (ExtractorLink)->Unit): Boolean {
        // Iframe Search
        val iframeSrc = doc.selectFirst("iframe#view_iframe")?.attr("src") 
            ?: doc.selectFirst("iframe[src*='bunny-frame']")?.attr("src")
        
        if (!iframeSrc.isNullOrEmpty()) {
            val fixedUrl = fixUrl(iframeSrc).replace("&amp;", "&")
            if (BunnyPoorCdn().extract(fixedUrl, data, subCb, cb)) return true
        }

        // Script Search
        doc.select("script").forEach { script ->
            val match = Regex("""https://(player\.bunny-frame\.online|vid\.\w+)/[^"'\s]+""").find(script.html())
            if (match != null) {
                val foundUrl = match.value.replace("&amp;", "&")
                if (BunnyPoorCdn().extract(foundUrl, data, subCb, cb)) return true
            }
        }
        return false
    }
}
