package com.tvwiki

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

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
            // 메인 페이지 리스트 ID 변경: #list_type
            val list = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
            
            newHomePageResponse(request.name, list, hasNext = list.isNotEmpty())
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val aTag = this.selectFirst("a.img") ?: return null
        val link = fixUrl(aTag.attr("href"))
        
        // 메인페이지는 .title2, 검색페이지는 .title 사용
        val title = this.selectFirst("a.title")?.text()?.trim() 
            ?: this.selectFirst("a.title2")?.text()?.trim() 
            ?: return null

        val imgTag = aTag.selectFirst("img")
        val poster = imgTag?.attr("data-original")?.ifEmpty { null }
            ?: imgTag?.attr("data-src")?.ifEmpty { null }
            ?: imgTag?.attr("src")
            ?: ""

        val type = determineTypeFromUrl(link)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> newMovieSearchResponse(
                title,
                link,
                type
            ) { this.posterUrl = fixUrl(poster) }

            TvType.Anime -> newAnimeSearchResponse(
                title,
                link,
                TvType.Anime
            ) { this.posterUrl = fixUrl(poster) }

            else -> newTvSeriesSearchResponse(
                title,
                link,
                TvType.TvSeries
            ) { this.posterUrl = fixUrl(poster) }
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
        
        // 검색 결과 리스트 ID: mov_con_list
        var items = doc.select("ul#mov_con_list li").mapNotNull { it.toSearchResponse() }
        if (items.isEmpty()) {
             // Fallback
             items = doc.select("#list_type ul li").mapNotNull { it.toSearchResponse() }
        }
        return items
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = commonHeaders).document

        // 제목 파싱 (#bo_v_movinfo h3)
        val h3Element = doc.selectFirst("#bo_v_movinfo h3")
        var title = h3Element?.ownText()?.trim()
        val oriTitleFull = h3Element?.selectFirst(".ori_title")?.text()?.trim()

        if (title.isNullOrEmpty()) {
            title = doc.selectFirst("#bo_v_movinfo h3")?.text()?.trim()
                ?: doc.selectFirst("input[name='con_title']")?.attr("value")?.trim()
                ?: "Unknown"
        }
        // 제목 정리 (회차 정보 제거)
        title = title!!.replace(
            Regex("\\\\s*\\\\d+[화회부].*"),
            ""
        ).replace(" 다시보기", "").trim()

        if (!oriTitleFull.isNullOrEmpty()) {
            val pureOriTitle = oriTitleFull.replace("원제 :", "").replace("원제:", "").trim()
            val hasKorean = pureOriTitle.contains(Regex("[가-힣]"))
            if (!hasKorean && pureOriTitle.isNotEmpty()) {
                title = "$title (원제 : $pureOriTitle)"
            }
        }

        // 포스터
        val poster = doc.selectFirst("#bo_v_poster img")?.attr("src")
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")
            ?: ""

        // 정보 (국가, 언어, 개봉년도 등)
        val infoList = doc.select(".bo_v_info dd").map { it.text().trim().replace("개봉년도:", "공개일:") }
        
        // 장르 (.tags dd a)
        val genreList = doc.select(".tags dd a").filter {
            val txt = it.text()
            !txt.contains("트레일러") && !it.hasClass("btn_watch")
        }.map { it.text().trim() }

        val genreFormatted = if (genreList.isNotEmpty()) {
            "장르: ${genreList.joinToString(", ")}"
        } else {
            ""
        }

        // 출연진 파싱
        val castList = doc.select(".slider_act .item .name").map { it.text().trim() }
        val castFormatted = if (castList.isNotEmpty()) {
            "출연진: ${castList.joinToString(", ")}"
        } else {
            ""
        }

        val metaParts = mutableListOf<String>()
        if (infoList.isNotEmpty()) {
            metaParts.add(infoList.joinToString(" / "))
        }
        if (genreFormatted.isNotEmpty()) {
            metaParts.add(genreFormatted)
        }
        if (castFormatted.isNotEmpty()) {
            metaParts.add(castFormatted)
        }
        val metaString = metaParts.joinToString(" / ")

        // 줄거리 (#bo_v_con)
        var story = doc.selectFirst("#bo_v_con")?.text()?.trim()
            ?: doc.selectFirst(".story")?.text()?.trim() // Fallback
            ?: doc.selectFirst("meta[name='description']")?.attr("content")
            ?: ""

        if (story.contains("다시보기") && story.contains("무료")) story = "다시보기"
        if (story.isEmpty()) story = "다시보기"

        val finalPlot = if (story == "다시보기") {
                "다시보기"
        } else {
                if (metaString.isNullOrBlank()) {
                        "줄거리: $story".trim()
                } else {
                        "$metaString / 줄거리: $story".trim()
                }
        }
        
        // 에피소드 리스트 (#other_list ul li)
        val episodes = doc.select("#other_list ul li").mapNotNull { li ->
            val aTag = li.selectFirst("a.ep-link") ?: return@mapNotNull null
            val href = fixUrl(aTag.attr("href"))

            val epName = li.selectFirst("a.title")?.text()?.trim()
                ?: "Episode"

            val thumbImg = li.selectFirst("a.img img")
            val epThumb = thumbImg?.attr("data-src")?.ifEmpty { null }
                ?: thumbImg?.attr("data-original")?.ifEmpty { null }
                ?: thumbImg?.attr("src")?.ifEmpty { null }
                ?: li.selectFirst("img")?.attr("src")

            newEpisode(href) {
                this.name = epName
                this.posterUrl = fixUrl(epThumb ?: "")
            }
        }.reversed() // 에피소드 순서 반전 (1화부터 나오게)

        val type = determineTypeFromUrl(url)

        return when (type) {
            TvType.Movie, TvType.AnimeMovie -> {
                val movieLink = episodes.firstOrNull()?.data ?: url
                newMovieLoadResponse(title, url, type, movieLink) {
                    this.posterUrl = fixUrl(poster)
                    this.plot = finalPlot
                }
            }

            else -> {
                newTvSeriesLoadResponse(title, url, type, episodes) {
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
        println("[TVWiki] loadLinks 시작 - data: $data")
        
        val doc = app.get(data, headers = commonHeaders).document
        println("[TVWiki] 페이지 로드 완료")

        // 1. iframe 요소 찾기
        val iframe = doc.selectFirst("iframe#view_iframe")
        if (iframe != null) {
            println("[TVWiki] iframe#view_iframe 발견!")
            println("[TVWiki] iframe 전체 HTML: ${iframe.outerHtml().take(500)}...")
            
            // iframe의 모든 속성 출력 (디버깅용)
            for (attr in iframe.attributes()) {
                println("[TVWiki]   ${attr.key} = ${attr.value.take(100)}")
            }
            
            // 2. data-session1 또는 data-session2 속성 가져오기
            val sessionDataString = iframe.attr("data-session1").ifEmpty { 
                iframe.attr("data-session2") 
            }
            
            println("[TVWiki] sessionDataString 길이: ${sessionDataString.length}")
            println("[TVWiki] sessionDataString 처음 200자: ${sessionDataString.take(200)}")
            
            if (sessionDataString.isNotEmpty()) {
                try {
                    // 3. session 데이터를 사용하여 플레이어 URL 얻기
                    val sessionUrl = "$mainUrl/api/create_session.php"
                    println("[TVWiki] 세션 생성 API 호출: $sessionUrl")
                    
                    // 헤더를 명시적으로 Map<String, String>으로 변환
                    val postHeaders = mapOf(
                        "User-Agent" to USER_AGENT,
                        "Content-Type" to "application/json",
                        "Referer" to data,
                        "Origin" to mainUrl,
                        "Accept" to "application/json, text/plain, */*",
                        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                    
                    val sessionResponse = app.post(
                        url = sessionUrl,
                        headers = postHeaders,
                        data = sessionDataString
                    )
                    
                    println("[TVWiki] 세션 API 응답 코드: ${sessionResponse.code}")
                    println("[TVWiki] 세션 API 응답 본문: ${sessionResponse.text.take(500)}")
                    
                    // JSON 응답 파싱 - 간단한 정규식으로 파싱
                    val responseText = sessionResponse.text
                    
                    // success 값 추출
                    val successMatch = Regex("\"success\"\\s*:\\s*(true|false)").find(responseText)
                    val success = successMatch?.groups?.get(1)?.value == "true"
                    
                    // player_url 값 추출
                    val playerUrlMatch = Regex("\"player_url\"\\s*:\\s*\"([^\"]+)\"").find(responseText)
                    val playerUrl = playerUrlMatch?.groups?.get(1)?.value
                    
                    // sig 값 추출
                    val sigMatch = Regex("\"sig\"\\s*:\\s*\"([^\"]*)\"").find(responseText)
                    val sig = sigMatch?.groups?.get(1)?.value
                    
                    // t 값 추출
                    val tMatch = Regex("\"t\"\\s*:\\s*\"([^\"]*)\"").find(responseText)
                    val t = tMatch?.groups?.get(1)?.value
                    
                    println("[TVWiki] API 응답 파싱: success=$success, playerUrl=$playerUrl, sig=$sig, t=$t")
                    
                    if (success && playerUrl != null && playerUrl.isNotEmpty()) {
                        val finalPlayerUrl = if (sig != null && sig.isNotEmpty() && t != null && t.isNotEmpty()) {
                            "$playerUrl?t=$t&sig=$sig"
                        } else {
                            playerUrl
                        }
                        
                        println("[TVWiki] 최종 플레이어 URL 생성: $finalPlayerUrl")
                        
                        // 4. BunnyPoorCdn 추출기 호출
                        val extracted = BunnyPoorCdn().extract(
                            finalPlayerUrl,
                            data,
                            subtitleCallback,
                            callback,
                            null
                        )
                        println("[TVWiki] BunnyPoorCdn.extract 결과: $extracted")
                        
                        if (extracted) {
                            println("[TVWiki] 성공적으로 링크 추출됨")
                            return true
                        }
                    } else {
                        println("[TVWiki] API 응답이 실패하거나 player_url이 없음")
                    }
                } catch (e: Exception) {
                    println("[TVWiki] 세션 API 호출 중 오류: ${e.message}")
                    e.printStackTrace()
                }
            } else {
                println("[TVWiki] data-session1과 data-session2가 모두 비어있음")
            }
            
            // 5. 대체 방법: iframe의 src 속성 사용 (동적으로 설정되기 전의 기본 URL)
            val playerUrl = iframe.attr("src")
            println("[TVWiki] iframe src 속성: $playerUrl")
            
            if (playerUrl.isNotEmpty()) {
                val finalPlayerUrl = fixUrl(playerUrl).replace("&amp;", "&")
                println("[TVWiki] src로부터 플레이어 URL 생성: $finalPlayerUrl")
                
                val extracted = BunnyPoorCdn().extract(
                    finalPlayerUrl,
                    data,
                    subtitleCallback,
                    callback,
                    null
                )
                println("[TVWiki] BunnyPoorCdn.extract 결과: $extracted")
                
                if (extracted) {
                    println("[TVWiki] 성공적으로 링크 추출됨")
                    return true
                }
            }
        } else {
            println("[TVWiki] iframe#view_iframe을 찾을 수 없음")
        }

        // 6. 썸네일 힌트 방법 (백업)
        println("[TVWiki] 기본 방법 실패, 썸네일 힌트 시도")
        val thumbnailHint = extractThumbnailHint(doc)
        println("[TVWiki] 썸네일 힌트 추출: ${thumbnailHint ?: "없음"}")

        if (thumbnailHint != null) {
            println("[TVWiki] 썸네일 힌트로 링크 생성 시도: $thumbnailHint")
            try {
                val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")
                val pathMatch = pathRegex.find(thumbnailHint)
                println("[TVWiki] pathMatch 결과: ${pathMatch?.value}")

                if (pathMatch != null) {
                    val m3u8Url =
                        thumbnailHint.substringBefore(pathMatch.value) + pathMatch.value + "/index.m3u8"
                    val fixedM3u8Url = m3u8Url.replace(Regex("//v/"), "/v/")
                    
                    println("[TVWiki] 생성된 m3u8 URL: $fixedM3u8Url")

                    callback(
                        newExtractorLink(name, name, fixedM3u8Url, ExtractorLinkType.M3U8) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = commonHeaders
                        }
                    )
                    println("[TVWiki] callback 호출 완료")
                    return true
                }
            } catch (e: Exception) {
                println("[TVWiki] 썸네일 힌트 처리 중 오류: ${e.message}")
                e.printStackTrace()
            }
        }

        println("[TVWiki] loadLinks 실패 - 링크를 찾을 수 없음")
        return false
    }

    private fun extractThumbnailHint(doc: Document): String? {
        println("[TVWiki] extractThumbnailHint 시작")
        val videoThumbElements = doc.select("img[src*='/v/'], img[data-src*='/v/']")
        println("[TVWiki] /v/ 패턴을 가진 이미지 요소 수: ${videoThumbElements.size}")
        
        val priorityRegex = Regex("""/v/[a-z]/""")

        for (el in videoThumbElements) {
            val raw = el.attr("src").ifEmpty { el.attr("data-src") }
            val fixed = fixUrl(raw)
            println("[TVWiki] 검사 중 - raw: $raw, fixed: $fixed")
            
            if (priorityRegex.containsMatchIn(fixed)) {
                println("[TVWiki] 적합한 썸네일 힌트 발견: $fixed")
                return fixed
            }
        }
        
        println("[TVWiki] 적합한 썸네일 힌트 없음")
        return null
    }
}
