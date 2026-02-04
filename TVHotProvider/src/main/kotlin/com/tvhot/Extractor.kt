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
        // 기본 헤더 설정
        val baseHeaders = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Upgrade-Insecure-Requests" to "1",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "cross-site",
            "Referer" to (referer ?: "https://player.bunny-frame.online/")
        )
        
        // 1. 서버 번호 추출 (s=5 -> every5)
        val serverNum = Regex("""[?&]s=(\d+)""").find(url)?.groupValues?.get(1) ?: "9"
        val domain = "https://every$serverNum.poorcdn.com"
        println("DEBUG_EXTRACTOR: Using domain: $domain")
        
        // 2. 플레이어 페이지 로드
        val response = app.get(url, headers = baseHeaders).text
        
        // 3. 다양한 패턴으로 경로 탐색
        val pathPatterns = listOf(
            // 패턴 1: /v/f/ 뒤에 32자 이상의 ID
            Regex("""/v/f/([a-f0-9]{32,})"""),
            // 패턴 2: src= 뒤에 ID
            Regex("""src=([a-f0-9]{32,})"""),
            // 패턴 3: 암호화된 src 파라미터
            Regex("""[?&]src=([a-f0-9]{32,})"""),
            // 패턴 4: JSON 내부 경로
            Regex("""["']/v/f/([a-f0-9]{32,})/index\.m3u8["']"""),
            // 패턴 5: upnext 배열 내부
            Regex("""upnext.*?"src"\s*:\s*"([^"]+\.m3u8)"""),
            // 패턴 6: 모든 m3u8 링크 찾기
            Regex(""""(https?://[^"]+\.m3u8[^"]*)""")
        )
        
        var idMatch: String? = null
        var foundM3u8: String? = null
        
        for (pattern in pathPatterns) {
            try {
                when (pattern.pattern) {
                    // m3u8 직접 링크 패턴 (패턴 6)
                    """\"(https?://[^\"]+\.m3u8[^\"]*)""" -> {
                        val match = pattern.find(response)?.groupValues?.get(1)
                        if (match != null && match.contains(".m3u8")) {
                            foundM3u8 = match.replace("\\/", "/")
                            println("DEBUG_EXTRACTOR: Found direct m3u8: $foundM3u8")
                            break
                        }
                    }
                    // ID 패턴
                    else -> {
                        val match = pattern.find(response)?.groupValues?.get(1)
                        if (match != null && match.length >= 32) {
                            idMatch = match
                            println("DEBUG_EXTRACTOR: Found ID via ${pattern.pattern.take(30)}...: $idMatch")
                            break
                        }
                    }
                }
            } catch (e: Exception) {
                println("DEBUG_EXTRACTOR: Error with pattern ${pattern.pattern}: ${e.message}")
            }
        }
        
        // URL 자체에서도 ID 찾기 시도
        if (idMatch == null) {
            val urlIdMatch = Regex("""[?&]src=([a-f0-9]{32,})""").find(url)?.groupValues?.get(1)
            if (urlIdMatch != null) {
                idMatch = urlIdMatch
                println("DEBUG_EXTRACTOR: Found ID in URL: $idMatch")
            }
        }
        
        // 4. ID를 찾은 경우 처리
        if (idMatch != null) {
            println("DEBUG_EXTRACTOR: Processing with ID: $idMatch")
            
            // BunnyCDN/PoorCDN의 표준 경로 시도 (여러 경로 패턴)
            val pathVariants = listOf(
                "/v/f/",  // 기본
                "/v/w/",  // 대체 경로 1
                "/v/k/",  // 대체 경로 2
                "/v/m/"   // 대체 경로 3
            )
            
            var success = false
            
            for (path in pathVariants) {
                try {
                    val tokenUrl = "$domain$path$idMatch/c.html"
                    val directM3u8 = "$domain$path$idMatch/index.m3u8"
                    
                    println("DEBUG_EXTRACTOR: Trying path $path, token: $tokenUrl, direct: $directM3u8")
                    
                    // 4-1. 토큰 페이지 접근 시도
                    try {
                        val tokenHeaders = baseHeaders.toMutableMap()
                        tokenHeaders["Referer"] = url
                        
                        val tokenResponse = app.get(tokenUrl, headers = tokenHeaders).text
                        
                        // 토큰 페이지 내부에서 진짜 재생 주소 추출
                        val m3u8Patterns = listOf(
                            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
                            Regex("""(https?://[^\s"']+\.m3u8)"""),
                            Regex("""src\s*:\s*["']([^"']+\.m3u8)["']"""),
                            Regex("""file\s*:\s*["']([^"']+\.m3u8)["']""")
                        )
                        
                        var extractedM3u8: String? = null
                        for (m3u8Pattern in m3u8Patterns) {
                            val match = m3u8Pattern.find(tokenResponse)?.groupValues?.get(1)
                            if (match != null) {
                                extractedM3u8 = match.replace("\\/", "/")
                                println("DEBUG_EXTRACTOR: Extracted m3u8 from token page: $extractedM3u8")
                                break
                            }
                        }
                        
                        val finalM3u8 = extractedM3u8 ?: directM3u8
                        println("DEBUG_EXTRACTOR: Using m3u8: $finalM3u8")
                        
                        invokeLink(finalM3u8, callback, url)
                        success = true
                        break
                        
                    } catch (e: Exception) {
                        println("DEBUG_EXTRACTOR: Token page failed for path $path: ${e.message}")
                        
                        // 토큰 페이지 실패 시 직접 m3u8 시도
                        try {
                            invokeLink(directM3u8, callback, url)
                            success = true
                            break
                        } catch (e2: Exception) {
                            println("DEBUG_EXTRACTOR: Direct m3u8 also failed for path $path: ${e2.message}")
                        }
                    }
                    
                } catch (e: Exception) {
                    println("DEBUG_EXTRACTOR: Exception with path $path: ${e.message}")
                }
            }
            
            if (!success) {
                println("DEBUG_EXTRACTOR: All path variants failed")
                throw ErrorLoadingException("All path variants failed for ID: $idMatch")
            }
            
        } else if (foundM3u8 != null) {
            // 직접 찾은 m3u8 URL 사용
            println("DEBUG_EXTRACTOR: Using found m3u8 URL: $foundM3u8")
            invokeLink(foundM3u8, callback, url)
            
        } else {
            // 최후의 방법: 전체 응답에서 m3u8 패턴 강제 추출
            println("DEBUG_EXTRACTOR: Trying fallback regex search")
            
            val fallbackRegex = Regex("""https?://[^\s"'<>]+?\.m3u8[^\s"'<>]*""")
            val fallbackMatch = fallbackRegex.findAll(response).map { it.value.replace("\\/", "/") }.firstOrNull()
            
            fallbackMatch?.let {
                val finalUrl = if (it.startsWith("http")) it else domain + it
                println("DEBUG_EXTRACTOR: Fallback found: $finalUrl")
                invokeLink(finalUrl, callback, url)
            } ?: run {
                // HTML에서 m3u8 포함된 script 태그 찾기
                val scriptRegex = Regex("""<script[^>]*>(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
                val scripts = scriptRegex.findAll(response).map { it.groupValues[1] }
                
                for (script in scripts) {
                    val m3u8InScript = Regex("""(https?://[^"\']+\.m3u8)""").find(script)?.value
                    if (m3u8InScript != null) {
                        println("DEBUG_EXTRACTOR: Found m3u8 in script: $m3u8InScript")
                        invokeLink(m3u8InScript, callback, url)
                        return
                    }
                }
                
                throw ErrorLoadingException("No video source found in response. Response length: ${response.length}")
            }
        }
    }

    private suspend fun invokeLink(m3u8Url: String, callback: (ExtractorLink) -> Unit, referer: String) {
        println("DEBUG_EXTRACTOR: Final URL to invoke: $m3u8Url")
        
        // m3u8 요청을 위한 헤더
        val m3u8Headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
            "Accept-Encoding" to "gzip, deflate, br",
            "Referer" to referer,
            "Origin" to "https://player.bunny-frame.online",
            "Sec-Fetch-Dest" to "empty",
            "Sec-Fetch-Mode" to "cors",
            "Sec-Fetch-Site" to "cross-site",
            "Connection" to "keep-alive"
        )
        
        // m3u8 URL이 상대경로인지 확인
        val finalM3u8Url = if (m3u8Url.startsWith("//")) {
            "https:${m3u8Url}"
        } else if (m3u8Url.startsWith("/")) {
            // 도메인 추출 시도
            val domainMatch = Regex("""https?://[^/]+""").find(referer)?.value ?: "https://player.bunny-frame.online"
            "$domainMatch$m3u8Url"
        } else {
            m3u8Url
        }
        
        println("DEBUG_EXTRACTOR: Final m3u8 URL with headers: $finalM3u8Url")
        
        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = finalM3u8Url,
                headers = m3u8Headers, // 헤더 추가
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.quality = Qualities.Unknown.value
                // 추가 M3U8 옵션 설정
                this.isM3u8 = true
            }
        )
    }
}
