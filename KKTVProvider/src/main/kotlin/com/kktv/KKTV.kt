package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URLDecoder

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

    private fun getNoMatchesResponse(requestName: String, msg: String): HomePageResponse {
        println("[KKTV DEBUG] ❌ 에러/빈화면 반환 사유: $msg")
        val errorItem = newLiveSearchResponse(name = msg, url = mainUrl) {
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
            println("[KKTV DEBUG] 2. Iframe HTML 로드 시도: $iframeUrl")
            val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            println("[KKTV DEBUG] 3. Iframe HTML 로드 성공 (길이: ${iframeHtml.length})")
            
            // 정규식 정의
            val tokenRegex = Regex("""auToken\s*[:=]\s*['"]([^'"]+)['"]""", RegexOption.IGNORE_CASE)
            val hexRegex = Regex("""['"]([a-fA-F0-9]{200,})['"]""")
            val targetIdRegex = Regex("""c_targetId\s*[:=]\s*['"]?(\d+)['"]?""")
            
            println("[KKTV DEBUG] 4. HTML 내부 토큰/해시값 검색 시작...")
            var dynamicToken = tokenRegex.find(iframeHtml)?.groupValues?.get(1)
            
            if (dynamicToken != null) {
                println("[KKTV DEBUG] ✅ HTML 내부에서 auToken 직접 발견!")
            } else {
                println("[KKTV DEBUG] ⚠️ HTML 내부 auToken 직접 발견 실패. 긴 해시(Hex) 문자열로 재검색...")
                dynamicToken = hexRegex.find(iframeHtml)?.groupValues?.get(1)
                
                if (dynamicToken != null) {
                    println("[KKTV DEBUG] ✅ HTML 내부에서 긴 해시 문자열 발견!")
                } else {
                    println("[KKTV DEBUG] ⚠️ HTML 내부 해시 발견 실패. 외부 JS 스크립트 추적 시작...")
                    val scripts = Jsoup.parse(iframeHtml).select("script[src]")
                    println("[KKTV DEBUG] 🔍 발견된 외부 스크립트 개수: ${scripts.size}개")
                    
                    for (script in scripts) {
                        val src = script.attr("src")
                        if (src.isNotBlank()) {
                            val scriptUrl = if (src.startsWith("http")) src else "https://fkwdsvop.com$src"
                            println("[KKTV DEBUG] 🔍 스크립트 스캔 중: $scriptUrl")
                            try {
                                val scriptText = app.get(scriptUrl, headers = mapOf("Referer" to iframeUrl, "User-Agent" to userAgent)).text
                                
                                var scriptTokenMatch = tokenRegex.find(scriptText)?.groupValues?.get(1)
                                if (scriptTokenMatch == null) {
                                    scriptTokenMatch = hexRegex.find(scriptText)?.groupValues?.get(1)
                                }
                                
                                if (scriptTokenMatch != null) {
                                    dynamicToken = scriptTokenMatch
                                    println("[KKTV DEBUG] ✅ 외부 JS에서 토큰 발견 성공!")
                                    break
                                }
                            } catch (e: Exception) {
                                println("[KKTV DEBUG] ❌ 스크립트 로드 실패: ${e.message}")
                            }
                        }
                    }
                }
            }

            // 🚨 하드코딩 제거: 샅샅이 뒤졌는데도 토큰이 없으면 즉시 파싱을 중단하고 HTML을 덤프합니다.
            if (dynamicToken == null) {
                println("[KKTV DEBUG] ❌ 모든 동적 토큰 추출 실패! 디버깅을 위해 HTML 전체를 덤프합니다.")
                iframeHtml.chunked(3000).forEachIndexed { index, chunk ->
                    println("[KKTV DEBUG HTML DUMP $index] $chunk")
                }
                return getNoMatchesResponse(request.name, "토큰 추출 실패 (로그캣 확인 요망)")
            }

            // Target ID 추출 (실패 시 기본 469도 동적으로 찾게끔 설정)
            var dynamicTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1)
            if (dynamicTargetId == null) {
                println("[KKTV DEBUG] ⚠️ targetId를 찾지 못해 기본값 469 사용")
                dynamicTargetId = "469"
            }

            println("[KKTV DEBUG] 5. 최종 추출 데이터 -> TargetId: $dynamicTargetId | Token 길이: ${dynamicToken.length}")

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

            println("[KKTV DEBUG] 6. API POST 통신 시도: $apiUrl")
            val response = app.post(apiUrl, headers = postHeaders, data = postData)

            if (response.code != 200) {
                println("[KKTV DEBUG] ❌ API 통신 에러 발생. HTTP 상태 코드: ${response.code}")
                return getNoMatchesResponse(request.name, "API 통신 에러 (HTTP ${response.code})")
            }

            val responseText = response.text
            
            if (!responseText.trim().startsWith("{") && !responseText.trim().startsWith("[")) {
                println("[KKTV DEBUG] ❌ 잘못된 응답 데이터 수신. 앞부분: ${responseText.take(200)}")
                return getNoMatchesResponse(request.name, "잘못된 응답 데이터 (JSON이 아님)")
            }

            val jsonTree = mapper.readTree(responseText)
            val dataArray = jsonTree.get("data")

            if (dataArray != null && dataArray.isArray) {
                println("[KKTV DEBUG] 7. JSON 배열 내 경기 개수: ${dataArray.size()}")
                
                // 중요경기(title_color != N)와 일반경기를 분리하여 정렬
                val matchList = dataArray.toList()
                val importantMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color != null && color != "N" && color.isNotBlank()
                }
                val normalMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color == null || color == "N" || color.isBlank()
                }
                
                val sortedMatches = importantMatches + normalMatches
                
                sortedMatches.forEach { match ->
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

                        val playerUrl = "$iframeUrl?id=$seq"

                        val encodedTitle = URLEncoder.encode(displayTitle, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrlFromJson, "UTF-8")
                        val fakeUrl = "$playerUrl&kktv_title=$encodedTitle&kktv_poster=$encodedPoster"

                        items.add(
                            newLiveSearchResponse(name = displayTitle, url = fakeUrl) {
                                this.posterUrl = posterUrlFromJson
                            }
                        )
                    }
                }
                println("[KKTV DEBUG] 8. 최종 필터링된 'ING' 경기 개수: ${items.size}")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("[KKTV DEBUG] ❌ Exception 발생: ${e.message}")
            return getNoMatchesResponse(request.name, "파싱 중 치명적 오류 발생")
        }

        if (items.isEmpty()) {
            return getNoMatchesResponse(request.name, "현재 진행중인 중계가 없습니다")
        }

        println("[KKTV DEBUG] ✅ 카탈로그 반환 준비 완료.")
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
        val titleParam = Regex("""&kktv_title=([^&]+)""").find(url)?.groupValues?.get(1)
        val posterParam = Regex("""&kktv_poster=([^&]+)""").find(url)?.groupValues?.get(1)

        val title = if (titleParam != null) URLDecoder.decode(titleParam, "UTF-8") else "스포츠 실시간 중계"
        val poster = if (posterParam != null) URLDecoder.decode(posterParam, "UTF-8") else "$mainUrl/assets/Pc/img/hd_logo.png"

        val cleanUrl = url.substringBefore("&kktv_title=")

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = cleanUrl
        ) {
            this.posterUrl = poster
            this.plot = title
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[KKTV DEBUG] 9. 플레이어 페이지 로드 시도: $data")
            val playerHtml = app.get(data, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            val m3u8Regex = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex()
            var matchUrl = m3u8Regex.find(playerHtml)?.value

            if (matchUrl == null) {
                println("[KKTV DEBUG] ⚠️ 1차 m3u8 추출 실패. 이중 iframe 내부 탐색 시도...")
                val iframeSrc = Jsoup.parse(playerHtml).select("iframe").attr("src")
                if (iframeSrc.isNotBlank()) {
                    val iframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https://fkwdsvop.com$iframeSrc"
                    val iframeHtml2 = app.get(iframeUrl, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).text
                    matchUrl = m3u8Regex.find(iframeHtml2)?.value
                }
            }

            if (matchUrl != null) {
                println("[KKTV DEBUG] ✅ 최종 m3u8 스트림 추출 성공: $matchUrl")
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
                println("[KKTV DEBUG] ❌ m3u8 스트림을 찾지 못했습니다.")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            println("[KKTV DEBUG] ❌ loadLinks 에러: ${e.message}")
        }
        return false
    }
}
