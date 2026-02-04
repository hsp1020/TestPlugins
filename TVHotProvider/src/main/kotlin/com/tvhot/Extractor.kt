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

        // 서버 번호 추출 (여러 패턴 지원)
        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1)
            ?: Regex("""every(\d+)\.poorcdn\.com""").find(cleanUrl)?.groupValues?.get(1)
            ?: "9"
        
        val domain = "https://every$serverNum.poorcdn.com"
        
        // 1. 플레이어 페이지 접속
        val playerRes = app.get(cleanUrl, referer = cleanReferer, headers = browserHeaders)
        val responseText = playerRes.text
        val cookieMap = playerRes.cookies

        // [핵심 수정] 여러 패턴으로 ID 추출 시도
        val videoPath = extractVideoPath(responseText, cleanUrl)
        
        if (videoPath != null) {
            val (path, id) = videoPath
            println("DEBUG: Found path=$path, id=$id")
            
            val tokenUrl = "$domain$path$id/c.html"
            val directM3u8 = "$domain$path$id/index.m3u8"

            try {
                // 2. c.html 접속
                val tokenRes = app.get(tokenUrl, referer = cleanUrl, headers = browserHeaders)
                val combinedCookies = cookieMap + tokenRes.cookies
                
                val realM3u8 = extractM3u8FromToken(tokenRes.text) ?: directM3u8
                
                invokeLink(realM3u8, cleanUrl, combinedCookies, callback)
            } catch (e: Exception) {
                // c.html 실패 시 직접 m3u8 접근
                println("DEBUG: c.html failed, trying direct m3u8: $directM3u8")
                invokeLink(directM3u8, cleanUrl, cookieMap, callback)
            }
        } else {
            // [대체 방법] src 파라미터에서 직접 m3u8 URL 구성
            val m3u8FromSrc = extractM3u8FromSrcParam(cleanUrl, domain)
            if (m3u8FromSrc != null) {
                invokeLink(m3u8FromSrc, cleanUrl, cookieMap, callback)
            } else {
                throw Exception("Failed to extract video path from: $cleanUrl")
            }
        }
    }

    // [수정] 여러 패턴으로 비디오 경로 추출
    private fun extractVideoPath(text: String, url: String): Pair<String, String>? {
        // 패턴 1: /v/[a-z]/[id] (예: /v/e/... 또는 /v/f/...)
        val pattern1 = Regex("""(?i)(/v/[a-z]/)([a-z0-9]{32,45})""")
        val match1 = pattern1.find(text) ?: pattern1.find(url)
        
        if (match1 != null) {
            return Pair(match1.groupValues[1], match1.groupValues[2])
        }
        
        // 패턴 2: /[id]/ (짧은 ID 형식)
        val pattern2 = Regex("""(?i)(/v/[a-z]/)([a-z0-9]{20,50})""")
        val match2 = pattern2.find(text) ?: pattern2.find(url)
        
        if (match2 != null) {
            return Pair(match2.groupValues[1], match2.groupValues[2])
        }
        
        // 패턴 3: src 파라미터에서 base64 디코딩된 내용 찾기
        val srcParam = Regex("""[?&]src=([^&]+)""").find(url)?.groupValues?.get(1)
        if (srcParam != null && srcParam.length > 50) {
            // base64 디코딩 시도 (히트 아일랜드에서 사용하는 패턴)
            try {
                val decoded = android.util.Base64.decode(srcParam, android.util.Base64.DEFAULT)
                val decodedStr = decoded.toString(Charsets.UTF_8)
                val pathMatch = Regex("""(/v/[a-z]/[a-z0-9]+)""").find(decodedStr)
                if (pathMatch != null) {
                    val fullPath = pathMatch.value
                    val parts = fullPath.split("/")
                    if (parts.size >= 4) {
                        val path = "/${parts[1]}/${parts[2]}/"
                        val id = parts[3]
                        return Pair(path, id)
                    }
                }
            } catch (e: Exception) {
                // base64 디코딩 실패 시 무시
            }
        }
        
        return null
    }

    private fun extractM3u8FromToken(tokenText: String): String? {
        // 여러 패턴으로 m3u8 URL 추출
        val patterns = listOf(
            Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']"""),
            Regex("""src\s*[:=]\s*["']([^"']+\.m3u8)["']"""),
            Regex("""file\s*[:=]\s*["']([^"']+\.m3u8)["']"""),
            Regex("""(https?://[^\s<>"']+\.m3u8)""")
        )
        
        for (pattern in patterns) {
            val match = pattern.find(tokenText)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        
        return null
    }

    private fun extractM3u8FromSrcParam(url: String, domain: String): String? {
        val srcParam = Regex("""[?&]src=([^&]+)""").find(url)?.groupValues?.get(1)
        if (srcParam != null) {
            return if (srcParam.startsWith("http")) {
                srcParam
            } else if (srcParam.startsWith("/")) {
                "$domain$srcParam"
            } else {
                "$domain/$srcParam"
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
