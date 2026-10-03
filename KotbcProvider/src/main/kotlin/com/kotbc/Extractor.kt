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

/**
 * KOTBC Extractor v5
 *
 * 변경점:
 * 1. 특정 player host allowlist에 의존하지 않고 실제 HLS manifest 요청을 우선 캡처.
 * 2. Provider에서 전달한 정확한 KOTBC 상세/회차 URL을 Referer로 사용.
 * 3. third-party cookie / mixed content / autoplay / DOM storage 등 WebView player 설정 보강.
 * 4. shouldInterceptRequest에서 놓친 경우 JS + PerformanceResourceTiming으로 m3u8 재탐색.
 * 5. console / page error / media candidate 로그 추가.
 */
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
        println("[Kotbc v5] getUrl: $url")
        extract(url, referer, callback)
    }

    private suspend fun extract(
        url: String,
        referer: String?,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val safeReferer = referer
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "${Kotbc.currentMainUrl}/"

        println("[Kotbc v5] Referer: $safeReferer")

        /*
         * 먼저 정적 HTML/JS 안에 m3u8이 직접 박혀 있는지 확인한다.
         * 성공하면 WebView를 띄울 이유가 없다.
         */
        directManifestProbe(url, safeReferer)?.let { direct ->
            println("[Kotbc v5] direct manifest 성공: ${direct.url}")
            emitLink(direct, callback)
            return true
        }

        val hook = runWebViewHook(url, safeReferer)

        if (hook == null) {
            println("[Kotbc v5] ❌ WebView manifest 캡처 실패")
            return false
        }

        println("[Kotbc v5] ✅ WebView manifest 캡처: ${hook.url}")
        emitLink(hook, callback)
        return true
    }

    private suspend fun directManifestProbe(
        url: String,
        referer: String
    ): InterceptResult? {
        return try {
            val headers = mapOf(
                "User-Agent" to desktopUa,
                "Referer" to referer,
                "Accept" to "*/*"
            )

            val response = app.get(url, headers = headers)
            val text = response.text

            val manifest = extractManifestFromText(text)
                ?: return null

            InterceptResult(
                url = manifest,
                headers = headers
            )
        } catch (e: Exception) {
            println("[Kotbc v5] direct probe skip: ${e.message}")
            null
        }
    }

    private fun extractManifestFromText(text: String): String? {
        val normalized = text
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")

        return Regex(
            """https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
            .find(normalized)
            ?.value
    }

    private fun emitLink(
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

        println("[Kotbc v5] ExtractorLink 전달: ${result.url}")
    }

    private suspend fun runWebViewHook(
        url: String,
        referer: String
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
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val message = consoleMessage?.message().orEmpty()
                        if (
                            message.contains("error", ignoreCase = true) ||
                            message.contains("m3u8", ignoreCase = true) ||
                            message.contains("player", ignoreCase = true) ||
                            message.contains("manifest", ignoreCase = true)
                        ) {
                            println("[Kotbc v5] console: $message")
                        }
                        return super.onConsoleMessage(consoleMessage)
                    }
                }

                val timeout = Runnable {
                    if (!finished) {
                        println("[Kotbc v5] ⏱ WebView Timeout")
                        probeWebViewResources(webView, ::finish)
                    }
                }

                handler.postDelayed(timeout, 15000)

                webView?.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val reqUrl = request?.url?.toString().orEmpty()

                        if (reqUrl.isBlank()) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val lower = reqUrl.lowercase()

                        /*
                         * 세그먼트는 최종 재생 URL이 아니므로 제외.
                         */
                        val isSegment =
                            lower.matches(Regex(""".*\.(ts|m4s|mp4)(\?.*)?$""")) ||
                                lower.matches(Regex(""".*_[0-9]+\.(html|ts)(\?.*)?$"""))

                        if (isSegment) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        /*
                         * 핵심 수정:
                         * CDN host가 바뀌어도 .m3u8이면 무조건 manifest로 인정한다.
                         */
                        val isHlsManifest =
                            lower.contains(".m3u8") ||
                                (
                                    (lower.contains("master") ||
                                        lower.contains("playlist") ||
                                        lower.contains("manifest") ||
                                        lower.contains("index")) &&
                                        !lower.contains(".js") &&
                                        !lower.contains(".css") &&
                                        !lower.contains(".png") &&
                                        !lower.contains(".jpg")
                                    )

                        /*
                         * 과거 player2/glamov 구조의 비표준 playlist fallback.
                         */
                        val isLegacyPlaylist =
                            (
                                lower.contains("player2.") ||
                                    lower.contains("bunny-frame") ||
                                    lower.contains("glamov")
                                ) &&
                                (
                                    lower.contains(".html") ||
                                        lower.contains(".txt")
                                    )

                        if (isHlsManifest || isLegacyPlaylist) {
                            println("[Kotbc v5] candidate: $reqUrl")

                            val requestHeaders = request?.requestHeaders
                                ?.toMutableMap()
                                ?: mutableMapOf()

                            if (
                                requestHeaders.keys.none {
                                    it.equals("Referer", ignoreCase = true)
                                }
                            ) {
                                requestHeaders["Referer"] = referer
                            }

                            if (
                                requestHeaders.keys.none {
                                    it.equals("User-Agent", ignoreCase = true)
                                }
                            ) {
                                requestHeaders["User-Agent"] = desktopUa
                            }

                            /*
                             * .m3u8이면 즉시 종료.
                             * 비표준 html/txt는 JS/performance probe가 m3u8을 더 찾도록
                             * 바로 종료하지 않고 후보 로그만 남긴다.
                             */
                            if (lower.contains(".m3u8")) {
                                finish(
                                    InterceptResult(
                                        url = reqUrl,
                                        headers = requestHeaders
                                    )
                                )
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)

                        println("[Kotbc v5] pageFinished: $finishedUrl")

                        handler.postDelayed(
                            {
                                if (!finished) {
                                    probeWebViewResources(view, ::finish)
                                }
                            },
                            1200
                        )

                        handler.postDelayed(
                            {
                                if (!finished) {
                                    probeWebViewResources(view, ::finish)
                                }
                            },
                            4000
                        )
                    }
                }

                println("[Kotbc v5] WebView loadUrl: $url")

                webView?.loadUrl(
                    url,
                    mapOf(
                        "Referer" to referer,
                        "User-Agent" to desktopUa
                    )
                )
            } catch (e: Exception) {
                println("[Kotbc v5] WebView Init Error: ${e.message}")
                finish(null)
            }
        }
    }

    /**
     * shouldInterceptRequest가 blob/XHR 내부 변환 때문에 manifest를 놓치는 경우 대비.
     * DOM의 video/source/iframe src와 PerformanceResourceTiming 목록에서 m3u8을 찾는다.
     */
    private fun probeWebViewResources(
        view: WebView?,
        finish: (InterceptResult?) -> Unit
    ) {
        if (view == null) {
            finish(null)
            return
        }

        val js = """
            (function() {
                try {
                    const urls = [];

                    document.querySelectorAll('video[src], source[src], iframe[src]').forEach(function(el) {
                        if (el.src) urls.push(el.src);
                    });

                    if (window.performance && performance.getEntriesByType) {
                        performance.getEntriesByType('resource').forEach(function(e) {
                            if (e && e.name) urls.push(e.name);
                        });
                    }

                    const unique = Array.from(new Set(urls));
                    const hls = unique.find(function(u) {
                        return typeof u === 'string' && u.toLowerCase().indexOf('.m3u8') >= 0;
                    });

                    return hls || '';
                } catch (e) {
                    return '';
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            try {
                val value = JSONObject("""{"value":$raw}""")
                    .optString("value")
                    .trim()

                if (value.startsWith("http://") || value.startsWith("https://")) {
                    println("[Kotbc v5] JS/performance manifest: $value")

                    finish(
                        InterceptResult(
                            url = value,
                            headers = mapOf(
                                "User-Agent" to desktopUa,
                                "Referer" to (view.url ?: Kotbc.currentMainUrl)
                            )
                        )
                    )
                }
            } catch (e: Exception) {
                println("[Kotbc v5] JS probe decode 실패: ${e.message}")
            }
        }
    }
}
