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
            println("[YT_DEBUG] [Extractor-Companion] static init 블록 시작됨.")
            // [모바일 우회 활성화]: Android 및 iOS 클라이언트 수집을 활성화하여 기기 증명 연계를 복구합니다.
            YoutubeStreamExtractor.setFetchIosClient(true)
            YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
            
            GlobalScope.launch(Dispatchers.Main) {
                println("[YT_DEBUG] [Extractor-Companion] 비동기 웜스타트 코루틴이 메인 디스패처에서 가동되었습니다.")
                try {
                    AcraApplication.context?.let {
                        println("[YT_DEBUG] [Extractor-Companion] PoTokenProviderImpl.preInitialize() 비차단식 사전 호출 시도")
                        PoTokenProviderImpl.preInitialize(it)
                    } ?: println("[YT_DEBUG] [Extractor-Companion] 경고: AcraApplication.context가 null 상태입니다.")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [Extractor-Companion] 웜스타트 초기 기동 중 예외 감지: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

        fun clearCache(videoId: String) {
            println("[YT_DEBUG] [Companion-Cache] 캐시 무효화 요청 수신. 비디오 ID: $videoId")
            ytVideos.remove(videoId)
            ytVideosSubtitles.remove(videoId)
        }

        fun getProxyUrl(realUrl: String, userAgent: String): String {
            if (serverPort == 0) {
                println("[YT_DEBUG] [Proxy-Url] 경고: serverPort가 아직 0입니다. 프록시 바인딩을 건너뛰고 원본 주소를 반환합니다.")
                return realUrl
            }
            return try {
                val encodedUrl = java.net.URLEncoder.encode(realUrl, "UTF-8")
                val encodedUa = java.net.URLEncoder.encode(userAgent, "UTF-8")
                val proxyUrl = "http://127.0.0.1:$serverPort/proxy?url=$encodedUrl&ua=$encodedUa"
                println("[YT_DEBUG] [Proxy-Url] 프록시 우회 주소 생성 완료 (크기: ${proxyUrl.length} 자)")
                proxyUrl
            } catch (e: Exception) {
                println("[YT_DEBUG] [Proxy-Url] 인코딩 예외 발생: ${e.message}. 원본 주소를 우회 없이 주입합니다.")
                realUrl
            }
        }

        private fun handleProxyRequest(requestPath: String, clientHeaders: Map<String, String>, os: java.io.OutputStream) {
            println("[YT_DEBUG] [Proxy-Handler] 비디오 세그먼트 요청 가로채기 성공. 경로: $requestPath")
            var conn: HttpURLConnection? = null
            try {
                val uri = android.net.Uri.parse("http://127.0.0.1$requestPath")
                val rawUrl = uri.getQueryParameter("url")
                if (rawUrl == null) {
                    println("[YT_DEBUG] [Proxy-Handler] 오류: 프록시 주소에 'url' 필드가 유실되었습니다.")
                    return
                }
                val userAgent = uri.getQueryParameter("ua") ?: YoutubeUtils.CHROME_USER_AGENT

                val extraParams = java.lang.StringBuilder()
                uri.queryParameterNames.forEach { key ->
                    if (key != "url" && key != "ua") {
                        val values = uri.getQueryParameters(key)
                        values.forEach { value ->
                            val separator = if (extraParams.isEmpty() && !rawUrl.contains("?")) "?" else "&"
                            extraParams.append(separator).append(key).append("=").append(java.net.URLEncoder.encode(value, "UTF-8"))
                        }
                    }
                }
                val finalUrlStr = rawUrl + extraParams.toString()
                println("[YT_DEBUG] [Proxy-Handler] 최종 조립된 전송 목적지 CDN URL: $finalUrlStr")

                val url = URL(finalUrlStr)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Origin", "https://www.youtube.com")
                conn.setRequestProperty("Referer", "https://www.youtube.com")

                val rangeHeader = clientHeaders["range"]
                if (!rangeHeader.isNullOrBlank()) {
                    conn.setRequestProperty("Range", rangeHeader)
                }

                val responseCode = conn.responseCode
                val responseMessage = conn.responseMessage ?: "OK"

                if (responseCode >= 400) {
                    println("[YT_DEBUG] [Proxy-Handler] 오류: 유튜브 CDN이 에러 상태코드를 반환했습니다. 상태코드: $responseCode")
                    val errorResponse = "HTTP/1.1 $responseCode $responseMessage\r\nConnection: close\r\n\r\n"
                    os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    os.flush()
                    return
                }

                val headerBuilder = java.lang.StringBuilder()
                headerBuilder.append("HTTP/1.1 $responseCode $responseMessage\r\n")

                conn.headerFields.forEach { (key, values) ->
                    if (key != null) {
                        val lowerKey = key.lowercase()
                        if (lowerKey != "connection" && lowerKey != "transfer-encoding" && lowerKey != "keep-alive") {
                            headerBuilder.append("$key: ${values.joinToString(", ")}\r\n")
                        }
                    }
                }
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Connection: close\r\n")
                headerBuilder.append("\r\n")

                os.write(headerBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                os.flush()

                conn.inputStream.use { input ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                    }
                }
                os.flush()
            } catch (e: Exception) {
                println("[YT_DEBUG] [Proxy-Handler] 세그먼트 중계 중 예외 발생: ${e.message}")
                e.printStackTrace()
                try {
                    val errorResponse = "HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n"
                    os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    os.flush()
                } catch (_: Exception) {}
            } finally {
                conn?.disconnect()
            }
        }

        private fun handleClient(client: Socket) {
            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
                    val os = socket.getOutputStream()
                    val firstLine = reader.readLine()
                    if (firstLine != null && firstLine.startsWith("GET")) {
                        val parts = firstLine.split(" ")
                        if (parts.size > 1) {
                            val requestPath = parts[1]

                            val clientHeaders = mutableMapOf<String, String>()
                            var headerLine: String?
                            while (reader.readLine().also { headerLine = it } != null) {
                                val currentLine = headerLine ?: break
                                if (currentLine.isBlank()) break
                                val colonIndex = currentLine.indexOf(':')
                                if (colonIndex != -1) {
                                    val name = currentLine.substring(0, colonIndex).trim().lowercase()
                                    val value = currentLine.substring(colonIndex + 1).trim()
                                    clientHeaders[name] = value
                                }
                            }

                            if (requestPath.startsWith("/proxy")) {
                                handleProxyRequest(requestPath, clientHeaders, os)
                            } else {
                                val cleanPath = requestPath.substringAfter("/").substringBefore("?")
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
                }
            } catch (e: Exception) {
                println("[YT_DEBUG] [Loopback-Server] 소켓 예외 발생: ${e.message}")
                e.printStackTrace()
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
    ) = withContext(Dispatchers.IO) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        val extractorName = this@YoutubeExtractor.name
        val extractorMainUrl = this@YoutubeExtractor.mainUrl

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
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

                        val initR = if (vs.initStart != null && vs.initStart >= 0 && vs.initEnd != null && vs.initEnd >= 0) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexStart >= 0 && vs.indexEnd != null && vs.indexEnd >= 0) "${vs.indexStart}-${vs.indexEnd}" else null

                        StreamInfo(streamUrl, mime, height, label, initR, indexR)
                    } catch (e: Exception) { null }
                }.distinctBy { it.height }

                val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                    try {
                        val aUrl = asr.content ?: return@mapNotNull null
                        val bitrate = asr.bitrate ?: 128000
                        var mime = asr.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)

                        val initR = if (asr.initStart != null && asr.initStart >= 0 && asr.initEnd != null && asr.initEnd >= 0) "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexStart >= 0 && asr.indexEnd != null && asr.indexEnd >= 0) "${asr.indexStart}-${asr.indexEnd}" else null
            
                        var rawLang = asr.audioTrackId ?: "Default"
                        if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")
                        
                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { null }
                }.distinctBy { it.url }

                // [무결성 인증 우위 정렬]: pot 파라미터가 확보된 WEB 클라이언트 스트림을 최상위로 강제 정렬
                val sortedVideoOnlyList = videoOnlyList.sortedWith(
                    compareByDescending<StreamInfo> { it.url.contains("pot=") || it.url.contains("c=WEB") }
                )
                val sortedAudioInfoList = audioInfoList.sortedWith(
                    compareByDescending<AudioInfo> { it.url.contains("pot=") || it.url.contains("c=WEB") }
                )

                val audiosByLanguage = sortedAudioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 파싱 에러: ${e.message}")
                }

                startServerIfNeeded()

                for (video in sortedVideoOnlyList) {
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
                                    val sourceName = "$extractorName ($lang)"

                                    val streamUa = if (video.url.contains("&c=ANDROID")) {
                                        "com.google.android.youtube/19.29.1 (Linux; U; Android 11; en_US)"
                                    } else {
                                        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
                                    }

                                    builtLinks.add(
                                        newExtractorLink(
                                            sourceName,
                                            finalName,
                                            localLink,
                                            type = ExtractorLinkType.DASH
                                        ) {
                                            this.referer = extractorMainUrl
                                            this.quality = video.height
                                            this.headers = mapOf(
                                                "User-Agent" to streamUa,
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
                    val streamUa = if (mUrl.contains("&c=ANDROID")) {
                        "com.google.android.youtube/19.29.1 (Linux; U; Android 11; en_US)"
                    } else {
                        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
                    }

                    val proxiedUrl = getProxyUrl(mUrl, streamUa)

                    builtLinks.add(
                        newExtractorLink(extractorName, "$mLabel (Legacy)", proxiedUrl, type = INFER_TYPE) {
                            this.referer = extractorMainUrl
                            this.quality = mHeight
                            this.headers = mapOf(
                                "User-Agent" to streamUa,
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
                        this.referer = extractorMainUrl
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
                        this.referer = extractorMainUrl
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
                
                thread(name = "YT-DASH-Server-Acceptor") {
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
                        println("[YT_DEBUG] [ServerSocket-Thread] 수락 대기 동작 루프 중단 예외: ${e.message}")
                    } finally {
                        synchronized(serverLock) {
                            try {
                                activeServer?.close()
                            } catch (_: Exception) {}
                            activeServer = null
                            serverPort = 0
                        }
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

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val videoUa = if (video.url.contains("&c=ANDROID")) {
            "com.google.android.youtube/19.29.1 (Linux; U; Android 11; en_US)"
        } else {
            "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
        }
        val proxiedVideoUrl = escapeXml(getProxyUrl(video.url, videoUa))
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
                <BaseURL>$proxiedVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val audioUa = if (audio.url.contains("&c=ANDROID")) {
                "com.google.android.youtube/19.29.1 (Linux; U; Android 11; en_US)"
            } else {
                "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X; en_US)"
            }
            val proxiedAudioUrl = escapeXml(getProxyUrl(audio.url, audioUa))
            val audioId = "audio_$index"
            val aMime = audio.mimeType
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$proxiedAudioUrl</BaseURL>
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
        settings.blockNetworkLoads = true 

        webView.addJavascriptInterface(this, JS_INTERFACE)

        webView.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                if (m.message().contains("Uncaught")) {
                    val fmt = "\"${m.message()}\", source: ${m.sourceId()} (${m.lineNumber()})"
                    println("[YT_DEBUG] [PoTokenWebView] 웹뷰 자바스크립트 uncaught 예외 감지: $fmt")
                    initContinuation?.resumeWith(Result.failure(Exception("WebView Broken: $fmt")))
                }
                return super.onConsoleMessage(m)
            }
        }

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
                if (js) { 
                    if (!document.getElementById(challengeData.interpreterHash)) {
                        const script = document.createElement('script');
                        script.type = 'text/javascript';
                        script.id = challengeData.interpreterHash;
                        script.textContent = js;
                        document.head.appendChild(script);
                    }
                } else throw new Error('Could not load VM');
                
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
            PoTokenWebView.downloadAndRunBotguard();
            </script></head><body></body></html>
        """.trimIndent()

        suspendCancellableCoroutine<Unit> { cont ->
            initContinuation = cont
            webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]"
                )
                
                val parsedChallengeData = parseChallengeData(responseBody)
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
                e.printStackTrace()
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
                e.printStackTrace()
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
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
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
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            throw Exception("HTTP Error from BotGuard Service: $httpCode, details: $errText")
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
            val item = arr1.opt(i)
            if (item is String) {
                privateDoNotAccessOrElseSafeScriptWrappedValue = item
                break
            }
        }
    }

    val arr2 = challengeData.optJSONArray(2)
    var privateDoNotAccessOrElseTrustedResourceUrlWrappedValue: String? = null
    if (arr2 != null) {
        for (i in 0 until arr2.length()) {
            val item = arr2.opt(i)
            if (item is String) {
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
    val decoded = base64ToByteArray(scrambledChallenge)
    val descrambledBytes = decoded.map { (it + 97).toByte() }.toByteArray()
    return String(descrambledBytes, StandardCharsets.UTF_8)
}

private fun base64ToU8(base64: String): String {
    val decoded = base64ToByteArray(base64)
    return newUint8Array(decoded)
}

private fun base64ToByteArray(base64: String): ByteArray {
    val base64Mod = base64
        .replace('-', '+')
        .replace('_', '/')
        .replace('.', '=')
    return android.util.Base64.decode(base64Mod, android.util.Base64.DEFAULT)
}

// [NewPipe 사양의 PoTokenProvider 수집 연동 모듈]
object PoTokenProviderImpl : PoTokenProvider {
    private val lock = Any()
    private var webPoTokenVisitorData: String? = null
    private var webPoTokenStreamingPot: String? = null
    private var webPoTokenGenerator: PoTokenGenerator? = null

    fun preInitialize(context: Context) {
        synchronized(lock) {
            if (webPoTokenGenerator != null && !webPoTokenGenerator!!.isExpired()) {
                return
            }
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val savedVisitor = prefs.getString("yt_visitor_data", null) ?: "CgptdWpfa0NXZ2tfdyi28vS0Bg"
            webPoTokenVisitorData = savedVisitor

            GlobalScope.launch(Dispatchers.Main) {
                try {
                    val generator = PoTokenWebView.newPoTokenGenerator(context)
                    val streamingPot = generator.generatePoToken(savedVisitor)
                    synchronized(lock) {
                        webPoTokenGenerator = generator
                        webPoTokenStreamingPot = streamingPot
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        try {
            return getWebClientPoToken(videoId, forceRecreate = false)
        } catch (e: Exception) {
            return null
        }
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        val (generator, visitorData, streamingPot, hasBeenRecreated) = synchronized(lock) {
            val shouldRecreate = webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()
            if (shouldRecreate) {
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
                return getWebClientPoToken(videoId, forceRecreate = true)
            }
        }

        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null

    // [모바일 우회 증명 수립]: 안드로이드 및 iOS 클라이언트 기기 증명 누락으로 고화질 트랙이 숨겨지는(Hide) 현상을 원천 방지
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? {
        println("[YT_DEBUG] [PoTokenProviderImpl] getAndroidClientPoToken() 가 $videoId 에 대해 호출되었습니다. WEB 토큰을 공유 매핑합니다.")
        return getWebClientPoToken(videoId)
    }

    override fun getIosClientPoToken(videoId: String): PoTokenResult? {
        println("[YT_DEBUG] [PoTokenProviderImpl] getIosClientPoToken() 가 $videoId 에 대해 호출되었습니다. WEB 토큰을 공유 매핑합니다.")
        return getWebClientPoToken(videoId)
    }

    private data class Quadruple<T1, T2, T3, T4>(val first: T1, val second: T2, val third: T3, val fourth: T4)
}

//endregion
