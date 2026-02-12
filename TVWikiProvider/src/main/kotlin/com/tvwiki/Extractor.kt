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
 * Version: 2026-02-12-Verified-Fix
 * - [Fix] User-Agent Mismatch (2001 Error): Uses Mobile UA to match WebView.
 * - [Feature] Key Embedding: Prevents Key fetch errors by embedding key directly into M3U8.
 */
class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki Player"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    // [검증 완료] WebView 기본 UA와 일치시키는 것이 핵심입니다.
    // 기존 Windows UA -> Mobile UA로 변경
    private val MOBILE_UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

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
                "$base/$url"
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

        // 1. WebView 로딩 (세션 생성)
        val resolver = WebViewResolver(
            interceptUrl = interceptRegex, 
            useOkhttp = false, 
            timeout = 15000L
        )
        
        try {
            // [중요] WebView에도 Mobile UA를 강제로 주입하여 통일성 확보
            val requestHeaders = mapOf(
                "Referer" to cleanReferer, 
                "User-Agent" to MOBILE_UA
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
            println("[BunnyPoorCdn] Captured: $capturedUrl")
            
            // 쿠키 동기화 대기
            delay(1000)
            
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl) ?: ""
            println("[BunnyPoorCdn] Cookie: $cookie")

            // [핵심] 헤더에 Mobile UA 사용
            val headers = mutableMapOf(
                "User-Agent" to MOBILE_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*"
            )
            if (cookie.isNotEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = if (capturedUrl.contains("c.html")) "$capturedUrl#.m3u8" else capturedUrl
            
            // 2. M3U8 다운로드 및 키 임베딩 시도
            val embeddedSuccess = downloadAndProcessM3u8(finalUrl, headers, callback)
            
            if (!embeddedSuccess) {
                println("[BunnyPoorCdn] Embedding failed. Fallback to original URL.")
                // 실패 시 원본 링크 전달 (헤더 필수 포함)
                callback(
                    newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "https://player.bunny-frame.online/"
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }
        return false
    }

    private suspend fun downloadAndProcessM3u8(
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            // Mobile UA로 요청
            val response = app.get(url, headers = headers)
            
            if (!response.isSuccessful) {
                println("[BunnyPoorCdn] M3U8 Download Failed Code: ${response.code}")
                return false 
            }
            
            val content = response.text
            
            // Master Playlist (재귀 처리)
            if (content.contains("#EXT-X-STREAM-INF")) {
                val lines = content.lines()
                for (i in lines.indices) {
                    if (lines[i].startsWith("#EXT-X-STREAM-INF")) {
                         val nextLine = lines.getOrNull(i + 1)
                         if (!nextLine.isNullOrBlank() && !nextLine.startsWith("#")) {
                             val bestUrl = resolveUrl(url, nextLine.trim())
                             return downloadAndProcessM3u8(bestUrl, headers, callback)
                         }
                    }
                }
                return false 
            }

            // Media Playlist (키 임베딩)
            if (content.contains("#EXTINF")) {
                val (newM3u8, success) = embedKeyAndResolveUrls(url, content, headers)
                if (!success) return false
                
                val base64Data = Base64.encodeToString(newM3u8.toByteArray(), Base64.NO_WRAP)
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
            println("[BunnyPoorCdn] Process Error: ${e.message}")
        }
        return false
    }

    private suspend fun embedKeyAndResolveUrls(
        baseUrl: String, 
        content: String, 
        headers: Map<String, String>
    ): Pair<String, Boolean> {
        val newLines = mutableListOf<String>()
        val lines = content.lines()
        var allKeysDownloaded = true

        for (line in lines) {
            if (line.startsWith("#EXT-X-KEY")) {
                val uriMatch = Regex("""URI="([^"]+)"""").find(line)
                if (uriMatch != null) {
                    val keyUrl = uriMatch.groupValues[1]
                    val absoluteKeyUrl = resolveUrl(baseUrl, keyUrl)
                    
                    try {
                        // 키 다운로드 시에도 Mobile UA 헤더 사용
                        val keyResponse = app.get(absoluteKeyUrl, headers = headers)
                        if (keyResponse.isSuccessful) {
                            val keyBytes = keyResponse.body.bytes()
                            val b64Key = Base64.encodeToString(keyBytes, Base64.NO_WRAP)
                            val dataUri = "data:application/octet-stream;base64,$b64Key"
                            
                            val newLine = line.replace(keyUrl, dataUri)
                            newLines.add(newLine)
                        } else {
                            println("[BunnyPoorCdn] Key fetch failed: ${keyResponse.code}")
                            allKeysDownloaded = false
                            break 
                        }
                    } catch (e: Exception) {
                        println("[BunnyPoorCdn] Key exception: ${e.message}")
                        allKeysDownloaded = false
                        break
                    }
                } else {
                    newLines.add(line)
                }
            } else if (line.isNotBlank() && !line.startsWith("#")) {
                newLines.add(resolveUrl(baseUrl, line.trim()))
            } else {
                newLines.add(line)
            }
        }

        return if (allKeysDownloaded) {
            Pair(newLines.joinToString("\n"), true)
        } else {
            Pair("", false)
        }
    }
}
