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
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Ultra-Fast 0.1s Fix) <<<")
            
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
            // ⚡ PHASE 1: FAST-TRACK (API 직접 타격 - 0.1초 소요)
            // ====================================================================
            println("[STREMIOSUB-VIXSRC] ⚡ PHASE 1: Attempting Fast-Track API extraction...")
            try {
                val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
                val apiResponseObj = StremioC.customSession.get(apiUrl, headers = commonHeaders)
                val apiResponse = apiResponseObj.text.replace("\\/", "/") 
                
                var fastUrl: String? = null
                
                // API 응답에서 Embed URL과 실제 영상 ID(actualVid)를 추출!
                val embedMatch = """(/embed/(\d+)[^"'\s\\]*)""".toRegex().find(apiResponse)
                val embedPath = embedMatch?.groupValues?.get(1)
                val actualVid = embedMatch?.groupValues?.get(2) // ex: 700516
                
                if (embedPath != null && actualVid != null) {
                    val embedUrl = baseUrl + embedPath
                    println("[STREMIOSUB-VIXSRC] ⚡ Fast-Track caught Embed URL: $embedUrl")
                    
                    val embedHtml = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = commonHeaders).text
                    val rawPlaylistUrl = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
                    
                    if (rawPlaylistUrl != null) {
                        // ✨ 핵심 해결책: 프론트엔드의 'wrapper' 더미 텍스트를 실제 ID로 치환!
                        fastUrl = rawPlaylistUrl.replace("wrapper", actualVid)
                    }
                }

                if (fastUrl != null && fastUrl.contains("/playlist/")) {
                    masterPlaylistUrl = fastUrl.replace("\\u0026", "&").replace("&amp;", "&")
                    println("[STREMIOSUB-VIXSRC] ⚡ FAST-TRACK SUCCESS! Playlist URL: $masterPlaylistUrl")
                } else {
                    println("[STREMIOSUB-VIXSRC] 🐢 FAST-TRACK Failed to parse. Proceeding to Phase 2 (WebView).")
                }
            } catch (e: Exception) {
                println("[STREMIOSUB-VIXSRC] 🐢 FAST-TRACK Exception: ${e.message}")
            }

            // ====================================================================
            // 🐢 PHASE 2: WEBVIEW FALLBACK (API 차단 시 발동 - 2~3초 소요)
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
