package com.kktv

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

// JSON 응답을 매핑할 데이터 클래스
data class MatchData(
    @JsonProperty("id") val id: String?,
    @JsonProperty("league_name") val leagueName: String?,
    @JsonProperty("home_team") val homeTeam: String?,
    @JsonProperty("away_team") val awayTeam: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("stream_url") val streamUrl: String?
)

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    private val liveFrameUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"

    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        val homeList = mutableListOf<HomePageList>()
        val items = mutableListOf<SearchResponse>()

        try {
            val response = app.get(
                liveFrameUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )

            val text = response.text

            if (text.trim().startsWith("[") || text.trim().startsWith("{")) {
                val matches = parseJson<List<MatchData>>(text)
                matches.forEach { match ->
                    val league = match.leagueName ?: "스포츠"
                    val home = match.homeTeam ?: "홈팀"
                    val away = match.awayTeam ?: "원정팀"
                    val title = "[$league] $home vs $away"
                    
                    val playUrl = match.streamUrl ?: "$liveFrameUrl?id=${match.id}"

                    items.add(
                        newLiveSearchResponse(title, playUrl, TvType.Live) {
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
                            newLiveSearchResponse(title, playUrl, TvType.Live) {
                                this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
                            }
                        )
                    }
                }
            }

            if (items.isNotEmpty()) {
                homeList.add(HomePageList("실시간 스포츠 중계 목록", items))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (homeList.isEmpty()) return null
        return newHomePageResponse(homeList, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest("main", "", false))
        val allMatches = homeResponse?.items?.flatMap { it.list } ?: emptyList()
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        val title = "스포츠 실시간 중계"
        return newLiveStreamLoadResponse(title, url, url) {
            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
        }
    }

    /**
     * 빌드 에러 수정: CloudStream 3 최신 사양인 newExtractorLink + 람다 초기화 방식 적용
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // URL 자체가 바로 .m3u8 일 경우
            if (data.contains(".m3u8")) {
                callback.invoke(
                    newExtractorLink(
                        name,               // source
                        "$name Live",       // name
                        url = data,         // url
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = liveFrameUrl
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

            // HTML 페이지 내에서 Regex로 .m3u8 혹은 chunklist.m3u8 추출할 경우
            val playerPageText = app.get(
                data,
                headers = mapOf(
                    "Referer" to liveFrameUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            ).text

            val m3u8Regex = Regex("""https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val matchUrl = m3u8Regex.find(playerPageText)?.value

            if (matchUrl != null) {
                callback.invoke(
                    newExtractorLink(
                        name,                 // source
                        "$name Live HD",      // name
                        url = matchUrl,       // url
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = data
                        this.quality = Qualities.Unknown.value
                        // this.headers = mapOf(...) 필요시 추가 가능
                    }
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
