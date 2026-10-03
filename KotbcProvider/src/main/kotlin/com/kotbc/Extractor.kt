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
 * KOTBC Extractor v5.2
 *
 * 핵심:
 * - nnmo player를 top-level로 직접 열지 않고 KOTBC 부모 페이지를 먼저 연다.
 * - 부모 문서 안에 nnmo player iframe을 주입해 실제 사이트의 embed/referrer 컨텍스트를 재현한다.
 * - 부모 경로 실패 시 기존 direct player WebView를 fallback으로 한 번 더 시도한다.
 * - nnmo 관련 네트워크 요청을 로그에 남겨 실제 media endpoint를 추적 가능하게 한다.
 * - timeout 시 반드시 coroutine을 종료한다.
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
        val outerReferer = normalizeOuterReferer(
            referer
                ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
                ?: "${Kotbc.currentMainUrl}/"
        )

        println("[Kotbc v5.2] player=$url")
        println("[Kotbc v5.2] outerReferer=$outerReferer")

        /*
         * 1차: 실제 사이트 구조 재현
         * KOTBC 부모 페이지 -> nnmo iframe
         */
        val parentCaptured =
            if (outerReferer.contains("kotbc2.com", ignoreCase = true)) {
                println("[Kotbc v5.2] parent-flow 시작")
                runWebViewHook(
                    startUrl = outerReferer,
                    playerUrl = url,
                    outerReferer = outerReferer,
                    parentMode = true,
                    timeoutMs = 12000L
                )
            } else {
                null
            }

        val captured = parentCaptured ?: run {
            /*
             * 2차 fallback:
             * parent-flow가 실패한 경우에만 기존처럼 player 직접 로드.
             */
            println("[Kotbc v5.2] parent-flow 실패 -> direct fallback")
            runWebViewHook(
                startUrl = url,
                playerUrl = url,
                outerReferer = outerReferer,
                parentMode = false,
                timeoutMs = 10000L
            )
        }

        if (captured == null) {
            println("[Kotbc v5.2] ❌ manifest 캡처 실패")
            return
        }

        val resolved = resolveCapturedUrl(captured)

        if (resolved == null) {
            println("[Kotbc v5.2] ❌ captured URL에서 manifest 해석 실패")
            return
        }

        emitLink(resolved, callback)
    }

    /**
     * 구버전 payload/cache에 영화 URL이 /slug/slug 형태로 남아 있어도 방어한다.
     */
    private fun normalizeOuterReferer(value: String): String {
        return try {
            val parts = value.split("/")

            if (
                parts.size >= 5 &&
                parts.last().isNotBlank() &&
                parts[parts.lastIndex] == parts[parts.lastIndex - 1]
            ) {
                val normalized = parts.dropLast(1).joinToString("/")
                println("[Kotbc v5.2] referer 중복 slug 보정: $normalized")
                normalized
            } else {
                value
            }
        } catch (_: Exception) {
            value
        }
    }

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
            println("[Kotbc v5.2] captured URL resolve 실패: ${e.message}")
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

        println("[Kotbc v5.2] ✅ final=${result.url}")
        println("[Kotbc v5.2] headers=$headers")

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
        startUrl: String,
        playerUrl: String,
        outerReferer: String,
        parentMode: Boolean,
        timeoutMs: Long
    ) = suspendCancellableCoroutine<InterceptResult?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var finished = false
        var playerIframeInjected = false

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
                        webView?.let {
                            setAcceptThirdPartyCookies(it, true)
                        }
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
                            msg.contains("manifest", ignoreCase = true) ||
                            msg.contains("player", ignoreCase = true)
                        ) {
                            println("[Kotbc v5.2] console=$msg")
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

                        if (reqUrl.isBlank()) {
                            return super.shouldInterceptRequest(view, request)
                        }

                        val lower = reqUrl.lowercase()

                        /*
                         * 진단 로그:
                         * nnmo / player / media 후보 요청만 출력한다.
                         * 광고/일반 CSS/이미지는 제외해 로그 폭증을 막는다.
                         */
                        val isInterestingRequest =
                            lower.contains("nnmo0oi1.com") ||
                                lower.contains("/video/") ||
                                lower.contains(".m3u8") ||
                                lower.contains(".mpd") ||
                                lower.contains(".mp4") ||
                                lower.contains("manifest") ||
                                lower.contains("playlist") ||
                                lower.contains("master")

                        if (isInterestingRequest) {
                            println(
                                "[Kotbc v5.2 NET] " +
                                    "${request?.method ?: "GET"} $reqUrl"
                            )
                        }

                        /*
                         * HLS manifest는 host에 관계없이 즉시 캡처.
                         */
                        if (lower.contains(".m3u8")) {
                            println("[Kotbc v5.2] m3u8=$reqUrl")

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
                            println("[Kotbc v5.2] legacyCandidate=$reqUrl")

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

                        println(
                            "[Kotbc v5.2] pageFinished=" +
                                "$finishedUrl parentMode=$parentMode"
                        )

                        /*
                         * 실제 사이트의 embed 구조를 강제로 재현:
                         * KOTBC 부모 문서 안에 nnmo player를 iframe으로 넣는다.
                         *
                         * top-level direct load와 달리:
                         * - window.parent/top 관계가 생기고
                         * - document.referrer가 KOTBC 페이지가 되며
                         * - referrerPolicy=unsafe-url로 실제 사이트 동작과 맞춘다.
                         */
                        if (
                            parentMode &&
                            !playerIframeInjected &&
                            finishedUrl?.contains(
                                "kotbc2.com",
                                ignoreCase = true
                            ) == true
                        ) {
                            playerIframeInjected = true

                            handler.postDelayed(
                                {
                                    if (!finished) {
                                        injectPlayerIframe(
                                            view = view,
                                            playerUrl = playerUrl
                                        )
                                    }
                                },
                                300
                            )
                        } else if (!parentMode) {
                            triggerPlayback(view)
                        }

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
                            1800
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
                            5000
                        )
                    }
                }

                handler.postDelayed(
                    {
                        if (!finished) {
                            println(
                                "[Kotbc v5.2] ⏱ timeout final probe " +
                                    "parentMode=$parentMode"
                            )

                            probeResources(
                                view = webView,
                                playerUrl = playerUrl,
                                finishOnMiss = true,
                                finish = ::finish
                            )
                        }
                    },
                    timeoutMs
                )

                println(
                    "[Kotbc v5.2] loadUrl=$startUrl " +
                        "parentMode=$parentMode"
                )

                if (parentMode) {
                    webView?.loadUrl(
                        startUrl,
                        mapOf(
                            "User-Agent" to desktopUa
                        )
                    )
                } else {
                    webView?.loadUrl(
                        startUrl,
                        mapOf(
                            "Referer" to outerReferer,
                            "User-Agent" to desktopUa
                        )
                    )
                }
            } catch (e: Exception) {
                println("[Kotbc v5.2] WebView init 실패: ${e.message}")
                finish(null)
            }
        }
    }

    private fun injectPlayerIframe(
        view: WebView?,
        playerUrl: String
    ) {
        if (view == null) return

        val quotedPlayerUrl = JSONObject.quote(playerUrl)

        val js = """
            (function() {
                try {
                    var old = document.getElementById('kotbc-cs-player');
                    if (old) old.remove();

                    var frame = document.createElement('iframe');
                    frame.id = 'kotbc-cs-player';
                    frame.src = $quotedPlayerUrl;
                    frame.referrerPolicy = 'unsafe-url';
                    frame.allow = 'autoplay; fullscreen';
                    frame.setAttribute('allowfullscreen', 'true');

                    frame.style.position = 'fixed';
                    frame.style.left = '0';
                    frame.style.top = '0';
                    frame.style.width = '100vw';
                    frame.style.height = '100vh';
                    frame.style.zIndex = '2147483647';
                    frame.style.border = '0';

                    (document.body || document.documentElement).appendChild(frame);

                    return frame.src;
                } catch(e) {
                    return 'ERR:' + String(e);
                }
            })();
        """.trimIndent()

        view.evaluateJavascript(js) { raw ->
            println("[Kotbc v5.2] iframe inject result=$raw")
        }
    }

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

                    /*
                     * KOTBC 원본 페이지의 "바로 보기" 버튼도 같이 시도한다.
                     */
                    document.querySelectorAll('button, a').forEach(function(el) {
                        try {
                            const text = (el.innerText || el.textContent || '').trim();
                            if (text.indexOf('바로 보기') >= 0) {
                                el.click();
                            }
                        } catch(e) {}
                    });
                } catch(e) {}
            })();
        """.trimIndent()

        view.evaluateJavascript(js, null)
    }

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

                    const unique = Array.from(new Set(urls));

                    const hit = unique.find(function(u) {
                        return typeof u === 'string' &&
                            u.toLowerCase().indexOf('.m3u8') >= 0;
                    });

                    if (hit) return hit;

                    /*
                     * 최종 실패 시 다음 로그 분석용으로
                     * nnmo/media 관련 resource 목록을 반환한다.
                     */
                    const debug = unique.filter(function(u) {
                        if (typeof u !== 'string') return false;
                        const x = u.toLowerCase();

                        return x.indexOf('nnmo0oi1.com') >= 0 ||
                            x.indexOf('/video/') >= 0 ||
                            x.indexOf('.mpd') >= 0 ||
                            x.indexOf('.mp4') >= 0 ||
                            x.indexOf('manifest') >= 0 ||
                            x.indexOf('playlist') >= 0;
                    }).slice(-20);

                    return 'DEBUG:' + JSON.stringify(debug);
                } catch(e) {
                    return 'DEBUG_ERROR:' + String(e);
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
                println("[Kotbc v5.2] jsM3u8=$value")

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
                println("[Kotbc v5.2] probeDebug=$value")
                println("[Kotbc v5.2] ❌ final probe에서도 m3u8 없음")
                finish(null)
            }
        }
    }
}
