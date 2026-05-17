package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "씨유티비 (CUTV)"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36"

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
            
            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli") }

            if (iframeSrc.isNullOrBlank()) {
                println("DEBUG [CUTV] 🚨 메인 페이지에서 Iframe을 찾지 못했습니다.")
                return newHomePageResponse(request.name, emptyList())
            }
            
            println("DEBUG [CUTV] 2. 동적으로 추출된 Iframe 주소: $iframeSrc")

            // Iframe 호출 (Referer 필수)
            var iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            var iframeDoc = Jsoup.parse(iframeHtml)
            
            var rows = iframeDoc.select("tr.ssc-row-candy")
            
            // [핵심 로직] cli/box 에서 목록을 못 찾으면 cli/mobile 로 주소를 바꿔서 즉시 재시도합니다.
            if (rows.isEmpty()) {
                val altSrc = if (iframeSrc.contains("cli/box")) iframeSrc.replace("cli/box", "cli/mobile") else iframeSrc.replace("cli/mobile", "cli/box")
                println("DEBUG [CUTV] 3. 목록을 찾지 못했습니다. 대체 주소로 재시도합니다: $altSrc")
                
                iframeHtml = app.get(altSrc, headers = defaultHeaders, referer = url).text
                iframeDoc = Jsoup.parse(iframeHtml)
                rows = iframeDoc.select("tr.ssc-row-candy")
            }

            println("DEBUG [CUTV] 4. 최종 파싱된 경기 개수: ${rows.size}")

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
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                            }
                        )
                    }
                }
            } else {
                // [최후의 수단] 이래도 안 뜨면 서버가 뭘 숨기고 있는지 전체 HTML을 로그캣에 낱낱이 출력합니다.
                println("DEBUG [CUTV] 🚨 경기 목록 파싱 완전 실패. 서버가 반환한 HTML 전체를 덤프합니다.")
                iframeHtml.chunked(3000).forEachIndexed { index, chunk ->
                    println("DEBUG [CUTV] HTML_DUMP_$index: $chunk")
                }
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
            val responseText = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
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
