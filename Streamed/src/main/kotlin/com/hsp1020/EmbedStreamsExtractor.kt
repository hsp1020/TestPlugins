package com.hsp1020

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume


open class EmbedSporty(context: Context) : EmbedStreams(context) {
    override val name = "EmbedSporty"
    override val mainUrl = "https://embedsporty.top"
}

open class EmbedST(context: Context) : EmbedStreams(context) {
    override val name = "EmbedST"
    override val mainUrl = "https://embed.st"
}

open class EmbedStreams(context: Context) : ExtractorApi() {
    override val name = "EmbedStreams"
    override val mainUrl = "https://embedsports.top"
    override val requiresReferer = true
    private var appContext = context

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = coroutineScope {
        Log.d("kraptor_$name", "================== Extractor Start ==================")
        Log.d("kraptor_$name", "Target URL: $url")
        Log.d("kraptor_$name", "Referer: $referer")
        try {
            val videoUrl = withContext(Dispatchers.Main) {
                getVideoUrlWithWebView(appContext, url)
            }
            Log.d("kraptor_$name", "WebView returned videoUrl: $videoUrl")
            if (videoUrl != null) {
                processVideoUrl(videoUrl, callback)
            } else {
                Log.e("kraptor_$name", "❌ videoUrl is null. Extraction failed.")
            }
        } catch (e: Exception) { 
            Log.e("kraptor_$name", "❌ Error in getUrl: ${e.message}", e)
        }
    }

