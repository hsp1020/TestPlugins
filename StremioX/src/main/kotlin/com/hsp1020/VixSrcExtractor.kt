package com.hsp1020

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException
import java.io.ByteArrayInputStream

object VixSrcExtractor {

    // 긴 로그가 잘리지 않도록 분할해서 찍어주는 함수
    private fun logLargeString(tag: String, msg: String) {
        if (msg.isBlank()) {
            println("$tag [EMPTY BODY]")
            return
        }
        var index = 0
        while (index < msg.length) {
            val end = minOf(index + 3000, msg.length)
            println("$tag [PART] ${msg.substring(index, end)}")
            index = end
        }
    }

    suspend fun invokeVixSrc(
        loadData: StremioC.LoadData,
        targetType: String,
        globalSeason: Int?,
        globalEpisode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var webView: WebView? = null
        try {
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Hybrid Fast-Track + Debug) <<<")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

            val commonHeaders = mapOf(
                "Referer" to requestUrl,
                "Origin" to baseUrl,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            var masterPlaylistUrl: String? = null

            // ====================================================================
            // ⚡ PHASE 1: FAST-TRACK (API 직접 타격 및 검증)
            // ====================================================================
            println("[STREMIOSUB-VIXSRC] ⚡ PHASE 1: Attempting Fast-Track API extraction...")
            try {
                val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
                println("[STREMIOSUB-VIXSRC] ⚡ API Request URL: $apiUrl")

                val apiResponseObj = StremioC.customSession.get(apiUrl, headers = commonHeaders)
                println("[STREMIOSUB-VIXSRC] ⚡ API Response Code: ${apiResponseObj.code}")
                println("[STREMIOSUB-VIXSRC] ⚡ API Headers: ${apiResponseObj.headers}")
                
                val apiResponse = apiResponseObj.text
                println("[STREMIOSUB-VIXSRC] ⚡ API Response Body Dump:")
                logLargeString("[STREMIOSUB-VIXSRC-API-DUMP]", apiResponse)
                
                // 정규식 1: 직접 플레이리스트 주소가 있는지 탐색
                var fastUrl = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex().find(apiResponse)?.groupValues?.get(1)
                println("[STREMIOSUB-VIXSRC] ⚡ Direct Playlist Regex Match: ${fastUrl ?: "NONE"}")
                
                if (fastUrl == null) {
                    // 정규식 2: Embed URL이 있는지 탐색
                    val embedUrl = """(https://vixsrc\.to/embed/[^"'\s\\]+)""".toRegex().find(apiResponse)?.groupValues?.get(1)
                    println("[STREMIOSUB-VIXSRC] ⚡ Embed URL Regex Match: ${embedUrl ?: "NONE"}")

                    if (embedUrl != null) {
                        println("[STREMIOSUB-VIXSRC] ⚡ Requesting Embed URL to extract token...")
                        val embedResponseObj = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = commonHeaders)
                        println("[STREMIOSUB-VIXSRC] ⚡ Embed Response Code: ${embedResponseObj.code}")
                        
                        val embedHtml = embedResponseObj.text
                        println("[STREMIOSUB-VIXSRC] ⚡ Embed HTML Dump:")
                        logLargeString("[STREMIOSUB-VIXSRC-EMBED-DUMP]", embedHtml)
                        
                        val token = """token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                        val expires = """expires["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                        val vId = """id["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                        
                        println("[STREMIOSUB-VIXSRC] ⚡ Extracted from Embed -> Token: $token, Expires: $expires, ID: $vId")

                        if (token != null && expires != null && vId != null) {
                            fastUrl = "$baseUrl/playlist/$vId?token=$token&expires=$expires"
                        } else {
                            fastUrl = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
                            println("[STREMIOSUB-VIXSRC] ⚡ Fallback Direct Playlist Match from Embed: ${fastUrl ?: "NONE"}")
                        }
                    }
                }

                if (fastUrl != null && fastUrl.contains("/playlist/")) {
                    masterPlaylistUrl = fastUrl.replace("\\u0026", "&").replace("&amp;", "&")
                    println("[STREMIOSUB-VIXSRC] ⚡ FAST-TRACK SUCCESS! Playlist URL: $masterPlaylistUrl")
                } else {
                    println("[STREMIOSUB-VIXSRC] 🐢 FAST-TRACK Failed. Proceeding to Phase 2 (WebView).")
                }
            } catch (e: Exception) {
                println("[STREMIOSUB-VIXSRC] 🐢 FAST-TRACK Exception: ${e.message}")
            }

            // ====================================================================
            // 🐢 PHASE 2: WEBVIEW FALLBACK (API 차단 시 발동)
            // ====================================================================
            if (masterPlaylistUrl == null) {
                println("[STREMIOSUB-VIXSRC] 🐢 PHASE 2: Running Headless WebView Fallback...")
                val deferredUrl = CompletableDeferred<String?>()

                withContext(Dispatchers.Main) {
                    webView = WebView(CloudStreamApp.context!!)
                    webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                    
                    webView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadsImagesAutomatically = false
                        blockNetworkImage = true
                        mediaPlaybackRequiresUserGesture = true 
                        userAgentString = commonHeaders["User-Agent"]
                    }

                    webView?.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val url = request?.url?.toString() ?: ""
                            
                            if (url.endsWith(".css") || url.endsWith(".woff2") || url.contains("analytics") || url.contains("ping.gif")) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }

                            if (url.contains("vixsrc.to/playlist/") && url.contains("token=") && !url.contains("type=")) {
                                if (!deferredUrl.isCompleted) {
                                    println("[STREMIOSUB-VIXSRC] 🎯 WEBVIEW CAUGHT PLAYLIST: $url")
                                    deferredUrl.complete(url)
                                    view?.post { view.stopLoading() }
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }
                    }
                    webView?.loadUrl(requestUrl)
                }

                val foundUrl = withTimeoutOrNull(15000L) { deferredUrl.await() }
                if (foundUrl != null) {
                    masterPlaylistUrl = foundUrl
                }
            }

            // ====================================================================
            // 🎬 PHASE 3: M3U8 화질 필터링 및 플레이어 전송
            // ====================================================================
            if (masterPlaylistUrl != null) {
                println("[STREMIOSUB-VIXSRC] 🔥 Final Master M3U8: $masterPlaylistUrl")
                
                try {
                    val m3u8Links = M3u8Helper.generateM3u8("VixSrc", masterPlaylistUrl, requestUrl, headers = commonHeaders)
                    
                    if (m3u8Links.isNotEmpty()) {
                        val sortedLinks = m3u8Links.sortedByDescending { it.quality }
                        val highQualityLinks = sortedLinks.filter { it.quality >= Qualities.P1080.value }
                        
                        val finalLinks = highQualityLinks.ifEmpty { 
                            val maxQuality = sortedLinks.first().quality
                            sortedLinks.filter { it.quality == maxQuality }
                        }
                        
                        finalLinks.forEach { link ->
                            println("[STREMIOSUB-VIXSRC] 🎬 Added Filtered Track: Quality ${link.quality} -> ${link.url}")
                            callback.invoke(link)
                        }
                    } else {
                        throw Exception("M3u8Helper parsing returned empty list.")
                    }
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] M3u8Helper parsing failed, using fallback: ${e.message}")
                    callback.invoke(
                        newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = commonHeaders
                        }
                    )
                }
            } else {
                println("[STREMIOSUB-VIXSRC] Extraction Failed. Both Fast-Track and WebView timed out.")
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
        } finally {
            withContext(Dispatchers.Main) {
                try {
                    webView?.apply {
                        stopLoading()
                        loadUrl("about:blank") 
                        clearHistory()
                        clearCache(true)
                        removeAllViews()
                        destroy() 
                    }
                    webView = null 
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] Error destroying WebView: ${e.message}")
                }
            }
        }
    }
}
