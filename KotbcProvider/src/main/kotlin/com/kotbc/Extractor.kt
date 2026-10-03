package com.kotbc

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Message
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
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URI
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * KOTBC Extractor v7.0
 *
 * 2026-10-03 Cloud Browser 실측 구조:
 *
 *   KOTBC 상세/회차 페이지
 *        -> "바로 보기"
 *        -> 새 창 about:blank
 *        -> https://mov.glamov.com/detail.php?v=<회차별 토큰>
 *        -> wrapper 내부 <iframe src="https://nnmo0oi1.com/video/...">
 *        -> 실제 media manifest
 *
 * 핵심 원칙:
 * - reader/path로 wrapper URL을 추측하지 않는다.
 * - Inertia body.url(nnmo)을 곧바로 top-level로 열지 않는다.
 * - 실제 popup WebView에서 glamov wrapper URL을 캡처한다.
 * - wrapper 자체를 WebView로 로드해 nnmo iframe/referrer 흐름을 브라우저와 동일하게 유지한다.
 * - popup URL을 못 잡을 때는 fetch/XHR instrumentation 로그로 실제 요청을 남긴다.
 */
class KotbcExtractor : ExtractorApi() {
    override val name = "KOTBC"
    override val mainUrl = "https://mov.glamov.com"
    override val requiresReferer = true

    private val desktopUa =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/121.0.0.0 Safari/537.36"

    data class InterceptResult(
        val url: String,
        val headers: Map<String, String>
    )

    private data class WrapperInfo(
        val wrapperUrl: String,
        val iframeUrl: String?
    )

    /**
     * 신규 사이트의 정상 진입점.
     *
     * pageUrl:
     *   실제 KOTBC 작품/회차 페이지
     *
     * expectedPlayerUrl:
     *   Inertia props.playing.body.url 또는 episodes.data[].body.url.
     *   wrapper 주소가 아니라 wrapper 내부 nnmo iframe 주소이며,
     *   wrapper 검증용으로만 사용한다.
     */
    suspend fun getUrlFromKotbcPage(
        pageUrl: String,
        expectedPlayerUrl: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[Kotbc v7.1] ===== KOTBC popup resolver =====")
        println("[Kotbc v7.1] page=$pageUrl")
        println("[Kotbc v7.1] expectedPlayer=$expectedPlayerUrl")

        val wrapperUrl = resolveWrapperViaRealPopup(pageUrl)

        if (wrapperUrl.isNullOrBlank()) {
            println("[Kotbc v7.1] ❌ glamov wrapper URL 획득 실패")
            return
        }

        println("[Kotbc v7.1] ✅ wrapper=$wrapperUrl")

        val inspected = inspectWrapper(
            wrapperUrl = wrapperUrl,
            pageReferer = pageUrl
        )

        val actualIframe = inspected?.iframeUrl

        if (!actualIframe.isNullOrBlank()) {
            println("[Kotbc v7.1] wrapper iframe=$actualIframe")
        } else {
            println("[Kotbc v7.1] ⚠️ app.get() wrapper iframe 검증 실패/미확인")
        }

        if (
            !expectedPlayerUrl.isNullOrBlank() &&
            !actualIframe.isNullOrBlank()
        ) {
            val expectedNormalized = normalizeComparableUrl(expectedPlayerUrl)
            val actualNormalized = normalizeComparableUrl(actualIframe)

            if (expectedNormalized == actualNormalized) {
                println("[Kotbc v7.1] ✅ Inertia body.url == wrapper iframe")
            } else {
                /*
                 * popup에서 얻은 wrapper가 브라우저 실측의 authoritative 값이다.
                 * mismatch는 로그만 남기고 wrapper 재생은 계속한다.
                 */
                println(
                    "[Kotbc v7.1] ⚠️ player mismatch: " +
                        "expected=$expectedPlayerUrl actual=$actualIframe"
                )
            }
        }

        extractFromWrapper(
            wrapperUrl = wrapperUrl,
            pageReferer = pageUrl,
            expectedPlayerUrl = actualIframe ?: expectedPlayerUrl,
            callback = callback
        )
    }