    private suspend fun getVideoUrlWithWebView(context: Context, url: String): String? {
        Log.d("kraptor_webview", "Initializing WebView on Main Thread for URL: $url")
        return withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<String?> { cont ->
                val captured = AtomicBoolean(false)
                var webView: WebView? = null

                try {
                    webView = WebView(context.applicationContext).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = true
                        settings.allowContentAccess = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        
                        // Cloudflare 우회를 위해 데스크톱 User-Agent 명시적 지정
                        settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.48 Safari/537.36"

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                Log.d("kraptor_webview", "Page load started: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                Log.d("kraptor_webview", "Page load finished: $url")

                                // 1. 웹뷰에 로드된 전체 HTML 소스코드를 읽어와 청크 단위로 로그에 출력
                                view?.evaluateJavascript("(function() { return document.documentElement.outerHTML; })();") { rawHtml ->
                                    if (rawHtml != null) {
                                        Log.d("kraptor_webview_html", "┌==================== [HTML SOURCE CODE START] ====================┐")
                                        val cleanedHtml = unescapeJsonString(rawHtml)
                                        logInChunks("kraptor_webview_html", cleanedHtml)
                                        Log.d("kraptor_webview_html", "└==================== [HTML SOURCE CODE END] ====================┘")
                                    } else {
                                        Log.e("kraptor_webview_html", "⚠️ evaluateJavascript returned null HTML source.")
                                    }
                                }

                                // 2. 재생 버튼(JWPlayer 등) 자동 클릭 유도
                                Handler(Looper.getMainLooper()).postDelayed({
                                    Log.d("kraptor_webview", "Evaluating Play button click script...")
                                    view?.evaluateJavascript("""
                                        (function() {
                                            try {
                                                var playButton = document.querySelector('.jw-icon-display');
                                                if (playButton) {
                                                    playButton.click();
                                                    return 'Success: Clicked play button (.jw-icon-display)';
                                                }
                                                
                                                if (typeof jwplayer !== 'undefined') {
                                                    jwplayer().play();
                                                    return 'Success: Played using JWPlayer API';
                                                }
                                                
                                                return 'Warning: No play button found in current DOM';
                                            } catch(e) {
                                                return 'Error in JS: ' + e.message;
                                            }
                                        })();
                                    """.trimIndent()) { result ->
                                        Log.d("kraptor_webview", "Play JS result: $result")
                                    }
                                }, 2000) // 페이지가 안정적으로 그리기 완료될 때까지 2초 대기
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                
                                // 스트림(m3u8, mp4, ts 등)이나 플레이리스트 관련 핵심 요청을 감지하면 즉시 로그 출력
                                if (reqUrl.contains(".m3u8") || reqUrl.contains("playlist") || reqUrl.contains("stream")) {
                                    Log.d("kraptor_webview_intercept", "📺 Detected stream request: $reqUrl")
                                }

                                // m3u8 주소 추출 조건 검사
                                if (reqUrl.endsWith(".m3u8") && !captured.get()) {
                                    Log.i("kraptor_webview", "✅ Successfully captured target M3U8 URL: $reqUrl")
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl)
                                        Handler(Looper.getMainLooper()).postDelayed({ 
                                            Log.d("kraptor_webview", "Destroying WebView after capture.")
                                            destroy() 
                                        }, 500)
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    Log.d("kraptor_webview", "Loading URL in WebView...")
                    webView.loadUrl(url)

                    // 타임아웃 타이머 작동 (15초)
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            Log.e("kraptor_webview", "⏱️ Timeout reached (15s). No stream URL found.")
                            cont.resume(null)
                            webView?.destroy()
                        }
                    }, 15000)

                } catch (e: Exception) {
                    Log.e("kraptor_webview", "❌ Exception while creating WebView: ${e.message}", e)
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null)
                        webView?.destroy()
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Log.d("kraptor_webview", "WebView extraction job cancelled.")
                        Handler(Looper.getMainLooper()).post { webView?.destroy() }
                    }
                }
            }
        }
    }

    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        Log.d("kraptor_$name", "Starting processVideoUrl with: $videoUrl")
        val sourceName = if (videoUrl.contains("alpha")) {
            "Alpha-Most Reliable 720p 30fps"
        } else if (videoUrl.contains("bravo")) {
            "Bravo-High FPS but Low Bitrate"
        } else if (videoUrl.contains("charlie")) {
            "Charlie-May sometimes provide poor quality stream"
        } else if (videoUrl.contains("delta")) {
            "Delta-Backup, not bad (may lag/not load)"
        } else if (videoUrl.contains("echo")) {
            "Echo-Decent quality"
        } else if (videoUrl.contains("foxtrot")) {
            "Foxtrot"
        } else if (videoUrl.contains("golf")) {
            "Golf-High quality, direct from source"
        } else if (videoUrl.contains("intel")) {
            "Intel-Wide event coverage, questionable quality"
        } else if (videoUrl.contains("admin") || videoUrl.contains("poocloud")) {
            "Admin-Added by admin"
        } else if (videoUrl.contains("hotel")) {
            "Hotel-Very high quality"
        } else {
            "Streamed"
        }

        Log.d("kraptor_$name", "Matched Source Name: $sourceName")

        callback.invoke(newExtractorLink(
            source = sourceName,
            name = sourceName,
            url = videoUrl,
            type = ExtractorLinkType.M3U8
        ) {
            this.quality = Qualities.Unknown.value
            this.referer = mainUrl
            this.headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.7103.48 Safari/537.36",
                "Origin" to mainUrl,
                "Connection" to "keep-alive"
            )
        })
        Log.d("kraptor_$name", "================== Extractor End ==================")
    }

    // 디버깅 헬퍼: 로그캣 글자수 제한 우회를 위해 긴 문자열을 나누어 출력하는 함수
    private fun logInChunks(tag: String, message: String) {
        val maxLogSize = 2500
        var i = 0
        while (i < message.length) {
            val end = Math.min(message.length, i + maxLogSize)
            Log.d(tag, message.substring(i, end))
            i += maxLogSize
        }
    }

    // 디버깅 헬퍼: JS 결과 문자열(JSON-escaped)을 가독성 좋은 HTML 문자열로 디코딩
    private fun unescapeJsonString(jsonStr: String): String {
        var str = jsonStr
        if (str.startsWith("\"") && str.endsWith("\"")) {
            str = str.substring(1, str.length - 1)
        }
        return str
            .replace("\\u003C", "<")
            .replace("\\u003E", ">")
            .replace("\\u0026", "&")
            .replace("\\\"", "\"")
            .replace("\\'", "'")
            .replace("\\\\", "\\")
            .replace("\\n", "\n")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }
}
