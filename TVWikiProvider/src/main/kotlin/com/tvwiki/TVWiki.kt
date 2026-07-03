package com.tvwiki

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

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class TVWiki : MainAPI() {
    companion object {
        var currentMainUrl = "https://tvwiki35.net" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "TVWiki_Domain_Cache"
        private const val PREF_KEY = "current_domain"

        fun saveDomain(newDomain: String) {
            currentMainUrl = newDomain
            AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()?.putString(PREF_KEY, newDomain)?.apply()
            println("[TVWiki DBG] 💾 영구 도메인 저장 완료: $newDomain")
        }
    }

    override var mainUrl = currentMainUrl
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

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

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

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)?.replace("http://", "https://")
            
            if (cachedDomain != null) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
                println("[TVWiki DBG] 📂 캐시된 도메인 로드: $currentMainUrl")
            }

            val testPath = "/popular"
            println("[TVWiki DBG] 🔄 기본 도메인 유효성 체크 -> $currentMainUrl$testPath")
            
            try {
                val res = app.get("$currentMainUrl$testPath", headers = commonHeaders, timeout = 5L)
                val extractedUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: currentMainUrl
                val finalUrl = extractedUrl.replace("http://", "https://")
                val hasSignature = res.text.contains("list_type")
                
                if (res.isSuccessful && hasSignature) {
                    if (currentMainUrl != finalUrl) {
                        println("[TVWiki DBG] 🔄 리다이렉트 감지됨: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }
                    isDomainChecked = true
                    println("[TVWiki DBG] ✅ 기존 도메인 활성 상태 확정 -> $mainUrl")
                    return@withLock
                } else {
                    println("[TVWiki DBG] ⚠️ 기존 도메인 유효성 상실 (응답성공: ${res.isSuccessful}, 시그니처매칭: $hasSignature)")
                }
            } catch (e: Exception) { println("[TVWiki DBG] ❌ 현재 도메인 접속 실패: ${e.message}") }

            val match = Regex("tvwiki(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 35
            println("[TVWiki DBG] 🚀 브루트포스 탐색 시작 (시작번호: $startNum)")
            
            var foundDomain: String? = null
            coroutineScope {
                val candidates = (startNum..startNum + 200).map { "https://tvwiki$it.net" }
                val chunks = candidates.chunked(5) 
                
                for (chunk in chunks) {
                    val deferreds = chunk.map { testDomain ->
                        async {
                            val testUrl = "$testDomain$testPath"
                            println("[TVWiki DBG] 🔄 후보 도메인 검증 시도 -> $testUrl")
                            try {
                                val res = app.get(testUrl, headers = commonHeaders, timeout = 3L)
                                val extractedTestUrl = Regex("^https?://[^/]+").find(res.url)?.value ?: testDomain
                                val testFinalUrl = extractedTestUrl.replace("http://", "https://")
                                
                                if (res.isSuccessful && res.text.contains("list_type")) {
                                    return@async testFinalUrl
                                }
                            } catch (e: Exception) {}
                            null
                        }
                    }
                    
                    foundDomain = deferreds.awaitAll().firstOrNull { it != null }
                    if (foundDomain != null) {
                        break
                    }
                }
            }

            if (foundDomain != null) {
                println("[TVWiki DBG] ✨ 브루트포스 성공: $foundDomain")
                saveDomain(foundDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }

            println("[TVWiki DBG] 💀 탐색 범주($startNum ~ ${startNum + 100}) 내 모든 접속 실패. 수동 검토가 필요합니다.")
            isDomainChecked = true 
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()
        if (qLower.startsWith("!tvwiki ")) {
            val userDomain = query.substring(8).trim().trimEnd('/')
            if (userDomain.startsWith("http")) {
                println("[TVWiki DBG] 🔧 백도어 도메인 수동 갱신: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return listOf(newMovieSearchResponse("✅ TVWiki 갱신 성공: $userDomain", userDomain, TvType.Movie) { this.posterUrl = "" })
            }
        } else if (query.startsWith("!")) { return emptyList() }

        checkAndUpdateDomain() 
        val searchUrl = "$mainUrl/search?stx=$query"
        println("[TVWiki DBG] 🔍 검색 요청: $query")
        return try {
            val doc = app.get(searchUrl, headers = commonHeaders).document
            var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
            if (items.isEmpty()) {
                 items = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            }
            println("[TVWiki DBG] 🔍 검색 결과 수: ${items.size}")
            items
        } catch (e: Exception) { 
            if (e is CancellationException) throw e; emptyList() 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        val url = "$mainUrl${request.data}?page=$page"
        println("[TVWiki DBG] 📄 메인페이지 로드: 카테고리=${request.name}, 페이지=$page")
        
        return try {
            val doc = requestMutex.withLock {
                app.get(url, headers = commonHeaders).document
            }
            val list = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            println("[TVWiki DBG] 📄 메인페이지 아이템 수: ${list.size}")
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        var link = fixUrl(aTag.attr("href"))
        
        val title = this.selectFirst("a.title")?.text()?.trim() 
            ?: this.selectFirst("a.title2")?.text()?.trim() 
            ?: return null

        val imgTag = aTag.selectFirst("img")
        var rawPoster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        if (rawPoster.startsWith("/")) {
            rawPoster = "$mainUrl$rawPoster"
        }
        val fixedPoster = fixUrl(rawPoster)

        if (fixedPoster.isNotEmpty() && !fixedPoster.contains("no3.png")) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                val separator = if (link.contains("?")) "&" else "?"
                link = "$link${separator}cw_poster=$encodedPoster"
            } catch (e: Exception) {}
        }

        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(title, link, type) { this.posterUrl = fixedPoster }
            TvType.Anime -> newAnimeSearchResponse(title, link, TvType.Anime) { this.posterUrl = fixedPoster }
            else -> newTvSeriesSearchResponse(title, link, TvType.TvSeries) { this.posterUrl = fixedPoster }
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

    private fun getEpisodeNumber(name: String): Int {
        return try {
            val numberString = name.replace(Regex("[^0-9]"), "")
            if (numberString.isNotEmpty()) numberString.toInt() else Int.MAX_VALUE
        } catch (e: Exception) { Int.MAX_VALUE }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain() 
        println("[TVWiki DBG] 🎬 상세페이지 로드: $url")
        
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl) && targetUrl.contains("tvwiki")) {
            val path = targetUrl.replace(Regex("https?://[^/]+"), "")
            targetUrl = mainUrl + path
        }

        var passedPoster: String? = null
        var realUrl = targetUrl

        try {
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(targetUrl)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                realUrl = targetUrl.replace(match.value, "").trimEnd('?', '&')
            }
        } catch (e: Exception) {}

        val doc = app.get(realUrl, headers = commonHeaders).document

        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim() ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim() ?: "Unknown"
        }
        
        title = title!!.replace(Regex("\\s*\\d+[화회부].*"), "").replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src") ?: doc.selectFirst("meta[property='og:image']")?.attr("content") ?: ""

        val tvwikiMainUrlRegex = Regex("""^https?://tvwiki\d*\.net/?$""")
        val isInvalidPoster = poster.isEmpty() || poster.contains("no3.png") || poster.contains("no_image") || tvwikiMainUrlRegex.matches(poster) || poster.endsWith("/")

        if (isInvalidPoster && passedPoster != null) poster = passedPoster

        val tagsList = mutableListOf<String>()
        doc.select(".bo_v_info dd").forEach { dd ->
            val text = dd.text().trim()
            if (text.isNotEmpty() && !text.startsWith("제목")) tagsList.add(text.replace("개봉년도:", "공개일:").replace(tagCleanRegex, "").trim())
        }

        val genreList = doc.select(".tags dd a").filter { !it.text().contains("트레일러") && !it.hasClass("btn_watch") }.map { it.text().trim().replace(tagCleanRegex, "") }
        if (genreList.isNotEmpty()) tagsList.add("장르: ${genreList.joinToString(", ")}")

        val castList = doc.select(".slider_act .item .name").map { it.text().trim().replace(tagCleanRegex, "") }
        if (castList.isNotEmpty() && castList.none { it.contains("운영팀") }) tagsList.add("출연: ${castList.joinToString(", ")}")

        var story = doc.selectFirst("#bo_v_con")?.text()?.trim() ?: doc.selectFirst(".story")?.text()?.trim() ?: doc.selectFirst("meta[name='description']")?.attr("content") ?: "다시보기"
        if (story.isEmpty() || (story.contains("다시보기") && story.contains("무료"))) story = "다시보기"
        
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))
            val epName = li.selectFirst("a.title")?.text()?.trim() ?: "Episode"
            val thumbImg = li.selectFirst("a.img img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null } ?: thumbImg?.attr("data-original")?.ifEmpty { null } ?: thumbImg?.attr("src")?.ifEmpty { null } ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) { this.name = epName; this.posterUrl = fixUrl(epThumb ?: "") }
        }.sortedBy { getEpisodeNumber(it.name ?: "") }
        
        println("[TVWiki DBG] 🎬 파싱 완료: 에피소드 수=${episodes.size}")

        val type = determineTypeFromUrl(realUrl)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) { this.posterUrl = fixUrl(poster); this.plot = story; this.tags = tagsList }
            }
            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) { this.posterUrl = fixUrl(poster); this.plot = story; this.tags = tagsList }
            }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        checkAndUpdateDomain()
        println("[TVWiki DBG] 🔗 영상 링크 추출 시작: $data")
        
        var targetData = data
        if (!targetData.startsWith(mainUrl) && targetData.contains("tvwiki")) {
            val path = targetData.replace(Regex("https?://[^/]+"), "")
            targetData = mainUrl + path
        }

        val doc = try { app.get(targetData, headers = commonHeaders).document } catch (e: Exception) { if (e is CancellationException) throw e; return false }

        if (extractFromApi(doc, targetData, subtitleCallback, callback)) return true

        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("src")
            if (playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)
                 if(extracted) return true
            }
        }

        try {
            val webViewInterceptor = WebViewResolver(Regex("bunny-frame|googleapis"), timeout = 15000L)
            val response = app.get(targetData, headers = commonHeaders, interceptor = webViewInterceptor)
            val webViewDoc = response.document
            
            val wbIframe = webViewDoc.selectFirst("iframe#view_iframe") ?: webViewDoc.selectFirst("iframe[src*='bunny-frame']")
            if (wbIframe != null) {
                val playerUrl = wbIframe.attr("src")
                if (playerUrl.contains("player.bunny-frame.online")) {
                     val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), targetData, subtitleCallback, callback, null)
                     if(extracted) return true
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }

        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
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
            val iframe = doc.selectFirst("iframe#view_iframe") ?: return false
            val sessionData = iframe.attr("data-session1").ifEmpty { iframe.attr("data-session2") }

            if (sessionData.isNullOrEmpty()) return false

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
                if (fullUrl.contains("player.bunny-frame.online")) {
                    return BunnyPoorCdn().extract(fullUrl, refererUrl, subtitleCallback, callback, null)
                }
            }
        } catch (e: Exception) { if (e is CancellationException) throw e }
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
