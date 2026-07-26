package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    private val liveFrameUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"

    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // 앱 홈 화면에 노출될 카탈로그 정의 (CUTVProvider와 동일한 방식)
    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    private val defaultHeaders: Map<String, String> get() {
        return mapOf(
            "User-Agent" to userAgent,
            "Referer" to mainUrl
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()

        try {
            val response = app.get(liveFrameUrl, headers = defaultHeaders)
            val text = response.text

            if (text.trim().startsWith("[") || text.trim().startsWith("{")) {
                // 예시 코드의 Jackson mapper.readValue 완벽 적용 (별도 데이터 클래스 불필요)
                val matches = mapper.readValue<List<Map<String, String>>>(text)
                
                matches.forEach { match ->
                    val league = match["league_name"] ?: "스포츠"
                    val home = match["home_team"] ?: "홈팀"
                    val away = match["away_team"] ?: "원정팀"
                    val title = "[$league] $home vs $away"
                    
                    val id = match["id"] ?: ""
                    val streamUrl = match["stream_url"] ?: "$liveFrameUrl?id=$id"

                    // 예시 코드의 네임드 파라미터(named parameter) 적용
                    items.add(
                        newLiveSearchResponse(name = title, url = streamUrl) {
                            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
                        }
                    )
                }
            } else {
                val doc = response.document
                doc.select(".match_list_item, tr.game_item, div[data-game-id]").forEach { element ->
                    val league = element.select(".league, .comp").text().trim()
                    val home = element.select(".home_team, .home").text().trim()
                    val away = element.select(".away_team, .away").text().trim()
                    val gameId = element.attr("data-game-id").ifEmpty {
                        element.select("a").attr("href")
                    }

                    if (home.isNotEmpty() && away.isNotEmpty()) {
                        val title = if (league.isNotEmpty()) "[$league] $home vs $away" else "$home vs $away"
                        val playUrl = if (gameId.startsWith("http")) gameId else "$liveFrameUrl?id=$gameId"

                        items.add(
                            newLiveSearchResponse(name = title, url = playUrl) {
                                this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 예시 코드의 newHomePageResponse 안전한 리턴 방식 적용
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest("live_matches", "main", false))
        val allMatches = homeResponse.items.flatMap { it.list }
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        // 예시 코드의 newLiveStreamLoadResponse 파라미터 적용
        return newLiveStreamLoadResponse(
            name = "스포츠 실시간 중계",
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
            this.plot = "실시간 스포츠 중계"
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            if (data.contains(".m3u8")) {
                // 예시 코드의 newExtractorLink 네임드 파라미터 방식 완벽 적용
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live",
                        url = data,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = liveFrameUrl
                        this.headers = mapOf("User-Agent" to userAgent)
                    }
                )
                return true
            }

            val playerPageText = app.get(
                data,
                headers = mapOf(
                    "Referer" to liveFrameUrl,
                    "User-Agent" to userAgent
                )
            ).text

            val m3u8Regex = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex()
            val matchUrl = m3u8Regex.find(playerPageText)?.value

            if (matchUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live HD",
                        url = matchUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.headers = mapOf("User-Agent" to userAgent)
                    }
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
        return false
    }
}
