package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent
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
        debugLog("getUrl: Triggered extractor. Raw URL input payload: $url")
        val links = tryParseJson<List<Pair<String, String>>>(url)
        
        val extractors = if (links != null) {
            debugLog("getUrl: Parsed payload list of size ${links.size}. Initializing extraction queue.")
            links.map {
                extractVideo(it.second, it.first)
            }
        } else {
            debugLog("getUrl: Input payload is not list-formatted. Single link extraction initiated: $url")
            listOf(extractVideo(url))
        }

        var successfullyExtractedCount = 0
        extractors.forEach {
            if (it != null) {
                successfullyExtractedCount++
                callback(it)
            }
        }
        debugLog("getUrl SUCCESS: Extracted $successfullyExtractedCount stream link(s) out of ${extractors.size} candidates.")
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        debugLog("extractVideo: [Step 1] Loading player host page: $url")
        if (!url.contains("dlhd") || url.contains("stream-5015.php")) {
            debugLog("extractVideo ERROR: URL does not belong to main dlhd domain or is a blacklisted backup stream. Terminating.")
            return null
        }

        return try {
            val resp = app.post(url, headers = headers).document
            val iframes = resp.select("iframe")
            val iframeSrc = iframes.attr("src")
            debugLog("extractVideo: [Step 2] Extracted iframe elements. Candidate src attribute: '$iframeSrc'")
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("extractVideo ERROR: FAILED to find valid iframe source inside document.")
                return null
            }
            
            val url1 = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            debugLog("extractVideo: [Step 3] Formatted iframe source: '$url1'")

            // 플레이어 URL에서 고유 ID 추출 (예: stream-168.php -> 168)
            val id = url.substringAfter("stream-").substringBefore(".php").trim()
            if (id.isEmpty() || !id.all { it.isDigit() }) {
                debugLog("extractVideo ERROR: Invalid or empty ID extracted: '$id'")
                return null
            }
            val channelKey = "premium$id"
            debugLog("extractVideo: [Step 4] Resolved channelKey: '$channelKey'")

            // 1. 임베드 도메인 디렉토리 형태에 맞춘 server_lookup.php 동적 경로 조립
            val serverLookupUrl = if (url1.contains("/premiumtv/")) {
                url1.substringBefore("/premiumtv/") + "/premiumtv/server_lookup.php?channel_id=$channelKey"
            } else {
                val parsed = URL(url1)
                "${parsed.protocol}://${parsed.host}/server_lookup.php?channel_id=$channelKey"
            }
            debugLog("extractVideo: [Step 5] Fetching live CDN serverKey from: $serverLookupUrl")
            
            val serverKeyResp = app.get(serverLookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to url1)).body.string()
            debugLog("extractVideo: [Step 6] server_lookup response payload: '$serverKeyResp'")
            
            val data = try { 
                parseJson<DataResponse>(serverKeyResp) 
            } catch (e: Exception) {
                debugLog("extractVideo ERROR: Failed to parse server_lookup response", e)
                return null
            }
            val serverKey = data.serverKey
            debugLog("extractVideo: [Step 7] Active serverKey resolved: '$serverKey'")

            // 2. 동적 게이트웨이 접미사 큐 순회 탐색 (dvalna 및 newkso 대역 모두 수용)
            val hostSuffixes = listOf("new.newkso.ru", "new.dvalna.ru")
            var location: String? = null
            var activeGatewayUrl = ""

            val streamHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://forcedtoplay.xyz/",
                "Origin" to "https://forcedtoplay.xyz",
                "Connection" to "Keep-Alive"
            )

            for (suffix in hostSuffixes) {
                val m3u8GatewayUrl = if (serverKey == "top1/cdn") {
                    "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                } else {
                    "https://${serverKey}${suffix}/${serverKey}/${channelKey}/mono.m3u8"
                }
                debugLog("extractVideo: [Step 8] Attempting Gateway URL: $m3u8GatewayUrl")

                try {
                    val streamResponse = app.get(m3u8GatewayUrl, headers = streamHeaders, allowRedirects = false)
                    debugLog("extractVideo: [Step 9] Gateway Server Response for '$suffix': ${streamResponse.code}")
                    
                    val loc = streamResponse.headers["Location"] ?: streamResponse.headers["location"]
                    if (!loc.isNullOrEmpty()) {
                        location = loc
                        activeGatewayUrl = m3u8GatewayUrl
                        debugLog("extractVideo: [Step 10] Active Gateway identified with redirect location: '$location'")
                        break
                    }
                } catch (e: Exception) {
                    debugLog("extractVideo WARNING: Failed on suffix '$suffix' due to network/DNS exception: ${e.message}")
                }
            }

            val finalUrl = if (!location.isNullOrEmpty()) {
                location
            } else {
                debugLog("extractVideo WARNING: All redirects were empty. Falling back to default gateway URL.")
                if (serverKey == "top1/cdn") "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                else "https://${serverKey}new.newkso.ru/${serverKey}/${channelKey}/mono.m3u8"
            }

            debugLog("extractVideo SUCCESS: Resolved dynamic tokenized playback URL: $finalUrl")
            
            val refererBase = "https://forcedtoplay.xyz"
            newExtractorLink(
                sourceName,
                sourceName,
                finalUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$refererBase/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to "$refererBase/",
                    "Origin" to refererBase,
                    "Connection" to "Keep-Alive",
                    "User-Agent" to userAgent
                )
            }
        } catch (e: Exception) {
            debugLog("extractVideo EXCEPTION at target URL: $url : ${e.message}")
            null
        }
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
}
