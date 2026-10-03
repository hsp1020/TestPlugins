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

class Kotbc : MainAPI() {
    companion object {
        private const val DEFAULT_DOMAIN = "https://k01.kotbc2.com"

        // 사이트 구조가 mXX -> kXX로 완전히 변경되었으므로
        // 구버전 SharedPreferences(m200 등)를 다시 읽지 않도록 캐시 네임스페이스를 분리한다.
        private const val PREFS_NAME = "Kotbc_Domain_Cache_v2"
        private const val PREF_KEY = "current_domain"

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

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()

        val targetUrl = normalizeTargetUrl(url)
        println("[Kotbc DBG] 🎬 상세페이지 로드: $targetUrl")

        val doc = app.get(targetUrl).document

        val title = doc.selectFirst("h1")
            ?.text()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: doc.selectFirst("meta[property=og:title]")
                ?.attr("content")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: "Unknown"

        /*
         * 상세정보 카드(section)만 잡는다.
         * 사이드바의 인기 작품/최근 업데이트를 상세정보로 오인하지 않도록 h1을 가진 section으로 제한.
         */
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

        /*
         * 예:
         * 국내드라마 · 2026-09-10 · 총 10화 · 방영중
         * 한국영화 · 2026-08-17 · 108분
         */
        val summary = infoSection
            ?.select("p")
            ?.map { it.text().trim() }
            ?.firstOrNull { text ->
                text.contains(" · ") &&
                    (
                        text.contains("영화") ||
                            text.contains("드라마") ||
                            text.contains("총 ")
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
                val text = a.text().trim()

                if (href.startsWith("/?tag=") && text.startsWith("#")) {
                    text.removePrefix("#").trim().takeIf { it.isNotEmpty() }
                } else {
                    null
                }
            }
            .orEmpty()

        val tags = (genreTags + metadataTags).distinct()

        /*
         * 회차 링크는 반드시 "회차 목록" section 내부에서만 가져온다.
         * 이렇게 해야 오른쪽 "최근 업데이트"에 있는 타 작품 회차가 섞이지 않는다.
         */
        val episodeSection = doc.select("section")
            .firstOrNull { section ->
                section.select("span")
                    .any { span -> span.text().trim() == "회차 목록" }
            }

        val episodes = episodeSection
            ?.select("ul[data-infinite-scroll-page] a[href]")
            ?.mapNotNull { link ->
                val href = link.attr("href")
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val episodeName = link.text()
                    .trim()
                    .takeIf { it.isNotEmpty() }
                    ?: return@mapNotNull null

                val episodeNumber = Regex("""(\d+)\s*화""")
                    .find(episodeName)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

                newEpisode(resolveUrl(href)) {
                    this.name = episodeName
                    this.episode = episodeNumber
                    this.posterUrl = poster
                }
            }
            .orEmpty()
            .distinctBy { it.data }

        println(
            "[Kotbc DBG] 🎬 상세 파싱 완료: " +
                "title=$title, movie=$isMovie, episodes=${episodes.size}, summary=$summary"
        )

        return if (isMovie) {
            val playData = episodes.firstOrNull()?.data ?: targetUrl

            newMovieLoadResponse(
                title,
                targetUrl,
                TvType.Movie,
                playData
            ) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            val sortedEpisodes = episodes.sortedWith(
                compareBy<Episode> { it.episode ?: Int.MAX_VALUE }
                    .thenBy { it.name.orEmpty() }
            )

            val finalEpisodes = if (sortedEpisodes.isNotEmpty()) {
                sortedEpisodes
            } else {
                listOf(
                    newEpisode(targetUrl) {
                        this.name = title
                        this.posterUrl = poster
                    }
                )
            }

            newTvSeriesLoadResponse(
                title,
                targetUrl,
                TvType.TvSeries,
                finalEpisodes
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
        val targetUrl = normalizeTargetUrl(data)

        println("[Kotbc DBG] 🔗 영상 링크 추출 시작: $targetUrl")

        try {
            val response = app.get(targetUrl)
            val doc = response.document

            /*
             * 1) 과거 KOTBC 폼 구조도 fallback으로 유지.
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

                    println("[Kotbc DBG] 🔗 구형 form player 감지 -> $playerUrl")

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
             * 2) 현재/향후 SSR에 iframe이 직접 노출되는 경우.
             */
            val iframeUrls = doc.select("iframe[src]")
                .mapNotNull { iframe ->
                    iframe.attr("src")
                        .trim()
                        .takeIf { it.isNotEmpty() }
                        ?.let { resolveUrl(it) }
                }
                .distinct()

            if (iframeUrls.isNotEmpty()) {
                iframeUrls.forEach { playerUrl ->
                    println("[Kotbc DBG] 🔗 iframe player 감지 -> $playerUrl")

                    KotbcExtractor().getUrl(
                        playerUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

                return true
            }

            /*
             * 3) 신규 Inertia 페이지의 JSON 안에
             *    "body":{"url":"https://...","iframe":true,"type":"video"}
             * 형태로 영상 주소가 들어오는 경우를 탐지한다.
             *
             * HTML JSON의 \/ 이스케이프를 먼저 정상 URL로 복원한다.
             */
            val normalizedHtml = response.text.replace("\\/", "/")

            val bodyUrls = Regex(
                """(?s)"body"\s*:\s*\{[^{}]{0,1500}?"url"\s*:\s*"(https?://[^"]+)"""
            )
                .findAll(normalizedHtml)
                .mapNotNull { match ->
                    match.groupValues
                        .getOrNull(1)
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                }
                .filter { url ->
                    !url.contains("kotbc2.com", ignoreCase = true)
                }
                .distinct()
                .toList()

            if (bodyUrls.isNotEmpty()) {
                /*
                 * /video/, /embed/ 계열을 우선하고 나머지는 뒤에 둔다.
                 * 현재 첨부 메인 데이터에서는 nnmo.../video/... 형태가 확인된다.
                 */
                val ordered = bodyUrls.sortedByDescending { url ->
                    url.contains("/video/", ignoreCase = true) ||
                        url.contains("/embed/", ignoreCase = true)
                }

                ordered.forEach { playerUrl ->
                    println("[Kotbc DBG] 🔗 Inertia body player 감지 -> $playerUrl")

                    KotbcExtractor().getUrl(
                        playerUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

                return true
            }

            /*
             * 4) data-url / data-src 형태 fallback.
             */
            val dataUrls = doc
                .select("[data-url], [data-src]")
                .flatMap { el ->
                    listOf(
                        el.attr("data-url"),
                        el.attr("data-src")
                    )
                }
                .map { it.trim() }
                .filter { it.startsWith("http") || it.startsWith("//") }
                .map { resolveUrl(it) }
                .filter { url ->
                    url.contains("/video/", ignoreCase = true) ||
                        url.contains("/embed/", ignoreCase = true) ||
                        url.contains("player", ignoreCase = true)
                }
                .distinct()

            if (dataUrls.isNotEmpty()) {
                dataUrls.forEach { playerUrl ->
                    println("[Kotbc DBG] 🔗 data-* player 감지 -> $playerUrl")

                    KotbcExtractor().getUrl(
                        playerUrl,
                        mainUrl,
                        subtitleCallback,
                        callback
                    )
                }

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
        if (url.isBlank()) return mainUrl

        if (url.startsWith(mainUrl)) {
            return url
        }

        if (url.startsWith("http://") || url.startsWith("https://")) {
            return mainUrl + url.replace(Regex("""https?://[^/]+"""), "")
        }

        return resolveUrl(url)
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
