package com.youtube

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
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
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
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

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        private val streamUrlCache = ConcurrentHashMap<String, String>()

        private val serverExecutor = ThreadPoolExecutor(
            8, 
            32, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(1024),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        private val proxyHttpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
            
        init {
            println("[YT_DEBUG_INIT] 독자 API 추출기 초기화 완료 (DASH XML 및 자막·더빙 패치)")
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
        val indexRange: String?,
        val trackId: String,
        val displayName: String
    )

    private suspend fun fetchStreamingData(videoId: String): Triple<JSONObject?, JSONObject?, Long> {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val html = app.get(watchUrl, headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)).text
        val visitorData = Regex(""""VISITOR_DATA":"([^"]+)"""").find(html)?.groupValues?.get(1) ?: ""
        val sts = Regex(""""signatureTimestamp":\s*(\d+)""").find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0

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
            println("[YT_DEBUG_EXTRACTOR] ANDROID_VR API 호출 중 (sts: $sts)")
            val vrRes = app.post(apiUrl, json = vrPayload, headers = vrHeaders).text
            val vrJson = JSONObject(vrRes)
            val status = vrJson.optJSONObject("playabilityStatus")?.optString("status")
            
            val lengthSeconds = vrJson.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
            
            if (status == "OK" || vrJson.has("streamingData")) {
                println("[YT_DEBUG_EXTRACTOR] ANDROID_VR streamingData & captions 획득 완료")
                return Triple(vrJson.optJSONObject("streamingData"), vrJson.optJSONObject("captions"), lengthSeconds)
            } else if (status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
                println("[YT_DEBUG_EXTRACTOR] 연령 제한 감지. TVHTML5_SIMPLY_EMBEDDED_PLAYER 우회 시도...")
                val webApiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.get(1)
                
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
                    return Triple(tvJson.optJSONObject("streamingData"), tvJson.optJSONObject("captions"), tvLengthSeconds)
                }
            }
        } catch (e: Exception) { 
            println("[YT_DEBUG_EXTRACTOR] Player API 요청 중 오류: ${e.message}") 
        }

        return Triple(null, null, 3600L)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        withContext(Dispatchers.IO) {
            try {
                val (streamingData, captionsObj, durationSeconds) = fetchStreamingData(videoId)

                if (streamingData == null) {
                    println("[YT_DEBUG_EXTRACTOR] 🚨 모든 우회 수단 실패. 웹 다운로드 주소를 대체 링크로 제공합니다.")
                    callback(newExtractorLink(source = "YouTube Downloader", name = "Cobalt Web Download (4K)", url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                    callback(newExtractorLink(source = "YouTube Downloader", name = "SOSYouTube Download (1080p)", url = "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 1080; this.referer = mainUrl })
                    return@withContext
                }

                // 1. 자막 및 자동 번역 자막 파싱 처리
                if (captionsObj != null) {
                    val tracklistRenderer = captionsObj.optJSONObject("playerCaptionsTracklistRenderer")
                    if (tracklistRenderer != null) {
                        val captionTracks = tracklistRenderer.optJSONArray("captionTracks")
                        val translationLanguages = tracklistRenderer.optJSONArray("translationLanguages")

                        if (captionTracks != null) {
                            for (i in 0 until captionTracks.length()) {
                                val track = captionTracks.getJSONObject(i)
                                val baseUrl = track.optString("baseUrl") ?: continue
                                if (baseUrl.isEmpty()) continue
                                
                                val trackNameJson = track.optJSONObject("name")
                                val trackName = trackNameJson?.optString("simpleText") 
                                    ?: trackNameJson?.optJSONArray("runs")?.optJSONObject(0)?.optString("text") 
                                    ?: track.optString("languageCode") 
                                    ?: "Subtitle"
                                
                                val isAsr = track.optString("kind") == "asr"
                                val suffix = if (isAsr) " (Auto-Generated)" else ""
                                val displayName = "$trackName$suffix"

                                val srtUrl = cleanSubtitleUrl(baseUrl)
                                subtitleCallback(SubtitleFile(displayName, srtUrl))
                            }

                            // 첫 번째 번역 가능 원본 트랙을 기준으로 구글 번역 자막을 생성
                            if (translationLanguages != null && translationLanguages.length() > 0) {
                                var transBaseTrack: JSONObject? = null
                                for (i in 0 until captionTracks.length()) {
                                    val track = captionTracks.getJSONObject(i)
                                    if (track.optBoolean("isTranslatable", true)) {
                                        transBaseTrack = track
                                        break
                                    }
                                }
                                if (transBaseTrack == null && captionTracks.length() > 0) {
                                    transBaseTrack = captionTracks.getJSONObject(0)
                                }

                                if (transBaseTrack != null) {
                                    val baseSubUrl = transBaseTrack.optString("baseUrl")
                                    if (baseSubUrl.isNotEmpty()) {
                                        for (j in 0 until translationLanguages.length()) {
                                            val transLang = translationLanguages.getJSONObject(j)
                                            val tLangCode = transLang.optString("languageCode")
                                            val tLangNameJson = transLang.optJSONObject("languageName")
                                            val tLangName = tLangNameJson?.optString("simpleText")
                                                ?: tLangNameJson?.optJSONArray("runs")?.optJSONObject(0)?.optString("text")
                                                ?: tLangCode
                                            
                                            if (tLangCode == transBaseTrack.optString("languageCode")) continue

                                            val translatedUrl = cleanSubtitleUrl(baseSubUrl, tLangCode)
                                            val translatedDisplayName = "$tLangName (Translated)"
                                            subtitleCallback(SubtitleFile(translatedDisplayName, translatedUrl))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. 비디오 및 오디오 인포 파싱 처리
                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                val videoOnlyList = mutableListOf<StreamInfo>()
                val audioInfoList = mutableListOf<AudioInfo>()

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    if (streamUrl.isNullOrEmpty()) continue

                    val rawMimeType = format.optString("mimeType", "")
                    var cleanMimeType = rawMimeType
                    var codec = ""
                    
                    if (rawMimeType.contains(";")) {
                        val parts = rawMimeType.split(";")
                        cleanMimeType = parts[0].trim()
                        val codecPart = parts.find { it.trim().startsWith("codecs=") }
                        if (codecPart != null) {
                            codec = codecPart.substringAfter("=").replace("\"", "").trim()
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
                        val audioTrack = format.optJSONObject("audioTrack")
                        val trackId = audioTrack?.optString("id", "Default") ?: "Default"
                        val displayName = audioTrack?.optString("displayName", "Default") ?: "Default"
                        if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "opus" else "mp4a.40.2"
                        
                        audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, trackId, displayName))
                    }
                }

                // 3. 다국어 및 더빙 트랙 결합을 통한 DASH 매니페스트 구축 (TrackId 기준 그룹화로 중복 제거 방지)
                if (videoOnlyList.isNotEmpty() && audioInfoList.isNotEmpty()) {
                    val audiosByTrack = audioInfoList.groupBy { it.trackId }
                    startServerIfNeeded()

                    for (video in videoOnlyList.distinctBy { it.height }) {
                        for ((trackId, audios) in audiosByTrack) {
                            val bestAudio = if (video.mimeType.contains("webm")) {
                                audios.maxByOrNull { if (it.mimeType.contains("webm")) it.bitrate + 1000000 else it.bitrate }
                            } else {
                                audios.maxByOrNull { if (it.mimeType.contains("mp4")) it.bitrate + 1000000 else it.bitrate }
                            }

                            if (bestAudio != null) {
                                val dashXml = buildDashManifestXml(video, listOf(bestAudio), durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    val formattedLang = formatAudioLabel(bestAudio.trackId, bestAudio.displayName)
                                    val finalName = "YouTube ${video.label} ($formattedLang)"
                                    val sourceName = "${this@YoutubeExtractor.name} ($formattedLang)"
                                    
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
                }

                // 4. 레거시 단일 비디오( formats ) 처리 추가 (360p / 720p Direct)
                val formats = streamingData.optJSONArray("formats")
                if (formats != null) {
                    for (i in 0 until formats.length()) {
                        val format = formats.getJSONObject(i)
                        val streamUrl = format.optString("url")
                        if (streamUrl.isNullOrEmpty()) continue
                        
                        val height = format.optInt("height", 0)
                        val label = if (height > 0) "${height}p" else "Legacy"
                        
                        callback(
                            newExtractorLink(
                                this@YoutubeExtractor.name,
                                "YouTube $label (Legacy Direct)",
                                streamUrl,
                                type = INFER_TYPE
                            ) {
                                this.referer = mainUrl
                                this.quality = height
                                this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                            }
                        )
                    }
                }

            } catch (e: Exception) { 
                println("[YT_DEBUG_EXTRACTOR] 🚨 오류 발생: ${e.message}")
                logError(e) 
            }
        }
    }

    private fun cleanSubtitleUrl(baseUrl: String, tlang: String? = null): String {
        var url = baseUrl
        url = url.replace(Regex("&fmt=[^&]+"), "")
        if (tlang != null) {
            url = url.replace(Regex("&tlang=[^&]+"), "")
            url += "&tlang=$tlang"
        }
        if (!url.contains("fmt=srt")) {
            url += "&fmt=srt"
        }
        return url
    }

    private fun formatAudioLabel(trackId: String, displayName: String): String {
        val upperId = trackId.uppercase()
        val isDefault = upperId == "DEFAULT" || upperId == "PRIMARY"
        
        var baseLang = if (trackId.contains(".")) trackId.substringBefore(".") else trackId
        if (baseLang.length > 3) {
            baseLang = ""
        }
        baseLang = baseLang.uppercase()

        val lowerName = displayName.lowercase()
        val type = when {
            lowerName.contains("original") -> "Original"
            lowerName.contains("descriptive") || lowerName.contains("description") || trackId.endsWith(".2") -> "Descriptive"
            lowerName.contains("dubbed") || lowerName.contains("dub") || trackId.endsWith(".10") || trackId.endsWith(".3") -> "Dubbed"
            else -> ""
        }

        val displayLang = if (baseLang.isNotEmpty()) baseLang else {
            when {
                lowerName.contains("korean") || lowerName.contains("한국어") -> "KO"
                lowerName.contains("english") || lowerName.contains("영어") -> "EN"
                lowerName.contains("japanese") || lowerName.contains("일본어") -> "JA"
                lowerName.contains("spanish") -> "ES"
                lowerName.contains("french") -> "FR"
                else -> displayName.take(15).trim()
            }
        }

        return buildString {
            append(displayLang)
            if (type.isNotEmpty()) {
                append(" - $type")
            } else if (trackId.contains(".") && !trackId.endsWith(".4") && !trackId.endsWith(".0")) {
                append(" (Track ${trackId.substringAfter(".")})")
            }
        }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) return
            try {
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_PROXY_DEBUG] 가상 루프백 DASH 프록시 서버 시작됨 포트: $serverPort")
                
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
                        println("[YT_PROXY_DEBUG] ServerSocket 연결 예외: ${e.message}")
                    }
                }
            } catch (e: Exception) { logError(e) }
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
                val inputStream = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))
                val output = socket.getOutputStream()
                val outWriter = PrintWriter(output, true)

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val fullPath = parts[1]
                
                val clientHeaders = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                    val colonIndex = line.indexOf(':')
                    if (colonIndex != -1) {
                        clientHeaders[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
                    }
                }

                val cleanPath = fullPath.substringBefore("?")
                val queryParams = fullPath.substringAfter("?", "")

                if (cleanPath.startsWith("/chunk")) {
                    val idMatch = Regex("id=([^&]+)").find(queryParams)
                    val chunkId = idMatch?.groupValues?.get(1)
                    val targetYoutubeUrl = if (chunkId != null) streamUrlCache[chunkId] else null

                    if (targetYoutubeUrl != null) {
                        proxyChunkToYouTube(socket, targetYoutubeUrl, clientHeaders)
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
            println("[YT_PROXY_DEBUG] handleClient 오류: ${e.message}")
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
            
            if (response.code == 403 || response.code == 400) {
                println("[YT_PROXY_DEBUG] 🚨 유튜브 CDN 오류 응답: ${response.code}")
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

            response.body?.byteStream()?.use { input ->
                input.copyTo(output)
            }
            output.flush()

        } catch (e: Exception) {
            println("[YT_PROXY_DEBUG] 프록시 릴레이 중 오류: ${e.message}")
        }
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val videoChunkId = UUID.randomUUID().toString()
        streamUrlCache[videoChunkId] = video.url
        val proxyVideoUrl = "http://127.0.0.1:$serverPort/chunk?id=$videoChunkId"
        
        val cleanVideoUrl = escapeXml(proxyVideoUrl)
        val durationString = "PT${durationSec}S"

        val sb = java.lang.StringBuilder(2048)
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        // mimeType과 codecs의 속성을 각 태그에 맞게 온전히 분할 적용
        sb.append("""
            <AdaptationSet mimeType="${video.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="${video.codec}">
                <BaseURL>$cleanVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val audioIdChunk = UUID.randomUUID().toString()
            streamUrlCache[audioIdChunk] = audio.url
            val proxyAudioUrl = "http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk"
            val cleanAudioUrl = escapeXml(proxyAudioUrl)

            val audioId = "audio_$index"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="${audio.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="${audio.codec}">
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
}
