// v8.3 - 400 body error 해결: 합법적인 m3u8 주소 및 토큰 획득을 위해 iOS 사파리 User-Agent 위장 로직 적용
package com.KingkongTv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLDecoder
import java.net.URLEncoder

class KingkongTv : MainAPI() {
    override var mainUrl = "https://holyindia.org"
    override var name = "KKTV"
    override val hasMainPage = true
    override var lang = "ko"
    
    override val supportedTypes = setOf(TvType.Others)

    // 메인 페이지 파싱은 기본 PC 환경 유지
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()

        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc!!)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { row ->
                val isImportant = row.selectFirst("td.subject")?.hasClass("bold") == true
                isImportant
            }
            
            val home = sortedRows.mapNotNull { row ->
                row.toSearchResult(realUrl)
            }

            return newHomePageResponse(
                list = HomePageList(
                    name = request.name,
                    list = home,
                    isHorizontalImages = true
                ),
                hasNext = false
            )
        } catch (e: Exception) {
            e.printStackTrace()
            return newHomePageResponse(request.name, emptyList())
        }
    }

    private fun Element.toSearchResult(refererUrl: String): SearchResponse? {
        if (this.selectFirst("span.off") != null) return null

        val channelDiv = this.selectFirst("div.channel_on") ?: return null
        var title = channelDiv.attr("data-title")
        val streamId = channelDiv.attr("data-stream")
        
        if (title.isBlank() || streamId.isBlank()) return null

        if (this.selectFirst("td.subject")?.hasClass("bold") == true) {
            title = "🔥 $title"
        }

        val thumbUrlRaw = this.selectFirst("td.thumb img")?.attr("src")
        val thumbUrl = if (thumbUrlRaw != null) {
            val fixedUrl = fixUrl(refererUrl, thumbUrlRaw)
            val separator = if (fixedUrl.contains("?")) "&" else "?"
            "$fixedUrl${separator}t=${System.currentTimeMillis()}"
        } else ""

        val encodedTitle = URLEncoder.encode(title, "UTF-8")
        val encodedPoster = URLEncoder.encode(thumbUrl, "UTF-8")
        val encodedRef = URLEncoder.encode(refererUrl, "UTF-8")

        val fakeUrl = "$mainUrl/live_stream/$streamId?title=$encodedTitle&poster=$encodedPoster&ref=$encodedRef"

        return newMovieSearchResponse(title, fakeUrl, TvType.Others) {
            this.posterUrl = thumbUrl
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val streamId = url.substringAfter("/live_stream/").substringBefore("?")
        
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        return newMovieLoadResponse(title, url, TvType.Others, url) {
            this.posterUrl = poster
            this.plot = "실시간 중계" 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val rawRef = uri.getQueryParameter("ref")
        val refererUrl = if (!rawRef.isNullOrBlank()) URLDecoder.decode(rawRef, "UTF-8") else "https://kktv.speed10-1.com/kktv/index.php"
        
        val rawTitle = uri.getQueryParameter("title") ?: ""
        val cleanTitle = rawTitle.replace("🔥 ", "")
        val encodedTitle = URLEncoder.encode(cleanTitle, "UTF-8").replace("+", "%20")
        
        val playerPageUrl = "https://kktv.speed10-1.com/kktv/theme/kktv/pages/pc/pc_view.php?ch=$streamId&title=$encodedTitle&start=on&num=1"
        
        // 아이폰 사파리 환경으로 강제 위장
        val iosUserAgent = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

        val headers = mapOf(
            "User-Agent" to iosUserAgent,
            "Referer" to refererUrl,
            "Origin" to "https://kktv.speed10-1.com"
        )

        try {
            val response = app.get(playerPageUrl, headers = headers).text
            
            // 1. 아이폰 UA에 속아 .m3u8 확장자와 올바른 토큰을 반환했는지 탐색
            var m3u8Url: String? = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            if (m3u8Url == null) {
                // 2. 응답이 없다면 모바일 전용 경로(/m/m_view.php)로 2차 시도
                val mobilePageUrl = playerPageUrl.replace("/pc/pc_view.php", "/m/m_view.php")
                val mobileResponse = try { app.get(mobilePageUrl, headers = headers).text } catch(e: Exception) { "" }
                m3u8Url = Regex("""['"](http[^'"]+\.m3u8[^'"]*)['"]""").find(mobileResponse)?.groupValues?.get(1)
            }

            if (m3u8Url == null) {
                // 3. 최후의 수단: 여전히 sdp를 내려준다면 이전처럼 강제 변환 시도
                val sdpMatch = Regex("""['"](http[^'"]+\.sdp[^'"]*)['"]""").find(response)?.groupValues?.get(1)
                m3u8Url = sdpMatch?.replace(".sdp", ".m3u8")
            }

            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                m3u8Url = webrtcMatch?.replace("webrtc://", "https://")?.replace(streamId, "$streamId.m3u8") 
            }

            if (m3u8Url == null) {
                m3u8Url = "https://cdnnowstream.com/cdnnow/$streamId.m3u8?site=kktv"
            }

            val finalUrl = if (m3u8Url!!.contains("play.ogtv3.com") && m3u8Url!!.startsWith("https://")) {
                m3u8Url!!.replaceFirst("https://", "http://")
            } else {
                m3u8Url!!
            }

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = finalUrl,
                    type = ExtractorLinkType.M3U8
                ) {
                    this.referer = "https://kktv.speed10-1.com/"
                    this.quality = Qualities.Unknown.value
                    this.headers = mapOf(
                        "Origin" to "https://kktv.speed10-1.com",
                        "Referer" to "https://kktv.speed10-1.com/",
                        "User-Agent" to iosUserAgent // 플레이어 단에도 동일하게 모바일 환경임을 증명
                    )
                }
            )

        } catch (e: Exception) {
            e.printStackTrace()
        }

        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try {
            val baseUri = java.net.URI(baseUrl)
            val resolved = baseUri.resolve(relativeUrl)
            resolved.toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}
