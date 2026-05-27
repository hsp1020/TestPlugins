package com.youtube

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.AcraApplication
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewPoTokenProvider : PoTokenProvider {

    companion object {
        private const val TAG = "YT_PO_TOKEN"
        private const val TIMEOUT_SECONDS = 25L
        private const val DELAY_BEFORE_INJECTION_MS = 2500L
    }

    override fun getIosClientPoToken(videoId: String): PoTokenResult? {
        Log.d(TAG, "getIosClientPoToken called for $videoId")
        return generatePoToken(videoId)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    private fun generatePoToken(videoId: String): PoTokenResult? {
        val latch = CountDownLatch(1)
        var result: PoTokenResult? = null
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            try {
                val context = AcraApplication.context ?: run {
                    Log.e(TAG, "Context is null")
                    latch.countDown()
                    return@post
                }

                val webView = WebView(context)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false
                webView.settings.userAgentString = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

                val jsInterface = PoTokenJsInterface { token, visitorData ->
                    if (!token.isNullOrBlank() && !visitorData.isNullOrBlank()) {
                        Log.d(TAG, "PO token generated: token=${token.take(30)}..., visitorData=$visitorData")
                        // PoTokenResult(token, visitorData, expiresInMsString?)
                        result = PoTokenResult(token, visitorData, null)
                    } else {
                        Log.e(TAG, "Empty token or visitorData")
                    }
                    latch.countDown()
                }
                webView.addJavascriptInterface(jsInterface, "PoTokenBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "Page finished: $url")
                        handler.postDelayed({
                            val script = getBotGuardScript(videoId)
                            Log.d(TAG, "Injecting BotGuard script after delay...")
                            view?.evaluateJavascript(script, null)
                        }, DELAY_BEFORE_INJECTION_MS)
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e(TAG, "WebView error $errorCode: $description")
                        latch.countDown()
                    }
                }

                webView.loadUrl("https://www.youtube.com/watch?v=$videoId")
            } catch (e: Exception) {
                Log.e(TAG, "Exception setting up WebView", e)
                latch.countDown()
            }
        }

        return try {
            if (latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) result
            else {
                Log.w(TAG, "Timeout waiting for PO token")
                null
            }
        } catch (e: InterruptedException) {
            Log.e(TAG, "Interrupted", e)
            null
        }
    }

    private fun getBotGuardScript(videoId: String): String {
        return """
            (function() {
                try {
                    var ytcfg = window.ytcfg || {};
                    var visitorData = ytcfg.get('VISITOR_DATA') || '';
                    console.log('[YT_PO_TOKEN] visitorData=' + visitorData);
                    
                    if (window.botguard && window.botguard.generateToken) {
                        console.log('[YT_PO_TOKEN] botguard found');
                        window.botguard.generateToken('ios', '$videoId', visitorData)
                            .then(function(token) {
                                console.log('[YT_PO_TOKEN] token=' + token);
                                PoTokenBridge.onTokenGenerated(token, visitorData);
                            })
                            .catch(function(err) {
                                console.error('[YT_PO_TOKEN] token error:', err);
                                PoTokenBridge.onTokenGenerated('', '');
                            });
                    } else {
                        console.warn('[YT_PO_TOKEN] botguard not available');
                        PoTokenBridge.onTokenGenerated('', '');
                    }
                } catch (e) {
                    console.error('[YT_PO_TOKEN] error:', e);
                    PoTokenBridge.onTokenGenerated('', '');
                }
            })();
        """.trimIndent()
    }

    private class PoTokenJsInterface(
        private val callback: (token: String?, visitorData: String?) -> Unit
    ) {
        @JavascriptInterface
        fun onTokenGenerated(token: String?, visitorData: String?) {
            callback(token ?: "", visitorData ?: "")
        }
    }
}
