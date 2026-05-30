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
import org.json.JSONObject
import org.json.JSONArray
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
            println("[YT_DEBUG_INIT] 독자 추출기 초기화 완료 (NewPipe 종속성 완전 제거)")
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

    private suspend fun fetchStreamingData(videoId: String, clientName: String): JSONObject? {
        val apiUrl = "https://www.youtube.com/youtubei/v1/player"
        
        // 클라이언트별 완벽한 스펙 매칭
        val (clientVersion, userAgent, osName, osVersion, clientId) = when (clientName) {
            "ANDROID_VR" -> listOf("1.65.10", YoutubeUtils.ANDROID_VR_USER_AGENT, "Android", "12L", "28")
            "IOS" -> listOf("19.45.4", YoutubeUtils.IOS_USER_AGENT, "iOS", "17.5.1.21F90", "5")
            else -> listOf("1.65.10", YoutubeUtils.ANDROID_VR_USER_AGENT, "Android", "12L", "28")
        }
        
        val payload = mapOf(
            "context" to mapOf(
                "client" to mapOf(
                    "clientName" to clientName,
                    "clientVersion" to clientVersion,
                    "hl" to "en",
                    "gl" to "US",
                    "osName" to osName,
                    "osVersion" to osVersion,
                    "userAgent" to userAgent
                )
            ),
            "videoId" to videoId
        )
        
        val headers = mapOf(
            "User-Agent" to userAgent,
            "X-YouTube-Client-Name" to clientId,
            "X-YouTube-Client-Version" to clientVersion,
            "Content-Type" to "application/json"
        )
        
        return try {
            val response = app.post(apiUrl, json = payload, headers = headers).text
            val root = JSONObject(response)
            
            val status = root.optJSONObject("playabilityStatus")?.optString("status")
            println("[YT_DEBUG_EXTRACTOR] API 호출 완료 ($clientName). PlayabilityStatus: $status")
            
            root.optJSONObject("streamingData")
        } catch (e: Exception) {
            println("[YT_DEBUG_EXTRACTOR] API 호출 에러 ($clientName): ${e.message}")
            null
        }
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
                // 1차 시도: ANDROID_VR (가장 강력한 403 우회)
                var clientType = "ANDROID_VR"
                var streamingData = fetchStreamingData(videoId, clientType)
                
                // 2차 시도: IOS (VR 실패 시 폴백)
                if (streamingData == null || !streamingData.has("adaptiveFormats")) {
                    println("[YT_DEBUG_EXTRACTOR] ANDROID_VR 추출 실패, IOS 클라이언트로 재시도...")
                    clientType = "IOS"
                    streamingData = fetchStreamingData(videoId, clientType)
                }

                if (streamingData == null) {
                    println("[YT_DEBUG_EXTRACTOR] 🚨 모든 클라이언트 우회 실패! streamingData 없음.")
                    return@withContext
                }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                
                val videoOnlyList = mutableListOf<StreamInfo>()
                val audioInfoList = mutableListOf<AudioInfo>()

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    if (streamUrl.isNullOrEmpty()) continue

                    val mimeType = format.optString("mimeType", "")
                    val initRange = format.optJSONObject("initRange")
                    val indexRange = format.optJSONObject("indexRange")

                    val initStart = initRange?.optString("start")
                    val initEnd = initRange?.optString("end")
                    val idxStart = indexRange?.optString("start")
                    val idxEnd = indexRange?.optString("end")

                    val initR = if (initStart != null && initEnd != null) "$initStart-$initEnd" else null
                    val indexR = if (idxStart != null && idxEnd != null) "$idxStart-$idxEnd" else null

                    if (mimeType.startsWith("video/")) {
                        val height = format.optInt("height", 0)
                        val label = if (height > 0) "${height}p" else "video"
                        videoOnlyList.add(StreamInfo(streamUrl, mimeType, height, label, initR, indexR))
                    } else if (mimeType.startsWith("audio/")) {
                        val bitrate = format.optInt("bitrate", 128000)
                        val audioTrack = format.optJSONObject("audioTrack")
                        val lang = audioTrack?.optString("id", "Default")?.uppercase() ?: "DEFAULT"
                        audioInfoList.add(AudioInfo(streamUrl, mimeType, bitrate, initR, indexR, lang))
                    }
                }

                println("[YT_DEBUG_EXTRACTOR] 파싱 완료 -> 비디오: ${videoOnlyList.size}개, 오디오: ${audioInfoList.size}개")

                // 자막 추출 로직
                val captions = streamingData.optJSONObject("captions")?.optJSONObject("playerCaptionsTracklistRenderer")?.optJSONArray("captionTracks")
                if (captions != null) {
                    for (i in 0 until captions.length()) {
                        val track = captions.getJSONObject(i)
                        val subUrl = track.optString("baseUrl")
                        val lang = track.optString("languageCode")
                        if (subUrl.isNotEmpty() && lang.isNotEmpty()) {
                            subtitleCallback(SubtitleFile(lang, subUrl))
                        }
                    }
                }

                if (videoOnlyList.isNotEmpty() && audioInfoList.isNotEmpty()) {
                    val audiosByLanguage = audioInfoList.groupBy { it.language }
                    startServerIfNeeded()

                    for (video in videoOnlyList.distinctBy { it.height }) {
                        for ((lang, audios) in audiosByLanguage) {
                            val bestAudio = if (video.mimeType.contains("webm")) {
                                audios.maxByOrNull { if (it.mimeType.contains("webm")) it.bitrate + 1000000 else it.bitrate }
                            } else {
                                audios.maxByOrNull { if (it.mimeType.contains("mp4")) it.bitrate + 1000000 else it.bitrate }
                            }

                            if (bestAudio != null) {
                                val dashXml = buildDashManifestXml(video, listOf(bestAudio), 3600L, clientType)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    val finalName = "${video.label} ($lang) ${video.label}"
                                    callback(
                                        newExtractorLink(
                                            "${this@YoutubeExtractor.name} ($lang)", finalName, localLink, type = ExtractorLinkType.DASH
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
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_PROXY_DEBUG] 가상 프록시 서버 포트 바인딩 완료: $serverPort")
                
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
            println("[YT_PROXY_DEBUG] handleClient 예외: ${e.message}")
        }
    }

    private fun proxyChunkToYouTube(socket: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
        try {
            val finalTargetUrl = targetUrl
            
            // [핵심 방어 로직] 스트림 URL에 포함된 "c=" (Client) 파라미터를 읽어서,
            // 유튜브 CDN이 봇으로 감지하지 못하도록 해당 클라이언트에 맞는 완벽한 User-Agent를 세팅합니다.
            val cParamMatch = Regex("[?&]c=([^&]+)").find(finalTargetUrl)
            val cParam = cParamMatch?.groupValues?.get(1)?.uppercase() ?: "ANDROID_VR"

            val dynamicUserAgent = when (cParam) {
                "ANDROID_VR" -> YoutubeUtils.ANDROID_VR_USER_AGENT
                "IOS" -> YoutubeUtils.IOS_USER_AGENT
                else -> YoutubeUtils.ANDROID_VR_USER_AGENT
            }

            val requestBuilder = okhttp3.Request.Builder()
                .url(finalTargetUrl)
                .header("User-Agent", dynamicUserAgent)
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

        } catch (e: Exception) {
            println("[YT_PROXY_DEBUG] 프록시 릴레이 중 예외 발생: ${e.message}")
        }
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long,
        clientType: String
    ): String {
        val videoChunkId = UUID.randomUUID().toString()
        // 프록시 서버가 클라이언트 타입을 알 수 있도록 c 파라미터 강제 삽입
        val safeVideoUrl = if (video.url.contains("c=")) video.url else "${video.url}&c=$clientType"
        streamUrlCache[videoChunkId] = safeVideoUrl
        val proxyVideoUrl = "http://127.0.0.1:$serverPort/chunk?id=$videoChunkId"
        
        val cleanVideoUrl = escapeXml(proxyVideoUrl)
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
            val audioIdChunk = UUID.randomUUID().toString()
            val safeAudioUrl = if (audio.url.contains("c=")) audio.url else "${audio.url}&c=$clientType"
            streamUrlCache[audioIdChunk] = safeAudioUrl
            val proxyAudioUrl = "http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk"
            val cleanAudioUrl = escapeXml(proxyAudioUrl)

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
}
