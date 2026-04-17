// v7.14 - 브라우저 재생 환경 동기화: 상세 Referer 위장 및 세션 쿠키 유지 로직 적용
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

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    private suspend fun updateMainUrlIfNeeded() {
        try {
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            if (!latestUrl.isNullOrBlank()) {
                mainUrl = latestUrl.trimEnd('/')
            }
        } catch (e: Exception) { }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame, iframe[src*='index.php']")
            val iframeSrc = iframeElement?.attr("src") ?: "https://kktv.speed10-1.com/kktv/index.php"
            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            val home = rows.mapNotNull { row ->
                if (row.selectFirst("span.off") != null) return@mapNotNull null
                val channelDiv = row.selectFirst("div.channel_on") ?: return@mapNotNull null
                val title = channelDiv.attr("data-title").let { if (row.selectFirst("td.subject")?.hasClass("bold") == true) "🔥 $it" else it }
                val streamId = channelDiv.attr("data-stream")
                val thumbUrlRaw = row.selectFirst("td.thumb img")?.attr("src") ?: ""
                val thumbUrl = fixUrl(realUrl, thumbUrlRaw)

                val fakeUrl = "$mainUrl/live_stream/$streamId?title=${URLEncoder.encode(title, "UTF-8")}&poster=${URLEncoder.encode(thumbUrl, "UTF-8")}&ref=${URLEncoder.encode(realUrl, "UTF-8")}"
                newMovieSearchResponse(title, fakeUrl, TvType.Others) { this.posterUrl = thumbUrl }
            }

            return newHomePageResponse(request.name, home)
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList())
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val uri = Uri.parse(url)
        val title = URLDecoder.decode(uri.getQueryParameter("title") ?: "Live", "UTF-8")
        val poster = URLDecoder.decode(uri.getQueryParameter("poster") ?: "", "UTF-8")
        return newMovieLoadResponse(title, url, TvType.Others, url) { this.posterUrl = poster }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val streamId = data.substringAfter("/live_stream/").substringBefore("?")
        val refererUrl = URLDecoder.decode(uri.getQueryParameter("ref") ?: "", "UTF-8")
        
        // 1. 실제 영상이 담긴 pc_view.php의 '정확한 전체 URL' 생성
        val baseDomain = try { val u = java.net.URI(refererUrl); "${u.scheme}://${u.host}" } catch (e: Exception) { "https://kktv.speed10-1.com" }
        val playerPageUrl = "$baseDomain/kktv/pc_view.php?stream=$streamId"

        println("DEBUG [KingkongTv] v7.14: 브라우저 환경 복제 시도 - $playerPageUrl")

        try {
            // 2. 페이지 요청 시 세션 유지 및 Referer 정밀화
            val response = app.get(playerPageUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to refererUrl)).text
            
            // 3. m3u8 주소 추출
            val m3u8Url = Regex("""['"](https?:[\\/]+[^'"]+\.m3u8[^'"]*)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            
            if (m3u8Url != null) {
                println("DEBUG [KingkongTv] v7.14: 추출 성공 - $m3u8Url")
                
                // 4. [핵심] ExoPlayer에 던질 때 Referer를 '사이트 메인'이 아닌 '플레이어 페이지 전체 주소'로 전달
                callback.invoke(
                    newExtractorLink(source = name, name = "$name Live", url = m3u8Url, type = ExtractorLinkType.M3U8) {
                        this.referer = playerPageUrl
                        this.headers = mapOf(
                            "User-Agent" to userAgent,
                            "Referer" to playerPageUrl,
                            "Origin" to baseDomain,
                            "Accept" to "*/*"
                        )
                    }
                )
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.14: 오류 - ${e.message}")
        }
        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try { java.net.URI(baseUrl).resolve(relativeUrl).toString() } catch (e: Exception) { relativeUrl }
    }
}