    /**
     * 구버전 캐시/legacy form 호환.
     *
     * - glamov wrapper가 직접 들어오면 wrapper부터 시작.
     * - KOTBC URL이면 신규 popup resolver로 보낸다.
     * - nnmo 등 외부 player URL은 최후 호환용 direct fallback.
     */
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val normalizedUrl = url.trim()
        val safeReferer = referer
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "${Kotbc.currentMainUrl}/"

        when {
            normalizedUrl.contains("kotbc2.com", ignoreCase = true) -> {
                getUrlFromKotbcPage(
                    pageUrl = normalizedUrl,
                    expectedPlayerUrl = null,
                    subtitleCallback = subtitleCallback,
                    callback = callback
                )
            }

            isGlamovWrapper(normalizedUrl) -> {
                println("[Kotbc v7.1] legacy wrapper=$normalizedUrl")
                extractFromWrapper(
                    wrapperUrl = normalizedUrl,
                    pageReferer = safeReferer,
                    expectedPlayerUrl = null,
                    callback = callback
                )
            }

            else -> {
                println("[Kotbc v7.1] ⚠️ legacy direct player fallback=$normalizedUrl")
                extractDirectPlayer(
                    playerUrl = normalizedUrl,
                    referer = safeReferer,
                    callback = callback
                )
            }
        }
    }

    /**
     * 실제 사이트의 window.open() 흐름을 그대로 재현한다.
     *
     * Cloud Browser 실측:
     *   child: about:blank -> mov.glamov.com/detail.php?v=...
     *
     * popup을 현재 parent WebView로 덮어쓰지 않는다.
     * WebChromeClient.onCreateWindow()에서 실제 child WebView를 만들어
     * child navigation URL을 감시한다.
     */
    private suspend fun resolveWrapperViaRealPopup(
        pageUrl: String
    ): String? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val completed = AtomicBoolean(false)
        val buttonClicked = AtomicBoolean(false)

        var parentView: WebView? = null
        val childViews = mutableListOf<WebView>()

        fun destroyAll() {
            try {
                parentView?.stopLoading()
                parentView?.destroy()
            } catch (_: Exception) {
            }
            parentView = null

            childViews.forEach { child ->
                try {
                    child.stopLoading()
                    child.destroy()
                } catch (_: Exception) {
                }
            }
            childViews.clear()
        }

        fun finish(value: String?) {
            if (!completed.compareAndSet(false, true)) return

            handler.removeCallbacksAndMessages(null)

            if (cont.isActive) {
                cont.resume(value)
            }

            handler.post {
                destroyAll()
            }
        }

        fun inspectPossibleWrapper(raw: String?) {
            val wrapper = extractGlamovWrapper(raw.orEmpty())
            if (!wrapper.isNullOrBlank()) {
                println("[Kotbc v7.1] 🎯 wrapper captured=$wrapper")
                finish(wrapper)
            }
        }

        fun configureCommonSettings(view: WebView, multiWindow: Boolean) {
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

        fun installHooks(view: WebView) {
            val js = """
                (function() {
                    try {
                        if (window.__kotbc_cs7_hooked) {
                            return 'ALREADY_HOOKED';
                        }
                        window.__kotbc_cs7_hooked = true;

                        var originalOpen = window.open;
                        window.open = function() {
                            try {
                                var u = arguments.length ? String(arguments[0] || '') : '';
                                console.log('KOTBC_V7_1_OPEN ' + u);
                            } catch(e) {}
                            return originalOpen.apply(this, arguments);
                        };

                        if (window.fetch) {
                            var originalFetch = window.fetch;
                            window.fetch = async function() {
                                var input = arguments[0];
                                var init = arguments[1] || {};
                                var requestUrl = '';

                                try {
                                    requestUrl = typeof input === 'string'
                                        ? input
                                        : (input && input.url ? input.url : String(input));
                                } catch(e) {}

                                try {
                                    console.log(
                                        'KOTBC_V7_1_FETCH ' +
                                        String((init && init.method) || 'GET') +
                                        ' ' + requestUrl +
                                        ' body=' + String((init && init.body) || '').slice(0, 500)
                                    );
                                } catch(e) {}

                                var response = await originalFetch.apply(this, arguments);

                                try {
                                    var clone = response.clone();
                                    clone.text().then(function(body) {
                                        console.log(
                                            'KOTBC_V7_1_FETCH_RESP ' +
                                            String(response.url || requestUrl) +
                                            ' status=' + response.status +
                                            ' body=' + String(body || '').slice(0, 1200)
                                        );
                                    }).catch(function(){});
                                } catch(e) {}

                                return response;
                            };
                        }

                        if (window.XMLHttpRequest) {
                            var X = XMLHttpRequest.prototype;
                            var originalXOpen = X.open;
                            var originalXSend = X.send;

                            X.open = function(method, url) {
                                try {
                                    this.__kotbcMethod = method;
                                    this.__kotbcUrl = url;
                                    console.log(
                                        'KOTBC_V7_1_XHR ' +
                                        String(method || 'GET') +
                                        ' ' + String(url || '')
                                    );
                                } catch(e) {}

                                return originalXOpen.apply(this, arguments);
                            };

                            X.send = function(body) {
                                try {
                                    var self = this;
                                    console.log(
                                        'KOTBC_V7_1_XHR_SEND ' +
                                        String(self.__kotbcUrl || '') +
                                        ' body=' + String(body || '').slice(0, 500)
                                    );

                                    self.addEventListener('load', function() {
                                        try {
                                            console.log(
                                                'KOTBC_V7_1_XHR_RESP ' +
                                                String(self.responseURL || self.__kotbcUrl || '') +
                                                ' status=' + self.status +
                                                ' body=' + String(self.responseText || '').slice(0, 1200)
                                            );
                                        } catch(e) {}
                                    });
                                } catch(e) {}

                                return originalXSend.apply(this, arguments);
                            };
                        }

                        return 'HOOKED';
                    } catch(e) {
                        return 'HOOK_ERROR:' + String(e);
                    }
                })();
            """.trimIndent()

            view.evaluateJavascript(js) { raw ->
                println("[Kotbc v7.1] hook=$raw")
            }
        }

        fun clickPlaybackButton(view: WebView) {
            if (completed.get() || buttonClicked.get()) return

            val js = """
                (function() {
                    try {
                        var nodes = Array.prototype.slice.call(
                            document.querySelectorAll('button')
                        ).filter(function(el) {
                            try {
                                var style = window.getComputedStyle(el);
                                return !el.disabled &&
                                    style.display !== 'none' &&
                                    style.visibility !== 'hidden';
                            } catch(e) {
                                return !el.disabled;
                            }
                        });

                        function textOf(el) {
                            return String(
                                el.innerText ||
                                el.textContent ||
                                el.getAttribute('aria-label') ||
                                ''
                            ).replace(/\s+/g, ' ').trim();
                        }

                        var exactWords = [
                            '바로 보기',
                            '바로보기',
                            '시청하기',
                            '재생',
                            '영화 보기',
                            '영화보기',
                            '영상 보기',
                            '영상보기',
                            '감상하기'
                        ];

                        var target = null;

                        for (var i = 0; i < exactWords.length && !target; i++) {
                            var word = exactWords[i];
                            target = nodes.find(function(el) {
                                var t = textOf(el);
                                return t === word || t.indexOf(word) >= 0;
                            }) || null;
                        }

                        /*
                         * 현재 회차 페이지에서 실측된 blue action button fallback.
                         * 검색/북마크/평점/요청 버튼은 제외한다.
                         */
                        if (!target) {
                            target = nodes.find(function(el) {
                                var t = textOf(el);
                                var c = String(el.className || '');

                                if (
                                    t.indexOf('검색') >= 0 ||
                                    t.indexOf('북마크') >= 0 ||
                                    t.indexOf('요청') >= 0 ||
                                    t.indexOf('첫 화') >= 0 ||
                                    t.indexOf('메뉴') >= 0
                                ) {
                                    return false;
                                }

                                return c.indexOf('bg-[#1247d5]') >= 0 ||
                                    (
                                        c.indexOf('bg-brand-600') >= 0 &&
                                        el.closest('main, section')
                                    );
                            }) || null;
                        }

                        var labels = nodes.map(textOf)
                            .filter(function(x) { return x; })
                            .slice(0, 40);

                        if (!target) {
                            return 'NO_BUTTONS:' + JSON.stringify(labels);
                        }

                        var label = textOf(target);
                        target.click();

                        return 'CLICKED:' + label;
                    } catch(e) {
                        return 'CLICK_ERROR:' + String(e);
                    }
                })();
            """.trimIndent()

            view.evaluateJavascript(js) { raw ->
                println("[Kotbc v7.1] watchClick=$raw")

                if (raw?.contains("CLICKED:", ignoreCase = false) == true) {
                    buttonClicked.set(true)
                }
            }
        }

        cont.invokeOnCancellation {
            handler.post {
                destroyAll()
            }
        }

        handler.post {
            try {
                val context: Context = CloudStreamApp.context
                    ?: run {
                        finish(null)
                        return@post
                    }

                parentView = WebView(context)
                val parent = parentView ?: run {
                    finish(null)
                    return@post
                }

                configureCommonSettings(parent, multiWindow = true)

                parent.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(
                        consoleMessage: ConsoleMessage?
                    ): Boolean {
                        val message = consoleMessage?.message().orEmpty()

                        if (
                            message.startsWith("KOTBC_V7_1_") ||
                            message.contains("glamov.com/detail.php", ignoreCase = true)
                        ) {
                            println("[Kotbc v7.1 JS] $message")
                            inspectPossibleWrapper(message)
                        }

                        return true
                    }

                    override fun onCreateWindow(
                        view: WebView?,
                        isDialog: Boolean,
                        isUserGesture: Boolean,
                        resultMsg: Message?
                    ): Boolean {
                        println(
                            "[Kotbc v7.1] onCreateWindow " +
                                "dialog=$isDialog userGesture=$isUserGesture"
                        )

                        val transport =
                            resultMsg?.obj as? WebView.WebViewTransport
                                ?: return false

                        val child = WebView(context)
                        configureCommonSettings(child, multiWindow = false)

                        child.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(
                                consoleMessage: ConsoleMessage?
                            ): Boolean {
                                val message = consoleMessage?.message().orEmpty()
                                if (message.isNotBlank()) {
                                    println("[Kotbc v7.1 CHILD JS] $message")
                                    inspectPossibleWrapper(message)
                                }
                                return true
                            }
                        }

                        child.webViewClient = object : WebViewClient() {
                            override fun onPageStarted(
                                childView: WebView?,
                                url: String?,
                                favicon: android.graphics.Bitmap?
                            ) {
                                super.onPageStarted(childView, url, favicon)
                                println("[Kotbc v7.1] child pageStarted=$url")
                                inspectPossibleWrapper(url)
                            }

                            override fun onPageFinished(
                                childView: WebView?,
                                url: String?
                            ) {
                                super.onPageFinished(childView, url)
                                println("[Kotbc v7.1] child pageFinished=$url")
                                inspectPossibleWrapper(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                childView: WebView?,
                                request: WebResourceRequest?
                            ): Boolean {
                                val next = request?.url?.toString().orEmpty()
                                println("[Kotbc v7.1] child navigate=$next")

                                val wrapper = extractGlamovWrapper(next)
                                return if (!wrapper.isNullOrBlank()) {
                                    println("[Kotbc v7.1] 🎯 child wrapper navigate=$wrapper")
                                    finish(wrapper)
                                    true
                                } else {
                                    false
                                }
                            }
                        }

                        childViews.add(child)

                        transport.webView = child
                        resultMsg?.sendToTarget()
                        return true
                    }
                }

                parent.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        view: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        println("[Kotbc v7.1] parent pageStarted=$url")
                    }

                    override fun onPageFinished(
                        view: WebView?,
                        url: String?
                    ) {
                        super.onPageFinished(view, url)
                        println("[Kotbc v7.1] parent pageFinished=$url")

                        if (
                            url?.contains("kotbc2.com", ignoreCase = true) != true ||
                            view == null
                        ) {
                            return
                        }

                        installHooks(view)

                        /*
                         * Vue hydration 시점을 고려해 세 번만 재시도한다.
                         * 실제 클릭이 한 번 성공하면 buttonClicked로 중복 클릭 방지.
                         */
                        handler.postDelayed(
                            { clickPlaybackButton(view) },
                            350L
                        )
                        handler.postDelayed(
                            { clickPlaybackButton(view) },
                            1200L
                        )
                        handler.postDelayed(
                            { clickPlaybackButton(view) },
                            3000L
                        )
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val next = request?.url?.toString().orEmpty()
                        inspectPossibleWrapper(next)
                        return false
                    }
                }

                /*
                 * 최종 실패에도 coroutine은 반드시 종료한다.
                 * 실패 시 바로 앞의 KOTBC_V7_1_FETCH/XHR/OPEN 로그가 진단 자료가 된다.
                 */
                handler.postDelayed(
                    {
                        if (!completed.get()) {
                            println("[Kotbc v7.1] ❌ wrapper resolver timeout")
                            finish(null)
                        }
                    },
                    15000L
                )

                println("[Kotbc v7.1] parent loadUrl=$pageUrl")
                parent.loadUrl(
                    pageUrl,
                    mapOf("User-Agent" to desktopUa)
                )
            } catch (e: Exception) {
                println("[Kotbc v7.1] popup resolver init error=${e.message}")
                finish(null)
            }
        }
    }

    /**
     * popup에서 얻은 wrapper HTML을 가볍게 검증한다.
     * 이 검증이 실패해도 popup URL 자체가 authoritative이므로 WebView 재생은 계속한다.
     */
    private suspend fun inspectWrapper(
        wrapperUrl: String,
        pageReferer: String
    ): WrapperInfo? {
        return try {
            val headers = mutableMapOf(
                "User-Agent" to desktopUa,
                "Referer" to pageReferer
            )

            val cookie = try {
                CookieManager.getInstance().getCookie(wrapperUrl)
            } catch (_: Exception) {
                null
            }

            if (!cookie.isNullOrBlank()) {
                headers["Cookie"] = cookie
            }

            val response = app.get(
                wrapperUrl,
                headers = headers
            )

            val finalUrl = response.url
            val iframeUrl = response.document
                .selectFirst("#movie_player iframe[src], iframe[src*=\"nnmo0oi1.com/video/\"]")
                ?.attr("src")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { makeAbsoluteUrl(finalUrl, it) }

            println(
                "[Kotbc v7.1] wrapper inspect: " +
                    "HTTP=${response.isSuccessful} final=$finalUrl iframe=$iframeUrl"
            )

            WrapperInfo(
                wrapperUrl = finalUrl,
                iframeUrl = iframeUrl
            )
        } catch (e: Exception) {
            println("[Kotbc v7.1] wrapper inspect error=${e.message}")
            null
        }
    }

    /**
     * 실제 브라우저 구조 그대로 wrapper를 top-level로 열고,
     * wrapper 내부 nnmo iframe이 만드는 media 요청을 가로챈다.
     */
    private suspend fun extractFromWrapper(
        wrapperUrl: String,
        pageReferer: String,
        expectedPlayerUrl: String?,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[Kotbc v7.1] wrapper WebView start=$wrapperUrl")

        val captured = runMediaWebView(
            startUrl = wrapperUrl,
            initialReferer = pageReferer,
            expectedPlayerUrl = expectedPlayerUrl,
            startIsPlayer = false,
            timeoutMs = 15000L
        )

        if (captured == null) {
            println("[Kotbc v7.1] ❌ wrapper에서 manifest 캡처 실패")
            return
        }

        val resolved = resolveCapturedUrl(captured)

        if (resolved == null) {
            println("[Kotbc v7.1] ❌ manifest 해석 실패")
            return
        }

        emitLink(resolved, callback)
    }

    /**
     * 구버전 direct nnmo payload만을 위한 호환 fallback.
     * 신규 정상 경로에서는 사용하지 않는다.
     */
    private suspend fun extractDirectPlayer(
        playerUrl: String,
        referer: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val captured = runMediaWebView(
            startUrl = playerUrl,
            initialReferer = referer,
            expectedPlayerUrl = playerUrl,
            startIsPlayer = true,
            timeoutMs = 10000L
        )

        val resolved = captured?.let { resolveCapturedUrl(it) }

        if (resolved != null) {
            emitLink(resolved, callback)
        } else {
            println("[Kotbc v7.1] ❌ legacy direct player 실패")
        }
    }

    private suspend fun runMediaWebView(
        startUrl: String,
        initialReferer: String,
        expectedPlayerUrl: String?,
        startIsPlayer: Boolean,
        timeoutMs: Long
    ): InterceptResult? = suspendCancellableCoroutine { cont ->
        val handler = Handler(Looper.getMainLooper())
        val finished = AtomicBoolean(false)
        val playerFrameSeen = AtomicBoolean(startIsPlayer)
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

            handler.removeCallbacksAndMessages(null)

            if (cont.isActive) {
                cont.resume(result)
            }

            handler.post {
                destroy()
            }
        }

        fun capturedHeaders(
            request: WebResourceRequest?,
            fallbackReferer: String
        ): Map<String, String> {
            val headers = request?.requestHeaders
                ?.toMutableMap()
                ?: mutableMapOf()

            if (headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                headers["User-Agent"] = desktopUa
            }

            if (headers.keys.none { it.equals("Referer", ignoreCase = true) }) {
                headers["Referer"] = fallbackReferer
            }

            return headers
        }

        cont.invokeOnCancellation {
            handler.post {
                destroy()
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
                    javaScriptCanOpenWindowsAutomatically = true
                    setSupportMultipleWindows(false)
                }

                CookieManager.getInstance().apply {
                    setAcceptCookie(true)
                    try {
                        setAcceptThirdPartyCookies(view, true)
                    } catch (_: Exception) {
                    }
                }

                view.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(
                        consoleMessage: ConsoleMessage?
                    ): Boolean {
                        val message = consoleMessage?.message().orEmpty()

                        if (
                            message.contains("error", ignoreCase = true) ||
                            message.contains("m3u8", ignoreCase = true) ||
                            message.contains("manifest", ignoreCase = true)
                        ) {
                            println("[Kotbc v7.1 MEDIA JS] $message")
                        }

                        return true
                    }
                }

                view.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(
                        v: WebView?,
                        url: String?,
                        favicon: android.graphics.Bitmap?
                    ) {
                        super.onPageStarted(v, url, favicon)
                        println("[Kotbc v7.1 MEDIA] pageStarted=$url")

                        if (
                            url?.contains("nnmo0oi1.com/video/", ignoreCase = true) == true
                        ) {
                            playerFrameSeen.set(true)
                        }
                    }

                    override fun shouldInterceptRequest(
                        v: WebView?,
                        request: WebResourceRequest?
                    ): WebResourceResponse? {
                        val requestUrl = request?.url?.toString().orEmpty()

                        if (requestUrl.isBlank()) {
                            return super.shouldInterceptRequest(v, request)
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
                            if (playerFrameSeen.compareAndSet(false, true)) {
                                println("[Kotbc v7.1 MEDIA] ✅ nnmo player frame seen=$requestUrl")
                            } else {
                                println("[Kotbc v7.1 MEDIA] nnmo=$requestUrl")
                            }
                        }

                        val isManifest =
                            lower.contains(".m3u8") ||
                                lower.contains(".mpd")

                        if (isManifest) {
                            if (playerFrameSeen.get()) {
                                println(
                                    "[Kotbc v7.1 MEDIA] 🎯 manifest " +
                                        "${request?.method ?: "GET"} $requestUrl"
                                )

                                finish(
                                    InterceptResult(
                                        url = requestUrl,
                                        headers = capturedHeaders(
                                            request,
                                            expectedPlayerUrl ?: startUrl
                                        )
                                    )
                                )
                            } else {
                                /*
                                 * wrapper 광고가 먼저 HLS를 요청해도 콘텐츠로 오인하지 않는다.
                                 */
                                println(
                                    "[Kotbc v7.1 MEDIA] ignore pre-player manifest=$requestUrl"
                                )
                            }

                            return super.shouldInterceptRequest(v, request)
                        }

                        /*
                         * 과거 KOTBC가 쓰던 비표준 playlist 계열.
                         * nnmo frame을 실제로 본 이후에만 허용한다.
                         */
                        val isSegment =
                            Regex(""".*_[0-9]+\.(html|ts)(\?.*)?$""")
                                .matches(lower)

                        val isLegacyPlaylist =
                            !isSegment &&
                                playerFrameSeen.get() &&
                                (
                                    lower.contains("player2.") ||
                                    lower.contains("bunny-frame") ||
                                    lower.contains("glamov") ||
                                    lower.contains("nnmo0oi1.com")
                                ) &&
                                (
                                    lower.contains(".html") ||
                                    lower.contains(".txt") ||
                                    lower.contains("master")
                                )

                        if (isLegacyPlaylist) {
                            println("[Kotbc v7.1 MEDIA] legacyCandidate=$requestUrl")

                            finish(
                                InterceptResult(
                                    url = requestUrl,
                                    headers = capturedHeaders(
                                        request,
                                        expectedPlayerUrl ?: startUrl
                                    )
                                )
                            )
                        }

                        return super.shouldInterceptRequest(v, request)
                    }

                    override fun onPageFinished(
                        v: WebView?,
                        url: String?
                    ) {
                        super.onPageFinished(v, url)
                        println("[Kotbc v7.1 MEDIA] pageFinished=$url")
                    }
                }

                handler.postDelayed(
                    {
                        if (!finished.get()) {
                            println(
                                "[Kotbc v7.1 MEDIA] ❌ timeout " +
                                    "playerFrameSeen=${playerFrameSeen.get()}"
                            )
                            finish(null)
                        }
                    },
                    timeoutMs
                )

                val headers = mutableMapOf(
                    "User-Agent" to desktopUa
                )

                if (initialReferer.isNotBlank()) {
                    headers["Referer"] = initialReferer
                }

                println(
                    "[Kotbc v7.1 MEDIA] load=$startUrl " +
                        "referer=$initialReferer"
                )

                view.loadUrl(startUrl, headers)
            } catch (e: Exception) {
                println("[Kotbc v7.1 MEDIA] init error=${e.message}")
                finish(null)
            }
        }
    }

    private suspend fun resolveCapturedUrl(
        captured: InterceptResult
    ): InterceptResult? {
        val lower = captured.url.lowercase()

        if (
            lower.contains(".m3u8") ||
            lower.contains(".mpd")
        ) {
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
                println("[Kotbc v7.1] ✅ legacy playlist body is #EXTM3U: ${captured.url}")
                captured
            } else {
                val manifest = Regex(
                    """https?://[^\s"'<>\\]+\.(?:m3u8|mpd)(?:\?[^\s"'<>\\]*)?""",
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
            println("[Kotbc v7.1] captured URL resolve error=${e.message}")
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

        val type =
            if (result.url.contains(".mpd", ignoreCase = true)) {
                ExtractorLinkType.DASH
            } else {
                ExtractorLinkType.M3U8
            }

        println("[Kotbc v7.1] ✅ final=${result.url}")
        println("[Kotbc v7.1] finalHeaders=$headers")

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

    private fun extractGlamovWrapper(raw: String): String? {
        if (raw.isBlank()) return null

        val normalized = raw
            .replace("\\/", "/")
            .replace("\\u002F", "/")
            .replace("\\u0026", "&")
            .replace("&amp;", "&")

        return Regex(
            """https?://mov\.glamov\.com/detail\.php\?[^ \t\r\n"'<>\\]*v=[A-Za-z0-9_-]+[^ \t\r\n"'<>\\]*""",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.value
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
