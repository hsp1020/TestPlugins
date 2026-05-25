package com.youtube

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.AcraApplication
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
import org.schabi.newpipe.extractor.services.youtube.PoTokenProvider
import org.schabi.newpipe.extractor.services.youtube.PoTokenResult
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

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

        // CallerRunsPolicy를 적용하여 가상 MPD 및 세그먼트 정체 시 패킷 폐기 방지 및 백프레셔 제어 보장
        private val serverExecutor = ThreadPoolExecutor(
            2, 
            8, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(256),
            ThreadFactory { r -> Thread(r, "YT-DASH-Server-Worker") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        private const val EMBED_URL = "https://www.youtube.com/embed/aqz-KE-bpKQ"

        init {
            // iOS 공식 앱 클라이언트 파싱 기본 활성화
            YoutubeStreamExtractor.setFetchIosClient(true)

            // [최종 구축본 적용]: 1분 시점의 403 Forbidden 에러를 방어하는 정식 무결성 토큰 프로바이더 장착
            YoutubeStreamExtractor.setPoTokenProvider(object : PoTokenProvider {
                override fun getWebClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getWebClientPoToken] 웹 클라이언트 용 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getWebEmbedClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getWebEmbedClientPoToken] 웹 임베드 용 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getAndroidClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getAndroidClientPoToken] 안드로이드 클라이언트 용 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }

                override fun getIosClientPoToken(videoId: String?): PoTokenResult? {
                    println("[YT_DEBUG] [getIosClientPoToken] iOS 클라이언트 용 poToken 요구. 비디오 ID: $videoId")
                    return getPoTokenResultForVideo(videoId)
                }
            })
        }

        // 재생 세션이 새로 열릴 때(loadLinks) 정적 companion 영역의 캐시를 안전하게 해제하기 위한 타겟 헬퍼 함수
        fun clearCache(videoId: String) {
            ytVideos.remove(videoId)
            ytVideosSubtitles.remove(videoId)
        }

        // 특정 플랫폼의 요청을 일괄 수용하여 캐시 대조 혹은 실시간 가상 웹뷰 생성을 분기하는 최종 헬퍼 함수
        private fun getPoTokenResultForVideo(videoId: String?): PoTokenResult? {
            val cached = getCachedTokens()
            if (cached != null) {
                println("[YT_DEBUG] [setPoTokenProvider] 캐시된 poToken이 비디오 [$videoId]에 정상 적용됩니다. 토큰 값: ${cached.first.take(15)}...")
                return PoTokenResult(cached.first, "", "")
            }

            println("[YT_DEBUG] [setPoTokenProvider] 캐시가 유효하지 않아 실시간 웹뷰 자가발전(Blocking)을 통해 신규 토큰을 획득합니다.")
            val generated = kotlinx.coroutines.runBlocking {
                getPoTokenAndVisitor()
            }

            return if (generated != null) {
                println("[YT_DEBUG] [setPoTokenProvider] 새로 취득한 poToken이 비디오 [$videoId]에 최종 정상 바인딩됩니다. 토큰 값: ${generated.first.take(15)}...")
                PoTokenResult(generated.first, "", "")
            } else {
                println("[YT_DEBUG] [setPoTokenProvider] 실시간 poToken 생성 루틴이 완전히 무산되었습니다. 비디오 [$videoId]에 토큰 없이 재생을 시도합니다.")
                null
            }
        }

        // SharedPreferences 캐시 유효성 획득 및 시간 정합성 프린트 헬퍼 함수
        private fun getCachedTokens(): Pair<String, String>? {
            val context = AcraApplication.context ?: return null
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val poToken = prefs.getString("yt_cached_potoken", null)
            val visitorData = prefs.getString("yt_cached_visitor_data", null)
            val timestamp = prefs.getLong("yt_potoken_timestamp", 0L)

            if (poToken.isNullOrBlank() || visitorData.isNullOrBlank()) {
                println("[YT_DEBUG] [getCachedTokens] 기존에 저장된 캐시 토큰 세트가 존재하지 않습니다.")
                return null
            }

            val elapsed = System.currentTimeMillis() - timestamp
            val remainingSec = (3 * 3600 * 1000 - elapsed) / 1000
            
            if (elapsed > 3 * 3600 * 1000) {
                println("[YT_DEBUG] [getCachedTokens] 캐시 토큰 유효기한 만료 감지 (경과 시간: ${elapsed / 1000}초, 유효한 한계시간: 10800초)")
                return null
            }

            val expDate = java.util.Date(timestamp + 3 * 3600 * 1000)
            println("[YT_DEBUG] [getCachedTokens] 신선한 캐시 발견. 유효 남은시간: ${remainingSec}초 (만료 시각: $expDate)")
            return Pair(poToken, visitorData)
        }

        // SharedPreferences 캐시 데이터 저장 및 유효기한 타임스탬프 계산 헬퍼 함수
        private fun saveCachedTokens(poToken: String, visitorData: String) {
            val context = AcraApplication.context ?: return
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val now = System.currentTimeMillis()
            prefs.edit()
                .putString("yt_cached_potoken", poToken)
                .putString("yt_cached_visitor_data", visitorData)
                .putLong("yt_potoken_timestamp", now)
                .apply()
            
            val expDate = java.util.Date(now + 3 * 3600 * 1000)
            println("[YT_DEBUG] [saveCachedTokens] 토큰 영구 캐싱 성공. poToken: ${poToken.take(15)}..., visitorData: ${visitorData.take(15)}..., 캐시 등록 시간: ${java.util.Date(now)}, 만료 예정 시간: $expDate")
        }

        /**
         * 안드로이드 메인 스레드 안전 루프를 이용한 Headless WebView 구동 및 BotGuard 토큰 파싱 메서드
         */
        private suspend fun getPoTokenAndVisitor(): Pair<String, String>? = withContext(Dispatchers.Main) {
            val context = AcraApplication.context ?: return@withContext null
            println("[YT_DEBUG] [getPoTokenAndVisitor] Headless WebView 컴파일 준비 및 인증용 로드 시작. 대상 URL: $EMBED_URL")

            suspendCancellableCoroutine { continuation ->
                val webView = WebView(context)

                webView.apply {
                    @SuppressLint("SetJavaScriptEnabled")
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true

                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView?, url: String?) {
                            println("[YT_DEBUG] [getPoTokenAndVisitor] WebView 페이지 렌더링 완료. window.ytplayer 내부 구성 객체 추출을 개시합니다.")
                            // 다중 경로 가로채기 및 실패 원인 상세 진단용 자바스크립트 가로채기 엔진 구동
                            evaluateJavascript(
                                "(function() { " +
                                "  try { " +
                                "    var args = window.ytplayer?.config?.args; " +
                                "    if (!args) return JSON.stringify({error: 'window.ytplayer.config.args 객체가 소스에 존재하지 않습니다.'}); " +
                                "    var playerResponse = args.raw_player_response; " +
                                "    if (!playerResponse && args.player_response) { " +
                                "      playerResponse = JSON.parse(args.player_response); " +
                                "    } " +
                                "    if (!playerResponse) return JSON.stringify({error: 'player_response를 파싱하지 못했습니다.'}); " +
                                "    var poToken = playerResponse?.serviceIntegrityDimensions?.poToken || playerResponse?.streamingData?.poToken; " +
                                "    var visitor = args.visitor_data || playerResponse?.responseContext?.visitorData; " +
                                "    if (!poToken) return JSON.stringify({error: '인증용 poToken 식별자가 누락되었습니다.'}); " +
                                "    if (!visitor) return JSON.stringify({error: '인증용 visitorData 식별자가 누락되었습니다.'}); " +
                                "    return JSON.stringify({poToken: poToken, visitorData: visitor}); " +
                                "  } catch(e) { " +
                                "    return JSON.stringify({error: '자바스크립트 실행 중 치명적 예외 발생: ' + e.message}); " +
                                "  } " +
                                "})();"
                            ) { jsonResult ->
                                println("[YT_DEBUG] [getPoTokenAndVisitor] 웹뷰 자바스크립트 엔진 평가 결과 원본 수신: $jsonResult")
                                if (jsonResult != null && jsonResult != "null" && jsonResult.isNotBlank()) {
                                    try {
                                        val cleanedJson = jsonResult.replace("\\\"", "\"").removePrefix("\"").removeSuffix("\"")
                                        val json = JSONObject(cleanedJson)

                                        if (json.has("error")) {
                                            println("[YT_DEBUG] [getPoTokenAndVisitor] 자가발전 엔진 내부 에러 발견: ${json.getString("error")}")
                                            if (continuation.isActive) continuation.resume(null)
                                            destroy()
                                            return@evaluateJavascript
                                        }

                                        val poToken = json.optString("poToken")
                                        val visitorData = json.optString("visitorData")

                                        if (poToken.isNotBlank() && visitorData.isNotBlank()) {
                                            println("[YT_DEBUG] [getPoTokenAndVisitor] 미디어 인증 토큰을 정상적으로 뽑아냈습니다. poToken: ${poToken.take(15)}...")
                                            saveCachedTokens(poToken, visitorData)
                                            if (continuation.isActive) {
                                                continuation.resume(Pair(poToken, visitorData))
                                            }
                                            destroy()
                                        } else {
                                            println("[YT_DEBUG] [getPoTokenAndVisitor] 파싱 실패: 토큰 값이 비어있습니다.")
                                            if (continuation.isActive) continuation.resume(null)
                                            destroy()
                                        }
                                    } catch (e: Exception) {
                                        println("[YT_DEBUG] [getPoTokenAndVisitor] 웹뷰 결과 파싱 예외 발생: ${e.message}")
                                        if (continuation.isActive) continuation.resume(null)
                                        destroy()
                                    }
                                } else {
                                    println("[YT_DEBUG] [getPoTokenAndVisitor] 수신된 웹뷰 결과가 유효하지 않습니다.")
                                    if (continuation.isActive) continuation.resume(null)
                                    destroy()
                                }
                            }
                        }
                    }
                }

                webView.loadUrl(EMBED_URL)

                continuation.invokeOnCancellation {
                    // 비정상 취소 동작 시 UI 메인 루퍼 핸들러를 경유한 안전 소멸 보장
                    android.os.Handler(android.os.Looper.getMainLooper()).post {
                        println("[YT_DEBUG] [getPoTokenAndVisitor] 태스크 취소 감지. 자원 정리를 위해 웹뷰 소멸을 실행합니다.")
                        webView.destroy()
                    }
                }
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
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl
        println("[YT_DEBUG] [getUrl] 가공된 cleanedUrl: $cleanedUrl, 비디오 ID: $videoId")

        // 재생 진입(loadLinks) 단계에서 명시적으로 삭제가 수행되므로, 동일 재생 세션 내부 처리를 위해 기존 강제 캐시 삭제 코드는 제거되었습니다.

        val cachedLinks = ytVideos[videoId]
        if (cachedLinks.isNullOrEmpty()) {
            println("[YT_DEBUG] [getUrl] 분석을 진행합니다.")
            try {
                println("[YT_DEBUG] [getUrl] NewPipe linkHandler에서 link 분석 시작")
                val link = YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(cleanedUrl)
                val s = object : YoutubeStreamExtractor(ServiceList.YouTube, link) {}
                println("[YT_DEBUG] [getUrl] s.fetchPage() 네트워크 호출 시작 (무결성 토큰 적용 단계)")
                s.fetchPage()
                println("[YT_DEBUG] [getUrl] s.fetchPage() 추출 프로세스 완료. 제목: '${s.name}', 길이: ${s.length}초")

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
                            this.headers = mapOf(
                                "User-Agent" to YoutubeUtils.IOS_USER_AGENT,
                                "Origin" to "https://www.youtube.com",
                                "Referer" to "https://www.youtube.com"
                            )
                        }
                    )
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
                            client.soTimeout = 5000 
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
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), java.nio.charset.StandardCharsets.UTF_8))
                val os = socket.getOutputStream()
                val line = reader.readLine()
                if (line != null && line.startsWith("GET")) {
                    val parts = line.split(" ")
                    if (parts.size > 1) {
                        // 쿼리 매개변수(?) 및 불필요 경로를 정규화하여 안정적인 UUID 획득
                        val cleanPath = parts[1].substringAfter("/").substringBefore("?")
                        val path = if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
                            cleanPath.substring(0, cleanPath.length - 4)
                        } else {
                            cleanPath
                        }
                        val content = manifestMap[path]
                        if (content != null) {
                            val bodyBytes = content.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                            val headerBuilder = java.lang.StringBuilder()
                            headerBuilder.append("HTTP/1.1 200 OK\r\n")
                            headerBuilder.append("Content-Type: application/dash+xml\r\n")
                            headerBuilder.append("Content-Length: ${bodyBytes.size}\r\n")
                            headerBuilder.append("Connection: close\r\n")
                            headerBuilder.append("Access-Control-Allow-Origin: *\r\n")
                            headerBuilder.append("\r\n")

                            val headerBytes = headerBuilder.toString().toByteArray(java.nio.charset.StandardCharsets.UTF_8)
                            os.write(headerBytes)
                            os.write(bodyBytes)
                            os.flush()
                        } else {
                            println("[YT_DEBUG] [handleClient] 에러: 메모리에 매칭되는 가상 MPD가 없습니다. UUID: $path (404 반환)")
                            val errorResponse = "HTTP/1.1 404 Not Found\r\nConnection: close\r\n\r\n"
                            os.write(errorResponse.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                            os.flush()
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
