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
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Optimized) <<<")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

            val finalPlaylistUrl = CompletableDeferred<String?>()

            // 메인 쓰레드에서 초경량 웹뷰 생성
            withContext(Dispatchers.Main) {
                webView = WebView(CloudStreamApp.context!!)
                
                // [최적화 1] 화면에 그릴 필요가 없으므로 GPU 하드웨어 가속 비활성화 (RAM 절약)
                webView?.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
                
                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    // [최적화 2] 데이터, 배터리, CPU 소모를 막기 위해 이미지 및 미디어 자동 재생 완벽 차단
                    loadsImagesAutomatically = false
                    blockNetworkImage = true
                    mediaPlaybackRequiresUserGesture = true 
                    userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val url = request?.url?.toString() ?: ""
                        
                        // [최적화 3] 불필요한 트래킹, 폰트, CSS 요청은 빈 응답을 던져 네트워크 낭비 차단
                        if (url.endsWith(".css") || url.endsWith(".woff2") || url.contains("analytics") || url.contains("ping.gif")) {
                            return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                        }

                        // 타겟 플레이리스트 캡처
                        if (url.contains("vixsrc.to/playlist/") && url.contains("token=") && !url.contains("type=")) {
                            if (!finalPlaylistUrl.isCompleted) {
                                println("[STREMIOSUB-VIXSRC] 🎯 PERFECT PLAYLIST CAUGHT: $url")
                                finalPlaylistUrl.complete(url)
                                
                                // [최적화 4] 타겟을 찾자마자 메인 쓰레드에 웹뷰 렌더링 즉각 중단 명령 (CPU 사용률 감소)
                                view?.post { view.stopLoading() }
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                }
                
                webView?.loadUrl(requestUrl)
            }

            // 최대 15초 대기
            val foundUrl = withTimeoutOrNull(15000L) {
                finalPlaylistUrl.await()
            }

            if (foundUrl != null) {
                println("[STREMIOSUB-VIXSRC] 🔥 Extracted Master M3U8: $foundUrl")
                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc", foundUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
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
            // [최적화 5] 메인 쓰레드에서 완벽한 메모리 해제 (가비지 컬렉터 최적화)
            withContext(Dispatchers.Main) {
                try {
                    webView?.apply {
                        stopLoading()
                        loadUrl("about:blank") // 빈 페이지로 덮어씌워 내부 JS 컨텍스트 삭제
                        clearHistory()
                        clearCache(true)
                        removeAllViews()
                        destroy() // 코어 객체 파괴
                    }
                    webView = null // 참조 해제
                    println("[STREMIOSUB-VIXSRC] WebView safely & aggressively destroyed.")
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] Error destroying WebView: ${e.message}")
                }
            }
        }
    }
}
