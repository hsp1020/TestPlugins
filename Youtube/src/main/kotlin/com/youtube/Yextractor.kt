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
        // 데이터 모델
        data class VideoStreamInfo(
            val quality: Int,
            val label: String,
            val mimeType: String,
            val initRange: String?,
            val indexRange: String?,
            var url: String                      // 현재 스트리밍 URL (갱신됨)
        )

        data class AudioStreamInfo(
            val bitrate: Int,
            val mimeType: String,
            val language: String,
            val initRange: String?,
            val indexRange: String?,
            var url: String                      // 현재 스트리밍 URL (갱신됨)
        )

        data class StreamSession(
            val videoId: String,
            var videoStreams: MutableList<VideoStreamInfo>,   // 갱신 가능
            var audioStreams: MutableList<AudioStreamInfo>,
            val subtitles: List<SubtitlesStream>,
            val durationSec: Long,
            var lastRefreshMs: Long = 0L,
            val refreshLock: Any = Any()
        )

        private val sessions = ConcurrentHashMap<String, StreamSession>()  // sessionId → 세션
        private val sessionQueue = ConcurrentLinkedQueue<String>()         // LRU 관리

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
            YoutubeStreamExtractor.setFetchIosClient(true)   // 서명 해독 없이 직링크 획득
        }

        // 서버 시작 (싱글톤)
        fun startServer() {
            synchronized(serverLock) {
                if (activeServer != null && !activeServer!!.isClosed) return
                try {
                    val server = ServerSocket(0)
                    activeServer = server
                    serverPort = server.localPort
                    println("[YT_PROXY] Dynamic DASH proxy started on port $serverPort")
                    thread(name = "YT-Proxy-Acceptor") {
                        while (true) {
                            val client = try {
                                server.accept()
                            } catch (e: Exception) {
                                break
                            }
                            client.soTimeout = 5000
                            serverExecutor.execute { handleClient(client) }
                        }
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }
        }

        // 클라이언트 요청 처리
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
                        // MPD 요청
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
                        // 미디어 세그먼트 프록시 → 302 리디렉션
                        path.startsWith("/proxy/") -> {
                            val partsPath = path.removePrefix("/proxy/").split("/")
                            if (partsPath.size < 3) {
                                sendResponse(output, 400, "Bad Request")
                                return
                            }
                            val sessionId = partsPath[0]
                            val type = partsPath[1]       // "video" or "audio"
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

                            // 302 Redirect to actual CDN URL (Range 헤더 등은 클라이언트가 따라감)
                            val headers = mapOf("Location" to freshUrl, "Access-Control-Allow-Origin" to "*")
                            sendResponse(output, 302, "", headers = headers)
                        }
                        else -> sendResponse(output, 404, "Not Found")
                    }
                }
            } catch (e: Exception) {
                // ignore
            }
        }

        private fun sendResponse(output: java.io.OutputStream, code: Int, body: String,
                                 contentType: String? = null, headers: Map<String, String> = emptyMap()) {
            val writer = PrintWriter(output, true)
            val statusText = when (code) {
                200 -> "OK"
                302 -> "Found"
                404 -> "Not Found"
                else -> ""
            }
            writer.println("HTTP/1.1 $code $statusText")
            if (contentType != null) writer.println("Content-Type: $contentType")
            headers.forEach { (k, v) -> writer.println("$k: $v") }
            if (body.isNotEmpty()) {
                writer.println("Content-Length: ${body.toByteArray().size}")
                writer.println()
                writer.print(body)
            } else {
                writer.println()
            }
            writer.flush()
        }

        // 최신 스트리밍 URL 획득 (필요 시 갱신)
        private fun getFreshStreamUrl(session: StreamSession, type: String, index: Int): String? {
            synchronized(session.refreshLock) {
                val now = System.currentTimeMillis()
                if (now - session.lastRefreshMs > 50_000) {   // 50초 이상 경과 → 갱신
                    refreshSession(session)
                }
                return if (type == "video") {
                    session.videoStreams.getOrNull(index)?.url
                } else {
                    session.audioStreams.getOrNull(index)?.url
                }
            }
        }

        // 세션 갱신: NewPipe 재추출 → URL 업데이트
        private fun refreshSession(session: StreamSession) {
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance()
                    .fromUrl("https://www.youtube.com/watch?v=${session.videoId}")
                val extractor = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                extractor.fetchPage()

                // 비디오 스트림 갱신
                val newVideoStreams = extractor.videoOnlyStreams.orEmpty().mapNotNull { vs ->
                    val url = vs.content ?: return@mapNotNull null
                    val height = vs.height ?: 0
                    VideoStreamInfo(
                        quality = height,
                        label = height.toString(),
                        mimeType = vs.format?.mimeType ?: "video/mp4",
                        initRange = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null,
                        indexRange = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null,
                        url = url
                    )
                }.toMutableList()

                // 오디오 스트림 갱신
                val newAudioStreams = extractor.audioStreams.orEmpty().mapNotNull { as_ ->
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

                session.videoStreams = newVideoStreams
                session.audioStreams = newAudioStreams
                session.lastRefreshMs = System.currentTimeMillis()
            } catch (e: Exception) {
                logError(e)
            }
        }

        // 동적 MPD 생성 (BaseURL은 내부 프록시)
        private fun generateDynamicMpd(sessionId: String, session: StreamSession): String {
            val baseProxy = "http://127.0.0.1:$serverPort/proxy/$sessionId"
            val sb = StringBuilder(2048)
            sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>")
            sb.append("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\" profiles=\"urn:mpeg:dash:profile:isoff-on-demand:2011\" ")
            sb.append("type=\"static\" minBufferTime=\"PT5.0S\" mediaPresentationDuration=\"PT${session.durationSec}S\">")
            sb.append("<Period>")

            // 비디오 AdaptationSet (대표 1개)
            if (session.videoStreams.isNotEmpty()) {
                val bestVideo = session.videoStreams.maxByOrNull { it.quality }!!
                sb.append("<AdaptationSet mimeType=\"${bestVideo.mimeType}\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
                sb.append("<Representation id=\"video\" bandwidth=\"4000000\" width=\"0\" height=\"${bestVideo.quality}\" codecs=\"${getCodec(bestVideo.mimeType, false)}\">")
                sb.append("<BaseURL>$baseProxy/video/0</BaseURL>")
                if (bestVideo.initRange != null && bestVideo.indexRange != null) {
                    sb.append("<SegmentBase indexRange=\"${bestVideo.indexRange}\"><Initialization range=\"${bestVideo.initRange}\"/></SegmentBase>")
                }
                sb.append("</Representation></AdaptationSet>")
            }

            // 오디오 AdaptationSet (언어별)
            for ((idx, audio) in session.audioStreams.withIndex()) {
                sb.append("<AdaptationSet mimeType=\"${audio.mimeType}\" subsegmentAlignment=\"true\" subsegmentStartsWithSAP=\"1\">")
                sb.append("<Representation id=\"audio_${audio.language}\" bandwidth=\"${audio.bitrate}\" codecs=\"${getCodec(audio.mimeType, true)}\">")
                sb.append("<BaseURL>$baseProxy/audio/$idx</BaseURL>")
                if (audio.initRange != null && audio.indexRange != null) {
                    sb.append("<SegmentBase indexRange=\"${audio.indexRange}\"><Initialization range=\"${audio.initRange}\"/></SegmentBase>")
                }
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

    // ---- ExtractorApi 구현 ----
    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        // 세션 생성 (이미 존재하면 재사용하지 않고 새로 생성 – 단, 중복 방지하려면 videoId 기반 검색 가능)
        // 여기서는 단순하게 새 세션으로 대체 (기존 것은 LRU에 의해 정리)
        val sessionId = UUID.randomUUID().toString()

        // NewPipe 추출
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

        val session = StreamSession(
            videoId = videoId,
            videoStreams = videoStreams,
            audioStreams = audioStreams,
            subtitles = subtitles,
            durationSec = durationSec,
            lastRefreshMs = System.currentTimeMillis()
        )

        // 세션 저장 (LRU)
        sessions[sessionId] = session
        sessionQueue.add(sessionId)
        if (sessions.size > 50) {
            val old = sessionQueue.poll()
            if (old != null) sessions.remove(old)
        }

        // 동적 프록시 서버 시작
        startServer()

        // MPD URL 콜백 (하나의 링크만 보내고, 내부에서 모든 트랙 제공)
        val mpdUrl = "http://127.0.0.1:$serverPort/$sessionId.mpd"
        callback(
            newExtractorLink(
                source = name,
                name = "Dynamic DASH",
                url = mpdUrl,
                type = ExtractorLinkType.DASH
            ) {
                this.referer = mainUrl
                this.quality = videoStreams.maxOfOrNull { it.quality } ?: 720
                this.headers = mapOf(
                    "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com"
                )
            }
        )

        // 자막 콜백
        subtitles.mapNotNull { sub ->
            try {
                val lang = sub.locale?.language ?: return@mapNotNull null
                var subUrl = sub.content ?: sub.url ?: return@mapNotNull null
                if (subUrl.startsWith("http") && !subUrl.contains("fmt=srt")) {
                    subUrl += "&fmt=srt"
                }
                newSubtitleFile(lang, subUrl)
            } catch (e: Exception) {
                null
            }
        }.forEach { subtitleCallback(it) }
    }
}
