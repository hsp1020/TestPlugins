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
    ) {
        if (referer?.trimEnd('/') != Streamed.MAIN_URL.trimEnd('/')) return

        coroutineScope {
            try {
                val videoUrl = withContext(Dispatchers.Main) {
                    getVideoUrlWithWebView(appContext, url)
                }
                if (videoUrl != null) {
                    processVideoUrl(videoUrl, callback)
                }
            } catch (e: Exception) { }
        }
    }

    private suspend fun getVideoUrlWithWebView(context: Context, url: String): String? {
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

                        val defaultUa = settings.userAgentString
                        val cleanMobileChromeUa = defaultUa
                            .replace("Version/4.0 ", "")
                            .replace("; wv", "")
                            .trim()

                        settings.userAgentString = cleanMobileChromeUa

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                // Log.d("hsp1020_webview", "Page is loading: $url")
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                super.onPageFinished(view, url)
                                // Log.d("hsp1020_webview", "Page loaded: $url")

                                // Click the play button
                                Handler(Looper.getMainLooper()).postDelayed({
                                    view?.evaluateJavascript("""
                                        (function() {
                                            try {
                                                // Find and click the JWPlayer play button
                                                var playButton = document.querySelector('.jw-icon-display');
                                                if (playButton) {
                                                    console.log('Clicking play button...');
                                                    playButton.click();
                                                    return 'Play button clicked';
                                                }
                                                
                                                // Alternatively, use the JWPlayer API directly
                                                if (typeof jwplayer !== 'undefined') {
                                                    jwplayer().play();
                                                    return 'Played with JWPlayer API';
                                                }
                                                
                                                return 'Play button not found';
                                            } catch(e) {
                                                return 'Error: ' + e.message;
                                            }
                                        })();
                                    """.trimIndent()) { result ->
                                        // Log.d("hsp1020_webview", "Play button result: $result")
                                    }
                                }, 2000) // Wait 2 seconds for the page to fully load

                                // Log.d("hsp1020_webview", "Waiting for shouldInterceptRequest...")
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): android.webkit.WebResourceResponse? {
                                val reqUrl = request?.url?.toString() ?: return null
                                // Log.d("hsp1020_webview", "Request: $reqUrl")

                                // Capture only URLs ending with .m3u8
                                if (reqUrl.endsWith(".m3u8") && !captured.get()) {
                                    // Log.d("hsp1020_webview", "✅ Video URL captured: $reqUrl")
                                    if (captured.compareAndSet(false, true)) {
                                        cont.resume(reqUrl)
                                        Handler(Looper.getMainLooper()).postDelayed({ destroy() }, 500)
                                    }
                                }

                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                    }

                    webView.loadUrl(url)

                    // Add timeout
                    Handler(Looper.getMainLooper()).postDelayed({
                        if (captured.compareAndSet(false, true)) {
                            // Log.d("hsp1020_webview", "Timeout: URL not found")
                            cont.resume(null)
                            webView?.destroy()
                        }
                    }, 15000) // 15 seconds timeout

                } catch (e: Exception) {
                    // Log.e("hsp1020_webview", "WebView creation error: ${e.message}")
                    if (captured.compareAndSet(false, true)) {
                        cont.resume(null)
                        webView?.destroy()
                    }
                }

                cont.invokeOnCancellation {
                    if (captured.compareAndSet(false, true)) {
                        Handler(Looper.getMainLooper()).post { webView?.destroy() }
                    }
                }
            }
        }
    }

    private suspend fun processVideoUrl(videoUrl: String, callback: (ExtractorLink) -> Unit) {
        // Log.d("hsp1020_$name", "Video URL: $videoUrl")
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
    }
}
