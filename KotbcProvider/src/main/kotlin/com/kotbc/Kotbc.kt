package com.kotbc

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class Kotbc : MainAPI() {
    companion object {
        var currentMainUrl = "https://m142.kotbc2.com" 
        var isDomainChecked = false 
        private val domainMutex = Mutex()
        private const val PREFS_NAME = "Kotbc_Domain_Cache"
        private const val PREF_KEY = "current_domain"
    }

    override var mainUrl = currentMainUrl
    override var name = "KOTBC"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.AsianDrama)

    override val mainPage = mainPageOf("movie" to "영화", "drama" to "드라마", "enter" to "예능/시사", "mid" to "해외드라마")

    private suspend fun checkAndUpdateDomain() {
        if (isDomainChecked) return
        domainMutex.withLock {
            if (isDomainChecked) return@withLock
            val prefs = AcraApplication.context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val cachedDomain = prefs?.getString(PREF_KEY, null)

            if (cachedDomain != null && currentMainUrl == "https://m142.kotbc2.com") {
                currentMainUrl = cachedDomain; mainUrl = currentMainUrl
            }
            
            try {
                val res = app.get(currentMainUrl, timeout = 3L)
                val finalUrl = res.url.trimEnd('/')
                if (res.isSuccessful && res.text.contains("list-box")) {
                    if (currentMainUrl != finalUrl) {
                        currentMainUrl = finalUrl; mainUrl = currentMainUrl
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply()
                    }
                    isDomainChecked = true; return@withLock
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }

            try {
                val redirectRes = app.get("https://kotbc2.com", timeout = 3L)
                val baseFinalUrl = redirectRes.url.trimEnd('/')
                if (redirectRes.isSuccessful && baseFinalUrl.contains("kotbc2.com") && redirectRes.text.contains("list-box")) {
                    currentMainUrl = baseFinalUrl; mainUrl = currentMainUrl; isDomainChecked = true
                    prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply(); return@withLock
                }
            } catch (e: Exception) { if (e is CancellationException) throw e }

            val match = Regex("m(\\d+)").find(currentMainUrl)
            val startNum = match?.groupValues?.get(1)?.toIntOrNull() ?: 142
            
            // [공통 개선] 5개씩 병렬로 빠르게 스캔 (최대 150초 로딩 방지)
            coroutineScope {
                val candidates = (startNum..startNum + 100).map { "https://m$it.kotbc2.com" }
                val chunks = candidates.chunked(10)
                
                for (chunk in chunks) {
                    val deferreds = chunk.map { testUrl ->
                        async {
                            try {
                                val res = app.get(testUrl, timeout = 3L)
                                if (res.isSuccessful && res.text.contains("list-box")) return@async res.url.trimEnd('/')
                            } catch (e: Exception) { if (e is CancellationException) throw e }
                            null
                        }
                    }
                    val found = deferreds.awaitAll().firstOrNull { it != null }
                    if (found != null) {
                        currentMainUrl = found; mainUrl = currentMainUrl; isDomainChecked = true
                        prefs?.edit()?.putString(PREF_KEY, currentMainUrl)?.apply(); return@coroutineScope
                    }
                }
            }
            isDomainChecked = true 
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        checkAndUpdateDomain()
        return try {
            val doc = app.get("$mainUrl/bbs/board.php?bo_table=${request.data}&page=$page").document
            val list = doc.select(".list-body .list-row .list-box").mapNotNull { it.toSearchResponse() }
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        checkAndUpdateDomain()
        return try {
            app.get("$mainUrl/bbs/search.php?sfl=wr_subject&stx=$query").document
                .select(".list-body .list-row .list-box").mapNotNull { it.toSearchResponse() }
        } catch (e: Exception) {
            if (e is CancellationException) throw e; emptyList()
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        try {
            val linkEl = this.selectFirst(".list-front a") ?: this.selectFirst("a")
            val href = linkEl?.attr("href") ?: return null
            val title = (this.selectFirst(".post-title")?.text() ?: linkEl.text()).trim().replace(Regex("\\s*\\(\\d{4}\\)$"), "").trim()
            val posterUrl = (this.selectFirst(".img-item img") ?: this.selectFirst("img"))?.attr("src")?.let { resolvePosterUrl(it) }
            val type = if (href.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries

            return if (type == TvType.Movie) newMovieSearchResponse(title, fixUrl(href), TvType.Movie) { this.posterUrl = posterUrl }
                   else newTvSeriesSearchResponse(title, fixUrl(href), TvType.TvSeries) { this.posterUrl = posterUrl }
        } catch (e: Exception) { return null }
    }

    override suspend fun load(url: String): LoadResponse {
        checkAndUpdateDomain()
        var targetUrl = url
        if (!targetUrl.startsWith(mainUrl)) targetUrl = mainUrl + targetUrl.replace(Regex("https?://[^/]+"), "")
        
        try {
            val doc = app.get(targetUrl).document
            val title = doc.selectFirst(".view-title h1")?.text()?.trim()?.replace(Regex("\\s*\\(\\d{4}\\)$"), "") ?: "Unknown"
            val poster = doc.selectFirst(".view-info .image img")?.attr("src")?.let { resolvePosterUrl(it) }
            val description = doc.selectFirst(".view-cont")?.text()?.trim()
            
            val tags = doc.select(".view-info p").mapNotNull { p ->
                val label = p.selectFirst("span.block:first-child")?.text()?.trim() ?: return@mapNotNull null
                var value = p.selectFirst("span.block:last-child")?.text()?.trim() ?: return@mapNotNull null
                if (label == "제목") return@mapNotNull null
                if (label == "개요") value = value.replace(Regex("\\s*(한국|해외)?영화\\s*\\(\\d{4}\\).*"), "").trim()
                if (value.isNotEmpty()) "${if (label == "개요") "장르" else label}: $value" else null
            }

            val episodes = mutableListOf<Episode>()
            val episodeItems = doc.select(".serial-list .list-body .list-item")
            
            if (episodeItems.isNotEmpty()) {
                episodeItems.forEach { item ->
                    val linkEl = item.selectFirst("a.item-subject")
                    val epHref = linkEl?.attr("href")
                    if (!epHref.isNullOrEmpty()) episodes.add(newEpisode(fixUrl(epHref)) {
                        this.name = linkEl.text().trim(); this.episode = Regex("(\\d+)[화회부]").find(this.name!!)?.groupValues?.get(1)?.toIntOrNull(); this.posterUrl = poster
                    })
                }
            } else episodes.add(newEpisode(targetUrl) { this.name = title; this.posterUrl = poster })

            val type = if (targetUrl.contains("bo_table=movie")) TvType.Movie else TvType.TvSeries
            return if (type == TvType.Movie) newMovieLoadResponse(title, targetUrl, TvType.Movie, episodes.firstOrNull()?.data ?: targetUrl) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
            } else newTvSeriesLoadResponse(title, targetUrl, TvType.TvSeries, episodes.sortedBy { it.episode ?: 0 }) {
                this.posterUrl = poster; this.plot = description; this.tags = tags
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e; throw e
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        try {
            val doc = app.get(data).document
            val form = doc.selectFirst("form.tt2") ?: doc.selectFirst("form.tt")
            if (form != null) {
                val action = form.attr("action")
                val vParam = form.selectFirst("input[name=v]")?.attr("value")
                if (action.isNotEmpty() && !vParam.isNullOrEmpty()) {
                    KotbcExtractor().getUrl("$action?v=$vParam", mainUrl, subtitleCallback, callback)
                    return true
                }
            }
            // [고유 개선] Iframe 범용성 확보 (nnmo0oi1, glamov 하드코딩 탈피)
            doc.select("iframe").forEach { iframe ->
                val src = iframe.attr("src")
                if (src.contains(Regex("""player|embed|view|video|nnmo|glamov"""))) {
                     KotbcExtractor().getUrl(fixUrl(src), mainUrl, subtitleCallback, callback)
                }
            }
        } catch (e: Exception) { 
            if (e is CancellationException) throw e
        }
        return true
    }

    private fun fixUrl(url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        val baseUrl = "$mainUrl/bbs/"
        if (url.startsWith("./")) return baseUrl + url.substring(2)
        if (url.startsWith("/")) return mainUrl + url
        return baseUrl + url
    }
    private fun resolvePosterUrl(url: String) = if (url.startsWith("http")) url else if (url.startsWith("/")) mainUrl + url else "$mainUrl/bbs/$url"
}
