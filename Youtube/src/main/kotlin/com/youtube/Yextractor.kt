package com.youtube

import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        @Volatile
        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        // SecureRandom 동기식 지연 차단을 배제하기 위한 고성능 원자적 스레드세이프 카운터
        private val streamCounter = AtomicLong(0)

        // 가상 프록시 Manifest 캐시 (최대 1000개 보장 LRU)
        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        // 메모리 누수를 완전히 방어하는 고정 크기 LRU 스트림 URL 캐시 (최대 1000개 보장)
        private val streamUrlCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        // ExoPlayer의 백그라운드 중복 검증 조회 호출(Retry) 차단을 위한 쿨타임 관리 캐시
        private val recentTriggers: MutableMap<String, Long> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                    return size > 100
                }
            }
        )

        private val serverExecutor = ThreadPoolExecutor(
            4, 
            16, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        // 타입 추론 및 의존성 고립을 위한 표준 OkHttpClient 인스턴스 빌드 구성
        private val proxyHttpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        // 클래스 적재 시 정적 pre-compile 처리되는 정규식 선언부
        private val VISITOR_DATA_REGEX = Regex(""""VISITOR_DATA":"([^"]+)"""")
        private val STS_REGEX = Regex(""""signatureTimestamp":\s*(\d+)"""")
        private val WEB_API_KEY_REGEX = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
        private val CHUNK_ID_REGEX = Regex("id=([^&]+)")
        private val FMT_REPLACE_REGEX = Regex("&fmt=[^&]+")
            
        init {
            println("[YT_DEBUG_INIT] 유튜브 DASH 추출기 최적화 가상서버 초기화 완료")
        }

        // 인텐트를 구성해 타겟 다운로더 앱(Seal, YTDLnis 등)에 유튜브 주소를 즉시 넘겨 실행시키는 메소드
        private fun openInExternalApp(packageName: String, targetUrl: String) {
            val context = AcraApplication.context ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, targetUrl)
                        setPackage(packageName)
                        addCategory(android.content.Intent.CATEGORY_DEFAULT)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    // 앱 미설치로 실행에 실패한 경우 타겟 패키지에 매칭되는 영어 토스트 알림 노출
                    try {
                        val appName = when (packageName) {
                            "com.junkfood.seal" -> "Seal"
                            "com.maheshtechnicals.sealplus" -> "Seal Plus"
                            "com.deniscerri.ytdl" -> "YTDLnis"
                            else -> "The downloader"
                        }
                        android.widget.Toast.makeText(
                            context,
                            "$appName is not installed. Please install the app and try again.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                }
            }
        }

        private fun handleClient(client: Socket) {
            try {
                // client.use 영역 진입을 통하여 소켓 및 IO 자원의 안전 소멸 주기 확립
                client.use { socket ->
                    val inputStream = socket.getInputStream()
                    val reader = BufferedReader(InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))
                    val output = socket.getOutputStream()
                    val outWriter = PrintWriter(output, true)

                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return

                    val fullPath = parts[1]
                    
                    val clientHeaders = LinkedHashMap<String, String>()
                    var headerLines = 0
                    while (headerLines++ < 100) {
                        val line = reader.readLine()
                        if (line.isNullOrBlank()) break
                        val colonIndex = line.indexOf(':')
                        if (colonIndex != -1) {
                            clientHeaders[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
                        }
                    }

                    val cleanPath = fullPath.substringBefore("?")
                    val queryParams = fullPath.substringAfter("?", "")

                    // 타겟 외부 다운로드 앱 바로가기 인터셉터 동작 처리 영역
                    if (cleanPath.startsWith("/open_app")) {
                        var pkg: String? = null
                        var targetUrl: String? = null
                        
                        val pairs = queryParams.split("&")
                        for (pair in pairs) {
                            val idx = pair.indexOf("=")
                            if (idx != -1) {
                                val key = pair.substring(0, idx)
                                val value = pair.substring(idx + 1)
                                if (key == "package") {
                                    pkg = java.net.URLDecoder.decode(value, "UTF-8")
                                } else if (key == "url") {
                                    targetUrl = java.net.URLDecoder.decode(value, "UTF-8")
                                }
                            }
                        }
                        
                        if (targetUrl != null && pkg != null) {
                            val triggerKey = "$pkg|$targetUrl"
                            val now = System.currentTimeMillis()
                            val lastTrigger = recentTriggers[triggerKey] ?: 0L
                            
                            // 3초 중복 호출 감지 쿨타임을 통해 중복 실행 및 대기열 중지 해결
                            if (now - lastTrigger > 3000L) {
                                recentTriggers[triggerKey] = now
                                openInExternalApp(pkg, targetUrl)
                            } else {
                                println("[YT_PROXY_DEBUG] 중복된 앱 실행 요청 감지되어 인텐트 발송 차단: $triggerKey")
                            }
                        }
                        
                        // 비디오 플레이어가 에러 메시지 없이 조용히 중단되도록 204 반환
                        outWriter.print("HTTP/1.1 204 No Content\r\n")
                        outWriter.print("Connection: close\r\n")
                        outWriter.print("Access-Control-Allow-Origin: *\r\n")
                        outWriter.print("\r\n")
                        outWriter.flush()
                    }
                    else if (cleanPath.startsWith("/chunk")) {
                        val idMatch = CHUNK_ID_REGEX.find(queryParams)
                        val chunkId = idMatch?.groupValues?.get(1)
                        val targetYoutubeUrl = if (chunkId != null) streamUrlCache[chunkId] else null

                        if (targetYoutubeUrl != null) {
                            proxyChunkToYouTube(client, targetYoutubeUrl, clientHeaders)
                        } else {
                            outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                            outWriter.flush()
                        }
                    } 
                    else if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
                        val path = cleanPath.substringBeforeLast(".mpd").removePrefix("/")
                        val content = manifestMap[path]
                        if (content != null) {
                            outWriter.print("HTTP/1.1 200 OK\r\n")
                            outWriter.print("Content-Type: application/dash+xml\r\n")
                            outWriter.print("Connection: close\r\n")
                            outWriter.print("Access-Control-Allow-Origin: *\r\n")
                            outWriter.print("\r\n")
                            outWriter.print(content)
                            outWriter.flush()
                        } else {
                            outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                            outWriter.flush()
                        }
                    }
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] handleClient 예외: ${e.message}")
            }
        }

        private fun proxyChunkToYouTube(socket: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
            try {
                val requestBuilder = okhttp3.Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", YoutubeUtils.ANDROID_VR_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")

                clientHeaders.forEach { (key, value) ->
                    val lowerKey = key.lowercase()
                    if (lowerKey != "host" && lowerKey != "user-agent" && lowerKey != "origin" && lowerKey != "referer" && lowerKey != "accept-encoding") {
                        requestBuilder.header(key, value)
                    }
                }

                val request = requestBuilder.build()
                val response = proxyHttpClient.newCall(request).execute()
                
                try {
                    if (response.code == 403 || response.code == 400) {
                        println("[YT_PROXY_DEBUG] 🚨 유튜브 CDN 에러 발생: ${response.code}")
                        val output = socket.getOutputStream()
                        val rawOutWriter = java.io.PrintWriter(output, false)
                        rawOutWriter.print("HTTP/1.1 ${response.code} ${response.message}\r\n\r\n")
                        rawOutWriter.flush()
                        return
                    }

                    val output = socket.getOutputStream()
                    val rawOutWriter = java.io.PrintWriter(output, false)

                    rawOutWriter.print("HTTP/1.1 ${response.code} ${response.message}\r\n")
                    response.header("Content-Type")?.let { rawOutWriter.print("Content-Type: $it\r\n") }
                    response.header("Content-Length")?.let { rawOutWriter.print("Content-Length: $it\r\n") }
                    response.header("Content-Range")?.let { rawOutWriter.print("Content-Range: $it\r\n") }
                    response.header("Accept-Ranges")?.let { rawOutWriter.print("Accept-Ranges: $it\r\n") }
                    rawOutWriter.print("Connection: close\r\n")
                    rawOutWriter.print("\r\n")
                    rawOutWriter.flush()

                    val body = response.body
                    if (body != null) {
                        body.byteStream().use { input ->
                            // 64KB 고속 버퍼 릴레이 데이터 전송 지원
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    output.flush()
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] 프록시 릴레이 중 예외 발생: ${e.message}")
            }
        }
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val codec: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val codec: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?
    )

    // 로컬 가상 외부 다운로더 앱 인텐트 공유 리다이렉트 URL 생성기
    private fun getAppRedirectUrl(packageName: String, targetUrl: String): String? {
        startServerIfNeeded()
        return if (serverPort != 0) {
            try {
                val encodedPkg = java.net.URLEncoder.encode(packageName, "UTF-8")
                val encodedUrl = java.net.URLEncoder.encode(targetUrl, "UTF-8")
                "http://127.0.0.1:$serverPort/open_app?package=$encodedPkg&url=$encodedUrl"
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    private suspend fun fetchPlayerResponse(videoId: String): Pair<JSONObject?, Long> {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val html = app.get(watchUrl, headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)).text
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: ""
        val sts = STS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val androidApiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$androidApiKey"

        val vrPayload = mapOf(
            "videoId" to videoId,
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to "ANDROID_VR",
                    "clientVersion" to "1.65.10",
                    "androidSdkVersion" to 32,
                    "osName" to "Android",
                    "osVersion" to "12L",
                    "deviceMake" to "Oculus",
                    "deviceModel" to "Quest 3",
                    "hl" to "en",
                    "gl" to "US",
                    "visitorData" to visitorData
                )
            ),
            "playbackContext" to mapOf(
                "contentPlaybackContext" to mapOf(
                    "signatureTimestamp" to sts
                )
            )
        )
        
        val vrHeaders = mapOf(
            "User-Agent" to YoutubeUtils.ANDROID_VR_USER_AGENT,
            "X-YouTube-Client-Name" to "28",
            "X-YouTube-Client-Version" to "1.65.10",
            "Content-Type" to "application/json"
        )

        try {
            println("[YT_DEBUG_EXTRACTOR] ANDROID_VR API 직접 호출 중... (sts: $sts)")
            val vrRes = app.post(apiUrl, json = vrPayload, headers = vrHeaders).text
            val vrJson = JSONObject(vrRes)
            val status = vrJson.optJSONObject("playabilityStatus")?.optString("status")
            
            val lengthSeconds = vrJson.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
            
            println("[YT_DEBUG_EXTRACTOR] ANDROID_VR API 응답 상태: $status, 영상 길이: ${lengthSeconds}초")
            
            if (status == "OK" || vrJson.has("streamingData")) {
                println("[YT_DEBUG_EXTRACTOR] ✅ ANDROID_VR에서 playerResponse 확보 성공!")
                return Pair(vrJson, lengthSeconds)
            } else if (status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
                println("[YT_DEBUG_EXTRACTOR] VR 차단됨. TV_EMBEDDED로 연령 제한 우회 시도...")
                val webApiKey = WEB_API_KEY_REGEX.find(html)?.groupValues?.get(1)
                
                if (!webApiKey.isNullOrEmpty()) {
                    val tvApiUrl = "https://www.youtube.com/youtubei/v1/player?key=$webApiKey"
                    val tvPayload = mapOf(
                        "videoId" to videoId,
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                                "clientVersion" to "2.0",
                                "hl" to "en",
                                "gl" to "US",
                                "visitorData" to visitorData
                            ),
                            "thirdParty" to mapOf("embedUrl" to "https://www.youtube.com")
                        ),
                        "playbackContext" to mapOf(
                            "contentPlaybackContext" to mapOf(
                                "signatureTimestamp" to sts
                            )
                        )
                    )
                    val tvRes = app.post(tvApiUrl, json = tvPayload).text
                    val tvJson = JSONObject(tvRes)
                    val tvLengthSeconds = tvJson.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                    println("[YT_DEBUG_EXTRACTOR] TV API 응답 상태: ${tvJson.optJSONObject("playabilityStatus")?.optString("status")}")
                    return Pair(tvJson, tvLengthSeconds)
                }
            }
        } catch (e: Exception) { 
            println("[YT_DEBUG_EXTRACTOR] API 요청 예외: ${e.message}") 
        }

        return Pair(null, 3600L)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[YT_DEBUG_EXTRACTOR] ===== GET URL START =====")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl
        println("[YT_DEBUG_EXTRACTOR] 비디오 ID: $videoId")

        withContext(Dispatchers.IO) {
            try {
                val (playerResponse, durationSeconds) = fetchPlayerResponse(videoId)

                // 우회 수단이 전부 막혔을 때 등록되는 Fallback 영역 (오직 앱 다이렉트 호출만 활성화)
                if (playerResponse == null) {
                    println("[YT_DEBUG_EXTRACTOR] 🚨 모든 우회 실패! playerResponse 없음.")
                    
                    val originalUrl = "https://www.youtube.com/watch?v=$videoId"

                    val ytdlPkg = "com.deniscerri.ytdl"
                    val ytdlRedirect = getAppRedirectUrl(ytdlPkg, originalUrl) ?: originalUrl
                    callback(newExtractorLink(source = "YouTube Downloader", name = "YTDLnis Download", url = ytdlRedirect, type = if (ytdlRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                  
                    // Seal, SealPlus, YTDLnis 즉시 다운로드용 가상 리링크 추가
                    val sealPkg = "com.junkfood.seal"
                    val sealRedirect = getAppRedirectUrl(sealPkg, originalUrl) ?: originalUrl
                    callback(newExtractorLink(source = "YouTube Downloader", name = "Seal Download", url = sealRedirect, type = if (sealRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

                    val sealPlusPkg = "com.maheshtechnicals.sealplus"
                    val sealPlusRedirect = getAppRedirectUrl(sealPlusPkg, originalUrl) ?: originalUrl
                    callback(newExtractorLink(source = "YouTube Downloader", name = "Seal Plus Download", url = sealPlusRedirect, type = if (sealPlusRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

                    
                    return@withContext
                }

                val streamingData = playerResponse.optJSONObject("streamingData")
                if (streamingData == null) {
                    println("[YT_DEBUG_EXTRACTOR] 🚨 streamingData 없음.")
                    return@withContext
                }

                val hlsUrl = streamingData.optString("hlsManifestUrl")
                if (!hlsUrl.isNullOrBlank()) {
                    println("[YT_DEBUG_EXTRACTOR] 실시간 스트리밍(HLS) 발견: $hlsUrl")
                    callback(
                        newExtractorLink(
                            this@YoutubeExtractor.name,
                            "YouTube Live",
                            hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return@withContext
                }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                
                val videoOnlyList = ArrayList<StreamInfo>(adaptiveFormats.length())
                val audioInfoList = ArrayList<AudioInfo>(adaptiveFormats.length())

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    
                    if (streamUrl.isNullOrEmpty()) continue

                    val rawMimeType = format.optString("mimeType", "")
                    var cleanMimeType = rawMimeType
                    var codec = ""
                    
                    val semicolonIndex = rawMimeType.indexOf(";")
                    if (semicolonIndex != -1) {
                        cleanMimeType = rawMimeType.substring(0, semicolonIndex).trim()
                        val rawCodecs = rawMimeType.substring(semicolonIndex + 1)
                        val codecStart = rawCodecs.indexOf("codecs=")
                        if (codecStart != -1) {
                            val afterCodecs = rawCodecs.substring(codecStart + 7).trim()
                            val firstQuote = afterCodecs.indexOf("\"")
                            if (firstQuote != -1) {
                                val secondQuote = afterCodecs.indexOf("\"", firstQuote + 1)
                                codec = if (secondQuote != -1) {
                                    afterCodecs.substring(firstQuote + 1, secondQuote)
                                } else {
                                    afterCodecs.substring(firstQuote + 1)
                                }
                            } else {
                                codec = afterCodecs
                            }
                        }
                    }

                    val initRange = format.optJSONObject("initRange")
                    val indexRange = format.optJSONObject("indexRange")

                    val initStart = initRange?.optString("start")
                    val initEnd = initRange?.optString("end")
                    val idxStart = indexRange?.optString("start")
                    val idxEnd = indexRange?.optString("end")

                    val initR = if (initStart != null && initEnd != null) "$initStart-$initEnd" else null
                    val indexR = if (idxStart != null && idxEnd != null) "$idxStart-$idxEnd" else null

                    if (cleanMimeType.startsWith("video/")) {
                        val height = format.optInt("height", 0)
                        val label = if (height > 0) "${height}p" else "video"
                        if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "vp9" else "avc1.4d401f"
                        
                        videoOnlyList.add(StreamInfo(streamUrl, cleanMimeType, codec, height, label, initR, indexR))
                    } else if (cleanMimeType.startsWith("audio/")) {
                        val bitrate = format.optInt("bitrate", 128000)
                        if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "opus" else "mp4a.40.2"
                        
                        audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR))
                    }
                }

                println("[YT_DEBUG_EXTRACTOR] 파싱 완료 -> 비디오: ${videoOnlyList.size}개, 오디오: ${audioInfoList.size}개")

                val captions = playerResponse.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
                if (captions != null) {
                    for (i in 0 until captions.length()) {
                        val track = captions.getJSONObject(i)
                        var subUrl = track.optString("baseUrl")
                        val lang = track.optString("languageCode")
                        
                        // [자막 최적화] 자동 생성(Auto-generated / ASR) 자막 식별 및 필터링 제거
                        val kind = track.optString("kind", "")
                        val vssId = track.optString("vssId", "")
                        if (kind == "asr" || vssId.startsWith("a.")) {
                            continue
                        }

                        val nameObj = track.optJSONObject("name")
                        var displayName = nameObj?.optString("simpleText") ?: ""
                        if (displayName.isBlank()) {
                            val runs = nameObj?.optJSONArray("runs")
                            if (runs != null && runs.length() > 0) {
                                displayName = runs.optJSONObject(0)?.optString("text") ?: ""
                            }
                        }
                        if (displayName.isBlank()) {
                            displayName = lang.uppercase()
                        }

                        // [자막 최적화] 이름 기반의 2차 자동 생성 자막 안전 장치 필터링
                        if (displayName.contains("auto-generated", ignoreCase = true) || 
                            displayName.contains("자동 생성", ignoreCase = true)) {
                            continue
                        }

                        if (subUrl.isNotEmpty() && lang.isNotEmpty()) {
                            if (subUrl.startsWith("http")) {
                                subUrl = subUrl.replace(FMT_REPLACE_REGEX, "")
                                if (!subUrl.contains("fmt=srt")) {
                                    subUrl = "$subUrl&fmt=srt"
                                }
                            }
                            subtitleCallback(SubtitleFile(displayName, subUrl))
                        }
                    }
                }

                if (videoOnlyList.isNotEmpty() && audioInfoList.isNotEmpty()) {
                    startServerIfNeeded()

                    for (video in videoOnlyList.distinctBy { it.height }) {
                        val bestAudio = if (video.mimeType.contains("webm")) {
                            audioInfoList.maxByOrNull { if (it.mimeType.contains("webm")) it.bitrate + 1000000 else it.bitrate }
                        } else {
                            audioInfoList.maxByOrNull { if (it.mimeType.contains("mp4")) it.bitrate + 1000000 else it.bitrate }
                        }

                        if (bestAudio != null) {
                            val dashXml = buildDashManifestXml(video, listOf(bestAudio), durationSeconds)
                            val localLink = registerManifestAndGetUrl(dashXml)

                            if (localLink != null) {
                                val finalName = "YouTube ${video.label}"
                                val sourceName = "YouTube"
                                
                                callback(
                                    newExtractorLink(
                                        sourceName, finalName, localLink, type = ExtractorLinkType.DASH
                                    ) {
                                        this.referer = mainUrl
                                        this.quality = video.height
                                        this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                                    }
                                )
                            }
                        }
                    }
                }

                // 정상 동작 상황 하에서의 외부 앱 리링크 추가 영역
                val originalUrl = "https://www.youtube.com/watch?v=$videoId"

                val ytdlPkg = "com.deniscerri.ytdl"
                val ytdlRedirect = getAppRedirectUrl(ytdlPkg, originalUrl) ?: originalUrl
                callback(newExtractorLink(source = "YouTube Downloader", name = "YTDLnis Download", url = ytdlRedirect, type = if (ytdlRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                
                // Seal, SealPlus, YTDLnis 즉시 다운로드용 가상 리링크 추가
                val sealPkg = "com.junkfood.seal"
                val sealRedirect = getAppRedirectUrl(sealPkg, originalUrl) ?: originalUrl
                callback(newExtractorLink(source = "YouTube Downloader", name = "Seal Download", url = sealRedirect, type = if (sealRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

                val sealPlusPkg = "com.maheshtechnicals.sealplus"
                val sealPlusRedirect = getAppRedirectUrl(sealPlusPkg, originalUrl) ?: originalUrl
                callback(newExtractorLink(source = "YouTube Downloader", name = "Seal Plus Download", url = sealPlusRedirect, type = if (sealPlusRedirect.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

                
            } catch (e: Exception) { 
                println("[YT_DEBUG_EXTRACTOR] 🚨 Exception 발생: ${e.message}")
                logError(e) 
            }
        }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) return
            try {
                // 루프백 호스트(127.0.0.1) 격리 바인딩을 적용하여 보안 한계 유지
                val newServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_PROXY_DEBUG] 가상 프록시 루프백 서버 바인딩 완료: $serverPort")
                
                thread(name = "YT-DASH-Server-Acceptor") {
                    try {
                        while (true) {
                            val active = activeServer ?: break
                            if (active.isClosed) break
                            val client = active.accept()
                            client.soTimeout = 15000 
                            serverExecutor.execute { handleClient(client) }
                        }
                    } catch (e: Exception) {
                        println("[YT_PROXY_DEBUG] ServerSocket accept 오류: ${e.message}")
                    }
                }
            } catch (e: Exception) { logError(e) }
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = "m_${streamCounter.getAndIncrement()}"
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        // 난수 고갈 회피형 Atomic ID 체계 매핑
        val videoChunkId = "v_${streamCounter.getAndIncrement()}"
        streamUrlCache[videoChunkId] = video.url
        val proxyVideoUrl = "http://127.0.0.1:$serverPort/chunk?id=$videoChunkId"
        
        val durationString = "PT${durationSec}S"

        val sb = java.lang.StringBuilder(2048)
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="${video.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="${video.codec}">
                <BaseURL>$proxyVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val audioIdChunk = "a_${streamCounter.getAndIncrement()}"
            streamUrlCache[audioIdChunk] = audio.url
            val proxyAudioUrl = "http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk"

            val audioId = "audio_$index"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="${audio.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="${audio.codec}">
                    <BaseURL>$proxyAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }
}
