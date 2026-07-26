package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // 빈 화면 방지용 함수 (여기서도 가로 썸네일 속성 유지)
    private fun getNoMatchesResponse(requestName: String, msg: String = "진행중인 중계가 없습니다"): HomePageResponse {
        val errorItem = newLiveSearchResponse(name = msg, url = mainUrl) {
            this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
        }
        return newHomePageResponse(
            // 🚨 수정: 에러 메시지도 가로 썸네일 규격에 맞게 노출
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val items = mutableListOf<SearchResponse>()
        val apiUrl = "https://fkwdsvop.com/ajax/getLivescoreListV3"

        try {
            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://fkwdsvop.com",
                "Referer" to "https://fkwdsvop.com/KingKong/livescore/livescore/",
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent
            )

            val response = app.post(
                apiUrl,
                headers = postHeaders,
                data = emptyMap()
            )

            val responseText = response.text
            val jsonTree = mapper.readTree(responseText)
            val dataArray = jsonTree.get("data")

            if (dataArray != null && dataArray.isArray) {
                dataArray.forEach { match ->
                    val states = match.get("states")?.asText() ?: ""
                    
                    if (states == "ING") {
                        val rawTitle = match.get("title")?.asText() ?: ""
                        val cleanTitle = Jsoup.parse(rawTitle).text().ifEmpty {
                            val home = match.get("home_name")?.asText() ?: "홈"
                            val away = match.get("away_name")?.asText() ?: "원정"
                            "$home vs $away"
                        }
                        
                        val league = match.get("leagueName")?.asText() ?: "스포츠"
                        val displayTitle = "[$league] $cleanTitle"

                        val seq = match.get("seq")?.asText() ?: ""
                        val thumbnail = match.get("thumbnail")?.asText() ?: ""
                        
                        // 🚨 수정: JSON에서 받아온 썸네일 주소(//image...)를 완성된 https 주소로 매핑
                        val posterUrlFromJson = if (thumbnail.isNotBlank() && thumbnail.startsWith("//")) {
                            "https:$thumbnail"
                        } else {
                            "$mainUrl/assets/Pc/img/hd_logo.png" // 썸네일 없을 때 기본 로고
                        }

                        val channelId = thumbnail.substringAfterLast("/")
                        val tParam = (System.currentTimeMillis() / 1000) + 86400
                        val m3u8Url = "https://kcdn.ehfkdowl.com/bigwinsoft/$channelId/chunklist.m3u8?t=$tParam&ds=$seq"

                        items.add(
                            newLiveSearchResponse(name = displayTitle, url = m3u8Url) {
                                // 🚨 포스터(썸네일) 세팅
                                this.posterUrl = posterUrlFromJson
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        if (items.isEmpty()) {
            return getNoMatchesResponse(request.name, "진행중인 중계가 없습니다")
        }

        return newHomePageResponse(
            // 🚨 수정: isHorizontalImages = true 속성을 부여하여 썸네일을 가로형(16:9 비율)으로 배치
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val homeResponse = getMainPage(1, MainPageRequest("live_matches", "main", false))
        val allMatches = homeResponse.items.flatMap { it.list }
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        return newLiveStreamLoadResponse("스포츠 실시간 중계", url, url) {
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
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live",
                        url = data,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://fkwdsvop.com/"
                        this.headers = mapOf("User-Agent" to userAgent)
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
