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
            
            var iframeSrc = Regex("""<iframe[^>]*id=["']thatframe["'][^>]*src=["'](.*?)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc.isNullOrEmpty()) {
                iframeSrc = Regex("""<iframe\s+[^>]*src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            }
            if (iframeSrc.isNullOrEmpty()) {
                iframeSrc = Regex("""<iframe[^>]*src=["'](.*?)["']""").find(html)?.groupValues?.get(1)
            }
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("오류: iframe 주소를 찾지 못했습니다.")
                return null
            }
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            debugLog("3. 발견된 iframe 주소 -> $iframeUrl")

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
                debugLog("Freeshot Player 페이지 응답 코드: ${popResp.code}")
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
                return null
            }

            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = url
            iframeHeaders["Origin"] = entryOrigin
            
            debugLog("4. Iframe 페이지 요청 시작 -> URL: $iframeUrl")
            val iframeResp = app.get(iframeUrl, headers = iframeHeaders)
            debugLog("5. Iframe 페이지 응답 코드: ${iframeResp.code}")
            val iframeHtml = iframeResp.text

            // =======================================================
            // [보완] 동적 자바스크립트 수집기 파싱 분기 (CORS 우회 우회)
            // =======================================================
            // wiki.js를 불러오는 도메인이 유동적이어도 감지하도록 설계
            val wikiJsMatch = Regex("""src=["'](https?://[^"']+|//[^"']+)wiki\.js""").find(iframeHtml)
            if (wikiJsMatch != null) {
                var rawWikiHost = wikiJsMatch.groupValues[1]
                if (rawWikiHost.startsWith("//")) {
                    rawWikiHost = "https:$rawWikiHost"
                }
                val igniteHost = rawWikiHost.trimEnd('/')
                
                val parsedIframe = URL(iframeUrl)
                val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                debugLog("[IGNITE] 동적 스트림 플레이어 감지! 호스트: $igniteHost, pageName: $pageName")

                // 진짜 토큰 정보가 실리는 플레이어 페이지 조립 및 요청
                val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$pageName"
                val playerHeaders = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to iframeUrl,
                    "Origin" to "${parsedIframe.protocol}://${parsedIframe.host}"
                )
                debugLog("[IGNITE] 진짜 플레이어 페이지 요청 -> $playerUrl")
                val playerResp = app.get(playerUrl, headers = playerHeaders)
                val playerHtml = playerResp.text

                // 플레이어 본문 내부에서 md5, expires 가 완벽히 삽입된 진짜 m3u8 주소 추출
                val streamUrl = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(playerHtml)?.groupValues?.get(1)?.replace("\\", "")
                
                if (!streamUrl.isNullOrEmpty()) {
                    debugLog("[IGNITE] 진짜 m3u8 최종 주소 추출 성공 -> $streamUrl")
                    
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
                    debugLog("[IGNITE] ExtractorLink 탑재 헤더 -> $finalHeaders")
                    debugLog("================= [DDL EXTRACT END] =================")
                    
                    return ExtractorLink(
                        source = sourceName, name = sourceName, url = streamUrl,
                        referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                        headers = finalHeaders
                    )
                } else {
                    debugLog("[IGNITE] 플레이어 소스 내부에서 m3u8 주소 탐색 실패.")
                }
            }

            // =======================================================
            // [기존 분기 레거시 백업] (atob 로직)
            // =======================================================
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val decodedUrl = base64Decode(b64Url).replace("\"", "").trim()
                debugLog("6. atob Base64 원본 해독 성공 -> $decodedUrl")
                
                val parsedIframe = URL(iframeUrl)
                val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
                
                val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                val resolvedPath = if (decodedUrl.contains("stream") && pageName.isNotEmpty() && pageName != "stream") {
                    decodedUrl.replace("stream", pageName)
                } else {
                    decodedUrl
                }
                
                val isRelative = resolvedPath.startsWith("/") || (!resolvedPath.startsWith("http://") && !resolvedPath.startsWith("https://"))
                
                var streamUrl = resolvedPath
                if (resolvedPath.startsWith("//")) {
                    streamUrl = "https:$resolvedPath"
                } else if (resolvedPath.startsWith("/")) {
                    streamUrl = "$iframeOrigin$resolvedPath"
                } else if (!resolvedPath.startsWith("http://") && !resolvedPath.startsWith("https://")) {
                    streamUrl = "$iframeOrigin/$resolvedPath"
                }
                
                val finalReferer = if (isRelative) iframeUrl else "$iframeOrigin/"
                val finalOrigin = iframeOrigin
                val fetchSite = if (isRelative) "same-origin" else "cross-site"
                
                val finalHeaders = mutableMapOf(
                    "Referer" to finalReferer,
                    "Origin" to finalOrigin,
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "X-Direct-Connection" to "1",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to fetchSite
                )
                
                debugLog("================= [DDL EXTRACT END] =================")
                
                return ExtractorLink(
                    source = sourceName, name = sourceName, url = streamUrl,
                    referer = finalReferer, quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                    headers = finalHeaders
                )
            } else {
                debugLog("알림: 이 iframe($iframeUrl)에는 atob 구문이 존재하지 않습니다. 다음 후보로 넘어갑니다.")
                debugLog("================= [DDL EXTRACT END] =================")
                return null
            }
        } catch (e: Exception) {
            debugLog("오류: 추출 중 예외가 발생했습니다: ${e.message}")
            e.printStackTrace()
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        }
    }
}
