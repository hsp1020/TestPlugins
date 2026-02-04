package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.*

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // 1. 플레이어 페이지 로드 (에피소드 주소를 Referer로 사용)
        val playerResponse = app.get(url, referer = referer).text
        
        // 도메인 추출 (현재 요청한 URL의 도메인을 그대로 사용: everyX.poorcdn.com)
        val domainMatch = Regex("""https?://([^/]+)""").find(url)
        val currentDomain = if (domainMatch != null) "https://${domainMatch.groupValues[1]}" else "https://every9.poorcdn.com"

        // 2. 유동적 경로 추출 (/v/f/, /v/w/, /v/k/ 등 모든 패턴 대응)
        // index.m3u8 또는 c.html?token= 으로 끝나는 모든 따옴표 안의 경로를 찾음
        val pathRegex = Regex("""["']((?:https?://[^"']*)?/v/[a-z]/[a-f0-9]{32,}[^"']*(?:\.m3u8|\.html\?token=[^"']+))["']""")
        
        val matches = pathRegex.findAll(playerResponse).map { it.groupValues[1].replace("\\/", "/") }.toList()
        
        // 3. m3u8 주소 먼저 확인
        val m3u8Path = matches.find { it.contains(".m3u8") }
        if (m3u8Path != null) {
            val finalUrl = if (m3u8Path.startsWith("http")) m3u8Path else "$currentDomain${if (m3u8Path.startsWith("/")) "" else "/"}$m3u8Path"
            println("DEBUG_EXTRACTOR: Found m3u8 -> $finalUrl")
            invokeLink(finalUrl, url, callback) // 플레이어 주소를 Referer로 전달
            return
        }

        // 4. m3u8이 없다면 Token HTML 확인
        val tokenPath = matches.find { it.contains("token=") }
        if (tokenPath != null) {
            val tokenUrl = if (tokenPath.startsWith("http")) tokenPath else "$currentDomain${if (tokenPath.startsWith("/")) "" else "/"}$tokenPath"
            println("DEBUG_EXTRACTOR: Found Token URL -> $tokenUrl")
            
            val tokenResponse = app.get(tokenUrl, referer = url).text
            val finalM3u8Match = pathRegex.find(tokenResponse)?.groupValues?.get(1)?.replace("\\/", "/")
            
            finalM3u8Match?.let {
                val finalUrl = if (it.startsWith("http")) it else "$currentDomain${if (it.startsWith("/")) "" else "/"}$it"
                invokeLink(finalUrl, url, callback)
            }
        } else {
            println("DEBUG_EXTRACTOR: Failed to find any valid path in 66KB response")
        }
    }

    private suspend fun invokeLink(m3u8Url: String, refererUrl: String, callback: (ExtractorLink) -> Unit) {
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // 핵심: m3u8을 재생할 때 Referer가 플레이어 URL(bunny-frame.online)이어야 404/403이 안 남
                this.referer = refererUrl 
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
