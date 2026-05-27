package com.youtube

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewPoTokenProvider : PoTokenProvider {

    companion object {
        private const val TAG = "YT_PO_TOKEN"
        private const val TIMEOUT_SECONDS = 20L
        private const val DELAY_BEFORE_INJECTION_MS = 2000L // BotGuard 준비를 위한 지연 시간
    }

    override fun getIosClientPoToken(videoId: String): PoTokenResult? {
        Log.d(TAG, "getIosClientPoToken called for videoId=$videoId")
        return generatePoToken(videoId)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = null // 필요 없음
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    private fun generatePoToken(videoId: String): PoTokenResult? {
        val latch = CountDownLatch(1)
        var result: PoTokenResult? = null
        val handler = Handler(Looper.getMainLooper())

        handler.post {
            try {
                Log.d(TAG, "Initializing WebView for PO token generation...")
                val webView = WebView(com.lagradost.cloudstream3.AcraApplication.context!!)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true
                webView.settings.mediaPlaybackRequiresUserGesture = false

                val jsInterface = PoTokenJsInterface { token, visitorData ->
                    if (token.isNotBlank() && visitorData.isNotBlank()) {
                        Log.d(TAG, "PO token generated: token=${token.take(20)}..., visitorData=$visitorData")
                        result = PoTokenResult(token, visitorData)
                    } else {
                        Log.e(TAG, "PO token generation returned empty token or visitorData")
                    }
                    latch.countDown()
                }
                webView.addJavascriptInterface(jsInterface, "PoTokenBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        Log.d(TAG, "Page finished loading: $url")
                        // BotGuard가 로드될 시간을 주기 위해 지연 후 스크립트 실행
                        handler.postDelayed({
                            val script = getBotGuardScript(videoId)
                            Log.d(TAG, "Injecting BotGuard script...")
                            view?.evaluateJavascript(script, null)
                        }, DELAY_BEFORE_INJECTION_MS)
                    }

                    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                        Log.e(TAG, "WebView error: $errorCode $description")
                        latch.countDown()
                    }
                }

                // 시청 페이지 로드 – BotGuard가 포함된 페이지로 이동
                webView.loadUrl("https://www.youtube.com/watch?v=$videoId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to setup WebView", e)
                latch.countDown()
            }
        }

        return try {
            if (latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) result else {
                Log.w(TAG, "Timeout waiting for PO token generation")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Interrupted while waiting", e)
            null
        }
    }

    /**
     * BotGuard를 호출하여 PO Token을 생성하고 결과를 JSBridge를 통해 전달하는 스크립트
     */
    private fun getBotGuardScript(videoId: String): String {
        return """
            (function() {
                try {
                    const ytcfg = window.ytcfg || {};
                    const visitorData = ytcfg.get('VISITOR_DATA') || '';
                    console.log('[YT_PO_TOKEN] visitorData=' + visitorData);
                    
                    if (window.botguard && window.botguard.generateToken) {
                        console.log('[YT_PO_TOKEN] botguard found, generating token...');
                        window.botguard.generateToken('ios', '$videoId', visitorData)
                            .then(function(token) {
                                console.log('[YT_PO_TOKEN] token generated: ' + token);
                                PoTokenBridge.onTokenGenerated(token, visitorData);
                            })
                            .catch(function(err) {
                                console.error('[YT_PO_TOKEN] generateToken failed:', err);
                                PoTokenBridge.onTokenGenerated('', '');
                            });
                    } else {
                        console.warn('[YT_PO_TOKEN] botguard not available on this page');
                        PoTokenBridge.onTokenGenerated('', '');
                    }
                } catch (e) {
                    console.error('[YT_PO_TOKEN] Error in script:', e);
                    PoTokenBridge.onTokenGenerated('', '');
                }
            })();
        """.trimIndent()
    }

    private class PoTokenJsInterface(
        private val callback: (token: String, visitorData: String) -> Unit
    ) {
        @JavascriptInterface
        fun onTokenGenerated(token: String, visitorData: String) {
            callback(token, visitorData)
        }
    }
}
