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
        
        // 1. M3U8 URL 직접 찾기 (JWT 토큰 포함된 URL)
        // 주어진 에러 로그의 URL 패턴 분석:
        // https://player.bcbc.red/stream/m3u8/{JWT_TOKEN}
        
        // 2. URL에서 JWT 토큰 추출 또는 재생성
        val m3u8Url = extractM3u8UrlFromPage(url, userAgent, referer)
        
        if (m3u8Url.isBlank()) {
            throw Error("Failed to extract M3U8 URL")
        }
        
        // 3. M3U8 내용을 먼저 확인하여 키 URI 문제 검사
        try {
            val m3u8Response = app.get(
                m3u8Url,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://player-v1.bcbc.red/",
                    "Accept" to "*/*",
                    "Origin" to "https://player-v1.bcbc.red"
                )
            )
            
            val m3u8Content = m3u8Response.text
            
            // 4. 키 URI가 있는지 확인하고 문제 있으면 처리
            val processedM3u8Content = processM3u8Content(m3u8Content, m3u8Url)
            
            if (processedM3u8Content != m3u8Content) {
                // M3U8 내용이 수정되었다면, 수정된 내용을 별도 서버나 로컬에서 제공해야 함
                // CloudStream3에서는 직접적인 M3U8 내용 수정이 어려움
                // 대신 키 URI를 제거한 M3U8을 제공하거나 다른 방법 모색
                throw Error("Encrypted stream detected - may need additional handling")
            }
            
        } catch (e: Exception) {
            // M3U8 요청 실패 - 인증 문제일 가능성 높음
        }
        
        // 5. 기본 M3U8 링크 제공 (문제는 여전히 발생할 수 있음)
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = m3u8Url,
                type = ExtractorLinkType.M3U8,
                referer = "https://player-v1.bcbc.red/",
                quality = Qualities.Unknown.value,
                headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to "https://player-v1.bcbc.red/",
                    "Accept" to "*/*",
                    "Origin" to "https://player-v1.bcbc.red",
                    // JWT 토큰이 URL에 있으므로 추가 인증 헤더는 필요 없을 수 있음
                    // 하지만 쿠키나 추가 헤더가 필요할 수 있음
                )
            )
        )
    }
    
    private suspend fun extractM3u8UrlFromPage(pageUrl: String, userAgent: String, referer: String?): String {
        // WebView를 사용하여 실제 플레이어 페이지 로드
        val response = app.get(
            pageUrl,
            headers = mapOf("User-Agent" to userAgent),
            interceptor = WebViewResolver(Regex("""player\.bcbc\.red"""), timeout = 10000L)
        )
        
        // 다양한 패턴으로 M3U8 URL 찾기
        val patterns = listOf(
            Regex("""(https?://player\.bcbc\.red/stream/m3u8/[^\s"']+)"""),
            Regex("""src\s*=\s*["']([^"']+\.m3u8[^"']*)["']"""),
            Regex("""data-(?:src|m3u8)\s*=\s*["']([^"']+)["']"""),
            Regex(""""[^"]*\.m3u8[^"]*"""")
        )
        
        for (pattern in patterns) {
            val matches = pattern.findAll(response.text)
            matches.forEach { match ->
                var url = match.groupValues.getOrNull(1) ?: match.value
                url = url.trim().removeSurrounding("\"").removeSurrounding("'")
                
                // JWT 토큰이 포함된 M3U8 URL인지 확인
                if (url.contains("/stream/m3u8/") && url.contains("eyJ")) {
                    return url
                }
                
                // .m3u8로 끝나는지 확인
                if (url.endsWith(".m3u8") || url.contains(".m3u8?")) {
                    // 상대 URL 처리
                    if (url.startsWith("//")) {
                        return "https:$url"
                    } else if (url.startsWith("/")) {
                        return "https://player.bcbc.red$url"
                    }
                    return url
                }
            }
        }
        
        return ""
    }
    
    private fun processM3u8Content(content: String, baseUrl: String): String {
        // #EXT-X-KEY 태그 찾기
        val keyPattern = Regex("""#EXT-X-KEY:METHOD=AES-128,URI="([^"]+)"""")
        val keyMatch = keyPattern.find(content)
        
        if (keyMatch != null) {
            val keyUri = keyMatch.groupValues[1]
            println("Found encryption key URI: $keyUri")
            
            // 키 URI가 상대경로인지 확인
            var fullKeyUri = keyUri
            if (keyUri.startsWith("//")) {
                fullKeyUri = "https:$keyUri"
            } else if (keyUri.startsWith("/")) {
                // baseUrl에서 도메인 추출
                val domain = Regex("""(https?://[^/]+)""").find(baseUrl)?.value ?: "https://player.bcbc.red"
                fullKeyUri = "$domain$keyUri"
            }
            
            // 여기서 키 URI에 대한 요청 테스트를 할 수 있지만,
            // Extractor 내에서는 제한적임
            
            // 만약 키 URI가 문제라면, 암호화를 제거한 스트림을 찾거나
            // 다른 소스를 찾아야 함
        }
        
        return content
    }
}
