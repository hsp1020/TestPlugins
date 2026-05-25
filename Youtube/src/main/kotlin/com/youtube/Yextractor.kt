package com.youtube

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebResourceError
import android.webkit.JavascriptInterface
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.withLock

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private val ytVideos = ConcurrentHashMap<String, List<ExtractorLink>>()
        private val ytVideosSubtitles = ConcurrentHashMap<String, List<SubtitlesStream>>()

        @Volatile
        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        private val tokenMutex = kotlinx.coroutines.sync.Mutex()

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        private val serverExecutor = ThreadPoolExecutor(
            2, 
            8, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        init {
            YoutubeStreamExtractor.setFetchIosClient(true)

            YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {
                override fun getWebClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [Telemetry] getWebClientPoToken requested. Video ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [Telemetry] getWebEmbedClientPoToken requested. Video ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [Telemetry] getAndroidClientPoToken requested. Video ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getIosClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [Telemetry] getIosClientPoToken requested. Video ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }
            })
        }

        fun clearCache(videoId: String) {
            println("[YT_DEBUG] [Telemetry] clearCache triggered for Video ID: $videoId")
            ytVideos.remove(videoId)
            ytVideosSubtitles.remove(videoId)
        }

        private fun getPoTokenResultForVideo(videoId: String?): PoTokenResult? {
            val id = videoId ?: "aqz-KE-bpKQ"
            val cached = getCachedTokens(id)
            if (cached != null) {
                println("[YT_DEBUG] [Telemetry] Cache HIT for video: [$id]. poToken: ${cached.first.take(15)}...")
                // [교정완료]: 생성자 규격 불일치 버그 수정 (visitorData, playerRequestPoToken, streamingDataPoToken)
                return PoTokenResult(cached.second, cached.first, cached.first)
            }

            println("[YT_DEBUG] [Telemetry] Cache MISS for video: [$id]. Entering Thread-Safe Mutex Lock.")
            val generated: Triple<String, String, String?>? = kotlinx.coroutines.runBlocking {
                tokenMutex.withLock {
                    val doubleCheck = getCachedTokens(id)
                    if (doubleCheck != null) {
                        println("[YT_DEBUG] [Telemetry] Mutex Double-Check HIT for video: [$id]")
                        doubleCheck
                    } else {
                        println("[YT_DEBUG] [Telemetry] Spawning WebView Attestation Sandbox for video: [$id]")
                        getPoTokenAndVisitor(id)
                    }
                }
            }

            return if (generated != null) {
                println("[YT_DEBUG] [Telemetry] Final Binding success. poToken: ${generated.first.take(20)}... bound to Video [$id]")
                // [교정완료]: 생성자 규격 불일치 버그 수정 (visitorData, playerRequestPoToken, streamingDataPoToken)
                val visitor = if (generated.second.isNotBlank()) generated.second else "CgptdWpfa0NXZ2tfdyi28vS0Bg"
                PoTokenResult(visitor, generated.first, generated.first)
            } else {
                println("[YT_DEBUG] [Telemetry] Attestation Sandboxing failed completely for video [$id]. Proceeding with bare request.")
                null
            }
        }

        private fun getCachedTokens(videoId: String): Triple<String, String, String?>? {
            val context = AcraApplication.context ?: return null
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val poToken = prefs.getString("yt_cached_potoken_$videoId", null)
            val visitorData = prefs.getString("yt_cached_visitor_data_$videoId", null)
            val cookieStr = prefs.getString("yt_cached_cookie_$videoId", null)
            val timestamp = prefs.getLong("yt_potoken_timestamp_$videoId", 0L)

            if (poToken.isNullOrBlank() || visitorData.isNullOrBlank()) {
                return null
            }

            val elapsed = System.currentTimeMillis() - timestamp
            val remainingSec = (3 * 3600 * 1000 - elapsed) / 1000
            if (elapsed > 3 * 3600 * 1000) { 
                println("[YT_DEBUG] [Telemetry] Cached token expired for video: [$videoId]. Elapsed: ${elapsed / 1000}s")
                return null
            }

            println("[YT_DEBUG] [Telemetry] Cache Valid. Remaining: ${remainingSec}s for video: [$videoId]")
            return Triple(poToken, visitorData, cookieStr)
        }

        private fun saveCachedTokens(videoId: String, poToken: String, visitorData: String, cookieStr: String?) {
            val context = AcraApplication.context ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val now = System.currentTimeMillis()
            
            println("[YT_DEBUG] [Telemetry] Saving Generated Attestation. Video: [$videoId], poToken: ${poToken.take(15)}..., visitorData: ${visitorData.take(15)}")
            val editor = prefs.edit()
                .putString("yt_cached_potoken_$videoId", poToken)
                .putString("yt_cached_visitor_data_$videoId", visitorData)
                .putLong("yt_potoken_timestamp_$videoId", now)
                
            if (!cookieStr.isNullOrBlank()) {
                editor.putString("yt_cached_cookie_$videoId", cookieStr)
                
                val visitorInfoLive = extractCookieValue(cookieStr, "VISITOR_INFO1_LIVE")
                if (!visitorInfoLive.isNullOrBlank()) {
                    editor.putString("VISITOR_INFO1_LIVE", visitorInfoLive)
                    println("[YT_DEBUG] [Telemetry] Syncing VISITOR_INFO1_LIVE Cookie: $visitorInfoLive")
                }
                
                val ysc = extractCookieValue(cookieStr, "YSC")
                if (!ysc.isNullOrBlank()) {
                    editor.putString("YSC", ysc)
                    println("[YT_DEBUG] [Telemetry] Syncing YSC Cookie: $ysc")
                }
            }
            editor.apply()
        }

        private fun extractCookieValue(cookieStr: String?, cookieName: String): String? {
            if (cookieStr.isNullOrBlank()) return null
            val cookies = cookieStr.split(";")
            for (c in cookies) {
                val trimmed = c.trim()
                if (trimmed.startsWith("$cookieName=")) {
                    return trimmed.substringAfter("=")
                }
            }
            return null
        }

        private suspend fun getPoTokenAndVisitor(videoId: String): Triple<String, String, String?>? = withContext(Dispatchers.Main) {
            val context = AcraApplication.context ?: return@withContext null
            val targetUrl = "https://www.youtube.com/watch?v=$videoId"
            val startTime = System.currentTimeMillis()
            println("[YT_DEBUG] [Telemetry] Starting WebView attestation sandboxing on Mobile Watch Page.")
            println("[YT_DEBUG] [Telemetry] Target URL: $targetUrl")

            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                var isCompleted = false
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var pollRunnable: Runnable? = null

                val safeResume = { res: Triple<String, String, String?>? ->
                    if (!isCompleted) {
                        isCompleted = true
                        pollRunnable?.let { handler.removeCallbacks(it) }
                        val duration = System.currentTimeMillis() - startTime
                        println("[YT_DEBUG] [Telemetry] WebView Attestation finished in ${duration}ms. Success: ${res != null}")
                        if (continuation.isActive) {
                            continuation.resume(res)
                        }
                        webView.destroy()
                    }
                }

                class PoTokenBridge {
                    @JavascriptInterface
                    fun onPoTokenFound(token: String, visitor: String, url: String) {
                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                            if (isCompleted) return@post
                            println("[YT_DEBUG] [Telemetry] [Bridge-Callback] Captured Token: ${token.take(20)}...")
                            println("[YT_DEBUG] [Telemetry] [Bridge-Callback] Captured Visitor: $visitor")
                            
                            val cookieManager = android.webkit.CookieManager.getInstance()
                            val cookieStr = cookieManager.getCookie(targetUrl)
                            
                            saveCachedTokens(videoId, token, visitor, cookieStr)
                            safeResume(Triple(token, visitor, cookieStr))
                        }
                    }
                }

                webView.apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false 
                    
                    val defaultUa = settings.userAgentString
                    println("[YT_DEBUG] [Telemetry] [UA_Check] Using Genuine Default Mobile WebView UA: $defaultUa")
                    
                    settings.loadsImagesAutomatically = false

                    addJavascriptInterface(PoTokenBridge(), "PoTokenBridge")

                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                            consoleMessage?.let {
                                println("[YT_DEBUG] [WebView-Console] [${it.messageLevel()}] ${it.message()} (Source: ${it.sourceId()}:${it.lineNumber()})")
                            }
                            return true
                        }
                    }

                    pollRunnable = object : Runnable {
                        override fun run() {
                            if (isCompleted) return

                            // [유튜브 정품 Player Response 모바일 API 폴링]:
                            // 비동기 /player 네트워크 수집에 잡히지 않더라도, 동영상이 기동되는 즉시 html5 플레이어 
                            // 위젯이 생성한 메모리 원천Response 객체를 호출하여 poToken 및 pot 파라미터를 낚아채는 가속 마스터 수식
                            val pollingScript = """
                                (function() {
                                    try {
                                        var poToken = null;
                                        var visitorData = null;
                                        
                                        var player = document.getElementById('movie_player') || document.querySelector('.html5-video-player');
                                        if (player && typeof player.getPlayerResponse === 'function') {
                                            var pr = player.getPlayerResponse();
                                            if (pr) {
                                                if (pr.serviceIntegrityDimensions && pr.serviceIntegrityDimensions.poToken) {
                                                    poToken = pr.serviceIntegrityDimensions.poToken;
                                                }
                                                if (!poToken && pr.streamingData && pr.streamingData.poToken) {
                                                    poToken = pr.streamingData.poToken;
                                                }
                                                if (!poToken && pr.streamingData && pr.streamingData.adaptiveFormats) {
                                                    var formats = pr.streamingData.adaptiveFormats;
                                                    for (var i = 0; i < formats.length; i++) {
                                                        var url = formats[i].url || formats[i].signatureCipher || "";
                                                        var m = url.match(/[&?]pot=([^&]+)/);
                                                        if (m) {
                                                            poToken = decodeURIComponent(m[1]);
                                                            break;
                                                        }
                                                    }
                                                }
                                                if (pr.responseContext && pr.responseContext.visitorData) {
                                                    visitorData = pr.responseContext.visitorData;
                                                }
                                            }
                                        }
                                        
                                        if (!poToken) {
                                            var pr = window.ytInitialPlayerResponse;
                                            if (pr) {
                                                if (pr.serviceIntegrityDimensions && pr.serviceIntegrityDimensions.poToken) {
                                                    poToken = pr.serviceIntegrityDimensions.poToken;
                                                }
                                                if (!poToken && pr.streamingData && pr.streamingData.poToken) {
                                                    poToken = pr.streamingData.poToken;
                                                }
                                            }
                                        }

                                        if (poToken) {
                                            return JSON.stringify({ poToken: poToken, visitorData: visitorData || "" });
                                        }
                                        return JSON.stringify({ error: 'Player response poToken not available yet' });
                                    } catch (e) {
                                        return JSON.stringify({ error: 'Exception in polling: ' + e.message });
                                    }
                                })()
                            """.trimIndent()

                            evaluateJavascript(pollingScript) { jsonResult ->
                                if (isCompleted) return@evaluateJavascript

                                if (jsonResult != null && jsonResult != "null" && jsonResult.isNotBlank()) {
                                    try {
                                        val cleanedJson = jsonResult.replace("\\\"", "\"").removePrefix("\"").removeSuffix("\"")
                                        val json = JSONObject(cleanedJson)

                                        if (!json.has("error")) {
                                            val poToken = json.optString("poToken")
                                            val visitorData = json.optString("visitorData")

                                            if (poToken.isNotBlank()) {
                                                println("[YT_DEBUG] [Telemetry] [Polling-Fallback] Success! Captured poToken via getPlayerResponse().")
                                                val cookieManager = android.webkit.CookieManager.getInstance()
                                                val cookieStr = cookieManager.getCookie(targetUrl)
                                                
                                                saveCachedTokens(videoId, poToken, visitorData, cookieStr)
                                                safeResume(Triple(poToken, visitorData, cookieStr))
                                                return@evaluateJavascript
                                            }
                                        }
                                    } catch (e: Exception) {
                                        // 무시
                                    }
                                }
                                handler.postDelayed(this, 300)
                            }
                        }
                    }

                    val injectJsHooks = {
                        val hookScript = """
                            (function() {
                                if (window._poTokenHookInjected) {
                                    return;
                                }
                                window._poTokenHookInjected = true;
                                console.log("[JS_HOOK] Injecting Fetch/XHR Interceptors and Active Ad-Skipper...");

                                var skipAd = function() {
                                    var video = document.querySelector('video');
                                    var adShowing = document.querySelector('.ad-showing, .ytp-ad-player-overlay, .ytp-ad-player-overlay-instream-info, .ytp-ad-overlay-container, .ytp-ad-persistent-progress-bar');
                                    var skipBtn = document.querySelector('.ytp-ad-skip-button, .ytp-ad-skip-button-modern, .ytp-ad-skip-button-slot, .ytp-ad-skip-button-container');
                                    if ((adShowing || skipBtn) && video) {
                                        console.log("[AD_SKIP] Ad detected! Force-skipping...");
                                        if (video.duration && isFinite(video.duration)) {
                                            video.currentTime = video.duration;
                                        } else {
                                            video.currentTime = 9999;
                                        }
                                        if (skipBtn) {
                                            skipBtn.click();
                                        }
                                    }
                                };
                                setInterval(skipAd, 200);

                                function scanObjectForPoToken(obj) {
                                    if (!obj) return null;
                                    if (typeof obj === 'string') {
                                        if (obj.length > 80 && obj.length < 200 && (obj.startsWith('M') || obj.startsWith('AO'))) {
                                            return obj;
                                        }
                                        return null;
                                    }
                                    if (typeof obj === 'object') {
                                        for (var key in obj) {
                                            if (obj.hasOwnProperty(key)) {
                                                if (key === 'poToken' || key === 'rolloutToken') {
                                                    if (typeof obj[key] === 'string' && obj[key].length > 50) {
                                                        return obj[key];
                                                    }
                                                }
                                                var found = scanObjectForPoToken(obj[key]);
                                                if (found) return found;
                                            }
                                        }
                                    }
                                    return null;
                                }

                                function checkPayload(body, url) {
                                    if (!body) return;
                                    try {
                                        var parsed = null;
                                        if (typeof body === 'string') {
                                            parsed = JSON.parse(body);
                                        } else {
                                            parsed = body;
                                        }
                                        if (parsed) {
                                            var token = scanObjectForPoToken(parsed);
                                            var visitorData = null;
                                            if (parsed.context && parsed.context.client) {
                                                visitorData = parsed.context.client.visitorData;
                                            }
                                            if (token) {
                                                console.log("[JS_HOOK] Success! Captured poToken: " + token.substring(0, 15) + "..., visitorData: " + visitorData);
                                                if (window.PoTokenBridge) {
                                                    window.PoTokenBridge.onPoTokenFound(token, visitorData || "", url || "");
                                                }
                                            }
                                        }
                                    } catch (e) {
                                        console.log("[JS_HOOK] JSON payload scan issue: " + e.message);
                                    }
                                }

                                var rawOpen = XMLHttpRequest.prototype.open;
                                XMLHttpRequest.prototype.open = function(method, url) {
                                    this._url = url;
                                    return rawOpen.apply(this, arguments);
                                };

                                var rawSend = XMLHttpRequest.prototype.send;
                                XMLHttpRequest.prototype.send = function(body) {
                                    if (this._url && (this._url.indexOf('v1/player') !== -1 || this._url.indexOf('youtubei/v1/player') !== -1)) {
                                        console.log("[JS_HOOK] XHR Payload intercepted. Endpoint: " + this._url);
                                        checkPayload(body, this._url);
                                    }
                                    return rawSend.apply(this, arguments);
                                };

                                var rawFetch = window.fetch;
                                window.fetch = function(input, init) {
                                    var url = (typeof input === 'string') ? input : (input && input.url);
                                    if (url && (url.indexOf('v1/player') !== -1 || url.indexOf('youtubei/v1/player') !== -1)) {
                                        console.log("[JS_HOOK] Fetch Payload intercepted. Endpoint: " + url);
                                        if (init && init.body) {
                                            checkPayload(init.body, url);
                                        }
                                    }
                                    return rawFetch.apply(this, arguments);
                                };

                                console.log("[JS_HOOK] Flow hook injection completed.");
                            })();
                        """.trimIndent()
                        evaluateJavascript(hookScript, null)
                    }

                    webViewClient = object : WebViewClient() {
                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                            val urlStr = request?.url?.toString() ?: return null
                            if (urlStr.contains(".png") || urlStr.contains(".jpg") || urlStr.contains(".jpeg") || 
                                urlStr.contains(".woff") || urlStr.contains(".ttf") || urlStr.contains(".css") ||
                                urlStr.contains("googleads") || urlStr.contains("doubleclick") || urlStr.contains("adservice")) {
                                return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                            }
                            return null
                        }

                        override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                            println("[YT_DEBUG] [Telemetry] [onPageStarted] Loading: $url. Injecting hook script.")
                            injectJsHooks()
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            println("[YT_DEBUG] [Telemetry] [onPageFinished] Loading done: $url. Sync-injecting hook.")
                            injectJsHooks()
                            pollRunnable?.let { handler.post(it) }
                        }

                        override fun onLoadResource(view: WebView?, url: String?) {
                            if (url != null && (url.contains("player") || url.contains("jnn") || url.contains("botguard") || url.contains("googleapis"))) {
                                println("[YT_DEBUG] [Telemetry] [onLoadResource] Intercepted resource loading: $url")
                            }
                        }

                        override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                            println("[YT_DEBUG] [Telemetry] [onReceivedError] Failed request: ${request?.url}, Error: ${error?.description} (Code: ${error?.errorCode})")
                        }
                    }

                    // 12초 타임아웃
                    handler.postDelayed({
                        if (!isCompleted) {
                            println("[YT_DEBUG] [Telemetry] WebView sandbox attestation TIMEOUT (12s). Triggering deep diagnostic scan...")
                            
                            val diagnosticScript = """
                                (function() {
                                    var logs = [];
                                    logs.push("========= ATTESTATION TIMEOUT DIAGNOSTIC REPORT =========");
                                    logs.push("Page Title: " + document.title);
                                    logs.push("Current URL: " + window.location.href);
                                    logs.push("ytInitialPlayerResponse exists: " + (!!window.ytInitialPlayerResponse));
                                    if (window.ytInitialPlayerResponse) {
                                        logs.push("  - Keys: " + Object.keys(window.ytInitialPlayerResponse).join(", "));
                                        var pr = window.ytInitialPlayerResponse;
                                        var sid = pr.serviceIntegrityDimensions || {};
                                        logs.push("  - serviceIntegrityDimensions exists: " + (!!pr.serviceIntegrityDimensions));
                                        logs.push("  - poToken in SID: " + (!!sid.poToken));
                                        var sd = pr.streamingData || {};
                                        logs.push("  - streamingData exists: " + (!!pr.streamingData));
                                        logs.push("  - poToken in streamingData: " + (!!sd.poToken));
                                    }
                                    logs.push("ytplayer exists: " + (!!window.ytplayer));
                                    if (window.ytplayer) {
                                        logs.push("  - config exists: " + (!!window.ytplayer.config));
                                        if (window.ytplayer.config) {
                                            logs.push("  - config.args exists: " + (!!window.ytplayer.config.args));
                                        }
                                        logs.push("  - bootstrapPlayerResponse exists: " + (!!window.ytplayer.bootstrapPlayerResponse));
                                    }
                                    logs.push("ytcfg exists: " + (!!window.ytcfg));
                                    if (window.ytcfg && typeof window.ytcfg.get === 'function') {
                                        logs.push("  - PLAYER_RESPONSE exists: " + (!!window.ytcfg.get('PLAYER_RESPONSE')));
                                        logs.push("  - PLAYER_VARS exists: " + (!!window.ytcfg.get('PLAYER_VARS')));
                                        logs.push("  - VISITOR_DATA: " + window.ytcfg.get('VISITOR_DATA'));
                                    }
                                    logs.push("Cookies on page: " + document.cookie);
                                    logs.push("Video elements on page: " + document.getElementsByTagName('video').length);
                                    if (document.getElementsByTagName('video').length > 0) {
                                        var v = document.getElementsByTagName('video')[0];
                                        logs.push("  - src: " + v.src);
                                        logs.push("  - paused: " + v.paused);
                                        logs.push("  - currentTime: " + v.currentTime);
                                        logs.push("  - error code: " + (v.error ? v.error.code : "none"));
                                    }
                                    var errorScreen = document.querySelector('.ytp-error');
                                    if (errorScreen) {
                                        logs.push("VISIBLE PLAYER ERROR: " + errorScreen.innerText);
                                    }
                                    console.log(logs.join("\n"));
                                    return logs.join("\n");
                                })();
                            """.trimIndent()
                            
                            evaluateJavascript(diagnosticScript) { diagnosticResult ->
                                println("[YT_DEBUG] [Telemetry] Timeout diagnostic scan dumped.")
                                safeResume(null)
                            }
                        }
                    }, 12000)
                }

                // 공식 모바일 Watch 페이지 진입
                webView.loadUrl(targetUrl)

                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        println("[YT_DEBUG] [Telemetry] Sandbox cancellation requested. Releasing web resources.")
                        safeResume(null)
                    }
                }
            }
        }
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[YT_DEBUG] [getUrl] 호출됨. 원본 URL: $url")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl
        println("[YT_DEBUG] [getUrl] 가공된 cleanedUrl: $cleanedUrl, 비디오 ID: $videoId")

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            println("[YT_DEBUG] [getUrl] 분석을 진행합니다.")
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = ConcurrentHashMap.newKeySet<String>()

                val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val streamUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(streamUrl)) return@mapNotNull null

                        val label = buildVideoLabelNumber(vs)
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0

                        var mime = vs.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)

                        val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                        StreamInfo(streamUrl, mime, height, label, initR, indexR)
                    } catch (e: Exception) { null }
                }.distinctBy { it.height }

                val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                    try {
                        val aUrl = asr.content ?: return@mapNotNull null
                        val bitrate = asr.bitrate ?: 128000
                        var mime = asr.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)

                        val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null

                        var rawLang = asr.audioTrackId ?: "Default"
                        if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { null }
                }.distinctBy { it.url }

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 파싱 에러: ${e.message}")
                }

                startServerIfNeeded()

                for (video in videoOnlyList) {
                    if (audiosByLanguage.isNotEmpty()) {
                        for ((lang, audios) in audiosByLanguage) {
                            val bestAudioForLang = if (video.mimeType.contains("webm")) {
                                audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("webm") }.thenByDescending { it.bitrate }).firstOrNull()
                            } else {
                                audios.sortedWith(compareByDescending<AudioInfo> { it.mimeType.contains("mp4") }.thenByDescending { it.bitrate }).firstOrNull()
                            }

                            if (bestAudioForLang != null) {
                                val singleAudioList = listOf(bestAudioForLang)
                                val dashXml = buildDashManifestXml(video, singleAudioList, durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    val finalName = "${video.label} ($lang) ${video.label}"
                                    val sourceName = "${this.name} ($lang)"

                                    builtLinks.add(
                                        newExtractorLink(
                                            sourceName,
                                            finalName,
                                            localLink,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.referer = mainUrl
                                            this.quality = video.height
                                            this.headers = mapOf(
                                                "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                                                "Origin" to "https://www.youtube.com",
                                                "Referer" to "https://www.youtube.com"
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                val muxedList = (s.videoStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val mUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(mUrl)) return@mapNotNull null
                        val label = buildVideoLabelNumber(vs)
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        Triple(mUrl, label, height)
                    } catch (e: Exception) { null }
                }
                for ((mUrl, mLabel, mHeight) in muxedList) {
                    builtLinks.add(
                        newExtractorLink(this.name, "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                            this.headers = mapOf(
                                "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                                "Origin" to "https://www.youtube.com",
                                "Referer" to "https://www.youtube.com"
                            )
                        }
                    )
                }

                builtLinks.add(
                    newExtractorLink(
                        source = "YouTube Downloader",
                        name = "Cobalt Web Download(4K)",
                        url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId",
                        type = INFER_TYPE
                    ) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )

                builtLinks.add(
                    newExtractorLink(
                        source = "YouTube Downloader",
                        name = "SOSYouTube Web Download(1080p)",
                        url = "https://www.sosyoutube.com/watch?v=$videoId",
                        type = INFER_TYPE
                    ) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )

                ytVideos[videoId] = builtLinks.toList()

            } catch (e: Exception) { 
                logError(e) 
            }
        }

        val finalLinks: List<ExtractorLink> = ytVideos[videoId] ?: emptyList()
        finalLinks.forEach { callback(it) }

        val finalSubtitles: List<SubtitlesStream> = ytVideosSubtitles[videoId] ?: emptyList()
        finalSubtitles.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                var url = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                
                if (url.startsWith("http")) {
                    url = url.replace(Regex("&fmt=[^&]+"), "")
                    if (!url.contains("fmt=srt")) {
                        url = "$url&fmt=srt"
                    }
                }
                newSubtitleFile(lang, url)
            } catch (e: Exception) { 
                null 
            }
        }.forEach { subtitleCallback(it) }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) {
                return
            }
            try {
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_DEBUG] [startServerIfNeeded] 가상 포트 $serverPort 리스너 바인 성공")
                
                thread(name = "YT-DASH-Server-Acceptor") {
                    println("[YT_DEBUG] [ServerSocket-Thread] accept() 대기 시작.")
                    try {
                        val active = activeServer
                        if (active != null) {
