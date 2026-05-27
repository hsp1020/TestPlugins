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
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
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
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<ExtractorLink>>?): Boolean {
                    return size > 50
                }
            }
        )

        private val ytVideosSubtitles: MutableMap<String, List<SubtitlesStream>> = Collections.synchronizedMap(
            object : LinkedHashMap<String, List<SubtitlesStream>>(32, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SubtitlesStream>>?): Boolean {
                    return size > 50
                }
            }
        )

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

        private val serverExecutor = ThreadPoolExecutor(
            0,
            32,
            60L,
            TimeUnit.SECONDS,
            SynchronousQueue<Runnable>(),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        private val proxyClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()

        init {
            YoutubeStreamExtractor.setFetchIosClient(true)
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
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl
        println("[YT_DEBUG] getUrl called. videoId=$videoId")

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            try {
                println("[YT_DEBUG] No cache, extracting streams via NewPipe...")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()
                println("[YT_DEBUG] fetchPage done. Duration=${s.length}")

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

                val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val streamUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(streamUrl)) return@mapNotNull null

                        val label = buildVideoLabelNumber(vs)
                        val height = vs.height ?: 0
                        var mime = vs.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)

                        val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                        println("[YT_DEBUG] Video stream: height=$height, mime=$mime, url=${streamUrl.take(70)}...")
                        StreamInfo(streamUrl, mime, height, label, initR, indexR)
                    } catch (e: Exception) { null }
                }.distinctBy { it.height }
                println("[YT_DEBUG] Total distinct video streams: ${videoOnlyList.size}")

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

                        println("[YT_DEBUG] Audio stream: lang=$rawLang, bitrate=$bitrate, url=${aUrl.take(70)}...")
                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { null }
                }.distinctBy { it.url }
                println("[YT_DEBUG] Total distinct audio streams: ${audioInfoList.size}")

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                    println("[YT_DEBUG] Subtitles count: ${subs.size}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] Failed to extract subtitles: ${e.message}")
                }

                startServerIfNeeded()

                // Build DASH links (now pointing to local proxy)
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
                                println("[YT_DEBUG] Generated DASH XML for video=${video.label}, audio_lang=$lang. XML head: ${dashXml.take(200)}")
                                val localLink = registerManifestAndGetUrl(dashXml)
                                if (localLink != null) {
                                    println("[YT_DEBUG] Registered MPD at: $localLink")
                                    builtLinks.add(
                                        newExtractorLink(
                                            "${this.name} ($lang)",
                                            "${video.label} ($lang)",
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
                                } else {
                                    println("[YT_DEBUG] FAILED to register MPD (serverPort=$serverPort)")
                                }
                            }
                        }
                    }
                }

                // 외부 다운로더 링크 (기존 유지)
                builtLinks.add(
                    newExtractorLink("YouTube Downloader", "Cobalt Web Download(4K)", "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )
                builtLinks.add(
                    newExtractorLink("YouTube Downloader", "SOSYouTube Web Download(1080p)", "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )

                ytVideos[videoId] = builtLinks.toList()
                println("[YT_DEBUG] Total ExtractorLinks built: ${builtLinks.size}")

            } catch (e: Exception) {
                println("[YT_DEBUG] Exception during extraction: ${e.message}")
                logError(e)
            }
        } else {
            println("[YT_DEBUG] Using cached links. Count=${cachedLinks.size}")
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
                    if (!url.contains("fmt=srt")) url = "$url&fmt=srt"
                }
                newSubtitleFile(lang, url)
            } catch (e: Exception) { null }
        }.forEach { subtitleCallback(it) }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) {
                println("[YT_DEBUG] Server already running on port $serverPort")
                return
            }
            try {
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_DEBUG] Local DASH proxy server started on port $serverPort")
                thread(name = "YT-DASH-Server-Acceptor", isDaemon = true) {
                    try {
                        while (true) {
                            val active = activeServer ?: break
                            if (active.isClosed) break
                            val client = active.accept()
                            println("[YT_DEBUG] Accepted connection from ${client.inetAddress}")
                            client.soTimeout = 15000
                            serverExecutor.execute { handleClient(client) }
                        }
                    } catch (e: Exception) {
                        println("[YT_DEBUG] Server accept loop terminated: ${e.message}")
                    }
                }
            } catch (e: Exception) {
                println("[YT_DEBUG] Failed to start server: ${e.message}")
                logError(e)
            }
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) {
            println("[YT_DEBUG] registerManifestAndGetUrl: serverPort is 0")
            return null
        }
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        val url = "http://127.0.0.1:$serverPort/$id.mpd"
        println("[YT_DEBUG] MPD registered: id=$id, url=$url")
        return url
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
                val line = reader.readLine() ?: run {
                    println("[YT_DEBUG] handleClient: empty request line")
                    return
                }
                println("[YT_DEBUG] handleClient: request line = $line")

                if (line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        val requestPath = parts[1]
                        val cleanPath = requestPath.substringAfter("/").substringBefore("?")
                        println("[YT_DEBUG] cleanPath = $cleanPath")

                        val clientHeaders = mutableMapOf<String, String>()
                        var headerLine: String?
                        while (true) {
                            headerLine = reader.readLine()
                            if (headerLine.isNullOrBlank()) break
                            val idx = headerLine.indexOf(":")
                            if (idx > 0) {
                                val key = headerLine.substring(0, idx).trim().lowercase()
                                val value = headerLine.substring(idx + 1).trim()
                                clientHeaders[key] = value
                            }
                        }
                        println("[YT_DEBUG] client headers: $clientHeaders")

                        if (cleanPath.startsWith("proxy", ignoreCase = true)) {
                            val query = requestPath.substringAfter("?")
                            val params = parseQueryParams(query)
                            val targetUrl = params["url"]
                            println("[YT_DEBUG] Proxy request for targetUrl = $targetUrl")
                            if (!targetUrl.isNullOrBlank()) {
                                handleProxyRequest(socket, targetUrl, clientHeaders)
                            } else {
                                println("[YT_DEBUG] Proxy request missing 'url' parameter")
                                send404(socket)
                            }
                        } else {
                            val path = if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
                                cleanPath.substring(0, cleanPath.length - 4)
                            } else {
                                cleanPath
                            }
                            println("[YT_DEBUG] MPD request for id=$path")
                            val content = manifestMap[path]
                            val output = PrintWriter(socket.getOutputStream(), true)
                            if (content != null) {
                                output.println("HTTP/1.1 200 OK")
                                output.println("Content-Type: application/dash+xml")
                                output.println("Connection: close")
                                output.println("Access-Control-Allow-Origin: *")
                                output.println("")
                                output.println(content)
                                println("[YT_DEBUG] Served MPD (size=${content.length})")
                            } else {
                                println("[YT_DEBUG] MPD not found for id=$path")
                                output.println("HTTP/1.1 404 Not Found")
                                output.println("")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] handleClient error: ${e.message}")
        }
    }

    private fun handleProxyRequest(client: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
        try {
            val requestBuilder = Request.Builder()
                .url(targetUrl)
                .header("User-Agent", YoutubeUtils.IOS_USER_AGENT)
                .header("Referer", "https://www.youtube.com")
                .header("Origin", "https://www.youtube.com")

            clientHeaders["range"]?.let { range ->
                requestBuilder.header("Range", range)
                println("[YT_DEBUG] Forwarding Range header: $range")
            }
            println("[YT_DEBUG] Starting proxy request to $targetUrl")

            proxyClient.newCall(requestBuilder.build()).execute().use { response ->
                val code = response.code
                println("[YT_DEBUG] Proxy response code: $code")
                response.headers.forEach { (name, value) ->
                    println("[YT_DEBUG] Response header: $name: $value")
                }

                val outputStream = client.getOutputStream()
                val headerBytes = StringBuilder()
                headerBytes.append("HTTP/1.1 $code ${response.message}\r\n")
                response.header("Content-Type")?.let { headerBytes.append("Content-Type: $it\r\n") }
                response.header("Content-Length")?.let { headerBytes.append("Content-Length: $it\r\n") }
                response.header("Content-Range")?.let { headerBytes.append("Content-Range: $it\r\n") }
                response.header("Accept-Ranges")?.let { headerBytes.append("Accept-Ranges: $it\r\n") }
                headerBytes.append("Connection: close\r\n")
                headerBytes.append("Access-Control-Allow-Origin: *\r\n")
                headerBytes.append("\r\n")

                outputStream.write(headerBytes.toString().toByteArray(Charsets.UTF_8))
                outputStream.flush()
                println("[YT_DEBUG] Sent response headers to client")

                val body = response.body
                if (body != null) {
                    var totalRead = 0L
                    body.byteStream().use { inputStream ->
                        val buffer = ByteArray(32 * 1024)
                        var bytesRead: Int
                        while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                            outputStream.write(buffer, 0, bytesRead)
                            totalRead += bytesRead
                        }
                    }
                    outputStream.flush()
                    println("[YT_DEBUG] Proxy forwarding completed, total bytes sent: $totalRead")
                } else {
                    println("[YT_DEBUG] Response body was null")
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] Proxy request failed: ${e.message}")
            try {
                val outputStream = client.getOutputStream()
                val errorResponse = "HTTP/1.1 500 Internal Server Error\r\nConnection: close\r\n\r\n"
                outputStream.write(errorResponse.toByteArray(Charsets.UTF_8))
                outputStream.flush()
            } catch (_: Exception) {}
        }
    }

    private fun parseQueryParams(query: String): Map<String, String> {
        val params = mutableMapOf<String, String>()
        query.split("&").forEach { pair ->
            val idx = pair.indexOf("=")
            if (idx > 0) {
                val key = pair.substring(0, idx)
                val value = pair.substring(idx + 1)
                params[key] = YoutubeUtils.urlDecode(value)
            }
        }
        return params
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val localVideoUrl = escapeXml("http://127.0.0.1:$serverPort/proxy?url=${URLEncoder.encode(video.url, "UTF-8")}")
        val durationString = "PT${durationSec}S"
        println("[YT_DEBUG] buildDashManifestXml: videoUrl=$localVideoUrl")

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
            val audioId = "audio_$index"
            val aMime = audio.mimeType
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"

            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="$aCodecs">
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
        val height = vs.height ?: 0
        if (height > 0) return height.toString()
        return "video"
    }

    private fun send404(socket: Socket) {
        try {
            PrintWriter(socket.getOutputStream(), true).use { it.println("HTTP/1.1 404 Not Found\r\n\r\n") }
        } catch (_: Exception) {}
    }
}
