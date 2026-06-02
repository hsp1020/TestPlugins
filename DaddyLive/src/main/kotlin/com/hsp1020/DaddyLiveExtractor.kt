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
        // AppUtils.tryParseJson 대신 mapper 직접 사용
        val links = try {
            mapper.readValue(url, object : TypeReference<List<Pair<String, String>>>() {})
        } catch (e: Exception) {
            null
        }
        
        // 단일 URL로 인입된 경우의 예외 처리
        if (links == null) {
            val link = extractVideo(url, this.name)
            if (link != null) callback(link)
            return
        }

        // 1. "채널명 - 플레이어타입"을 기준으로 그룹화 (예: "Sky Sports" 단위로 묶음)
        val groupedLinks = links.groupBy { it.first.substringBefore(" - ") }

        // 2. 각 채널마다 독립적으로 추출 시도
        groupedLinks.forEach { (channelName, playerPairs) ->
            debugLog("[$channelName] 채널의 스트림 추출 시작 (총 ${playerPairs.size}개 후보)")
            
            for (playerPair in playerPairs) {
                val sourceName = playerPair.first // UI에 표시될 이름 (예: "Sky Sports - stream")
                val streamUrl = playerPair.second

                val link = extractVideo(streamUrl, sourceName)
                if (link != null) {
                    callback(link)
                    // 해당 채널의 주소를 하나 찾았으므로, 이 채널의 나머지 백업 플레이어(cast, watch 등) 탐색만 중지함
                    debugLog("[$channelName] 성공적으로 추출 완료. 해당 채널의 나머지 백업 플레이어 탐색 중지.")
                    break 
                }
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        try {
            debugLog("파이썬 로직 탐색 시작 -> $url")
            
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to url,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            // 1. 플레이어 페이지 가져오기
            val resp = app.get(url, headers = headers)
            if (!resp.isSuccessful) return null
            val html = resp.text
            
            // 2. iframe src 추출 (dlstreams.py: id="thatframe" 우선 탐색)
            var iframeSrc = Regex("""<iframe[^>]*id=["']thatframe["'][^>]*src=["'](.*?)["']""").find(html)?.groupValues?.get(1)
            if (iframeSrc.isNullOrEmpty()) {
                iframeSrc = Regex("""<iframe\s+[^>]*src=["'](https?://[^"']+)["']""", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
            }
            if (iframeSrc.isNullOrEmpty()) {
                iframeSrc = Regex("""<iframe[^>]*src=["'](.*?)["']""").find(html)?.groupValues?.get(1)
            }
            
            if (iframeSrc.isNullOrEmpty()) return null
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            debugLog("발견된 iframe 주소 -> $iframeUrl")

            // =======================================================
            // [분기 A] freeshot.py 로직 (popcdn.day / freeshot.live 계열)
            // =======================================================
            if (iframeUrl.contains("freeshot.live") || iframeUrl.contains("popcdn.day")) {
                debugLog("Freeshot / Popcdn 로직 가동")
                var channelCode = ""
                
                val embedMatch = Regex("""embed/([^/.]+)\.php""").find(iframeUrl)
                if (embedMatch != null) {
                    channelCode = embedMatch.groupValues[1]
                } else {
                    channelCode = iframeUrl.split("/").last().split("?").first()
                }
                
                val targetUrl = "https://popcdn.day/player/$channelCode"
                val popBody = app.get(targetUrl, headers = headers).text
                
                var finalM3u8 = ""
                // 1) streamUrl : "..." 패턴 탐색
                val streamUrlMatch = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(popBody)
                if (streamUrlMatch != null) {
                    finalM3u8 = streamUrlMatch.groupValues[1].replace("\\", "")
                } else {
                    // 2) fallback token 탐색
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

            // =======================================================
            // [분기 B] dlstreams.py 로직 (atob 베이스64 디코딩 계열)
            // =======================================================
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = url
            iframeHeaders["Origin"] = entryOrigin
            
            val iframeHtml = app.get(iframeUrl, headers = iframeHeaders).text
            
            // dlstreams.py 핵심: atob 파싱
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val streamUrl = base64Decode(b64Url).replace("\"", "").trim()
                debugLog("atob Base64 디코딩 성공 -> $streamUrl")
                
                val parsedIframe = URL(iframeUrl)
                val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
                val refOrigin = if (entryOrigin.isNotEmpty()) entryOrigin else iframeOrigin
                
                // provider_hooks.py 핵심: 특수 우회 헤더
                return ExtractorLink(
                    source = sourceName, name = sourceName, url = streamUrl,
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
                debugLog("이 iframe($iframeUrl)에는 atob 평문이 없습니다. 다음 후보를 찾습니다.")
                return null
            }
        } catch (e: Exception) {
            debugLog("추출 중 예외 발생: ${e.message}")
            return null
        }
    }
}
