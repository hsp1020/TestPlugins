// file: Yextractor.kt
// v1.x (PipePipeExtractor 마이그레이션 및 403 대응 실시간 청크 프록시 개조 완료)
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
import com.lagradost.cloudstream3.ar.youtube.YoutubePoTokenProvider
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
        private val ytVideos = ConcurrentHashMap<String, List<ExtractorLink>>()
        private val ytVideosSubtitles = ConcurrentHashMap<String, List<SubtitlesStream>>()

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

        data class StreamCacheEntry(
            val url: String,
            val lastFetched: Long
        )
        private val streamUrlCache = ConcurrentHashMap<String, StreamCacheEntry>()

        private val serverExecutor = ThreadPoolExecutor(
            8, // I/O 블로킹 403 대비 스레드 상향
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
            YoutubeUtils.initNewPipeDownloader()
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
        val indexRange: String?,
        val itag: Int
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String,
        val itag: Int
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        println("[YT_DEBUG] [getUrl] 호출됨. 원본 URL: $url")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = ConcurrentHashMap.newKeySet<String>()

                val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val streamUrl = vs.url ?: vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(streamUrl)) return@mapNotNull null

                        val label = buildVideoLabelNumber(vs)
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        var mime = vs.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)
                        val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null
                        val itag = vs.id?.toIntOrNull() ?: 0
                        
                        streamUrlCache["${videoId}_$itag"] = StreamCacheEntry(streamUrl, System.currentTimeMillis())

                        StreamInfo(streamUrl, mime, height, label, initR, indexR, itag)
                    } catch (e: Exception) { null }
                }.distinctBy { it.height }

                val audioInfoList = (s.audioStreams ?: emptyList()).mapNotNull { asr ->
                    try {
                        val aUrl = asr.url ?: asr.content ?: return@mapNotNull null
                        val bitrate = asr.bitrate ?: 128000
                        var mime = asr.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)
                        val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null
                        var rawLang = asr.audioTrackId ?: "Default"
                        if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")
                        val itag = asr.id?.toIntOrNull() ?: 0

                        streamUrlCache["${videoId}_$itag"] = StreamCacheEntry(aUrl, System.currentTimeMillis())

                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase(), itag)
                    } catch (e: Exception) { null }
                }.distinctBy { it.url }

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                } catch (e: Exception) {}

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
                                val dashXml = buildDashManifestXml(videoId, video, listOf(bestAudioForLang), durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    val finalName = "${video.label} - $lang"
                                    builtLinks.add(
                                        newExtractorLink(
                                            name = this.name,
                                            name = finalName,
                                            url = localLink,
                                            referer = mainUrl,
                                            quality = INFER_TYPE,
                                            type = ExtractorLinkType.DASH,
                                            headers = mapOf("User-Agent" to YoutubeUtils.IOS_USER_AGENT)
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                ytVideos[videoId] = builtLinks.toList()
            } catch (e: Exception) {
                logError(e)
            }
        }

        val finalLinks: List<ExtractorLink> = ytVideos[videoId] ?: emptyList()
        finalLinks.forEach { callback(it) }

        val finalSubtitles: List<SubtitlesStream> = ytVideosSubtitles[videoId] ?: emptyList()
        finalSubtitles.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                var subUrl = ss.content ?: ss.url ?: return@mapNotNull null
                if (subUrl.startsWith("http")) {
                    subUrl = subUrl.replace(Regex("&fmt=[^&]+"), "")
                    if (!subUrl.contains("fmt=srt")) {
                        subUrl = "$subUrl&fmt=srt"
                    }
                }
                newSubtitleFile(lang, subUrl)
            } catch (e: Exception) { null }
        }.forEach { subtitleCallback(it) }
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
                    }
                }
            } catch (e: Exception) {
                logError(e)
            }
        }
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/manifest/$id.mpd"
    }

    private fun handleClient(client: Socket) {
        try {
            client.use { socket ->
                val inputStream = socket.getInputStream()
                val reader = BufferedReader(InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))
                val out = socket.getOutputStream()

                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val pathAndQuery = parts[1]
                
                val clientHeaders = mutableMapOf<String, String>()
                while (true) {
                    val line = reader.readLine()
                    if (line.isNullOrBlank()) break
                    val colonIndex = line.indexOf(':')
                    if (colonIndex != -1) {
                        clientHeaders[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
                    }
                }

                if (pathAndQuery.startsWith("/manifest/")) {
                    val id = pathAndQuery.substringAfterLast("/manifest/").substringBefore(".mpd")
                    val manifestData = manifestMap[id]
                    if (manifestData != null) {
                        val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: application/dash+xml\r\n" +
                                "Content-Length: ${manifestData.toByteArray().size}\r\n" +
                                "Access-Control-Allow-Origin: *\r\n" +
                                "Connection: close\r\n\r\n" +
                                manifestData
                        out.write(response.toByteArray())
                        out.flush()
                    } else {
                        out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                        out.flush()
                    }
                } else if (pathAndQuery.startsWith("/chunk?")) {
                    val query = pathAndQuery.substringAfter("?")
                    val params = query.split("&").associate {
                        val kv = it.split("=")
                        kv[0] to if (kv.size > 1) kv[1] else ""
                    }
                    val videoId = params["videoId"] ?: return
                    val itag = params["itag"]?.toIntOrNull() ?: return
                    val rangeHeader = clientHeaders["range"]

                    serveChunk(socket, videoId, itag, rangeHeader)
                }
            }
        } catch (e: Exception) {
        }
    }

    private fun getFreshStreamUrl(videoId: String, itag: Int): String? {
        return try {
            val link = YoutubeStreamLinkHandlerFactory.getInstance().fromId(videoId)
            val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
            s.fetchPage()

            val allStreams = (s.videoStreams ?: emptyList()) + (s.audioStreams ?: emptyList()) + (s.videoOnlyStreams ?: emptyList())
            val matchedStream = allStreams.find {
                it.id == itag.toString() ||
                (it as? org.schabi.newpipe.extractor.stream.VideoStream)?.resolution?.contains(itag.toString()) == true
            }
            matchedStream?.content ?: matchedStream?.url
        } catch (e: Exception) {
            null
        }
    }

    private fun serveChunk(socket: Socket, videoId: String, itag: Int, rangeHeader: String?) {
        val cacheKey = "${videoId}_${itag}"
        var cacheEntry = streamUrlCache[cacheKey]
        val out = socket.getOutputStream()
        
        var retryCount = 0
        while (retryCount < 2) {
            if (cacheEntry == null || System.currentTimeMillis() - cacheEntry.lastFetched > 45 * 60 * 1000L) {
                val newUrl = getFreshStreamUrl(videoId, itag)
                if (newUrl == null) {
                    out.write("HTTP/1.1 404 Not Found\r\n\r\n".toByteArray())
                    return
                }
                cacheEntry = StreamCacheEntry(newUrl, System.currentTimeMillis())
                streamUrlCache[cacheKey] = cacheEntry
            }

            val targetUrl = cacheEntry.url
            val reqBuilder = okhttp3.Request.Builder().url(targetUrl)
            if (rangeHeader != null) {
                reqBuilder.addHeader("Range", rangeHeader)
            }
            reqBuilder.addHeader("User-Agent", YoutubeUtils.IOS_USER_AGENT)

            val res = proxyHttpClient.newCall(reqBuilder.build()).execute()
            val responseCode = res.code

            if (responseCode == 403 || responseCode == 404) {
                res.close()
                cacheEntry = null 
                retryCount++
                continue
            }

            val headerBuilder = java.lang.StringBuilder()
            headerBuilder.append("HTTP/1.1 ${res.code} ${res.message}\r\n")
            res.headers.forEach { pair ->
                val name = pair.first.lowercase()
                if (name == "content-type" || name == "content-range" || name == "accept-ranges") {
                    headerBuilder.append("${pair.first}: ${pair.second}\r\n")
                }
            }
            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
            headerBuilder.append("Transfer-Encoding: chunked\r\n")
            headerBuilder.append("Connection: close\r\n\r\n")
            
            out.write(headerBuilder.toString().toByteArray())
            out.flush()

            res.body?.byteStream()?.use { input ->
                val buffer = ByteArray(32768)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    val hexLength = Integer.toHexString(bytesRead)
                    out.write("$hexLength\r\n".toByteArray())
                    out.write(buffer, 0, bytesRead)
                    out.write("\r\n".toByteArray())
                    out.flush()
                }
                out.write("0\r\n\r\n".toByteArray())
                out.flush()
            }
            res.close()
            break
        }
    }

    private fun buildVideoLabelNumber(vs: org.schabi.newpipe.extractor.stream.VideoStream): String {
        val h = vs.height ?: 0
        return if (h > 0) "${h}p" else "Unknown"
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

    private fun buildDashManifestXml(videoId: String, video: StreamInfo, audioList: List<AudioInfo>, durationSeconds: Long): String {
        val proxyBase = "http://127.0.0.1:$serverPort/chunk?videoId=$videoId"
        val cleanVideoUrl = escapeXml("$proxyBase&itag=${video.itag}&type=video")
        
        val vMime = escapeXml(video.mimeType)
        
        val sb = java.lang.StringBuilder()
        sb.append("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" minBufferTime="PT1.500S" profiles="urn:mpeg:dash:profile:isoff-main:2011" type="static" mediaPresentationDuration="PT${durationSeconds}S">""")
        sb.append("""<Period>""")
        
        val vCodecs = if (vMime.contains("webm")) "vp9" else "avc1.4d401e"
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

        for (audio in audioList) {
            val cleanAudioUrl = escapeXml("$proxyBase&itag=${audio.itag}&type=audio")
            val aMime = escapeXml(audio.mimeType)
            val lang = escapeXml(audio.language)
            val aCodecs = if (aMime.contains("webm")) "opus" else "mp4a.40.2"
            
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            sb.append("""
                <AdaptationSet mimeType="$aMime" subsegmentAlignment="true" subsegmentStartsWithSAP="1" lang="$lang">
                    <Representation id="audio_$lang" bandwidth="${audio.bitrate}" codecs="$aCodecs">
                        <BaseURL>$cleanAudioUrl</BaseURL>
                        $aSegmentBase
                    </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("""</Period>""")
        sb.append("""</MPD>""")
        return sb.toString()
    }
}
