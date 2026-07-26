package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.readValue
import org.jsoup.Jsoup

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    // CUTV 예시처럼 request.data 부분에 실제 접근할 URL Path를 넣습니다.
    override val mainPage = mainPageOf(
        "/live/sportstv" to "스포츠중계A",
        "/live/sportstv2" to "스포츠중계B"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

    // 🚨 파싱 실패 시 빈 화면(먹통)이 되는 것을 방지하기 위한 강제 에러 출력 함수
    private fun getNoMatchesResponse(requestName: String, msg: String = "중계 중인 경기가 없습니다"): HomePageResponse {
        println("[KKTV DEBUG] getNoMatchesResponse 호출됨! 사유: $msg")
        val errorItem = newLiveSearchResponse(name = msg, url = mainUrl) {
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem))),
            hasNext = false
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[KKTV DEBUG] =========================================")
        println("[KKTV DEBUG] getMainPage 시작 | request.name: ${request.name} | request.data: ${request.data}")
        
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()

        try {
            println("[KKTV DEBUG] 1. 메인 URL 접속 시도: $url")
            val response = app.get(url, headers = mapOf("User-Agent" to userAgent))
            val doc = response.document
            
            // 본문에서 iframe 추출
            val iframeSrc = doc.select("iframe#broadcastFrame").attr("src").ifEmpty {
                doc.select("iframe").firstOrNull()?.attr("src") ?: ""
            }
            println("[KKTV DEBUG] 2. 추출된 iframeSrc: $iframeSrc")

            if (iframeSrc.isBlank()) {
                println("[KKTV DEBUG] iframe src가 비어있습니다. 파싱 중단.")
                return getNoMatchesResponse(request.name, "방송 프레임을 찾을 수 없습니다")
            }

            val targetIframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "$mainUrl$iframeSrc"
            println("[KKTV DEBUG] 3. iframe URL 접속 시도: $targetIframeUrl")

            val iframeResponse = app.get(targetIframeUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to url))
            val iframeText = iframeResponse.text
            println("[KKTV DEBUG] 4. iframe 응답 텍스트 길이: ${iframeText.length}")
            
            if (iframeText.length > 200) {
                println("[KKTV DEBUG] iframe 텍스트 미리보기: ${iframeText.substring(0, 200).replace("\n", "")}")
            }

            // JSON 응답인지 HTML 응답인지 판별
            if (iframeText.trim().startsWith("[") || iframeText.trim().startsWith("{")) {
                println("[KKTV DEBUG] 5. JSON 형태의 응답 감지됨")
                try {
                    val matches = mapper.readValue<List<Map<String, String>>>(iframeText)
                    println("[KKTV DEBUG] JSON 파싱 성공, 아이템 수: ${matches.size}")
                    
                    matches.forEach { match ->
                        val league = match["league_name"] ?: "스포츠"
                        val home = match["home_team"] ?: "홈팀"
                        val away = match["away_team"] ?: "원정팀"
                        val title = "[$league] $home vs $away"
                        val id = match["id"] ?: ""
                        val streamUrl = match["stream_url"] ?: "$targetIframeUrl?id=$id"

                        items.add(
                            newLiveSearchResponse(name = title, url = streamUrl) {
                                this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
                            }
                        )
                    }
                } catch (e: Exception) {
                    println("[KKTV DEBUG] JSON 파싱 에러 발생: ${e.message}")
                }
            } else {
                println("[KKTV DEBUG] 5. HTML 형태의 응답 감지됨, DOM 파싱 시도")
                val iframeDoc = Jsoup.parse(iframeText)
                
                // 범용적인 테이블, 리스트 클래스를 싹 다 긁어옵니다.
                val elements = iframeDoc.select(".match_list_item, tr.game_item, div[data-game-id], li.live, tr")
                println("[KKTV DEBUG] DOM에서 추출된 경기 요소 후보군 수: ${elements.size}")

                elements.forEachIndexed { index, element ->
                    val league = element.select(".league, .comp, td:eq(0)").text().trim()
                    val home = element.select(".home_team, .home, td:eq(1)").text().trim()
                    val away = element.select(".away_team, .away, td:eq(2)").text().trim()
                    val gameId = element.attr("data-game-id").ifEmpty {
                        element.select("a").attr("href")
                    }

                    if (home.isNotEmpty() && away.isNotEmpty() && home != away) {
                        val title = if (league.isNotEmpty()) "[$league] $home vs $away" else "$home vs $away"
                        val playUrl = if (gameId.startsWith("http")) gameId else "$targetIframeUrl?id=$gameId"
                        
                        items.add(
                            newLiveSearchResponse(name = title, url = playUrl) {
                                this.posterUrl = "$mainUrl/assets/Pc/img/hd_logo.png"
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            println("[KKTV DEBUG] Exception 발생: ${e.message}")
            e.printStackTrace()
            return getNoMatchesResponse(request.name, "네트워크 및 파싱 오류 발생")
        }

        println("[KKTV DEBUG] 6. 파싱 종료. 최종 추출된 아이템 수: ${items.size}")
        
        if (items.isEmpty()) {
            println("[KKTV DEBUG] 아이템이 0개이므로 NoMatchesResponse를 반환합니다.")
            return getNoMatchesResponse(request.name)
        }

        println("[KKTV DEBUG] 7. 정상적으로 HomePageResponse를 반환합니다.")
        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[KKTV DEBUG] search 호출됨 | query: $query")
        val homeResponse = getMainPage(1, MainPageRequest("/live/sportstv", "스포츠중계A", false))
        val allMatches = homeResponse.items.flatMap { it.list }
        return allMatches.filter { it.name.contains(query, ignoreCase = true) }
    }

    override suspend fun load(url: String): LoadResponse {
        println("[KKTV DEBUG] load 호출됨 | url: $url")
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
        println("[KKTV DEBUG] loadLinks 호출됨 | data: $data")
        try {
            if (data.contains(".m3u8")) {
                println("[KKTV DEBUG] URL에 .m3u8이 직접 포함되어 있음")
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "${this.name} Live",
                        url = data,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.headers = mapOf("User-Agent" to userAgent)
                    }
                )
                return true
            }

            println("[KKTV DEBUG] 플레이어 텍스트 추출 시도...")
            val playerPageText = app.get(
                data,
                headers = mapOf(
                    "Referer" to mainUrl,
                    "User-Agent" to userAgent
                )
            ).text

            println("[KKTV DEBUG] 플레이어 페이지 응답 길이: ${playerPageText.length}")

            val m3u8Regex = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex()
            val matchUrl = m3u8Regex.find(playerPageText)?.value

            if (matchUrl != null) {
                println("[KKTV DEBUG] Regex를 통해 m3u8 링크 획득 성공: $matchUrl")
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
            } else {
                println("[KKTV DEBUG] Regex에서 m3u8을 찾지 못함!")
            }
        } catch (e: Exception) {
            println("[KKTV DEBUG] loadLinks Exception 발생: ${e.message}")
            e.printStackTrace()
            return false
        }
        return false
    }
}
