package com.hsp1020

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false

    // 연동할 Cloudflare Worker 베이스 주소
    private val workerBaseUrl = "https://dlhd.vynx-3b3.workers.dev"

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
        
        // ScheduleProvider에서 넘어온 JSON 페이로드 파싱 (예: [{"first":"채널명","second":"주소"}])
        val links = tryParseJson<List<Pair<String, String>>>(url)
        
        val streamUrls = if (links != null) {
            debugLog("getUrl: Parsed payload list of size ${links.size}.")
            links
        } else {
            debugLog("getUrl: Input payload is not list-formatted. Single link extraction initiated.")
            listOf(this.name to url)
        }

        var successfullyExtractedCount = 0
        
        streamUrls.forEach { (sourceName, streamUrl) ->
            try {
                // 스트림 URL에서 채널 고유 ID만 깔끔하게 추출 (예: stream-168.php -> 168)
                val id = streamUrl.substringAfter("stream-").substringBefore(".php").trim()
                
                if (id.isNotEmpty() && id.all { it.isDigit() }) {
                    // CF Worker의 VLC-compatible 엔드포인트를 사용 (/play/:channelId)
                    // Worker가 PoW 인증 및 AES 복호화를 백그라운드에서 전부 처리해줌
                    val finalProxyUrl = "$workerBaseUrl/play/$id"
                    
                    debugLog("extractVideo SUCCESS: Assembled CF Worker Proxy URL -> $finalProxyUrl")

                    callback(
                        ExtractorLink(
                            source = this.name,
                            name = "$sourceName (Worker Proxy)",
                            url = finalProxyUrl,
                            referer = "",
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8
                        )
                    )
                    successfullyExtractedCount++
                } else {
                    debugLog("extractVideo ERROR: Invalid or empty ID extracted from -> $streamUrl")
                }
            } catch (e: Exception) {
                debugLog("extractVideo EXCEPTION at target URL: $streamUrl", e)
            }
        }
        
        debugLog("getUrl SUCCESS: Extracted $successfullyExtractedCount stream link(s).")
    }
}
