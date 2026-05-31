package com.hsp1020

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URL
import kotlin.coroutines.resume

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent
    )

    private fun debugLog(message: String, throwable: Throwable? = null) {
        val errMessage = throwable?.message ?: "Unknown Error"
        val formatted = if (throwable != null) {
            "[DDL-DEBUG] $message | Exception: $errMessage"
        } else {
            "[DDL-DEBUG] $message"
        }
        
        if (throwable != null) {
            Log.e("DDL-DEBUG", "[DDL-DEBUG] $message: $errMessage")
            println(formatted)
        } else {
            Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
            println(formatted)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        debugLog("getUrl: Triggered extractor. Raw URL input payload: $url")
        val links = tryParseJson<List<Pair<String, String>>>(url)
        val extractors = links?.map { extractVideo(it.second, it.first) } ?: listOf(extractVideo(url))

        var successfullyExtractedCount = 0
        extractors.forEach {
            if (it != null) {
                successfullyExtractedCount++
                callback(it)
            }
        }
        debugLog("getUrl SUCCESS: Extracted $successfullyExtractedCount stream link(s).")
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        debugLog("extractVideo: [Step 1] Loading parent page: $url")
        
        return try {
            val resp = app.get(url, headers = headers).document
            val iframeSrc = resp.select("iframe").attr("src")
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("extractVideo ERROR: FAILED to find valid iframe source inside document.")
                return null
            }
            
            val iframeUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            val parsedUrl = URL(iframeUrl)
            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
            
            debugLog("extractVideo: [Step 2] Found embed iframe: $iframeUrl")

            // 쌩 WebView를 메인 스레드에 띄워서 자바스크립트 우회 및 m3u8 가로채기
            val finalUrl = resolveWithRawWebView(iframeUrl, refererBase)

            if (finalUrl == null) {
                debugLog("extractVideo ERROR: Raw WebView failed to intercept M3U8 within 30 seconds timeout.")
                return null
            }

            debugLog("extractVideo SUCCESS: Intercepted final stream URL: $finalUrl")

            newExtractorLink(
                sourceName,
                "$sourceName (WebView)",
                finalUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "https://player.videasy.net/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Origin" to "https://player.videasy.net",
                    "Referer" to "https://player.videasy.net/",
                    "Connection" to "Keep-Alive",
                    "User-Agent" to userAgent
                )
            }
        } catch (e: Exception) {
            debugLog("extractVideo EXCEPTION at target URL: $url", e)
            null
        }
    }

    // 안드로이드 쌩 WebView 생성 및 터치 이벤트 강제 주입 로직
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun resolveWithRawWebView(targetUrl: String, refererBase: String): String? {
        var webView: WebView? = null
        
        val resultUrl = withContext(Dispatchers.Main) {
            withTimeoutOrNull(30000L) { // 30초 내에 연산을 풀고 m3u8이 나와야 함
                suspendCancellableCoroutine<String> { cont ->
                    val context = AcraApplication.context
                    if (context == null) {
                        debugLog("RawWebView ERROR: Context is null. Cannot spawn WebView.")
                        return@suspendCancellableCoroutine
                    }

                    webView = WebView(context)
                    var isResumed = false

                    webView?.settings?.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        // 사용자 터치 없이도 미디어가 재생(WASM 실행)되도록 허용
                        mediaPlaybackRequiresUserGesture = false 
                        userAgentString = userAgent
                    }

                    webView?.webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): WebResourceResponse? {
                            val reqUrl = request?.url?.toString() ?: ""
                            
                            // WASM 연산이 끝나고 phantemlis.top의 m3u8 토큰 주소를 요청하는 순간 낚아챔
                            if (reqUrl.contains(".m3u8") && (reqUrl.contains("phantemlis.top") || reqUrl.contains("tracks-v1a1") || reqUrl.contains("mizhls.ru"))) {
                                if (!isResumed) {
                                    isResumed = true
                                    debugLog("RawWebView: Intercepted Target M3U8 -> $reqUrl")
                                    cont.resume(reqUrl)
                                }
                            }
                            return super.shouldInterceptRequest(view, request)
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            // 팝업, 투명 광고창을 모두 날려버리고 가짜 클릭 이벤트를 강제로 발생시키는 JS 주입
                            val js = """
                                setInterval(function() {
                                    // 1. 클릭을 방해하는 투명 광고 오버레이 날리기
                                    document.querySelectorAll('div, iframe').forEach(e => {
                                        var style = window.getComputedStyle(e);
                                        if (style.zIndex && parseInt(style.zIndex) > 10000) e.remove();
                                    });

                                    // 2. 플레이어 버튼 강제 클릭
                                    var btn = document.querySelector('.vjs-big-play-button, .play-button, button');
                                    if (btn) btn.click();
                                    
                                    // 3. 비디오 태그 강제 클릭 및 재생
                                    var vid = document.querySelector('video');
                                    if (vid) {
                                        vid.click();
                                        vid.play().catch(err => {});
                                    }

                                    // 4. 화면 정중앙 강제 터치 이벤트 발생
                                    var x = window.innerWidth / 2;
                                    var y = window.innerHeight / 2;
                                    var element = document.elementFromPoint(x, y);
                                    if (element) {
                                        element.click();
                                    }
                                }, 1000);
                            """.trimIndent()
                            view?.evaluateJavascript(js, null)
                        }
                    }

                    val customHeaders = mutableMapOf<String, String>()
                    customHeaders["Referer"] = "$refererBase/"
                    
                    debugLog("RawWebView: Loading URL -> $targetUrl")
                    webView?.loadUrl(targetUrl, customHeaders)
                }
            }
        }

        // 코루틴(결과 수신 혹은 타임아웃)이 끝나면 백그라운드에서 돌고 있는 웹뷰를 메인 스레드에서 즉각 폐기(메모리 누수 방지)
        withContext(Dispatchers.Main) {
            try {
                webView?.destroy()
                webView = null
                debugLog("RawWebView: WebView destroyed safely.")
            } catch (e: Exception) {
                debugLog("RawWebView WARNING: Failed to destroy WebView", e)
            }
        }

        return resultUrl
    }
}
