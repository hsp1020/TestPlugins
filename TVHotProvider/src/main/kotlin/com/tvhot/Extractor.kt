package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"
    
    // Boolean 반환형으로 변경하여 성공 여부 전달
    suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to (referer ?: mainUrl),
            "Origin" to mainUrl
        )

        return try {
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url // 리다이렉트된 최종 URL

            // 1. 소스 내에서 .m3u8 링크 직접 찾기 (가장 정확함)
            val directMatch = Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(text)
            if (directMatch != null) {
                val m3u8Url = directMatch.groupValues[1]
                val fullM3u8 = if (m3u8Url.startsWith("http")) m3u8Url else {
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}$m3u8Url"
                }
                loadM3u8(fullM3u8, finalUrl, headers, callback)
                return true
            }

            // 2. 비디오 경로(/v/f/ID)를 찾아 도메인과 결합
            // 예: /v/f/71411cac... 
            val pathMatch = Regex("""/v/[ef]/[a-zA-Z0-9]+""").find(text) ?: Regex("""/v/[ef]/[a-zA-Z0-9]+""").find(cleanUrl)
            
            if (pathMatch != null) {
                val path = pathMatch.value
                
                // 도메인 찾기 시도
                // 1) 텍스트 내에서 도메인 변수 찾기 (var domain = "...")
                val domainMatch = Regex("""var\s+\w+\s*=\s*["'](https?://[^"']+)["']""").find(text)
                
                // 2) 텍스트 내에서 해당 경로를 포함한 전체 URL 찾기 (예: 썸네일 이미지 등)
                val fullPathMatch = Regex("""(https?://[^"' \t\n]+)$path""").find(text)

                val domain = when {
                    fullPathMatch != null -> fullPathMatch.groupValues[1]
                    domainMatch != null -> domainMatch.groupValues[1]
                    finalUrl.contains(path) -> { // 현재 URL이 이미 CDN 주소인 경우
                        val uri = java.net.URI(finalUrl)
                        "${uri.scheme}://${uri.host}"
                    }
                    else -> null
                }

                if (domain != null) {
                    val m3u8Link = "$domain$path/index.m3u8"
                    loadM3u8(m3u8Link, finalUrl, headers, callback)
                    return true
                }
            }
            
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadM3u8(
        url: String, 
        referer: String, 
        headers: Map<String, String>, 
        callback: (ExtractorLink) -> Unit
    ) {
        M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        ).forEach(callback)
    }
}
