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
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/147.0.0.0 Safari/537.36" // EasyProxy UA 차용
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
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val streamUrls = links?.map { it.second } ?: listOf(url)

        streamUrls.forEach { streamUrl ->
            val link = extractVideo(streamUrl, this.name)
            if (link != null) {
                callback(link)
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        try {
            debugLog("extractVideo: [1] 메인 플레이어 요청 -> $url")
            
            // 1. stream-*.php 메인 페이지 호출
            val resp = app.get(url, headers = headers).document
            
            // 2. iframe src 추출 (EasyProxy 로직: thatframe ID 최우선 탐색)
            var iframeSrc = resp.select("iframe#thatframe").attr("src")
            if (iframeSrc.isNullOrEmpty()) {
                iframeSrc = resp.select("iframe").attr("src")
            }
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("extractVideo: [오류] iframe을 찾을 수 없습니다.")
                return null
            }
            
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            debugLog("extractVideo: [2] iframe 주소 발견 -> $iframeUrl")

            // 3. iframe 페이지 소스코드 다운로드
            val iframeHeaders = headers.toMutableMap()
            iframeHeaders["Referer"] = url
            
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            iframeHeaders["Origin"] = entryOrigin
            
            val iframeHtml = app.get(iframeUrl, headers = iframeHeaders).text
            
            // 4. EasyProxy 핵심 로직: atob('...') Base64 인코딩 주소 추출
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            
            if (atobMatch == null) {
                debugLog("extractVideo: [오류] iframe HTML 내에서 atob() 해독 파라미터를 찾지 못했습니다.")
                return null
            }

            val b64Url = atobMatch.groupValues[1]
            val streamUrl = base64Decode(b64Url) // Base64 디코딩
            
            debugLog("extractVideo: [3] Base64 디코딩 성공! 최종 스트림 주소 -> $streamUrl")

            // 5. EasyProxy 핵심 로직: 우회 헤더셋 구성
            val parsedIframe = URL(iframeUrl)
            val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
            val refOrigin = entryOrigin.ifEmpty { iframeOrigin }

            return ExtractorLink(
                source = sourceName,
                name = sourceName,
                url = streamUrl,
                referer = "$refOrigin/",
                quality = Qualities.Unknown.value,
                type = ExtractorLinkType.M3U8,
                headers = mapOf(
                    "Referer" to "$refOrigin/",
                    "Origin" to refOrigin,
                    "User-Agent" to userAgent,
                    "Accept" to "*/*",
                    "X-Direct-Connection" to "1", // ★ EasyProxy 핵심 우회 헤더
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "cross-site"
                )
            )
        } catch (e: Exception) {
            debugLog("extractVideo: [예외] ${e.message}")
            return null
        }
    }
}
