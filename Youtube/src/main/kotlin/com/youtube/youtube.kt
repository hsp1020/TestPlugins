package com.lagradost.cloudstream3.ar.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.localization.ContentCountry
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.Config.SLEEP_BETWEEN
import com.lagradost.cloudstream3.AcraApplication

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

// [완벽 디버깅판] NewPipe의 2-Token 체계를 100% 반영한 PoTokenProvider
object YoutubePoTokenProvider : PoTokenProvider {
    
    private val WebPoTokenGenLock = Any() // 스레드 충돌 방지 락
    
    @Volatile private var cachedVideoId: String = ""
    @Volatile private var visitorData: String = ""
    @Volatile private var playerPot: String = ""     // videoId로 서명된 초기 호출용 토큰
    @Volatile private var streamingPot: String = ""  // visitorData로 서명된 청크 다운로드용 토큰

    private val handler = Handler(Looper.getMainLooper())

    init {
        println("[YT_TOKEN_DEBUG] 듀얼 토큰 하이재킹 모듈 초기화 완료")
    }

    private fun getOrFetchTokens(videoId: String, forceRecreate: Boolean = false): PoTokenResult? {
        synchronized(WebPoTokenGenLock) {
            // 캐시가 유효하면 즉시 반환 (두 토큰이 모두 존재해야 함)
            if (!forceRecreate && cachedVideoId == videoId && playerPot.isNotBlank() && streamingPot.isNotBlank()) {
                return PoTokenResult(visitorData, playerPot, streamingPot)
            }

            val context = AcraApplication.context ?: return null
            println("[YT_TOKEN_DEBUG] 비디오($videoId) 전용 PlayerPot & StreamingPot 추출 시작...")

            val latch = CountDownLatch(1)
            var extVisitor = ""
            var extPlayer = ""
            var extStreaming = ""

            handler.post {
                try {
                    val webView = WebView(context)
                    @SuppressLint("SetJavaScriptEnabled")
                    webView.settings.javaScriptEnabled = true
                    webView.settings.domStorageEnabled = true
                    webView.settings.mediaPlaybackRequiresUserGesture = false // 버퍼링이 시작되어야 streamingPot이 나오므로 자동재생 허용
                    webView.settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"

                    // 안드로이드 인터페이스 브릿지
                    webView.addJavascriptInterface(object {
                        @JavascriptInterface
                        fun onTokensReady(vData: String, pPot: String, sPot: String) {
                            if (pPot.length > 50 && sPot.length > 50) {
                                extVisitor = vData
                                extPlayer = pPot
                                extStreaming = sPot
                                latch.countDown()
                            }
                        }
                    }, "PoTokenBridge")

                    webView.webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            
                            // [핵심 로직] /v1/player와 /videoplayback 통신을 동시에 가로채어 2개의 토큰을 모두 수집
                            val script = """
                                (function() {
                                    let capturedVisitor = '';
                                    let capturedPlayerPot = '';
                                    let capturedStreamingPot = '';

                                    function checkAndSend() {
                                        if (capturedVisitor && capturedPlayerPot && capturedStreamingPot) {
                                            PoTokenBridge.onTokensReady(capturedVisitor, capturedPlayerPot, capturedStreamingPot);
                                        }
                                    }

                                    // 1. /v1/player 가로채기 (visitorData, playerPot 수집)
                                    const originalFetch = window.fetch;
                                    window.fetch = async function(...args) {
                                        const reqUrl = args[0];
                                        if (typeof reqUrl === 'string' && reqUrl.includes('/youtubei/v1/player')) {
                                            try {
                                                const options = args[1];
                                                if (options && options.body) {
                                                    const bodyObj = JSON.parse(options.body);
                                                    if (bodyObj.context && bodyObj.context.client && bodyObj.context.client.visitorData) {
                                                        capturedVisitor = bodyObj.context.client.visitorData;
                                                    }
                                                    if (bodyObj.serviceIntegrityDimensions && bodyObj.serviceIntegrityDimensions.poToken) {
                                                        capturedPlayerPot = bodyObj.serviceIntegrityDimensions.poToken;
                                                    }
                                                    checkAndSend();
                                                }
                                            } catch(e) {}
                                        }
                                        
                                        // 2. 혹시 fetch로 videoplayback을 요청할 경우 대비
                                        if (typeof reqUrl === 'string' && reqUrl.includes('/videoplayback') && reqUrl.includes('pot=')) {
                                            try {
                                                const params = new URLSearchParams(reqUrl.split('?')[1]);
                                                if (params.has('pot')) {
                                                    capturedStreamingPot = params.get('pot');
                                                    checkAndSend();
                                                }
                                            } catch(e) {}
                                        }
                                        return originalFetch.apply(this, args);
                                    };

                                    // 3. /videoplayback XHR 가로채기 (streamingPot 수집)
                                    const originalXhrOpen = XMLHttpRequest.prototype.open;
                                    XMLHttpRequest.prototype.open = function(method, reqUrl) {
                                        if (typeof reqUrl === 'string' && reqUrl.includes('/videoplayback') && reqUrl.includes('pot=')) {
                                            try {
                                                const params = new URLSearchParams(reqUrl.split('?')[1]);
                                                if (params.has('pot')) {
                                                    capturedStreamingPot = params.get('pot');
                                                    checkAndSend();
                                                }
                                            } catch(e) {}
                                        }
                                        originalXhrOpen.apply(this, arguments);
                                    };
                                })();
                            """.trimIndent()
                            view?.evaluateJavascript(script, null)
                        }
                    }

                    // autoplay=1을 줘서 즉시 버퍼링(streamingPot 발생) 유도
                    webView.loadUrl("https://www.youtube.com/embed/$videoId?autoplay=1")

                    handler.postDelayed({
                        try {
                            webView.stopLoading()
                            webView.destroy()
                            if (latch.count > 0) latch.countDown()
                        } catch (e: Exception) {}
                    }, 15000) // 최대 15초 대기

                } catch (e: Exception) {
                    latch.countDown()
                }
            }

            try { latch.await(15, TimeUnit.SECONDS) } catch (e: InterruptedException) {}

            if (extVisitor.isNotBlank() && extPlayer.isNotBlank() && extStreaming.isNotBlank()) {
                println("[YT_TOKEN_DEBUG] 토큰 2종 수집 완료! PlayerPot=${extPlayer.take(10)}..., StreamingPot=${extStreaming.take(10)}...")
                cachedVideoId = videoId
                visitorData = extVisitor
                playerPot = extPlayer
                streamingPot = extStreaming
                return PoTokenResult(extVisitor, extPlayer, extStreaming)
            }

            println("[YT_TOKEN_DEBUG] 듀얼 토큰 수집 실패.")
            return null
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? = getOrFetchTokens(videoId)
    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getOrFetchTokens(videoId)
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = getOrFetchTokens(videoId)
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = getOrFetchTokens(videoId)
}

object YoutubeUtils {
    const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    const val IOS_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    const val DEFAULT_CLIENT_VERSION = "2.20240725.01.00"

