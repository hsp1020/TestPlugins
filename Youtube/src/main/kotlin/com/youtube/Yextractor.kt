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

        // 동적으로 탐색된 전체 작동 후보 리스트 캐시
        @Volatile
        private var cachedInstancesList: List<String>? = null

        // 실시간으로 선별되어 현재 작동 성공한 고속 화이트리스트 주소 메모리 캐시
        @Volatile
        private var cachedAutoInstance: String? = null

        // 이번 앱 구동 세션 동안 오류(인증 실패 등)가 감지된 비정상 인스턴스 실시간 블랙리스트
        private val sessionBlacklist = ConcurrentHashMap.newKeySet<String>()

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

    // =================================================================
    // [공식 명세 적용] cobalt.directory/api/tests 를 파싱하여 유튜브 status=true 인 전체 인스턴스 확보
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
                    
                    // tests.youtube.status 가 오직 true 인 완전 개방형 활성 서버만 리스트화 (인증 필요 도메인 자동 필터링)
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
    // [개선 완료] 화이트리스트 주소 다이렉트 고속 호출 및 실패 시 동적 힐링 재탐색
    // =================================================================
    private suspend fun getCobaltMergedLink(videoUrl: String, quality: String): String? {
        val appPrefs = com.lagradost.cloudstream3.AcraApplication.context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it)
        }
        
        val cobaltInstanceSetting = appPrefs?.getString("youtube_cobalt_api", "auto") ?: "auto"
        val apiKey = appPrefs?.getString("youtube_cobalt_api_key", null)

        val isAutoMode = cobaltInstanceSetting.equals("auto", ignoreCase = true)

        if (isAutoMode) {
            // 1. 메모리 캐시가 비어 있으면 스마트폰 기기에 영구 저장된 검증 완료 화이트리스트 복원
            if (cachedAutoInstance == null) {
                cachedAutoInstance = appPrefs?.getString("yt_cobalt_whitelist_server", null)
            }

            // 2. 검증된 화이트리스트 서버가 있고 블랙리스트가 아니라면 "cobalt.directory API 연동을 스킵"하고 다이렉트 고속 호출 (대기시간 0초)
            cachedAutoInstance?.let { whitelistInstance ->
                if (!sessionBlacklist.contains(whitelistInstance)) {
                    println("[YT_DEBUG] [Whitelist] 검증된 화이트리스트 주소로 고속 다이렉트 호출합니다: $whitelistInstance")
                    val directResult = executeCobaltRequest(videoUrl, quality, whitelistInstance, apiKey)
                    if (!directResult.isNullOrBlank()) {
                        return directResult
                    }
                }
            }

            // 3. 화이트리스트 서버가 없거나 차단되어 에러가 난 경우, 실시간 동적 힐링 탐색 발동
            println("[YT_DEBUG] [Self-Healing] 화이트리스트가 없거나 만료되었습니다. 작동 리스트 탐색을 시작합니다.")
            val candidates = fetchAllWorkingCobaltInstances()
            val activeCandidates = candidates.filter { !sessionBlacklist.contains(it) }
            
            println("[YT_DEBUG] [Self-Healing] 현재 가용한 우회 후보 리스트 (${activeCandidates.size}개): $activeCandidates")

            // 작동 가능한 모든 인스턴스 전체를 뚫릴 때까지 순차 순회
            for (currentInstance in activeCandidates) {
                val result = executeCobaltRequest(videoUrl, quality, currentInstance, apiKey)
                if (!result.isNullOrBlank()) {
                    // 성공 즉시 해당 서버를 기기 저장소 및 메모리에 "신규 화이트리스트"로 지정하여 영구 캐싱
                    println("[YT_DEBUG] [Self-Healing] 작동하는 최적의 서버 검증 완료. 신규 화이트리스트 등록: $currentInstance")
                    cachedAutoInstance = currentInstance
                    appPrefs?.edit()?.putString("yt_cobalt_whitelist_server", currentInstance)?.apply()
                    return result
                }
            }

            // 최후의 보루: 동적 탐색 후보가 모두 오류인 경우 예비 폴백 서버 최종 호출
            if (!sessionBlacklist.contains(DEFAULT_FALLBACK_API_URL)) {
                val fallbackResult = executeCobaltRequest(videoUrl, quality, DEFAULT_FALLBACK_API_URL, apiKey)
                if (!fallbackResult.isNullOrBlank()) {
                    return fallbackResult
                }
            }
        } else {
            // 수동 지정 모드인 경우 1회만 정적 호출 실행
            return executeCobaltRequest(videoUrl, quality, cobaltInstanceSetting, apiKey)
        }
        return null
    }

    // =================================================================
    // 개별 인스턴스 요청 처리 및 에러 감지 시 화이트리스트 폭파 및 블랙리스트 동적 등록
    // =================================================================
    private suspend fun executeCobaltRequest(videoUrl: String, quality: String, instanceUrl: String, apiKey: String?): String? {
        val appPrefs = com.lagradost.cloudstream3.AcraApplication.context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it)
        }
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

            // 2.5초 이내에 무응답이거나 터진 서버는 칼같이 패스하도록 OkHttpClient 빌더 커스텀
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
                        
                        // 지역 제한(video.unavailable) 같은 동영상 자체 에러인 경우 서버를 블랙리스트에 등록하지 않음
                        val isVideoSpecific = errBody.contains("video.unavailable") || errBody.contains("content")
                        if (!isVideoSpecific) {
                            sessionBlacklist.add(instanceUrl)
                            if (instanceUrl == cachedAutoInstance) {
                                cachedAutoInstance = null
                                appPrefs?.edit()?.remove("yt_cobalt_whitelist_server")?.apply()
                            }
                        }
                        null
                    }
                }
            }

            responseText?.takeIf { it.isNotBlank() }?.let { text ->
                val json = parseJson<Map<String, Any>>(text)
                val status = json["status"] as? String
                
                if (status == "tunnel" || status == "redirect" || status == "stream") {
                    json["url"] as? String
                } else {
                    println("[YT_DEBUG] Cobalt API 처리 상태 비정상: $status. 본문: $text")
                    
                    // 지역 제한(video.unavailable) 같은 동영상 자체 에러인 경우 서버를 블랙리스트에 등록하지 않음
                    val isVideoSpecific = text.contains("video.unavailable") || text.contains("content")
                    if (!isVideoSpecific) {
                        sessionBlacklist.add(instanceUrl)
                        if (instanceUrl == cachedAutoInstance) {
                            cachedAutoInstance = null
                            appPrefs?.edit()?.remove("yt_cobalt_whitelist_server")?.apply()
                        }
                    }
                    null
                }
            }
        } catch (e: Exception) {
            println("[YT_DEBUG] Cobalt API ($apiUrl) 연동 중 통신 장해/예외 발생: ${e.message}")
            // 타임아웃, 커넥션 실패 등 예외 발생 시 기기 화이트리스트 영구 파기 및 세션 블랙리스트 등록
            sessionBlacklist.add(instanceUrl)
            if (instanceUrl == cachedAutoInstance) {
                cachedAutoInstance = null
                appPrefs?.edit()?.remove("yt_cobalt_whitelist_server")?.apply()
            }
            null
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
                println("[YT_DEBUG] [getUrl] NewPipe Extractor 로직 수
