package com.hsp1020

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    private fun debugLog(message: String) {
        Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
        println("[DDL-DEBUG] $message")
    }

    private fun printLargeLog(tag: String, message: String) {
        var i = 0
        while (i < message.length) {
            val end = (i + 3500).coerceAtMost(message.length)
            Log.d(tag, "[BODY-CHUNK] ${message.substring(i, end)}")
            i += 3500
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = try {
            mapper.readValue(url, object : TypeReference<List<Pair<String, String>>>() {})
        } catch (e: Exception) {
            null
        }
        
        if (links == null) {
            val link = extractVideo(url, this.name)
            if (link != null) callback(link)
            return
        }

        val groupedLinks = links.groupBy { it.first.substringBefore(" - ") }

        groupedLinks.forEach { (channelName, playerPairs) ->
            debugLog("[$channelName] 채널의 스트림 추출 시작 (총 ${playerPairs.size}개 후보)")
            
            for (playerPair in playerPairs) {
                val sourceName = playerPair.first 
                val streamUrl = playerPair.second

                val link = extractVideo(streamUrl, sourceName)
                if (link != null) {
                    callback(link)
                    debugLog("[$channelName] 성공적으로 추출 완료. 해당 채널의 나머지 백업 플레이어 탐색 중지.")
                    break 
                }
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        try {
            debugLog("================= [DDL EXTRACT START] =================")
            debugLog("1. 소스 정보 -> Name: $sourceName, Target URL: $url")
            
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            val resp = app.get(url, headers = headers)
            debugLog("2. 플레이어 페이지 응답 코드: ${resp.code}")
            if (!resp.isSuccessful) return null
            val html = resp.text
            
            // 모든 iframe 주소를 수집합니다.
            val iframeMatches = Regex("""<iframe\s+[^>]*src=["']((?:https?:)?//[^"']+)["']""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { it.groupValues[1] }
                .distinct()
                .toList()
            
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = url
            iframeHeaders["Origin"] = entryOrigin

            // 수집된 iframe들을 순회하며 내부 검사합니다.
            for (rawSrc in iframeMatches) {
                val iframeUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
                
                // 광고 자바스크립트나 스타일시트 등 명백한 비-웹페이지 파일은 패스합니다.
                if (iframeUrl.endsWith(".js") || iframeUrl.endsWith(".css") || iframeUrl.contains("tag.min")) continue
                
                debugLog("Iframe 로드 및 wiki.js 탑재 여부 검사 중 -> $iframeUrl")
                val iframeHtml = app.get(iframeUrl, headers = iframeHeaders).text
                
                // 수집된 iframe 내부에서 wiki.js가 포함되어 있는지 검증합니다.
                val wikiJsMatch = Regex("""src=["'](https?://[^"']+|//[^"']+)wiki\.js""").find(iframeHtml)
                if (wikiJsMatch != null) {
                    var rawWikiHost = wikiJsMatch.groupValues[1]
                    if (rawWikiHost.startsWith("//")) {
                        rawWikiHost = "https:$rawWikiHost"
                    }
                    val igniteHost = rawWikiHost.trimEnd('/')
                    
                    val parsedIframe = URL(iframeUrl)
                    val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                    debugLog("[IGNITE-우회] 진짜 중계 플레이어 감지! 호스트: $igniteHost, pageName: $pageName")

                    // 진짜 영상 토큰이 탑재될 플레이어 페이지 호출
                    val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$pageName"
                    val playerHeaders = mapOf(
                        "User-Agent" to userAgent,
                        "Referer" to iframeUrl,
                        "Origin" to "${parsedIframe.protocol}://${parsedIframe.host}"
                    )
                    
                    debugLog("[IGNITE-DEBUG] 진짜 플레이어 페이지 요청 -> $playerUrl")
                    val playerResp = app.get(playerUrl, headers = playerHeaders)
                    val playerHtml = playerResp.text
                    
                    // [중요 디버깅 로그 추가]
                    // wiki.php가 실제로 돌려준 웹방화벽 상태, 헤더 구조, 바디 전체를 출력합니다.
                    debugLog("[IGNITE-DEBUG] wiki.php 응답 코드: ${playerResp.code}")
                    debugLog("[IGNITE-DEBUG] wiki.php 응답 헤더: ${playerResp.headers}")
                    debugLog("[IGNITE-DEBUG] wiki.php 바디 길이: ${playerHtml.length}")
                    printLargeLog("IGNITE-BODY", playerHtml)

                    // 혹시 플레이어 페이지 본문 내부에도 atob 구문이 존재하는지 미리 매칭해 봅니다.
                    val atobInPlayer = Regex("""atob\(['"](.*?)['"]\)""").findAll(playerHtml).map { it.groupValues[1] }.toList()
                    debugLog("[IGNITE-DEBUG] 플레이어 본문 내 발견된 atob 목록: $atobInPlayer")

                    val streamUrl = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)?.replace("\\", "")
                    
                    if (!streamUrl.isNullOrEmpty()) {
                        debugLog("[IGNITE-우회] 진짜 m3u8 주소 추출 성공 -> $streamUrl")
                        
                        val finalHeaders = mapOf(
                            "Referer" to "$igniteHost/",
                            "Origin" to igniteHost,
                            "User-Agent" to userAgent,
                            "Accept" to "*/*",
                            "X-Direct-Connection" to "1",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "same-site"
                        )
                        debugLog("================= [DDL EXTRACT END] =================")
                        return ExtractorLink(
                            source = sourceName, name = sourceName, url = streamUrl,
                            referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                            headers = finalHeaders
                        )
                    } else {
                        debugLog("[IGNITE-DEBUG] m3u8 단순 문자열 매칭 실패. 수집된 atob 디코딩 자가 분석 가동.")
                        for (b64 in atobInPlayer) {
                            try {
                                val decoded = base64Decode(b64).replace("\"", "").trim()
                                if (decoded.contains(".m3u8")) {
                                    debugLog("[IGNITE-우회] 자가 역디코딩 발견 및 성공 -> $decoded")
                                    val finalHeaders = mapOf(
                                        "Referer" to "$igniteHost/",
                                        "Origin" to igniteHost,
                                        "User-Agent" to userAgent,
                                        "Accept" to "*/*",
                                        "X-Direct-Connection" to "1",
                                        "Sec-Fetch-Dest" to "empty",
                                        "Sec-Fetch-Mode" to "cors",
                                        "Sec-Fetch-Site" to "same-site"
                                    )
                                    return ExtractorLink(
                                        source = sourceName, name = sourceName, url = decoded,
                                        referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                                        headers = finalHeaders
                                    )
                                }
                            } catch (e: Exception) {}
                        }
                    }
                }
                
                // Freeshot/Popcdn 분기
                if (iframeUrl.contains("freeshot.live") || iframeUrl.contains("popcdn.day")) {
                    debugLog("Freeshot / Popcdn 분기 감지")
                    var channelCode = ""
                    val embedMatch = Regex("""embed/([^/.]+)\.php""").find(iframeUrl)
                    if (embedMatch != null) {
                        channelCode = embedMatch.groupValues[1]
                    } else {
                        channelCode = iframeUrl.split("/").last().split("?").first()
                    }
                    
                    val targetUrl = "https://popcdn.day/player/$channelCode"
                    val popResp = app.get(targetUrl, headers = headers)
                    val popBody = popResp.text
                    
                    var finalM3u8 = ""
                    val streamUrlMatch = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(popBody)
                    if (streamUrlMatch != null) {
                        finalM3u8 = streamUrlMatch.groupValues[1].replace("\\", "")
                    } else {
                        val tokenMatch = Regex("""token=([^&"']+)""").find(popBody)
                        if (tokenMatch != null) {
                            val token = tokenMatch.groupValues[1]
                            finalM3u8 = "https://planetary.lovecdn.ru/$channelCode/tracks-v1a1/mono.m3u8?token=$token"
                        }
                    }
                    
                    if (finalM3u8.isNotEmpty()) {
                        debugLog("Freeshot 추출 성공 -> $finalM3u8")
                        return ExtractorLink(
                            source = sourceName, name = "Freeshot Server", url = finalM3u8,
                            referer = "https://popcdn.day/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                            headers = mapOf(
                                "Referer" to "https://popcdn.day/", "Origin" to "https://popcdn.day", "User-Agent" to userAgent
                            )
                        )
                    }
                }
            }

            debugLog("알림: 수집된 모든 iframe 내부에서 진짜 스트림 주소를 획득하지 못했습니다.")
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        } catch (e: Exception) {
            debugLog("오류: ${e.message}")
            return null
        }
    }
}
