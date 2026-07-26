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
            val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            val tokenRegex = Regex("""auToken\s*[:=]\s*['"]([^'"]+)['"]""")
            val targetIdRegex = Regex("""c_targetId\s*[:=]\s*['"]?(\d+)['"]?""")
            
            // 🚨 지적해주신 하드코딩 삭제. 정규식으로 찾고 없으면 빈 값 전송
            val dynamicToken = tokenRegex.find(iframeHtml)?.groupValues?.get(1) ?: ""
            val dynamicTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1) ?: ""

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
                
                // 중요경기(title_color != N)와 일반경기를 분리하여 정렬
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
                            "$mainUrl/assets/Pc/img/hd_logo.png"
                        }

                        // 🚨 지적해주신 kcdn 강제 하드코딩 삭제. 
                        // 대신 실제 플레이어 페이지 URL을 넘겨서 loadLinks에서 동적으로 추출하게 만듦.
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

    // 🚨 여기서 kcdn, wcdn, scdn 상관없이 알아서 m3u8을 추출합니다.
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // data는 이제 "https://fkwdsvop.com/KingKong/livescore/livescore/?id=48214" 와 같은 플레이어 주소입니다.
            val playerHtml = app.get(data, headers = mapOf("Referer" to mainUrl, "User-Agent" to userAgent)).text
            
            // HTML 소스 내부에 박혀있는 m3u8 링크를 정규식으로 모조리 잡아냅니다.
            val m3u8Regex = """https?://[^\s"'<>]+chunklist\.m3u8[^\s"'<>]*|https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""".toRegex()
            var matchUrl = m3u8Regex.find(playerHtml)?.value

            // 혹시라도 플레이어 페이지 안에 또 iframe으로 영상을 띄운 구조라면, 그 안까지 타고 들어갑니다.
            if (matchUrl == null) {
                val iframeSrc = Jsoup.parse(playerHtml).select("iframe").attr("src")
                if (iframeSrc.isNotBlank()) {
                    val iframeUrl = if (iframeSrc.startsWith("http")) iframeSrc else "https://fkwdsvop.com$iframeSrc"
                    val iframeHtml = app.get(iframeUrl, headers = mapOf("Referer" to data, "User-Agent" to userAgent)).text
                    matchUrl = m3u8Regex.find(iframeHtml)?.value
                }
            }

            // 동적으로 찾은 찐 m3u8 링크를 재생기에 던져줍니다.
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
        }
        return false
    }
}
