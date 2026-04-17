// v7.12 - 하드코딩 추측 방식 폐기 및 index.php 소스코드 실시간 분석을 통한 플레이어 엔드포인트 동적 추출 로직 적용
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
    
    // 기존 로직 보존: TvType.Others 유지
    override val supportedTypes = setOf(TvType.Others)

    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val portalUrl = "https://kingkongtv.org"

    override val mainPage = mainPageOf(
        "/live/sportstv" to "실시간 스포츠 중계",
    )

    private suspend fun updateMainUrlIfNeeded() {
        try {
            println("DEBUG [KingkongTv] v7.12: 포털 사이트($portalUrl)에서 최신 접속 주소 확인 시작")
            val doc = app.get(portalUrl).document
            val latestUrl = doc.selectFirst("a.nav-cta")?.attr("href")
            
            if (!latestUrl.isNullOrBlank()) {
                val cleanedUrl = latestUrl.trimEnd('/')
                if (mainUrl != cleanedUrl) {
                    println("DEBUG [KingkongTv] v7.12: 최신 주소 획득 완료 - $cleanedUrl (이전 주소: $mainUrl)")
                    mainUrl = cleanedUrl
                }
            }
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.12: 최신 주소 확인 중 오류 발생 - ${e.message}")
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

            // [디버깅 방어 로직 v7.12] index.php가 완전히 리뉴얼되어 형식이 바뀌었을 경우를 대비해 첫 번째 경기의 실제 HTML 구조를 로그캣에 강제 출력
            println("DEBUG [KingkongTv] v7.12: [메인페이지] 첫 번째 경기 파싱 HTML 구조 프리뷰 - ${rows.firstOrNull()?.outerHtml()}")

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
        
        println("DEBUG [KingkongTv] v7.12: === [STAGE 1] index.php 실시간 분석 및 엔드포인트 동적 추출 ===")
        
        val headers = mapOf(
            "User-Agent" to userAgent,
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        var dynamicEndpoints = mutableListOf<String>()

        // [수정 포인트 v7.12] 멍청한 파일명 하드코딩 추측을 버리고, 원본 페이지(index.php)의 소스를 분석해 실제 사용 중인 플레이어 파일명을 찾아냅니다.
        try {
            val indexHtml = app.get(refererUrl, headers = headers).text
            
            // 패턴 1: JS 함수 호출부(예: play_video('id', 'new_pc_view.php'))나 변수 할당부에 있는 .php 파일명 추출
            val phpFiles = Regex("""([a-zA-Z0-9_-]+\.php)""").findAll(indexHtml).map { it.groupValues[1] }.toList()
            val filteredPhp = phpFiles.filter { it.lowercase() != "index.php" }
                .filter { it.contains("view") || it.contains("play") || it.contains("live") || it.contains("cast") || it.contains("player") }
            dynamicEndpoints.addAll(filteredPhp)
            
            // 패턴 2: HTML 속성에 대놓고 숨겨진 경로 (예: data-url="ch_view.php?stream=")
            val dataUrls = Regex("""data-[a-zA-Z0-9_-]+=['"]([^'"]+\.php[^'"]*)['"]""").findAll(indexHtml).map { it.groupValues[1] }
            dataUrls.forEach { 
                val fileOnly = it.substringBefore("?").substringAfterLast("/")
                if(fileOnly.isNotBlank() && fileOnly.lowercase() != "index.php") dynamicEndpoints.add(fileOnly) 
            }
            
            println("DEBUG [KingkongTv] v7.12: index.php 소스코드 실시간 분석 성공. 긁어온 실제 사용 파일명 = ${dynamicEndpoints.distinct()}")
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.12: index.php 소스코드 분석 실패 (권한 차단 혹은 네트워크 문제) - ${e.message}")
        }

        // 동적으로 긁어온 진짜 파일명들을 최우선 탐색 대상으로 두고, 혹시 모를 실패를 대비해 기존의 범용 패턴들을 후순위 백업으로만 붙여둡니다.
        val endpoints = (dynamicEndpoints + listOf("pc_view.php", "ch_view.php", "view.php", "player.php")).distinct()
        
        val baseDomain = try {
            val u = java.net.URI(refererUrl)
            "${u.scheme}://${u.host}"
        } catch (e: Exception) {
            "https://kktv.speed10-1.com"
        }

        var m3u8Url: String? = null

        println("DEBUG [KingkongTv] v7.12: === [STAGE 2] 추출된 타겟 파일 순회 탐색 시작 ===")

        for (endpoint in endpoints) {
            val playerPageUrl = if (refererUrl.contains("index.php")) {
                refererUrl.replace("index.php", endpoint).substringBefore("?") + "?stream=$streamId"
            } else {
                "$baseDomain/kktv/$endpoint?stream=$streamId"
            }

            println("DEBUG [KingkongTv] v7.12: 타겟 Player URL 시도 = $playerPageUrl")

            try {
                val responseResponse = app.get(playerPageUrl, headers = headers)
                val response = responseResponse.text
                
                println("DEBUG [KingkongTv] v7.12: $endpoint HTTP 응답 코드 = ${responseResponse.code}")
                
                if (responseResponse.code != 200 || response.contains("File not found", ignoreCase = true) || response.length < 50) {
                    println("DEBUG [KingkongTv] v7.12: $endpoint 는 가짜이거나 삭제된 파일입니다. 다음 후보 탐색 진행.")
                    continue
                }

                println("DEBUG [KingkongTv] v7.12: $endpoint 응답 성공! 내부 영상 스트림 추출 정규식 가동 시작.")

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
                    println("DEBUG [KingkongTv] v7.12: 빙고! $endpoint 경로에서 최종 m3u8 스트림 추출 성공 - $m3u8Url")
                    break
                } else {
                    println("DEBUG [KingkongTv] v7.12: 파일은 찾았으나 내부에서 영상 링크 정규식 매칭 실패. 응답 프리뷰: ${if (response.length > 200) response.take(200) else response}")
                }
            } catch (e: Exception) {
                println("DEBUG [KingkongTv] v7.12: $endpoint 요청 에러 - ${e.message}")
            }
        }

        // 최후의 수단
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
            println("DEBUG [KingkongTv] v7.12: 모든 동적/정적 엔드포인트 추출 실패. 썸네일 기반 가설 도메인 할당 (재생 실패 확률 높음) - $m3u8Url")
        }

        println("DEBUG [KingkongTv] v7.12: 최종 확정 M3U8 URL = $m3u8Url")

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
            println("DEBUG [KingkongTv] v7.12: === 플레이어에 링크 인계 성공 ===")
        } catch (e: Exception) {
            println("DEBUG [KingkongTv] v7.12: === [ERROR] 치명적 에러 발생 ===")
            println("DEBUG [KingkongTv] v7.12: 에러 메시지: ${e.message}")
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
