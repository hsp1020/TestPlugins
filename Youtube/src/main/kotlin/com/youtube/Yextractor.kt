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

        // 추가: 클래스 구동 시 NewPipe Extractor에 내장된 iOS HLS 마스터 스트림 자동 우회 수집 모드 활성화
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
        println("[YT_DEBUG] [getUrl] 호출됨. 원본 URL: $url")
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = try { cleanedUrl.substringAfter("v=").substringBefore("&") } catch (e: Exception) { cleanedUrl }
        println("[YT_DEBUG] [getUrl] 가공된 cleanedUrl: $cleanedUrl, 비디오 ID: $videoId")

        ytVideos.remove(videoId)
        ytVideosSubtitles.remove(videoId)

        if (ytVideos[videoId].isNullOrEmpty()) {
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
                    ytVideosSubtitles[videoId] = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    println("[YT_DEBUG] [getUrl] 자막 트랙 개수: ${ytVideosSubtitles[videoId]?.size ?: 0}")
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
                                    // 언어별 우선순위 분리를 위한 소스명 변경
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
                        
                        println("[YT_DEBUG] [getUrl] Legacy Muxed 비디오 스트림 수집 완료 -> 화질: ${height}p, URL: ${mUrl.take(60)}...")
                        Triple(mUrl, label, height)
                    } catch (e: Exception) { null }
                }
                for ((mUrl, mLabel, mHeight) in muxedList) {
                    println("[YT_DEBUG] [getUrl] Legacy Muxed 링크 등록 -> 화질: $mHeight, 이름: $mLabel (Legacy)")
                    builtLinks.add(
                        newExtractorLink(this.name, "$mLabel (Legacy)", mUrl, type = INFER_TYPE) {
                            this.referer = mainUrl
                            this.quality = mHeight
                        }
                    )
                }

                ytVideos[videoId] = builtLinks.toList()
                println("[YT_DEBUG] [getUrl] 전체 링크 정렬 및 저장 완료. 총 수집 링크 개수: ${builtLinks.size}")

            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] NewPipe Extractor 분석 도중 에러가 감지되었습니다: ${e.message}")
                logError(e) 
            }
        } else {
            println("[YT_DEBUG] [getUrl] 이미 캐시된 링크 리스트가 존재하여 캐시 데이터를 반환합니다.")
        }

        val finalLinks = ytVideos[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] 반환되는 최종 링크 리스트 개수: ${finalLinks.size}")
        finalLinks.forEachIndexed { index, link ->
            println("[YT_DEBUG]   -> [링크 #${index + 1}] 이름: '${link.name}', 화질: ${link.quality}p, 타입: ${link.type}, 주소: '${link.url.take(100)}'")
        }

        ytVideos[videoId]?.forEach { callback(it) }

        val finalSubtitles = ytVideosSubtitles[videoId] ?: emptyList()
        println("[YT_DEBUG] [getUrl] 반환되는 최종 자막 개수: ${finalSubtitles.size}")
        ytVideosSubtitles[videoId]?.mapNotNull { ss ->
            try {
                val lang = ss.locale?.language ?: return@mapNotNull null
                var url = ss.content ?: ss.getUrl() ?: return@mapNotNull null
                
                // 핵심: NewPipe가 가져온 URL에서 기존 포맷을 강제로 뜯어내고 SRT를 주입합니다.
                if (url.startsWith("http")) {
                    url = url.replace(Regex("&fmt=[^&]+"), "")
                    if (!url.contains("fmt=srt")) {
                        url = "$url&fmt=srt"
                    }
                }
                println("[YT_DEBUG]   -> [자막 트랙] 언어: $lang, 주소: ${url.take(120)}...")
                newSubtitleFile(lang, url)
            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] 자막 처리 중 에러: ${e.message}")
                null 
            }
        }?.forEach { subtitleCallback(it) }        
        
    }

    @Synchronized
    private fun startServerIfNeeded() {
        println("[YT_DEBUG] [startServerIfNeeded] 호출됨. activeServer 존재: ${activeServer != null}, 닫힘 상태: ${activeServer?.isClosed}")
        if (activeServer != null && !activeServer!!.isClosed) {
            println("[YT_DEBUG] [startServerIfNeeded] 기존 DASH 루프백 서버가 구동 중이므로 리스너 스레드 생성을 스킵합니다. (포트: $serverPort)")
            return
        }
        try {
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            println("[YT_DEBUG] [startServerIfNeeded] 가상 포트 $serverPort 에 리스너 바인딩 성공")
            thread {
                println("[YT_DEBUG] [ServerSocket-Thread] accept() 대기 루프 스레드를 시작합니다.")
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = activeServer!!.accept()
                        println("[YT_DEBUG] [ServerSocket-Thread] ExoPlayer로부터 로컬 서버로 미디어 요청 감지됨: ${client.remoteSocketAddress}")
                        thread { handleClient(client) }
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

    private fun registerManifestAndGetUrl(xmlContent: String): String? {
        println("[YT_DEBUG] [registerManifestAndGetUrl] 호출됨. 포트: $serverPort")
        if (serverPort == 0) return null
        val id = UUID.randomUUID().toString()
        manifestMap[id] = xmlContent
        println("[YT_DEBUG] [registerManifestAndGetUrl] 가상 MPD 매니페스트 맵 등록 성공. UUID: $id, 주소: http://127.0.0.1:$serverPort/$id.mpd")
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun handleClient(client: Socket) {
        println("[YT_DEBUG] [handleClient] 요청 연결 처리 시작: ${client.remoteSocketAddress}")
        try {
            client.use { socket ->
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine()
                println("[YT_DEBUG] [handleClient] HTTP 요청: $line")
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path]
                        if (content != null) {
                            println("[YT_DEBUG] [handleClient] 가상 MPD UUID($path)를 발견하여 DASH XML을 반환합니다. (XML 길이: ${content.length})")
                            output.println("HTTP/1.1 200 OK")
                            output.println("Content-Type: application/dash+xml")
                            output.println("Connection: close")
                            output.println("Access-Control-Allow-Origin: *")
                            output.println("")
                            output.println(content)
                        } else {
                            println("[YT_DEBUG] [handleClient] 에러: 메모리에 매칭되는 가상 MPD가 없습니다. UUID: $path (404 반환)")
                            output.println("HTTP/1.1 404 Not Found")
                            output.println("")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] [handleClient] 소켓 데이터 스트림 처리 중 예외: ${e.message}")
        }
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
