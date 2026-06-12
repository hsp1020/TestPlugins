package com.hsp1020

import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.CancellationException

object VixSrcExtractor {

    suspend fun invokeVixSrc(
        loadData: StremioC.LoadData,
        targetType: String,
        globalSeason: Int?,
        globalEpisode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START <<<")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

            println("[STREMIOSUB-VIXSRC] Request URL: $requestUrl")
            println("[STREMIOSUB-VIXSRC] 🚀 Running Headless WebView Sniffer to bypass DevTools block & React SPA...")

            // 가로챈 URL을 코루틴으로 반환받기 위한 객체
            val embedOrPlaylistUrl = CompletableDeferred<String?>()

            withContext(Dispatchers.Main) {
                val webView = WebView(CloudStreamApp.context!!)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

                // 웹뷰에서 일어나는 모든 네트워크 요청을 가로채는 인터셉터
                webView.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        
                        // 쓸데없는 js, css, 폰트 요청은 제외하고 핵심 API 통신만 로그캣에 덤프
                        if (url.contains("vixsrc.to")) {
                            if (!url.endsWith(".js") && !url.endsWith(".css") && !url.endsWith(".woff2") && !url.endsWith(".png")) {
                                println("[STREMIOSUB-VIXSRC-WEBVIEW] Intercepted Network Request: $url")
                            }
                            
                            // 우리가 찾던 embed 또는 playlist 또는 api 호출을 발견하면 캐치!
                            if (url.contains("/embed/") || url.contains("/playlist/") || url.contains("/api/")) {
                                println("[STREMIOSUB-VIXSRC] 🎯 CRITICAL URL FOUND: $url")
                                
                                // embed나 playlist를 찾으면 즉시 대기 종료
                                if ((url.contains("/embed/") || url.contains("/playlist/")) && !embedOrPlaylistUrl.isCompleted) {
                                    embedOrPlaylistUrl.complete(url)
                                }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                // 가상 브라우저에서 실제 접속 시작
                webView.loadUrl(requestUrl)
            }

            // 웹뷰가 자바스크립트를 실행하고 네트워크 요청을 보낼 때까지 최대 15초 대기
            val foundUrl = withTimeoutOrNull(15000L) {
                embedOrPlaylistUrl.await()
            }

            if (foundUrl != null) {
                println("[STREMIOSUB-VIXSRC] WebView Sniffer successfully captured URL: $foundUrl")
                var finalPlaylistUrl = foundUrl
                
                // 만약 /embed/ URL을 잡았다면, 해당 URL의 HTML을 한 번 더 긁어서 최종 m3u8을 가져옴
                if (foundUrl.contains("/embed/")) {
                    println("[STREMIOSUB-VIXSRC] Fetched Embed URL. Parsing for Final Playlist Token...")
                    val embedHtml = StremioC.customSession.get(foundUrl, referer = requestUrl).text
                    
                    val token = """token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                    val expires = """expires["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                    val vId = """id["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                    
                    if (token != null && expires != null && vId != null) {
                        finalPlaylistUrl = "$baseUrl/playlist/$vId?token=$token&expires=$expires"
                    } else {
                        val directMatch = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
                        if (directMatch != null) finalPlaylistUrl = directMatch
                    }
                }

                if (finalPlaylistUrl.contains("/playlist/")) {
                    val cleanPlaylistUrl = finalPlaylistUrl.replace("\\u0026", "&").replace("&amp;", "&")
                    println("[STREMIOSUB-VIXSRC] 🔥 FINAL PLAYLIST URL READY: $cleanPlaylistUrl")
                    
                    callback.invoke(
                        newExtractorLink("VixSrc", "VixSrc", cleanPlaylistUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = mapOf(
                                "Referer" to requestUrl,
                                "Origin" to baseUrl,
                                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                            )
                        }
                    )
                } else {
                    println("[STREMIOSUB-VIXSRC] Failed to construct final playlist URL from Embed.")
                }

            } else {
                println("[STREMIOSUB-VIXSRC] WebView Sniffer timed out (15s). No embed/playlist URL found. Check the intercepted API logs.")
            }

            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() END <<<")

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] invokeVixSrc Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] invokeVixSrc Crash: ${e.message}")
        }
    }
}
