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
            
            // [우회 반영] 큰따옴표, 작은따옴표, 따옴표가 없는 비규격 주소까지 모두 추출하는 통합 수집기
            val iframeMatches = Regex("""<iframe\s+[^>]*src=(?:["']([^"']+)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }
                }
                .distinct()
                .toList()
            
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = url
            iframeHeaders["Origin"] = entryOrigin

            // 수집된 iframe들을 순회하며 재귀적으로 내부 검사합니다.
            for (rawSrc in iframeMatches) {
                var iframeUrl = rawSrc
                if (rawSrc.startsWith("//")) {
                    iframeUrl = "https:$rawSrc"
                } else if (rawSrc.startsWith("/")) {
                    iframeUrl = "$entryOrigin$rawSrc"
                } else if (!rawSrc.startsWith("http")) {
                    iframeUrl = "$entryOrigin/${rawSrc.trimStart('/')}"
                }
                
                if (iframeUrl.endsWith(".js") || iframeUrl.endsWith(".css") || iframeUrl.contains("tag.min")) continue
                
                // depth = 1 단계부터 재귀 탐색 시작
                val link = crawlIframe(iframeUrl, iframeHeaders, 1, sourceName)
                if (link != null) return link
            }

            debugLog("알림: 수집된 모든 iframe 내부에서 진짜 스트림 주소를 획득하지 못했습니다.")
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        } catch (e: Exception) {
            debugLog("오류: ${e.message}")
            return null
        }
    }

    // 중첩된 가짜 페이지 및 다중 iframe 깊이를 재귀적으로 파고들며 wiki.js를 탐색하는 핵심 유닛
    private suspend fun crawlIframe(iframeUrl: String, iframeHeaders: Map<String, String>, depth: Int, sourceName: String): ExtractorLink? {
        if (depth > 3) return null // 무한 루프 방지용 깊이 제한
        
        try {
            debugLog("[$depth 단계] Iframe 로드 검사 중 -> $iframeUrl")
            val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
            if (!iframeResp.isSuccessful) return null
            val iframeHtml = iframeResp.text
            
            // 1. 해당 HTML 본문 내에 wiki.js가 탑재되어 있는지 확인
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
                    
                    // 쪼개진 문자 배열 파싱 및 복구
                    val arrayMatch = Regex("""\["h"\s*,\s*"t"\s*,\s*"t"\s*,\s*"p"\s*,\s*"s"\s*,[^\]]+\]""").find(playerHtml)
                    if (arrayMatch != null) {
                        var streamUrl = arrayMatch.groupValues[0]
                            .replace("[", "")
                            .replace("]", "")
                            .replace("\"", "")
                            .replace("\\/", "/")
                            .replace(",", "")
                            .trim()
                        
                        // getElementById로 지정된 동적 span 태그 내의 추가 토큰값을 결합
                        val idMatch = Regex("""document\.getElementById\(["']([^"']+)["']\)""").find(playerHtml)?.groupValues?.get(1)
                        val spanMatch = if (idMatch != null) {
                            Regex("""id=["']?$idMatch["']?[^>]*>([^<]*)""").find(playerHtml)?.groupValues?.get(1) ?: ""
                        } else {
                            ""
                        }
                        streamUrl += spanMatch

                        if (streamUrl.startsWith("http")) {
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
                            return ExtractorLink(
                                source = sourceName, name = sourceName, url = streamUrl,
                                referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                                headers = finalHeaders
                            )
                        }
                    }
                }
            }

            // 2. 만약 해당 페이지에 wiki.js가 없고 또 다른 iframe들이 존재하면 재귀적으로 진입하여 검사합니다.
            val nestedIframeMatches = Regex("""<iframe\s+[^>]*src=(?:["']([^"']+)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .findAll(iframeHtml)
                .map { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }
                }
                .distinct()
                .toList()
                
            for (nestedRawSrc in nestedIframeMatches) {
                var nestedIframeUrl = nestedRawSrc
                val parsedParent = URL(iframeUrl)
                val parentOrigin = "${parsedParent.protocol}://${parsedParent.host}"
                
                // 상대 경로 복정 처리 수행
                if (nestedRawSrc.startsWith("//")) {
                    nestedIframeUrl = "https:$nestedRawSrc"
                } else if (nestedRawSrc.startsWith("/")) {
                    nestedIframeUrl = "$parentOrigin$nestedRawSrc"
                } else if (!nestedRawSrc.startsWith("http")) {
                    nestedIframeUrl = "$parentOrigin/${nestedRawSrc.trimStart('/')}"
                }
                
                if (nestedIframeUrl.endsWith(".js") || nestedIframeUrl.endsWith(".css") || nestedIframeUrl.contains("tag.min")) continue
                
                // 더 깊은 단계로 재귀 탐색 진행
                val link = crawlIframe(nestedIframeUrl, iframeHeaders, depth + 1, sourceName)
                if (link != null) return link
            }
            
            // Popcdn 분기 지원
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
                val popResp = app.get(targetUrl, headers = iframeHeaders)
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
        } catch (e: Exception) {
            debugLog("crawlIframe depth=$depth 에서 예외 발생: ${e.message}")
        }
        return null
    }
}
