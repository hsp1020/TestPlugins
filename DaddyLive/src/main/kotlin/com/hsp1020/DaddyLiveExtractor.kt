package com.hsp1020

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    // dlstreams.py 와 동일한 User-Agent 사용
    private val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36"

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
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val streamUrls = links?.map { it.second } ?: listOf(url)

        // 6개의 플레이어(stream, cast, watch 등)를 순회하며 뚫리는 곳을 찾음 (dlstreams.py 로직)
        for (streamUrl in streamUrls) {
            val link = extractVideo(streamUrl, this.name)
            if (link != null) {
                callback(link)
                // 하나라도 성공하면 불필요한 트래픽 낭비 방지를 위해 해당 채널 탐색 종료
                debugLog("성공적으로 추출 완료. 다음 후보 URL 탐색 중지.")
                break
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
