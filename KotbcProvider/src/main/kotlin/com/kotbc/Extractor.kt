package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

class KotbcExtractor : ExtractorApi() {
    private fun trace(stage: String, detail: String) = android.util.Log.i("KotbcPlaybackTrace", "stage=$stage $detail")

    override val name = "KOTBC"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = true

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/121.0.0.0 Safari/537.36"

    private data class InterceptResult(
        val url: String,
        val headers: Map<String, String>
    )

    private data class WrapperInfo(
        val url: String,
        val playerUrl: String?
    )

    /**
     * 현재 KOTBC 정상 재생 경로:
     * KOTBC 재생 상세 -> "바로 보기" -> popup glamov wrapper
     * -> wrapper 내부 nnmo iframe -> HLS/DASH
     */
    suspend fun getUrlFromKotbcPage(
        pageUrl: String,
        expectedPlayerUrl: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val start = android.os.SystemClock.elapsedRealtime()
        trace("PAGE_START", "expectedPlayer=${expectedPlayerUrl != null}")
        val wrapper = resolveWrapperViaPopup(pageUrl) ?: return
        trace("POPUP_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
        val info = inspectWrapper(wrapper, pageUrl)

        trace("INSPECT_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - start} player=${info?.playerUrl != null}")
        extractFromWrapper(
            wrapperUrl = info?.url ?: wrapper,
            pageReferer = pageUrl,
            playerUrl = info?.playerUrl ?: expectedPlayerUrl,
            callback = callback
        )
        trace("PAGE_END", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val target = url.trim()
        val safeReferer = referer
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "${Kotbc.currentMainUrl}/"

        when {
            target.contains("kotbc2.com", ignoreCase = true) -> {
                getUrlFromKotbcPage(
                    target,
                    null,
                    subtitleCallback,
                    callback
                )
            }

            isGlamovWrapper(target) -> {
                val info = inspectWrapper(target, safeReferer)
                extractFromWrapper(
                    wrapperUrl = info?.url ?: target,
                    pageReferer = safeReferer,
                    playerUrl = info?.playerUrl,
                    callback = callback
                )
            }

            else -> {
                extractDirectPlayer(target, safeReferer, callback)
            }
        }
    }

    private suspend fun resolveWrapperViaPopup(
        pageUrl: String
    ): String? = suspendCancellableCoroutine { cont ->
        val start = android.os.SystemClock.elapsedRealtime()
        trace("POPUP_START", "host=${runCatching { java.net.URL(pageUrl).host }.getOrDefault("unknown")}")
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val clicked = AtomicBoolean(false)

        var parent: WebView? = null
        var child: WebView? = null

        fun destroyViews() {
            listOf(parent, child).forEach { view ->
                try {
                    view?.stopLoading()
                    view?.destroy()
                } catch (_: Exception) {
                }
            }
            parent = null
            child = null
        }

         fun finish(value: String?) {
            if (!finished.compareAndSet(false, true)) return
            trace("POPUP_END", "ms=${android.os.SystemClock.elapsedRealtime() - start} found=${value != null}")
            handler.removeCallbacksAndMessages(null)
            if (cont.isActive) cont.resume(value)
            handler.post { destroyViews() }
        }

        fun configure(view: WebView, multiWindow: Boolean) {
            view.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mediaPlaybackRequiresUserGesture = false
                userAgentString = desktopUa
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                javaScriptCanOpenWindowsAutomatically = true
                setSupportMultipleWindows(multiWindow)
            }

            CookieManager.getInstance().apply {
                setAcceptCookie(true)
                try {
                    setAcceptThirdPartyCookies(view, true)
                } catch (_: Exception) {
                }
            }
        }

        fun clickPlaybackButton(view: WebView) {
            if (finished.get() || clicked.get()) return

            val js = """
                (function() {
                    var buttons = Array.prototype.slice.call(
                        document.querySelectorAll('button')
                    );

                    var target = buttons.find(function(button) {
                        var text = String(
                            button.innerText ||
                            button.textContent ||
                            ''
                        ).replace(/\s+/g, ' ').trim();

                        return !button.disabled &&
                            (text === '바로 보기' || text === '바로보기');
                    });

                    if (!target) return 'NOT_FOUND';
                    target.click();
                    return 'CLICKED';
                })();
            """.trimIndent()

            view.evaluateJavascript(js) { result ->
                if (result.contains("CLICKED")) {
                    trace("POPUP_CLICKED", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
                    clicked.set(true)
                } else if (!finished.get()) {
                    trace("POPUP_BUTTON_RETRY", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
                    handler.postDelayed(
                        { clickPlaybackButton(view) },
                        300L
                    )
                }
            }
        }

        fun captureWrapper(url: String?) {
            val value = url.orEmpty()
            if (isGlamovWrapper(value)) {
                trace("POPUP_WRAPPER_CAPTURE", "ms=${android.os.SystemClock.elapsedRealtime() - start} host=${runCatching { java.net.URL(value).host }.getOrDefault("unknown")}")
                finish(value)
            }
        }

        cont.invokeOnCancellation {
            handler.post { destroyViews() }
        }

        handler.post {
            try {
                val context: Context = CloudStreamApp.context
                    ?: run {
                        finish(null)
                        return@post
                    }

                parent = WebView(context)
                val parentView = parent ?: run {
                    finish(null)
                    return@post
                }

                configure(parentView, multiWindow = true)

                parentView.webChromeClient = object : WebChromeClient() {
                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        trace("POPUP_WINDOW", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
                        val popup = WebView(context)
                        child = popup
                        configure(popup, multiWindow = false)

                        popup.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                view: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?
                            ) {
                                super.onPageStarted(view, url, favicon)
                                captureWrapper(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                captureWrapper(request?.url?.toString())
                                return false
                            }
                        }

                        val transport = resultMsg?.obj as? WebView.WebViewTransport
                            ?: return false

                        transport.webView = popup
                        resultMsg.sendToTarget()
                        return true
                    }
                }

                parentView.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {
                        super.onPageFinished(view, url)
                        trace("POPUP_PAGE_FINISHED", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
                        view?.let { clickPlaybackButton(it) }
                    }
                }

                handler.postDelayed({ trace("POPUP_TIMEOUT", "ms=${android.os.SystemClock.elapsedRealtime() - start}"); finish(null) }, 12000L)

                trace("POPUP_LOAD_URL", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
                parentView.loadUrl(
                    pageUrl,
                    mapOf("User-Agent" to desktopUa)
                )
            } catch (_: Exception) {
                finish(null)
            }
        }
    }

    private suspend fun inspectWrapper(
        wrapperUrl: String,
        pageReferer: String
    ): WrapperInfo? {
        val start = android.os.SystemClock.elapsedRealtime()
        trace("INSPECT_START", "host=${runCatching { java.net.URL(wrapperUrl).host }.getOrDefault("unknown")}")
        return try {
            val headers = mutableMapOf(
                "User-Agent" to desktopUa,
                "Referer" to pageReferer
            )

            CookieManager.getInstance()
                .getCookie(wrapperUrl)
                ?.takeIf { it.isNotBlank() }
                ?.let { headers["Cookie"] = it }

            val response = app.get(wrapperUrl, headers = headers)
            trace("INSPECT_HTTP", "ms=${android.os.SystemClock.elapsedRealtime() - start} status=${response.code}")
            val finalUrl = response.url

            val player = response.document
                .selectFirst(
                    "#movie_player iframe[src], " +
                        "iframe[src*=\"nnmo0oi1.com/video/\"]"
                )
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { makeAbsoluteUrl(finalUrl, it) }

            trace("INSPECT_END", "ms=${android.os.SystemClock.elapsedRealtime() - start} player=${player != null}")
            WrapperInfo(finalUrl, player)
        } catch (_: Exception) {
            null
        }
    }

    private suspend fun extractFromWrapper(
        wrapperUrl: String,
        pageReferer: String,
        playerUrl: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        val start = android.os.SystemClock.elapsedRealtime()
        trace("WRAPPER_WEBVIEW_START", "player=${playerUrl != null}")
        trace("WRAPPER_INPUT", "wrapperHost=${runCatching { java.net.URL(wrapperUrl).host }.getOrDefault("unknown")} playerHost=${runCatching { java.net.URL(playerUrl ?: "").host }.getOrDefault("none")}")
        val captured = runMediaWebView(
            startUrl = wrapperUrl,
            initialReferer = pageReferer,
            expectedPlayerUrl = playerUrl,
            startIsPlayer = false,
            timeoutMs = 15000L
        ) ?: run { trace("WRAPPER_WEBVIEW_EMPTY", "ms=${android.os.SystemClock.elapsedRealtime() - start}"); return }

        trace("WRAPPER_WEBVIEW_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
        resolveCapturedUrl(captured)?.let {
            emitLink(it, callback)
        }
    }

    private suspend fun extractDirectPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val start = android.os.SystemClock.elapsedRealtime()
        trace("DIRECT_WEBVIEW_START", "host=${runCatching { java.net.URL(playerUrl).host }.getOrDefault("unknown")}")
        val captured = runMediaWebView(
            startUrl = playerUrl,
            initialReferer = referer,
            expectedPlayerUrl = playerUrl,
            startIsPlayer = true,
            timeoutMs = 10000L
        ) ?: run { trace("DIRECT_WEBVIEW_EMPTY", "ms=${android.os.SystemClock.elapsedRealtime() - start}"); return }

        trace("DIRECT_WEBVIEW_DONE", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
        resolveCapturedUrl(captured)?.let {
            emitLink(it, callback)
        }
    }

    private suspend fun runMediaWebView(
        startUrl: String,
        initialReferer: String,
        expectedPlayerUrl: String?,
        startIsPlayer: Boolean,
        timeoutMs: Long
    ): InterceptResult? = suspendCancellableCoroutine { cont ->
        val start = android.os.SystemClock.elapsedRealtime()
        trace("MEDIA_WEBVIEW_START", "timeoutMs=$timeoutMs player=$startIsPlayer")
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val playerSeen = AtomicBoolean(startIsPlayer)

        var webView: WebView? = null

        fun destroy() {
            try {
                webView?.stopLoading()
                webView?.destroy()
            } catch (_: Exception) {
            }
            webView = null
        }

        fun finish(result: InterceptResult?) {
            if (!finished.compareAndSet(false, true)) return
            trace("MEDIA_WEBVIEW_END", "ms=${android.os.SystemClock.elapsedRealtime() - start} found=${result != null}")
            handler.removeCallbacksAndMessages(null)
            if (cont.isActive) cont.resume(result)
            handler.post { destroy() }
        }

        fun requestHeaders(
            request: WebResourceRequest?,
            fallbackReferer: String
        ): Map<String, String> {
            val headers = request?.requestHeaders
                ?.toMutableMap()
                ?: mutableMapOf()

            if (headers.keys.none { it.equals("User-Agent", true) }) {
                headers["User-Agent"] = desktopUa
            }

            if (headers.keys.none { it.equals("Referer", true) }) {
                headers["Referer"] = fallbackReferer
            }

            return headers
        }

        cont.invokeOnCancellation {
            handler.post { destroy() }
        }

        handler.post {
            try {
                val context: Context = CloudStreamApp.context
                    ?: run {
                        finish(null)
                        return@post
                    }

                webView = WebView(context)
                val view = webView ?: run {
                    finish(null)
                    return@post
                }

                view.settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = true
                    mediaPlaybackRequiresUserGesture = false
                    userAgentString = desktopUa
                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    try {
                        setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString().orEmpty()
                        if (requestUrl.isBlank()) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val lower = requestUrl.lowercase()
                        val expectedMatch =
                            !expectedPlayerUrl.isNullOrBlank() &&
                                normalizeComparableUrl(requestUrl) ==
                                normalizeComparableUrl(expectedPlayerUrl)

                        if (
                            expectedMatch ||
                            lower.contains("nnmo0oi1.com/video/")
                        ) {
                            if (playerSeen.compareAndSet(false, true)) trace("MEDIA_PLAYER_SEEN", "ms=${android.os.SystemClock.elapsedRealtime() - start} expected=$expectedMatch")
                        }

                        if (!playerSeen.get()) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val isManifest =
                            lower.contains(".m3u8") ||
                                lower.contains(".mpd")

                        val isSegment = Regex(
                            """.*_[0-9]+\.(html|ts)(\?.*)?$"""
                        ).matches(lower)

                        val isLegacyPlaylist =
                            !isSegment &&
                                (
                                    lower.contains("nnmo0oi1.com") ||
                                    lower.contains("player2.") ||
                                    lower.contains("bunny-frame") ||
                                    lower.contains("glamov")
                                ) &&
                                (
                                    lower.contains(".txt") ||
                                    lower.contains("master") ||
                                    lower.contains(".html")
                                )

                        if (isManifest || isLegacyPlaylist) {
                            trace("MEDIA_CAPTURE", "ms=${android.os.SystemClock.elapsedRealtime() - start} manifest=$isManifest legacy=$isLegacyPlaylist host=${request?.url?.host}")
                            finish(
                                InterceptResult(
                                    requestUrl,
                                    requestHeaders(
                                        request,
                                        expectedPlayerUrl ?: startUrl
                                    )
                                )
                            )
                        }

                        return super.shouldInterceptRequest(view, request)
                    }
                }

                handler.postDelayed({ trace("MEDIA_TIMEOUT", "ms=${android.os.SystemClock.elapsedRealtime() - start} playerSeen=${playerSeen.get()}"); finish(null) }, timeoutMs)

                val headers = mutableMapOf(
                    "User-Agent" to desktopUa
                )

                if (initialReferer.isNotBlank()) {
                    headers["Referer"] = initialReferer
                }

                trace("MEDIA_LOAD_URL", "ms=${android.os.SystemClock.elapsedRealtime() - start} player=$startIsPlayer")
                view.loadUrl(startUrl, headers)
            } catch (_: Exception) {
                finish(null)
            }
        }
    }

    private suspend fun resolveCapturedUrl(
        captured: InterceptResult
    ): InterceptResult? {
        val start = android.os.SystemClock.elapsedRealtime()
        trace("CAPTURE_RESOLVE_START", "host=${runCatching { java.net.URL(captured.url).host }.getOrDefault("unknown")}")
        val lower = captured.url.lowercase()

        if (
            lower.contains(".m3u8") ||
            lower.contains(".mpd")
        ) {
            trace("CAPTURE_DIRECT", "ms=${android.os.SystemClock.elapsedRealtime() - start}")
            return captured
        }

        return try {
            val response = app.get(
                captured.url,
                headers = captured.headers
            )

            trace("CAPTURE_HTTP", "ms=${android.os.SystemClock.elapsedRealtime() - start} status=${response.code}")
            val body = response.text
                .replace("\\/", "/")
                .replace("\\u002F", "/")
                .replace("\\u0026", "&")

            trace("CAPTURE_CLASS", "ms=${android.os.SystemClock.elapsedRealtime() - start} isM3u8=${body.trimStart().startsWith("#EXTM3U")} chars=${body.length}")
            if (body.trimStart().startsWith("#EXTM3U")) {
                captured
            } else {
                val manifest = Regex(
                    """https?://[^\s"'<>\\]+\.(?:m3u8|mpd)(?:\?[^\s"'<>\\]*)?""",
                    RegexOption.IGNORE_CASE
                ).find(body)?.value

                trace("CAPTURE_EMBEDDED", "found=${manifest != null}")
                manifest?.let {
                    InterceptResult(it, captured.headers)
                }
            }
        } catch (e: Exception) {
            trace("CAPTURE_ERROR", "ms=${android.os.SystemClock.elapsedRealtime() - start} error=${e.javaClass.simpleName}")
            null
        }
    }

    private suspend fun emitLink(
        result: InterceptResult,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = result.headers.toMutableMap()

        if (headers.keys.none { it.equals("User-Agent", true) }) {
            headers["User-Agent"] = desktopUa
        }

        CookieManager.getInstance()
            .getCookie(result.url)
            ?.takeIf { it.isNotBlank() }
            ?.let { headers["Cookie"] = it }

        val type = if (result.url.contains(".mpd", ignoreCase = true)) {
            ExtractorLinkType.DASH
        } else {
            ExtractorLinkType.M3U8
        }

        trace("LINK_CALLBACK", "type=$type host=${runCatching { java.net.URL(result.url).host }.getOrDefault("unknown")}")
        callback(
            newExtractorLink(
                name,
                name,
                result.url,
                type
            ) {
                this.headers = headers
            }
        )
    }

    private fun isGlamovWrapper(url: String): Boolean {
        val lower = url.lowercase()
        return lower.contains("mov.glamov.com") &&
            lower.contains("detail.php") &&
            lower.contains("v=")
    }

    private fun normalizeComparableUrl(url: String): String {
        return url.trim()
            .replace("\\/", "/")
            .trimEnd('/')
    }

    private fun makeAbsoluteUrl(
        baseUrl: String,
        value: String
    ): String {
        val candidate = value.trim()

        if (
            candidate.startsWith("http://") ||
            candidate.startsWith("https://")
        ) {
            return candidate
        }

        if (candidate.startsWith("//")) {
            return "https:$candidate"
        }

        return try {
            URI(baseUrl).resolve(candidate).toString()
        } catch (_: Exception) {
            candidate
        }
    }
}
