package com.movieking

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

class BcbcRedExtractor : ExtractorApi() {
    override val name = "MovieKingPlayer"
    override val mainUrl = "https://player-v1.bcbc.red"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
        
        // 1. WebViewResolver로 실제 페이지 로드
        val response = app.get(
            url,
            headers = mapOf("User-Agent" to userAgent),
            interceptor = WebViewResolver(
                Regex("""player\.bcbc\.red"""),
                timeout = 10000L
            )
        )
        
        // 2. M3U8 URL 찾기 - 여러 패턴 시도
        val m3u8Url = findM3u8Url(response.text)
        
        if (m3u8Url.isEmpty()) {
            throw Error("No M3U8 URL found")
        }
        
        // 3. 올바른 newExtractorLink 사용법
        callback(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8
            ) {
                // 여기서 속성 설정
                this.referer = referer ?: "https://player-v1.bcbc.red/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to referer ?: "https://player-v1.bcbc.red/",
                    "Accept" to "*/*",
                    "Origin" to "https://player-v1.bcbc.red"
                )
            }
        )
    }
    
    private fun findM3u8Url(html: String): String {
        // 패턴 1: data-m3u8 속성
        val dataM3u8Pattern = Regex("""data-m3u8\s*=\s*["']([^"']+)["']""")
        val dataMatch = dataM3u8Pattern.find(html)
        if (dataMatch != null) {
            return processUrl(dataMatch.groupValues[1])
        }
        
        // 패턴 2: script에서 URL 찾기
        val scriptPattern = Regex("""(https?://[^"'\s]+\.m3u8[^"'\s]*)""")
        val scriptMatches = scriptPattern.findAll(html)
        scriptMatches.forEach { match ->
            return processUrl(match.value)
        }
        
        // 패턴 3: iframe src
        val iframePattern = Regex("""<iframe[^>]+src\s*=\s*["']([^"']+\.m3u8[^"']*)["']""")
        val iframeMatch = iframePattern.find(html)
        if (iframeMatch != null) {
            return processUrl(iframeMatch.groupValues[1])
        }
        
        return ""
    }
    
    private fun processUrl(url: String): String {
        var processed = url.trim()
            .removeSurrounding("\"")
            .removeSurrounding("'")
            .replace("\\/", "/")
        
        // URL 디코딩
        try {
            processed = java.net.URLDecoder.decode(processed, "UTF-8")
        } catch (e: Exception) {
            // 무시
        }
        
        // 상대 URL 처리
        if (processed.startsWith("//")) {
            processed = "https:$processed"
        } else if (processed.startsWith("/")) {
            processed = "https://player.bcbc.red$processed"
        }
        
        return processed
    }
}
