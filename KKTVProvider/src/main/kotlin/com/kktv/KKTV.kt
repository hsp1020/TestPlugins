// v8.6 - 로그캣 분석 기반 확정안: 403 차단 회피 및 저지연 재생을 위한 HTTP-FLV(.flv) 프로토콜 우회 로직 적용
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
        println("KKTV_DEBUG [Phase 1] Target Player URL: $playerPageUrl")
        
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to refererUrl,
            "Origin" to "https://kktv.speed10-1.com"
        )

        try {
            val response = app.get(playerPageUrl, headers = headers).text
            println("KKTV_DEBUG [Phase 2] Raw Response Length: ${response.length}")
            
            var flvUrl: String? = null
            var m3u8Url: String? = null
            var extractionMethod = "None"
            
            // 서버가 넘겨준 원본 스트리밍 주소 탐색 (.sdp 또는 .m3u8)
            val streamMatch = Regex("""['"](http[^'"]+\.(?:sdp|m3u8)[^'"]*)['"]""").find(response)?.groupValues?.get(1)
            
            if (streamMatch != null) {
                if (streamMatch.contains(".sdp")) {
                    flvUrl = streamMatch.replace(".sdp", ".flv")
                    m3u8Url = streamMatch.replace(".sdp", ".m3u8")
                    extractionMethod = "[우회] sdp 발견 -> HTTP-FLV 및 M3U8로 치환"
                } else if (streamMatch.contains(".m3u8")) {
                    m3u8Url = streamMatch
                    extractionMethod = "[성공] 네이티브 m3u8 발견"
                }
            } else {
                val webrtcMatch = Regex("""['"](webrtc://[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (webrtcMatch != null) {
                    val baseHttpUrl = webrtcMatch.replace("webrtc://", "https://")
                    flvUrl = baseHttpUrl.replace(streamId, "$streamId.flv")
                    m3u8Url = baseHttpUrl.replace(streamId, "$streamId.m3u8")
                    extractionMethod = "[우회] webrtc 프로토콜 발견 -> HTTP-FLV 및 M3U8로 치환"
                }
            }

            if (flvUrl == null && m3u8Url == null) {
                flvUrl = "https://cdnnowstream.com/cdnnow/$streamId.flv?site=kktv"
                m3u8Url = "https://cdnnowstream.com/cdnnow/$streamId.m3u8?site=kktv"
                extractionMethod = "[실패] 정규식 추출 완전 실패. 하드코딩된 기본값 사용"
            }

            println("KKTV_DEBUG [Result] 추출 방식: $extractionMethod")

            // FLV 콜백 (가장 성공 확률이 높음)
            if (flvUrl != null) {
                val finalFlv = if (flvUrl.contains("play.ogtv3.com") && flvUrl.startsWith("https://")) flvUrl.replaceFirst("https://", "http://") else flvUrl
                println("KKTV_DEBUG [Result] FLV URL (우선순위): $finalFlv")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (FLV - 저지연)",
                        url = finalFlv,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://kktv.speed10-1.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://kktv.speed10-1.com",
                            "Referer" to "https://kktv.speed10-1.com/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            }

            // M3U8 콜백 (서버 단에서 403 차단 가능성 있으나 예비용)
            if (m3u8Url != null) {
                val finalM3u8 = if (m3u8Url.contains("play.ogtv3.com") && m3u8Url.startsWith("https://")) m3u8Url.replaceFirst("https://", "http://") else m3u8Url
                println("KKTV_DEBUG [Result] M3U8 URL (백업용): $finalM3u8")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name (M3U8 - 백업)",
                        url = finalM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://kktv.speed10-1.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "Origin" to "https://kktv.speed10-1.com",
                            "Referer" to "https://kktv.speed10-1.com/",
                            "User-Agent" to userAgent
                        )
                    }
                )
            }

        } catch (e: Exception) {
            println("KKTV_DEBUG [Error] loadLinks 예외 발생: ${e.message}")
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
