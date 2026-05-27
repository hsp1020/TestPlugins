package com.youtube

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.mvvm.logError
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewPoTokenProvider : PoTokenProvider {

    companion object {
        private const val TIMEOUT_SECONDS = 30L
    }

    /**
     * iOS 클라이언트용 PO Token을 생성합니다.
     * WebView에서 BotGuard 스크립트를 실행한 후, 생성된 토큰을 PoTokenResult로 반환합니다.
     *
     * @param videoId 재생할 비디오의 ID
     * @return PoTokenResult 또는 null (생성 실패 시)
     */
    override fun getIosClientPoToken(videoId: String): PoTokenResult? {
        return generatePoToken("ios", videoId)
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        return generatePoToken("web", videoId)
    }

    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? {
        return generatePoToken("android", videoId)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? {
        return null // 필요 없음
    }

    private fun generatePoToken(clientType: String, videoId: String): PoTokenResult? {
        val latch = CountDownLatch(1)
        var result: PoTokenResult? = null

        Handler(Looper.getMainLooper()).post {
            try {
                val webView = WebView(com.lagradost.cloudstream3.AcraApplication.context!!)
                webView.settings.javaScriptEnabled = true
                webView.settings.domStorageEnabled = true

                val jsInterface = PoTokenJsInterface { token, visitorData ->
                    result = PoTokenResult(token, visitorData, (System.currentTimeMillis() / 1000 + 3600 * 6).toString())
                    latch.countDown()
                }
                webView.addJavascriptInterface(jsInterface, "PoTokenBridge")

                webView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        // YouTube 페이지 로드 완료 후 BotGuard 스크립트 실행
                        val script = getBotGuardScript(clientType, videoId)
                        view?.evaluateJavascript(script, null)
                    }
                }

                webView.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=0&controls=0")
            } catch (e: Exception) {
                logError(e)
                latch.countDown()
            }
        }

        return try {
            if (latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) result else null
        } catch (e: Exception) {
            logError(e)
            null
        }
    }

    /**
     * BotGuard JavaScript를 주입하여 PO Token을 생성하는 스크립트
     */
    private fun getBotGuardScript(clientType: String, videoId: String): String {
        return """
            (function() {
                try {
                    const ytcfg = window.ytcfg || {};
                    const visitorData = ytcfg.get('VISITOR_DATA') || '';
                    
                    if (window.botguard) {
                        window.botguard.generateToken('$clientType', '$videoId', visitorData)
                            .then(function(token) {
                                PoTokenBridge.onTokenGenerated(token, visitorData);
                            })
                            .catch(function(err) {
                                console.error('PO Token generation failed:', err);
                                PoTokenBridge.onTokenGenerated('', '');
                            });
                    } else {
                        console.warn('botguard not available');
                        PoTokenBridge.onTokenGenerated('', '');
                    }
                } catch (e) {
                    console.error('Error in PO token script:', e);
                    PoTokenBridge.onTokenGenerated('', '');
                }
            })();
        """.trimIndent()
    }

    /**
     * JavaScript에서 PO Token 결과를 수신하는 인터페이스
     */
    private class PoTokenJsInterface(
        private val callback: (token: String, visitorData: String) -> Unit
    ) {
        @JavascriptInterface
        fun onTokenGenerated(token: String, visitorData: String) {
            callback(token, visitorData)
        }
    }
}
