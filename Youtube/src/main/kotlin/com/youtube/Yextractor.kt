package com.youtube

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
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.UUID
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.*
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private val ytVideos: MutableMap<String, List<ExtractorLink>> = Collections.synchronizedMap(
            object : LinkedHashMap<String, List<ExtractorLink>>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExtractorLink>>?): Boolean = size > 50
            }
        )
        private val ytVideosSubtitles: MutableMap<String, List<SubtitlesStream>> = Collections.synchronizedMap(
            object : LinkedHashMap<String, List<SubtitlesStream>>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubtitlesStream>>?): Boolean = size > 50
            }
        )

        @Volatile private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 1000
            }
        )

        private val serverExecutor = ThreadPoolExecutor(
            0, 32, 60L, TimeUnit.SECONDS,
            SynchronousQueue(),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        private val proxyClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        /**
         * PO Token 문제를 해결하기 위해 WebView 기반 PoTokenProvider를 등록합니다.
         * 이 설정이 적용되면, NewPipe Extractor는 자동으로 PO Token을 생성하여
         * GVS 요청에 포함시킵니다.
         */
        init {
            YoutubeStreamExtractor.setFetchIosClient(true)
            // PO Token Provider 등록
            YoutubeStreamExtractor.setPoTokenProvider(WebViewPoTokenProvider())
            println("[YT_DEBUG] WebViewPoTokenProvider registered for iOS client PO token generation")
        }
    }

    override fun getExtractorUrl(id: String): String = "$mainUrl/watch?v=$id"

    data class StreamInfo(
        val url: String, val mimeType: String, val height: Int,
        val label: String, val initRange: String?, val indexRange: String?
    )
    data class AudioInfo(
        val url: String, val mimeType: String, val bitrate: Int,
        val initRange: String?, val indexRange: String?, val language: String
    )

    override suspend fun getUrl(
        url: String, referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            try {
                println("[YT_DEBUG] getUrl called. videoId=$videoId")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = ConcurrentHashMap.newKeySet<String>()

                val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    vs.content?.let { streamUrl ->
                        if (!seenUrls.add(streamUrl)) null
                        else {
                            val mime = vs.format?.mimeType ?: getMimeTypeFromUrl(streamUrl, false)
                            StreamInfo(
                                url = streamUrl, mimeType = mime,
                                height = vs.height, label = (vs.height ?: 0).toString(),
                                initRange = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null,
                                indexRange = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null
                            )
                        }
                    }
                }.distinctBy { it.height }

                val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                    asr.content?.let { aUrl ->
                        val bitrate = asr.bitrate ?: 128000
                        val mime = asr.format?.mimeType ?: getMimeTypeFromUrl(aUrl, true)
                        val rawLang = (asr.audioTrackId ?: "Default").substringBefore(".")
                        AudioInfo(
                            url = aUrl, mimeType = mime, bitrate = bitrate,
                            initRange = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null,
                            indexRange = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null,
                            language = rawLang.uppercase()
                        )
                    }
                }.distinctBy { it.url }

                println("[YT_DEBUG] Extracted ${videoOnlyList.size} video streams, ${audioInfoList.size} audio streams")

                try {
                    ytVideosSubtitles[videoId] = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                } catch (e: Exception) {
                    logError(e)
                }

                startServerIfNeeded()

                val audiosByLanguage = audioInfoList.groupBy { it.language }
                for (video in videoOnlyList) {
                    val bestAudio = audiosByLanguage.values.flatten().maxByOrNull { it.bitrate }
                    if (bestAudio != null) {
                        val dashXml = buildDashManifestXml(video, listOf(bestAudio), durationSeconds)
                        val localLink = registerManifestAndGetUrl(dashXml)
                        if (localLink != null) {
                            builtLinks.add(
                                newExtractorLink(
                                    "${this.name} (${bestAudio.language})",
                                    "${video.label} (${bestAudio.language})",
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

                builtLinks.add(
                    newExtractorLink("YouTube Downloader", "Cobalt Web Download(4K)", "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) {
                        this.quality = 2160; this.referer = mainUrl
                    }
                )
                builtLinks.add(
                    newExtractorLink("YouTube Downloader", "SOSYouTube Web Download(1080p)", "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) {
                        this.quality = 2160; this.referer = mainUrl
                    }
                )

                ytVideos[videoId] = builtLinks.toList()
            } catch (e: Exception) {
                logError(e)
            }
        }

        ytVideos[videoId]?.forEach { callback(it) }
        ytVideosSubtitles[videoId]?.mapNotNull { sub ->
            try {
                val lang = sub.locale?.language ?: return@mapNotNull null
                var subUrl = sub.content ?: sub.getUrl() ?: return@mapNotNull null
                if (subUrl.startsWith("http")) {
                    subUrl = subUrl.replace(Regex("&fmt=[^&]+"), "")
                    if (!subUrl.contains("fmt=srt")) subUrl += "&fmt=srt"
                }
                newSubtitleFile(lang, subUrl)
            } catch (_: Exception) { null }
        }?.forEach { subtitleCallback(it) }
    }

    // --- 로컬 서버 관련 코드 (기존과 동일) ---
    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            if (activeServer != null && !activeServer!!.isClosed) return
            try {
                val server = ServerSocket(0)
                activeServer = server
                serverPort = server.localPort
                println("[YT_DEBUG] Server started on port $serverPort")
                thread(name = "YT-DASH-Server-Acceptor", isDaemon = true) {
                    while (true) {
                        val client = try { server.accept() } catch (_: Exception) { break }
                        client.soTimeout = 15000
                        serverExecutor.execute { handleClient(client) }
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine() ?: return
                if (!line.startsWith("GET")) return
                val parts = line.split(" ")
                if (parts.size < 2) return
                val requestPath = parts[1]
                val cleanPath = requestPath.substringAfter("/").substringBefore("?")

                // 헤더 읽기
                val headers = mutableMapOf<String, String>()
                while (true) {
                    val headerLine = reader.readLine()?.takeIf { it.isNotBlank() } ?: break
                    val idx = headerLine.indexOf(":")
                    if (idx > 0) headers[headerLine.substring(0, idx).trim().lowercase()] = headerLine.substring(idx + 1).trim()
                }

                if (cleanPath.startsWith("proxy", ignoreCase = true)) {
                    val query = requestPath.substringAfter("?")
                    val params = parseQueryParams(query)
                    val targetUrl = params["url"]
                    if (!targetUrl.isNullOrBlank()) {
                        handleProxyRequest(socket, targetUrl, headers)
                    } else {
                        send404(socket)
                    }
                } else {
                    val path = if (cleanPath.endsWith(".mpd", ignoreCase = true)) cleanPath.substring(0, cleanPath.length - 4) else cleanPath
                    val content = manifestMap[path]
                    val output = PrintWriter(socket.getOutputStream(), true)
                    if (content != null) {
                        output.println("HTTP/1.1 200 OK")
                        output.println("Content-Type: application/dash+xml")
                        output.println("Connection: close")
                        output.println("Access-Control-Allow-Origin: *")
                        output.println()
                        output.println(content)
                    } else {
                        output.println("HTTP/1.1 404 Not Found")
                        output.println()
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun handleProxyRequest(client: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
        try {
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", YoutubeUtils.IOS_USER_AGENT)
                .header("Referer", "https://www.youtube.com")
                .header("Origin", "https://www.youtube.com")
            clientHeaders["range"]?.let { requestBuilder.header("Range", it) }

            proxyClient.newCall(requestBuilder.build()).execute().use { response ->
                val outputStream = client.getOutputStream()
                val headerBytes = StringBuilder()
                headerBytes.append("HTTP/1.1 ${response.code} ${response.message}\r\n")
                response.header("Content-Type")?.let { headerBytes.append("Content-Type: $it\r\n") }
                response.header("Content-Length")?.let { headerBytes.append("Content-Length: $it\r\n") }
                response.header("Content-Range")?.let { headerBytes.append("Content-Range: $it\r\n") }
                response.header("Accept-Ranges")?.let { headerBytes.append("Accept-Ranges: $it\r\n") }
                headerBytes.append("Connection: close\r\n")
                headerBytes.append("Access-Control-Allow-Origin: *\r\n")
                headerBytes.append("\r\n")
                outputStream.write(headerBytes.toString().toByteArray(Charsets.UTF_8))
                outputStream.flush()

                response.body?.byteStream()?.use { inputStream ->
                    val buffer = ByteArray(32 * 1024)
                    var bytesRead: Int
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                    }
                }
                outputStream.flush()
            }
        } catch (_: Exception) {
            try {
                val errorResponse = "HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n"
                client.getOutputStream().write(errorResponse.toByteArray(Charsets.UTF_8))
                client.getOutputStream().flush()
            } catch (_: Exception) {}
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) params[pair.substring(0, idx)] = YoutubeUtils.urlDecode(pair.substring(idx + 1))
        }
        return params
    }

    private fun buildDashManifestXml(video: StreamInfo, audioList: List<AudioInfo>, durationSec: Long): String {
        val localVideoUrl = escapeXml("http://127.0.0.1:$serverPort/proxy?url=${URLEncoder.encode(video.url, "UTF-8")}")
        val durationString = "PT${durationSec}S"

        val sb = StringBuilder(2048)
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
                <BaseURL>$localVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val localAudioUrl = escapeXml("http://127.0.0.1:$serverPort/proxy?url=${URLEncoder.encode(audio.url, "UTF-8")}")
            val aMime = audio.mimeType
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="audio_$index" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="$aCodecs">
                    <BaseURL>$localAudioUrl</BaseURL>
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
        val sb = StringBuilder(url.length + 16)
        url.forEach { c ->
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
            if (decoded.contains("video/webm") || decoded.contains("audio/webm"))
                if (isAudio) "audio/webm" else "video/webm"
            else
                if (isAudio) "audio/mp4" else "video/mp4"
        } catch (_: Exception) { if (isAudio) "audio/mp4" else "video/mp4" }
    }

    private fun send404(socket: Socket) {
        try { PrintWriter(socket.getOutputStream(), true).println("HTTP/1.1 404 Not Found\r\n\r\n") } catch (_: Exception) {}
    }
}
