package com.tvwiki

import android.util.Base64
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.network.WebViewResolver 
import android.webkit.CookieManager
import java.net.URI
import kotlinx.coroutines.delay

/**
 * BunnyPoorCdn Extractor
 * Version: 2026-02-12-Fix-v2
 * Fixes:
 * 1. Waits for CookieManager sync
 * 2. Recursive Master/Media playlist handling
 * 3. Strict Absolute URL resolution
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

    private fun resolveUrl(base: String, url: String): String {
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        return try {
            URI(base).resolve(url).toString()
        } catch (e: Exception) {
            if (url.startsWith("/")) {
                val baseUri = URI(base)
                "${baseUri.scheme}://${baseUri.host}$url"
            } else {
                "$base/$url" // Fallback
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        val cleanUrl = url.replace("&amp;", "&").trim()
        val cleanReferer = referer ?: "https://tvwiki5.net/"

        var capturedUrl: String? = null
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") 

        // 1. WebView로 세션 생성 시도
        val resolver = WebViewResolver(
            interceptUrl = interceptRegex, 
            useOkhttp = false, 
            timeout = 15000L
        )
        
        try {
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to DESKTOP_UA
            )

            val response = app.get(
                url = cleanUrl,
                headers = requestHeaders,
                interceptor = resolver
            )
            
            if (interceptRegex.containsMatchIn(response.url)) {
                capturedUrl = response.url
            }
        } catch (e: Exception) {
            println("[BunnyPoorCdn] WebView failed: ${e.message}")
        }

        if (capturedUrl != null) {
            println("[BunnyPoorCdn] Captured URL: $capturedUrl")
            
            // [CRITICAL] 쿠키 동기화 대기
            delay(1000) 
            
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl) ?: ""
            println("[BunnyPoorCdn] Cookies: $cookie")

            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )
            if (cookie.isNotEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = if (capturedUrl.contains("c.html")) "$capturedUrl#.m3u8" else capturedUrl
            
            // [Fix] M3U8 직접 다운로드 및 재귀 처리 시도
            if (downloadAndProcessM3u8(finalUrl, headers, callback)) {
                return true
            }

            // 실패 시 원본 링크 전달 (Fallback)
            callback(
                newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                    this.referer = "https://player.bunny-frame.online/"
                    this.headers = headers
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }
        return false
    }

    // [New] 재귀적 M3U8 처리 함수
    private suspend fun downloadAndProcessM3u8(
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            println("[BunnyPoorCdn] Downloading M3U8: $url")
            val response = app.get(url, headers = headers)
            if (!response.isSuccessful) return false
            
            val content = response.text
            
            // Case A: Master Playlist (다른 M3U8을 포함)
            if (content.contains("#EXT-X-STREAM-INF")) {
                println("[BunnyPoorCdn] Master Playlist detected.")
                val lines = content.lines()
                var bestUrl: String? = null
                
                // 단순히 첫 번째 또는 해상도가 가장 높은 스트림을 찾음
                for (i in lines.indices) {
                    if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                         // 다음 줄이 URL
                         val nextLine = lines.getOrNull(i + 1)
                         if (!nextLine.isNullOrBlank() && !nextLine.startsWith("#")) {
                             bestUrl = resolveUrl(url, nextLine.trim())
                             break // 첫 번째 발견된 것 사용 (보통 최고화질)
                         }
                    }
                }

                if (bestUrl != null) {
                    // 재귀 호출
                    return downloadAndProcessM3u8(bestUrl, headers, callback)
                }
                return false
            }

            // Case B: Media Playlist (실제 TS 및 Key 포함)
            if (content.contains("#EXTINF")) {
                println("[BunnyPoorCdn] Media Playlist detected.")
                val embeddedM3u8 = embedKeyAndResolveUrls(url, content, headers)
                
                val base64Data = Base64.encodeToString(embeddedM3u8.toByteArray(), Base64.NO_WRAP)
                val dataUri = "data:application/vnd.apple.mpegurl;base64,$base64Data"
                
                callback(
                    newExtractorLink("$name (Embedded)", "$name (Embedded)", dataUri, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }

        } catch (e: Exception) {
            println("[BunnyPoorCdn] M3U8 Process Error: ${e.message}")
            e.printStackTrace()
        }
        return false
    }

    private suspend fun embedKeyAndResolveUrls(baseUrl: String, content: String, headers: Map<String, String>): String {
        val newLines = mutableListOf<String>()
        val lines = content.lines()

        for (line in lines) {
            if (line.startsWith("#EXT-X-KEY")) {
                // Key 다운로드 및 교체
                val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                if (uriMatch != null) {
                    val keyUrl = uriMatch.groupValues[1]
                    val absoluteKeyUrl = resolveUrl(baseUrl, keyUrl)
                    
                    println("[BunnyPoorCdn] Fetching Key: $absoluteKeyUrl")
                    try {
                        val keyResponse = app.get(absoluteKeyUrl, headers = headers)
                        if (keyResponse.isSuccessful) {
                            val keyBytes = keyResponse.body.bytes()
                            val b64Key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                            val dataUri = "data:application/octet-stream;base64,$b64Key"
                            
                            val newLine = line.replace(keyUrl, dataUri)
                            newLines.add(newLine)
                            continue
                        } else {
                             println("[BunnyPoorCdn] Key fetch failed code: ${keyResponse.code}")
                        }
                    } catch (e: Exception) {
                        println("[BunnyPoorCdn] Key fetch error: ${e.message}")
                    }
                }
                newLines.add(line) // 실패해도 원본 라인 유지
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                // TS 파일 절대 경로 변환
                newLines.add(resolveUrl(baseUrl, line.trim()))
            } else {
                newLines.add(line)
            }
        }
        return newLines.joinToString("\n")
    }
}
