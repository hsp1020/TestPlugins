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
    override val mainUrl = "https://dlhd.st"
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
            if (!resp.isSuccessful) return null
            val html = resp.text
            
            // 1. 본문 내의 모든 iframe 주소를 일괄 수집합니다.
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

            // 2. 수집된 iframe들을 순회하며 내부 검사합니다.
            for (rawSrc in iframeMatches) {
                val iframeUrl = if (rawSrc.startsWith("//")) "https:$rawSrc" else rawSrc
                
                // 광고 자바스크립트나 스타일시트 등 명백한 비-웹페이지 파일은 패스합니다.
                if (iframeUrl.endsWith(".js") || iframeUrl.endsWith(".css") || iframeUrl.contains("tag.min")) continue
                
                debugLog("Iframe 로드 및 wiki.js 탑재 여부 검사 중 -> $iframeUrl")
                val iframeHtml = app.get(iframeUrl, headers = iframeHeaders).text
                
                // [핵심] 수집된 iframe 내부에서 wiki.js가 포함되어 있는지 하드코딩 검증합니다.
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
                    
                    val playerResp = app.get(playerUrl, headers = playerHeaders)
                    if (playerResp.isSuccessful) {
                        val playerHtml = playerResp.text
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
                        }
                    }
                }
                
                // Freeshot/Popcdn은 도메인 자체가 고정이므로 기존 흐름대로 처리합니다.
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
