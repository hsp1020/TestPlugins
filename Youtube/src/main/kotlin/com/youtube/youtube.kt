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
package com.lagradost.cloudstream3.ar.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.util.regex.Pattern
import com.lagradost.cloudstream3.AcraApplication
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.Config.SLEEP_BETWEEN
import kotlin.collections.remove
import kotlin.text.get

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {

    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true
    )
    
    data class CachedChip(
        @JsonProperty("name") val name: String,
        @JsonProperty("token") val token: String
    )

    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    override var lang = sharedPref?.getString("youtube_language", "ko") ?: "ko"
    override val supportedTypes = setOf(TvType.Movie, TvType.Live)

    companion object {
        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()
    }

    private var isSearchContext = false


class YouTubeInterceptor(private val prefs: SharedPreferences?, private val provider: YoutubeProvider) : Interceptor {
        
        private fun sha1(input: String): String {
            val md = java.security.MessageDigest.getInstance("SHA-1")
            val bytes = md.digest(input.toByteArray())
            return bytes.joinToString("") { "%02x".format(it) }
        }

        override fun intercept(chain: Interceptor.Chain): Response {
            val requestBuilder = chain.request().newBuilder()
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36")

            val cookieBuilder = StringBuilder()
            val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)

            if (!visitor.isNullOrBlank()) {
                cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
            } else {
                cookieBuilder.append("VISITOR_INFO1_LIVE=_Mk3UVhY40g; ")
            }

            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
            var sapisidVal: String? = null

            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                    if (key == "SAPISID") sapisidVal = value
                }
            }
            
            cookieBuilder.append("PREF=f6=40000000&hl=${provider.lang}; CONSENT=YES+fx.456722336;")

            requestBuilder.addHeader("Cookie", cookieBuilder.toString())
            requestBuilder.addHeader("Accept-Language", "${provider.lang},en-US;q=0.9,en;q=0.8")

            if (!sapisidVal.isNullOrBlank()) {
                try {
                    val timestamp = (System.currentTimeMillis() / 1000).toString()
                    val origin = "https://www.youtube.com"
                    val msg = "$timestamp $sapisidVal $origin"
                    val hash = sha1(msg)
                    requestBuilder.addHeader("Authorization", "SAPISIDHASH ${timestamp}_$hash")
                    requestBuilder.addHeader("X-Origin", origin)
                    requestBuilder.addHeader("X-Goog-AuthUser", "0")
                } catch (e: Exception) {
                    logError(e)
                }
            }

            return chain.proceed(requestBuilder.build())
        }
    }
    
    private val ytInterceptor = YouTubeInterceptor(sharedPref, this)
    private val safariUserAgent =
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"

    private var savedContinuationToken: String? = null
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

    @Suppress("PropertyName")
    private data class PlayerResponse(@JsonProperty("streamingData") val streamingData: StreamingData?)
    private data class StreamingData(@JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?)

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>

    private fun Map<*, *>.getString(key: String): String? =
        this.entries.firstOrNull { (it.key as? String) == key }?.value as? String

    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: obj.getListKey("runs")?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> run.getString("text")
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    private fun extractLockupMetadata(lockup: Map<*, *>): Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            rows?.forEach { row ->
                val parts = row.getListKey("metadataParts")
                parts?.forEach { part ->
                    val text = getText(part.getMapKey("text")) ?: ""
                    if (text.isNotBlank()) {
                        if (text.matches(Regex(".*(\\d+[KMBkmb]|views|مشاهدة).*"))) {
                            views = formatViews(text)
                        } else if (!text.matches(Regex(".*(\\d+:\\d+|ago|قبل).*")) && text.length > 1 && !text.contains("•")) {
                            channel = text
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (!direct.isNullOrBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        val text = viewText.toString()
        if (text.any { it in listOf('K', 'M', 'B', 'k', 'm', 'b') } && text.length < 15) {
            return text.split("view")[0].split("مشاهدة")[0].trim()
        }
        val digits = text.filter { it.isDigit() }
        if (digits.isBlank()) return text
        return try {
            val v = digits.toLong()
            when {
                v < 1000 -> v.toString()
                v < 1_000_000 -> String.format("%.1fK", v / 1000.0).replace(".0K", "K")
                v < 1_000_000_000 -> String.format("%.1fM", v / 1_000_000.0).replace(".0M", "M")
                else -> String.format("%.1fB", v / 1_000_000_000.0).replace(".0B", "B")
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)

                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    private fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = buildThumbnailFromId(videoId)
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) {
                    this.posterUrl = poster?.let { "https://wsrv.nl/?url=${java.net.URLEncoder.encode(it, "UTF-8")}&w=300&h=450&fit=contain&cbg=black" } ?: poster
                }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                if (currentList.none { it.data == finalUrl }) {
                    currentList.add(newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                    })
                }

                val finalTitle = "#$episodeNum {$views} $title"

                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            if (lockup.getString("contentType") == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    val title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"
                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val finalTitle =
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title
                    return newTvSeriesSearchResponse(
                        finalTitle,
                        "$mainUrl/playlist?list=$playlistId",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = poster?.let { "https://wsrv.nl/?url=${java.net.URLEncoder.encode(it, "UTF-8")}&w=300&h=450&fit=contain&cbg=black" } ?: poster
                    }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                var title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isEmpty()) title = "YouTube Video"
                var (channel, views) = extractLockupMetadata(lockup)
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|عبر|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    if (currentList.none { it.data == finalUrl }) {
                        currentList.add(newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {$views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) {
                        this.posterUrl = "https://wsrv.nl/?url=${java.net.URLEncoder.encode(poster, "UTF-8")}&w=300&h=450&fit=contain&cbg=black"
                    }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) {
                    this.posterUrl = poster?.let { "https://wsrv.nl/?url=${java.net.URLEncoder.encode(it, "UTF-8")}&w=300&h=450&fit=contain&cbg=black" } ?: poster
                }
            }
        }
        return null
    }

    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean
    ) {
        if (data is Map<*, *>) {

            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode)
            }
        }
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        val cleanedHtml = html.substringAfter("ytInitialData")
        if (cleanedHtml == html) return null
        
        val jsonStart = cleanedHtml.indexOf("{")
        if (jsonStart == -1) return null
        
        val possibleJson = cleanedHtml.substring(jsonStart)
        val jsonString = extractBalancedJson(possibleJson) ?: return null
        
        return try {
            parseJson<Map<String, Any>>(jsonString)
        } catch (e: Exception) {
            val regex = Regex("""(?:var ytInitialData|window\["ytInitialData"\])\s*=\s*(\{.*?\});\s*(?:</script>|window|var)""", RegexOption.DOT_MATCHES_ALL)
            regex.find(html)?.groupValues?.getOrNull(1)?.let {
                try { parseJson<Map<String, Any>>(it) } catch (err: Exception) { null }
            }
        }
    }

    private fun extractBalancedJson(input: String): String? {
        var braceCount = 0
        var inString = false
        var escape = false
        for (i in input.indices) {
            val char = input[i]
            if (escape) {
                escape = false
                continue
            }
            if (char == '\\') {
                escape = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (char == '{') {
                    braceCount++
                } else if (char == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return input.substring(0, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun findConfig(html: String, key: String): String? {
        return try {
            val m = Regex("\"$key\"\\s*:\\s*\"([^\"]+)\"").find(html)
            m?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            // [방어 로직]: 칩 바(가로 스크롤 메뉴) 자체의 더미 토큰 탐색 원천 차단
            if (data.containsKey("feedFilterChipBarRenderer") || 
                data.containsKey("chipCloudRenderer") || 
                data.containsKey("relatedChipCloudRenderer")) {
                return null
            }

            // [추출 로직 1]: 명시적 비디오 그리드 스크롤 렌더러 타겟팅
            val renderer = data["continuationItemRenderer"] as? Map<*, *>
            if (renderer != null) {
                val endpoint = renderer["continuationEndpoint"] as? Map<*, *>
                if (endpoint != null) {
                    val token = (endpoint["continuationCommand"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["browseContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["searchContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["command"] as? Map<*, *>)?.get("token") as? String
                        ?: endpoint["token"] as? String
                    if (!token.isNullOrBlank()) return token
                }
            }

            // [추출 로직 2]: 레거시 스크롤 컨테이너 타겟팅
            val nextData = data["nextContinuationData"] as? Map<*, *>
            if (nextData != null) {
                val token = nextData["continuation"] as? String
                if (!token.isNullOrBlank()) return token
            }

            // 정방향 재귀 탐색 (구조적 결함을 차단했으므로 역순 탐색 불필요)
            for (v in data.values) {
                val t = findTokenRecursive(v)
                if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i)
                if (t != null) return t
            }
        }
        return null
    }

        override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val homeTitle = when(lang) {
                "ko" -> "홈"
                "ar" -> "الرئيسية"
                else -> "Home"
            }

            if (sharedPref?.getBoolean("show_trending_home", true) == true) {
                list.add(MainPageData(homeTitle, "Home", horizontalImages = true))
                
                val cachedChipsJson = sharedPref.getString("yt_cached_chips", "[]") ?: "[]"
                try {
                    val chips: List<CachedChip> = AppUtils.parseJson(cachedChipsJson)
                    chips.forEach { chip ->
                        list.add(MainPageData(chip.name, "chip_${chip.token}", horizontalImages = true))
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }

            val customSections = getCustomHomepages()
            customSections.filter { it.isEnabled && it.url != "Home" }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url, horizontalImages = true))
            }

            return list
        }


    private fun getCustomHomepages(): List<CustomSection> {
        val json = sharedPref?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }

    private fun resetContinuation() {
        savedContinuationToken = null
    }

    private val continuationTokens = mutableMapOf<String, String>()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6
        if (page > maxPages) return newHomePageResponse(request, emptyList(), hasNext = false)

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null
        val requestData = request.data
        val isChip = requestData.startsWith("chip_")

        try {
            if (page == 1) {
                continuationTokens.remove(requestData)
                if (isChip) {
                    savedApiKey = savedApiKey ?: sharedPref?.getString("yt_api_key", null)
                    savedVisitorData = savedVisitorData ?: sharedPref?.getString("yt_visitor_data", null)
                    savedClientVersion = savedClientVersion ?: sharedPref?.getString("yt_client_version", "2.20240725.01.00")
                    val tokenToUse = requestData.removePrefix("chip_")
                    
                    if (!savedApiKey.isNullOrBlank()) {
                        val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                        val payload = mapOf(
                            "context" to mapOf("client" to mapOf(
                                "visitorData" to (savedVisitorData ?: ""), "clientName" to "WEB",
                                "clientVersion" to (savedClientVersion ?: "2.20240725.01.00"),
                                "platform" to "DESKTOP", "hl" to lang
                            )),
                            "continuation" to tokenToUse
                        )
                        val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                        val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        
                        if (response != null) {
                            val actions = response["onResponseReceivedActions"] 
                                ?: response["onResponseReceivedCommands"] 
                                ?: response["contents"] 
                                ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                } else {
                    val html = app.get(if(requestData == "Home") mainUrl else requestData, interceptor = ytInterceptor).text
                    
                    savedApiKey = findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                    savedVisitorData = findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                    savedClientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: "2.20240725.01.00"
                    
                    if (!savedApiKey.isNullOrBlank()) {
                        sharedPref?.edit()
                            ?.putString("yt_api_key", savedApiKey)
                            ?.putString("yt_visitor_data", savedVisitorData)
                            ?.putString("yt_client_version", savedClientVersion)
                            ?.apply()
                    }

                    val initialData = extractYtInitialData(html)
                    if (initialData != null) {
                        if (requestData == "Home") {
                            val chipBar = safeGet(initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "richGridRenderer", "header", "feedFilterChipBarRenderer", "contents") as? List<*>
                            if (chipBar != null) {
                                val jsonArray = org.json.JSONArray()
                                for (chipItem in chipBar) {
                                    val chipMap = (chipItem as? Map<*, *>)?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
                                    val text = getText(chipMap["text"])
                                    val token = safeGet(chipMap, "navigationEndpoint", "continuationCommand", "token") as? String
                                    if (text.isNotBlank() && !token.isNullOrBlank()) {
                                        val obj = org.json.JSONObject()
                                        obj.put("name", text)
                                        obj.put("token", token)
                                        jsonArray.put(obj)
                                    }
                                }
                                if (jsonArray.length() > 0) {
                                    sharedPref?.edit()?.putString("yt_cached_chips", jsonArray.toString())?.apply()
                                }
                            }
                        }
                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {
                val tokenToUse = continuationTokens[requestData]
                if (!tokenToUse.isNullOrBlank()) {
                    val apiKey = savedApiKey ?: sharedPref?.getString("yt_api_key", null)
                    val vData = savedVisitorData ?: sharedPref?.getString("yt_visitor_data", null) ?: ""
                    val cVersion = savedClientVersion ?: sharedPref?.getString("yt_client_version", "2.20240725.01.00") ?: "2.20240725.01.00"
                    val apiUrl = if (requestData.contains("/results")) "$mainUrl/youtubei/v1/search?key=$apiKey" else "$mainUrl/youtubei/v1/browse?key=$apiKey"
                    val payload = mapOf(
                        "context" to mapOf("client" to mapOf(
                            "clientName" to "WEB", 
                            "clientVersion" to cVersion, 
                            "hl" to lang,
                            "visitorData" to vData,
                            "platform" to "DESKTOP"
                        )),
                        "continuation" to tokenToUse
                    )
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedActions"] ?: response["onResponseReceivedCommands"] ?: response["continuationContents"] ?: response
                        processRecursive(actions, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(response)
                    }
                }
            }
        } catch (e: Exception) { logError(e) }

        if (!nextContinuation.isNullOrBlank()) {
            continuationTokens[requestData] = nextContinuation
        } else {
            continuationTokens.remove(requestData)
        }

        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }
    
       
    fun findContinuationItemsRecursive(obj: Any?): List<*>? {
        when (obj) {
            is Map<*, *> -> {
                if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>

                val keysToTry = listOf(
                    "onResponseReceivedActions",
                    "onResponseReceivedCommands",
                    "onResponseReceivedEndpoints",
                    "continuationContents"
                )
                for (k in keysToTry) {
                    val v = obj[k]
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }

                for (v in obj.values) {
                    val r = findContinuationItemsRecursive(v)
                    if (r != null) return r
                }
            }

            is List<*> -> {
                for (i in obj) {
                    val r = findContinuationItemsRecursive(i)
                    if (r != null) return r
                }
            }
        }
        return null
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6
        if (page > maxPages) return newSearchResponseList(emptyList(), false)

        val results = mutableListOf<SearchResponse>()
        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }

        val seenIds = mutableSetOf<String>()
        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = sharedPref?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }

        try {
            if (page == 1) {
                savedContinuationToken = null
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                val regexKey = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
                savedApiKey = regexKey.find(html)?.groupValues?.get(1)
                savedVisitorData = findConfig(html, "VISITOR_DATA")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                }
            } else {
                if (!savedContinuationToken.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$savedApiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "visitorData" to (savedVisitorData ?: "")
                            )
                        ),
                        "continuation" to savedContinuationToken
                    )

                    val response = app.post(apiUrl, json = payload, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedCommands"] ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                    }
                }
            }
            return newSearchResponseList(results, !savedContinuationToken.isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }

    private val collectedShorts = mutableListOf<Episode>()

    private fun addShortToCache(title: String, url: String, poster: String?) {
        if (collectedShorts.none { it.data == url }) {
             collectedShorts.add(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                    this.episode = collectedShorts.size + 1
                }
            )
        }
    }

    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/shorts/")) {
            val videoId = url.extractYoutubeId() ?: "video"
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { it.data.extractYoutubeId() == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: buildThumbnailFromId(videoId)

            return newTvSeriesLoadResponse("Shorts Feed", url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = "قائمة تشغيل تلقائية من الشورتس (${targetEpisodes.size} فيديو)"
                this.tags = listOf("Shorts", "Feed")
            }
        }

        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                var cleanUrl = url.substringBefore("?").removeSuffix("/")
                val tabKeywords = listOf("/featured", "/shorts", "/streams", "/playlists", "/community", "/about", "/videos")
                for (keyword in tabKeywords) {
                    if (cleanUrl.endsWith(keyword)) {
                        cleanUrl = cleanUrl.removeSuffix(keyword)
                        break
                    }
                }
                val channelUrl = "$cleanUrl/videos"
                val response = app.get(channelUrl, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val apiKey = findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: safeGet(header, "content", "pageHeaderViewModel", "title", "dynamicTextViewModel", "text", "content") as? String
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = getBestThumbnail(safeGet(header, "avatar"))
                    ?: getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val subscriberCount = extractTitle(safeGet(header, "subscriberCountText") as? Map<*, *>)
                    ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows", 1, "metadataParts", 0, "text", "content") as? String

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?): List<*>? {
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedCommands", "onResponseReceivedEndpoints", "continuationContents", "onResponseReceivedResults")
                            for (k in keysToTry) {
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "continuationCommand", "token") as? String
                        if (!token.isNullOrBlank()) return token
                        val token2 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "browseContinuationEndpoint", "token") as? String
                        if (!token2.isNullOrBlank()) return token2
                        val token3 = safeGet(m, "continuationItemRenderer", "continuationEndpoint", "token") as? String
                        if (!token3.isNullOrBlank()) return token3
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    items.forEach { item ->
                        val map = item as? Map<*, *> ?: return@forEach
                        val videoRenderer = when {
                            map.containsKey("videoRenderer") -> map["videoRenderer"] as? Map<*, *>
                            map.containsKey("gridVideoRenderer") -> map["gridVideoRenderer"] as? Map<*, *>
                            map.containsKey("compactVideoRenderer") -> map["compactVideoRenderer"] as? Map<*, *>
                            map.containsKey("shortsVideoRenderer") -> map["shortsVideoRenderer"] as? Map<*, *>
                            map.containsKey("reelItemRenderer") -> {
                                val content = safeGet(map, "reelItemRenderer", "content") as? Map<*, *>
                                content?.get("reelItemRenderer") as? Map<*, *>
                            }
                            map.containsKey("richItemRenderer") -> {
                                val content = safeGet(map, "richItemRenderer", "content") as? Map<*, *>
                                (content?.get("videoRenderer") ?: content?.get("gridVideoRenderer") ?: content?.get("shortsLockupViewModel")) as? Map<*, *>
                            }
                            else -> null
                        }

                        if (videoRenderer != null) {
                            val vId = videoRenderer["videoId"] as? String ?: return@forEach
                            val vidTitle = extractTitle(videoRenderer["title"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["headline"] as? Map<*, *>)
                                ?: extractTitle(videoRenderer["shortBylineText"] as? Map<*, *>)
                                ?: "Video"
                            val thumb = getBestThumbnail(videoRenderer["thumbnail"]) ?: buildThumbnailFromId(vId)
                            val vidUrl = "$mainUrl/watch?v=$vId"
                            val viewCount = formatViews(safeGet(videoRenderer, "viewCountText", "simpleText") as? String)
                            val publishedTime = extractTitle(safeGet(videoRenderer, "publishedTimeText") as? Map<*, *>)

                            collectTo.add(newEpisode(vidUrl) {
                                this.name = vidTitle
                                this.posterUrl = thumb
                                this.description = listOfNotNull(viewCount, publishedTime).joinToString(" • ")
                            })
                        }
                    }
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    val activeTab = tabs.firstOrNull { tab ->
                        val tabMap = tab as? Map<*, *>
                        val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                        tabRenderer?.get("selected") == true
                    }

                    val activeContent = (activeTab as? Map<*, *>)?.get("tabRenderer") as? Map<*, *>
                    val content = activeContent?.get("content") as? Map<*, *>

                    if (content?.containsKey("richGridRenderer") == true) {
                        initialItems = safeGet(content, "richGridRenderer", "contents") as? List<*>
                    } else if (content?.containsKey("gridRenderer") == true) {
                        initialItems = safeGet(content, "gridRenderer", "items") as? List<*>
                    }

                    if (initialItems == null) {
                        for (tab in tabs) {
                            val tabMap = tab as? Map<*, *>
                            val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                            val tabContent = tabRenderer?.get("content") as? Map<*, *>
                            if (tabContent?.containsKey("richGridRenderer") == true) {
                                initialItems = safeGet(tabContent, "richGridRenderer", "contents") as? List<*>
                                break
                            }
                            if (tabContent?.containsKey("gridRenderer") == true) {
                                initialItems = safeGet(tabContent, "gridRenderer", "items") as? List<*>
                                break
                            }
                        }
                    }
                }
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPages = sharedPref?.getInt("channel_pages_limit", 6) ?: 6

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPages && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationTokenFromItems(continuationItems)
                        kotlinx.coroutines.delay((10).toLong())
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount ?: "N/A"}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {
                logError(e)
            }
        }

        if (url.contains("list=")) {
            try {
                val response = app.get(url, interceptor = ytInterceptor)
                val html = response.text
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")

                val header = safeGet(data, "header", "playlistHeaderRenderer") as? Map<*, *>
                val title = extractTitle(safeGet(header, "title") as? Map<*, *>) ?: "YouTube Playlist"
                val ownerObj = safeGet(header, "ownerText") as? Map<*, *>
                val author = extractTitle(ownerObj) ?: "Unknown Channel"
                val description = extractTitle(safeGet(header, "description") as? Map<*, *>)

                val episodes = mutableListOf<Episode>()
                val tempSearchResponses = mutableListOf<SearchResponse>()
                val seenPlaylistIds = mutableSetOf<String>()

                processRecursive(data, tempSearchResponses, seenPlaylistIds, playlistMode = false)

                tempSearchResponses.forEachIndexed { index, res ->
                    if (res.url.contains("watch?v=")) {
                        episodes.add(newEpisode(res.url) {
                            this.name = res.name.substringAfter("} ").trim()
                            this.episode = index + 1
                            this.posterUrl = res.posterUrl
                        })
                    }
                }

                if (episodes.isEmpty()) {
                    val contents = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "sectionListRenderer", "contents") as? List<*>
                    processRecursive(contents, tempSearchResponses, seenPlaylistIds, playlistMode = false)
                    tempSearchResponses.forEachIndexed { index, res ->
                        if (res.url.contains("watch?v=")) {
                            episodes.add(newEpisode(res.url) {
                                this.name = res.name.substringAfter("} ").trim()
                                this.episode = index + 1
                                this.posterUrl = res.posterUrl
                            })
                        }
                    }
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    val finalDescription = if (description.isNullOrBlank()) "Channel: $author" else "Channel: $author\n\n$description"
                    this.plot = finalDescription
                    this.tags = listOf(author)
                }
            } catch (e: Exception) {
                logError(e)
            }
        }

        val videoId = url.extractYoutubeId() ?: throw ErrorLoadingException("Invalid YouTube URL")

        val response = app.get(url, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)

        var title = "YouTube Video"
        var plot = ""
        var poster = buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {
            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = extractTitle(primary["dateText"] as? Map<*, *>)
                    if (!dateText.isNullOrBlank()) plot += "$dateText\n\n"
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {
                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {
                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {
            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }

        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Channel: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar
            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        return newMovieLoadResponse(title, url, TvType.Movie, videoId) {
            this.posterUrl = poster
            this.plot = plot

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs
        }
    }

    private fun sha1(input: String): String {
        val md = java.security.MessageDigest.getInstance("SHA-1")
        val bytes = md.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun getSapisidHash(
        sapisid: String,
        origin: String = "https://www.youtube.com"
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val msg = "$timestamp $sapisid $origin"
        val hash = sha1(msg)
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    private fun extractYoutubeIdSafe(input: String): String {
        return when {
            input.contains("youtu.be/") ->
                input.substringAfter("youtu.be/").substringBefore("?")

            input.contains("watch?v=") ->
                input.substringAfter("watch?v=").substringBefore("&")

            input.contains("/shorts/") ->
                input.substringAfter("/shorts/").substringBefore("?")

            else -> input
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = data.extractYoutubeId() ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"

        val context = AcraApplication.context
        val playerType = if (context != null) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            prefs.getString("youtube_player_type", "advanced")
        } else {
            "advanced"
        }

        var isLive = false
        var playerResponseText = ""
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to "2.20240725.01.00",
                        "hl" to "ko",
                        "gl" to "KR"
                    )
                ),
                "videoId" to videoId
            )
            playerResponseText = app.post(apiUrl, json = payload, interceptor = ytInterceptor).text
            if (playerResponseText.isNotBlank()) {
                val root = JSONObject(playerResponseText)
                val videoDetails = root.optJSONObject("videoDetails")
                if (videoDetails != null && videoDetails.optBoolean("isLive", false)) {
                    isLive = true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (isLive) {
            loadExtractor(fullUrl, subtitleCallback, callback)
        } else {
            var handled = false
            if (playerType == "classic") {
                handled = loadLinksClassic(fullUrl, isCasting, subtitleCallback, callback)
            }
            
            if (!handled) {
                var advancedLinksCount = 0
                com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback) {
                    advancedLinksCount++
                    callback(it)
                }

                if (advancedLinksCount == 0) {
                    loadExtractor(fullUrl, subtitleCallback, callback)
                } else {
                    loadLinksAdvanced(videoId, subtitleCallback) { }
                }
            }
        }

        return true
    }



    private suspend fun loadLinksAdvanced(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"

        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "3",
                        "clientVersion" to "19.29.35",
                        "hl" to "en",
                        "gl" to "US"
                    )
                ),
                "videoId" to videoId
            )

            val responseText = app.post(apiUrl, json = payload, interceptor = ytInterceptor).text

        } catch (e: Exception) {

        }

        return true
    }

    private suspend fun loadLinksClassic(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
            fun logLarge(tag: String, text: String) {
                var i = 0
                val max = 4000
                while (i < text.length) {
                    val end = minOf(i + max, text.length)
                    i = end
                }
            }

            fun addParamsToUrl(base: String, params: Map<String, String>): String {
                val sep = if (base.contains("?")) "&" else "?"
                return base + sep + params.map { "${it.key}=${URLEncoder.encode(it.value, "utf-8")}" }.joinToString("&")
            }

            val ALL_LANGS = listOf(
                "aa","ab","af","ak","am","ar","as","ay","az","ba","be","bg","bho","bn","bo","br","bs","ca","ceb","co","crs",
                "cs","cy","da","de","dv","dz","ee","el","en","eo","es","et","eu","fa","fi","fil","fj","fo","fr","fy","ga",
                "gaa","gd","gl","gn","gu","gv","ha","haw","he","hi","hmn","hr","ht","hu","hy","id","ig","is","it","iu","iw",
                "ja","jv","ka","kha","kk","kl","km","kn","ko","kri","ku","ky","la","lb","lg","ln","lo","lt","lua","luo","lv",
                "mfe","mg","mi","mk","ml","mn","mr","ms","mt","my","ne","new","nl","no","nso","ny","oc","om","or","os","pa",
                "pam","pl","ps","pt","pt-BR","pt-PT","qu","rn","ro","ru","rw","sa","sd","sg","si","sk","sl","sm","sn","so","sq",
                "sr","ss","st","su","sv","sw","ta","te","tg","th","ti","tk","tn","to","tr","ts","tt","tum","ug","uk","ur","uz",
                "ve","vi","war","wo","xh","yi","yo","zh-Hans","zh-Hant","zu"
            )

            try {
                val videoId = data.extractYoutubeId() ?: run {
                    if (data.length == 11 && data.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                        data
                    } else {
                        return false
                    }
                }

                val safariHeaders = mapOf(
                    "User-Agent" to safariUserAgent,
                    "Accept-Language" to "en-US,en;q=0.5"
                )
                val watchUrl = "$mainUrl/watch?v=$videoId&hl=en"

                val watchHtml = app.get(watchUrl, headers = safariHeaders).text

                val ytcfgJsonString = try {
                    val regex =
                        Regex("""ytcfg\.set\(\s*(\{.*?\})\s*\)\s*;""", RegexOption.DOT_MATCHES_ALL)
                    val m = regex.find(watchHtml)
                    m?.groupValues?.getOrNull(1)
                        ?: watchHtml.substringAfter("ytcfg.set(", "").substringBefore(");")
                            .takeIf { it.trim().startsWith("{") }
                } catch (e: Exception) {
                    null
                }

                if (ytcfgJsonString.isNullOrBlank()) {
                    return false
                }

                val apiKey = findConfig(ytcfgJsonString, "INNERTUBE_API_KEY")
                val clientVersion =
                    findConfig(ytcfgJsonString, "INNERTUBE_CLIENT_VERSION") ?: "2.20240725.01.00"
                val visitorData = findConfig(ytcfgJsonString, "VISITOR_DATA")

                if (apiKey.isNullOrBlank() || visitorData.isNullOrBlank()) {
                    return false
                }

                val clientMap = mapOf(
                    "hl" to "ko",
                    "gl" to "KR",
                    "clientName" to "WEB",
                    "clientVersion" to clientVersion,
                    "userAgent" to safariUserAgent,
                    "visitorData" to visitorData,
                    "platform" to "DESKTOP"
                )
                val finalContext = mapOf("client" to clientMap)
                val payload = mapOf("context" to finalContext, "videoId" to videoId)

                val apiUrl = "$mainUrl/youtubei/v1/player?key=$apiKey"

                val postHeaders = mutableMapOf<String, String>()
                postHeaders.putAll(safariHeaders)
                postHeaders["Content-Type"] = "application/json"
                postHeaders["X-Youtube-Client-Name"] = "WEB"
                postHeaders["X-Youtube-Client-Version"] = clientVersion
                if (!visitorData.isNullOrBlank()) postHeaders["X-Goog-Visitor-Id"] = visitorData

                val cookieBuilder = StringBuilder()
                val savedVis = sharedPref?.getString("VISITOR_INFO1_LIVE", null)
                if (!savedVis.isNullOrBlank()) {
                    cookieBuilder.append("VISITOR_INFO1_LIVE=$savedVis; ")
                } else {
                    cookieBuilder.append("VISITOR_INFO1_LIVE=fzYjM8PCwjw; ")
                }

                val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
                var sapisidVal: String? = null

                authKeys.forEach { key ->
                    val value = sharedPref?.getString(key, null)
                    if (!value.isNullOrBlank()) {
                        cookieBuilder.append("$key=$value; ")
                        if (key == "SAPISID") sapisidVal = value
                    }
                }

                cookieBuilder.append("PREF=f6=40000000&hl=en; CONSENT=YES+fx.456722336;")
                postHeaders["Cookie"] = cookieBuilder.toString()

                if (!sapisidVal.isNullOrBlank()) {
                    try {
                        val origin = "https://www.youtube.com"
                        val hash = getSapisidHash(sapisidVal!!, origin)
                        postHeaders["Authorization"] = hash
                        postHeaders["X-Origin"] = origin
                        postHeaders["Origin"] = origin
                        postHeaders["X-Goog-AuthUser"] = "0"
                    } catch (e: Exception) {

                    }
                }

                val responseText = app.post(apiUrl, headers = postHeaders, json = payload).text
                logLarge(name, "PLAYER API Response (first 55k chars):\n${responseText.take(55000)}")

                val playerResponse = try {
                    parseJson<PlayerResponse>(responseText)
                } catch (e: Exception) {
                    null
                }

                if (playerResponse == null) {
                    return false
                }

                val hlsUrl = playerResponse.streamingData?.hlsManifestUrl
                if (!hlsUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(this.name, "M3U AUTO", hlsUrl) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )

                    try {
                        val masterM3u8 = app.get(hlsUrl, referer = mainUrl).text
                        val lines = masterM3u8.lines()

                        lines.filter { it.startsWith("#EXT-X-MEDIA") && it.contains("TYPE=SUBTITLES") }
                            .forEach { line ->
                                val subUri = parseM3u8Tag(line, "URI")
                                val subName = parseM3u8Tag(line, "NAME")
                                val subLang = parseM3u8Tag(line, "LANGUAGE")

                                if (subUri != null) {
                                    val displayName = subName ?: subLang ?: "Subtitle (HLS)"
                                    subtitleCallback(SubtitleFile(displayName, subUri))
                                }
                            }

                        lines.forEachIndexed { index, line ->
                            if (line.startsWith("#EXT-X-STREAM-INF")) {
                                val infoLine = line
                                val urlLine =
                                    lines.getOrNull(index + 1)?.takeIf { it.startsWith("http") }
                                        ?: return@forEachIndexed

                                val resolution = parseM3u8Tag(infoLine, "RESOLUTION")
                                val parsedHeight = resolution?.substringAfter("x")?.toIntOrNull() ?: 0
                                

                                val resolutionHeight = resolution?.substringAfter("x")?.plus("p") ?: ""

                                val audioId = parseM3u8Tag(infoLine, "YT-EXT-AUDIO-CONTENT-ID")
                                val lang = audioId?.substringBefore('.')?.uppercase()

                                val ytTags = parseM3u8Tag(infoLine, "YT-EXT-XTAGS")
                                val audioType = when {
                                    ytTags?.contains("dubbed") == true -> "Dubbed"
                                    ytTags?.contains("original") == true -> "Original"
                                    else -> null
                                }

                                val nameBuilder = StringBuilder()
                                nameBuilder.append(resolutionHeight)
                                if (lang != null) {
                                    nameBuilder.append(" ($lang")
                                    if (audioType != null) {
                                        nameBuilder.append(" - $audioType")
                                    }
                                    nameBuilder.append(")")
                                }

                                val streamName = nameBuilder.toString().trim()
                                val sourceProviderName = if (lang != null) "${this.name} ($lang)" else this.name

                                if (streamName.isNotBlank()) {
                                    callback(
                                        newExtractorLink(sourceProviderName, streamName, urlLine) {
                                            this.referer = mainUrl
                                            this.quality = getQualityFromName(resolutionHeight)
                                        }
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {

                    }

                    return true
                } else {
                    return false
                }
            } catch (e: Exception) {
                logError(e)
                return false
            }
        }

    private fun parseM3u8Tag(tag: String, key: String): String? {
        val regex = Regex("""$key=("([^"]*)"|([^,]*))""")
        val match = regex.find(tag)
        return match?.groupValues?.get(2)?.ifBlank { null }
            ?: match?.groupValues?.get(3)?.ifBlank { null }
    }

    private fun String.extractYoutubeId(): String? {
        val regex = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
        return regex.find(this)?.groupValues?.getOrNull(1)
    }
}
