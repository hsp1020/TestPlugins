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

    // 빈 화면(먹통) 방지 및 UI 화면에 직접 에러 사유를 띄워버리는 함수
    private fun getNoMatchesResponse(requestName: String, msg: String): HomePageResponse {
        println("[KKTV DEBUG] ❌ 에러/빈화면 반환 사유: $msg")
        val errorItem = newLiveSearchResponse(name = msg, url = mainUrl) {
            // 🚨 요청하신 대로 에러 아이템의 썸네일은 빈 값으로 비웁니다.
            this.posterUrl = "" 
        }
        return newHomePageResponse(
            list = listOf(HomePageList(requestName, listOf(errorItem), isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        println("[KKTV DEBUG] =========================================")
        println("[KKTV DEBUG] 1. getMainPage 시작 | request.data: ${request.data}")
        
        val items = mutableListOf<SearchResponse>()
        val iframeUrl = "https://fkwdsvop.com/KingKong/livescore/livescore/"
        val apiUrl = "https://fkwdsvop.com/ajax/getLivescoreListV3"

        try {
            println("[KKTV DEBUG] 2. Iframe HTML 로드 및 토큰 추출 시도...")
            val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            val tokenRegex = Regex("""auToken\s*[:=]\s*['"]([^'"]+)['"]""")
            val targetIdRegex = Regex("""c_targetId\s*[:=]\s*['"]?(\d+)['"]?"")
            
            val dynamicToken = tokenRegex.find(iframeHtml)?.groupValues?.get(1) 
                ?: "3113b57e00d91c434ae2d3062c0723f3a5c1640ad062845e9f0ab5056d83574794045fad9947198d6a6e33fa44852bdd2b6ebabc84ae436d762c56bae8341624456b001f393b5c4ca8e235c402e86f48ad75cb6b5e4d51c37f458dbb55fc3f5b5d37ec2574af1a0180aee522b902ce96"
            val dynamicTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1) ?: "469"

            println("[KKTV DEBUG] 3. 추출된 Token: $dynamicToken | TargetId: $dynamicTargetId")

            val postData = mapOf(
                "sportsType" to "all",
                "status" to "",
                "c_targetId" to dynamicTargetId,
                "auToken" to dynamicToken
            )

            val postHeaders = mapOf(
                "Accept" to "application/json, text/javascript, */*; q=0.01",
                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                "Origin" to "https://fkwdsvop.com",
                "Referer" to iframeUrl,
                "X-Requested-With" to "XMLHttpRequest",
                "User-Agent" to userAgent
            )

            println("[KKTV DEBUG] 4. API POST 통신 시도: $apiUrl")
            val response = app.post(apiUrl, headers = postHeaders, data = postData)

            println("[KKTV DEBUG] 5. HTTP 응답 코드: ${response.code}")
            if (response.code != 200) {
                return getNoMatchesResponse(request.name, "API 통신 에러 (HTTP ${response.code})")
            }

            val responseText = response.text
            println("[KKTV DEBUG] 6. API 원본 응답 텍스트 길이: ${responseText.length}")
            if (responseText.length > 300) {
                println("[KKTV DEBUG] 7. API 응답 미리보기: ${responseText.substring(0, 300)}")
            } else {
                println("[KKTV DEBUG] 7. API 응답 전체: $responseText")
            }

            if (!responseText.trim().startsWith("{") && !responseText.trim().startsWith("[")) {
                return getNoMatchesResponse(request.name, "잘못된 응답 데이터 (JSON이 아님)")
            }

            println("[KKTV DEBUG] 8. JSON 파싱 시작")
            val jsonTree = mapper.readTree(responseText)
            val dataArray = jsonTree.get("data")

            if (dataArray != null && dataArray.isArray) {
                println("[KKTV DEBUG] 9. JSON 배열 내 전체 경기 개수: ${dataArray.size()}")
                
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
                        
                        val posterUrlFromJson = if (thumbnail.isNotBlank() && thumbnail.startsWith("//")) {
                            "https:$thumbnail"
                        } else {
                            "$mainUrl/assets/Pc/img/hd_logo.png"
                        }

                        val channelId = thumbnail.substringAfterLast("/")
                        val tParam = (System.currentTimeMillis() / 1000) + 86400
                        val m3u8Url = "https://kcdn.ehfkdowl.com/bigwinsoft/$channelId/chunklist.m3u8?t=$tParam&ds=$seq"

                        items.add(
                            newLiveSearchResponse(name = displayTitle, url = m3u8Url) {
                                this.posterUrl = posterUrlFromJson
                            }
                        )
                    }
                }
                println("[KKTV DEBUG] 10. 최종 필터링된 'ING(진행중)' 경기 개수: ${items.size}")
            } else {
                println("[KKTV DEBUG] ❌ JSON 내 'data' 배열을 찾을 수 없습니다.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("[KKTV DEBUG] ❌ Exception 발생: ${e.message}")
            return getNoMatchesResponse(request.name, "파싱 중 치명적 오류 발생")
        }

        if (items.isEmpty()) {
            println("[KKTV DEBUG] ❌ 최종 카탈로그에 담을 아이템이 0개입니다.")
            return getNoMatchesResponse(request.name, "현재 진행중인 중계가 없습니다")
        }

        println("[KKTV DEBUG] ✅ 카탈로그 렌더링 성공! 반환 준비 완료.")
        return newHomePageResponse(
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
            println("[KKTV DEBUG] loadLinks 호출됨 | data(url): $data")
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
            println("[KKTV DEBUG] loadLinks 에러: ${e.message}")
            e.printStackTrace()
        }
        return false
    }
}
