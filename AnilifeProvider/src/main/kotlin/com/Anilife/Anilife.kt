package com.anilife

import android.util.Base64
import android.util.Log // Log 임포트 추가
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live" // 통신사 차단시 https://kr1.anilife.app 등 접속 가능한 도메인으로 변경 테스트
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "AnilifeProvider" // 디버그 태그 설정
    private val pcUserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/144.0.0.0 Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to pcUserAgent,
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        Log.d(TAG, "getMainPage 통신 시도 URL: $url")
        
        return try {
            val response = app.get(url, headers = commonHeaders)
            Log.d(TAG, "getMainPage 응답 코드: ${response.code}") // 상태 코드 확인
            
            val doc = response.document
            val items = parseCommonList(doc)
            Log.d(TAG, "getMainPage 파싱된 아이템 수: ${items.size}개")
            
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 에러를 무시하지 않고 로그캣에 출력합니다. (Connection reset인지 여부 확인)
            Log.e(TAG, "getMainPage 네트워크 또는 파싱 에러 (URL: $url)", e)
            e.printStackTrace()
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        val elements = doc.select(".listupd > article.bs")
        Log.d(TAG, "parseCommonList 찾은 문서 요소(article.bs) 갯수: ${elements.size}개")
        
        return elements.mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: run {
                    Log.w(TAG, "a 태그를 찾지 못함")
                    return@mapNotNull null
                }
                val rawHref = fixUrl(aTag.attr("href"))
                val title = (element.selectFirst(".tt h2") ?: element.selectFirst(".tt"))?.text()?.trim() ?: "Unknown"
                
                val imgTag = element.selectFirst("img")
                var poster = imgTag?.attr("src") ?: imgTag?.attr("data-src") ?: ""
                poster = fixUrl(poster)
                
                if (poster.isEmpty()) {
                    poster = "data:image/gif;base64,R0lGODlhAQABAIAAAAAAAP///yH5BAEAAAAALAAAAAABAAEAAAIBRAA7"
                }
                
                val finalHref = if (poster.isNotEmpty()) {
                    val encoded = Base64.encodeToString(poster.toByteArray(), Base64.NO_WRAP)
                    if (rawHref.contains("?")) "$rawHref&poster=$encoded" else "$rawHref?poster=$encoded"
                } else rawHref
                
                Log.d(TAG, "파싱 성공 -> 제목: $title, 링크: $rawHref")
                
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { 
                Log.e(TAG, "개별 아이템 파싱 중 에러", e)
                null 
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        Log.d(TAG, "search 통신 시도 URL: $url")
        
        return try {
            val doc = app.get(url, headers = commonHeaders).document
            val items = parseCommonList(doc)
            Log.d(TAG, "search 파싱된 아이템 수: ${items.size}개")
            items
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "search 네트워크 또는 파싱 에러 (Query: $query)", e)
            e.printStackTrace()
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        var tunnelingPoster: String? = null
        val cleanUrl = if (url.contains("poster=")) {
            val posterParam = url.substringAfter("poster=")
            tunnelingPoster = String(Base64.decode(posterParam.substringBefore("&"), Base64.NO_WRAP))
            url.substringBefore("?poster=")
        } else url

        Log.d(TAG, "load 통신 시도 URL: $cleanUrl")

        try {
            val response = app.get(cleanUrl, headers = commonHeaders)
            Log.d(TAG, "load 응답 코드: ${response.code}")
            
            val doc = response.document
            val finalUrl = response.url
            val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

            val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
            val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            val tags = doc.select(".genxed a").map { it.text() }

            var currentEpisodeNumber = 0
            val numRegex = Regex("[^0-9.]") 

            val elements = doc.select(".eplister > ul > li > a").reversed()
            Log.d(TAG, "load 에피소드 갯수: ${elements.size}개 (제목: $title)")

            val episodes = elements.mapNotNull { element ->
                val rawHref = fixUrl(element.attr("href"))
                val numText = element.selectFirst(".epl-num")?.text()?.trim() ?: ""
                val epTitle = element.selectFirst(".epl-title")?.text()?.trim() ?: ""
                
                val fullName = if (numText.isNotEmpty()) "${numText}화 - $epTitle" else epTitle
                val finalHref = if (rawHref.contains("?")) "$rawHref&ref=$encodedRef" else "$rawHref?ref=$encodedRef"
                
                val parsedNum = numText.replace(numRegex, "").toFloatOrNull()?.toInt() ?: 0

                if (currentEpisodeNumber == 0) {
                    currentEpisodeNumber = if (parsedNum > 0) parsedNum else 1
                } else {
                    if (parsedNum > currentEpisodeNumber) {
                        currentEpisodeNumber = parsedNum
                    } else {
                        currentEpisodeNumber++
                    }
                }

                newEpisode(finalHref) {
                    this.name = fullName
                    this.episode = currentEpisodeNumber
                }
            }

            return newAnimeLoadResponse(title, cleanUrl, TvType.Anime) {
                this.posterUrl = doc.selectFirst(".thumb img")?.attr("src") ?: tunnelingPoster
                this.posterHeaders = commonHeaders
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "load 중 예외 발생 (URL: $cleanUrl)", e)
            e.printStackTrace()
            throw e
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var cleanData = data.substringBefore("?poster=")
        var detailReferer = "$mainUrl/"
        if (cleanData.contains("ref=")) {
            try {
                val refEncoded = cleanData.substringAfter("ref=").substringBefore("&")
                detailReferer = String(Base64.decode(refEncoded, Base64.NO_WRAP))
                cleanData = cleanData.substringBefore("?ref=").substringBefore("&ref=")
            } catch (e: Exception) { }
        }

        val videoIdMatch = Regex("""(?:id|provider)/([^/?]+)""").find(cleanData)
        val videoId = videoIdMatch?.groupValues?.get(1) ?: "unknown_id"

        Log.d(TAG, "loadLinks 통신 시도 URL: $cleanData (Referer: $detailReferer)")

        try {
            val webResponse = app.get(
                cleanData, 
                headers = mapOf("Referer" to detailReferer, "User-Agent" to pcUserAgent)
            )

            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl)
            Log.d(TAG, "loadLinks 추출된 플레이어 URL: $playerUrl")
            
            if (playerUrl == null) {
                Log.w(TAG, "플레이어 URL을 찾지 못함")
                return false
            }

            return AnilifeProxyExtractor().extractWithProxy(
                m3u8Url = "",
                playerUrl = playerUrl,
                referer = "https://anilife.live/",
                ssid = null,
                cookies = "",
                targetKeyUrl = null,
                videoId = videoId,
                callback = callback
            )

        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "loadLinks 중 예외 발생", e)
            e.printStackTrace()
        }
        return false
    }
}
