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

    private val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S911B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Mobile Safari/537.36"
    
    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
        "Sec-Ch-Ua" to "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"121\", \"Google Chrome\";v=\"121\"",
        "Sec-Ch-Ua-Mobile" to "?1",
        "Sec-Ch-Ua-Platform" to "\"Android\""
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim()

        val serverNum = extractServerNumber(cleanUrl)
        
        // ***** 핵심 수정: serverNum이 null이면 기존 로직 유지 *****
        val domain = if (serverNum != null) {
            "https://every$serverNum.poorcdn.com"
        } else {
            // 기존 로직: s= 파라미터 또는 도메인에서 숫자 추출, 없으면 "9"
            val defaultServer = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
                ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
                ?: "9"
            "https://every$defaultServer.poorcdn.com"
        }
        
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        // ***** 완전히 수정된 ID 추출 로직 - 기존 src= 로직 삭제 *****
        val idMatch = extractRealVideoId(cleanUrl, responseText)

        if (idMatch != null) {
            val tokenUrl = "$domain/v/f/$idMatch/c.html"
            val directM3u8 = "$domain/v/f/$idMatch/index.m3u8"

            try {
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                val combinedCookies = cookieMap + tokenRes.cookies
                
                val realM3u8 = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(tokenRes.text)?.groupValues?.get(1)
                    ?: directM3u8
                
                invokeLink(realM3u8, cleanUrl, combinedCookies, callback)
            } catch (e: Exception) {
                invokeLink(directM3u8, cleanUrl, cookieMap, callback)
            }
        } else {
            throw ErrorLoadingException("영상 ID를 찾을 수 없습니다.")
        }
    }

    /**
     * upnext에서 server 번호 추출 시도
     */
    private fun extractServerNumber(url: String): String? {
        try {
            val upnextRegex = Regex("""upnext=([^&]+)""")
            val upnextMatch = upnextRegex.find(url) ?: return null
            
            val upnextValue = upnextMatch.groupValues[1]
            val decoded = java.net.URLDecoder.decode(upnextValue, "UTF-8")
            
            // upnext JSON의 첫 번째 항목에서 server 값 추출
            val serverPattern = Regex(""""server":"(\d+)"""")
            val match = serverPattern.find(decoded)
            
            return match?.groupValues?.get(1)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * 진짜 영상 ID를 추출하는 메인 함수
     * 기존의 src= 값(128자 해시)은 완전히 무시
     */
    private fun extractRealVideoId(url: String, responseText: String): String? {
        // 우선순위 1: upnext 데이터에서 현재 영상 ID 추출
        val upnextId = extractIdFromUpnext(url)
        if (upnextId != null) {
            return upnextId
        }
        
        // 우선순위 2: responseText에서 /v/f/[id]/index.m3u8 패턴 추출
        val responseId = extractIdFromResponse(responseText)
        if (responseId != null) {
            return responseId
        }
        
        // 우선순위 3: URL에서 /v/f/ 패턴 추출 (기존 로직은 src= 값만 제외)
        return extractIdFromUrl(url)
    }

    /**
     * upnext 파라미터에서 영상 ID 추출
     */
    private fun extractIdFromUpnext(url: String): String? {
        try {
            val upnextRegex = Regex("""upnext=([^&]+)""")
            val upnextMatch = upnextRegex.find(url) ?: return null
            
            val upnextValue = upnextMatch.groupValues[1]
            val decoded = java.net.URLDecoder.decode(upnextValue, "UTF-8")
            
            // "src":"/v/f/[id]/index.m3u8" 패턴 찾기
            val srcPattern = Regex(""""src":"/v/f/([a-f0-9]{32,50})/index\\.m3u8"""")
            val match = srcPattern.find(decoded)
            
            return match?.groupValues?.get(1)
        } catch (e: Exception) {
            return null
        }
    }

    /**
     * responseText에서 /v/f/[id]/index.m3u8 패턴 추출
     */
    private fun extractIdFromResponse(responseText: String): String? {
        val pattern = Regex("""/v/f/([a-f0-9]{32,50})/index\.m3u8""")
        val match = pattern.find(responseText)
        return match?.groupValues?.get(1)
    }

    /**
     * URL에서 /v/f/ 패턴 추출
     */
    private fun extractIdFromUrl(url: String): String? {
        // ***** 기존 로직 수정: src= 값은 완전히 무시 *****
        val pattern = Regex("""/v/f/([a-f0-9]{32,50})""")
        
        // URL에서 모든 /v/f/ 패턴 찾기
        val matches = pattern.findAll(url).toList()
        
        // src=로 시작하는 파라미터에서 찾은 ID는 무시
        for (match in matches) {
            val matchedString = match.value
            // src= 다음에 오는 /v/f/ 패턴인지 확인
            val srcPattern = Regex("""src=[^&]*/v/f/([a-f0-9]{32,50})""")
            val isFromSrc = srcPattern.find(url)?.groupValues?.get(1) == match.groupValues[1]
            
            if (!isFromSrc) {
                return match.groupValues[1]
            }
        }
        
        return null
    }

    private suspend fun invokeLink(m3u8Url: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val cleanM3u8 = m3u8Url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cookieString = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

        callback.invoke(
            newExtractorLink(
                source = name,
                name = name,
                url = cleanM3u8,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = referer
                this.headers = browserHeaders.toMutableMap().apply {
                    if (cookieString.isNotEmpty()) put("Cookie", cookieString)
                    put("Referer", referer)
                }
                this.quality = Qualities.Unknown.value
            }
        )
    }
}
