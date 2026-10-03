package com.kotbc

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder

class Kotbc : MainAPI() {
    companion object {
        private const val DEFAULT_DOMAIN = "https://k01.kotbc2.com"

        // 사이트 구조가 mXX -> kXX로 완전히 변경되었으므로
        // 구버전 SharedPreferences(m200 등)를 다시 읽지 않도록 캐시 네임스페이스를 분리한다.
        private const val PREFS_NAME = "Kotbc_Domain_Cache_v2"
        private const val PREF_KEY = "current_domain"
        private const val PLAYER_DATA_PREFIX = "KOTBC_PLAYER:"

        var currentMainUrl = DEFAULT_DOMAIN
        var isDomainChecked = false

        private val domainMutex = Mutex()

        fun saveDomain(newDomain: String) {
            val normalized = newDomain.trimEnd('/')
            currentMainUrl = normalized

            CloudStreamApp.context
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.edit()
                ?.putString(PREF_KEY, normalized)
                ?.apply()

            println("[Kotbc DBG] 💾 영구 도메인 저장 완료: $normalized")
        }
    }

    override var mainUrl = currentMainUrl
    override var name = "KOTBC"
    override val hasMainPage = true
    override var lang = "ko"

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.AsianDrama
    )

    /*
     * 현재 KOTBC:
     * /영화
     * /드라마
     * /예능시사
     * /해외드라마
     */
    override val mainPage = mainPageOf(
        "%EC%98%81%ED%99%94" to "영화",
        "%EB%93%9C%EB%9D%BC%EB%A7%88" to "드라마",
        "%EC%98%88%EB%8A%A5%EC%8B%9C%EC%82%AC" to "예능/시사",
        "%ED%95%B4%EC%99%B8%EB%93%9C%EB%9D%BC%EB%A7%88" to "해외드라마"
    )

    /**
     * 구형 list-box 시그니처는 폐기됨.
     * 현재 페이지에 공통으로 존재하는 Inertia data-page와 검색 input을 생존 시그니처로 사용한다.
     */
    private fun isValidSiteDocument(doc: Document): Boolean {
        return doc.selectFirst("script[data-page=app][type=application/json]") != null &&
            (
                doc.selectFirst("input#haru-search-input") != null ||
                    doc.selectFirst("input#haru-search-input-mobile") != null
                )
    }

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return

        domainMutex.withLock {
            if (isDomainChecked) return@withLock

            val prefs = CloudStreamApp.context
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

            val cachedDomain = prefs
                ?.getString(PREF_KEY, null)
                ?.trimEnd('/')

            if (!cachedDomain.isNullOrBlank()) {
                currentMainUrl = cachedDomain
                mainUrl = currentMainUrl
                println("[Kotbc DBG] 📂 캐시된 k도메인 로드: $currentMainUrl")
            } else {
                currentMainUrl = DEFAULT_DOMAIN
                mainUrl = currentMainUrl
            }

            println("[Kotbc DBG] 🔄 현재 도메인 유효성 체크 -> $currentMainUrl")

            try {
                val res = app.get(currentMainUrl, timeout = 3L)
                val finalUrl = res.url.trimEnd('/')
                val valid = res.isSuccessful && isValidSiteDocument(res.document)

                if (valid) {
                    if (finalUrl != currentMainUrl) {
                        println("[Kotbc DBG] 🔄 리다이렉트 감지: $currentMainUrl -> $finalUrl")
                        saveDomain(finalUrl)
                        mainUrl = currentMainUrl
                    }

                    isDomainChecked = true
                    println("[Kotbc DBG] ✅ 활성 도메인 확정 -> $mainUrl")
                    return@withLock
                }

                println(
                    "[Kotbc DBG] ⚠️ 현재 도메인 검증 실패 " +
                        "(HTTP=${res.isSuccessful}, 신규시그니처=$valid)"
                )
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                println("[Kotbc DBG] ❌ 현재 도메인 접속 실패: ${e.message}")
            }

            // 공식 베이스 도메인이 새 kXX 주소로 리다이렉트하는 경우 우선 사용한다.
            try {
                println("[Kotbc DBG] 🌐 베이스 도메인 kotbc2.com 폴백")
                val res = app.get("https://kotbc2.com", timeout = 3L)
                val finalUrl = res.url.trimEnd('/')

                if (res.isSuccessful && isValidSiteDocument(res.document)) {
                    println("[Kotbc DBG] ✅ 베이스 도메인 리다이렉트 성공 -> $finalUrl")
                    saveDomain(finalUrl)
                    mainUrl = currentMainUrl
                    isDomainChecked = true
                    return@withLock
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            }

            val match = Regex("""https?://k(\d+)\.kotbc2\.com""", RegexOption.IGNORE_CASE)
                .find(currentMainUrl)

            val startNum = match
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
                ?: 1

            println(
                "[Kotbc DBG] 🚀 k도메인 탐색 시작: " +
                    "k${startNum.toString().padStart(2, '0')}"
            )

            var foundDomain: String? = null

            coroutineScope {
                val candidates = (startNum..startNum + 200).map { num ->
                    "https://k${num.toString().padStart(2, '0')}.kotbc2.com"
                }

                for (chunk in candidates.chunked(5)) {
                    val results = chunk.map { testUrl ->
                        async {
                            println("[Kotbc DBG] 🔄 후보 검증 -> $testUrl")

                            try {
                                val res = app.get(testUrl, timeout = 3L)

                                if (res.isSuccessful && isValidSiteDocument(res.document)) {
                                    res.url.trimEnd('/')
                                } else {
                                    null
                                }
                            } catch (e: Exception) {
                                if (e is CancellationException) throw e
                                null
                            }
                        }
                    }.awaitAll()

                    foundDomain = results.firstOrNull { it != null }

                    if (foundDomain != null) {
                        return@coroutineScope
                    }
                }
            }

            if (foundDomain != null) {
                println("[Kotbc DBG] ✨ k도메인 탐색 성공: $foundDomain")
                saveDomain(foundDomain!!)
                mainUrl = currentMainUrl
                isDomainChecked = true
                return@withLock
            }

            println(
                "[Kotbc DBG] 💀 탐색 실패: " +
                    "k${startNum.toString().padStart(2, '0')} ~ " +
                    "k${(startNum + 200).toString().padStart(2, '0')}"
            )

            // 무한 브루트포스를 막기 위해 이번 프로세스에서는 재탐색하지 않는다.
            isDomainChecked = true
        }
    }

    /**
     * 현재 사이트에는 구형 /bbs/search.php가 없다.
     *
     * 검색 API는 첨부 소스만으로 확정할 수 없으므로,
     * 홈 SSR에 노출되는 4개 섹션의 최신 작품을 대상으로 안전한 검색 fallback을 제공한다.
     */
    override suspend fun search(query: String): List<SearchResponse> {
        val qLower = query.lowercase()

        if (qLower.startsWith("!kotbc ")) {
            val userDomain = query
                .substring(7)
                .trim()
                .trimEnd('/')

            if (userDomain.startsWith("http")) {
                println("[Kotbc DBG] 🔧 수동 도메인 갱신: $userDomain")
                saveDomain(userDomain)
                mainUrl = currentMainUrl
                isDomainChecked = true

                return listOf(
                    newMovieSearchResponse(
                        "✅ Kotbc 갱신 성공: $userDomain",
                        userDomain,
                        TvType.Movie
                    ) {
                        this.posterUrl = ""
                    }
                )
            }
        } else if (query.startsWith("!")) {
            return emptyList()
        }

        checkAndUpdateDomain()
        println("[Kotbc DBG] 🔍 검색 fallback: $query")

        return try {
            val doc = app.get(mainUrl).document
            val normalizedQuery = query.trim()

            parseHomeSections(doc)
                .filter {
                    it.name.contains(normalizedQuery, ignoreCase = true)
                }
                .distinctBy { it.url }
                .also {
                    println("[Kotbc DBG] 🔍 검색 결과 수: ${it.size}")
                }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[Kotbc DBG] ❌ 검색 실패: ${e.message}")
            emptyList()
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        checkAndUpdateDomain()

        val pageUrl = buildString {
            append(mainUrl)
            append("/")
            append(request.data)

            if (page > 1) {
                append("?page=")
                append(page)
            }
        }

        println(
            "[Kotbc DBG] 📄 메인페이지 로드: " +
                "카테고리=${request.name}, 페이지=$page, url=$pageUrl"
        )

        return try {
            val doc = app.get(pageUrl).document
            val type = if (request.name == "영화") TvType.Movie else TvType.TvSeries

            val list = doc
                .select("a.group.block")
                .mapNotNull { card -> card.toCardSearchResponse(type) }
                .distinctBy { it.url }

            println("[Kotbc DBG] 📄 ${request.name} 아이템 수: ${list.size}")

            newHomePageResponse(
                request.name,
                list,
                hasNext = list.isNotEmpty()
            )
        } catch (e: Exception) {
            if (e is CancellationException) throw e

            println(
                "[Kotbc DBG] ❌ 메인페이지 실패: " +
                    "${request.name} page=$page / ${e.message}"
            )

            newHomePageResponse(
                request.name,
                emptyList(),
                hasNext = false
            )
        }
    }

    /**
     * 루트 홈 페이지의 4개 섹션을 검색 fallback 용도로 파싱한다.
     * 각 section의 h2 텍스트로 영화/시리즈 타입을 구분한다.
     */
    private fun parseHomeSections(doc: Document): List<SearchResponse> {
        return doc.select("section")
            .flatMap { section ->
                val sectionName = section.selectFirst("h2")
                    ?.text()
                    ?.trim()
                    ?: return@flatMap emptyList()

                val type = if (sectionName == "영화") {
                    TvType.Movie
                } else if (
                    sectionName == "드라마" ||
                    sectionName == "예능시사" ||
                    sectionName == "해외드라마"
                ) {
                    TvType.TvSeries
                } else {
                    return@flatMap emptyList()
                }

                section.select("a.group.block")
                    .mapNotNull { card -> card.toCardSearchResponse(type) }
            }
    }

    /**
     * 신규 카드 DOM:
     *
     * <a class="group block" href="/작품-slug">
     *   <article>
     *     <img alt="작품명" src="/storage/...">
     *     ...
     *     <h3 title="작품명">작품명</h3>
     *   </article>
     * </a>
     */
    private fun Element.toCardSearchResponse(type: TvType): SearchResponse? {
        val href = attr("href")
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: return null

        val title = selectFirst("h3[title]")
            ?.attr("title")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: selectFirst("img[alt]")
                ?.attr("alt")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: return null

        val posterUrl = selectFirst("img[alt]")
            ?.attr("src")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?.let { resolveUrl(it) }

        val absoluteUrl = resolveUrl(href)

        return if (type == TvType.Movie) {
            newMovieSearchResponse(
                title,
                absoluteUrl,
                TvType.Movie
            ) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(
                title,
                absoluteUrl,
                TvType.TvSeries
            ) {
                this.posterUrl = posterUrl
            }
        }
    }

    private data class ParsedEpisode(
        val name: String,
        val number: Int?,
        val slug: String?,
        val videoUrl: String?
    )

    /**
     * KOTBC 신규 구조의 진짜 원본 데이터.
     *
     * <script data-page="app" type="application/json">
     *   props.episodes.data[]
     * </script>
     *
     * DOM 회차 목록은 Vue/Inertia 렌더 상태에 따라 app.get()에서 비어 보일 수 있으므로
     * JSON을 1순위로 사용하고 DOM은 fallback으로만 사용한다.
     */
    private fun parseInertiaEpisodes(doc: Document): List<ParsedEpisode> {
        return try {
            val script = doc.selectFirst("script[data-page=app][type=application/json]")
                ?: return emptyList()

            val rawJson = script.data()
                .ifBlank { script.html() }
                .trim()

            if (rawJson.isEmpty()) return emptyList()

            val root = JSONObject(rawJson)
            val props = root.optJSONObject("props") ?: return emptyList()
            val episodesObject = props.optJSONObject("episodes") ?: return emptyList()
            val data = episodesObject.optJSONArray("data") ?: return emptyList()

            buildList {
                for (index in 0 until data.length()) {
                    val item = data.optJSONObject(index) ?: continue

                    val name = item.optString("title")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?: continue

                    val number = item.optInt("sort", -1)
                        .takeIf { it >= 0 }
                        ?: Regex("""(\d+)\s*화""")
                            .find(name)
                            ?.groupValues
                            ?.getOrNull(1)
                            ?.toIntOrNull()

                    val slug = item.optString("slug")
                        .trim()
                        .takeIf { it.isNotEmpty() }

                    val body = item.optJSONObject("body")
                    val videoUrl = body
                        ?.optString("url")
                        ?.trim()
                        ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }

                    add(
                        ParsedEpisode(
                            name = name,
                            number = number,
                            slug = slug,
                            videoUrl = videoUrl
                        )
                    )
                }
            }
        } catch (e: Exception) {
            println("[Kotbc DBG] ⚠️ Inertia episodes JSON 파싱 실패: ${e.message}")
            emptyList()
        }
    }

    /**
     * DOM fallback.
     * 신규 사이트에서 정상 SSR이 내려오면 동작하지만, JSON 파싱보다 우선하지 않는다.
     */
    private fun parseDomEpisodes(doc: Document): List<ParsedEpisode> {
        val episodeSection = doc.select("section")
            .firstOrNull { section ->
                section.select("span")
                    .any { span -> span.text().trim() == "회차 목록" }
            }
            ?: return emptyList()

        return episodeSection
            .select("ul[data-infinite-scroll-page] a[href]")
            .mapNotNull { link ->
                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val episodeName = link.text()
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val number = Regex("""(\d+)\s*화""")
                    .find(episodeName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                // DOM에는 body.url이 없으므로 href를 slug 대용으로 보관한다.
                ParsedEpisode(
                    name = episodeName,
                    number = number,
                    slug = href,
                    videoUrl = null
                )
            }
            .distinctBy { it.slug ?: it.name }
    }

    private fun packPlayerData(playerUrl: String, refererUrl: String): String {
        val payload = JSONObject()
            .put("url", playerUrl)
            .put("referer", refererUrl)
            .toString()

        return PLAYER_DATA_PREFIX + payload
    }

    private fun unpackPlayerData(data: String): Pair<String, String>? {
        if (!data.startsWith(PLAYER_DATA_PREFIX)) return null

        return try {
            val payload = JSONObject(data.removePrefix(PLAYER_DATA_PREFIX))
            val playerUrl = payload.optString("url").trim()
            val refererUrl = payload.optString("referer").trim()

            if (playerUrl.isEmpty()) {
                null
            } else {
                playerUrl to refererUrl.ifEmpty { mainUrl }
            }
        } catch (e: Exception) {
            println("[Kotbc DBG] ⚠️ player data decode 실패: ${e.message}")
            null
        }
    }

    private fun buildEpisodeData(
        targetUrl: String,
        parsed: ParsedEpisode
    ): String? {
        val slug = parsed.slug

        val episodePageUrl = when {
            slug.isNullOrBlank() -> targetUrl
            slug.startsWith("/") -> resolveUrl(slug)
            else -> targetUrl.trimEnd('/') + "/" + encodePathSegment(slug)
        }

        /*
         * video URL만 넘기면 nnmo player가 원래 KOTBC 문서 Referer를 잃는다.
         * player URL + 정확한 회차/영화 페이지 URL을 함께 전달한다.
         */
        parsed.videoUrl?.let { playerUrl ->
            return packPlayerData(playerUrl, episodePageUrl)
        }

        return episodePageUrl
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
    }

    /**
     * 회차 페이지 fallback에서 "현재 회차"의 body.url 하나만 선택한다.
     * 페이지 JSON에는 전체 회차의 body.url이 같이 들어 있으므로 전부 실행하면 안 된다.
     */
    private fun findCurrentEpisodeVideoUrl(doc: Document): String? {
        val parsed = parseInertiaEpisodes(doc)
        if (parsed.isEmpty()) return null

        val pageTitle = doc.selectFirst("meta[property=og:title]")
            ?.attr("content")
            ?.trim()
            .orEmpty()
            .ifBlank {
                doc.selectFirst("h1")
                    ?.text()
                    ?.trim()
                    .orEmpty()
            }

        val currentNumber = Regex("""(\d+)\s*화""")
            .find(pageTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        if (currentNumber != null) {
            parsed.firstOrNull { it.number == currentNumber }
                ?.videoUrl
                ?.let { return it }
        }

        // 영화처럼 회차가 실제로 하나뿐인 경우에만 안전하게 단일 URL 사용.
        if (parsed.size == 1) {
            return parsed.first().videoUrl
        }

        return null
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()

        val targetUrl = normalizeTargetUrl(url)
        println("[Kotbc DBG] 🎬 상세페이지 로드: $targetUrl")

        val response = app.get(targetUrl)
        val doc = response.document

        val title = doc.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        val infoSection = doc.select("section")
            .firstOrNull { section ->
                section.selectFirst("h1") != null
            }

        val poster = infoSection
            ?.select("img[alt]")
            ?.firstOrNull { img ->
                img.attr("alt").trim() == title
            }
            ?.attr("src")
            ?.takeIf { it.isNotBlank() }
            ?.let { resolveUrl(it) }
            ?: doc.selectFirst("meta[property=og:image]")
                ?.attr("content")
                ?.takeIf { it.isNotBlank() }
                ?.let { resolveUrl(it) }

        val description = doc.selectFirst("meta[property=og:description]")
            ?.attr("content")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: infoSection
                ?.select("p")
                ?.firstOrNull { p ->
                    p.classNames().contains("line-clamp-4")
                }
                ?.text()
                ?.trim()

        val summary = infoSection
            ?.select("p")
            ?.map { it.text().trim() }
            ?.firstOrNull { value ->
                value.contains(" · ") &&
                    (
                        value.contains("영화") ||
                            value.contains("드라마") ||
                            value.contains("총 ")
                        )
            }
            .orEmpty()

        val isMovie = summary.contains("영화") && !summary.contains("드라마")

        val metadataTags = infoSection
            ?.select("dl > div")
            ?.mapNotNull { row ->
                val label = row.selectFirst("dt")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                val value = row.selectFirst("dd")
                    ?.text()
                    ?.trim()
                    .orEmpty()

                if (label.isNotEmpty() && value.isNotEmpty()) {
                    "$label: $value"
                } else {
                    null
                }
            }
            .orEmpty()

        val genreTags = infoSection
            ?.select("a[href]")
            ?.mapNotNull { a ->
                val href = a.attr("href")
                val value = a.text().trim()

                if (href.startsWith("/?tag=") && value.startsWith("#")) {
                    value.removePrefix("#")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
            .orEmpty()

        val tags = (genreTags + metadataTags).distinct()

        val inertiaEpisodes = parseInertiaEpisodes(doc)
        val parsedEpisodes = if (inertiaEpisodes.isNotEmpty()) {
            println(
                "[Kotbc DBG] ✅ Inertia 회차 파싱 성공: " +
                    "${inertiaEpisodes.size}개 / videoUrl=" +
                    "${inertiaEpisodes.count { !it.videoUrl.isNullOrBlank() }}개"
            )
            inertiaEpisodes
        } else {
            val domEpisodes = parseDomEpisodes(doc)
            println("[Kotbc DBG] ⚠️ Inertia 회차 0개 -> DOM fallback ${domEpisodes.size}개")
            domEpisodes
        }

        val episodes = parsedEpisodes
            .mapNotNull { parsed ->
                val episodeData = buildEpisodeData(targetUrl, parsed)
                    ?: return@mapNotNull null

                newEpisode(episodeData) {
                    this.name = parsed.name
                    this.episode = parsed.number
                    this.posterUrl = poster
                }
            }
            .distinctBy { it.data }

        println(
            "[Kotbc DBG] 🎬 상세 파싱 완료: " +
                "title=$title, movie=$isMovie, " +
                "parsed=${parsedEpisodes.size}, episodes=${episodes.size}, summary=$summary"
        )

        return if (isMovie) {
            /*
             * 영화도 KOTBC 내부적으로 episodes.data 1개를 사용한다.
             * body.url이 있으면 작품 상세 URL이 아니라 실제 nnmo.../video/...를 data로 넘긴다.
             */
            val playData = episodes.firstOrNull()?.data

            if (playData.isNullOrBlank()) {
                println("[Kotbc DBG] ⚠️ 영화 재생 data를 찾지 못함: $targetUrl")
            } else {
                println("[Kotbc DBG] 🎞️ 영화 직접 재생 data -> $playData")
            }

            newMovieLoadResponse(
                title,
                targetUrl,
                TvType.Movie,
                playData ?: targetUrl
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            /*
             * 기존의 "파싱 실패 시 가짜 1개 episode 생성" 로직 제거.
             * 파싱 실패는 그대로 0개로 남겨야 원인을 숨기지 않는다.
             */
            val sortedEpisodes = episodes.sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() }
            )

            newTvSeriesLoadResponse(
                title,
                targetUrl,
                TvType.TvSeries,
                sortedEpisodes
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val rawData = data.trim()

        if (rawData.isEmpty()) {
            println("[Kotbc DBG] ❌ loadLinks data가 비어 있음")
            return false
        }

        /*
         * 정상 경로: load()가 player URL과 실제 KOTBC 회차/영화 페이지 Referer를
         * 하나의 payload로 묶어 전달한다.
         */
        unpackPlayerData(rawData)?.let { (playerUrl, refererUrl) ->
            println("[Kotbc DBG] 🔗 player payload -> $playerUrl")
            println("[Kotbc DBG] 🔗 player referer -> $refererUrl")

            KotbcExtractor().getUrl(
                playerUrl,
                refererUrl,
                subtitleCallback,
                callback
            )

            return true
        }

        /*
         * 구버전/캐시된 Episode.data 호환.
         */
        if (
            (rawData.startsWith("http://") || rawData.startsWith("https://")) &&
            !rawData.contains("kotbc2.com", ignoreCase = true)
        ) {
            println("[Kotbc DBG] 🔗 구버전 직접 player URL -> $rawData")

            KotbcExtractor().getUrl(
                rawData,
                mainUrl,
                subtitleCallback,
                callback
            )

            return true
        }

        val targetUrl = normalizeTargetUrl(rawData)
        println("[Kotbc DBG] 🔗 KOTBC 페이지 링크 추출 시작: $targetUrl")

        try {
            val response = app.get(targetUrl)
            val doc = response.document

            /*
             * 신규 회차 페이지:
             * 화면에는 "바로 보기" 버튼만 보이지만 Inertia JSON에는
             * 전체 episodes.data가 있으며 현재 회차와 일치하는 body.url을 하나 고를 수 있다.
             */
            val currentVideoUrl = findCurrentEpisodeVideoUrl(doc)

            if (!currentVideoUrl.isNullOrBlank()) {
                println("[Kotbc DBG] ✅ 현재 회차 Inertia player -> $currentVideoUrl")

                KotbcExtractor().getUrl(
                    currentVideoUrl,
                    targetUrl,
                    subtitleCallback,
                    callback
                )

                return true
            }

            /*
             * 실제 동영상 wrapper 페이지처럼 iframe이 SSR로 직접 존재하는 경우.
             */
            val iframeUrl = doc.select("iframe[src]")
                .mapNotNull { iframe ->
                    iframe.attr("src")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { resolveUrl(it) }
                }
                .firstOrNull { url ->
                    url.contains("/video/", ignoreCase = true) ||
                        url.contains("/embed/", ignoreCase = true) ||
                        url.contains("player", ignoreCase = true)
                }

            if (!iframeUrl.isNullOrBlank()) {
                println("[Kotbc DBG] ✅ iframe player -> $iframeUrl")

                KotbcExtractor().getUrl(
                    iframeUrl,
                    mainUrl,
                    subtitleCallback,
                    callback
                )

                return true
            }

            /*
             * 과거 KOTBC form 구조 fallback.
             */
            val legacyForm = doc.selectFirst("form.tt2")
                ?: doc.selectFirst("form.tt")

            if (legacyForm != null) {
                val action = legacyForm.attr("action")
                val vParam = legacyForm
                    .selectFirst("input[name=v]")
                    ?.attr("value")

                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    val playerUrl = resolveUrl(action) + "?v=$vParam"

                    println("[Kotbc DBG] 🔗 구형 form player -> $playerUrl")

                    KotbcExtractor().getUrl(
                        playerUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )

                    return true
                }
            }

            /*
             * data-url / data-src 최후 fallback.
             */
            val dataUrl = doc
                .select("[data-url], [data-src]")
                .flatMap { element ->
                    listOf(
                        element.attr("data-url"),
                        element.attr("data-src")
                    )
                }
                .map { it.trim() }
                .filter { it.startsWith("http") || it.startsWith("//") }
                .map { resolveUrl(it) }
                .firstOrNull { url ->
                    url.contains("/video/", ignoreCase = true) ||
                        url.contains("/embed/", ignoreCase = true) ||
                        url.contains("player", ignoreCase = true)
                }

            if (!dataUrl.isNullOrBlank()) {
                println("[Kotbc DBG] 🔗 data-* player -> $dataUrl")

                KotbcExtractor().getUrl(
                    dataUrl,
                    mainUrl,
                    subtitleCallback,
                    callback
                )

                return true
            }

            println("[Kotbc DBG] ⚠️ 영상 player URL을 찾지 못함: $targetUrl")
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            println("[Kotbc DBG] ❌ 영상 링크 추출 실패: ${e.message}")
        }

        return false
    }

    private fun normalizeTargetUrl(url: String): String {
        val value = url.trim()

        if (value.isEmpty()) return mainUrl

        /*
         * 외부 player URL은 절대 KOTBC host로 변환하지 않는다.
         * 예: https://nnmo0oi1.com/video/... 그대로 유지.
         */
        if (value.startsWith("http://") || value.startsWith("https://")) {
            if (!value.contains("kotbc2.com", ignoreCase = true)) {
                return value
            }

            /*
             * 과거 mXX/kXX KOTBC 절대 URL만 현재 활성 mainUrl로 host 교체.
             */
            val pathAndQuery = value.replace(
                Regex("""https?://[^/]+""", RegexOption.IGNORE_CASE),
                ""
            )

            return mainUrl + pathAndQuery
        }

        return resolveUrl(value)
    }

    /**
     * 신규 사이트는 /bbs/ 기준이 아니라 사이트 root 기준 상대경로를 사용한다.
     */
    private fun resolveUrl(url: String): String {
        val value = url.trim()

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value
        }

        if (value.startsWith("//")) {
            return "https:$value"
        }

        if (value.startsWith("/")) {
            return mainUrl + value
        }

        return "$mainUrl/$value"
    }
}
