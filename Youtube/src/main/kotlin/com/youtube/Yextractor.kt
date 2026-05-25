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
            YoutubeStreamExtractor.setFetchIosClient(true)
            YoutubeStreamExtractor.setPoTokenProvider(PoTokenProviderImpl)
            
            // [웜스타트 감지 로그]: 앱 부팅 및 세션 유입 단계에서 백그라운드 웜업 상태 파악
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
            println("[YT_DEBUG] [Proxy-Handler] 플레이어(ExoPlayer) 전달 원본 헤더 목록: $clientHeaders")
            var conn: HttpURLConnection? = null
            try {
                val uri = android.net.Uri.parse("http://127.0.0.1$requestPath")
                val rawUrl = uri.getQueryParameter("url")
                if (rawUrl == null) {
                    println("[YT_DEBUG] [Proxy-Handler] 오류: 프록시 주소에 'url' 필드가 유실되었습니다.")
                    return
                }
                val userAgent = uri.getQueryParameter("ua") ?: YoutubeUtils.CHROME_USER_AGENT
                println("[YT_DEBUG] [Proxy-Handler] 해석된 대상 목적지 주소: ${rawUrl.take(120)}...")
                println("[YT_DEBUG] [Proxy-Handler] 매칭된 정합성 User-Agent: '$userAgent'")

                // 플레이어가 덧붙인 추가 쿼리 파라미터가 있다면 재결합 처리
                var finalUrlStr = rawUrl
                uri.queryParameterNames.forEach { key ->
                    if (key != "url" && key != "ua") {
                        val values = uri.getQueryParameters(key)
                        values.forEach { value ->
                            val separator = if (finalUrlStr.contains("?")) "&" else "?"
                            finalUrlStr += "$separator$key=${java.net.URLEncoder.encode(value, "UTF-8")}"
                        }
                    }
                }
                println("[YT_DEBUG] [Proxy-Handler] 최종 조립된 전송 목적지 CDN URL: $finalUrlStr")

                val url = URL(finalUrlStr)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Origin", "https://www.youtube.com")
                conn.setRequestProperty("Referer", "https://www.youtube.com")

                // 탐색(Seek) 요청 처리를 위해 플레이어 측 Range 헤더 동기화
                val rangeHeader = clientHeaders["range"]
                if (!rangeHeader.isNullOrBlank()) {
                    conn.setRequestProperty("Range", rangeHeader)
                    println("[YT_DEBUG] [Proxy-Handler] Range 헤더 동기화 완료: $rangeHeader")
                }

                println("[YT_DEBUG] [Proxy-Handler] 유튜브 CDN 서버에 아웃바운드 소켓 접속을 수립합니다.")
                val responseCode = conn.responseCode
                val responseMessage = conn.responseMessage ?: "OK"
                println("[YT_DEBUG] [Proxy-Handler] 유튜브 CDN 응답 수신 완료 -> 코드: $responseCode, 메시지: $responseMessage")

                if (responseCode >= 400) {
                    println("[YT_DEBUG] [Proxy-Handler] 오류: 유튜브 CDN이 에러 상태코드를 반환했습니다. 스트림 차단 가능성 존재.")
                    val errorResponse = "HTTP/1.1 $responseCode $responseMessage\r\nConnection: close\r\n\r\n"
                    os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                    os.flush()
                    return
                }

                // 프록시 응답 헤더 재조립 및 동기화
                val headerBuilder = java.lang.StringBuilder()
                headerBuilder.append("HTTP/1.1 $responseCode $responseMessage\r\n")

                val contentType = conn.contentType
                if (contentType != null) {
                    headerBuilder.append("Content-Type: $contentType\r\n")
                }
                val contentLength = conn.contentLengthLong
                if (contentLength > 0) {
                    headerBuilder.append("Content-Length: $contentLength\r\n")
                }
                val acceptRanges = conn.getHeaderField("Accept-Ranges")
                if (acceptRanges != null) {
                    headerBuilder.append("Accept-Ranges: $acceptRanges\r\n")
                }
                val contentRange = conn.getHeaderField("Content-Range")
                if (contentRange != null) {
                    headerBuilder.append("Content-Range: $contentRange\r\n")
                }
                headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                headerBuilder.append("Connection: close\r\n")
                headerBuilder.append("\r\n")

                os.write(headerBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                os.flush()

                println("[YT_DEBUG] [Proxy-Handler] 플레이어 측 소켓으로 데이터 파이프라인 전송을 개시합니다.")
                var totalBytesTransferred = 0L
                conn.inputStream.use { input ->
                    val buffer = ByteArray(32768)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        os.write(buffer, 0, bytesRead)
                        totalBytesTransferred += bytesRead
                    }
                }
                os.flush()
                println("[YT_DEBUG] [Proxy-Handler] 데이터 전송 완료. 총 전송 바이트: $totalBytesTransferred bytes")
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
                println("[YT_DEBUG] [Proxy-Handler] 리소스가 폐쇄되었습니다.")
            }
        }

        private fun handleClient(client: Socket) {
            println("[YT_DEBUG] [Loopback-Server] 새로운 클라이언트 세션 소켓이 진입했습니다. 주소: ${client.remoteSocketAddress}")
            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
                    val os = socket.getOutputStream()
                    val firstLine = reader.readLine()
                    println("[YT_DEBUG] [Loopback-Server] HTTP 요청 라인: '$firstLine'")
                    if (firstLine != null && firstLine.startsWith("GET")) {
                        val parts = firstLine.split(" ")
                        if (parts.size > 1) {
                            val requestPath = parts[1]
                            println("[YT_DEBUG] [Loopback-Server] 분석 대상 경로: $requestPath")

                            // HTTP 요청 헤더 매핑
                            val clientHeaders = mutableMapOf<String, String>()
                            var headerLine: String?
                            while (reader.readLine().also { headerLine = it } != null) {
                                val currentLine = headerLine ?: break
                                if (currentLine.isBlank()) {
                                    println("[YT_DEBUG] [Loopback-Server] HTTP 헤더 수집 완료.")
                                    break
                                }
                                val colonIndex = currentLine.indexOf(':')
                                if (colonIndex != -1) {
                                    val name = currentLine.substring(0, colonIndex).trim().lowercase()
                                    val value = currentLine.substring(colonIndex + 1).trim()
                                    clientHeaders[name] = value
                                }
                            }

                            if (requestPath.startsWith("/proxy")) {
                                println("[YT_DEBUG] [Loopback-Server] '/proxy' 매핑을 감지하여 중계 핸들러로 라우팅합니다.")
                                handleProxyRequest(requestPath, clientHeaders, os)
                            } else {
                                println("[YT_DEBUG] [Loopback-Server] '/proxy' 외의 일반 요청을 수신했습니다. 매니페스트(MPD) 파싱을 진행합니다.")
                                val cleanPath = requestPath.substringAfter("/").substringBefore("?")
                                val path = if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
                                    cleanPath.substring(0, cleanPath.length - 4)
                                } else {
                                    cleanPath
                                }
                                println("[YT_DEBUG] [Loopback-Server] 캐시 맵에서 키를 조회합니다: '$path'")
                                val content = manifestMap[path]
                                if (content != null) {
                                    println("[YT_DEBUG] [Loopback-Server] 매칭되는 MPD 소스가 발견되었습니다. 크기: ${content.length} 자")
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
                                    println("[YT_DEBUG] [Loopback-Server] MPD 응답 스트림이 안전하게 전송되었습니다.")
                                } else {
                                    println("[YT_DEBUG] [Loopback-Server] 오류: 매니페스트 캐시가 존재하지 않습니다. UUID: '$path' (404 반환)")
                                    val errorResponse = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                                    os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                                    os.flush()
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[YT_DEBUG] [Loopback-Server] 소켓 세션 처리 중 예외: ${e.message}")
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

    // [데드락 완화 처리]: getUrl의 모든 처리를 Dispatchers.IO 영역으로 격리하여 메인 UI 스레드 정지 현상을 예방
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) = withContext(Dispatchers.IO) {
        val currentThreadName = Thread.currentThread().name
        println("[YT_DEBUG] [getUrl] 진입 완료. 비동기 백그라운드 스레드명: $currentThreadName")
        println("[YT_DEBUG] [getUrl] 호출인자 - url: '$url'")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl
        println("[YT_DEBUG] [getUrl] 식별된 비디오 ID: $videoId")

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            println("[YT_DEBUG] [getUrl] 로컬 비디오 캐시가 없으므로 네트워크 파싱을 전개합니다.")
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                
                println("[YT_DEBUG] [getUrl] s.fetchPage() 네트워크 분석기 동작을 호출합니다.")
                s.fetchPage()
                println("[YT_DEBUG] [getUrl] s.fetchPage() 파싱 동작 성공완료.")

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
                println("[YT_DEBUG] [getUrl] 비디오 단독 트랙 파싱 성공 수량: ${videoOnlyList.size}")

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
                println("[YT_DEBUG] [getUrl] 오디오 단독 트랙 파싱 성공 수량: ${audioInfoList.size}")

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                    println("[YT_DEBUG] [getUrl] 연동 자막 트랙 개수: ${subs.size}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 파싱 예외: ${e.message}")
                }

                startServerIfNeeded()

                println("[YT_DEBUG] [getUrl] DASH 가상 매니페스트(.mpd) 컴파일 및 캐시 등록 처리를 시작합니다.")
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
                                            this.referer = mainUrl
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
                        newExtractorLink(this.name, "$mLabel (Legacy)", proxiedUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
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
                println("[YT_DEBUG] [getUrl] 비디오 파싱 처리 완료. 수집 링크 수량: ${builtLinks.size} 개")

            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] 비디오 트랙 수집 중 예외 터짐: ${e.message}")
                e.printStackTrace()
                logError(e) 
            }
        } else {
            println("[YT_DEBUG] [getUrl] 캐시된 비디오 세션이 존재하여 신속하게 캐시로부터 리턴합니다.")
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
                println("[YT_DEBUG] [startServerIfNeeded] 로컬 소켓 서버가 이미 포트 $serverPort 에 바인딩되어 동작 중입니다.")
                return
            }
            try {
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_DEBUG] [startServerIfNeeded] 포트 $serverPort 에 리스너 소켓 바인딩을 성공했습니다.")
                
                thread(name = "YT-DASH-Server-Acceptor") {
                    println("[YT_DEBUG] [ServerSocket-Thread] 수락(accept) 대기 가상 루프 가동.")
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
                        println("[YT_DEBUG] [ServerSocket-Thread] 소켓 수락 루프가 완전히 종료되었습니다.")
                    }
                }
            } catch (e: Exception) { 
                println("[YT_DEBUG] [startServerIfNeeded] 소켓 리스너 빌드 도중 치명적 예외: ${e.message}")
                logError(e) 
            }
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        println("[YT_DEBUG] [Manifest-Register] 가상 MPD 생성 완료. ID: '$id', XML 크기: ${xmlContent.length} 자")
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
                """<SegmentBase indexRange="${audio.initRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
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
            println("[YT_DEBUG] [PoTokenWebView] newPoTokenGenerator() 메인 스레드 호출 시작.")
            val potWv = PoTokenWebView(context)
            potWv.initialize()
            potWv
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun initialize() {
        println("[YT_DEBUG] [PoTokenWebView] initialize() 내부 초기설정 진입.")
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.userAgentString = USER_AGENT
        settings.blockNetworkLoads = true // 로컬 내부 연산만 허용하여 샌드박스 보안 강화

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
            println("[YT_DEBUG] [PoTokenWebView] 웹뷰 데이터 렌더링 호출을 전개합니다.")
            webView.loadDataWithBaseURL("https://www.youtube.com", html, "text/html", "utf-8", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    @JavascriptInterface
    fun downloadAndRunBotguard() {
        println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] downloadAndRunBotguard() 가 샌드박스로부터 트리거되었습니다.")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                println("[YT_DEBUG] [PoTokenWebView] BotGuard JNN API Create를 호출합니다.")
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/Create",
                    "[ \"$REQUEST_KEY\" ]"
                )
                println("[YT_DEBUG] [PoTokenWebView] JNN Create 호출 성공. 응답 문자 크기: ${responseBody.length} 자")
                
                val parsedChallengeData = parseChallengeData(responseBody)
                val base64Challenge = android.util.Base64.encodeToString(
                    parsedChallengeData.toByteArray(StandardCharsets.UTF_8), 
                    android.util.Base64.NO_WRAP
                )
                
                withContext(Dispatchers.Main) {
                    println("[YT_DEBUG] [PoTokenWebView] 웹뷰 컨텍스트 내의 자바스크립트 VM 엔진을 구동합니다.")
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
                println("[YT_DEBUG] [PoTokenWebView] downloadAndRunBotguard 진행 중 예외 발생: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    initContinuation?.resumeWith(Result.failure(e))
                }
            }
        }
    }

    @JavascriptInterface
    fun onJsInitializationError(error: String) {
        println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] 웹뷰 JS 구동 중 실패 피드백 수신: $error")
        initContinuation?.resumeWith(Result.failure(Exception("JS Init Error: $error")))
    }

    @JavascriptInterface
    fun onRunBotguardResult(botguardResponse: String) {
        println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] BotGuard 응답 수신 성공. 응답 캡슐 크기: ${botguardResponse.length} 자")
        GlobalScope.launch(Dispatchers.IO) {
            try {
                println("[YT_DEBUG] [PoTokenWebView] BotGuard JNN API GenerateIT를 전송하여 무결성을 증명합니다.")
                val responseBody = makeBotguardServiceRequest(
                    "https://www.youtube.com/api/jnn/v1/GenerateIT",
                    "[ \"$REQUEST_KEY\", \"$botguardResponse\" ]"
                )
                println("[YT_DEBUG] [PoTokenWebView] GenerateIT 호출 성공.")
                
                val (integrityToken, expirationTimeInSeconds) = parseIntegrityTokenData(responseBody)
                expirationInstant = Instant.now().plusSeconds(expirationTimeInSeconds - 600)
                println("[YT_DEBUG] [PoTokenWebView] 무결성 토큰 만료시간 동기화 완료: ${expirationInstant}")

                withContext(Dispatchers.Main) {
                    webView.evaluateJavascript("this.integrityToken = $integrityToken", null)
                    println("[YT_DEBUG] [PoTokenWebView] 샌드박스 기기증명 처리가 비차단 방식으로 복개되었습니다.")
                    initContinuation?.resume(Unit)
                }
            } catch (e: Exception) {
                println("[YT_DEBUG] [PoTokenWebView] onRunBotguardResult 처리 중 예외 발생: ${e.message}")
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    initContinuation?.resumeWith(Result.failure(e))
                }
            }
        }
    }

    override suspend fun generatePoToken(identifier: String): String = withContext(Dispatchers.Main) {
        println("[YT_DEBUG] [PoTokenWebView] 비디오 식별자에 묶인 poToken 연산 시도. 식별 인자: '$identifier'")
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
        println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] poToken 연동 파괴 수신. 식별 인자: '$identifier', 오류: $error")
        poTokenContinuations.remove(identifier)?.resumeWith(Result.failure(Exception(error)))
    }

    @JavascriptInterface
    fun onObtainPoTokenResult(identifier: String, poTokenU8: String) {
        try {
            val poToken = u8ToBase64(poTokenU8)
            println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] poToken 발급 최종 완료. 크기: ${poToken.length} 자")
            poTokenContinuations.remove(identifier)?.resume(poToken)
        } catch (t: Throwable) {
            println("[YT_DEBUG] [PoTokenWebView] [JS-Callback] poToken 디코딩 및 역직렬화 중 파괴: ${t.message}")
            poTokenContinuations.remove(identifier)?.resumeWith(Result.failure(t))
        }
    }

    override fun isExpired(): Boolean {
        val expired = Instant.now().isAfter(expirationInstant)
        if (expired) {
            println("[YT_DEBUG] [PoTokenWebView] 현재 증명된 토큰 세션이 만료되었습니다.")
        }
        return expired
    }

    override fun close() {
        println("[YT_DEBUG] [PoTokenWebView] 샌드박스 리소스 해제를 시작합니다.")
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            webView.clearHistory()
            webView.clearCache(true)
            webView.loadUrl("about:blank")
            webView.onPause()
            webView.destroy()
            println("[YT_DEBUG] [PoTokenWebView] 웹뷰 해제 완료.")
        }
    }

    private fun makeBotguardServiceRequest(urlStr: String, data: String): String {
        println("[YT_DEBUG] [PoTokenWebView-Net] POST 아웃바운드 시도: $urlStr")
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
        println("[YT_DEBUG] [PoTokenWebView-Net] HTTP 응답 결과 코드: $httpCode")
        if (httpCode != 200) {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            println("[YT_DEBUG] [PoTokenWebView-Net] 전송 에러 페이로드: $errText")
            throw Exception("HTTP Error from BotGuard Service: $httpCode, details: $errText")
        }
        val res = conn.inputStream.bufferedReader().use { it.readText() }
        println("[YT_DEBUG] [PoTokenWebView-Net] 수집 완료 데이터 크기: ${res.length} 자")
        return res
    }
}

fun parseChallengeData(rawChallengeData: String): String {
    println("[YT_DEBUG] [Telemetry-Decoder] parseChallengeData() 인입 데이터 크기: ${rawChallengeData.length}")
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

    println("[YT_DEBUG] [Telemetry-Decoder] Challenge 파싱 완료 -> messageId: $messageId, globalName: $globalName")

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

    // [데드락 및 진입 레이턴시 해결]: 재생 진입 전 사전 웜스타트 연산 처리 및 락 해제
    fun preInitialize(context: Context) {
        synchronized(lock) {
            if (webPoTokenGenerator != null && !webPoTokenGenerator!!.isExpired()) {
                println("[YT_DEBUG] [PoTokenProviderImpl] 사전 기동 상태 검증: 이미 발급 세션이 활성화되어 있으므로 바이패스합니다.")
                return
            }
            println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트 초기 기동을 백그라운드 세션에 등록합니다.")
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val savedVisitor = prefs.getString("yt_visitor_data", null) ?: "CgptdWpfa0NXZ2tfdyi28vS0Bg"
            webPoTokenVisitorData = savedVisitor

            GlobalScope.launch(Dispatchers.Main) {
                try {
                    println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트용 PoTokenWebView 샌드박스를 구성합니다.")
                    val generator = PoTokenWebView.newPoTokenGenerator(context)
                    println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트 샌드박스 초기설정 수립 완료. 사전 poToken을 인출합니다.")
                    val streamingPot = generator.generatePoToken(savedVisitor)
                    synchronized(lock) {
                        webPoTokenGenerator = generator
                        webPoTokenStreamingPot = streamingPot
                    }
                    println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트 완료 -> visitorData: $savedVisitor, streamingPot: ${streamingPot.take(15)}...")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트 사전 증명 단계 중 예외: ${e.message}")
                    e.printStackTrace()
                }
            }
        }
    }

    override fun getWebClientPoToken(videoId: String): PoTokenResult? {
        try {
            return getWebClientPoToken(videoId, forceRecreate = false)
        } catch (e: Exception) {
            println("[YT_DEBUG] [PoTokenProviderImpl] poToken 최종 인출 억제실패: ${e.message}")
            return null
        }
    }

    private fun getWebClientPoToken(videoId: String, forceRecreate: Boolean): PoTokenResult {
        println("[YT_DEBUG] [PoTokenProviderImpl] getWebClientPoToken() 최종 요청 수신. 타겟 비디오 ID: $videoId, 강제 재구동 옵션: $forceRecreate")
        val (generator, visitorData, streamingPot, hasBeenRecreated) = synchronized(lock) {
            val shouldRecreate = webPoTokenGenerator == null || forceRecreate || webPoTokenGenerator!!.isExpired()
            if (shouldRecreate) {
                println("[YT_DEBUG] [PoTokenProviderImpl] 웜스타트 누락 혹은 유실로 신규 샌드박스를 현시점 동기 생성합니다. (약간의 대기 지연이 발생할 수 있음)")
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

        println("[YT_DEBUG] [PoTokenProviderImpl] 비디오 결합형 챌린지 생성을 개시합니다.")
        val playerPot = try {
            kotlinx.coroutines.runBlocking {
                generator.generatePoToken(videoId)
            }
        } catch (throwable: Throwable) {
            if (hasBeenRecreated) {
                throw throwable
            } else {
                println("[YT_DEBUG] [PoTokenProviderImpl] 세션 유효성 만료로 인한 에러 감지. 신규 샌드박스로 강제 재시도합니다.")
                return getWebClientPoToken(videoId, forceRecreate = true)
            }
        }

        println("[YT_DEBUG] [PoTokenProviderImpl] 유튜브 전역 기기 무결성 증명 성공. PlayerPot: ${playerPot.take(15)}...")
        return PoTokenResult(visitorData, playerPot, streamingPot)
    }

    override fun getWebEmbedClientPoToken(videoId: String): PoTokenResult? = null
    override fun getAndroidClientPoToken(videoId: String): PoTokenResult? = null 
    override fun getIosClientPoToken(videoId: String): PoTokenResult? = null     

    private data class Quadruple<T1, T2, T3, T4>(val first: T1, val second: T2, val third: T3, val fourth: T4)
}

//endregion
