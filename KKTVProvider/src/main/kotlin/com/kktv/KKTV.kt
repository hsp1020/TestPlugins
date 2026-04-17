// v7.10 - 스트림 도메인 Base64/JSON 이스케이프 범용 정규식 추가 및 단계별 정밀 디버깅 로그 적용
package com.KingkongTv

import android.net.Uri
import android.util.Base64
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
    
    // [기존 로직 보존] TvType.Others 유지
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.10: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.10: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.10: 최신 주소 확인 중 오류 발생 - ${e.message}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        updateMainUrlIfNeeded()
        val url = "$mainUrl${request.data}"
        
        try {
            val mainDoc = app.get(url).document
            val iframeElement = mainDoc.selectFirst("iframe#broadcastFrame, iframe[src*='index.php'], iframe[src*='kktv']")
            var iframeSrc = iframeElement?.attr("src")

            if (iframeSrc.isNullOrBlank()) {
                iframeSrc = "https://kktv.speed10-1.com/kktv/index.php?tg=1ch&ca=0"
            }

            val realUrl = fixUrl(url, iframeSrc)
            val contentDoc = app.get(realUrl, referer = url).document
            val rows = contentDoc.select("div.sports_on_list table tbody tr")

            val sortedRows = rows.sortedByDescending { row ->
                row.selectFirst("td.subject")?.hasClass("bold") == true
            }

            val home = sortedRows.mapNotNull { row ->
                row.toSearchResult(realUrl)
            }

            return newHomePageResponse(
                list = HomePageList(name = request.name, list = home, isHorizontalImages = true),
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
        
        println("DEBUG [KingkongTv] v7.10: === [STAGE 1] 링크 로드 파라미터 초기화 ===")
        println("DEBUG [KingkongTv] v7.10: Stream ID = $streamId")
        println("DEBUG [KingkongTv] v7.10: Referer = $refererUrl")

        val playerPageUrl = if (refererUrl.contains("index.php")) {
            refererUrl.replace("index.php", "pc_view.php").substringBefore("?") + "?stream=$streamId"
        } else {
            try {
                val baseUri = java.net.URI(refererUrl)
                "${baseUri.scheme}://${baseUri.host}/kktv/pc_view.php?stream=$streamId"
            } catch (e: Exception) {
                "https://kktv.speed10-1.com/kktv/pc_view.php?stream=$streamId"
            }
        }
        
        val baseDomain = try {
            val u = java.net.URI(playerPageUrl)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://kktv.speed10-1.com"
        }

        println("DEBUG [KingkongTv] v7.10: 타겟 Player URL = $playerPageUrl")

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        try {
            println("DEBUG [KingkongTv] v7.10: === [STAGE 2] Player 페이지 네트워크(Network) 요청 시작 ===")
            val responseResponse = app.get(playerPageUrl, headers = headers)
            val response = responseResponse.text
            
            println("DEBUG [KingkongTv] v7.10: HTTP 상태 코드 = ${responseResponse.code}")
            println("DEBUG [KingkongTv] v7.10: 응답 데이터 길이 = ${response.length} bytes")
            
            val debugText = if (response.length > 800) response.take(400) + "\n...[중략]...\n" + response.takeLast(400) else response
            println("DEBUG [KingkongTv] v7.10: [응답 본문 프리뷰 시작]\n$debugText\n[응답 본문 프리뷰 종료]")
            
            var m3u8Url: String? = null

            println("DEBUG [KingkongTv] v7.10: === [STAGE 3] 정규식 패턴 매칭 시작 ===")
            
            // 1. JSON 이스케이프 문자열(\/)까지 고려한 명시적 패턴 탐색
            m3u8Url = Regex("""(?i)(?:source|file|url|src|stream)\s*[:=]\s*['"](https?:[\\/]+[^'"]+)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
            if (m3u8Url != null) println("DEBUG [KingkongTv] v7.10: 패턴 1 (명시적 속성) 성공 - $m3u8Url")

            // 2. Base64 인코딩 패턴 탐색
            if (m3u8Url == null) {
                val b64Match = Regex("""['"](aHR0cHM6Ly9[^'"]+|aHR0cDovLz[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (b64Match != null) {
                    try {
                        m3u8Url = String(Base64.decode(b64Match, Base64.DEFAULT)).replace("\\/", "/")
                        println("DEBUG [KingkongTv] v7.10: 패턴 2 (Base64 디코딩) 성공 - $m3u8Url")
                    } catch (e: Exception) {
                        println("DEBUG [KingkongTv] v7.10: 패턴 2 Base64 디코딩 실패")
                    }
                }
            }

            // 3. 확장자가 .m3u8 이거나 streamId를 포함하는 모든 http 링크 탐색 (가장 포괄적)
            if (m3u8Url == null) {
                m3u8Url = Regex("""['"](https?:[\\/]+[^'"]+(?:\.m3u8|$streamId)[^'"]*)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                if (m3u8Url != null) println("DEBUG [KingkongTv] v7.10: 패턴 3 (포괄적 탐색) 성공 - $m3u8Url")
            }

            // 4. WebRTC 변환 로직
            if (m3u8Url == null) {
                val webrtcMatch = Regex("""['"](webrtc:[\\/]+[^'"]+)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                if (webrtcMatch != null) {
                    m3u8Url = webrtcMatch.replace("webrtc://", "https://").replace(streamId, "$streamId.m3u8") 
                    println("DEBUG [KingkongTv] v7.10: 패턴 4 (WebRTC) 성공 - $m3u8Url")
                }
            }

            // 5. 중첩 iframe 플레이어 재탐색
            if (m3u8Url == null) {
                val iframeSrc = Regex("""<iframe[^>]+src=['"]([^'"]+)['"]""").find(response)?.groupValues?.get(1)
                if (iframeSrc != null) {
                    val realIframeSrc = fixUrl(playerPageUrl, iframeSrc).replace("&amp;", "&")
                    println("DEBUG [KingkongTv] v7.10: 패턴 5 중첩 iframe 발견, 2차 탐색 시도 - $realIframeSrc")
                    try {
                        val iframeResponse = app.get(realIframeSrc, headers = headers).text
                        println("DEBUG [KingkongTv] v7.10: 2차 iframe 응답 길이 = ${iframeResponse.length} bytes")
                        m3u8Url = Regex("""['"](https?:[\\/]+[^'"]+\.m3u8[^'"]*)['"]""").find(iframeResponse)?.groupValues?.get(1)?.replace("\\/", "/")
                        if (m3u8Url != null) println("DEBUG [KingkongTv] v7.10: 패턴 5 (중첩 iframe 내부) 성공 - $m3u8Url")
                    } catch (e: Exception) { 
                        println("DEBUG [KingkongTv] v7.10: 2차 iframe 요청 중 예외 발생 - ${e.message}")
                    }
                }
            }

            // 6. 최후의 수단 (이전 버전의 타임아웃 주범이었으나 안전망으로 최소한만 남김)
            if (m3u8Url == null) {
                var ogtvDomain = "play.ogtv3.com"
                val rawPoster = uri.getQueryParameter("poster")
                if (!rawPoster.isNullOrBlank()) {
                    val decodedPoster = URLDecoder.decode(rawPoster, "UTF-8")
                    val match = Regex("""ogtv(\d+)""").find(decodedPoster)
                    if (match != null) {
                        ogtvDomain = "play.ogtv${match.groupValues[1]}.com"
                    }
                }
                m3u8Url = "https://$ogtvDomain/live/$streamId.m3u8?site=kktv"
                println("DEBUG [KingkongTv] v7.10: 강력 경고! 모든 추출 실패. 가설 백업 도메인 할당 - $m3u8Url")
            }

            println("DEBUG [KingkongTv] v7.10: === [STAGE 4] 최종 추출 완료 및 ExtractorLink 생성 ===")
            println("DEBUG [KingkongTv] v7.10: 최종 확정 M3U8 URL = $m3u8Url")

            // 도메인 추출 후 헤더용 Host 동적 생성
            val streamDomain = try {
                val u = java.net.URI(m3u8Url!!)
                "${u.scheme}://${u.host}"
            } catch (e: Exception) { baseDomain }

            // [기존 로직 보존 v7.10] HTTPS/HTTP 동시 제공
            if (m3u8Url != null && m3u8Url!!.contains("play.ogtv") && m3u8Url!!.startsWith("https://")) {
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTPS)", url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = "$streamDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to streamDomain, "Referer" to "$streamDomain/", "User-Agent" to userAgent)
                    }
                )
                
                val bypassedUrl = m3u8Url!!.replaceFirst("https://", "http://")
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTP 우회)", url = bypassedUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$streamDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to streamDomain, "Referer" to "$streamDomain/", "User-Agent" to userAgent)
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = "$streamDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to streamDomain, "Referer" to "$streamDomain/", "User-Agent" to userAgent)
                    }
                )
            }
            println("DEBUG [KingkongTv] v7.10: === 모든 처리 정상 종료 ===")

        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.10: === [ERROR] 치명적 에러 발생 ===")
            println("DEBUG [KingkongTv] v7.10: 에러 메시지: ${e.message}")
            e.printStackTrace()
        }
        return true
    }

    private fun fixUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http")) return relativeUrl
        return try {
            val baseUri = java.net.URI(baseUrl)
            baseUri.resolve(relativeUrl).toString()
        } catch (e: Exception) {
            relativeUrl
        }
    }
}
