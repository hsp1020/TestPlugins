package com.anilife

import android.util.Base64
import android.util.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import kotlinx.coroutines.CancellationException

class Anilife : MainAPI() {
    override var mainUrl = "https://anilife.live"
    override var name = "Anilife"
    override val hasMainPage = true
    override var lang = "ko"
    override val supportedTypes = setOf(TvType.Anime)

    private val TAG = "AnilifeDebug"

    // 일반 PC 브라우저와 완전히 동일한 지문을 남기기 위한 극한의 헤더 셋업
    private val chromeHeaders = mapOf(
        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Sec-Ch-Ua" to "\"Chromium\";v=\"124\", \"Google Chrome\";v=\"124\", \"Not-A.Brand\";v=\"99\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Windows\"",
        "Sec-Fetch-Dest" to "document",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "none",
        "Sec-Fetch-User" to "?1",
        "Upgrade-Insecure-Requests" to "1",
        "Connection" to "keep-alive",
        "Referer" to "$mainUrl/" // 레퍼러 강제 삽입
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
        
        Log.d(TAG, "===============================================")
        Log.d(TAG, "[getMainPage] 데이터 요청 시작 -> $url")
        Log.d(TAG, "[getMainPage] 주입된 헤더: $chromeHeaders")
        
        return try {
            val response = app.get(url, headers = chromeHeaders)
            Log.d(TAG, "[getMainPage] 서버 응답 코드: ${response.code}") 
            
            // 만약 클라우드플레어 차단 페이지가 뜬다면 여기서 감지됩니다.
            if (response.code == 403 || response.text.contains("Cloudflare")) {
                Log.e(TAG, "[getMainPage] 클라우드플레어 403 봇 방어막에 차단됨!")
            }

            val doc = response.document
            val items = parseCommonList(doc)
            Log.d(TAG, "[getMainPage] 파싱 성공 아이템 수: ${items.size}개")
            
            val hasNextPage = if (request.name.contains("TOP 20")) false else items.isNotEmpty()
            
            newHomePageResponse(request.name, items, hasNext = hasNextPage)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            
            Log.e(TAG, "[getMainPage] ❌ 통신 에러 발생 ❌")
            Log.e(TAG, "[getMainPage] 에러 종류: ${e.javaClass.simpleName}")
            Log.e(TAG, "[getMainPage] 에러 메시지: ${e.message}")
            
            // 핸드셰이크 단계 에러인지 판별
            if (e.stackTraceToString().contains("doHandshake")) {
                Log.e(TAG, "🚨 분석 결과: 서버에 헤더(Referer 등)를 보내기도 전에 SSL Handshake 단계에서 연결이 끊어졌습니다. (클라우드플레어 TLS 지문 차단 또는 ECH 부재로 인한 망 차단)")
            } else {
                Log.e(TAG, "🚨 분석 결과: 연결은 성공했으나 다른 문제가 발생했습니다.")
            }
            
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
                    this.posterHeaders = chromeHeaders
                }
            } catch (e: Exception) { null }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return try {
            val doc = app.get("$mainUrl/search?keyword=$query", headers = chromeHeaders).document
            parseCommonList(doc)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[search] 에러 발생: ${e.message}")
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

        try {
            val response = app.get(cleanUrl, headers = chromeHeaders)
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
                this.posterHeaders = chromeHeaders
                this.plot = plot
                this.tags = tags
                addEpisodes(DubStatus.Subbed, episodes)
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "[load] 에러 발생: ${e.message}")
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

        try {
            // loadLinks에는 해당 상세 페이지의 레퍼러를 동적으로 덮어씌웁니다.
            val linkHeaders = chromeHeaders.toMutableMap()
            linkHeaders["Referer"] = detailReferer

            val webResponse = app.get(cleanData, headers = linkHeaders)
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
            Log.e(TAG, "[loadLinks] 에러 발생: ${e.message}")
        }
        return false
    }
}
