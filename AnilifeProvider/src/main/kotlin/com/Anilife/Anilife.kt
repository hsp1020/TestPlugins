package com.anilife

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.WebViewResolver // 클라우드플레어 우회용 인터셉터
import com.lagradost.cloudstream3.network.NiceResponse
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "AnilifeProvider"
    
    // 모바일 기기 및 안드로이드 WebView의 실제 TLS 핑거프린트와 정합성을 맞추기 위해 모바일 크롬 UA 적용
    private val mobileUserAgent = "Mozilla/5.0 (Linux; Android 13; Pixel 7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

    private val commonHeaders = mapOf(
        "User-Agent" to mobileUserAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Referer" to "$mainUrl/"
    )

    // 핵심: anilife.live 도메인으로 가는 요청은 실제 숨겨진 브라우저(WebView)를 태워서 통과시킴
    private val cfInterceptor = WebViewResolver(Regex("""anilife\.live"""))

    override val mainPage = mainPageOf(
        "/top20" to "실시간 TOP 20",
        "/vodtype/categorize/TV/1" to "TV 애니메이션",
        "/vodtype/categorize/OVA/1" to "OVA",
        "/vodtype/categorize/ONA/1" to "ONA",
        "/vodtype/categorize/SP/1" to "SP",
        "/vodtype/categorize/Movie/1" to "극장판"
    )

    /**
     * OkHttp 다이렉트 통신을 1차 시도하고, 
     * 실패 또는 검증 요구 발생 시 WebViewResolver를 2차로 우회 호출하는 Resilient 모듈
     */
    private suspend fun requestWithFallback(url: String, headers: Map<String, String> = commonHeaders): NiceResponse {
        return try {
            // 1차 시도: 일반 OkHttp 통신 (매우 신속하며 WebView의 SSL 버그 자체를 원천 회피)
            val response = app.get(url, headers = headers)
            if (response.code == 200) {
                Log.d(TAG, "[requestWithFallback] 일반 OkHttp 통신 성공 -> $url")
                response
            } else {
                throw Exception("Response code ${response.code}")
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.w(TAG, "[requestWithFallback] 일반 통신 실패 (${e.message}). WebViewResolver 우회 작동 시도 -> $url")
            // 2차 시도: WebViewResolver 사용 (모바일 UA 대조를 통해 완벽한 핸드셰이크 조율 제공)
            app.get(url, headers = headers, interceptor = cfInterceptor)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name.contains("TOP 20") && page > 1) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val url = if (request.name.contains("TOP 20")) "$mainUrl${request.data}" 
                  else "$mainUrl${request.data.substringBeforeLast("/")}/$page"
        
        Log.d(TAG, "[getMainPage] 데이터 로드 시도 -> $url")
        
        return try {
            val response = requestWithFallback(url, commonHeaders)
            Log.d(TAG, "[getMainPage] 응답 코드: ${response.code}") 
            
            val doc = response.document
            val items = parseCommonList(doc)
            Log.d(TAG, "[getMainPage] 파싱 성공: ${items.size}개")
            
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[getMainPage] 페이지 획득 최종 실패", e)
            newHomePageResponse(request.name, emptyList(), hasNext = false)
        }
    }

    private fun parseCommonList(doc: Document): List<SearchResponse> {
        val elements = doc.select(".listupd > article.bs")
        return elements.mapNotNull { element ->
            try {
                val aTag = element.selectFirst("div.bsx > a") ?: return@mapNotNull null
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
                
                newAnimeSearchResponse(title, finalHref, TvType.Anime) {
                    this.posterUrl = poster
                    this.posterHeaders = commonHeaders
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/search?keyword=$query"
        Log.d(TAG, "[search] 검색 시도 -> $url")
        return try {
            val response = requestWithFallback(url, commonHeaders)
            parseCommonList(response.document)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[search] 검색 최종 에러 발생", e)
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

        Log.d(TAG, "[load] 상세 정보 획득 시도 -> $cleanUrl")

        try {
            val response = requestWithFallback(cleanUrl, commonHeaders)
            val doc = response.document
            val finalUrl = response.url
            val encodedRef = Base64.encodeToString(finalUrl.toByteArray(), Base64.NO_WRAP)

            val title = doc.selectFirst(".entry-title")?.text()?.trim() ?: "Unknown"
            val plot = doc.selectFirst(".synp .entry-content")?.text()?.trim()
            val tags = doc.select(".genxed a").map { it.text() }

            var currentEpisodeNumber = 0
            val numRegex = Regex("[^0-9.]") 

            val elements = doc.select(".eplister > ul > li > a").reversed()
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
            Log.e(TAG, "[load] 상세 정보 로드 최종 에러", e)
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

        Log.d(TAG, "[loadLinks] 동영상 링크 로드 시도 -> $cleanData")

        try {
            val linkHeaders = commonHeaders.toMutableMap()
            linkHeaders["Referer"] = detailReferer

            val webResponse = requestWithFallback(cleanData, linkHeaders)
            val playerUrl = AnilifeProxyExtractor().extractPlayerUrl(webResponse.text, mainUrl) ?: return false

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
            Log.e(TAG, "[loadLinks] 링크 분석 최종 에러", e)
        }
        return false
    }
}
