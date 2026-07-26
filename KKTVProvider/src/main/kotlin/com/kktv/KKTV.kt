package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.fasterxml.jackson.annotation.JsonProperty

// 중계 API JSON 응답 모델 (사이트 API 구조에 맞춰 매핑)
data class MatchData(
    @JsonProperty("id") val id: String?,
    @JsonProperty("league_name") val leagueName: String?,
    @JsonProperty("home_team") val homeTeam: String?,
    @JsonProperty("away_team") val awayTeam: String?,
    @JsonProperty("status") val status: String?,
    @JsonProperty("stream_url") val streamUrl: String?
)

class KingKongTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    // 실제 중계 목록 및 라이브스코어가 로드되는 iframe Base URL
    private val liveFrameUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"

    override var name = "킹콩티비"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    /**
     * 1. 실제 스포츠 중계 경기 목록 파싱 (게시글 X, 경기 목록 O)
     */
    override suspend fun getMainPage(page: Int, request: ProviderData): HomePageResponse {
        val homeList = mutableListOf<HomePageList>()
        val items = mutableListOf<SearchResponse>()

        try {
            // iframe 페이지 요청 (Referer 헤더 필수)
            val response = app.get(
                liveFrameUrl,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            )

            val text = response.text

            // API가 JSON 배열 형태 데이터를 반환하는 경우
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
                // HTML DOM으로 중계 경기 엘리먼트를 노출하는 경우
                val doc = response.document
                // 경기 항목 셀렉터 파싱 (iframe 내부 중계 목록 DOM 기준)
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

        return newHomePageResponse(homeList, hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, ProviderData(""))
        val allMatches = homeResponse.items.flatMap { it.list }
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("스포츠 실시간 중계", url, TvType.Live, url) {
            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
        }
    }

    /**
     * 2. kcdn.ehfkdowl.com 등 m3u8 라이브 스트림 URL 추출
     */
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // 이미 직렬 m3u8 URL인 경우
            if (data.contains(".m3u8")) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Live",
                        url = data,
                        referer = liveFrameUrl,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }

            // 플레이어 페이지 요청
            val playerPageText = app.get(
                data,
                headers = mapOf(
                    "Referer" to liveFrameUrl,
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                )
            ).text

            // kcdn m3u8 (chunklist.m3u8 등) 정규식 추출
            // 예시: https://kcdn.ehfkdowl.com/bigwinsoft/143/chunklist.m3u8?t=1785049200&ds=48214
            val m3u8Regex = Regex("""https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""")
            val matchUrl = m3u8Regex.find(playerPageText)?.value

            if (matchUrl != null) {
                callback(
                    ExtractorLink(
                        source = name,
                        name = "$name Live HD",
                        url = matchUrl,
                        referer = data,
                        quality = Qualities.Unknown.value,
                        isM3u8 = true
                    )
                )
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return false
    }
}
