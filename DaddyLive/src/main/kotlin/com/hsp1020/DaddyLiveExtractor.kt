package com.hsp1020

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
        // [Step 1] 비디오 추출 탐색 시작 로깅
        debugLog("extractVideo: [Step 1] Initiated extraction for player page URL: $url")
        if (!url.contains("dlhd")) {
            debugLog("extractVideo ERROR: URL does not belong to dlhd domain. Terminating.")
            return null
        }

        return try {
            val resp = app.post(url, headers = headers).document
            // [Step 2] 부모 페이지 로드 성공 로깅
            debugLog("extractVideo: [Step 2] Successfully fetched dlhd parent page. DOM size: ${resp.toString().length} characters.")
            
            val iframes = resp.select("iframe")
            val iframeSrc = iframes.attr("src")
            // [Step 3] iframe 획득 로깅
            debugLog("extractVideo: [Step 3] Raw iframe source attribute found: '$iframeSrc'")
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("extractVideo ERROR: FAILED to find valid iframe source inside document.")
                return null
            }
            
            val url1 = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            // [Step 4] 정형화된 iframe 주소 도출 로깅
            debugLog("extractVideo: [Step 4] Formatted iframe source URL: '$url1'")

            // 플레이어 URL에서 고유 ID 추출 (예: stream-168.php -> 168)
            val id = url.substringAfter("stream-").substringBefore(".php").trim()
            // [Step 5] 고유 채널 ID 파싱 결과 로깅
            debugLog("extractVideo: [Step 5] Extracted raw stream ID: '$id'")
            if (id.isEmpty() || !id.all { it.isDigit() }) {
                debugLog("extractVideo ERROR: Raw stream ID is empty or contains non-numeric characters.")
                return null
            }
            val channelKey = "premium$id"
            // [Step 6] Newzar/mizhls 채널 매핑 키 규격 로깅
            debugLog("extractVideo: [Step 6] Configured channelKey = '$channelKey'")

            // 1. mizhls.ru 게이트웨이 주소 조립
            val mizhlsGatewayUrl = "https://xyzdddd.mizhls.ru/lb/$channelKey/index.m3u8"
            // [Step 7] 게이트웨이 최종 도메인 타겟 빌드 결과 로깅
            debugLog("extractVideo: [Step 7] Assembled Gateway URL = $mizhlsGatewayUrl")

            // 2. 게이트웨이에 302 리다이렉트 추적 요청 (cookiewebplay 헤더 필수 우회)
            val streamHeaders = mapOf(
                "User-Agent" to userAgent,
                "Referer" to "https://cookiewebplay.xyz/",
                "Origin" to "https://cookiewebplay.xyz",
                "Connection" to "Keep-Alive"
            )
            // [Step 8] 게이트웨이 우회용 cookiewebplay 헤더 할당 로깅
            debugLog("extractVideo: [Step 8] Configured bypass headers -> Referer: 'https://cookiewebplay.xyz/', Origin: 'https://cookiewebplay.xyz'")
            
            // [Step 9] 302 가로채기 호출 전 로깅
            debugLog("extractVideo: [Step 9] Requesting Gateway URL with allowRedirects=false to catch 302 redirection...")
            val streamResponse = app.get(mizhlsGatewayUrl, headers = streamHeaders, allowRedirects = false)
            // [Step 10] 게이트웨이 서버 응답 코드 로깅
            debugLog("extractVideo: [Step 10] Gateway Server HTTP Response Code = ${streamResponse.code}")
            
            val location = streamResponse.headers["Location"] ?: streamResponse.headers["location"]
            // [Step 11] 리다이렉트 Location 헤더 로깅 (phantemlis.top의 md5/expires가 수집되는 영역)
            debugLog("extractVideo: [Step 11] Extracted Location Header = '$location'")
            
            val finalUrl = if (!location.isNullOrEmpty()) {
                location
            } else {
                debugLog("extractVideo WARNING: Redirect Location was empty or null. Falling back to Gateway URL.")
                mizhlsGatewayUrl
            }

            // [Step 12] 추출 완료된 최종 재생용 HLS 주소 로깅
            debugLog("extractVideo: [Step 12] Final Stream URL Resolved = $finalUrl")
            
            val refererBase = "https://cookiewebplay.xyz"
            // [Step 13] 미디어 플레이어 전달용 최종 헤더 바인딩 로깅
            debugLog("extractVideo: [Step 13] Creating ExtractorLink with refererBase: '$refererBase'")
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
            debugLog("extractVideo EXCEPTION at target URL: $url", e)
            null
        }
    }
}
