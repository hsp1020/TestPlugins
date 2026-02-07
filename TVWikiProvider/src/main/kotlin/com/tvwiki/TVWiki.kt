package com.tvwiki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

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
            ?: return null

        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val fixedPoster = fixUrl(poster)

        // [변경 1] URL에 포스터 주소를 인코딩해서 붙임
        // 나중에 load() 함수에서 이 값을 백업으로 사용합니다.
        if (fixedPoster.isNotEmpty()) {
            try {
                val encodedPoster = URLEncoder.encode(fixedPoster, "UTF-8")
                // 기존 링크에 파라미터가 있는지 확인 후 연결자 결정
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

            TvType.Anime -> newAnimeSearchResponse(
                title,
                link,
                TvType.Anime
            ) { this.posterUrl = fixedPoster }

            else -> newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) { this.posterUrl = fixedPoster }
        }
    }

    private fun determineTypeFromUrl(url: String): TvType {
        // 파라미터가 붙어도 포함 여부 확인에는 문제 없음
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
        // [변경 2] URL에서 백업용 포스터 추출 및 URL 정리
        var passedPoster: String? = null
        var realUrl = url

        try {
            // cw_poster 파라미터 찾기
            val regex = Regex("[?&]cw_poster=([^&]+)")
            val match = regex.find(url)
            if (match != null) {
                val encoded = match.groupValues[1]
                passedPoster = URLDecoder.decode(encoded, "UTF-8")
                
                // 실제 요청할 URL에서는 파라미터 제거 (서버 에러 방지)
                realUrl = url.replace(match.value, "")
                // 만약 ?로 시작하는 파라미터를 지워서 URL이 깨졌다면(ex: id=1&... 에서 앞이 날아감) 조정 필요하지만
                // 여기선 우리가 맨 뒤에 붙였으므로 replace로 충분하거나, 안전하게 split 사용
                if (realUrl.endsWith("?") || realUrl.endsWith("&")) {
                    realUrl = realUrl.dropLast(1)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 실제 페이지 요청은 깨끗한 URL로 수행
        val doc = app.get(realUrl, headers = commonHeaders).document

        // 제목 파싱
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim()
                ?: "Unknown"
        }
        title = title!!.replace(Regex("\\\\\\\\s*\\\\\\\\d+[화회부].*"), "").replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        // [변경 3] 포스터 결정 로직 개선
        // 1순위: 상세페이지 내 #bo_v_poster
        // 2순위: 메타태그 og:image
        // 3순위: 목록에서 가져온 passedPoster (진입 전 포스터)
        var poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
        
        if (poster.isNullOrEmpty() && passedPoster != null) {
            poster = passedPoster
        }
        
        poster = poster ?: "" // Null safe

        // 정보 (국가, 언어, 개봉년도 등)
        val infoList = doc.select(".bo_v_info dd").map { it.text().trim().replace("개봉년도:", "공개일:") }
        
        // 장르
        val genreList = doc.select(".tags dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim() }

        val genreFormatted = if (genreList.isNotEmpty()) "장르: ${genreList.joinToString(", ")}" else ""

        // 출연진
        val castList = doc.select(".slider_act .item .name").map { it.text().trim() }
        val castFormatted = if (castList.isNotEmpty()) "출연진: ${castList.joinToString(", ")}" else ""

        val metaParts = mutableListOf<String>()
        if (infoList.isNotEmpty()) metaParts.add(infoList.joinToString(" / "))
        if (genreFormatted.isNotEmpty()) metaParts.add(genreFormatted)
        if (castFormatted.isNotEmpty()) metaParts.add(castFormatted)
        val metaString = metaParts.joinToString(" / ")

        // 줄거리
        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim()
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val finalPlot = if (story == "다시보기") {
                "다시보기"
        } else {
                if (metaString.isNullOrBlank()) "줄거리: $story".trim()
                else "$metaString / 줄거리: $story".trim()
        }
        
        // 에피소드 리스트
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
        }.reversed()

        val type = determineTypeFromUrl(realUrl) // 실제 URL 기준 타입 판별

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: realUrl
                newMovieLoadResponse(title, realUrl, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = finalPlot
                }
            }

            else -> {
                newTvSeriesLoadResponse(title, realUrl, type, episodes) {
                    this.posterUrl = fixUrl(poster)
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
        // 기존 코드 유지 (URL 파싱 부분은 이미 load()에서 처리되어 data에는 순수 URL만 넘어옴)
        // 하지만 혹시 loadLinks가 직접 호출될 경우를 대비해 파라미터 제거 로직 추가 가능
        // 보통은 필요 없음
        
        println("[TVWiki] loadLinks 시작 - data: $data")
        val doc = app.get(data, headers = commonHeaders).document
        
        // ... (이하 기존 loadLinks 로직 동일) ...
        
        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            val playerUrl = iframe.attr("src")
            if (playerUrl.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) return true
            }
             val playerUrl1 = iframe.attr("data-player1")
             if (playerUrl1.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl1).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) return true
            }
            val playerUrl2 = iframe.attr("data-player2")
            if (playerUrl2.contains("player.bunny-frame.online")) {
                 val extracted = BunnyPoorCdn().extract(fixUrl(playerUrl2).replace("&amp;", "&"), data, subtitleCallback, callback, null)
                 if(extracted) return true
            }
        }

        val scriptTags = doc.select("script")
        for (script in scriptTags) {
            val scriptContent = script.html()
            if (scriptContent.contains("player.bunny-frame.online")) {
                val urlRegex = Regex("https://player\\\\.bunny-frame\\\\.online/[^\\"'\\\\s]+")
                val match = urlRegex.find(scriptContent)
                if (match != null) {
                    val foundUrl = match.value.replace("&amp;", "&")
                    if(BunnyPoorCdn().extract(foundUrl, data, subtitleCallback, callback, null)) return true
                }
            }
        }

        val thumbnailHint = extractThumbnailHint(doc)
        if (thumbnailHint != null) {
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                if (pathMatch != null) {
                    val m3u8Url = thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")
                    callback(
                        newExtractorLink(name, name, fixedM3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = commonHeaders
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
