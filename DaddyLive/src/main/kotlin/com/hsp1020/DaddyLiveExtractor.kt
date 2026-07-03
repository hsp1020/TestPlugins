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
                
                // depth = 1 단계부터 재귀 탐색 시작 (entryOrigin 전달하여 프리미엄 채널 헤더 보장)
                val link = crawlIframe(iframeUrl, iframeHeaders, 1, sourceName, entryOrigin)
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

    // 중첩된 껍데기 페이지 및 다중 iframe 깊이를 재귀적으로 탐색하는 유닛
    private suspend fun crawlIframe(iframeUrl: String, iframeHeaders: Map<String, String>, depth: Int, sourceName: String, entryOrigin: String): ExtractorLink? {
        if (depth > 3) return null // 깊이 제한
        
        try {
            debugLog("[$depth 단계] Iframe 로드 검사 중 -> $iframeUrl")
            val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
            debugLog("[$depth 단계] Iframe 응답 코드: ${iframeResp.code}")
            if (!iframeResp.isSuccessful) return null
            val iframeHtml = iframeResp.text
            
            // 수집된 iframe의 소스 코드를 분할 출력합니다.
            debugLog("[$depth 단계] 본문 크기: ${iframeHtml.length}")
            printLargeLog("DDL-IFRAME-BODY-DEPTH-$depth", iframeHtml)
            
            val parsedIframe = URL(iframeUrl)
            val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"

            // =======================================================
            // [복구 및 가짜 거름 필터] 프리미엄 채널 전용 정석 atob 복구
            // =======================================================
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val decodedUrl = base64Decode(b64Url).replace("\"", "").trim()
                
                // 해독된 주소가 정상적인 외부 절대 경로 CDN 주소(http로 시작)인 경우에만 정식으로 재생 반환
                if (decodedUrl.startsWith("http")) {
                    debugLog("[PREMIUM] 프리미엄 정석 스트림 해독 성공 -> $decodedUrl")
                    
                    val refOrigin = if (entryOrigin.isNotEmpty()) entryOrigin else iframeOrigin
                    return ExtractorLink(
                        source = sourceName, name = sourceName, url = decodedUrl,
                        referer = "$refOrigin/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "Referer" to "$refOrigin/",
                            "Origin" to refOrigin,
                            "User-Agent" to userAgent,
                            "Accept" to "*/*",
                            "X-Direct-Connection" to "1",
                            "Sec-Fetch-Dest" to "empty",
                            "Sec-Fetch-Mode" to "cors",
                            "Sec-Fetch-Site" to "cross-site"
                        )
                    )
                } else {
                    debugLog("[atob-필터링] 감지된 주소($decodedUrl)는 가짜 허니팟 주소이므로 우회를 지속합니다.")
                }
            }
            
            // 1. 해당 HTML 본문 내에 wiki.js가 탑재되어 있는지 확인
            val wikiJsMatch = Regex("""src=["'](https?://[^"']+|//[^"']+)wiki\.js""").find(iframeHtml)
            if (wikiJsMatch != null) {
                var rawWikiHost = wikiJsMatch.groupValues[1]
                if (rawWikiHost.startsWith("//")) {
                    rawWikiHost = "https:$rawWikiHost"
                }
                val igniteHost = rawWikiHost.trimEnd('/')
                
                val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                
                val fid = Regex("""fid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                val zmidVar = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                
                val liveId = when {
                    !fid.isNullOrEmpty() -> fid
                    !zmidVar.isNullOrEmpty() -> zmidVar
                    else -> pageName
                }
                
                debugLog("[IGNITE-우회] 진짜 중계 플레이어 감지! 호스트: $igniteHost, liveId: $liveId")

                // 진짜 영상 토큰이 탑재될 플레이어 페이지 호출
                val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$liveId"
                val playerHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to iframeUrl,
                    "Origin" to "${parsedIframe.protocol}://${parsedIframe.host}"
                )
                
                val playerResp = app.get(playerUrl, headers = playerHeaders)
                if (playerResp.isSuccessful) {
                    val playerHtml = playerResp.text
                    
                    // 쪼개진 m3u8 문자 배열 파싱 및 복구
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

            // [우회] 자바스크립트 변수로 위장한 동적 iframe 생성 감지 (embedsports.me 동적 생성 우회)
            val edm = Regex("""edm\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            val zmid = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            if (!edm.isNullOrEmpty() && !zmid.isNullOrEmpty()) {
                val constructedIframeUrl = "https://$edm/court/$zmid.php"
                debugLog("[$depth 단계] 동적 JS Iframe 가상 복원 성공 -> URL: $constructedIframeUrl")
                
                // 가상 조립된 중첩 iframe 주소로 재귀 진입하여 탐색
                val link = crawlIframe(constructedIframeUrl, iframeHeaders, depth + 1, sourceName, entryOrigin)
                if (link != null) return link
            }

            // 2. 일반적인 static 중첩 iframe들을 순회하며 재귀적으로 진입 검사합니다.
            val nestedIframeMatches = Regex("""<iframe\s+[^>]*src=(?:["']([^"']+)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .findAll(iframeHtml)
                .map { match ->
                    match.groupValues[1].ifEmpty { match.groupValues[2] }
                }
                .distinct()
                .toList()
                
            for (nestedRawSrc in nestedIframeMatches) {
                var nestedIframeUrl = nestedRawSrc
                val parentOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
                
                // 상대 경로 복원 처리
                if (nestedRawSrc.startsWith("//")) {
                    nestedIframeUrl = "https:$nestedRawSrc"
                } else if (nestedRawSrc.startsWith("/")) {
                    nestedIframeUrl = "$parentOrigin$nestedRawSrc"
                } else if (!nestedRawSrc.startsWith("http")) {
                    nestedIframeUrl = "$parentOrigin/${nestedRawSrc.trimStart('/')}"
                }
                
                if (nestedIframeUrl.endsWith(".js") || nestedIframeUrl.endsWith(".css") || nestedIframeUrl.contains("tag.min")) continue
                
                // 더 깊은 단계로 재귀 탐색 진행
                val link = crawlIframe(nestedIframeUrl, iframeHeaders, depth + 1, sourceName, entryOrigin)
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
