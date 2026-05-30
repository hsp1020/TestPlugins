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
            println("[YT_DEBUG_INIT] 독자 API 추출기 초기화 완료 (더빙, 자막, 포맷팅 완벽 복구)")
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
        val languageId: String,
        val displayName: String // 더빙/원본 구분을 위한 이름
    )

    private suspend fun fetchStreamingData(videoId: String): Pair<JSONObject?, Long> {
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
                    "hl" to "ko", // 자막 및 더빙 트랙 이름을 한국어로 받기 위해 ko 설정
                    "gl" to "KR",
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
            "X-YouTube-Client-Name" to "28", // 28 = ANDROID_VR
            "X-YouTube-Client-Version" to "1.65.10",
            "Content-Type" to "application/json"
        )

        try {
            val vrRes = app.post(apiUrl, json = vrPayload, headers = vrHeaders).text
            val vrJson = JSONObject(vrRes)
            val status = vrJson.optJSONObject("playabilityStatus")?.optString("status")
            val lengthSeconds = vrJson.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
            
            if (status == "OK" || vrJson.has("streamingData")) {
                return Pair(vrJson.optJSONObject("streamingData"), lengthSeconds)
            } else if (status == "UNPLAYABLE" || status == "LOGIN_REQUIRED") {
                val webApiKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""").find(html)?.groupValues?.get(1)
                
                if (!webApiKey.isNullOrEmpty()) {
                    val tvApiUrl = "https://www.youtube.com/youtubei/v1/player?key=$webApiKey"
                    val tvPayload = mapOf(
                        "videoId" to videoId,
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
                                "clientVersion" to "2.0",
                                "hl" to "ko",
                                "gl" to "KR",
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
                    return Pair(tvJson.optJSONObject("streamingData"), tvLengthSeconds)
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
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        withContext(Dispatchers.IO) {
            try {
                val (streamingData, durationSeconds) = fetchStreamingData(videoId)

                if (streamingData == null) {
                    callback(newExtractorLink(source = "YouTube Downloader", name = "Cobalt Web Download(4K)", url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                    callback(newExtractorLink(source = "YouTube Downloader", name = "SOSYouTube Web Download(1080p)", url = "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                    return@withContext
                }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                
                val videoOnlyList = mutableListOf<StreamInfo>()
                val audioInfoList = mutableListOf<AudioInfo>()

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    
                    if (streamUrl.isNullOrEmpty() || format.has("signatureCipher")) continue

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
                        
                        // [보고서 1, 2번 개선] 오디오 트랙 이름과 언어 코드 정제
                        val audioTrack = format.optJSONObject("audioTrack")
                        val rawId = audioTrack?.optString("id", "Default") ?: "Default"
                        // EN.4 -> EN 으로 정제
                        val langId = if (rawId.contains(".")) rawId.substringBefore(".").uppercase() else rawId.uppercase()
                        // Korean (Dubbed) 같은 친절한 이름을 가져오고, 없으면 langId 사용
                        val displayName = audioTrack?.optString("displayName", langId) ?: langId
                        
                        if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "opus" else "mp4a.40.2"
                        
                        audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, langId, displayName))
                    }
                }

                // [보고서 3번 개선] 자막 및 구글 자동 번역 자막 완벽 복구
                val captionsData = streamingData.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")
                if (captionsData != null) {
                    val captionTracks = captionsData.optJSONArray("captionTracks") ?: JSONArray()
                    val translationLanguages = captionsData.optJSONArray("translationLanguages") ?: JSONArray()

                    for (i in 0 until captionTracks.length()) {
                        val track = captionTracks.getJSONObject(i)
                        var subUrl = track.optString("baseUrl")
                        val langCode = track.optString("languageCode")
                        val nameObj = track.optJSONObject("name")
                        val subName = nameObj?.optString("simpleText", langCode) ?: langCode

                        if (subUrl.isNotEmpty()) {
                            // 핵심: SRT 포맷 강제 변환
                            subUrl = subUrl.replace(Regex("&fmt=[^&]+"), "")
                            if (!subUrl.contains("fmt=srt")) {
                                subUrl += "&fmt=srt"
                            }
                            
                            subtitleCallback(SubtitleFile(subName, subUrl))

                            // [핵심] 첫 번째 자막 트랙(보통 자동생성 또는 기본)을 이용해 구글 번역 자막 생성
                            if (i == 0 && translationLanguages.length() > 0) {
                                for (j in 0 until translationLanguages.length()) {
                                    val transLang = translationLanguages.getJSONObject(j)
                                    val tLangCode = transLang.optString("languageCode")
                                    val tNameObj = transLang.optJSONObject("languageName")
                                    val tName = tNameObj?.optString("simpleText", tLangCode) ?: tLangCode

                                    val transUrl = "$subUrl&tlang=$tLangCode"
                                    subtitleCallback(SubtitleFile("$tName (번역)", transUrl))
                                }
                            }
                        }
                    }
                }

                if (videoOnlyList.isNotEmpty() && audioInfoList.isNotEmpty()) {
                    // [보고서 2번 개선] displayName(예: Korean (Dubbed)) 기준으로 오디오 그룹화
                    val audiosByName = audioInfoList.groupBy { it.displayName }
                    startServerIfNeeded()

                    for (video in videoOnlyList.distinctBy { it.height }) {
                        for ((audioName, audios) in audiosByName) {
                            val bestAudio = if (video.mimeType.contains("webm")) {
                                audios.maxByOrNull { if (it.mimeType.contains("webm")) it.bitrate + 1000000 else it.bitrate }
                            } else {
                                audios.maxByOrNull { if (it.mimeType.contains("mp4")) it.bitrate + 1000000 else it.bitrate }
                            }

                            if (bestAudio != null) {
                                val dashXml = buildDashManifestXml(video, listOf(bestAudio), durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    // [보고서 1번 개선] 소스명과 화질 라벨을 깔끔하게 분리
                                    val sourceName = "${this@YoutubeExtractor.name} ($audioName)"
                                    val finalName = "${video.height}p"
                                    
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

                callback(newExtractorLink(source = "YouTube Downloader", name = "Cobalt Web Download(4K)", url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                callback(newExtractorLink(source = "YouTube Downloader", name = "SOSYouTube Web Download(1080p)", url = "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

            } catch (e: Exception) { 
                logError(e) 
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
        } catch (e: Exception) { }
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

        } catch (e: Exception) { }
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
