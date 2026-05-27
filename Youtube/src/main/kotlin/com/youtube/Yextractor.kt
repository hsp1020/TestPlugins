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

        // 2004 에러의 근본적 해결책: 캐시 상한치를 50개에서 1000개로 대폭 확장 (다국어 매핑 폭발에 대처)
        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        // [프록시 추가됨] 청크 UUID -> 원본 YouTube CDN URL 매핑
        private val streamUrlCache = ConcurrentHashMap<String, String>()

        // CallerRunsPolicy를 적용하여 가상 MPD 및 세그먼트 정체 시 패킷 폐기 방지 및 백프레셔 제어 보장
        private val serverExecutor = ThreadPoolExecutor(
            4, // 청크 프록시 처리를 위해 코어 스레드 4개로 확장
            16, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(512),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        // [프록시 추가됨] 청크 릴레이 통신용 로컬 HTTP 클라이언트
        private val proxyHttpClient = okhttp3.OkHttpClient.Builder()
            .retryOnConnectionFailure(true)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        init {
            // NewPipe Extractor에 강제 Header 및 통신망 주입 (YoutubeUtils에 선언됨)
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
        println("[YT_DEBUG] [getUrl] 가공된 cleanedUrl: $cleanedUrl, 비디오 ID: $videoId")

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            println("[YT_DEBUG] [getUrl] 분석을 진행합니다.")
            try {
                println("[YT_DEBUG] [getUrl] NewPipe linkHandler에서 link 분석 시작")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                println("[YT_DEBUG] [getUrl] s.fetchPage() 네트워크 호출 시작")
                s.fetchPage()
                println("[YT_DEBUG] [getUrl] s.fetchPage() 호출 성공. 제목: '${s.name}', 길이: ${s.length}초")

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = ConcurrentHashMap.newKeySet<String>()

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

                        println("[YT_DEBUG] [getUrl] DASH 비디오 스트림 파싱 성공 -> 화질: ${height}p, 포맷: $mime, URL: ${streamUrl.take(60)}...")
                        StreamInfo(streamUrl, mime, height, label, initR, indexR)
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 비디오 파싱 중 오류 발생: ${e.message}")
                        null 
                    }
                }.distinctBy { it.height }
                println("[YT_DEBUG] [getUrl] 최종 수집된 DASH 비디오 스트림 개수: ${videoOnlyList.size}")

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

                        println("[YT_DEBUG] [getUrl] DASH 오디오 스트림 파싱 성공 -> 언어: $rawLang, 전송률: $bitrate, 포맷: $mime, URL: ${aUrl.take(60)}...")
                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 오디오 파싱 중 오류 발생: ${e.message}")
                        null 
                    }
                }.distinctBy { it.url }
                println("[YT_DEBUG] [getUrl] 최종 수집된 DASH 오디오 스트림 개수: ${audioInfoList.size}")

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    val subs = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    ytVideosSubtitles[videoId] = subs
                    println("[YT_DEBUG] [getUrl] 자막 트랙 개수: ${subs.size}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 파싱 중 예외 발생: ${e.message}")
                }

                startServerIfNeeded()

                println("[YT_DEBUG] [getUrl] DASH 가상 XML 생성 루프를 시작합니다.")
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

                                    println("[YT_DEBUG] [getUrl] DASH 가상 링크 빌드 성공 -> 화질: ${video.label}, 언어: $lang, 가상 URL: $localLink")
                                    builtLinks.add(
                                        newExtractorLink(
                                            sourceName,
                                            finalName,
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
                    }
                }

                // 외부 고해상도 웹 다운로드 포털 연동 보완 로직 보존
                builtLinks.add(
                    newExtractorLink(
                        source = "YouTube Downloader",
                        name = "Cobalt Web Download(4K)",
                        url = "https://qwkuns.me/#https://www.youtube.com/watch?v=$videoId",
                        type = INFER_TYPE
                    ) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )

                builtLinks.add(
                    newExtractorLink(
                        source = "YouTube Downloader",
                        name = "SOSYouTube Web Download(1080p)",
                        url = "https://www.sosyoutube.com/watch?v=$videoId",
                        type = INFER_TYPE
                    ) {
                        this.quality = 2160
                        this.referer = mainUrl
                    }
                )

                ytVideos[videoId] = builtLinks.toList()
                println("[YT_DEBUG] [getUrl] 전체 링크 정렬 및 저장 완료. 총 수집 링크 개수: ${builtLinks.size}")

            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] NewPipe Extractor 분석 도중 에러가 감지되었습니다: ${e.message}")
                logError(e) 
            }
        } else {
            println("[YT_DEBUG] [getUrl] 이미 캐시된 링크 리스트가 존재하여 캐시 데이터를 반환합니다.")
        }

        val finalLinks: List<ExtractorLink> = ytVideos[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] 반환되는 최종 링크 리스트 개수: ${finalLinks.size}")
        finalLinks.forEach { callback(it) }

        val finalSubtitles: List<SubtitlesStream> = ytVideosSubtitles[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] 반환되는 최종 자막 개수: ${finalSubtitles.size}")
        finalSubtitles.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                var url = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                
                if (url.startsWith("http")) {
                    url = url.replace(Regex("&fmt=[^&]+"), "")
                    if (!url.contains("fmt=srt")) {
                        url = "$url&fmt=srt"
                    }
                }
                newSubtitleFile(lang, url)
            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] 자막 처리 중 에러: ${e.message}")
                null 
            }
        }.forEach { subtitleCallback(it) }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) {
                println("[YT_DEBUG] [startServerIfNeeded] 기존 DASH 루프백 서버가 구동 중이므로 리스너 스레드 생성을 스킵합니다. (포트: $serverPort)")
                return
            }
            try {
                val newServer = ServerSocket(0)
                activeServer = newServer
                serverPort = newServer.localPort
                println("[YT_DEBUG] [startServerIfNeeded] 가상 포트 $serverPort 에 리스너 바인딩 성공")
                
                thread(name = "YT-DASH-Server-Acceptor") {
                    println("[YT_DEBUG] [ServerSocket-Thread] accept() 대기 루프 스레드를 시작합니다.")
                    try {
                        while (true) {
                            val active = activeServer ?: break
                            if (active.isClosed) break
                            val client = active.accept()
                            client.soTimeout = 15000 // 프록시 처리를 위해 타임아웃 15초로 상향 
                            serverExecutor.execute { handleClient(client) }
                        }
                    } catch (e: Exception) {
                        println("[YT_DEBUG] [ServerSocket-Thread] accept() 대기 동작 중 오류 발생: ${e.message}")
                    }
                }
            } catch (e: Exception) { 
                println("[YT_DEBUG] [startServerIfNeeded] 서버 소켓 생성 실패 예외: ${e.message}")
                logError(e) 
            }
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
                if (!requestLine.startsWith("GET")) return
                val parts = requestLine.split(" ")
                if (parts.size < 2) return

                val fullPath = parts[1]
                
                // HTTP 헤더 수집 (ExoPlayer가 보낸 Range 헤더 탈취)
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

                // 1. 영상/음성 청크 프록시 라우트
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
                // 2. MPD 매니페스트 제공 라우트
                else {
                    val path = cleanPath.substringAfterLast("/").removeSuffix(".mpd")
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
                        println("[YT_DEBUG] [handleClient] 에러: 메모리에 매칭되는 가상 MPD가 없습니다. UUID: $path (404 반환)")
                        outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                        outWriter.flush()
                    }
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] [handleClient] 소켓 데이터 스트림 처리 중 예외: ${e.message}")
        }
    }

    // [프록시 로직] 로컬 소켓 -> 유튜브 원본 서버로 HTTP 프록시 릴레이 (po_token + Header 주입)
    private fun proxyChunkToYouTube(socket: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
        try {
            var finalTargetUrl = targetUrl
            val poToken = YoutubePoTokenProvider.poToken
            
            // 2004 에러 방지: potoken 누락 시 동적 주입
            if (finalTargetUrl.contains("googlevideo.com") && !finalTargetUrl.contains("pot=") && poToken.isNotBlank()) {
                finalTargetUrl += "&pot=$poToken"
            }

            val requestBuilder = okhttp3.Request.Builder()
                .url(finalTargetUrl)
                // 2004 에러 방지: 반드시 초기 추출 시점의 User-Agent와 일치시켜야 봇 방어를 뚫음
                .header("User-Agent", YoutubeUtils.IOS_USER_AGENT)
                .header("Connection", "keep-alive")

            // ExoPlayer가 요청한 동적 청크 범위를 원본 유튜브 서버에 바이패스
            clientHeaders["range"]?.let { requestBuilder.header("Range", it) }

            val response = proxyHttpClient.newCall(requestBuilder.build()).execute()
            val output = socket.getOutputStream()
            val rawOutWriter = java.io.PrintWriter(output, false)

            // 스트림 헤더 릴레이
            rawOutWriter.print("HTTP/1.1 ${response.code} ${response.message}\r\n")
            response.header("Content-Type")?.let { rawOutWriter.print("Content-Type: $it\r\n") }
            response.header("Content-Length")?.let { rawOutWriter.print("Content-Length: $it\r\n") }
            response.header("Content-Range")?.let { rawOutWriter.print("Content-Range: $it\r\n") }
            response.header("Accept-Ranges")?.let { rawOutWriter.print("Accept-Ranges: $it\r\n") }
            rawOutWriter.print("Connection: close\r\n")
            rawOutWriter.print("\r\n")
            rawOutWriter.flush()

            // 유튜브 조각 데이터를 그대로 ExoPlayer 소켓으로 펌핑
            response.body?.byteStream()?.use { input ->
                input.copyTo(output)
            }
            output.flush()

        } catch (e: Exception) {
            println("[YT_DEBUG] [proxyChunkToYouTube] 프록시 릴레이 실패: ${e.message}")
        }
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        // [중요 수정됨] 원본 URL을 캐시에 넣고, MPD 안에는 로컬 프록시 URL을 삽입
        val videoChunkId = UUID.randomUUID().toString()
        streamUrlCache[videoChunkId] = video.url
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
            // 오디오도 프록시 URL 처리
            val audioIdChunk = UUID.randomUUID().toString()
            streamUrlCache[audioIdChunk] = audio.url
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
