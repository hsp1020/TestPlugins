package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.suspendCancellableCoroutine
import org.json.JSONObject
import kotlin.coroutines.resume

class KotbcExtractor : ExtractorApi() {
    override val name = "KOTBC"
    override val mainUrl = "https://nnmo0oi1.com"
    override val requiresReferer = true

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/121.0.0.0 Safari/537.36"

    data class InterceptResult(
        val url: String,
        val headers: Map<String, String>
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val outerReferer = referer
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "${Kotbc.currentMainUrl}/"

        println("[Kotbc v5.1] player=$url")
        println("[Kotbc v5.1] outerReferer=$outerReferer")

        val captured = runWebViewHook(url, outerReferer)

        if (captured == null) {
            println("[Kotbc v5.1] ❌ manifest 캡처 실패")
            return
        }

        val resolved = resolveCapturedUrl(captured)

        if (resolved == null) {
            println("[Kotbc v5.1] ❌ captured URL에서 manifest 해석 실패")
            return
        }

        emitLink(resolved, callback)
    }

    /**
     * .m3u8이면 그대로 사용.
     * 과거 player2/glamov 계열의 .html/.txt playlist URL이면 내용을 한 번 읽어
     * 내부 m3u8을 찾는다.
     */
    private suspend fun resolveCapturedUrl(
        captured: InterceptResult
    ): InterceptResult? {
        if (captured.url.contains(".m3u8", ignoreCase = true)) {
            return captured
        }

        return try {
            val response = app.get(
                captured.url,
                headers = captured.headers
            )

            val text = response.text
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")

            if (text.trimStart().startsWith("#EXTM3U")) {
                captured
            } else {
                val manifest = Regex(
                    """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""",
                    RegexOption.IGNORE_CASE
                ).find(text)?.value

                manifest?.let {
                    InterceptResult(
                        url = it,
                        headers = captured.headers
                    )
                }
            }
        } catch (e: Exception) {
            println("[Kotbc v5.1] captured URL resolve 실패: ${e.message}")
            null
        }
    }

    private suspend fun emitLink(
        result: InterceptResult,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = result.headers.toMutableMap()

        if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
            headers["User-Agent"] = desktopUa
        }

        val cookie = try {
            CookieManager.getInstance().getCookie(result.url)
        } catch (_: Exception) {
            null
        }

        if (!cookie.isNullOrBlank()) {
            headers["Cookie"] = cookie
        }

        println("[Kotbc v5.1] ✅ final=${result.url}")
        println("[Kotbc v5.1] headers=$headers")

