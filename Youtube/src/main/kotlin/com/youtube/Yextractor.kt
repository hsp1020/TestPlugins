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

        // ✅ [FIX] chunkId → (url, createdAt) 로 변경하여 TTL 기반 만료 관리
        data class CachedStream(val url: String, val createdAt: Long = System.currentTimeMillis())
        private val streamUrlCache = ConcurrentHashMap<String, CachedStream>()
        private const val STREAM_CACHE_TTL_MS = 5 * 60 * 60 * 1000L // 5시간 (YouTube URL 유효기간 6h)

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
            YoutubeUtils.initNewPipeDownloader()
            YoutubeStreamExtractor.setFetchIosClient(true)

            // ✅ [FIX] PoToken 백그라운드 사전 초기화
            thread(name = "YT-PoToken-Init", isDaemon = true) {
                try {
                    YoutubePoTokenProvider.refresh()
                    val tok = YoutubePoTokenProvider.poToken
                    val vis = YoutubePoTokenProvider.visitorData
                    println("[YT_DEBUG] PoToken 초기화 완료 | pot=${if (tok.isNotBlank()) tok.take(12) + "..." else "EMPTY"} | visitor=${if (vis.isNotBlank()) vis.take(10) + "..." else "EMPTY"}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] PoToken 초기화 실패 (무시됨): ${e.message}")
                }
            }
        }

        // ✅ [FIX] URL에 pot + visitorData를 주입하는 유틸
        private fun injectTokensToUrl(rawUrl: String): String {
            if (!rawUrl.contains("googlevideo.com")) return rawUrl
            val poToken = YoutubePoTokenProvider.poToken
            val visitorData = YoutubePoTokenProvider.visitorData
            var enriched = rawUrl
            if (poToken.isNotBlank() && !enriched.contains("pot=")) {
                enriched += "&pot=${java.net.URLEncoder.encode(poToken, "UTF-8")}"
            }
            // visitorData는 일부 클라이언트 타입에서 필요 (무해하므로 항상 포함)
            if (visitorData.isNotBlank() && !enriched.contains("visitor_data=")) {
                enriched += "&visitor_data=${java.net.URLEncoder.encode(visitorData, "UTF-8")}"
            }
            return enriched
        }

        // ✅ [FIX] 만료된 캐시 엔트리 정리
        private fun cleanExpiredCache() {
            val now = System.currentTimeMillis()
            streamUrlCache.entries.removeIf { now - it.value.createdAt > STREAM_CACHE_TTL_MS }
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
        println("[YT_DEBUG] [getUrl] 호출됨. 원본 URL: $url")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        // ✅ [FIX] getUrl 호출 시마다 PoToken 만료 여부 확인 후 갱신 시도
        val poToken = YoutubePoTokenProvider.poToken
        if (poToken.isBlank()) {
            println("[YT_DEBUG] PoToken이 비어있음. 재획득 시도...")
            try {
                YoutubePoTokenProvider.refresh()
            } catch (e: Exception) {
                println("[YT_DEBUG] PoToken 재획득 실패: ${e.message}")
            }
        }

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            try {
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                s.fetchPage()

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = ConcurrentHashMap.newKeySet<String>()

                // ✅ [FIX] 스트림 URL 추출 시점에 pot 토큰 즉시 주입
                val videoOnlyList = (s.videoOnlyStreams ?: emptyList()).mapNotNull { vs ->
                    try {
                        val rawStreamUrl = vs.getUrl() ?: return@mapNotNull null
                        val streamUrl = injectTokensToUrl(rawStreamUrl)
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
                        val rawAUrl = asr.getUrl() ?: return@mapNotNull null
                        val aUrl = injectTokensToUrl(rawAUrl) // ✅ [FIX] 오디오 URL에도 토큰 주입
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
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                } catch (e: Exception) {}

                startServerIfNeeded()
                cleanExpiredCache() // ✅ [FIX] 오래된 캐시 정리

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
                                            sourceName, finalName, localLink, type = ExtractorLinkType.DASH
                                        ) {
                                            this.referer = mainUrl
                                            this.quality = video.height
                                            this.headers = mapOf(
                                                "User-Agent" to YoutubeUtils.IOS_USER_AGENT
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                builtLinks.add(newExtractorLink(source = "YouTube Downloader", name = "Cobalt Web Download(4K)", url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })
                builtLinks.add(newExtractorLink(source = "YouTube Downloader", name = "SOSYouTube Web Download(1080p)", url = "https://www.sosyoutube.com/watch?v=$videoId", type = INFER_TYPE) { this.quality = 2160; this.referer = mainUrl })

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
                var url = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                if (url.startsWith("http")) {
                    url = url.replace(Regex("&fmt=[^&]+"), "")
                    if (!url.contains("fmt=srt")) { url = "$url&fmt=srt" }
                }
                newSubtitleFile(lang, url)
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
                    val cached = if (chunkId != null) streamUrlCache[chunkId] else null

                    if (cached != null) {
                        // ✅ [FIX] 캐시된 URL의 TTL 만료 체크
                        val isExpired = System.currentTimeMillis() - cached.createdAt > STREAM_CACHE_TTL_MS
                        if (isExpired) {
                            println("[YT_PROXY_DEBUG] ⚠️ 스트림 URL TTL 만료됨 (chunkId=$chunkId). 410 반환.")
                            streamUrlCache.remove(chunkId)
                            outWriter.print("HTTP/1.1 410 Gone\r\nContent-Length: 0\r\n\r\n")
                            outWriter.flush()
                        } else {
                            proxyChunkToYouTube(socket, cached.url, clientHeaders, chunkId!!)
                        }
                    } else {
                        println("[YT_PROXY_DEBUG] 404 Not Found - Chunk ID가 메모리에 없습니다: $chunkId")
                        outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                        outWriter.flush()
                    }
                } else if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
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

    private fun proxyChunkToYouTube(
        socket: Socket,
        targetUrl: String,
        clientHeaders: Map<String, String>,
        chunkId: String // ✅ [FIX] 403 시 캐시 제거를 위해 chunkId 추가
    ) {
        try {
            // ✅ [FIX] 프록시 시점에서도 pot 토큰 재확인 및 주입 (URL 생성 후 토큰이 갱신된 경우 대비)
            var finalTargetUrl = targetUrl
            val currentPoToken = YoutubePoTokenProvider.poToken
            val currentVisitorData = YoutubePoTokenProvider.visitorData

            // URL에 pot가 없고 현재 토큰이 있으면 주입 (URL 생성 시점에 토큰이 없었을 경우 보완)
            if (finalTargetUrl.contains("googlevideo.com")) {
                if (currentPoToken.isNotBlank() && !finalTargetUrl.contains("pot=")) {
                    finalTargetUrl += "&pot=${java.net.URLEncoder.encode(currentPoToken, "UTF-8")}"
                }
                if (currentVisitorData.isNotBlank() && !finalTargetUrl.contains("visitor_data=")) {
                    finalTargetUrl += "&visitor_data=${java.net.URLEncoder.encode(currentVisitorData, "UTF-8")}"
                }
            }

            // n 파라미터 상태 디버그
            val nParam = Regex("[?&]n=([^&]+)").find(finalTargetUrl)?.groupValues?.get(1) ?: "없음"
            val potInUrl = if (finalTargetUrl.contains("pot=")) "✅ 포함됨" else "❌ 없음"

            println("\n[YT_PROXY_DEBUG] ================= CHUNK REQUEST START =================")
            println("[YT_PROXY_DEBUG] 1. n-파라미터: $nParam")
            println("[YT_PROXY_DEBUG] 2. pot URL 포함 여부: $potInUrl")
            println("[YT_PROXY_DEBUG] 3. 현재 PoToken: ${if (currentPoToken.isNotBlank()) "✅ [${currentPoToken.take(12)}...]" else "❌ EMPTY"}")
            println("[YT_PROXY_DEBUG] 4. visitorData: ${if (currentVisitorData.isNotBlank()) "✅ [${currentVisitorData.take(10)}...]" else "❌ EMPTY"}")

            val requestBuilder = okhttp3.Request.Builder()
                .url(finalTargetUrl)
                .header("User-Agent", YoutubeUtils.IOS_USER_AGENT)
                .header("Accept", "*/*")
                .header("Connection", "keep-alive")

            clientHeaders["range"]?.let {
                requestBuilder.header("Range", it)
                println("[YT_PROXY_DEBUG] 5. 요청 Range: $it")
            }

            val request = requestBuilder.build()
            val response = proxyHttpClient.newCall(request).execute()

            println("[YT_PROXY_DEBUG] 6. 유튜브 CDN 응답 코드: ${response.code}")

            if (response.code == 403 || response.code == 400) {
                val errorBody = response.body?.string() ?: "Empty Body"
                println("[YT_PROXY_DEBUG] 🚨 ${response.code} FORBIDDEN 발생!")
                println("[YT_PROXY_DEBUG] 에러 응답 본문: $errorBody")

                // ✅ [FIX] 403/400 시 해당 chunkId 캐시 제거 (재생 재시작 시 재추출 유도)
                streamUrlCache.remove(chunkId)
                println("[YT_PROXY_DEBUG] ♻️ 만료된 스트림 캐시 제거 완료: chunkId=$chunkId")
                println("[YT_PROXY_DEBUG] =======================================================\n")

                val output = socket.getOutputStream()
                val rawOutWriter = java.io.PrintWriter(output, false)
                // ✅ [FIX] 410 Gone 반환 → 플레이어가 정상 종료 처리하도록 유도 (무한 재시도 방지)
                rawOutWriter.print("HTTP/1.1 410 Gone\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
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
            println("[YT_PROXY_DEBUG] Chunk 릴레이 성공 및 전송 완료.")
            println("[YT_PROXY_DEBUG] =======================================================\n")

        } catch (e: Exception) {
            println("[YT_PROXY_DEBUG] 프록시 릴레이 중 예외 발생: ${e.message}")
        }
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val videoChunkId = UUID.randomUUID().toString()
        // ✅ [FIX] CachedStream으로 저장 (TTL 추적)
        streamUrlCache[videoChunkId] = CachedStream(video.url)
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
            // ✅ [FIX] CachedStream으로 저장
            streamUrlCache[audioIdChunk] = CachedStream(audio.url)
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
                  <Representation id="$audioId" bandwidth="${if (audio.bitrate > 0) audio.bitrate else 128000}" codecs="$aCodecs">
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
        for (i in url.indices) {
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
        val height = runCatching { vs.height }.getOrNull() ?: 0
        if (height > 0) return height.toString()
        return "video"
    }
}
