package com.youtube

import android.annotation.SuppressLint
import android.content.Context
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
import org.json.JSONArray
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.io.Closeable
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.HttpURLConnection
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.time.Instant
import kotlin.concurrent.thread
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlin.coroutines.resume
import kotlinx.coroutines.sync.withLock
import java.nio.charset.StandardCharsets

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
            YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
        }

        fun clearCache(videoId: String) {
            println("[YT_DEBUG] [Telemetry] clearCache triggered for Video ID: $videoId")
            ytVideos.remove(videoId)
            ytVideosSubtitles.remove(videoId)
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
                """<SegmentBase indexRange="${audio.initRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
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

//region [NewPipe 순정 App 레벨 PoToken 발급 모듈 - Coroutines 완전 포팅 사양]

interface PoTokenGenerator : Closeable {
    suspend fun generatePoToken(identifier: String): String
    fun isExpired(): Boolean
}

class PoTokenWebView private constructor(
    context: Context
) : PoTokenGenerator {
    private val webView = WebView(context)
    private val poTokenContinuations = ConcurrentHashMap<String, kotlin.coroutines.Continuation<String>>()
    private lateinit var expirationInstant: Instant
    private var initContinuation: kotlin.coroutines.Continuation<Unit>? = null

    companion object {
        private const val TAG = "PoTokenWebView"
        private const val GOOGLE_API_KEY = "AIzaSyDyT5W0Jh49F30Pqqtyfdf7pDLFKLJoAnw"
        private const val REQUEST_KEY = "O43z0dpjhgX20SCx4KAo"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.3"
        private const val JS_INTERFACE = "PoTokenWebView"

        suspend fun newPoTokenGenerator(context: Context): PoTokenGenerator = withContext(Dispatchers.Main) {
            val potWv = PoTokenWebView(context)
            potWv.initialize()
            potWv
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initialize() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = USER_AGENT
        settings.blockNetworkLoads = true // 로컬 연산이므로 네트워크 로딩 방지

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    println("[YT_DEBUG] [PoTokenWebView] Broken WebView: $fmt")
                    initContinuation?.resumeWith(Result.failure(Exception("WebView Broken: $fmt")))
                }
                return super.onConsoleMessage(m)
            }
        }

        // [Asset 격리 해결]: HTML 파일을 에셋 대신 raw string으로 하드코딩 주입
        // [CORS 보안 해결]: 가상 전역 유연성 바인딩 문법 교정 완료
        val html = """
            <!DOCTYPE html>
            <html lang="en"><head><title></title><script>
            function loadBotGuard(challengeData) {
                var globalObj = typeof window !== 'undefined' ? window : (typeof globalThis !== 'undefined' ? globalThis : this);
                this.vm = (this && this[challengeData.globalName]) || globalObj[challengeData.globalName];
                this.program = challengeData.program;
                this.vmFunctions = {};
                this.syncSnapshotFunction = null;
                if (!this.vm) throw new Error('[BotGuardClient]: VM not found');
                if (!this.vm.a) throw new Error('[BotGuardClient]: Could not load program');
                const vmFunctionsCallback = function (asyncSnapshotFunction, shutdownFunction, passEventFunction, checkCameraFunction) {
                    this.vmFunctions = {
                        asyncSnapshotFunction: asyncSnapshotFunction,
                        shutdownFunction: shutdownFunction,
                        passEventFunction: passEventFunction,
                        checkCameraFunction: checkCameraFunction
                    };
                };
                this.syncSnapshotFunction = this.vm.a(this.program, vmFunctionsCallback, true, this.userInteractionElement, function () {}, [ [], [] ])[0];
                return new Promise(function (resolve, reject) {
                    var i = 0;
                    var refreshIntervalId = setInterval(function () {
                        if (!!this.vmFunctions.asyncSnapshotFunction) {
                            resolve(this);
                            clearInterval(refreshIntervalId);
                        }
                        if (i >= 10000) {
                            reject("asyncSnapshotFunction is null even after 10s");
                            clearInterval(refreshIntervalId);
                        }
                        i += 1;
                    }, 1);
                });
            }
            function snapshot(args) {
                return new Promise(function (resolve, reject) {
                    if (!this.vmFunctions.asyncSnapshotFunction) return reject(new Error('Async snapshot not found'));
                    this.vmFunctions.asyncSnapshotFunction(function (response) { resolve(response) }, [
                        args.contentBinding, args.signedTimestamp, args.webPoSignalOutput, args.skipPrivacyBuffer
                    ]);
                });
            }
            function runBotGuard(challengeData) {
                const js = challengeData.interpreterJavascript.privateDoNotAccessOrElseSafeScriptWrappedValue;
                if (js) { new Function(js)(); } else throw new Error('Could not load VM');
                const webPoSignalOutput = [];
                return loadBotGuard({
                    globalName: challengeData.globalName, globalObj: this, program: challengeData.program
                }).then(function (botguard) {
                    return botguard.snapshot({ webPoSignalOutput: webPoSignalOutput })
                }).then(function (botguardResponse) {
                    return { webPoSignalOutput: webPoSignalOutput, botguardResponse: botguardResponse }
                });
            }
            function obtainPoToken(webPoSignalOutput, integrityToken, identifier) {
                const getMinter = webPoSignalOutput[0];
                if (!getMinter) throw new Error('PMD:Undefined');
                const mintCallback = getMinter(integrityToken);
                if (!(mintCallback instanceof Function)) throw new Error('APF:Failed');
                const result = mintCallback(identifier);
                if (!result) throw new Error('YNJ:Undefined');
                if (!(result instanceof Uint8Array)) throw new Error('ODM:Invalid');
                return result;
            }
            
            // 페이지 가상 로드 완료 시 즉시 코틀린 API 연동 트리깅 가동
            PoTokenWebView.downloadAndRunBotguard();
            </script></head><body></body></html>
        """.trimIndent()

        suspendCancellableCoroutine<Unit> { cont ->
            initContinuation = cont
            webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        }
    }

    @JavascriptInterface
    fun downloadAndRunBotguard() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]"
                )
                val parsedChallengeData = parseChallengeData(responseBody)
                // [정합성 파괴 해결]: 복잡한 난독화 VM 자바스크립트가 웹뷰 전이 시 특수문자 더블 이스케이프로 깨지는 현상을 
                // 방지하기 위해 코틀린에서 무결성 Base64로 1차 봉인하여 웹뷰 내부에서 안전 복원 실행
                val base64Challenge = android.util.Base64.encodeToString(
                    parsedChallengeData.toByteArray(StandardCharsets.UTF_8), 
                    android.util.Base64.NO_WRAP
                )
                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript(
                        """try {
                            var data = JSON.parse(decodeURIComponent(escape(atob('$base64Challenge'))));
                            runBotGuard(data).then(function (result) {
                                this.webPoSignalOutput = result.webPoSignalOutput
                                $JS_INTERFACE.onRunBotguardResult(result.botguardResponse)
                            }, function (error) {
                                $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                            })
                        } catch (error) {
                            $JS_INTERFACE.onJsInitializationError(error + "\n" + error.stack)
                        }""".trimIndent(),
                        null
                    )
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    initContinuation?.resumeWith(Result.failure(e))
                }
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        initContinuation?.resumeWith(Result.failure(Exception("JS Init Error: $error")))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
                )
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("this.integrityToken = $integrityToken", null)
                    initContinuation?.resume(Unit)
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    initContinuation?.resumeWith(Result.failure(e))
                }
            }
        }
    }

    override suspend fun generatePoToken(identifier: String): String = withContext(Dispatchers.Main) {
        val u8Identifier = stringToU8(identifier)
        suspendCancellableCoroutine { cont ->
            poTokenContinuations[identifier] = cont
            webView.evaluateJavascript(
                """try {
                        identifier = "$identifier"
                        u8Identifier = $u8Identifier
                        poTokenU8 = obtainPoToken(webPoSignalOutput, integrityToken, u8Identifier)
                        poTokenU8String = ""
                        for (i = 0; i < poTokenU8.length; i++) {
                            if (i != 0) poTokenU8String += ","
                            poTokenU8String += poTokenU8[i]
                        }
                        $JS_INTERFACE.onObtainPoTokenResult(identifier, poTokenU8String)
                    } catch (error) {
                        $JS_INTERFACE.onObtainPoTokenError(identifier, error + "\n" + error.stack)
                    }""".trimIndent(),
                null
            )
        }
    }

    @JavascriptInterface
    fun onObtainPoTokenError(identifier: String, error: String) {
        poTokenContinuations.remove(identifier)?.resumeWith(Result.failure(Exception(error)))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        try {
            val poToken = u8ToBase64(poTokenU8)
            poTokenContinuations.remove(identifier)?.resume(poToken)
        } catch (t: Throwable) {
            poTokenContinuations.remove(identifier)?.resumeWith(Result.failure(t))
        }
    }

    override fun isExpired(): Boolean {
        return Instant.now().isAfter(expirationInstant)
    }

    override fun close() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.destroy()
        }
    }

    private fun makeBotguardServiceRequest(urlStr: String, data: String): String {
        val url = URL(urlStr)
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.doOutput = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Content-Type", "application/json+protobuf")
        conn.setRequestProperty("x-goog-api-key", GOOGLE_API_KEY)
        conn.setRequestProperty("x-user-agent", "grpc-web-javascript/0.1")

        conn.outputStream.use { os ->
            os.write(data.toByteArray(StandardCharsets.UTF_8))
        }
        val httpCode = conn.responseCode
        if (httpCode != 200) {
            throw Exception("HTTP Error from BotGuard Service: $httpCode")
        }
        return conn.inputStream.bufferedReader().use { it.readText() }
    }
}

fun parseChallengeData(rawChallengeData: String): String {
    val scrambled = JSONArray(rawChallengeData)
    val challengeData = if (scrambled.length() > 1 && scrambled.optString(1).isNotEmpty()) {
        val descrambled = descramble(scrambled.getString(1))
        JSONArray(descrambled)
    } else {
        scrambled.getJSONArray(0)
    }

    val messageId = challengeData.getString(0)
    val interpreterHash = challengeData.getString(3)
    val program = challengeData.getString(4)
    val globalName = challengeData.getString(5)
    val clientExperimentsStateBlob = challengeData.getString(7)

    val arr1 = challengeData.optJSONArray(1)
    var privateDoNotAccessOrElseSafeScriptWrappedValue: String? = null
    if (arr1 != null) {
        for (i in 0 until arr1.length()) {
            val item = arr1.optString(i)
            if (!item.isNullOrBlank()) {
                privateDoNotAccessOrElseSafeScriptWrappedValue = item
                break
            }
        }
    }

    val arr2 = challengeData.optJSONArray(2)
    var privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: String? = null
    if (arr2 != null) {
        for (i in 0 until arr2.length()) {
            val item = arr2.optString(i)
            if (!item.isNullOrBlank()) {
                privateDoNotAccessOrElseTrustedResourceUrlWrappedValue = item
                break
            }
        }
    }

    val out = JSONObject()
    out.put("messageId", messageId)
    val interpreterJs = JSONObject()
    interpreterJs.put("privateDoNotAccessOrElseSafeScriptWrappedValue", privateDoNotAccessOrElseSafeScriptWrappedValue)
    interpreterJs.put("privateDoNotAccessOrElseTrustedResourceUrlWrappedValue", privateDoNotAccessOrElseTrustedResourceUrlWrappedValue)
    out.put("interpreterJavascript", interpreterJs)
    out.put("interpreterHash", interpreterHash)
    out.put("program", program)
    out.put("globalName", globalName)
    out.put("clientExperimentsStateBlob", clientExperimentsStateBlob)
    
    return out.toString()
}

fun parseIntegrityTokenData(rawIntegrityTokenData: String): Pair<String, Long> {
    val integrityTokenData = JSONArray(rawIntegrityTokenData)
    return base64ToU8(integrityTokenData.getString(0)) to integrityTokenData.getLong(1)
}

fun stringToU8(identifier: String): String {
    return newUint8Array(identifier.toByteArray(StandardCharsets.UTF_8))
}

fun newUint8Array(contents: ByteArray): String {
    return "new Uint8Array([" + contents.joinToString(separator = ",") { (it.toInt() and 0xFF).toString() } + "])"
}

fun u8ToBase64(poToken: String): String {
    val bytes = poToken.split(",")
        .map { it.trim().toInt().toByte() }
        .toByteArray()
    return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP or android.util.Base64.URL_SAFE)
        .replace("=", "")
}

private fun descramble(scrambledChallenge: String): String {
    val decoded = android.util.Base64.decode(scrambledChallenge.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT)
    val descrambledBytes = decoded.map { (it + 97).toByte() }.toByteArray()
    return String(descrambledBytes, StandardCharsets.UTF_8)
}

private fun base64ToU8(base64: String): String {
    val decoded = android.util.Base64.decode(base64.replace('-', '+').replace('_', '/'), android.util.Base64.DEFAULT)
    return newUint8Array(decoded)
}

object PoTokenProviderImpl : PoTokenProvider {
    private val lock = Any()
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        try {
            return getWebClientPoToken(videoId, forceRecreate = false)
        } catch (e: Exception) {
            println("[YT_DEBUG] [PoTokenProviderImpl] Failed to obtain poToken: ${e.message}")
            return null
        }
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (generator, visitorData, streamingPot, hasBeenRecreated) = synchronized(lock) {
            val shouldRecreate = webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()
            if (shouldRecreate) {
                println("[YT_DEBUG] [PoTokenProviderImpl] Recreating PoTokenGenerator Sandbox...")
                val prefs = PreferenceManager.getDefaultSharedPreferences(AcraApplication.context!!)
                val savedVisitor = prefs.getString("yt_visitor_data", null) ?: "CgptdWpfa0NXZ2tfdyi28vS0Bg"
                
                webPoTokenVisitorData = savedVisitor
                webPoTokenGenerator?.close()
                
                webPoTokenGenerator = kotlinx.coroutines.runBlocking {
                    PoTokenWebView.newPoTokenGenerator(AcraApplication.context!!)
                }
                webPoTokenStreamingPot = kotlinx.coroutines.runBlocking {
                    webPoTokenGenerator!!.generatePoToken(webPoTokenVisitorData!!)
                }
            }
            
            Quadruple(webPoTokenGenerator!!, webPoTokenVisitorData!!, webPoTokenStreamingPot!!, shouldRecreate)
        }

        val playerPot = try {
            kotlinx.coroutines.runBlocking {
                generator.generatePoToken(videoId)
            }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                println("[YT_DEBUG] [PoTokenProviderImpl] Attestation failed, retrying forceRecreate...")
                return getWebClientPoToken(videoId, forceRecreate = true)
            }
        }

        println("[YT_DEBUG] [PoTokenProviderImpl] Attestation SUCCESS! PlayerPot: ${playerPot.take(15)}..., StreamingPot: ${streamingPot.take(15)}...")
        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = getWebClientPoToken(videoId)

    private data class Quadruple<T1, T2, T3, T4>(val first: T1, val second: T2, val third: T3, val fourth: T4)
}

//endregion
