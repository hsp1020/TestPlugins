package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URLDecoder
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://ifadetv.com"
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
            val iframeResponse = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent))
            val iframeHtml = iframeResponse.text
            
            println("[KKTV DEBUG] 2. iframe 응답 결과 | HTTP Code: ${iframeResponse.code} | 본문 길이: ${iframeHtml.length}")
            
            if (iframeHtml.length < 1000) {
                println("[KKTV DEBUG] ⚠️ 본문이 지나치게 짧습니다. 아래는 수신된 실제 HTML 전체 내용입니다:")
                println(iframeHtml)
            }

            // 1. targetId 추출 (var targetId=469; 매칭)
            val targetIdRegex = Regex("""targetId\s*=\s*(\d+)""")
            val parsedTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1)

            if (parsedTargetId == null) {
                println("[KKTV DEBUG] ⚠️ targetId 추출 실패! 기본값 469 사용.")
            } else {
                println("[KKTV DEBUG] ✅ targetId 추출 성공: $parsedTargetId")
            }

            // 2. 외부 JS 파일 추적 및 실시간 auToken 추출 진행
            var parsedToken: String? = null

            // HTML 내부에서 /def_iframe_v2/ 경로를 포함하는 JS 스크립트 소스 주소들을 동적 추출
            val scriptRegex = Regex("""src=["']([^"']+/def_iframe_v2/[^"']+)["']""")
            var jsUrls = scriptRegex.findAll(iframeHtml).map { match ->
                val path = match.groupValues[1]
                if (path.startsWith("http")) path else "https://fkwdsvop.com$path"
            }.toList()

            // 추출 실패 시 대비용 대체 경로
            if (jsUrls.isEmpty()) {
                println("[KKTV DEBUG] ⚠️ HTML에서 JS 파일 경로 추출 실패. 기본 경로 목록을 대입합니다.")
                jsUrls = listOf(
                    "https://fkwdsvop.com/static/common/js/def_iframe_v2/common.js?v=202603251115671",
                    "https://fkwdsvop.com/static/common/js/def_iframe_v2/sports.min.js?v=202603251115671"
                )
            }

            // 토큰 추출용 224자 Hex 정규식
            val hexRegex = Regex("""[0-9a-fA-F]{200,240}""")

            for (jsUrl in jsUrls) {
                try {
                    println("[KKTV DEBUG] 🔍 외부 JS 파일 다운로드 및 토큰 검색 중: $jsUrl")
                    val jsResponse = app.get(jsUrl, headers = mapOf("Referer" to iframeUrl, "User-Agent" to userAgent))
                    val jsContent = jsResponse.text
                    
                    // [개선] getEcodeData 발견 시 안드로이드 로그캣 한계 우회를 위해 1,000자씩 분할 출력 수행
                    var lastIndex = 0
                    var matchCount = 1
                    while (true) {
                        val index = jsContent.indexOf("getEcodeData", lastIndex)
                        if (index == -1) break
                        
                        println("[KKTV DEBUG] 🔍 getEcodeData 발견 #$matchCount ($jsUrl) - index: $index")
                        val start = (index - 200).coerceAtLeast(0)
                        val end = (index + 8000).coerceAtMost(jsContent.length) // 8,000자 수집
                        
                        println("[KKTV DEBUG] 📋 주변 소스코드 #$matchCount ---------------------------------")
                        val fullSubstring = jsContent.substring(start, end)
                        
                        // 1000자 조각으로 쪼개서 줄 단위로 출력 (로그캣 잘림 우회 완료)
                        fullSubstring.chunked(1000).forEachIndexed { chunkIndex, chunk ->
                            println("[KKTV DEBUG] 📋 [파트 ${chunkIndex + 1}] $chunk")
                        }
                        println("[KKTV DEBUG] 📋 -------------------------------------------------------------")
                        
                        lastIndex = index + "getEcodeData".length
                        matchCount++
                    }

                    // [추가] 시간 추출 포맷 확인을 위해 getLocalTime 함수 정의도 함께 추적 출력합니다.
                    var lastLtIndex = 0
                    var ltMatchCount = 1
                    while (true) {
                        val index = jsContent.indexOf("getLocalTime", lastLtIndex)
                        if (index == -1) break
                        
                        println("[KKTV DEBUG] 🔍 getLocalTime 발견 #$ltMatchCount ($jsUrl) - index: $index")
                        val start = (index - 200).coerceAtLeast(0)
                        val end = (index + 1500).coerceAtMost(jsContent.length)
                        val ltSubstring = jsContent.substring(start, end)
                        
                        ltSubstring.chunked(1000).forEachIndexed { chunkIndex, chunk ->
                            println("[KKTV DEBUG] 📋 [getLocalTime 파트 ${chunkIndex + 1}] $chunk")
                        }
                        println("[KKTV DEBUG] 📋 -------------------------------------------------------------")
                        
                        lastLtIndex = index + "getLocalTime".length
                        ltMatchCount++
                    }

                    // JS 본문 안에서 "auToken" 키워드 위치 추적
                    val auTokenKeyIndex = jsContent.indexOf("auToken")
                    if (auTokenKeyIndex != -1) {
                        println("[KKTV DEBUG] 🔍 JS 내부에서 'auToken' 키워드 위치 감지 성공!")
                        
                        val searchArea = jsContent.substring(
                            (auTokenKeyIndex - 100).coerceAtLeast(0),
                            (auTokenKeyIndex + 1000).coerceAtMost(jsContent.length)
                        )
                        
                        println("[KKTV DEBUG] 📋 'auToken' 키워드 기준 주변 소스코드 대량 출력 시작 -----------------")
                        println(searchArea)
                        println("[KKTV DEBUG] 📋 -------------------------------------------------------------")

                        val tokenMatch = hexRegex.find(searchArea)?.value
                        if (tokenMatch != null) {
                            parsedToken = tokenMatch
                            println("[KKTV DEBUG] ✅ JS 키워드 기반 auToken 추출 성공: $parsedToken")
                            break
                        } else {
                            println("[KKTV DEBUG] ⚠️ 키워드 감지는 했으나, 주변 1100자 이내에서 200~240자리 Hex 문자열 매칭 실패")
                        }
                    }
                    
                    // 성능 병목 해결: 대용량 JS 전문 스캔 대신 상위 100KB 범위에서만 고속 매칭 테스트
                    val scanLimit = jsContent.substring(0, 100000.coerceAtMost(jsContent.length))
                    val generalMatch = hexRegex.find(scanLimit)?.value
                    if (generalMatch != null) {
                        parsedToken = generalMatch
                        println("[KKTV DEBUG] ✅ JS 전역 224자 Hex 패턴 auToken 파싱 완료: $parsedToken")
                        break
                    }
                } catch (e: Exception) {
                    println("[KKTV DEBUG] ⚠️ JS 파일 ($jsUrl) 다운로드 실패: ${e.message}")
                }
            }

            // 하드코딩 토큰을 완전히 지우고, 추출 실패 시 바로 에러를 띄워 헛수고 요청을 차단합니다.
            val dynamicToken = parsedToken ?: return getNoMatchesResponse(request.name, "인증 토큰(auToken) 실시간 추출 실패")
            val dynamicTargetId = parsedTargetId ?: "469"

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

            val response = app.post(apiUrl, headers = postHeaders, data = postData)

            if (response.code != 200) {
                return getNoMatchesResponse(request.name, "API 통신 에러 (HTTP ${response.code})")
            }

            val responseText = response.text
            
            if (!responseText.trim().startsWith("{") && !responseText.trim().startsWith("[")) {
                return getNoMatchesResponse(request.name, "잘못된 응답 데이터 (JSON이 아님)")
            }

            val jsonTree = mapper.readTree(responseText)
            val dataArray = jsonTree.get("data")

            if (dataArray != null && dataArray.isArray) {
                
                val matchList = dataArray.toList()
                
                val importantMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color != null && color != "N" 
                }
                val normalMatches = matchList.filter { 
                    val color = it.get("title_color")?.asText()
                    color == null || color == "N" 
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
                            ""
                        }

                        val channelId = thumbnail.substringAfterLast("/")
                        val tParam = (System.currentTimeMillis() / 1000) + 86400
                        val m3u8Url = "https://kcdn.ehfkdowl.com/bigwinsoft/$channelId/chunklist.m3u8?t=$tParam&ds=$seq"

                        val encodedTitle = URLEncoder.encode(displayTitle, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrlFromJson, "UTF-8")
                        val fakeUrl = "$m3u8Url&kktv_title=$encodedTitle&kktv_poster=$encodedPoster"

                        items.add(
                            newLiveSearchResponse(name = displayTitle, url = fakeUrl) {
                                this.posterUrl = posterUrlFromJson
                            }
                        )
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return getNoMatchesResponse(request.name, "파싱 중 치명적 오류 발생")
        }

        if (items.isEmpty()) {
            return getNoMatchesResponse(request.name, "현재 진행중인 중계가 없습니다")
        }

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
        val poster = if (posterParam != null) URLDecoder.decode(posterParam, "UTF-8") else ""

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

    private suspend fun checkUrl(url: String): Boolean {
        return try {
            withTimeoutOrNull(2500) {
                val response = app.get(
                    url,
                    headers = mapOf(
                        "Referer" to "https://fkwdsvop.com/",
                        "User-Agent" to userAgent
                    )
                )
                response.code == 200
            } ?: false
        } catch (e: Exception) {
            false
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
                val kcdnUrl = data
                val scdnUrl = data.replace("kcdn.ehfkdowl.com", "scdn.ehfkdowl.com")
                val wcdnUrl = data.replace("kcdn.ehfkdowl.com", "wcdn.ehfkdowl.com")

                val candidates = listOf(
                    "K-CDN" to kcdnUrl,
                    "S-CDN" to scdnUrl,
                    "W-CDN" to wcdnUrl
                )

                val validLinks = coroutineScope {
                    candidates.map { (cdnName, url) ->
                        async {
                            if (checkUrl(url)) {
                                cdnName to url
                            } else {
                                null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }

                if (validLinks.isNotEmpty()) {
                    for ((cdnName, url) in validLinks) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "${this.name} $cdnName",
                                url = url,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "https://fkwdsvop.com/"
                                this.headers = mapOf("User-Agent" to userAgent)
                            }
                        )
                    }
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "${this.name} K-CDN (Fallback)",
                            url = kcdnUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = "https://fkwdsvop.com/"
                            this.headers = mapOf("User-Agent" to userAgent)
                        }
                    )
                }
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }
}
