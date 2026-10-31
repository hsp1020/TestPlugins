package com.kktv

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

class KKTVProvider : MainAPI() {
    override var mainUrl = "https://www.goli.tv"
    override var name = "KKTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "live_matches" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

    // 해독된 알고리즘을 기반으로 로컬에서 최신 auToken을 0.001초 만에 구워내는 자체 엔진
    private fun generateAuToken(): String {
        val basePlaintext = "https://fkwdsvop.com/KingKong/livescore/livescore/@${System.currentTimeMillis()}@"
        val hexChars = "abcdef0123456789"
        val random = SecureRandom()
        
        // 1. 전체 평문 길이가 정확히 80자가 될 때까지 무작위 Hex(난수) 패딩
        var plaintext = basePlaintext
        while (plaintext.length < 80) {
            plaintext += hexChars[random.nextInt(hexChars.length)]
        }
        
        // 2. 무작위 AES 키(16자) 및 무작위 솔트(16자) 생성
        val keyStr = (1..16).map { hexChars[random.nextInt(hexChars.length)] }.joinToString("")
        val saltStr = (1..16).map { hexChars[random.nextInt(hexChars.length)] }.joinToString("")
        
        // 3. AES-128-ECB 암호화 연산 수행
        val keyBytes = keyStr.toByteArray(Charsets.UTF_8)
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey)
        
        val ciphertextBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val ciphertextHex = ciphertextBytes.joinToString("") { String.format("%02x", it) }
        
        // 4. 최종 auToken 조립 규칙 구현 [솔트(16자) + 키(16자) + 암호문]
        return saltStr + keyStr + ciphertextHex
    }

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

            // 1. targetId 추출 (var targetId=469; 매칭)
            val targetIdRegex = Regex("""targetId\s*=\s*(\d+)""")
            val parsedTargetId = targetIdRegex.find(iframeHtml)?.groupValues?.get(1)

            if (parsedTargetId == null) {
                println("[KKTV DEBUG] ⚠️ targetId 추출 실패! 기본값 469 사용.")
            } else {
                println("[KKTV DEBUG] ✅ targetId 추출 성공: $parsedTargetId")
            }

            // 2. 자체 토큰 생성기 연동 (지연 시간 0ms, 무한 보장)
            val dynamicToken = generateAuToken()
            val dynamicTargetId = parsedTargetId ?: "469"
            
            println("[KKTV DEBUG] 🚀 로컬 실시간 auToken 자동 생성 완료: $dynamicToken")

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
                        
                        // [개선] 캐시 우회 기법(Cache Busting)을 위해 썸네일 URL 뒤에 분단위 타임스탬프를 덧붙여 전송합니다.
                        val currentTimestamp = System.currentTimeMillis() / 60000
                        val posterUrlFromJson = if (thumbnail.isNotBlank()) {
                            val cleanThumb = if (thumbnail.startsWith("//")) "https:$thumbnail" else thumbnail
                            "$cleanThumb?t=$currentTimestamp"
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
