package com.tvwiki

import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody

class TVWiki : MainAPI() {
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
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/",
        "Upgrade-Insecure-Requests" to "1"
    )

    data class SessionResponse(
        val success: Boolean,
        val player_url: String?,
        val t: String?,
        val sig: String?
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
            val list = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            // [수정] newHomePageResponse -> HomePageResponse 생성자 (필요 시)
            // HomePageResponse는 보통 호환성 문제 적음
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        val link = fixUrl(aTag.attr("href"))
        val title = this.selectFirst("a.title")?.text()?.trim() 
            ?: this.selectFirst("a.title2")?.text()?.trim() 
            ?: return null

        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val type = determineTypeFromUrl(link)

        // [수정] new...SearchResponse -> 생성자 직접 사용
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> MovieSearchResponse(
                name = title,
                url = link,
                apiName = this@TVWiki.name,
                type = type,
                posterUrl = fixUrl(poster),
                year = null
            )
            TvType.Anime -> AnimeSearchResponse(
                name = title,
                url = link,
                apiName = this@TVWiki.name,
                type = TvType.Anime,
                posterUrl = fixUrl(poster),
                year = null,
                dubStatus = null
            )
            else -> TvSeriesSearchResponse(
                name = title,
                url = link,
                apiName = this@TVWiki.name,
                type = TvType.TvSeries,
                posterUrl = fixUrl(poster),
                year = null,
                episodes = null
            )
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        return when {
            url.contains("/movie") || url.contains("/kor_movie") -> TvType.Movie
            url.contains("/ani_movie") -> TvType.AnimeMovie
            url.contains("/animation") -> TvType.Anime
            url.contains("/ent") || url.contains("/old_ent") || url.contains("/ott_ent") -> TvType.TvSeries
            else -> TvType.TvSeries
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?stx=$query"
        val doc = app.get(searchUrl, headers = commonHeaders).document
        var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
             items = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim() ?: "Unknown"
        title = title.replace(Regex("\\s*\\d+[화회부].*"), "").replace(" 다시보기", "").trim()

        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim() ?: "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            // [수정] newEpisode -> Episode 생성자
            Episode(
                data = href,
                name = epName,
                posterUrl = null,
                episode = null,
                season = null
            )
        }.reversed()

        val type = determineTypeFromUrl(url)

        // [수정] new...LoadResponse -> 생성자 직접 사용
        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                MovieLoadResponse(
                    name = title,
                    url = url,
                    apiName = name,
                    type = type,
                    dataUrl = movieLink,
                    posterUrl = fixUrl(poster),
                    plot = story
                )
            }
            else -> {
                TvSeriesLoadResponse(
                    name = title,
                    url = url,
                    apiName = name,
                    type = type,
                    episodes = episodes,
                    posterUrl = fixUrl(poster),
                    plot = story
                )
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        Log.d("TVWIKI_DEBUG", "LoadLinks: $data")
        val doc = app.get(data, headers = commonHeaders).document
        val thumbnailHint = extractThumbnailHint(doc)

        val iframe = doc.select("iframe").firstOrNull { 
            val s = it.attr("src")
            s.contains("bunny-frame") || s.contains("/v/") || it.attr("id") == "view_iframe"
        }
        var playerUrl = iframe?.attr("src")?.ifEmpty { null }

        if (playerUrl.isNullOrEmpty() || !playerUrl.contains("bunny-frame")) {
            val sessionJson = iframe?.attr("data-session1")?.ifEmpty { null }
                ?: iframe?.attr("data-session2")?.ifEmpty { null }
            
            if (sessionJson != null) {
                try {
                    val apiUrl = "$mainUrl/api/create_session.php"
                    
                    // [수정] RequestBody 생성
                    val mediaType = "application/json; charset=utf-8".toMediaTypeOrNull()
                    val body = sessionJson.toRequestBody(mediaType)

                    val jsonResp = app.post(
                        apiUrl,
                        headers = commonHeaders + mapOf(
                            "Content-Type" to "application/json",
                            "X-Requested-With" to "XMLHttpRequest"
                        ),
                        requestBody = body // [수정] 이제 타입 일치함
                    ).parsedSafe<SessionResponse>()

                    if (jsonResp?.success == true && jsonResp.player_url != null) {
                        playerUrl = "${jsonResp.player_url}?t=${jsonResp.t}&sig=${jsonResp.sig}"
                    }
                } catch (e: Exception) {
                    Log.e("TVWIKI_DEBUG", "API Error", e)
                }
            }
        }

        if (playerUrl != null && playerUrl.contains("bunny-frame")) {
            val finalUrl = fixUrl(playerUrl).replace("&amp;", "&")
            return BunnyPoorCdn().extract(
                finalUrl,
                data,
                subtitleCallback,
                callback,
                thumbnailHint
            )
        }

        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")
                    callback(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = fixedM3u8Url,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = commonHeaders
                        )
                    )
                    return true
                }
            } catch (e: Exception) {}
        }
        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        val priorityRegex = Regex("""/v/[a-z]/""")
        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw)
            if (priorityRegex.containsMatchIn(fixed)) return fixed
        }
        return null
    }
}
