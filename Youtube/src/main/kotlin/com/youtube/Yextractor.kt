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
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
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

        @Synchronized
        private fun startServerIfNeeded() {
            println("[YT_DEBUG] [startServerIfNeeded] 호출됨. activeServer 존재 여부: ${activeServer != null}, 닫힘 여부: ${activeServer?.isClosed}")
            if (activeServer != null && !activeServer!!.isClosed) {
                println("[YT_DEBUG] [startServerIfNeeded] 기존 로컬 DASH 서버가 포트 $serverPort 에서 정상 동작 중이므로 신규 생성을 스킵합니다.")
                return
            }
            try {
                activeServer = ServerSocket(0)
                serverPort = activeServer!!.localPort
                println("[YT_DEBUG] [startServerIfNeeded] 신규 로컬 ServerSocket이 성공적으로 생성됨. 할당된 포트: $serverPort")
                
                thread(name = "YT-LocalDASH-Server") {
                    println("[YT_DEBUG] [ServerSocket-Thread] 클라이언트 대기 accept() 루프를 시작합니다.")
                    try {
                        while (activeServer != null && !activeServer!!.isClosed) {
                            val client = activeServer!!.accept()
                            println("[YT_DEBUG] [ServerSocket-Thread] ExoPlayer로부터 로컬 서버 접속 감지됨: ${client.remoteSocketAddress}")
                            thread { handleClient(client) }
                        }
                    } catch (e: Exception) {
                        println("[YT_DEBUG] [ServerSocket-Thread] accept() 루프 내부에서 예외 발생하여 서버 스레드 종료됨: ${e.message}")
                    } finally {
                        println("[YT_DEBUG] [ServerSocket-Thread] accept() 루프가 마쳤습니다. cleanupServer()를 수행합니다.")
                        cleanupServer()
                    }
                }
            } catch (e: Exception) { 
                println("[YT_DEBUG] [startServerIfNeeded] ServerSocket 초기화 중 심각한 예외 발생: ${e.message}")
                cleanupServer()
            }
        }

        private fun cleanupServer() {
            println("[YT_DEBUG] [cleanupServer] 로컬 서버 자원 해제 시작. (현재 포트: $serverPort)")
            try {
                activeServer?.close()
                println("[YT_DEBUG] [cleanupServer] activeServer.close() 성공")
            } catch (e: Exception) {
                println("[YT_DEBUG] [cleanupServer] activeServer.close() 실패: ${e.message}")
            }
            activeServer = null
            serverPort = 0
            println("[YT_DEBUG] [cleanupServer] 서버 인스턴스 초기화 완료 (activeServer = null, port = 0)")
        }

        private fun handleClient(client: Socket) {
            println("[YT_DEBUG] [handleClient] 클라이언트 요청 처리 시작: ${client.remoteSocketAddress}")
            try {
                client.use { socket ->
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val output = PrintWriter(socket.getOutputStream(), true)
                    val line = reader.readLine()
                    println("[YT_DEBUG] [handleClient] 수신된 HTTP Request Line: $line")
                    if (line != null && line.startsWith("GET")) {
                        val parts = line.split(" ")
                        if (parts.size > 1) {
                            var path = parts[1].substring(1)
                            if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                            
                            println("[YT_DEBUG] [handleClient] 파싱된 Manifest UUID Key: $path")
                            val content = manifestMap[path]
                            if (content != null) {
                                println("[YT_DEBUG] [handleClient] 매칭되는 DASH XML 탐색 성공. ExoPlayer에게 200 OK 응답 전송합니다. (XML 길이: ${content.length})")
                                output.println("HTTP/1.1 200 OK")
                                output.println("Content-Type: application/dash+xml")
                                output.println("Connection: close")
                                output.println("Access-Control-Allow-Origin: *")
                                output.println("")
                                output.println(content)
                            } else {
                                println("[YT_DEBUG] [handleClient] 에러: 요청한 UUID($path)에 매칭되는 DASH XML이 메모리에 존재하지 않습니다. (404 전송)")
                                output.println("HTTP/1.1 404 Not Found")
                                output.println("")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[YT_DEBUG] [handleClient] 클라이언트 소켓 통신 중 예외 발생: ${e.message}")
            }
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
        val cleanedUrl = if (url.startsWith("http")) url else "https://$url"
        val videoId = try { cleanedUrl.substringAfter("v=").substringBefore("&") } catch (e: Exception) { cleanedUrl }
        println("[YT_DEBUG] [getUrl] 가공된 URL: $cleanedUrl, 파싱된 Video ID: $videoId")

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        if (ytVideos[videoId].isNullOrEmpty()) {
            println("[YT_DEBUG] [getUrl] 캐시가 비어있어 NewPipe Extractor 호출을 준비합니다.")
            try {
                println("[YT_DEBUG] [getUrl] NewPipe YoutubeStreamLinkHandlerFactory 분석 시작")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                
                println("[YT_DEBUG] [getUrl] s.fetchPage() 호출 시작 (네트워크 요청)")
                s.fetchPage()
                println("[YT_DEBUG] [getUrl] s.fetchPage() 호출 완료. 비디오 제목: '${s.name}', 비디오 길이(초): ${s.length}")

                val durationSeconds = if (s.length > 0) s.length else 3600L
                val builtLinks = mutableListOf<ExtractorLink>()
                val seenUrls = mutableSetOf<String>()

                // DASH 비디오 전용 스트림 수집
                val rawVideoOnlyStreams = s.videoOnlyStreams ?: emptyList()
                println("[YT_DEBUG] [getUrl] NewPipe가 수집한 raw videoOnlyStreams 개수: ${rawVideoOnlyStreams.size}")
                
                val videoOnlyList = rawVideoOnlyStreams.mapNotNull { vs ->
                    try {
                        val streamUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(streamUrl)) return@mapNotNull null

                        val label = buildVideoLabelNumber(vs)
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        
                        var mime = vs.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(streamUrl, false)

                        val initR = if (vs.initStart != null && vs.initEnd != null) "${vs.initStart}-${vs.initEnd}" else null
                        val indexR = if (vs.indexStart != null && vs.indexEnd != null) "${vs.indexStart}-${vs.indexEnd}" else null

                        println("[YT_DEBUG] [getUrl] 파싱 성공한 DASH 비디오 전용 스트림 -> 화질: ${height}p, 포맷: $mime, URL: ${streamUrl.take(60)}...")
                        StreamInfo(streamUrl, mime, height, label, initR, indexR)
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 비디오 스트림 정보 분석 중 예외 발생: ${e.message}")
                        null 
                    }
                }.distinctBy { it.height }
                println("[YT_DEBUG] [getUrl] 중복 제거 후 최종 DASH 비디오 스트림 개수: ${videoOnlyList.size}")

                // DASH 오디오 전용 스트림 수집
                val rawAudioStreams = s.audioStreams ?: emptyList()
                println("[YT_DEBUG] [getUrl] NewPipe가 수집한 raw audioStreams 개수: ${rawAudioStreams.size}")
                
                val audioInfoList = rawAudioStreams.mapNotNull { asr ->
                    try {
                        val aUrl = asr.content ?: return@mapNotNull null
                        val bitrate = asr.bitrate ?: 128000
                        var mime = asr.format?.mimeType
                        if (mime.isNullOrEmpty()) mime = getMimeTypeFromUrl(aUrl, true)

                        val initR = if (asr.initStart != null && asr.initEnd != null) "${asr.initStart}-${asr.initEnd}" else null
                        val indexR = if (asr.indexStart != null && asr.indexEnd != null) "${asr.indexStart}-${asr.indexEnd}" else null

                        var rawLang = asr.audioTrackId ?: "Default"
                        if (rawLang.contains(".")) rawLang = rawLang.substringBefore(".")

                        println("[YT_DEBUG] [getUrl] 파싱 성공한 DASH 오디오 스트림 -> 언어: $rawLang, 전송률: $bitrate, 포맷: $mime, URL: ${aUrl.take(60)}...")
                        AudioInfo(aUrl, mime, bitrate, initR, indexR, rawLang.uppercase())
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 오디오 스트림 정보 분석 중 예외 발생: ${e.message}")
                        null 
                    }
                }.distinctBy { it.url }
                println("[YT_DEBUG] [getUrl] 중복 제거 후 최종 DASH 오디오 스트림 개수: ${audioInfoList.size}")

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    ytVideosSubtitles[videoId] = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    println("[YT_DEBUG] [getUrl] NewPipe 자막 트랙 개수: ${ytVideosSubtitles[videoId]?.size}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 목록 수집 중 실패: ${e.message}")
                }

                // DASH용 로컬 서버 재생 준비
                startServerIfNeeded()

                println("[YT_DEBUG] [getUrl] DASH XML 빌드 및 로컬 루프백 링크 등록 루프 진입")
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

                                    println("[YT_DEBUG] [getUrl] 고화질 DASH 가상 링크 등록 성공 -> 소스: $sourceName, 이름: $finalName, 주소: $localLink")
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
                                } else {
                                    println("[YT_DEBUG] [getUrl] 경고: DASH XML 등록 실패 (localLink가 null 반환됨)")
                                }
                            } else {
                                println("[YT_DEBUG] [getUrl] 경고: 비디오 포맷(${video.mimeType})에 맞는 최적의 오디오 트랙($lang) 매칭 실패")
                            }
                        }
                    } else {
                        println("[YT_DEBUG] [getUrl] DASH 오디오 트랙 목록(audiosByLanguage)이 완전히 비어있습니다. DASH 생성을 스킵합니다.")
                    }
                }

                // 2순위 Legacy 360p/640p 단일 Muxed 비디오 수집
                val rawMuxedStreams = s.videoStreams ?: emptyList()
                println("[YT_DEBUG] [getUrl] NewPipe가 수집한 raw videoStreams(Muxed) 개수: ${rawMuxedStreams.size}")
                
                val muxedList = rawMuxedStreams.mapNotNull { vs ->
                    try {
                        val mUrl = vs.content ?: return@mapNotNull null
                        if (!seenUrls.add(mUrl)) return@mapNotNull null
                        val label = buildVideoLabelNumber(vs)
                        val height = runCatching { vs.height ?: 0 }.getOrNull() ?: 0
                        
                        println("[YT_DEBUG] [getUrl] 파싱 성공한 Legacy Muxed 스트림 -> 화질: ${height}p, 포맷: $label, URL: ${mUrl.take(60)}...")
                        Triple(mUrl, label, height)
                    } catch (e: Exception) { null }
                }
                for ((mUrl, mLabel, mHeight) in muxedList) {
                    println("[YT_DEBUG] [getUrl] Legacy Muxed 링크 등록 -> 이름: $mLabel (Legacy), 주소: ${mUrl.take(60)}...")
                    builtLinks.add(
                        newExtractorLink(this.name, "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                }

                ytVideos[videoId] = builtLinks.toList()
                println("[YT_DEBUG] [getUrl] 최종 링크 리스트 정렬 및 캐싱 완료. 추출된 링크 개수: ${builtLinks.size}")

            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] NewPipe 파싱 전체 과정 중 심각한 치명적 예외 발생: ${e.message}")
                logError(e) 
            }
        } else {
            println("[YT_DEBUG] [getUrl] 동일 Video ID에 대해 이미 분석된 캐시가 존재합니다. 캐시 데이터 로드.")
        }

        // 콜백 반환부 디버깅
        val cachedLinks = ytVideos[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] ExoPlayer로 반환할 최종 링크 개수: ${cachedLinks.size}")
        cachedLinks.forEachIndexed { index, link ->
            println("[YT_DEBUG]   -> [링크 #${index + 1}] 이름: '${link.name}', 주소: '${link.url.take(120)}...', 타입: ${link.type}, 화질: ${link.quality}p")
        }
        
        cachedLinks.forEach { callback(it) }

        // 자막 콜백 반환부 디버깅
        val cachedSubtitles = ytVideosSubtitles[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] 최종 전달할 자막 트랙 개수: ${cachedSubtitles.size}")
        cachedSubtitles.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                var url = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                
                if (url.startsWith("http")) {
                    url = url.replace(Regex("&fmt=[^&]+"), "")
                    if (!url.contains("fmt=srt")) {
                        url = "$url&fmt=srt"
                    }
                }
                println("[YT_DEBUG]   -> [자막 트랙] 언어: $lang, 주소: ${url.take(120)}...")
                newSubtitleFile(lang, url)
            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] 자막 트랙 파싱 실패: ${e.message}")
                null 
            }
        }.forEach { subtitleCallback(it) }        
    }

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        if (serverPort == 0) {
            println("[YT_DEBUG] [registerManifestAndGetUrl] 에러: 로컬 서버 포트가 0입니다. 서버가 구동되지 않은 상태입니다.")
            return null
        }
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        val localUrl = "http://127.0.0.1:$serverPort/$id.mpd"
        println("[YT_DEBUG] [registerManifestAndGetUrl] DASH XML 등록 성공. UUID: $id, 로컬 가상 주소: $localUrl")
        return localUrl
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
                """<SegmentBase indexRange="${audio.initRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
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