    private val sha1Digest = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-1")
    }

    val YT_ID_REGEX = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
    val MIX_TITLE_REPLACE_KO = Regex("""\s*\(믹스\s*재생목록\)""", RegexOption.IGNORE_CASE)
    val MIX_TITLE_REPLACE_EN = Regex("""\s*\(Mix\s*Playlist\)""", RegexOption.IGNORE_CASE)

    private val regexCache = ConcurrentHashMap<String, Regex>()

    // NewPipe Extractor 통신 엔진 초기화
    fun initNewPipeDownloader() {
        val downloader = object : Downloader() {
            private val okHttpClient = okhttp3.OkHttpClient.Builder().build()
            
            override fun execute(request: Request): org.schabi.newpipe.extractor.downloader.Response {
                val method = request.httpMethod()
                val url = request.url()
                val headers = request.headers()
                val data = request.dataToSend()

                val reqBody = data?.toRequestBody(null)
                val reqBuilder = okhttp3.Request.Builder()
                    .url(url)
                    .method(method, reqBody)

                headers.forEach { (key, values) ->
                    // Origin, Referer를 아예 제외시켜 iOS 앱인 척 완벽 위장
                    if (!key.equals("Origin", true) && !key.equals("Referer", true)) {
                        values.forEach { reqBuilder.addHeader(key, it) }
                    }
                }
                
                reqBuilder.header("User-Agent", IOS_USER_AGENT)

                val res = okHttpClient.newCall(reqBuilder.build()).execute()
                val resHeaders = res.headers.toMultimap()
                val resBodyStr = res.body?.string() ?: ""
                
                return org.schabi.newpipe.extractor.downloader.Response(
                    res.code, res.message, resHeaders, resBodyStr, request.url()
                )
            }
        }
        NewPipe.init(downloader, Localization.DEFAULT, ContentCountry.DEFAULT)
    }

    fun sha1(input: String): String {
        val md = sha1Digest.get()
        md.reset()
        val bytes = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun getSapisidHash(sapisid: String, origin: String = "https://www.youtube.com"): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val msg = "$timestamp $sapisid $origin"
        val hash = sha1(msg)
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    fun buildCookiesAndExtractSapisid(
        prefs: SharedPreferences?, 
        lang: String, 
        fallbackVisitor: String,
        isSearch: Boolean = false
    ): Pair<String, String?> {
        val cookieBuilder = java.lang.StringBuilder()
        val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)
        if (!visitor.isNullOrBlank()) {
            cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
        } else {
            cookieBuilder.append("VISITOR_INFO1_LIVE=$fallbackVisitor; ")
        }

        var sapisidVal: String? = null
        if (!isSearch) {
            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                    if (key == "SAPISID") sapisidVal = value
                }
            }
        }
        cookieBuilder.append("PREF=f6=40000000&hl=$lang; CONSENT=YES+fx.456722336;")
        return Pair(cookieBuilder.toString(), sapisidVal)
    }

    fun getProxiedPoster(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return try {
            "https://wsrv.nl/?url=${URLEncoder.encode(poster, "UTF-8")}&w=300&h=450&fit=contain&cbg=black"
        } catch (e: Exception) {
            poster
        }
    }

    fun findConfig(html: String, key: String): String? {
        return try {
            val regex = regexCache.computeIfAbsent(key) { k ->
                Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"")
            }
            regex.find(html)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)
                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    fun extractYoutubeId(url: String): String? {
        return YT_ID_REGEX.find(url)?.groupValues?.getOrNull(1) ?: run {
            if (url.length == 11 && url.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                url
            } else {
                null
            }
        }
    }

    fun cleanMixTitle(title: String): String {
        return MIX_TITLE_REPLACE_EN.replace(MIX_TITLE_REPLACE_KO.replace(title, ""), "").trim()
    }

    fun urlDecode(input: String): String {
        var current = input
        var decodeCount = 0
        while (current.contains('%') && decodeCount < 3) {
            val decoded = try {
                URLDecoder.decode(current, "UTF-8")
            } catch (e: Exception) {
                break
            }
            if (decoded == current) break
            current = decoded
            decodeCount++
        }
        return current
    }

    fun getMapKeyFromAny(obj: Any?, key: String): Map<*, *>? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? Map<*, *>
    }

    fun getListKeyFromAny(obj: Any?, key: String): List<*>? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? List<*>
    }

    fun getStringFromAny(obj: Any?, key: String): String? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? String ?: value?.toString()
    }

    fun extractUrlFromLockup(lockup: Map<*, *>?): String? {
        if (lockup == null) return null
        val command = getMapKeyFromAny(lockup, "onTap")?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "rendererContext")?.let { getMapKeyFromAny(it, "commandContext") }?.let { getMapKeyFromAny(it, "onTap") }?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "itemPlayback")?.let { getMapKeyFromAny(it, "inlinePlayerData") }?.let { getMapKeyFromAny(it, "onSelect") }?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "navigationEndpoint")
        
        val webCommandUrl = getMapKeyFromAny(command, "commandMetadata")?.let { getMapKeyFromAny(it, "webCommandMetadata") }?.let { getStringFromAny(it, "url") }
        if (!webCommandUrl.isNullOrBlank()) return webCommandUrl

        val watch = getMapKeyFromAny(command, "watchEndpoint") ?: command
        val videoId = getStringFromAny(watch, "videoId")
        val playlistId = getStringFromAny(watch, "playlistId")
        if (!playlistId.isNullOrBlank()) {
            return if (!videoId.isNullOrBlank()) {
                "/watch?v=$videoId&list=$playlistId"
            } else {
                "/watch?list=$playlistId"
            }
        }
        return null
    }
}

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {

    val appPrefs: SharedPreferences?
        get() = sharedPref ?: AcraApplication.context?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true
    )
    
    data class CachedChip(
        @JsonProperty("name") val name: String,
        @JsonProperty("token") val token: String
    )

    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    
    override var lang: String
        get() = appPrefs?.getString("youtube_language", "ko") ?: "ko"
        set(value) {}
        
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    companion object {
        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()

        // SharedPreferences 연속 I/O 병목을 해결하기 위한 인메모리 캐시 맵
        private val continuationTokenCache = ConcurrentHashMap<String, String>()

        private val VIEW_REPLACE_REGEX = Regex("조회수|views|مشاهدة|시청 중|시청중|watching|명|시청자")
        private val VIEW_FORMAT_REGEX = Regex(".*[천만억KMBkmb].*")
        private val METADATA_VIEW_REGEX = Regex(".*(\\d+[KMBkmb]|views|조회수|회|시청|watching|مشاهدة|명|시청자|مشاهد).*")
        private val METADATA_SKIP_REGEX = Regex(".*(\\d+:\\d+|ago|قبل|전|시간|분|일|주|개월|년|시청|watching).*")
        private val SUBSCRIBER_REGEX = Regex(".*(구독자|subscribers|명|K|M|B|sub).*\\s*", RegexOption.IGNORE_CASE)
        private val SUBSCRIBER_REPLACE_REGEX = Regex("(구독자|subscribers?)\\s*", RegexOption.IGNORE_CASE)
        private val API_KEY_EXTRACT_REGEX = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
    }

    private var isSearchContext = false

    class YouTubeInterceptor(private val provider: YoutubeProvider) : Interceptor {
        private val prefs get() = provider.appPrefs

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val urlStr = request.url.toString()
            val isSearchRequest = urlStr.contains("/results") || urlStr.contains("/v1/search")

            val requestBuilder = request.newBuilder()
                .header("User-Agent", YoutubeUtils.CHROME_USER_AGENT)

            val origReferer = request.header("Referer")
            if (origReferer != null && (origReferer.contains("/results") || origReferer.contains("/v1/search"))) {
                requestBuilder.header("Referer", "https://www.youtube.com/")
            }

            if (!isSearchRequest) {
                val (cookies, sapisidVal) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, provider.lang, "_Mk3UVhY40g", isSearch = false)
                requestBuilder.addHeader("Cookie", cookies)
                requestBuilder.addHeader("Accept-Language", "${provider.lang},en-US;q=0.9,en;q=0.8")

                if (!sapisidVal.isNullOrBlank()) {
                    try {
                        val authHeader = YoutubeUtils.getSapisidHash(sapisidVal)
                        requestBuilder.addHeader("Authorization", authHeader)
                        requestBuilder.addHeader("X-Origin", "https://www.youtube.com")
                        requestBuilder.addHeader("X-Goog-AuthUser", "0")
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            } else {
                val (cookies, _) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, provider.lang, "_Mk3UVhY40g", isSearch = true)
                requestBuilder.addHeader("Cookie", cookies)
                requestBuilder.addHeader("Accept-Language", "${provider.lang},en-US;q=0.9,en;q=0.8")
            }

            return chain.proceed(requestBuilder.build())
        }
    }
    
    private val ytInterceptor = YouTubeInterceptor(this)

    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    @Suppress("PropertyName")
    private data class PlayerResponse(
        @JsonProperty("streamingData") val streamingData: StreamingData?,
        @JsonProperty("playbackTracking") val playbackTracking: PlaybackTracking? 
    )
    private data class StreamingData(
        @JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?
    )

    private data class PlaybackTracking(
        @JsonProperty("videostatsPlaybackUrl") val videostatsPlaybackUrl: TrackingUrl?,
        @JsonProperty("videostatsWatchtimeUrl") val videostatsWatchtimeUrl: TrackingUrl?
    )
    private data class TrackingUrl(
        @JsonProperty("baseUrl") val baseUrl: String?
    )

    private fun generateCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { alphabet.random() }.joinToString("")
    }

    private fun buildTrackingUrl(base: String, cpn: String, cmt: String, state: String?, realTime: String? = null): String {
        val uri = android.net.Uri.parse(base).buildUpon()
        uri.appendQueryParameter("cpn", cpn)
        uri.appendQueryParameter("cmt", cmt)
        uri.appendQueryParameter("ver", "2")
        if (state != null) {
            uri.appendQueryParameter("state", state)
            uri.appendQueryParameter("st", "0.000")
            uri.appendQueryParameter("et", cmt)
        }
        if (realTime != null) {
            uri.appendQueryParameter("rt", realTime)
        }
        return uri.build().toString()
    }

    private suspend fun triggerPlaybackTracking(playbackTracking: PlaybackTracking?, videoId: String) {
        val playbackBaseUrl = playbackTracking?.videostatsPlaybackUrl?.baseUrl
        val watchtimeBaseUrl = playbackTracking?.videostatsWatchtimeUrl?.baseUrl

        if (playbackBaseUrl.isNullOrBlank() || watchtimeBaseUrl.isNullOrBlank()) {
            println("[YT_DEBUG] playbackTracking base URLs are empty, skipping tracking")
            return
        }

        val cpn = generateCpn()
        println("[YT_DEBUG] Triggering playback tracking for videoId: $videoId with cpn: $cpn")

        try {
            val playbackUrl = buildTrackingUrl(playbackBaseUrl, cpn, cmt = "0.000", state = null, realTime = "0")
            println("[YT_DEBUG] Pinging playbackUrl: $playbackUrl")
            app.get(playbackUrl, interceptor = ytInterceptor)

            val watchtimeUrl = buildTrackingUrl(watchtimeBaseUrl, cpn, cmt = "5.000", state = "playing", realTime = "5")
            println("[YT_DEBUG] Pinging watchtimeUrl: $watchtimeUrl")
            app.get(watchtimeUrl, interceptor = ytInterceptor)
            
            println("[YT_DEBUG] Playback tracking successfully triggered and watch history synced.")
        } catch (e: Exception) {
            println("[YT_DEBUG] Failed to trigger playback tracking: ${e.message}")
            logError(e)
        }
    }
    
    private fun getSavedContinuationToken(key: String): String? {
        val hashedKey = YoutubeUtils.sha1(key)
        val cached = continuationTokenCache[hashedKey]
        if (cached != null) return cached
        
        val token = appPrefs?.getString("cont_token_$hashedKey", null)
        if (token != null) {
            continuationTokenCache[hashedKey] = token
        }
        return token
    }

    private fun saveContinuationToken(key: String, token: String?) {
        val hashedKey = YoutubeUtils.sha1(key)
        if (token.isNullOrBlank()) {
            val removed = continuationTokenCache.remove(hashedKey)
            if (removed != null) {
                appPrefs?.edit()?.remove("cont_token_$hashedKey")?.apply()
            }
        } else {
            val oldToken = continuationTokenCache.put(hashedKey, token)
            if (oldToken != token) {
                appPrefs?.edit()?.putString("cont_token_$hashedKey", token)?.apply()
            }
        }
    }

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? {
        return (this[key] as? Map<*, *>) 
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>)
    }

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? {
        return (this[key] as? List<Map<*, *>>)
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>)
    }

    private fun Map<*, *>.getString(key: String): String? {
        return (this[key] as? String)
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? String)
    }

    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: (obj.getListKey("runs") ?: YoutubeUtils.getListKeyFromAny(obj, "runs"))?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> (run["text"] as? String)
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    private fun extractPartsFromMetadataRows(rows: List<*>?, viewFormatter: (String) -> String): List<String> {
        val collected = mutableListOf<String>()
        rows?.forEach { row ->
            val rowMap = row as? Map<*, *> ?: return@forEach
            val parts = (rowMap["metadataParts"] as? List<*>)
                ?: (rowMap.entries.firstOrNull { (it.key as? String) == "metadataParts" }?.value as? List<*>)
            parts?.forEach { part ->
                val partMap = part as? Map<*, *> ?: return@forEach
                val text = getText(partMap.getMapKey("text") ?: partMap["text"])
                if (text.isNotBlank()) {
                    if (METADATA_VIEW_REGEX.matches(text)) {
                        collected.add(viewFormatter(text))
                    } else {
                        collected.add(text)
                    }
                }
            }
        }
        return collected
    }

    private fun extractLockupMetadata(lockup: Map<*, *>) : Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            val rawParts = extractPartsFromMetadataRows(rows) { formatViews(it) }
            rawParts.forEach { text ->
                if (text.contains("회") || text.contains("시청") || text.contains("조회") || 
                    text.contains("views") || text.contains("watching") || 
                    text.contains("명") || text.contains("시청자") || text.contains("مشاهد")) {
                    views = text
                } else if (!METADATA_SKIP_REGEX.matches(text) && text.length > 1 && !text.contains("•")) {
                    channel = text
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (direct.isNotBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        var text = viewText.trim()
        
        val isLive = text.contains("명") || text.contains("시청자") || text.contains("watching") || text.contains("시청 중")

        text = VIEW_REPLACE_REGEX.replace(text, "").trim()

        if (VIEW_FORMAT_REGEX.matches(text) && text.length < 15) {
            return if (isLive) {
                if (lang == "ko" && !text.contains("명")) "${text}명" 
                else if (lang != "ko" && !text.contains("watching")) "${text} watching"
                else text
            } else {
                if (lang == "ko" && !text.contains("회")) "${text}회" 
                else text
            }
        }
        
        val sb = java.lang.StringBuilder(text.length)
        for (i in 0 until text.length) {
            val c = text[i]
            if (c in '0'..'9') sb.append(c)
        }
        val digits = sb.toString()
        if (digits.isBlank()) return text
        
        return try {
            val v = digits.toLong()
            if (lang == "ko") {
                val suffix = if (isLive) { "명" } else { "회" }
                when {
                    v < 1000 -> "$v$suffix"
                    v < 10000 -> String.format("%.1f천$suffix", v / 1000.0).replace(".0천", "천")
                    v < 100000 -> String.format("%.1f만$suffix", v / 10000.0).replace(".0만", "만")
                    v < 100000000 -> "${v / 10000}만$suffix"
                    else -> String.format("%.1f억$suffix", v / 100000000.0).replace(".0억", "억")
                }
            } else {
                val suffix = if (isLive) " watching" else ""
                when {
                    v < 1000 -> "$v$suffix"
                    v < 1_000_000 -> String.format("%.1fK$suffix", v / 1000.0).replace(".0K", "K")
                    v < 1_000_000_000 -> String.format("%.1fM$suffix", v / 1_000_000.0).replace(".0M", "M")
                    else -> String.format("%.1fB$suffix", v / 1_000_000_000.0).replace(".0B", "B")
                }
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")
            ?: renderer.getMapKey("playlistVideoRenderer")       
            ?: renderer.getMapKey("playlistPanelVideoRenderer")  

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = YoutubeUtils.getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = YoutubeUtils.buildThumbnailFromId(videoId)
                
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
                }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                val descText = if (views.isNotBlank()) "조회수: $views" else ""
                if (currentList.none { it.data == finalUrl }) {
                    currentList.add(newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                        this.description = descText
                    })
                }

                val finalTitle = "#$episodeNum {Short | $views} $title"
                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            val contentType = lockup.getString("contentType") ?: ""
            if (contentType.contains("AD") || contentType.contains("SPONSORED") || contentType.contains("PROMOTED")) {
                return null
            }

            if (contentType == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    var title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"

                    if (playlistId.startsWith("RD") || playlistId.startsWith("UL") || playlistId.startsWith("MM") || playlistId.startsWith("RDMM")) {
                        title = YoutubeUtils.cleanMixTitle(title)
                    }

                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val extractedUrl = YoutubeUtils.extractUrlFromLockup(lockup)
                    val targetPath = if (!extractedUrl.isNullOrBlank()) {
                        extractedUrl.removePrefix("/")
                    } else {
                        if (playlistId.startsWith("RD") || playlistId.startsWith("UL") || playlistId.startsWith("MM") || playlistId.startsWith("RDMM")) {
                            "watch?list=$playlistId"
                        } else {
                            "playlist?list=$playlistId"
                        }
                    }

                    return newTvSeriesSearchResponse(
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title,
                        "$mainUrl/$targetPath",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
                    }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isBlank()) {
                    return null
                }
                var (channel, views) = extractLockupMetadata(lockup)     
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|ع버|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    
                    val descText = buildString {
                        if (channel.isNotBlank()) append("채널: $channel")
                        if (views.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append("조회수: $views")
                        }
                    }
                    if (currentList.none { it.data == finalUrl }) {
                        currentList.add(newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                            this.description = descText
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {Short | $views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = YoutubeUtils.getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) {
                        this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
                    }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = YoutubeUtils.getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
                }
            }
        }
        return null
    }

    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean,
        depth: Int = 0
    ) {
        if (data == null || depth > 32) return // Stack Overflow 방지용 Depth 안전 보호막
        if (data is Map<*, *>) {
            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "continuationItems",
                "onResponseReceivedActions",
                "onResponseReceivedEndpoints",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "richGridRenderer"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode, depth + 1)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode, depth + 1)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode, depth + 1)
            }
        }
    }

    private fun extractBalancedJson(input: String): String? {
        var braceCount = 0
        var inString = false
        var escape = false
        val len = input.length
        for (i in 0 until len) {
            val char = input[i]
            if (escape) {
                escape = false
                continue
            }
            if (char == '\\') {
                escape = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (char == '{') {
                    braceCount++
                } else if (char == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return input.substring(0, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        var index = 0
        val htmlLen = html.length
        while (true) {
            val found = html.indexOf("ytInitialData", index)
            if (found == -1) break
            val contextEnd = minOf(found + 100, htmlLen)
            var hasAssign = false
            var hasVarOrWindow = false
            
            for (i in found until contextEnd) {
                if (html[i] == '=') {
                    hasAssign = true
                    break
                }
            }
            
            if (html.indexOf("var", found, ignoreCase = false) in found until contextEnd ||
                html.indexOf("window", found, ignoreCase = false) in found until contextEnd ||
                html.startsWith("ytInitialData", found)) {
                hasVarOrWindow = true
            }

            if (hasAssign && hasVarOrWindow) {
                val braceIndex = html.indexOf("{", found)
                if (braceIndex != -1 && braceIndex - found < 150) {
                    val possibleJson = html.substring(braceIndex)
                    val jsonString = extractBalancedJson(possibleJson)
                    if (jsonString != null) {
                        try {
                            return parseJson<Map<String, Any>>(jsonString)
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            index = minOf(found + 13, htmlLen)
        }
        return null
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            val renderer = data["continuationItemRenderer"] as? Map<*, *>
            if (renderer != null) {
                val endpoint = renderer["continuationEndpoint"] as? Map<*, *>
                if (endpoint != null) {
                    val token = (endpoint["continuationCommand"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["browseContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["searchContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["command"] as? Map<*, *>)?.get("token") as? String
                        ?: endpoint["token"] as? String
                    if (!token.isNullOrBlank()) return token
                }
            }

            val nextData = data["nextContinuationData"] as? Map<*, *>
            if (nextData != null) {
                val token = nextData["continuation"] as? String
                if (!token.isNullOrBlank()) return token
            }

            for ((k, v) in data) {
                val keyStr = k as? String ?: continue
                if (keyStr == "feedFilterChipBarRenderer" || 
                    keyStr == "chipCloudRenderer" || 
                    keyStr == "relatedChipCloudRenderer" ||
                    keyStr == "chipCloudChipRenderer") {
                    continue
                }
                val t = findTokenRecursive(v)
                if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i)
                if (t != null) return t
            }
        }
        return null
    }
    
    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val homeTitle = when(lang) {
                "ko" -> "홈"
                "ar" -> "الرئيسية"
                else -> "Home"
            }

            if (appPrefs?.getBoolean("show_trending_home", true) == true) {
                list.add(MainPageData(homeTitle, "Home", horizontalImages = true))
                
                val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
                try {
                    val chips: List<CachedChip> = AppUtils.parseJson(cachedChipsJson)
                    chips.forEach { chip ->
                        list.add(MainPageData(chip.name, "chip_${chip.token}", horizontalImages = true))
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }

            val customSections = getCustomHomepages()
            customSections.filter { it.isEnabled && it.url != "Home" }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url, horizontalImages = true))
            }

            return list
        }

    private fun getCustomHomepages(): List<CustomSection> {
        val json = appPrefs?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }

    private fun resetContinuation() {
        savedContinuationToken = null
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        isSearchContext = false
        val rawRequestData = request.data
        val requestData = YoutubeUtils.urlDecode(rawRequestData)
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null
        val isChip = requestData.startsWith("chip_")

        try {
            if (page == 1) {
                if (isChip) {
                    savedApiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    savedVisitorData = savedVisitorData ?: appPrefs?.getString("yt_visitor_data", null)
                    savedClientVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION)
                    val tokenToUse = requestData.removePrefix("chip_")

                    if (!savedApiKey.isNullOrBlank()) {
                        val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to (savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION),
                                    "platform" to "DESKTOP",
                                    "hl" to lang
                                )
                            ),
                            "continuation" to tokenToUse
                        )
                        
                        val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                        val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        
                        if (response != null) {
                            val actions = response["onResponseReceivedActions"] 
                                ?: response["onResponseReceivedEndpoints"]
                                ?: response["onResponseReceivedCommands"] 
                                ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                } else {
                    val targetUrl = if(requestData == "Home") mainUrl else requestData
                    val html = app.get(targetUrl, interceptor = ytInterceptor).text
                    
                    savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                    savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                    savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    
                    if (!savedApiKey.isNullOrBlank()) {
                        appPrefs?.edit()
                            ?.putString("yt_api_key", savedApiKey)
                            ?.putString("yt_visitor_data", savedVisitorData)
                            ?.putString("yt_client_version", savedClientVersion)
                            ?.apply()
                    }

                    val initialData = extractYtInitialData(html)
                    if (initialData != null) {
                        if (requestData == "Home") {
                            val chipBar = safeGet(initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "richGridRenderer", "header", "feedFilterChipBarRenderer", "contents") as? List<*>
                            if (chipBar != null) {
                                val jsonArray = org.json.JSONArray()
                                for (chipItem in chipBar) {
                                    val chipMap = (chipItem as? Map<*, *>)?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
                                    val text = getText(chipMap["text"])
                                    val token = safeGet(chipMap, "navigationEndpoint", "continuationCommand", "token") as? String
                                    if (text.isNotBlank() && !token.isNullOrBlank()) {
                                        val obj = org.json.JSONObject()
                                        obj.put("name", text)
                                        obj.put("token", token)
                                        jsonArray.put(obj)
                                    }
                                }
                                if (jsonArray.length() > 0) {
                                    appPrefs?.edit()?.putString("yt_cached_chips", jsonArray.toString())?.apply()
                                }
                            }
                        }
                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {
                var tokenToUse = getSavedContinuationToken(requestData)
                
                if (tokenToUse.isNullOrBlank() && page == 2) { 
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION

                    if (isChip) {
                        val rawToken = requestData.removePrefix("chip_")
                        val apiUrl = "$mainUrl/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB", 
                                    "clientVersion" to cVersion, 
                                    "hl" to lang,
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to rawToken
                        )
                        val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                        val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        if (response != null) {
                            tokenToUse = findTokenRecursive(response)
                            saveContinuationToken(requestData, tokenToUse)
                        }
                    } else {
                        val urlToGet = if(requestData == "Home") mainUrl else requestData
                        val html = app.get(urlToGet, interceptor = ytInterceptor).text
                        val initialData = extractYtInitialData(html)
                        if (initialData != null) {
                            tokenToUse = findTokenRecursive(initialData)
                            saveContinuationToken(requestData, tokenToUse)
                        }
                    }
                }

                if (!tokenToUse.isNullOrBlank()) {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    val apiUrl = if (requestData.contains("/results")) "$mainUrl/youtubei/v1/search?key=$apiKey" else "$mainUrl/youtubei/v1/browse?key=$apiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB", 
                                "clientVersion" to cVersion, 
                                "hl" to lang,
                                "platform" to "DESKTOP"
                            )
                        ),
                        "continuation" to tokenToUse
                    )
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedActions"] 
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response["continuationContents"] 
                            ?: response
                        processRecursive(actions, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(response)
                    }
                }
            }
        } catch (e: Exception) { 
            logError(e) 
        }

        saveContinuationToken(requestData, nextContinuation)
        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            return newSearchResponseList(emptyList(), false)
        }

        val results = mutableListOf<SearchResponse>()
        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }

        val seenIds = mutableSetOf<String>()
        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = appPrefs?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }

        try {
            if (page == 1) {
                saveContinuationToken("search_token", null)
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                savedApiKey = API_KEY_EXTRACT_REGEX.find(html)?.groupValues?.get(1)
                savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                    saveContinuationToken("search_token", savedContinuationToken)
                }
            } else {
                val tokenToUse = getSavedContinuationToken("search_token")
                if (!tokenToUse.isNullOrBlank()) {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val vData = savedVisitorData ?: appPrefs?.getString("yt_visitor_data", null) ?: ""
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$apiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "hl" to lang,
                                "gl" to if (lang == "ko") "KR" else "US",
                                "visitorData" to vData
                            )
                        ),
                        "continuation" to tokenToUse
                    )
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl) 
                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedActions"]
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                        saveContinuationToken("search_token", savedContinuationToken)
                    }
                }
            }
            return newSearchResponseList(results, !getSavedContinuationToken("search_token").isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }

    private val collectedShorts = mutableListOf<Episode>()

    private fun addShortToCache(title: String, url: String, poster: String?) {
        if (collectedShorts.none { it.data == url }) {
             collectedShorts.add(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                    this.episode = collectedShorts.size + 1
                }
             )
        }
    }

    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }

    private fun extractPlaylistEpisodes(data: Any?, seenIds: MutableSet<String>): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        fun recurse(node: Any?, depth: Int = 0) {
            if (node == null || depth > 32) return
            if (node is Map<*, *>) {
                val playlistVideo = node.getMapKey("playlistVideoRenderer")
                if (playlistVideo != null) {
                    val videoId = playlistVideo.getString("videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistVideo.getMapKey("title"))
                        val channel = getText(playlistVideo.getMapKey("shortBylineText"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = channel
                        })
                    }
                }
                
                val playlistPanelVideo = node.getMapKey("playlistPanelVideoRenderer")
                if (playlistPanelVideo != null) {
                    val videoId = playlistPanelVideo.getString("videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistPanelVideo.getMapKey("title"))
                        val channel = getText(playlistPanelVideo.getMapKey("shortBylineText"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistPanelVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = channel
                        })
                    }
                }
                
                for (value in node.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        recurse(value, depth + 1)
                    }
                }
            } else if (node is List<*>) {
                for (item in node) {
                    recurse(item, depth + 1)
                }
            }
        }
        
        recurse(data)
        return episodes
    }

    private class PlaylistMetadata {
        var title: String = ""
        var author: String = ""
        var videoAuthor: String = ""
        var description: String = ""
        var channelId: String = ""
        var channelAvatar: String = ""
        var subscriberCount: String = ""
    }

    private fun findPlaylistMetadataRecursive(obj: Any?, metadata: PlaylistMetadata, depth: Int = 0) {
        if (obj == null || depth > 32) return
        if (obj is Map<*, *>) {
            val keys = obj.keys.map { it.toString() }

            val primary = YoutubeUtils.getMapKeyFromAny(obj, "playlistSidebarPrimaryInfoRenderer")
            if (primary != null) {
                val t = getText(primary["title"] ?: YoutubeUtils.getMapKeyFromAny(primary, "title"))
                if (t.isNotBlank()) {
                    metadata.title = t
                }
                val d = getText(primary["description"] ?: YoutubeUtils.getMapKeyFromAny(primary, "description"))
                if (d.isNotBlank()) metadata.description = d
            }

            val secondary = YoutubeUtils.getMapKeyFromAny(obj, "playlistSidebarSecondaryInfoRenderer")
            if (secondary != null) {
                val owner = YoutubeUtils.getMapKeyFromAny(secondary, "videoOwner")?.let { YoutubeUtils.getMapKeyFromAny(it, "videoOwnerRenderer") }
                if (owner != null) {
                    val a = getText(owner["title"] ?: YoutubeUtils.getMapKeyFromAny(owner, "title"))
                    if (a.isNotBlank()) {
                        metadata.author = a
                    }
                }
            }

            val header = YoutubeUtils.getMapKeyFromAny(obj, "playlistHeaderRenderer")
            if (header != null) {
                val t = getText(header["title"] ?: YoutubeUtils.getMapKeyFromAny(header, "title"))
                if (t.isNotBlank()) metadata.title = t
                val ownerText = YoutubeUtils.getMapKeyFromAny(header, "ownerText")
                if (ownerText != null) {
                    val a = getText(ownerText)
                    if (a.isNotBlank()) metadata.author = a
                }
                val d = getText(header["description"] ?: YoutubeUtils.getMapKeyFromAny(header, "description"))
                if (d.isNotBlank()) metadata.description = d
            }

            val panel = YoutubeUtils.getMapKeyFromAny(obj, "playlistPanelRenderer")
            if (panel != null) {
                val t = getText(panel["titleText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "titleText"))
                    .ifBlank { getText(panel["title"] ?: YoutubeUtils.getMapKeyFromAny(panel, "title")) }
                if (t.isNotBlank()) {
                    metadata.title = t
                }
                
                val a = getText(panel["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "shortBylineText"))
                    .ifBlank { getText(panel["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "longBylineText")) }
                    .ifBlank { getText(panel["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(panel, "ownerName")) }
                if (a.isNotBlank()) {
                    metadata.author = a
                }
            }

            val metaRenderer = YoutubeUtils.getMapKeyFromAny(obj, "playlistMetadataRenderer")
            if (metaRenderer != null) {
                val t = YoutubeUtils.getStringFromAny(metaRenderer, "title") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "title"))
                if (!t.isNullOrBlank()) {
                    metadata.title = t
                }
                val d = YoutubeUtils.getStringFromAny(metaRenderer, "description") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "description"))
                if (!d.isNullOrBlank()) metadata.description = d
            }

            if (keys.contains("playlistId") && (keys.contains("titleText") || keys.contains("title"))) {
                val t = getText(obj["titleText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "titleText"))
                    .ifBlank { getText(obj["title"] ?: YoutubeUtils.getMapKeyFromAny(obj, "title")) }
                if (t.isNotBlank() && metadata.title.isBlank()) {
                    metadata.title = t
                }
                val a = getText(obj["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "shortBylineText"))
                    .ifBlank { getText(obj["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "longBylineText")) }
                    .ifBlank { getText(obj["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(obj, "ownerName")) }
                if (a.isNotBlank() && metadata.author.isBlank()) {
                    metadata.author = a
                }
            }

            val ownerRenderer = obj["videoOwnerRenderer"] as? Map<*, *>
                ?: YoutubeUtils.getMapKeyFromAny(obj, "videoOwnerRenderer")
            if (ownerRenderer != null) {
                val sub = getText(ownerRenderer["subscriberCountText"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "subscriberCountText"))
                if (sub.isNotBlank()) metadata.subscriberCount = sub
                
                val avatar = YoutubeUtils.getBestThumbnail(ownerRenderer["thumbnail"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "thumbnail"))
                if (!avatar.isNullOrBlank()) metadata.channelAvatar = avatar
                
                val browseId = YoutubeUtils.getMapKeyFromAny(ownerRenderer, "navigationEndpoint")
                    ?.let { YoutubeUtils.getMapKeyFromAny(it, "browseEndpoint") }
                    ?.let { YoutubeUtils.getStringFromAny(it, "browseId") }
                if (!browseId.isNullOrBlank()) metadata.channelId = browseId
                
                val vAuth = getText(ownerRenderer["title"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "title"))
                if (vAuth.isNotBlank()) {
                    metadata.videoAuthor = vAuth
                }
            }

            for (value in obj.values) {
                findPlaylistMetadataRecursive(value, metadata, depth + 1)
            }
        } else if (obj is List<*>) {
            for (item in obj) {
                findPlaylistMetadataRecursive(item, metadata, depth + 1)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/shorts/")) {
            val videoId = YoutubeUtils.extractYoutubeId(url) ?: "video"
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { YoutubeUtils.extractYoutubeId(it.data) == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = YoutubeUtils.buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: YoutubeUtils.buildThumbnailFromId(videoId)

            val shortTitle = currentEp?.name ?: "Shorts Video"
            val shortPlot = buildString {
                append(shortTitle)
                val desc = currentEp?.description
                if (!desc.isNullOrBlank()) {
                    append("\n\n$desc")
                }
            }

            return newTvSeriesLoadResponse(shortTitle, url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = shortPlot
                this.tags = listOf("Shorts")
            }
        }

        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                var cleanUrl = url.substringBefore("?").removeSuffix("/")
                val tabKeywords = listOf("/featured", "/shorts", "/streams", "/playlists", "/community", "/about", "/videos")
                for (keyword in tabKeywords) {
                    if (cleanUrl.endsWith(keyword)) {
                        cleanUrl = cleanUrl.removeSuffix(keyword)
                        break
                    }
                }
                val channelUrl = "$cleanUrl/videos"
                var response = app.get(channelUrl, interceptor = ytInterceptor)
                var html = response.text
                var data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val tabsCheck = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                val activeTab = tabsCheck?.firstOrNull { tab ->
                    safeGet(tab, "tabRenderer", "selected") == true
                }
                
                val endpointUrl = safeGet(activeTab, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") as? String

                if (endpointUrl != null && !endpointUrl.endsWith("/videos")) {
                    val canonicalUrl = response.document.selectFirst("link[rel=canonical]")?.attr("href")
                    if (!canonicalUrl.isNullOrBlank()) {
                        val cleanCanonical = canonicalUrl.substringBefore("?").removeSuffix("/")
                        if (!cleanCanonical.endsWith("/videos")) {
                            val targetUrl = "$cleanCanonical/videos"
                            response = app.get(targetUrl, interceptor = ytInterceptor)
                            html = response.text
                            val newData = extractYtInitialData(html)
                            if (newData != null) {
                                data = newData
                            }
                        }
                    }
                }

                val apiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                val visitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: safeGet(header, "content", "pageHeaderViewModel", "title", "dynamicTextViewModel", "text", "content") as? String
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = YoutubeUtils.getBestThumbnail(safeGet(header, "avatar"))
                    ?: YoutubeUtils.getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                var subscriberCount = getText(safeGet(header, "subscriberCountText"))
                if (subscriberCount.isBlank()) {
                    val rows = safeGet(header, "content", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                        ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                    
                    rows?.forEach { row ->
                        val parts = safeGet(row, "metadataParts") as? List<*>
                        parts?.forEach { part ->
                            val text = getText(safeGet(part, "text"))
                            if (SUBSCRIBER_REGEX.matches(text)) {
                                subscriberCount = text
                            }
                        }
                    }
                }
                subscriberCount = SUBSCRIBER_REPLACE_REGEX.replace(subscriberCount, "").trim()
                if (subscriberCount.isBlank()) subscriberCount = "N/A"

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?, depth: Int = 0): List<*>? {
                    if (obj == null || depth > 32) return null
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands", "onResponseReceivedResults", "continuationContents")
                            for (k in keysToTry) {
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v, depth + 1)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v, depth + 1)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i, depth + 1)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    
                    fun findAllRenderers(obj: Any?, targetKeys: List<String>, results: MutableList<Map<*,*>>, depth: Int = 0) {
                        if (obj == null || depth > 32) return
                        when (obj) {
                            is Map<*, *> -> {
                                for (key in targetKeys) {
                                    if (obj.containsKey(key)) {
                                        val match = obj[key] as? Map<*,*>
                                        if (match != null) results.add(match)
                                    }
                                }
                                for (value in obj.values) {
                                    findAllRenderers(value, targetKeys, results, depth + 1)
                                }
                            }
                            is List<*> -> {
                                for (item in obj) {
                                    findAllRenderers(item, targetKeys, results, depth + 1)
                                }
                            }
                        }
                    }

                    val targetRendererKeys = listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "shortsVideoRenderer", "reelItemRenderer", "shortsLockupViewModel", "videoViewModel", "lockupViewModel")

                    items.forEachIndexed { index, item ->
                        try {
                            val map = item as? Map<*, *>
                            if (map == null) {
                                return@forEachIndexed
                            }

                            if (map.containsKey("continuationItemRenderer")) {
                                return@forEachIndexed
                            }

                            val foundRenderers = mutableListOf<Map<*,*>>()
                            findAllRenderers(map, targetRendererKeys, foundRenderers)

                            if (foundRenderers.isEmpty()) {
                                return@forEachIndexed
                            }

                            for (renderer in foundRenderers) {
                                var vidId = renderer["videoId"] as? String
                                
                                if (vidId.isNullOrBlank() && renderer.containsKey("contentId")) {
                                    vidId = renderer["contentId"] as? String
                                }
                                if (vidId.isNullOrBlank()) {
                                    vidId = safeGet(renderer, "onTap", "innertubeCommand", "reelWatchEndpoint", "videoId") as? String
                                }

                                if (!vidId.isNullOrBlank()) {
                                    var vidTitle = getText(renderer["title"])
                                    if (vidTitle.isBlank()) vidTitle = getText(renderer["headline"])
                                    if (vidTitle.isBlank()) vidTitle = getText(renderer["shortBylineText"])
                                    if (vidTitle.isBlank()) vidTitle = getText(safeGet(renderer, "metadata", "lockupMetadataViewModel", "title"))
                                    if (vidTitle.isBlank()) vidTitle = "Video"

                                    val thumb = YoutubeUtils.getBestThumbnail(renderer["thumbnail"]) 
                                        ?: YoutubeUtils.getBestThumbnail(safeGet(renderer, "contentImage", "image", "sources")) 
                                        ?: YoutubeUtils.buildThumbnailFromId(vidId)

                                    val metaList = mutableListOf<String>()

                                    val legacyViews = getText(renderer["viewCountText"])
                                    if (legacyViews.isNotBlank()) metaList.add(formatViews(legacyViews))

                                    val legacyTime = getText(renderer["publishedTimeText"])
                                    if (legacyTime.isNotBlank()) metaList.add(legacyTime)

                                    if (metaList.isEmpty()) {
                                        val rows = safeGet(renderer, "metadata", "lockupMetadataViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                                        rows?.forEach { row ->
                                            val parts = safeGet(row, "metadataParts") as? List<*>
                                            parts?.forEach { part ->
                                                val text = getText(safeGet(part, "text"))
                                                if (text.isNotBlank()) {
                                                    if (METADATA_VIEW_REGEX.matches(text)) {
                                                        metaList.add(formatViews(text))
                                                    } else {
                                                        metaList.add(text)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    collectTo.add(newEpisode("$mainUrl/watch?v=$vidId") {
                                        this.name = vidTitle
                                        this.posterUrl = thumb
                                        this.description = metaList.joinToString(" • ")
                                    })
                                    
                                    break 
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    val actualActiveTab = tabs.firstOrNull { tab ->
                        safeGet(tab, "tabRenderer", "selected") == true
                    }

                    val activeContent = safeGet(actualActiveTab, "tabRenderer", "content") as? Map<*, *>

                    if (activeContent?.containsKey("richGridRenderer") == true) {
                        initialItems = safeGet(activeContent, "richGridRenderer", "contents") as? List<*>
                    } else if (activeContent?.containsKey("gridRenderer") == true) {
                        initialItems = safeGet(activeContent, "gridRenderer", "items") as? List<*>
                    }

                    if (initialItems == null) {
                        for (tab in tabs) {
                            val tabMap = tab as? Map<*, *>
                            val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                            val tabContent = tabRenderer?.get("content") as? Map<*, *>
                            if (tabContent?.containsKey("richGridRenderer") == true) {
                                initialItems = safeGet(tabContent, "richGridRenderer", "contents") as? List<*>
                                break
                            }
                            if (tabContent?.containsKey("gridRenderer") == true) {
                                initialItems = safeGet(tabContent, "gridRenderer", "items") as? List<*>
                                break
                            }
                        }
                    }
                }
                
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = findTokenRecursive(m)
                        if (!token.isNullOrBlank()) return token
                    }
                    return null
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPagesLimit = appPrefs?.getInt("channel_pages_limit", 36) ?: 36

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPagesLimit && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "hl" to lang,
                                    "gl" to if (lang == "ko") "KR" else "US",
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationTokenFromItems(continuationItems)
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {
                logError(e)
            }
        }

        if (url.contains("list=")) {
            try {
                var targetUrl = url
                if (url.contains("playlist?list=")) {
                    val listId = url.substringAfter("list=").substringBefore("&")
                    if (listId.startsWith("RD") || listId.startsWith("UL") || listId.startsWith("MM") || listId.startsWith("RDMM")) {
                        targetUrl = "$mainUrl/watch?list=$listId"
                    }
                }

                val response = app.get(targetUrl, interceptor = ytInterceptor)
                val html = response.text
                
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")

                val playlistMeta = PlaylistMetadata()
                findPlaylistMetadataRecursive(data, playlistMeta)

                var author = playlistMeta.videoAuthor.ifBlank { playlistMeta.author }.ifBlank { "Unknown Channel" }
                var title = playlistMeta.title.ifBlank { "YouTube Playlist" }
                var description = playlistMeta.description

                val seenPlaylistIds = mutableSetOf<String>()
                val episodes = extractPlaylistEpisodes(data, seenPlaylistIds).toMutableList()

                val isMix = url.contains("list=RD") || url.contains("list=MM") || url.contains("list=UL")
                if (isMix) {
                    val firstChannel = episodes.firstOrNull()?.description
                    if (!firstChannel.isNullOrBlank()) {
                        title = if (lang == "ko") {
                            "믹스 - $firstChannel"
                        } else {
                            "Mix - $firstChannel"
                        }
                    } else if (title.contains("YouTube Mix") || title.contains("믹스")) {
                        title = "YouTube Mix"
                    }
                }

                title = YoutubeUtils.cleanMixTitle(title)

                episodes.forEachIndexed { index, ep ->
                    ep.episode = index + 1
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val recList = mutableListOf<SearchResponse>()
                if (author.isNotBlank() && playlistMeta.channelId.isNotBlank()) {
                    val cId = playlistMeta.channelId
                    val channelUrlFull = if (cId.startsWith("UC") || cId.startsWith("@")) "$mainUrl/channel/$cId" else "$mainUrl/$cId"
                    val channelCard = newMovieSearchResponse(
                        "Channel: $author",
                        channelUrlFull,
                        TvType.Live
                    ) {
                        this.posterUrl = playlistMeta.channelAvatar
                    }
                    recList.add(channelCard)
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    
                    val finalDescription = buildString {
                        append("Channel: $author")
                        if (playlistMeta.subscriberCount.isNotBlank()) {
                            append(" (${playlistMeta.subscriberCount})")
                        }
                        if (description.isNotBlank()) {
                            append("\n\n$description")
                        }
                    }
                    this.plot = finalDescription
                    this.tags = listOf(author)
                    this.recommendations = recList
                }
            } catch (e: Exception) {
                logError(e)
            }
        }          

        val videoId = YoutubeUtils.extractYoutubeId(url) ?: throw ErrorLoadingException("Invalid YouTube URL")

        val headers = mapOf("Referer" to "https://www.youtube.com/")
        val response = app.get(url, headers = headers, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)
        
        var title = "YouTube Video"
        var plot = ""
        var poster = YoutubeUtils.buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {
            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = extractTitle(primary["dateText"] as? Map<*, *>)
                    if (!dateText.isNullOrBlank()) plot += "$dateText\n\n"
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {
                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = YoutubeUtils.getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {
                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {
            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }

        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Channel: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar
            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        val episodes = mutableListOf<Episode>()
        
        episodes.add(newEpisode(url) {
            this.name = title
            this.posterUrl = poster
            this.episode = 1
            this.description = channelName.ifBlank { "재생 중" }
        })

        var epCount = 2
        filteredRecs.forEach { rec ->
            if (rec.url.contains("watch?v=") || rec.url.contains("/shorts/")) {
                episodes.add(newEpisode(rec.url) {
                    this.name = rec.name
                    this.posterUrl = rec.posterUrl
                    this.episode = epCount++
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
            this.plot = plot

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = YoutubeUtils.extractYoutubeId(data) ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"

        var isLive = false
        var playerResponseText = ""
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to YoutubeUtils.DEFAULT_CLIENT_VERSION,
                        "hl" to lang,
                        "gl" to if (lang == "ko") "KR" else "US"
                    )
                ),
                "videoId" to videoId
            )
            val headers = mapOf("Referer" to "$mainUrl/watch?v=$videoId")
            playerResponseText = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).text
            if (playerResponseText.isNotBlank()) {
                val root = JSONObject(playerResponseText)
                val videoDetails = root.optJSONObject("videoDetails")
                if (videoDetails != null && videoDetails.optBoolean("isLive", false)) {
                    isLive = true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (isLive) {
            loadExtractor(fullUrl, subtitleCallback, callback)
        } else {
            if (playerResponseText.isNotBlank()) {
                try {
                    val playerResponse = parseJson<PlayerResponse>(playerResponseText)
                    triggerPlaybackTracking(playerResponse.playbackTracking, videoId)
                } catch (e: Exception) {
                }
            }

            var advancedLinksCount = 0
            com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback) {
                advancedLinksCount++
                callback(it)
            }

            if (advancedLinksCount == 0) {
                loadExtractor(fullUrl, subtitleCallback, callback)
            } else {
                loadLinksAdvanced(videoId, subtitleCallback) { }
            }
        }

        return true
    }    
    
    private suspend fun loadLinksAdvanced(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "3",
                        "clientVersion" to "19.29.35",
                        "hl" to lang,
                        "gl" to if (lang == "ko") "KR" else "US"
                    )
                ),
                "videoId" to videoId
            )
            val headers = mapOf("Referer" to "$mainUrl/watch?v=$videoId")
            app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).text
        } catch (e: Exception) {
        }
        return true
    }
}

2. Yextractor.kt 전체 코드

(기존 코드를 모두 지우고 아래 코드로 100% 덮어쓰세요.)
