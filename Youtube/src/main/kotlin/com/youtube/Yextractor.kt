package com.youtube

import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
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

        // 403 에러 방지를 위한 iOS 클라이언트 전용 User-Agent
        private const val IOS_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

        // 폴백용 기본 무료 API 서버 주소 (모든 탐색이 완전히 막혔을 때의 예비 안전 수단)
        private const val DEFAULT_FALLBACK_API_URL = "https://cobalt.meowing.de"

        // 동적으로 탐색된 전체 작동 후보 리스트 임시 캐시
        @Volatile
        private var cachedInstancesList: List<String>? = null

        // 실시간으로 선별되어 현재 작동 성공한 고속 화이트리스트 주소 메모리 캐시
        @Volatile
        private var cachedAutoInstance: String? = null

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

    // 소스 코드 가독성 및 무결성을 위해 통신 결과를 담는 독립 데이터 모델 정의
    data class CobaltResult(
        val url: String?,
        val isServerBroken: Boolean
    )

    // =================================================================
    // cobalt.directory/api/tests 를 파싱하여 유튜브 status=true 인 전체 인스턴스 확보
    // =================================================================
    private suspend fun fetchAllWorkingCobaltInstances(): List<String> {
        val cached = cachedInstancesList
        if (!cached.isNullOrEmpty()) return cached

        return try {
            println("[YT_DEBUG] cobalt.directory/api/tests 실시간 탐색 호출 시작")
            val responseText = withContext(Dispatchers.IO) {
                app.get("https://cobalt.directory/api/tests").text
            }
            if (responseText.isNotBlank()) {
                val json = parseJson<Map<String, Any>>(responseText)
                val dataList = json["data"] as? List<*> ?: return emptyList()
                
                val workingList = mutableListOf<String>()
                for (item in dataList) {
                    val instance = item as? Map<*, *> ?: continue
                    
                    val online = instance["online"] as? Boolean ?: false
                    if (!online) continue
                    
                    val tests = instance["tests"] as? Map<*, *> ?: continue
                    val youtube = tests["youtube"] as? Map<*, *> ?: continue
                    
                    // tests.youtube.status 가 오직 true 인 완전 개방형 활성 서버만 리스트화
                    val youtubeStatus = youtube["status"] as? Boolean ?: false
                    if (youtubeStatus) {
                        val apiHost = instance["api"] as? String
                        if (!apiHost.isNullOrBlank()) {
                            val protocol = instance["protocol"] as? String ?: "https"
                            workingList.add("$protocol://$apiHost")
                        }
                    }
                }
                println("[YT_DEBUG] cobalt.directory 분석 완료. 작동 후보 리스트 (${workingList.size}개): $workingList")
                cachedInstancesList = workingList
                workingList
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] cobalt.directory 원천 API 실시간 탐색 중 오류 발생: ${e.message}")
            emptyList()
        }
    }

    // =================================================================
    // [설계 교정] 화이트리스트 주소 항시 직접 호출 및 오작동 시에만 순회하여 새 화이트리스트 등록
    // =================================================================
    private suspend fun getCobaltMergedLink(videoUrl: String, quality: String): String? {
        val appPrefs = com.lagradost.cloudstream3.AcraApplication.context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it)
        }
        
        val cobaltInstanceSetting = appPrefs?.getString("youtube_cobalt_api", "auto") ?: "auto"
        val apiKey = appPrefs?.getString("youtube_cobalt_api_key", null)

        val isAutoMode = cobaltInstanceSetting.equals("auto", ignoreCase = true)

        if (isAutoMode) {
            // 1. 메모리 캐시 복구
            if (cachedAutoInstance == null) {
                cachedAutoInstance = appPrefs?.getString("yt_cobalt_whitelist_server", null)
            }

            // 이번 동영상 요청 도중 실시간으로 실패한 인스턴스를 일시 기억하여 중복 시도를 방지할 로컬 변수
            var failedInstance: String? = null

            // 2. 검증된 화이트리스트 서버 다이렉트 찌르기
            cachedAutoInstance?.let { whitelistInstance ->
                println("[YT_DEBUG] [Whitelist] 검증된 화이트리스트 주소 다이렉트 호출: $whitelistInstance")
                val result = executeCobaltRequest(videoUrl, quality, whitelistInstance, apiKey)
                if (!result.url.isNullOrBlank()) {
                    return result.url
                }
                
                // 만약 동영상 제한이 아니라 서버 자체가 깨진 것이라면 화이트리스트 파기 및 재탐색 가동
                if (result.isServerBroken) {
                    println("[YT_DEBUG] [Whitelist] 화이트리스트 서버 오작동 감지. 폭파 후 재탐색을 시작합니다.")
                    failedInstance = whitelistInstance
                    cachedAutoInstance = null
                    appPrefs?.edit()?.remove("yt_cobalt_whitelist_server")?.apply()
                } else {
                    // 비디오 자체 지역 차단인 경우 더 이상의 무의미한 탐색을 하지 않고 즉시 null 반환 (속도 확보)
                    println("[YT_DEBUG] [Whitelist] 비디오 자체 지역 차단입니다. 탐색을 조기 종료합니다.")
                    return null
                }
            }

            // 3. 화이트리스트 서버가 없거나 실패한 경우에만 동적 재탐색 시작
            println("[YT_DEBUG] [Self-Healing] api/tests 목록 탐색을 시작합니다.")
            val candidates = fetchAllWorkingCobaltInstances()
            
            // 직전에 실패한 주소는 당연히 제외
            val activeCandidates = if (failedInstance != null) {
                candidates.filter { it != failedInstance }
            } else {
                candidates
            }
            
            println("[YT_DEBUG] [Self-Healing] 현재 가용한 익명 고화질 후보 리스트 (${activeCandidates.size}개): $activeCandidates")

            for (currentInstance in activeCandidates) {
                println("[YT_DEBUG] [Self-Healing] 후보 서버 호출 시도: $currentInstance")
                val result = executeCobaltRequest(videoUrl, quality, currentInstance, apiKey)
                if (!result.url.isNullOrBlank()) {
                    // 성공 즉시 신규 화이트리스트 서버로 영구 등록
                    println("[YT_DEBUG] [Self-Healing] 신규 작동 서버 검증 완료 및 화이트리스트 등록: $currentInstance")
                    cachedAutoInstance = currentInstance
                    appPrefs?.edit()?.putString("yt_cobalt_whitelist_server", currentInstance)?.apply()
                    return result.url
                }
                
                // 만약 서버가 깨진 게 아니고 단순 비디오 자체 제한 에러라면, 다른 서버를 찔러도 똑같이 실패하므로 루프를 조기 종료
                if (!result.isServerBroken) {
                    println("[YT_DEBUG] [Self-Healing] 비디오 자체 지역 차단입니다. 후보 순회를 조기 종료합니다.")
                    return null
                }
            }

            // 최후의 보루: 폴백 시도
            if (failedInstance != DEFAULT_FALLBACK_API_URL) {
                val fallbackResult = executeCobaltRequest(videoUrl, quality, DEFAULT_FALLBACK_API_URL, apiKey)
                if (!fallbackResult.url.isNullOrBlank()) {
                    return fallbackResult.url
                }
            }
        } else {
            // 수동 지정 모드인 경우 1회만 정적 호출 실행
            val result = executeCobaltRequest(videoUrl, quality, cobaltInstanceSetting, apiKey)
            return result.url
        }
        return null
    }

    // =================================================================
    // 개별 인스턴스 통신 처리 및 에러 본문 분석 (서버 결함 vs 비디오 결함 분리)
    // =================================================================
    private suspend fun executeCobaltRequest(videoUrl: String, quality: String, instanceUrl: String, apiKey: String?): CobaltResult {
        val apiUrl = if (instanceUrl.endsWith("/")) instanceUrl else "$instanceUrl/"
        return try {
            val jsonString = "{\"url\":\"$videoUrl\",\"videoQuality\":\"$quality\",\"filenameStyle\":\"pretty\"}"
            val requestBody = jsonString.toRequestBody(null)
            
            val headersMap = mutableMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            if (!apiKey.isNullOrBlank()) {
                headersMap["Authorization"] = "Bearer $apiKey"
            }

            val request = Request.Builder()
                .url(apiUrl)
                .post(requestBody)
                .apply {
                    headersMap.forEach { (k, v) -> header(k, v) }
                }
                .build()

            // 2.5초 타임아웃 세팅
            val localOkHttpClient = OkHttpClient.Builder()
                .connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(2500, TimeUnit.MILLISECONDS)
                .build()

            val responseText = withContext(Dispatchers.IO) {
                localOkHttpClient.newCall(request).execute().use { response ->
                    println("[YT_DEBUG] Cobalt API 응답 수신 ($instanceUrl). 코드: ${response.code}")
                    if (response.isSuccessful) {
                        response.body?.string()
                    } else {
                        val errBody = response.body?.string() ?: ""
                        println("[YT_DEBUG] Cobalt API 호출 실패. 에러 코드: ${response.code}, 내용: $errBody")
                        
                        // 지역 제한(video.unavailable) 같은 동영상 자체 에러인 경우 서버는 무죄(isServerBroken = false)
                        val isVideoSpecific = errBody.contains("video.unavailable") || errBody.contains("content")
                        return@use "ERROR:${if (isVideoSpecific) "VIDEO" else "SERVER"}"
                    }
                }
            }

            if (responseText == "ERROR:VIDEO") {
                CobaltResult(null, isServerBroken = false)
            } else if (responseText == "ERROR:SERVER" || responseText.isNullOrBlank()) {
                CobaltResult(null, isServerBroken = true)
            } else {
                val json = parseJson<Map<String, Any>>(responseText)
                val status = json["status"] as? String
                
                if (status == "tunnel" || status == "redirect" || status == "stream") {
                    val streamUrl = json["url"] as? String
                    CobaltResult(streamUrl, isServerBroken = false)
                } else {
                    println("[YT_DEBUG] Cobalt API 처리 상태 비정상: $status. 본문: $responseText")
                    val isVideoSpecific = responseText.contains("video.unavailable") || responseText.contains("content")
                    CobaltResult(null, isServerBroken = !isVideoSpecific)
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] Cobalt API ($apiUrl) 연동 중 통신 장해/예외 발생: ${e.message}")
            // 타임아웃, 커넥션 에러 등 예외는 서버 문제(isServerBroken = true)로 취급
            CobaltResult(null, isServerBroken = true)
        }
    }

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
            val builtLinks = mutableListOf<ExtractorLink>()
            val seenUrls = mutableSetOf<String>()
            val youtubeWatchUrl = "https://www.youtube.com/watch?v=$videoId"

            // =================================================================
            // Cobalt API 기반 서버 사이드 병합 고화질 링크 추출 시도
            // =================================================================
            println("[YT_DEBUG] Cobalt 서버를 통해 병합 링크 생성을 시도합니다.")
            val qualitiesToRequest = listOf("1080", "720")
            for (q in qualitiesToRequest) {
                val mergedUrl = getCobaltMergedLink(youtubeWatchUrl, q)
                if (!mergedUrl.isNullOrBlank()) {
                    println("[YT_DEBUG] 서버 병합 링크 획득 성공 -> ${q}p")
                    builtLinks.add(
                        newExtractorLink(
                            "${this.name} (Direct Download)",
                            "${q}p [Merged - Download]",
                            mergedUrl,
                            type = INFER_TYPE
                        ) {
                            this.referer = mainUrl
                            this.quality = q.toIntOrNull() ?: 1080
                        }
                    )
                }
            }

            // =================================================================
            // 기존 NewPipe Extractor 기반 스트리밍 링크 분석 루틴 구동
            // =================================================================
            try {
                println("[YT_DEBUG] [getUrl] NewPipe linkHandler에서 link 분석 시작")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                println("[YT_DEBUG] [getUrl] s.fetchPage() 네트워크 호출 시작")
                s.fetchPage()
                println("[YT_DEBUG] [getUrl] s.fetchPage() 호출 성공. 제목: '${s.name}', 길이: ${s.length}초")

                val durationSeconds = if (s.length > 0) s.length else 3600L

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
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 비디오 파싱 중 오류 발생: ${e.message}")
                        null 
                    }
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
                    } catch (e: Exception) { 
                        println("[YT_DEBUG] [getUrl] DASH 오디오 파싱 중 오류 발생: ${e.message}")
                        null 
                    }
                }.distinctBy { it.url }

                val audiosByLanguage = audioInfoList.groupBy { it.language }

                try {
                    ytVideosSubtitles[videoId] = s.subtitlesDefault?.filterNotNull() ?: emptyList()
                    println("[YT_DEBUG] [getUrl] 자막 트랙 개수: ${ytVideosSubtitles[videoId]?.size ?: 0}")
                } catch (e: Exception) {
                    println("[YT_DEBUG] [getUrl] 자막 파싱 중 예외 발생: ${e.message}")
                }

                startServerIfNeeded()

                // 플레이어 실시간 재생용 가상 DASH XML 생성
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
                                            this.headers = mapOf(
                                                "User-Agent" to IOS_USER_AGENT,
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

                // 기기 직접 다운로드 호환성이 완벽한 레거시 Muxed 스트림 (소리 포함 360p 등)
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
                            this.headers = mapOf(
                                "User-Agent" to IOS_USER_AGENT,
                                "Origin" to "https://www.youtube.com",
                                "Referer" to "https://www.youtube.com"
                            )
                        }
                    )
                }

            } catch (e: Exception) { 
                println("[YT_DEBUG] [getUrl] NewPipe Extractor 로직 수행 중 오류 감지: ${e.message}")
                logError(e) 
            }

            ytVideos[videoId] = builtLinks.toList()
        }

        // 수집된 모든 결과 전송
        val finalLinks = ytVideos[videoId] ?: emptyList()
        finalLinks.forEach { callback(it) }

        // 자막 트랙 연결
        val finalSubtitles = ytVideosSubtitles[videoId] ?: emptyList()
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
            } catch (e: Exception) { null }
        }.forEach { subtitleCallback(it) }        
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
                } catch (e: Exception) {
                    // 서버 소켓 리스너 에러 로깅 생략
                }
            }
        } catch (e: Exception) { 
            logError(e) 
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val output = PrintWriter(socket.getOutputStream(), true)
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        var path = parts[1].substring(1)
                        if (path.endsWith(".mpd")) path = path.replace(".mpd", "")
                        val content = manifestMap[path]
                        if (content != null) {
                            output.println("HTTP/1.1 200 OK")
                            output.println("Content-Type: application/dash+xml")
                            output.println("Connection: close")
                            output.println("Access-Control-Allow-Origin: *")
                            output.println("")
                            output.println(content)
                        } else {
                            output.println("HTTP/1.1 404 Not Found")
                            output.println("")
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // 연결 처리 예외 무시
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
        return url.replace("&", "&amp;").replace("<", "&gt;")
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