        callback(
            newExtractorLink(
                name,
                name,
                result.url,
                ExtractorLinkType.M3U8
            ) {
                this.headers = headers
            }
        )
    }

    private suspend fun runWebViewHook(
        playerUrl: String,
        outerReferer: String
    ) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var finished = false

        fun finish(result: InterceptResult?) {
            if (finished) return
            finished = true

            handler.removeCallbacksAndMessages(null)

            if (cont.isActive) {
                cont.resume(result)
            }

            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                } catch (_: Exception) {
                }
            }
        }

        fun makeHeaders(
            request: WebResourceRequest?,
            fallbackReferer: String
        ): Map<String, String> {
            val headers = request?.requestHeaders
                ?.toMutableMap()
                ?: mutableMapOf()

            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = desktopUa
            }

            /*
             * manifest 요청에는 KOTBC 상세페이지가 아니라
             * 실제 player 페이지를 Referer로 쓰는 것이 우선이다.
             */
            if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                headers["Referer"] = fallbackReferer
            }

            return headers
        }

        cont.invokeOnCancellation {
            handler.post {
                try {
                    webView?.stopLoading()
                    webView?.destroy()
                    webView = null
                } catch (_: Exception) {
                }
            }
        }

        handler.post {
            try {
                val context: Context = CloudStreamApp.context
                    ?: run {
                        finish(null)
                        return@post
                    }

                webView = WebView(context)

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    try {
                        webView?.let { setAcceptThirdPartyCookies(it, true) }
                    } catch (_: Exception) {
                    }
                }

                webView?.settings?.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = desktopUa
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)
                }

                webView?.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(
                        consoleMessage: ConsoleMessage?
                    ): Boolean {
                        val msg = consoleMessage?.message().orEmpty()

                        if (
                            msg.contains("error", ignoreCase = true) ||
                            msg.contains("m3u8", ignoreCase = true) ||
                            msg.contains("manifest", ignoreCase = true)
                        ) {
                            println("[Kotbc v5.1] console=$msg")
                        }

                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()
                        val lower = reqUrl.lowercase()

                        if (reqUrl.isBlank()) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        /*
                         * 실제 HLS manifest는 host에 관계없이 즉시 캡처.
                         */
                        if (lower.contains(".m3u8")) {
                            println("[Kotbc v5.1] m3u8=$reqUrl")

                            finish(
                                InterceptResult(
                                    url = reqUrl,
                                    headers = makeHeaders(
                                        request,
                                        view?.url ?: playerUrl
                                    )
                                )
                            )

                            return super.shouldInterceptRequest(view, request)
                        }

                        /*
                         * 구형 비표준 playlist fallback.
                         * 일반 세그먼트 _123.html 은 제외.
                         */
                        val isHtmlSegment =
                            Regex(""".*_[0-9]+\.html(\?.*)?$""")
                                .matches(lower)

                        val isLegacyPlaylist =
                            !isHtmlSegment &&
                                (
                                    lower.contains("player2.") ||
                                    lower.contains("bunny-frame") ||
                                    lower.contains("glamov")
                                ) &&
                                (
                                    lower.contains(".html") ||
                                    lower.contains(".txt") ||
                                    lower.contains("master")
                                )

                        if (isLegacyPlaylist) {
                            println("[Kotbc v5.1] legacyCandidate=$reqUrl")

                            finish(
                                InterceptResult(
                                    url = reqUrl,
                                    headers = makeHeaders(
                                        request,
                                        view?.url ?: playerUrl
                                    )
                                )
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        finishedUrl: String?
                    ) {
                        super.onPageFinished(view, finishedUrl)

                        println("[Kotbc v5.1] pageFinished=$finishedUrl")

                        /*
                         * 플레이어가 사용자 클릭 전까지 manifest를 요청하지 않는 경우 대비.
                         */
                        triggerPlayback(view)

                        handler.postDelayed(
                            {
                                if (!finished) {
                                    probeResources(
                                        view = view,
                                        playerUrl = playerUrl,
                                        finishOnMiss = false,
                                        finish = ::finish
                                    )
                                }
                            },
                            1500
                        )

                        handler.postDelayed(
                            {
                                if (!finished) {
                                    triggerPlayback(view)

                                    probeResources(
                                        view = view,
                                        playerUrl = playerUrl,
                                        finishOnMiss = false,
                                        finish = ::finish
                                    )
                                }
                            },
                            4500
                        )
                    }
                }

                /*
                 * 반드시 15초 안에 coroutine을 종료한다.
                 * 기존 v5는 timeout 후 JS probe가 빈 결과면 finish(null)을 호출하지 않는 버그가 있었다.
                 */
                handler.postDelayed(
                    {
                        if (!finished) {
                            println("[Kotbc v5.1] ⏱ timeout final probe")

                            probeResources(
                                view = webView,
                                playerUrl = playerUrl,
                                finishOnMiss = true,
                                finish = ::finish
                            )
                        }
                    },
                    15000
                )

                println("[Kotbc v5.1] loadUrl=$playerUrl")

                webView?.loadUrl(
                    playerUrl,
                    mapOf(
                        "Referer" to outerReferer,
                        "User-Agent" to desktopUa
                    )
                )
            } catch (e: Exception) {
                println("[Kotbc v5.1] WebView init 실패: ${e.message}")
                finish(null)
            }
        }
    }

    /**
     * nnmo/JWPlayer/video.js 계열에서 실제 play 동작을 유도한다.
     */
    private fun triggerPlayback(view: WebView?) {
        if (view == null) return

        val js = """
            (function() {
                try {
                    document.querySelectorAll('video').forEach(function(v) {
                        try {
                            v.muted = true;
                            const p = v.play();
                            if (p && p.catch) p.catch(function(){});
                        } catch(e) {}
                    });

                    [
                        '.jw-icon-playback',
                        '.jw-display-icon-container',
                        '.vjs-big-play-button',
                        '.plyr__control--overlaid',
                        '[aria-label="Play"]',
                        '[aria-label="재생"]',
                        '[title="Play"]'
                    ].forEach(function(sel) {
                        try {
                            const el = document.querySelector(sel);
                            if (el) el.click();
                        } catch(e) {}
                    });
                } catch(e) {}
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

    /**
     * shouldInterceptRequest에서 놓친 경우
     * DOM + PerformanceResourceTiming에서 m3u8을 찾는다.
     */
    private fun probeResources(
        view: WebView?,
        playerUrl: String,
        finishOnMiss: Boolean,
        finish: (InterceptResult?) -> Unit
    ) {
        if (view == null) {
            if (finishOnMiss) finish(null)
            return
        }

        val js = """
            (function() {
                try {
                    const urls = [];

                    document.querySelectorAll(
                        'video[src], source[src], iframe[src]'
                    ).forEach(function(el) {
                        if (el.src) urls.push(el.src);
                    });

                    if (window.performance && performance.getEntriesByType) {
                        performance.getEntriesByType('resource')
                            .forEach(function(e) {
                                if (e && e.name) urls.push(e.name);
                            });
                    }

                    const hit = Array.from(new Set(urls)).find(function(u) {
                        return typeof u === 'string' &&
                            u.toLowerCase().indexOf('.m3u8') >= 0;
                    });

                    return hit || '';
                } catch(e) {
                    return '';
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            val value = try {
                JSONObject("""{"v":$raw}""")
                    .optString("v")
                    .trim()
            } catch (_: Exception) {
                ""
            }

            if (
                value.startsWith("http://") ||
                value.startsWith("https://")
            ) {
                println("[Kotbc v5.1] jsM3u8=$value")

                finish(
                    InterceptResult(
                        url = value,
                        headers = mapOf(
                            "User-Agent" to desktopUa,
                            "Referer" to (view.url ?: playerUrl)
                        )
                    )
                )
            } else if (finishOnMiss) {
                println("[Kotbc v5.1] ❌ final probe에서도 m3u8 없음")
                finish(null)
            }
        }
    }
}
