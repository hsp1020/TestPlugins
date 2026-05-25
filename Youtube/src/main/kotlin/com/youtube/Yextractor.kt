package com.youtube

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
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
// [컴파일 해결]: Mutex 확장 함수 'withLock' 임포트 추가
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

        private const val EMBED_URL = "https://www.youtube.com/embed/aqz-KE-bpKQ?autoplay=1&mute=1"

        init {
            YoutubeStreamExtractor.setFetchIosClient(true)

            YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {
                override fun getWebClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getWebClientPoToken] 웹 클라이언트 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getWebEmbedClientPoToken] 임베드 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getAndroidClientPoToken] 안드로이드 클라이언트 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getIosClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getIosClientPoToken] iOS 클라이언트 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }
            })
        }

        fun clearCache(videoId: String) {
            ytVideos.remove(videoId)
            ytVideosSubtitles.remove(videoId)
        }

        private fun getPoTokenResultForVideo(videoId: String?): PoTokenResult? {
            val cached = getCachedTokens()
            if (cached != null) {
                println("[YT_DEBUG] [setPoTokenProvider] 캐시된 poToken 적용. 비디오: [$videoId]")
                return PoTokenResult(cached.first, "", "")
            }

            println("[YT_DEBUG] [setPoTokenProvider] 캐시 무효화. WebView 가상 자가발전(Locking) 진입.")
            // [컴파일 해결]: 명시적 타입 지정을 적용하여 추론 실패 및 스마트캐스트 컴파일 에러 해결
            val generated: Triple<String, String, String?>? = kotlinx.coroutines.runBlocking {
                tokenMutex.withLock {
                    val doubleCheck = getCachedTokens()
                    if (doubleCheck != null) {
                        doubleCheck
                    } else {
                        getPoTokenAndVisitor()
                    }
                }
            }

            return if (generated != null) {
                println("[YT_DEBUG] [setPoTokenProvider] 새로 취득한 poToken이 비디오 [$videoId]에 최종 정상 바인딩됩니다. 토큰 값: ${generated.first.take(15)}...")
                PoTokenResult(generated.first, "", "")
            } else {
                println("[YT_DEBUG] [setPoTokenProvider] poToken 획득 실패. 일반 우회 모드 시도.")
                null
            }
        }

        private fun getCachedTokens(): Triple<String, String, String?>? {
            val context = AcraApplication.context ?: return null
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val poToken = prefs.getString("yt_cached_potoken", null)
            val visitorData = prefs.getString("yt_cached_visitor_data", null)
            val cookieStr = prefs.getString("yt_cached_cookie", null)
            val timestamp = prefs.getLong("yt_potoken_timestamp", 0L)

            if (poToken.isNullOrBlank() || visitorData.isNullOrBlank()) {
                return null
            }

            val elapsed = System.currentTimeMillis() - timestamp
            if (elapsed > 3 * 3600 * 1000) { 
                println("[YT_DEBUG] [getCachedTokens] 캐시 토큰 유효 시간 만료.")
                return null
            }

            return Triple(poToken, visitorData, cookieStr)
        }

        private fun saveCachedTokens(poToken: String, visitorData: String, cookieStr: String?) {
            val context = AcraApplication.context ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val now = System.currentTimeMillis()
            
            val editor = prefs.edit()
                .putString("yt_cached_potoken", poToken)
                .putString("yt_cached_visitor_data", visitorData)
                .putLong("yt_potoken_timestamp", now)
                
            if (!cookieStr.isNullOrBlank()) {
                editor.putString("yt_cached_cookie", cookieStr)
                
                val visitorInfoLive = extractCookieValue(cookieStr, "VISITOR_INFO1_LIVE")
                if (!visitorInfoLive.isNullOrBlank()) {
                    editor.putString("VISITOR_INFO1_LIVE", visitorInfoLive)
                    println("[YT_DEBUG] [saveCachedTokens] VISITOR_INFO1_LIVE 동기화 성공: $visitorInfoLive")
                }
                
                val ysc = extractCookieValue(cookieStr, "YSC")
                if (!ysc.isNullOrBlank()) {
                    editor.putString("YSC", ysc)
                    println("[YT_DEBUG] [saveCachedTokens] YSC 세션 동기화 성공: $ysc")
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

        private suspend fun getPoTokenAndVisitor(): Triple<String, String, String?>? = withContext(Dispatchers.Main) {
            val context = AcraApplication.context ?: return@withContext null
            println("[YT_DEBUG] [getPoTokenAndVisitor] Headless WebView 로드 준비. 대상 URL: $EMBED_URL")

            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)
                var isCompleted = false
                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                var pollRunnable: Runnable? = null

                val safeResume = { res: Triple<String, String, String?>? ->
                    if (!isCompleted) {
                        isCompleted = true
                        pollRunnable?.let { handler.removeCallbacks(it) }
                        if (continuation.isActive) {
                            continuation.resume(res)
                        }
                        webView.destroy()
                    }
                }

                webView.apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    settings.databaseEnabled = true
                    settings.mediaPlaybackRequiresUserGesture = false 
                    settings.userAgentString = YoutubeUtils.CHROME_USER_AGENT

                    pollRunnable = object : Runnable {
                        override fun run() {
                            if (isCompleted) return

                            evaluateJavascript(
                                "(function() { " +
                                "  try { " +
                                "    var poToken = null; " +
                                "    var visitorData = null; " +
                                "    var playerResponse = window.ytInitialPlayerResponse; " +
                                "    if (playerResponse) { " +
                                "      poToken = playerResponse.serviceIntegrityDimensions?.poToken || playerResponse.streamingData?.poToken; " +
                                "      visitorData = playerResponse.responseContext?.visitorData; " +
                                "    } " +
                                "    if (!poToken || !visitorData) { " +
                                "      var args = window.ytplayer?.config?.args; " +
                                "      if (args) { " +
                                "        var rawResponse = args.raw_player_response ? (typeof args.raw_player_response === 'string' ? JSON.parse(args.raw_player_response) : args.raw_player_response) : (args.player_response ? JSON.parse(args.player_response) : null); " +
                                "        if (rawResponse) { " +
                                "          poToken = poToken || rawResponse.serviceIntegrityDimensions?.poToken || rawResponse.streamingData?.poToken; " +
                                "          visitorData = visitorData || rawResponse.responseContext?.visitorData; " +
                                "        } " +
                                "        visitorData = visitorData || args.visitor_data; " +
                                "      } " +
                                "    } " +
                                "    if (!poToken || !visitorData) { " +
                                "      var bootstrapResponse = window.ytplayer?.bootstrapPlayerResponse; " +
                                "      if (bootstrapResponse) { " +
                                "        poToken = poToken || bootstrapResponse.serviceIntegrityDimensions?.poToken || bootstrapResponse.streamingData?.poToken; " +
                                "        visitorData = visitorData || bootstrapResponse.responseContext?.visitorData; " +
                                "      } " +
                                "    } " +
                                "    if (!poToken || !visitorData) { " +
                                "      if (window.ytcfg && typeof window.ytcfg.get === 'function') { " +
                                "        var ytcfgPlayerResponse = window.ytcfg.get('PLAYER_VARS')?.embedded_player_response || window.ytcfg.get('PLAYER_RESPONSE'); " +
                                "        if (ytcfgPlayerResponse) { " +
                                "          var parsedYtcfg = typeof ytcfgPlayerResponse === 'string' ? JSON.parse(ytcfgPlayerResponse) : ytcfgPlayerResponse; " +
                                "          poToken = poToken || parsedYtcfg.serviceIntegrityDimensions?.poToken || parsedYtcfg.streamingData?.poToken; " +
                                "          visitorData = visitorData || parsedYtcfg.responseContext?.visitorData; " +
                                "        } " +
                                "        visitorData = visitorData || window.ytcfg.get('VISITOR_DATA'); " +
                                "      } " +
                                "    } " +
                                "    if (!poToken) { " +
                                "      var scripts = document.getElementsByTagName('script'); " +
                                "      for (var i = 0; i < scripts.length; i++) { " +
                                "        var text = scripts[i].textContent || scripts[i].innerText || ''; " +
                                "        if (text.indexOf('poToken') !== -1) { " +
                                "          var m = text.match(/\"poToken\"\\s*:\\s*\"([^\"]+)\"/); " +
                                "          if (m) poToken = m[1]; " +
                                "          var v = text.match(/\"visitorData\"\\s*:\\s*\"([^\"]+)\"/); " +
                                "          if (v) visitorData = visitorData || v[1]; " +
                                "        } " +
                                "      } " +
                                "    } " +
                                "    if (poToken && visitorData) { " +
                                "      return JSON.stringify({poToken: poToken, visitorData: visitorData}); " +
                                "    } " +
                                "    return JSON.stringify({ " +
                                "      error: '대기 중... ' + " +
                                "             'ytInitialPlayerResponse: ' + (!!window.ytInitialPlayerResponse) + ', ' + " +
                                "             'ytplayer.config: ' + (!!window.ytplayer?.config) + ', ' + " +
                                "             'ytcfg: ' + (!!window.ytcfg) " +
                                "    }); " +
                                "  } catch(e) { " +
                                "    return JSON.stringify({error: 'JS 에러: ' + e.message}); " +
                                "  } " +
                                "})();"
                            ) { jsonResult ->
                                if (isCompleted) return@evaluateJavascript

                                if (jsonResult != null && jsonResult != "null" && jsonResult.isNotBlank()) {
                                    try {
                                        val cleanedJson = jsonResult.replace("\\\"", "\"").removePrefix("\"").removeSuffix("\"")
                                        val json = JSONObject(cleanedJson)

                                        if (!json.has("error")) {
                                            val poToken = json.optString("poToken")
                                            val visitorData = json.optString("visitorData")

                                            if (poToken.isNotBlank() && visitorData.isNotBlank()) {
                                                println("[YT_DEBUG] [getPoTokenAndVisitor] 가상 자가발전 성공!")
                                                val cookieManager = android.webkit.CookieManager.getInstance()
                                                val cookieStr = cookieManager.getCookie(EMBED_URL)
                                                saveCachedTokens(poToken, visitorData, cookieStr)
                                                safeResume(Triple(poToken, visitorData, cookieStr))
                                                return@evaluateJavascript
                                            }
                                        }
                                    } catch (e: Exception) {
                                        println("[YT_DEBUG] [getPoTokenAndVisitor] 결과 파싱 에러: ${e.message}")
                                    }
                                }
                                handler.postDelayed(this, 300)
                            }
                        }
                    }

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            println("[YT_DEBUG] [getPoTokenAndVisitor] 웹뷰 로딩 완료. 강제 재생 주입 수행.")
                            
                            evaluateJavascript(
                                "(function() { " +
                                "  var v = document.querySelector('video'); " +
                                "  if (v) { v.play(); } " +
                                "  var btn = document.querySelector('.ytp-play-button'); " +
                                "  if (btn) { btn.click(); } " +
                                "})();", null
                            )
                            pollRunnable?.let { handler.post(it) }
                        }
                    }

                    handler.postDelayed({
                        if (!isCompleted) {
                            println("[YT_DEBUG] [getPoTokenAndVisitor] 타임아웃(12초) 도달. 우회 재생 지시.")
                            safeResume(null)
                        }
                    }, 12000)
                }

                webView.loadUrl(EMBED_URL)

                continuation.invokeOnCancellation {
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        println("[YT_DEBUG] [getPoTokenAndVisitor] 태스크 중단 수신. 자원 즉시 해제.")
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
