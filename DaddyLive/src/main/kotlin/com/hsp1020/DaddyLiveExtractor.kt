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
import org.jsoup.Jsoup
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

    private fun debugLog(message: String, throwable: Throwable? = null) {
        val errMessage = throwable?.message ?: "Unknown Error"
        val formatted = if (throwable != null) {
            "[DDL-DEBUG] $message | Exception: $errMessage"
        } else {
            "[DDL-DEBUG] $message"
        }
        
        if (throwable != null) {
            Log.e("DDL-DEBUG", "[DDL-DEBUG] $message: $errMessage")
            println(formatted)
        } else {
            Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
            println(formatted)
        }
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

            // 임베드 페이지의 스크립트를 로드하여 실시간 도메인 접미사(Host Suffix)를 동적으로 파싱
            val pageResponse = app.get(url1, headers = headers)
            debugLog("extractVideo: [Step 5] Loaded iframe contents. Response code: ${pageResponse.code}")
            val pageHtml = pageResponse.body.string()
            val page = Jsoup.parse(pageHtml)

            val scriptElement = page.select("script").firstOrNull { 
                it.data().contains("serverKey") || it.data().contains("CHANNEL_KEY") 
            }
            
            if (scriptElement == null) {
                debugLog("extractVideo ERROR: Failed to find script containing serverKey/CHANNEL_KEY in page.")
                return null
            }
            val script = scriptElement.data()

            // 1. Newkso.ru 나 dvalna.ru 등의 가변 도메인 접미사 동적 정규식 파싱 (하드코딩 제거)
            val rawHostSuffix = Regex("""serverKey\s*\+\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1) ?: "new.newkso.ru"
            val hostSuffix = rawHostSuffix.trim().removeSuffix("/").trim()
            debugLog("extractVideo: [Step 6] Dynamically extracted Host Suffix: '$hostSuffix'")

            // 2. 임베드 도메인의 server_lookup.php를 호출하여 실시간 serverKey 획득
            val serverLookupUrl = url1.substringBefore("/daddy") + "/server_lookup.php?channel_id=$channelKey"
            debugLog("extractVideo: [Step 7] Fetching live CDN serverKey from: $serverLookupUrl")
            
            val serverKeyResp = app.get(serverLookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to url1)).body.string()
            debugLog("extractVideo: [Step 8] server_lookup response payload: '$serverKeyResp'")
            
            val data = try { 
                parseJson<DataResponse>(serverKeyResp) 
            } catch (e: Exception) {
                debugLog("extractVideo ERROR: Failed to parse server_lookup response", e)
                return null
            }
            val serverKey = data.serverKey
            debugLog("extractVideo: [Step 9] Active serverKey resolved: '$serverKey'")

            // 3. 동적으로 수집된 키와 도메인 접미사를 조합해 최신 게이트웨이 주소 조립
            val m3u8GatewayUrl = if (serverKey == "top1/cdn") {
                "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
            } else {
                "https://${serverKey}${hostSuffix}/${serverKey}/${channelKey}/mono.m3u8"
            }
            debugLog("extractVideo: [Step 10] Assembled Gateway URL: $m3u8GatewayUrl")

            // 4. 게이트웨이에 302 리다이렉트 추적 요청 송신 (forcedtoplay 필수 검증 헤더 기입)
            val streamHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://forcedtoplay.xyz/",
                "Origin" to "https://forcedtoplay.xyz",
                "Connection" to "Keep-Alive"
            )
            debugLog("extractVideo: [Step 11] Requesting Gateway URL with allowRedirects=false to catch 302 redirection...")
            val streamResponse = app.get(m3u8GatewayUrl, headers = streamHeaders, allowRedirects = false)
            debugLog("extractVideo: [Step 12] Gateway Server HTTP Response Code = ${streamResponse.code}")
            
            val location = streamResponse.headers["Location"] ?: streamResponse.headers["location"]
            debugLog("extractVideo: [Step 13] Extracted Location Header = '$location'")
            
            val finalUrl = if (!location.isNullOrEmpty()) {
                location
            } else {
                debugLog("extractVideo WARNING: Redirect Location was empty. Reverting to Gateway URL.")
                m3u8GatewayUrl
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
