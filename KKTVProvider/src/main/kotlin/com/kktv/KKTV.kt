// v7.11 - ExoPlayer 2004(403 Forbidden) 차단 해결을 위한 Referer 원복 및 다중 플레이어 엔드포인트 순회 로직 추가
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
            println("DEBUG [KingkongTv] v7.11: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.11: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.11: 최신 주소 확인 중 오류 발생 - ${e.message}")
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
        
        println("DEBUG [KingkongTv] v7.11: === [STAGE 1] 링크 로드 시작 ===")
        
        val baseDomain = try {
            val u = java.net.URI(refererUrl)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://kktv.speed10-1.com"
        }

        // [수정 포인트 v7.11] 운영자가 기존 플레이어 경로를 삭제하여 404 에러가 나는 것을 회피하기 위해 다중 엔드포인트 파일명 순회
        val endpoints = listOf("pc_view.php", "ch_view.php", "view.php", "player.php")
        var m3u8Url: String? = null

        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        for (endpoint in endpoints) {
            val playerPageUrl = if (refererUrl.contains("index.php")) {
                refererUrl.replace("index.php", endpoint).substringBefore("?") + "?stream=$streamId"
            } else {
                "$baseDomain/kktv/$endpoint?stream=$streamId"
            }

            println("DEBUG [KingkongTv] v7.11: 타겟 Player URL 시도 = $playerPageUrl")

            try {
                val responseResponse = app.get(playerPageUrl, headers = headers)
                val response = responseResponse.text
                
                println("DEBUG [KingkongTv] v7.11: HTTP 상태 코드 = ${responseResponse.code}")
                
                // File not found(404)나 데이터가 빈 경우 다음 파일명으로 패스
                if (responseResponse.code != 200 || response.contains("File not found", ignoreCase = true) || response.length < 50) {
                    println("DEBUG [KingkongTv] v7.11: $endpoint 경로가 유효하지 않음 (404 또는 빈 페이지). 다음 엔드포인트 탐색")
                    continue
                }

                println("DEBUG [KingkongTv] v7.11: $endpoint 응답 성공. 정규식 추출 시작")

                m3u8Url = Regex("""(?i)(?:source|file|url|src|stream)\s*[:=]\s*['"](https?:[\\/]+[^'"]+)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (m3u8Url == null) {
                    val b64Match = Regex("""['"](aHR0cHM6Ly9[^'"]+|aHR0cDovLz[^'"]+)['"]""").find(response)?.groupValues?.get(1)
                    if (b64Match != null) {
                        try {
                            m3u8Url = String(Base64.decode(b64Match, Base64.DEFAULT)).replace("\\/", "/")
                        } catch (e: Exception) { }
                    }
                }
                
                if (m3u8Url == null) m3u8Url = Regex("""['"](https?:[\\/]+[^'"]+(?:\.m3u8|$streamId)[^'"]*)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                
                if (m3u8Url == null) {
                    val webrtcMatch = Regex("""['"](webrtc:[\\/]+[^'"]+)['"]""").find(response)?.groupValues?.get(1)?.replace("\\/", "/")
                    if (webrtcMatch != null) m3u8Url = webrtcMatch.replace("webrtc://", "https://").replace(streamId, "$streamId.m3u8") 
                }

                if (m3u8Url != null) {
                    println("DEBUG [KingkongTv] v7.11: $endpoint 경로에서 성공적으로 스트림 추출 완료 - $m3u8Url")
                    break
                }
            } catch (e: Exception) {
                println("DEBUG [KingkongTv] v7.11: $endpoint 요청 에러 - ${e.message}")
            }
        }

        // 모든 파일 경로에서도 찾지 못했을 경우 썸네일 기반 추론 적용 (최후의 수단)
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
            println("DEBUG [KingkongTv] v7.11: 모든 엔드포인트 추출 실패. 가설 백업 도메인 할당 - $m3u8Url")
        }

        println("DEBUG [KingkongTv] v7.11: 최종 확정 M3U8 URL = $m3u8Url")

        // [수정 포인트 v7.11] ExoPlayer 2004(403 Forbidden) 차단을 풀기 위해, 이전 버전의 오류였던 '영상 서버의 도메인'을 던지는 것이 아니라 본래 포털의 도메인(baseDomain)으로 Referer를 단단하게 고정합니다.
        try {
            if (m3u8Url != null && m3u8Url!!.contains("play.ogtv") && m3u8Url!!.startsWith("https://")) {
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTPS)", url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to baseDomain, "Referer" to "$baseDomain/", "User-Agent" to userAgent)
                    }
                )
                
                val bypassedUrl = m3u8Url!!.replaceFirst("https://", "http://")
                callback.invoke(
                    newExtractorLink(source = name, name = "$name (HTTP 우회)", url = bypassedUrl, type = ExtractorLinkType.M3U8) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to baseDomain, "Referer" to "$baseDomain/", "User-Agent" to userAgent)
                    }
                )
            } else {
                callback.invoke(
                    newExtractorLink(source = name, name = name, url = m3u8Url!!, type = ExtractorLinkType.M3U8) {
                        this.referer = "$baseDomain/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf("Origin" to baseDomain, "Referer" to "$baseDomain/", "User-Agent" to userAgent)
                    }
                )
            }
            println("DEBUG [KingkongTv] v7.11: === 플레이어에 링크 인계 성공 ===")
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.11: === [ERROR] 치명적 에러 발생 ===")
            println("DEBUG [KingkongTv] v7.11: 에러 메시지: ${e.message}")
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
