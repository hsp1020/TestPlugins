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
        var webView: WebView? = null
        try {
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START <<<")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

            println("[STREMIOSUB-VIXSRC] Request URL: $requestUrl")
            println("[STREMIOSUB-VIXSRC] 🚀 Running Headless WebView to auto-generate token...")

            // 최종 마스터 플레이리스트 URL을 받을 코루틴 객체
            val finalPlaylistUrl = CompletableDeferred<String?>()

            withContext(Dispatchers.Main) {
                webView = WebView(CloudStreamApp.context!!)
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        
                        // VixSrc 통신 중 핵심 M3U8 플레이리스트 캐치
                        // 조건: /playlist/를 포함하고, token이 있으면서, 단일 화질(type=video)이 아닌 마스터 플레이리스트
                        if (url.contains("vixsrc.to/playlist/") && url.contains("token=") && !url.contains("type=")) {
                            println("[STREMIOSUB-VIXSRC] 🎯 PERFECT PLAYLIST CAUGHT: $url")
                            if (!finalPlaylistUrl.isCompleted) {
                                finalPlaylistUrl.complete(url)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView?.loadUrl(requestUrl)
            }

            // 웹뷰가 우회 로직을 풀고 토큰을 생성할 때까지 최대 15초 대기
            val foundUrl = withTimeoutOrNull(15000L) {
                finalPlaylistUrl.await()
            }

            if (foundUrl != null) {
                println("[STREMIOSUB-VIXSRC] 🔥 Extracted Master M3U8: $foundUrl")
                
                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc", foundUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        // VixSrc 영상 서버는 Referer를 빡빡하게 검사함
                        this.headers = mapOf(
                            "Referer" to requestUrl,
                            "Origin" to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
            } else {
                println("[STREMIOSUB-VIXSRC] WebView timed out. No master playlist generated.")
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
        } finally {
            // 메모리 누수 및 백그라운드 오디오 재생 방지를 위해 메인 쓰레드에서 웹뷰 완벽 파괴
            withContext(Dispatchers.Main) {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    println("[STREMIOSUB-VIXSRC] WebView safely destroyed.")
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] Error destroying WebView: ${e.message}")
                }
            }
        }
    }
}
