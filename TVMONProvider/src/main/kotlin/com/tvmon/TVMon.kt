package com.tvmon

import android.content.Context
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TVMon : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvmon1.com" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "TVMon_Domain_Cache"
        private const val PREF_KEY = "current_domain"

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[TVMon DBG] 영구 도메인 저장 완료: $newDomain")
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "TVMON"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama, TvType.Anime, TvType.AnimeMovie)

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    private val commonHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Referer" to "$mainUrl/",
            "Upgrade-Insecure-Requests" to "1"
        )

    private val tagCleanRegex = Regex("""\s*(한국|해외)?영화\s*\(\d{4}\).*""")
    private val requestMutex = Mutex()

    data class SessionResponse(@JsonProperty("success") val success: Boolean, @JsonProperty("player_url") val playerUrl: String?, @JsonProperty("t") val t: String?, @JsonProperty("sig") val sig: String?)

    override val mainPage = mainPageOf("/kor_movie" to "영화", "/drama" to "드라마", "/ent" to "예능", "/sisa" to "시사/다큐", "/movie" to "해외영화", "/world" to "해외드라마", "/animation" to "애니메이션", "/ani_movie" to "극장판 애니", "/old_drama" to "추억의 드라마", "/old_ent" to "추억의 예능")

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)?.replace("http://", "https://")
            
            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
            }

            val testPath = "/drama"
            
            try {
                val res = app.get("$currentMainUrl$testPath", headers = commonHeaders, timeout = 3L)
                val extractedUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: currentMainUrl
                val finalUrl = extractedUrl.replace("http://", "https://")
                
                if (res.isSuccessful && (res.text.contains("mov_list") || res.text.contains("item"))) {
                    if (currentMainUrl != finalUrl) {
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {}

            val match = Regex("tvmon(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
            
            coroutineScope {
                val candidates = (startNum..startNum + 100).map { "https://tvmon$it.com" }
                val chunks = candidates.chunked(5) 
                
                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            val testUrl = "$testDomain$testPath"
                            try {
                                val res = app.get(testUrl, headers = commonHeaders, timeout = 3L)
                                val extractedTestUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: testDomain
                                val testFinalUrl = extractedTestUrl.replace("http://", "https://")
                                
                                if (res.isSuccessful && (res.text.contains("mov_list") || res.text.contains("item"))) {
                                    return@async testFinalUrl
                                }
                            } catch (e: Exception) {}
                            null
                        }
                    }
                    val foundDomain = deferreds.awaitAll().firstOrNull { it != null }
                    if (foundDomain != null) {
                        saveDomain(foundDomain)
                        mainUrl = currentMainUrl
                        isDomainChecked = true
                        return@coroutineScope
                    }
                }
            }
            isDomainChecked = true 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!tvmon ")) {
            val userDomain = query.substring(7).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ TVMON 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain()
        val searchUrl = "$mainUrl/search?stx=$query"
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) items = doc.select(".mov_list ul li, .item").mapNotNull { it.toSearchResponse() }
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e; emptyList()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl${request.data}?page=$page"
        return try {
            delay((200..800).random().toLong()) 
            val doc = requestMutex.withLock {
                app.get(url, headers = commonHeaders).document
            }
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
        val aTag = this.selectFirst("a.img, a.poster") ?: return null
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

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl) && targetUrl.contains("tvmon")) {
            val path = targetUrl.replace(Regex("https?://[^/]+"), "")
            targetUrl = mainUrl + path
        }

        var passedPoster: String? = null; var realUrl = targetUrl
        try {
            val match = Regex("[?&]cw_poster=([^&]+)").find(targetUrl)
            if (match != null) { passedPoster = URLDecoder.decode(match.groupValues[1], "UTF-8"); realUrl = targetUrl.replace(match.value, "").trimEnd('?', '&') }
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
        checkAndUpdateDomain()
        
        var targetData = data
        if (!targetData.startsWith(mainUrl) && targetData.contains("tvmon")) {
            val path = targetData.replace(Regex("https?://[^/]+"), "")
            targetData = mainUrl + path
        }

        val doc = try { app.get(targetData, headers = commonHeaders).document } catch (e: Exception) { if (e is CancellationException) throw e; return false }

        if (doc.text().contains("삭제된 영상") || doc.text().contains("존재하지 않는") || doc.text().contains("404")) return false

        if (extractFromApi(doc, targetData, subtitleCallback, callback)) return true

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("data-player1").ifEmpty { iframe.attr("data-player2") }.ifEmpty { iframe.attr("src") }
            if (playerUrl.contains("player.bunny-frame.online")) if(BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)) return true
        }

        try {
            val webViewDoc = app.get(targetData, headers = commonHeaders, interceptor = WebViewResolver(Regex("bunny-frame|googleapis"), timeout = 15000L)).document
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                if (playerUrl.contains("player.bunny-frame.online")) if(BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)) return true
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
