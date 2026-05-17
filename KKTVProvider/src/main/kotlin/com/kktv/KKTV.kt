package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "CUTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            println("DEBUG [CUTV] 1. 메인 페이지 로드 시작: $url")
            val doc = app.get(url, headers = defaultHeaders).document
            
            // [핵심 수정] 하드코딩 제거. 
            // 페이지 내의 모든 iframe 중 'galaxy-stream', 'hudtv', 'cli/mobile'이 포함된 진짜 주소만 동적으로 찾습니다.
            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli/mobile") }

            if (iframeSrc.isNullOrBlank()) {
                // 하드코딩된 예비 주소로 가지 않고, 깔끔하게 에러 처리하여 구조 변경에 대응합니다.
                println("DEBUG [CUTV] 🚨 메인 페이지에서 Iframe을 찾지 못했습니다. 사이트 구조가 변경되었습니다.")
                return newHomePageResponse(request.name, emptyList())
            }
            
            println("DEBUG [CUTV] 2. 동적으로 추출된 Iframe 주소: $iframeSrc")

            // Iframe 호출 (500 에러 방지를 위해 Referer 필수)
            println("DEBUG [CUTV] 3. Iframe 페이지 로드 시작 (Referer: $url)")
            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            val iframeDoc = Jsoup.parse(iframeHtml)
            
            val rows = iframeDoc.select("tr.ssc-row-candy")
            println("DEBUG [CUTV] 4. 파싱된 경기 개수: ${rows.size}")

            if (rows.isNotEmpty()) {
                rows.forEach { row ->
                    val time = row.selectFirst("td.ssc-schedule-td")?.text()?.trim() ?: ""
                    val league = row.selectFirst("td.ssc-league-td")?.text()?.trim() ?: ""
                    val homeTeam = row.selectFirst("span.ssc-title-team-1-text")?.text()?.trim() ?: ""
                    val awayTeam = row.selectFirst("span.ssc-title-team-2-text")?.text()?.trim() ?: ""
                    
                    if (homeTeam.isNotBlank()) {
                        val title = "[$league] $homeTeam vs $awayTeam ($time)"
                        val posterUrl = row.selectFirst("img.ssc-thumbnail-icon")?.attr("src") ?: ""

                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedPoster = URLEncoder.encode(posterUrl, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        
                        val fakeUrl = "$mainUrl/live_stream/dummy?title=$encodedTitle&poster=$encodedPoster&iframe=$encodedIframe"

                        items.add(
                            newLiveSearchResponse(
                                name = title,
                                url = fakeUrl
                            ) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
            } else {
                println("DEBUG [CUTV] 🚨 경기 목록이 없습니다. HTML 확인: ${iframeHtml.take(300)}")
            }
        } catch (e: Exception) {
            println("DEBUG [CUTV] 🚨 에러 발생 (getMainPage): ${e.message}")
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawPoster = uri.getQueryParameter("poster")
        val poster = if (!rawPoster.isNullOrBlank()) URLDecoder.decode(rawPoster, "UTF-8") else null

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        try {
            // 영상 링크를 딸 때도 500 에러 방지를 위해 Referer를 강제합니다.
            val responseText = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
            // [정규식 설명] http 또는 https로 시작하고 .m3u8을 포함하는 모든 문자열을 추출합니다.
            // lux1.hudtv01.com, coupangstreaming.com 등 도메인에 상관없이 모두 완벽하게 잡아냅니다.
            val m3u8Regex = """(https?://[^"'\s]+?\.m3u8[^"'\s]*)""".toRegex()
            val streamLinks = m3u8Regex.findAll(responseText).map { it.value }.toSet().toList()

            streamLinks.forEachIndexed { index, link ->
                val cleanLink = link.replace("\\/", "/") 
                val qualityName = if (cleanLink.contains("coupangstreaming")) "채널 1 (HD)" 
                                  else if (cleanLink.contains("hudtv")) "채널 2 (FHD)" 
                                  else "채널 ${index + 1}"

                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = qualityName,
                        url = cleanLink,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = iframeUrl
                    }
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
}
