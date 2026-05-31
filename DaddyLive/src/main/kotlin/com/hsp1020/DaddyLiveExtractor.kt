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

            // 글로벌 검증 공유 데이터베이스 API 도메인 지정
            val apiHosts = listOf("chevy.giokko.ru", "giokko.ru", "chevy.kiko2.ru")
            val cfIps = listOf("104.21.32.228", "172.67.149.186") // giokko 서버 소속 글로벌 Cloudflare 공유 IP 주소 대역
            var serverKey: String? = null

            for (apiHost in apiHosts) {
                val serverLookupUrl = "https://$apiHost/server_lookup?channel_id=$channelKey"
                debugLog("extractVideo: [Step 5] Fetching live CDN serverKey from: $serverLookupUrl")
                try {
                    // 1차 시도: 표준 DNS 환경에서 API 질의 진행
                    val serverKeyResp = app.get(serverLookupUrl, headers = mapOf("User-Agent" to userAgent, "Referer" to url1)).body.string()
                    debugLog("extractVideo: [Step 6] server_lookup response payload on '$apiHost': '$serverKeyResp'")
                    val data = parseJson<DataResponse>(serverKeyResp)
                    if (data.serverKey.isNotEmpty()) {
                        serverKey = data.serverKey
                        debugLog("extractVideo: [Step 7] Active serverKey resolved: '$serverKey'")
                        break
                    }
                } catch (e: Exception) {
                    debugLog("extractVideo WARNING: Failed to query API host '$apiHost' via standard DNS: ${e.message}")
                    
                    // 2차 시도: 국내 통신사 DNS 강제 검열(UnknownHostException) 감지 시, Cloudflare IP 직결 SNI 바이패스 기법 적용
                    if (e is java.net.UnknownHostException || e.message?.contains("Unable to resolve host") == true) {
                        debugLog("extractVideo: [DNS-Bypass] DNS Block detected on '$apiHost'. Attempting Cloudflare IP direct SNI routing...")
                        for (ip in cfIps) {
                            val ipUrl = "https://$ip/server_lookup?channel_id=$channelKey"
                            try {
                                val serverKeyResp = app.get(
                                    ipUrl, 
                                    headers = mapOf(
                                        "User-Agent" to userAgent, 
                                        "Referer" to url1,
                                        "Host" to apiHost // Cloudflare 에지 프록시 라우팅을 위한 가상 Host 헤더 주입 (핵심 우회부)
                                    )
                                ).body.string()
                                debugLog("extractVideo: [DNS-Bypass SUCCESS] server_lookup payload on IP '$ip': '$serverKeyResp'")
                                val data = parseJson<DataResponse>(serverKeyResp)
                                if (data.serverKey.isNotEmpty()) {
                                    serverKey = data.serverKey
                                    debugLog("extractVideo: [Step 7] Active serverKey resolved via DNS-Bypass: '$serverKey'")
                                    break
                                }
                            } catch (ipException: Exception) {
                                debugLog("extractVideo WARNING: DNS-Bypass failed on IP '$ip': ${ipException.message}")
                            }
                        }
                    }
                }
                if (serverKey != null) break
            }

            if (serverKey.isNullOrEmpty()) {
                debugLog("extractVideo ERROR: FAILED to resolve serverKey from all available API nodes. DNS Censorship Bypass exhausted.")
                return null
            }

            // 동적 게이트웨이 접미사 큐 순회 탐색 (dvalna 및 newkso 대역 모두 수용)
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
                    debugLog("extractVideo WARNING: Failed on suffix '$suffix' due to network/DNS exception", e)
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
