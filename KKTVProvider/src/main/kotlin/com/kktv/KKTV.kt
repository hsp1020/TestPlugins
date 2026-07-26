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
            val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            // [개선] JSON 객체나 일반 선언문 모두 매칭할 수 있도록 유연해진 정규식 패턴
            val tokenRegex = Regex("""['"]?auToken['"]?\s*[:=]\s*['"]([^'"]+)['"]""")
            val targetIdRegex = Regex("""['"]?c_targetId['"]?\s*[:=]\s*['"]?(\d+)['"]?""")
            
            val parsedToken = tokenRegex.find(iframeHtml)?.groupValues?.get(1)
            val parsedTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1)

            // [추가] 추출 성공 여부 디버깅을 위한 Logcat용 콘솔 로그 출력
            if (parsedToken != null) {
                println("[KKTV DEBUG]  auToken 추출 성공: ${parsedToken.take(15)}... (길이: ${parsedToken.length})")
            } else {
                println("[KKTV DEBUG] ⚠️ auToken 추출 실패! 패턴 비매칭 혹은 요청 차단 의심. 임시 하드코딩 값 사용.")
            }

            if (parsedTargetId != null) {
                println("[KKTV DEBUG]  c_targetId 추출 성공: $parsedTargetId")
            } else {
                println("[KKTV DEBUG] ⚠️ c_targetId 추출 실패! 임시 하드코딩 값 사용.")
            }

            val dynamicToken = parsedToken 
                ?: "3113b57e00d91c434ae2d3062c0723f3a5c1640ad062845e9f0ab5056d83574794045fad9947198d6a6e33fa44852bdd2b6ebabc84ae436d762c56bae8341624456b001f393b5c4ca8e235c402e86f48ad75cb6b5e4d51c37f458dbb55fc3f5b5d37ec2574af1a0180aee522b902ce96"
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

    // [추가] 각 CDN 링크의 정상 작동 여부(HTTP 200 OK)를 판단하는 비동기 요청 함수
    private suspend fun checkUrl(url: String): Boolean {
        return try {
            withTimeoutOrNull(2500) { // 응답 제한 시간 2.5초 설정
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
                // K, S, W 세 가지 CDN 주소 후보군 대입
                val kcdnUrl = data
                val scdnUrl = data.replace("kcdn.ehfkdowl.com", "scdn.ehfkdowl.com")
                val wcdnUrl = data.replace("kcdn.ehfkdowl.com", "wcdn.ehfkdowl.com")

                val candidates = listOf(
                    "K-CDN" to kcdnUrl,
                    "S-CDN" to scdnUrl,
                    "W-CDN" to wcdnUrl
                )

                // [추가] 코루틴 병렬 처리를 활용하여 세 주소를 동시에 비동기 검사
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
                    // 유효성이 검증된 실시간 CDN 링크들만 필터링하여 플레이어에 반환
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
                    // 세 도메인 모두 확인이 실패했거나 일시적 네트워크 타임아웃 발생 시, 기본 K-CDN 제공
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
