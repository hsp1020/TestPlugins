package com.youtube

import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import kotlinx.coroutines.runBlocking
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private var ytVideos: MutableMap<String, List<ExtractorLink>> = mutableMapOf()
        private var ytVideosSubtitles: MutableMap<String, List<SubtitlesStream>> = mutableMapOf()

        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val manifestMap = ConcurrentHashMap<String, String>()
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
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = try { cleanedUrl.substringAfter("v=").substringBefore("&") } catch (e: Exception) { cleanedUrl }

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        if (ytVideos[videoId].isNullOrEmpty()) {
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = mutableSetOf<String>()

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
                    ytVideosSubtitles[videoId] = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                } catch (e: Exception) { }

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
                        }
                    )
                }

                ytVideos[videoId] = builtLinks.toList()

            } catch (e: Exception) { logError(e) }
        }

        ytVideos[videoId]?.forEach { callback(it) }

        startServerIfNeeded()

        ytVideosSubtitles[videoId]?.forEach { ss ->
            try {
                val lang = ss.locale?.language ?: return@forEach
                val rawUrl = ss.content ?: ss.getUrl() ?: return@forEach

                if (rawUrl.startsWith("http")) {
                    val cleanUrl = rawUrl.replace(Regex("&fmt=[^&]+"), "")

                    val srtUrl = "$cleanUrl&fmt=srt"
                    val originalName = ss.locale?.getDisplayLanguage(java.util.Locale.getDefault()) ?: lang
                    subtitleCallback(SubtitleFile(originalName, srtUrl))

                    val autoLangs = listOf("ko")

                    for (targetLang in autoLangs) {
                        if (targetLang.equals(lang, true)) continue

                        val autoVttUrl = "$cleanUrl&fmt=vtt&tlang=$targetLang"

                        val proxyId = UUID.randomUUID().toString()
                        manifestMap[proxyId] = autoVttUrl
                        val localAutoUrl = "http://127.0.0.1:$serverPort/sub_$proxyId.vtt"

                        subtitleCallback(SubtitleFile("$lang → $targetLang", localAutoUrl))
                    }
                }
            } catch (e: Exception) { }
        }
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            thread {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = activeServer!!.accept()
                        thread { handleClient(client) }
                    }
                } catch (e: Exception) {}
            }
        } catch (e: Exception) { logError(e) }
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val out = socket.getOutputStream()
                val line = reader.readLine() ?: return

                if (line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)

                        // 1. 자막(VTT) 프록시 요청 처리
                        if (path.startsWith("sub_")) {
                            val id = path.substringAfter("sub_").substringBefore(".vtt")
                            val targetUrl = manifestMap[id]

                            if (targetUrl != null) {
                                try {
                                    // 봇 차단을 우회하기 위한 헤더 즉석 생성
                                    val context = AcraApplication.context
                                    val prefs = context?.let { PreferenceManager.getDefaultSharedPreferences(it) }
                                    val visitor = prefs?.getString("VISITOR_INFO1_LIVE", "_Mk3UVhY40g") ?: "_Mk3UVhY40g"
                                    val sapisid = prefs?.getString("SAPISID", null)

                                    val cookieBuilder = StringBuilder()
                                    cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; PREF=f6=40000000&hl=ko; CONSENT=YES+fx.456722336; ")
                                    if (!sapisid.isNullOrBlank()) {
                                        cookieBuilder.append("SAPISID=$sapisid; ")
                                    }

                                    val requestHeaders = mutableMapOf(
                                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36",
                                        "Accept-Language" to "ko,en-US;q=0.9,en;q=0.8",
                                        "Cookie" to cookieBuilder.toString(),
                                        "Origin" to "https://www.youtube.com",
                                        "Referer" to "https://www.youtube.com/"
                                    )

                                    // SAPISID 해시 생성 (세션 인증)
                                    if (!sapisid.isNullOrBlank()) {
                                        try {
                                            val timestamp = (System.currentTimeMillis() / 1000).toString()
                                            val md = java.security.MessageDigest.getInstance("SHA-1")
                                            val msg = "$timestamp $sapisid https://www.youtube.com"
                                            val hash = md.digest(msg.toByteArray()).joinToString("") { "%02x".format(it) }
                                            requestHeaders["Authorization"] = "SAPISIDHASH ${timestamp}_$hash"
                                            requestHeaders["X-Origin"] = "https://www.youtube.com"
                                            requestHeaders["X-Goog-AuthUser"] = "0"
                                        } catch (e: Exception) { }
                                    }

                                    // 생성한 헤더를 기반으로 안전하게 텍스트 호출
                                    val vttText = runBlocking {
                                        app.get(targetUrl, headers = requestHeaders).text
                                    }

                                    // 유튜브 봇 차단 HTML 감지 (크래시 방지)
                                    if (vttText.trim().startsWith("<html", ignoreCase = true)) {
                                        sendError(out, 500, "Blocked by YouTube Bot Protection")
                                        return
                                    }

                                    var finalVtt = vttText.trim()
                                    // WEBVTT 헤더가 누락된 채 왔다면 강제 주입
                                    if (!finalVtt.contains("WEBVTT")) {
                                        finalVtt = "WEBVTT\n\n$finalVtt"
                                    }

                                    // VTT 찌꺼기 위치 태그 및 포맷팅 완벽 청소
                                    val cleanedVtt = finalVtt
                                        .replace(Regex("(align|position|line|size|c\\.color[A-Za-z0-9]+):[^\\s]+"), "")
                                        .replace(Regex("<[^>]*>"), "")

                                    sendSuccessResponse(out, "text/vtt; charset=utf-8", cleanedVtt)
                                } catch (e: Exception) {
                                    sendError(out, 500, "Internal Server Error")
                                }
                            } else {
                                sendError(out, 404, "Not Found")
                            }
                            return
                        }

                        // 2. MPD 매니페스트 프록시 요청 처리
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path]
                        if (content != null) {
                            sendSuccessResponse(out, "application/dash+xml", content)
                        } else {
                            sendError(out, 404, "Not Found")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 소켓 파이프라인 에러 무시
        }
    }

    private fun sendSuccessResponse(out: OutputStream, contentType: String, content: String) {
        val responseBytes = content.toByteArray(Charsets.UTF_8)
        val headers = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${responseBytes.size}\r\n" +
                "Connection: close\r\n" +
                "Access-Control-Allow-Origin: *\r\n\r\n"

        out.write(headers.toByteArray(Charsets.UTF_8))
        out.write(responseBytes)
        out.flush()
    }

    private fun sendError(out: OutputStream, code: Int, message: String) {
        val response = "HTTP/1.1 $code $message\r\nConnection: close\r\n\r\n"
        out.write(response.toByteArray(Charsets.UTF_8))
        out.flush()
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val cleanVideoUrl = escapeXml(video.url)
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder()
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
        return url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun getMimeTypeFromUrl(url: String, isAudio: Boolean): String {
        return try {
            val decoded = URLDecoder.decode(url, "UTF-8")
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
