package com.youtube

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.*
import kotlin.concurrent.thread

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        data class VideoStreamInfo(
            val quality: Int,
            val label: String,
            val mimeType: String,
            val initRange: String?,
            val indexRange: String?,
            var url: String
        )

        data class AudioStreamInfo(
            val bitrate: Int,
            val mimeType: String,
            val language: String,
            val initRange: String?,
            val indexRange: String?,
            var url: String
        )

        data class StreamSession(
            val videoId: String,
            var videoStreams: MutableList<VideoStreamInfo>,
            var audioStreams: MutableList<AudioStreamInfo>,
            val subtitles: List<SubtitlesStream>,
            val durationSec: Long,
            var lastRefreshMs: Long = 0L,
            val refreshLock: Any = Any()
        )

        private val sessions = ConcurrentHashMap<String, StreamSession>()
        private val sessionQueue = ConcurrentLinkedQueue<String>()
        private val videoIdToSession = ConcurrentHashMap<String, String>() // videoId → sessionId 매핑

        @Volatile private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        private val serverExecutor = ThreadPoolExecutor(
            2, 8, 30L, TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            ThreadFactory { r -> Thread(r, "YT-Dynamic-Proxy") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        init {
            YoutubeStreamExtractor.setFetchIosClient(true)
        }

        fun startServer() {
            synchronized(serverLock) {
                if (activeServer != null && !activeServer!!.isClosed) return
                try {
                    val server = ServerSocket(0)
                    activeServer = server
                    serverPort = server.localPort
                    thread(name = "YT-Proxy-Acceptor") {
                        while (true) {
                            val client = try {
                                server.accept()
                            } catch (e: Exception) { break }
                            client.soTimeout = 5000
                            serverExecutor.execute { handleClient(client) }
                        }
                    }
                } catch (e: Exception) { logError(e) }
            }
        }

        private fun handleClient(client: Socket) {
            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                    val output = socket.getOutputStream()
                    val requestLine = reader.readLine() ?: return
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return
                    val rawPath = parts[1]
                    val path = rawPath.substringBefore("?")

                    when {
                        path.matches(Regex("^/([a-f0-9\\-]+)\\.mpd$")) -> {
                            val sessionId = path.substring(1).removeSuffix(".mpd")
                            val session = sessions[sessionId]
                            if (session == null) {
                                sendResponse(output, 404, "Not Found")
                                return
                            }
                            val mpd = generateDynamicMpd(sessionId, session)
                            sendResponse(output, 200, mpd, "application/dash+xml")
                        }
                        path.startsWith("/proxy/") -> {
                            val partsPath = path.removePrefix("/proxy/").split("/")
                            if (partsPath.size < 3) {
                                sendResponse(output, 400, "Bad Request")
                                return
                            }
                            val sessionId = partsPath[0]
                            val type = partsPath[1]
                            val index = partsPath.getOrNull(2)?.toIntOrNull() ?: 0

                            val session = sessions[sessionId]
                            if (session == null) {
                                sendResponse(output, 404, "Session not found")
                                return
                            }
                            val freshUrl = getFreshStreamUrl(session, type, index)
                            if (freshUrl == null) {
                                sendResponse(output, 500, "Stream refresh failed")
                                return
                            }
                            val headers = mapOf("Location" to freshUrl, "Access-Control-Allow-Origin" to "*")
                            sendResponse(output, 302, "", headers = headers)
                        }
                        else -> sendResponse(output, 404, "Not Found")
                    }
                }
            } catch (_: Exception) {}
        }

        private fun sendResponse(output: java.io.OutputStream, code: Int, body: String,
                                 contentType: String? = null, headers: Map<String, String> = emptyMap()) {
            val writer = PrintWriter(output, true)
            val statusText = when (code) { 200 -> "OK"; 302 -> "Found"; 404 -> "Not Found"; else -> "" }
            writer.println("HTTP/1.1 $code $statusText")
            if (contentType != null) writer.println("Content-Type: $contentType")
            headers.forEach { (k, v) -> writer.println("$k: $v") }
            if (body.isNotEmpty()) {
                writer.println("Content-Length: ${body.toByteArray().size}")
                writer.println()
                writer.print(body)
            } else writer.println()
            writer.flush()
        }

        private fun getFreshStreamUrl(session: StreamSession, type: String, index: Int): String? {
            synchronized(session.refreshLock) {
                val now = System.currentTimeMillis()
                if (now - session.lastRefreshMs > 50_000) refreshSession(session)
                return if (type == "video") session.videoStreams.getOrNull(index)?.url
                else session.audioStreams.getOrNull(index)?.url
            }
        }

        private fun refreshSession(session: StreamSession) {
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance()
                    .fromUrl("https://www.youtube.com/watch?v=${session.videoId}")
                val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                extractor.fetchPage()

                session.videoStreams = extractor.videoOnlyStreams.orEmpty().mapNotNull { vs ->
                    val url = vs.content ?: return@mapNotNull null
                    VideoStreamInfo(
                        quality = vs.height ?: 0,
                        label = (vs.height ?: 0).toString(),
                        mimeType = vs.format?.mimeType ?: "video/mp4",
                        initRange = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null,
                        indexRange = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null,
                        url = url
                    )
                }.toMutableList()

                session.audioStreams = extractor.audioStreams.orEmpty().mapNotNull { as_ ->
                    val url = as_.content ?: return@mapNotNull null
                    val lang = as_.audioTrackId?.substringBefore(".") ?: "Default"
                    AudioStreamInfo(
                        bitrate = as_.bitrate ?: 128000,
                        mimeType = as_.format?.mimeType ?: "audio/mp4",
                        language = lang.uppercase(),
                        initRange = if (as_.initStart != null && as_.initEnd != null) "${as_.initStart}-${as_.initEnd}" else null,
                        indexRange = if (as_.indexStart != null && as_.indexEnd != null) "${as_.indexStart}-${as_.indexEnd}" else null,
                        url = url
                    )
                }.toMutableList()

                session.lastRefreshMs = System.currentTimeMillis()
            } catch (e: Exception) { logError(e) }
        }

        private fun generateDynamicMpd(sessionId: String, session: StreamSession): String {
            val baseProxy = "http://127.0.0.1:$serverPort/proxy/$sessionId"
            val sb = StringBuilder(2048)
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            sb.append("type=\"static\" minBufferTime=\"PT5.0S\" mediaPresentationDuration=\"PT${session.durationSec}S\">")
            sb.append("<Period>")

            if (session.videoStreams.isNotEmpty()) {
                val bestVideo = session.videoStreams.maxByOrNull { it.quality }!!
                sb.append("<AdaptationSet mimeType=\"${bestVideo.mimeType}\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
                sb.append("<Representation id=\"video\" bandwidth=\"4000000\" width=\"0\" height=\"${bestVideo.quality}\" codecs=\"${getCodec(bestVideo.mimeType, false)}\">")
                sb.append("<BaseURL>$baseProxy/video/0</BaseURL>")
                if (bestVideo.initRange != null && bestVideo.indexRange != null)
                    sb.append("<SegmentBase indexRange=\"${bestVideo.indexRange}\"><Initialization range=\"${bestVideo.initRange}\"/></SegmentBase>")
                sb.append("</Representation></AdaptationSet>")
            }

            for ((idx, audio) in session.audioStreams.withIndex()) {
                sb.append("<AdaptationSet mimeType=\"${audio.mimeType}\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
                sb.append("<Representation id=\"audio_${audio.language}\" bandwidth=\"${audio.bitrate}\" codecs=\"${getCodec(audio.mimeType, true)}\">")
                sb.append("<BaseURL>$baseProxy/audio/$idx</BaseURL>")
                if (audio.initRange != null && audio.indexRange != null)
                    sb.append("<SegmentBase indexRange=\"${audio.indexRange}\"><Initialization range=\"${audio.initRange}\"/></SegmentBase>")
                sb.append("</Representation></AdaptationSet>")
            }
            sb.append("</Period></MPD>")
            return sb.toString()
        }

        private fun getCodec(mime: String, isAudio: Boolean): String {
            return if (mime.contains("webm")) {
                if (isAudio) "opus" else "vp9"
            } else {
                if (isAudio) "mp4a.40.2" else "avc1.4d401f"
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        // 이미 존재하는 세션이면 재사용 (중복 추출 방지)
        var sessionId = videoIdToSession[videoId]
        var session: StreamSession? = if (sessionId != null) sessions[sessionId] else null

        if (session == null) {
            sessionId = UUID.randomUUID().toString()
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl("https://www.youtube.com/watch?v=$videoId")
            val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            extractor.fetchPage()

            val durationSec = if (extractor.length > 0) extractor.length else 3600L

            val videoStreams = extractor.videoOnlyStreams.orEmpty().mapNotNull { vs ->
                val streamUrl = vs.content ?: return@mapNotNull null
                VideoStreamInfo(
                    quality = vs.height ?: 0,
                    label = (vs.height ?: 0).toString(),
                    mimeType = vs.format?.mimeType ?: "video/mp4",
                    initRange = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null,
                    indexRange = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null,
                    url = streamUrl
                )
            }.toMutableList()

            val audioStreams = extractor.audioStreams.orEmpty().mapNotNull { as_ ->
                val streamUrl = as_.content ?: return@mapNotNull null
                val lang = as_.audioTrackId?.substringBefore(".") ?: "Default"
                AudioStreamInfo(
                    bitrate = as_.bitrate ?: 128000,
                    mimeType = as_.format?.mimeType ?: "audio/mp4",
                    language = lang.uppercase(),
                    initRange = if (as_.initStart != null && as_.initEnd != null) "${as_.initStart}-${as_.initEnd}" else null,
                    indexRange = if (as_.indexStart != null && as_.indexEnd != null) "${as_.indexStart}-${as_.indexEnd}" else null,
                    url = streamUrl
                )
            }.toMutableList()

            val subtitles = extractor.subtitlesDefault.orEmpty().filterNotNull()

            session = StreamSession(
                videoId = videoId,
                videoStreams = videoStreams,
                audioStreams = audioStreams,
                subtitles = subtitles,
                durationSec = durationSec,
                lastRefreshMs = System.currentTimeMillis()
            )

            sessions[sessionId] = session
            sessionQueue.add(sessionId)
            videoIdToSession[videoId] = sessionId

            // LRU 정리
            if (sessions.size > 50) {
                val old = sessionQueue.poll()
                if (old != null) {
                    val oldSession = sessions.remove(old)
                    if (oldSession != null) videoIdToSession.remove(oldSession.videoId)
                }
            }
        } else {
            // 기존 세션이 있으면 갱신 (50초 이상 지났으면)
            synchronized(session.refreshLock) {
                if (System.currentTimeMillis() - session.lastRefreshMs > 50_000) {
                    refreshSession(session)
                }
            }
            // sessionId는 그대로 유지
            sessionId = videoIdToSession[videoId]!!
        }

        startServer()
        val mpdUrl = "http://127.0.0.1:$serverPort/$sessionId.mpd"
        callback(
            newExtractorLink(
                source = name,
                name = "Dynamic DASH",
                url = mpdUrl,
                type = ExtractorLinkType.DASH
            ) {
                this.referer = mainUrl
                this.quality = session!!.videoStreams.maxOfOrNull { it.quality } ?: 720
                this.headers = mapOf(
                    "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com"
                )
            }
        )

        session!!.subtitles.mapNotNull { sub ->
            try {
                val lang = sub.locale?.language ?: return@mapNotNull null
                var subUrl = sub.content ?: sub.url ?: return@mapNotNull null
                if (subUrl.startsWith("http") && !subUrl.contains("fmt=srt")) subUrl += "&fmt=srt"
                newSubtitleFile(lang, subUrl)
            } catch (_: Exception) { null }
        }.forEach { subtitleCallback(it) }
    }
}
