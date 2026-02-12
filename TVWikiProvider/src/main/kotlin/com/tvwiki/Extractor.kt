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

/**
 * BunnyPoorCdn Extractor
 * Version: [2026-02-12-EmbeddedKey]
 * - Feature: Data URI M3U8 Injection (Solves 403 Forbidden Key Fetch Error)
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
            url
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
        var cleanUrl = url.replace("&amp;", "&").trim()
        val cleanReferer = referer ?: "https://tvwiki5.net/"

        try {
            if (cleanUrl.contains(".m3u8")) {
                callback(
                    newExtractorLink(name, name, cleanUrl, ExtractorLinkType.M3U8) {
                        this.referer = cleanReferer
                        this.quality = Qualities.Unknown.value
                    }
                )
                return true
            }
        } catch (e: Exception) {}

        var capturedUrl: String? = null
        // m3u8이 요청될 때까지 대기하여 완벽한 세션 쿠키를 보장함
        val interceptRegex = Regex("""(/c\.html|\.m3u8)""") 

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
            val cookieManager = CookieManager.getInstance()
            val cookie = cookieManager.getCookie(capturedUrl)

            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "https://player.bunny-frame.online/",
                "Origin" to "https://player.bunny-frame.online",
                "Accept" to "*/*",
                "Sec-Ch-Ua" to "\"Chromium\";v=\"122\", \"Not(A:Brand\";v=\"24\", \"Google Chrome\";v=\"122\"",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to "\"Windows\"",
                "Sec-Fetch-Site" to "cross-site",
                "Sec-Fetch-Mode" to "cors"
            )

            if (!cookie.isNullOrEmpty()) {
                headers["Cookie"] = cookie
            }
            
            val finalUrl = if (capturedUrl.contains("c.html")) "$capturedUrl#.m3u8" else capturedUrl
            
            // [핵심 해결책] M3U8을 직접 다운로드하여 Key를 삽입하는 과정
            try {
                val m3u8Response = app.get(finalUrl, headers = headers)
                val m3u8Text = m3u8Response.text
                
                if (m3u8Text.contains("#EXTM3U")) {
                    println("[BunnyPoorCdn] Processing Direct M3U8 Injection...")
                    processAndReturnM3u8(finalUrl, m3u8Text, headers, callback)
                    
                    // 만약을 대비해 원본 URL도 Fallback으로 제공
                    callback(
                        newExtractorLink("$name (Direct)", "$name (Fallback)", finalUrl, ExtractorLinkType.M3U8) {
                            this.referer = "https://player.bunny-frame.online/"
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return true
                }
            } catch (e: Exception) {
                println("[BunnyPoorCdn] Failed to fetch M3U8 directly: ${e.message}")
            }
            
            // 직접 파싱에 실패한 경우 기존 방식 사용
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

    private suspend fun processAndReturnM3u8(
        baseUrl: String, 
        m3u8Text: String, 
        headers: Map<String, String>, 
        callback: (ExtractorLink) -> Unit
    ) {
        if (m3u8Text.contains("#EXT-X-STREAM-INF")) {
            // Master Playlist: 하위 화질별 M3U8 분석
            val lines = m3u8Text.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    var quality = Qualities.Unknown.value
                    val resMatch = Regex("""RESOLUTION=(\d+x\d+)""").find(line)
                    if (resMatch != null) {
                        val height = resMatch.groupValues[1].split("x")[1].toIntOrNull()
                        if (height != null) quality = height
                    }
                    
                    val subUrl = lines.getOrNull(i + 1)?.trim()
                    if (subUrl != null && !subUrl.startsWith("#")) {
                        val absoluteSubUrl = resolveUrl(baseUrl, subUrl)
                        try {
                            val subM3u8Text = app.get(absoluteSubUrl, headers = headers).text
                            if (subM3u8Text.contains("#EXTM3U")) {
                                processMediaPlaylist(absoluteSubUrl, subM3u8Text, headers, quality, callback)
                            }
                        } catch (e: Exception) {
                            println("[BunnyPoorCdn] Sub M3U8 fetch failed: ${e.message}")
                        }
                        i++ 
                    }
                }
                i++
            }
        } else {
            // Media Playlist
            processMediaPlaylist(baseUrl, m3u8Text, headers, Qualities.Unknown.value, callback)
        }
    }

    private suspend fun processMediaPlaylist(
        baseUrl: String,
        m3u8Text: String,
        headers: Map<String, String>,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        var newM3u8Text = m3u8Text
        
        // 1. 암호화 키 직접 다운로드 및 Data URI 삽입
        val keyMatch = Regex("""#EXT-X-KEY:METHOD=([^,]+),URI="([^"]+)"""").find(newM3u8Text)
        if (keyMatch != null) {
            val method = keyMatch.groupValues[1]
            val keyUrl = keyMatch.groupValues[2]
            
            if (method.contains("AES-128")) {
                val absoluteKeyUrl = resolveUrl(baseUrl, keyUrl)
                try {
                    val keyResponse = app.get(absoluteKeyUrl, headers = headers)
                    val keyBytes = keyResponse.body.bytes()
                    
                    if (keyBytes.size == 16) {
                        val base64Key = Base64.encodeToString(keyBytes, Base64.NO_WRAP).trim()
                        val dataUri = "data:application/octet-stream;base64,$base64Key"
                        newM3u8Text = newM3u8Text.replace("URI=\"$keyUrl\"", "URI=\"$dataUri\"")
                        println("[BunnyPoorCdn] AES-128 Key successfully embedded.")
                    }
                } catch (e: Exception) {
                    println("[BunnyPoorCdn] Failed to fetch Key: ${e.message}")
                }
            }
        }
        
        // 2. 비디오 조각(TS) 경로 절대 경로화
        val newLines = newM3u8Text.lines().map { line ->
            if (line.isNotBlank() && !line.startsWith("#")) {
                resolveUrl(baseUrl, line.trim())
            } else {
                line
            }
        }
        newM3u8Text = newLines.joinToString("\n")
        
        // 3. 전체 M3U8을 Data URI로 변환하여 전송
        val base64M3u8 = Base64.encodeToString(newM3u8Text.toByteArray(), Base64.NO_WRAP).trim()
        val finalDataUri = "data:application/vnd.apple.mpegurl;base64,$base64M3u8"
        
        callback(
            newExtractorLink("$name (Inject)", "$name (Embedded Key)", finalDataUri, ExtractorLinkType.M3U8) {
                this.referer = "https://player.bunny-frame.online/"
                this.headers = headers
                this.quality = quality
            }
        )
    }
}
