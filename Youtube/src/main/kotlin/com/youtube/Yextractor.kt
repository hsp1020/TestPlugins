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
                return PoTokenResult(cached.first, "", "")
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
                PoTokenResult(generated.first, "", "")
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

        // 경량 임베드 샌드박스 + 지문 일치화 모바일 UA 크로스오버 가속화 엔진
        private suspend fun getPoTokenAndVisitor(videoId: String): Triple<String, String, String?>? = withContext(Dispatchers.Main) {
            val context = AcraApplication.context ?: return@withContext null
            // [최종 진화형 경량 우회]: 대용량 광고가 활성화되는 watch 대신 무광고 embed 주소 사용
            val embedUrl = "https://www.youtube.com/embed/$videoId?autoplay=1&mute=1"
            val startTime = System.currentTimeMillis()
            println("[YT_DEBUG] [Telemetry] Starting WebView attestation sandboxing on ad-free embed page.")
            println("[YT_DEBUG] [Telemetry] Target URL: $embedUrl")

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
                            val cookieStr = cookieManager.getCookie(embedUrl)
                            
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
                    
                    // [대조 지문 일치화]: 앱 패키지 헤더(X-Requested-With)와 정합성을 100% 동치시켜
                    // 'embedder.identity.denied' 필터를 통과하도록 모바일 순정 WebView UA를 채용합니다.
                    val defaultUa = settings.userAgentString
                    println("[YT_DEBUG] [Telemetry] [UA_Check] Using Genuine Default Mobile Webkit UA: $defaultUa")
                    
                    // 속도 극대화 리소스 필터 가동
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

                            val pollingScript = """
                                (function() {
                                    try {
                                        var poToken = null;
                                        var visitorData = null;
                                        
                                        var pr = window.ytInitialPlayerResponse;
                                        if (pr) {
                                            if (pr.serviceIntegrityDimensions && pr.serviceIntegrityDimensions.poToken) {
                                                poToken = pr.serviceIntegrityDimensions.poToken;
                                            }
                                            if (!poToken && pr.streamingData && pr.streamingData.poToken) {
                                                poToken = pr.streamingData.poToken;
                                            }
                                            if (pr.responseContext && pr.responseContext.visitorData) {
                                                visitorData = pr.responseContext.visitorData;
                                            }
                                        }
                                        
                                        if ((!poToken || !visitorData) && window.ytplayer) {
                                            var config = window.ytplayer.config;
                                            if (config && config.args) {
                                                var args = config.args;
                                                var raw = args.raw_player_response;
                                                var parsed = null;
                                                if (raw) {
                                                    parsed = (typeof raw === 'string') ? JSON.parse(raw) : raw;
                                                } else if (args.player_response) {
                                                    parsed = JSON.parse(args.player_response);
                                                }
                                                if (parsed) {
                                                    if (!poToken && parsed.serviceIntegrityDimensions && parsed.serviceIntegrityDimensions.poToken) {
                                                        poToken = parsed.serviceIntegrityDimensions.poToken;
                                                    }
                                                    if (!poToken && parsed.streamingData && parsed.streamingData.poToken) {
                                                        poToken = parsed.streamingData.poToken;
                                                    }
                                                    if (!visitorData && parsed.responseContext && parsed.responseContext.visitorData) {
                                                        visitorData = parsed.responseContext.visitorData;
                                                    }
                                                }
                                                if (!visitorData && args.visitor_data) {
                                                    visitorData = args.visitor_data;
                                                }
                                            }
                                        }
                                        
                                        if ((!poToken || !visitorData) && window.ytplayer) {
                                            var bpr = window.ytplayer.bootstrapPlayerResponse;
                                            if (bpr) {
                                                if (!poToken && bpr.serviceIntegrityDimensions && bpr.serviceIntegrityDimensions.poToken) {
                                                    poToken = bpr.serviceIntegrityDimensions.poToken;
                                                }
                                                if (!poToken && bpr.streamingData && bpr.streamingData.poToken) {
                                                    poToken = bpr.streamingData.poToken;
                                                }
                                                if (!visitorData && bpr.responseContext && bpr.responseContext.visitorData) {
                                                    visitorData = bpr.responseContext.visitorData;
                                                }
                                            }
                                        }
                                        
                                        if ((!poToken || !visitorData) && window.ytcfg && typeof window.ytcfg.get === 'function') {
                                            var cfgPr = window.ytcfg.get('PLAYER_VARS');
                                            var embeddedPr = cfgPr ? cfgPr.embedded_player_response : null;
                                            var pr2 = embeddedPr || window.ytcfg.get('PLAYER_RESPONSE');
                                            if (pr2) {
                                                var parsedCfg = (typeof pr2 === 'string') ? JSON.parse(pr2) : pr2;
                                                if (!poToken && parsedCfg.serviceIntegrityDimensions && parsedCfg.serviceIntegrityDimensions.poToken) {
                                                    poToken = parsedCfg.serviceIntegrityDimensions.poToken;
                                                }
                                                if (!poToken && parsedCfg.streamingData && parsedCfg.streamingData.poToken) {
                                                    poToken = parsedCfg.streamingData.poToken;
                                                }
                                                if (!visitorData && parsedCfg.responseContext && parsedCfg.responseContext.visitorData) {
                                                    visitorData = parsedCfg.responseContext.visitorData;
                                                }
                                            }
                                            if (!visitorData) {
                                                visitorData = window.ytcfg.get('VISITOR_DATA');
                                            }
                                        }
                                        
                                        if (!poToken) {
                                            var scripts = document.getElementsByTagName('script');
                                            for (var i = 0; i < scripts.length; i++) {
                                                var text = scripts[i].textContent || scripts[i].innerText || '';
                                                if (text.indexOf('poToken') !== -1) {
                                                    var m = text.match(/\"poToken\"\\s*:\\s*\"([^\"]+)\"/);
                                                    if (m) poToken = m[1];
                                                    var v = text.match(/\"visitorData\"\\s*:\\s*\"([^\"]+)\"/);
                                                    if (v) visitorData = visitorData || v[1];
                                                }
                                            }
                                        }

                                        if (poToken) {
                                            return JSON.stringify({ poToken: poToken, visitorData: visitorData || "" });
                                        }
                                        return JSON.stringify({
                                            error: 'Wait... ytInitialPlayerResponse: ' + (!!window.ytInitialPlayerResponse) + 
                                                   ', ytplayer.config: ' + (!!(window.ytplayer && window.ytplayer.config)) + 
                                                   ', ytcfg: ' + (!!window.ytcfg)
                                        });
                                    } catch(e) {
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
                                                println("[YT_DEBUG] [Telemetry] [Polling-Fallback] Success! Captured via Window Polling.")
                                                val cookieManager = android.webkit.CookieManager.getInstance()
                                                val cookieStr = cookieManager.getCookie(embedUrl)
                                                
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
                                console.log("[JS_HOOK] Injecting Fetch/XHR Interceptors now...");

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
                            
                            evaluateJavascript(
                                """
                                    (function() {
                                        console.log("[YT_PLAY_TRIGGER] Initializing play overrides...");
                                        
                                        var meta = document.createElement('meta');
                                        meta.name = 'referrer';
                                        meta.content = 'strict-origin-when-cross-origin';
                                        document.getElementsByTagName('head')[0].appendChild(meta);
                                        console.log("[YT_PLAY_TRIGGER] strict-origin-when-cross-origin Referrer Meta Tag Injected.");

                                        var v = document.querySelector('video');
                                        if (v) {
                                            v.play().then(function() { console.log("[YT_PLAY_TRIGGER] Auto-play successfully triggered."); })
                                            .catch(function(err) { console.log("[YT_PLAY_TRIGGER] Auto-play blocked: " + err.message); });
                                        }
                                        var btn = document.querySelector('.ytp-play-button');
                                        if (btn) {
                                            btn.click();
                                            console.log("[YT_PLAY_TRIGGER] Player button clicked.");
                                        }
                                    })();
                                """.trimIndent(), null
                            )
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
                                println("[YT_DEBUG] [Telemetry] Timeout diagnostic scan dumped to console.")
                                safeResume(null)
                            }
                        }
                    }, 12000)
                }

                // 모바일 레퍼러 헤더 주입 로드 수행
                val extraHeaders = mapOf(
                    "Referer" to "https://www.youtube.com/",
                    "Origin" to "https://www.youtube.com"
                )
                webView.loadUrl(embedUrl, extraHeaders)

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
                println("[YT_DEBUG] [startServerIfNeeded] 가상 포트 $serverPort 리스너 바인딩 성공")
                
                thread(name = "YT-DASH-Server-Acceptor") {
                    println("[YT_DEBUG] [ServerSocket-Thread] accept() 대기 시작.")
                    try {
                        val active = activeServer
                        if (active != null) {
                            while (!active.isClosed) {
                                val client = active.accept()
                                client.soTimeout = 5000 
                                serverExecutor.execute { handleClient(client) }
                            }
                        }
                    } catch (e: Exception) {
                        println("[YT_DEBUG] [ServerSocket-Thread] accept() 도중 오류 발생: ${e.message}")
                    } finally {
                        synchronized(serverLock) {
                            try {
                                activeServer?.close()
                            } catch (_: Exception) {}
                            activeServer = null
                            serverPort = 0
                        }
                        println("[YT_DEBUG] [ServerSocket-Thread] 리스너 중단 및 포트 가상화 초기화.")
                    }
                }
            } catch (e: Exception) { 
                logError(e) 
            }
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
                val os = socket.getOutputStream()
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        val cleanPath = parts[1].substringAfter("/").substringBefore("?")
                        val path = if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
                            cleanPath.substring(0, cleanPath.length - 4)
                        } else {
                            cleanPath
                        }
                        val content = manifestMap[path]
                        if (content != null) {
                            val bodyBytes = content.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                            val headerBuilder = java.lang.StringBuilder()
                            headerBuilder.append("HTTP/1.1 200 OK\r\n")
                            headerBuilder.append("Content-Type: application/dash+xml\r\n")
                            headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                            headerBuilder.append("Connection: close\r\n")
                            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                            headerBuilder.append("\r\n")

                            val headerBytes = headerBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                            os.write(headerBytes)
                            os.write(bodyBytes)
                            os.flush()
                        } else {
                            val errorResponse = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                            os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            os.flush()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] [handleClient] 소켓 예외 발생: ${e.message}")
        }
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = java.lang.StringBuilder(2048)
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vMime = video.mimeType
        val vCodecs = if (vMime.contains("webm")) "vp9" else "avc1.4d401f"

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="$vMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="$vCodecs">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val cleanAudioUrl = escapeXml(audio.url)
            val audioId = "audio_$index"
            val aMime = audio.mimeType
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$cleanAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }

    private fun escapeXml(url: String): String {
        if (!url.contains('&') && !url.contains('<') && !url.contains('>') && !url.contains('\'') && !url.contains('"')) return url
        val sb = java.lang.StringBuilder(url.length + 16)
        for (i in 0 until url.length) {
            val c = url[i]
            when (c) {
                '&' -> sb.append("&amp;")
                '<' -> sb.append("&lt;")
                '>' -> sb.append("&gt;")
                '\'' -> sb.append("&apos;")
                '"' -> sb.append("&quot;")
                else -> sb.append(c)
            }
        }
        return sb.toString()
    }

    private fun getMimeTypeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = YoutubeUtils.urlDecode(url)
            if (decoded.contains("video/webm") || decoded.contains("audio/webm")) {
                if (isAudio) "audio/webm" else "video/webm"
            } else {
                if (isAudio) "audio/mp4" else "video/mp4"
            }
        } catch (e: Exception) { if (isAudio) "audio/mp4" else "video/mp4" }
    }

    private fun buildVideoLabelNumber(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val height = runCatching { vs.height }.getOrNull() ?: 0
        if (height > 0) return height.toString()
        return "video"
    }
}
