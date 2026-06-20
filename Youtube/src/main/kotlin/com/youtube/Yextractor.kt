package com.youtube

import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong

data class HlsParsedStream(
    val labelName: String,
    val url: String,
    val height: Int,
    val isOriginal: Boolean
)

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        @Volatile
        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        private val streamCounter = AtomicLong(0)

        // ---------------- [ 구성 정보 및 복호화 캐시 가속 엔진 ] ----------------
        @Volatile
        private var cachedSts: Int = 20002
        @Volatile
        private var cachedPlayerId: String = ""
        @Volatile
        private var lastCacheTime: Long = 0L
        private const val CACHE_TTL = 6 * 60 * 60 * 1000L // 6시간 TTL

        // 이미 복호화된 서명 값을 담아두는 중복 필터 캐시
        private val decodedSigCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        private val decodedNCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )
        // ----------------------------------------------------------------------

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 150
                }
            }
        )

        private val streamUrlCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 300
                }
            }
        )

        private val recentTriggers: MutableMap<String, Long> = Collections.synchronizedMap(
            object : LinkedHashMap<String, Long>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean {
                    return size > 100
                }
            }
        )

        private val proxyHttpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()

        private val VISITOR_DATA_REGEX = Regex(""""VISITOR_DATA":"([^"]+)"""")
        private val CHUNK_ID_REGEX = Regex("id=([^&]+)")

        private const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
        private const val SAFARI_CLIENT_VERSION = "2.20260114.08.00"
        private const val VR_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
            
        init {
            println("[YT_DEBUG_INIT] 유튜브 DASH 추출기 코루틴 가상서버 초기화 완료")
        }

        private fun generateCpn(): String {
            val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return (1..16).map { allowedChars.random() }.joinToString("")
        }

        private fun openInExternalApp(packageName: String, targetUrl: String) {
            val context = AcraApplication.context ?: return
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(android.content.Intent.EXTRA_TEXT, targetUrl)
                        setPackage(packageName)
                        addCategory(android.content.Intent.CATEGORY_DEFAULT)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    println("[YT_PROXY_DEBUG] 외부 앱 연동 실패: ${e.message}")
                }
            }
        }

        private suspend fun handleClient(client: Socket) = withContext(Dispatchers.IO) {
            try {
                client.use { socket ->
                    val inputStream = socket.getInputStream()
                    val reader = BufferedReader(InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))
                    val output = socket.getOutputStream()
                    val outWriter = PrintWriter(output, true)

                    val requestLine = reader.readLine() ?: return@withContext
                    val parts = requestLine.split(" ")
                    if (parts.size < 2) return@withContext

                    val fullPath = parts[1]
                    val clientHeaders = LinkedHashMap<String, String>()
                    var headerLines = 0
                    while (headerLines++ < 100) {
                        val line = reader.readLine()
                        if (line.isNullOrBlank()) break
                        val colonIndex = line.indexOf(':')
                        if (colonIndex != -1) {
                            clientHeaders[line.substring(0, colonIndex).trim().lowercase()] = line.substring(colonIndex + 1).trim()
                        }
                    }

                    val cleanPath = fullPath.substringBefore("?")
                    val queryParams = fullPath.substringAfter("?", "")

                    when {
                        cleanPath.startsWith("/open_app") -> {
                            handleOpenAppRequest(queryParams, outWriter)
                        }
                        cleanPath.startsWith("/chunk") -> {
                            val idMatch = CHUNK_ID_REGEX.find(queryParams)
                            val chunkId = idMatch?.groupValues?.get(1)
                            val targetYoutubeUrl = if (chunkId != null) streamUrlCache[chunkId] else null

                            if (targetYoutubeUrl != null) {
                                proxyChunkToYouTube(socket, targetYoutubeUrl, clientHeaders)
                            } else {
                                outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                                outWriter.flush()
                            }
                        }
                        cleanPath.endsWith(".mpd", ignoreCase = true) -> {
                            val path = cleanPath.substringBeforeLast(".mpd").removePrefix("/")
                            val content = manifestMap[path]
                            if (content != null) {
                                outWriter.print("HTTP/1.1 200 OK\r\nContent-Type: application/dash+xml\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n$content")
                                outWriter.flush()
                            } else {
                                outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                                outWriter.flush()
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] 프록시 연결 처리 예외: ${e.message}")
            }
        }

        private fun handleOpenAppRequest(queryParams: String, outWriter: PrintWriter) {
            try {
                var pkg: String? = null
                var targetUrl: String? = null
                
                val pairs = queryParams.split("&")
                for (pair in pairs) {
                    val idx = pair.indexOf("=")
                    if (idx != -1) {
                        val key = pair.substring(0, idx)
                        val value = pair.substring(idx + 1)
                        if (key == "package") {
                            pkg = java.net.URLDecoder.decode(value, "UTF-8")
                        } else if (key == "url") {
                            targetUrl = java.net.URLDecoder.decode(value, "UTF-8")
                        }
                    }
                }
                
                if (targetUrl != null && pkg != null) {
                    val triggerKey = "$pkg|$targetUrl"
                    val now = System.currentTimeMillis()
                    val lastTrigger = recentTriggers[triggerKey] ?: 0L
                    
                    if (now - lastTrigger > 3000L) {
                        recentTriggers[triggerKey] = now
                        openInExternalApp(pkg, targetUrl)
                    } else {
                        println("[YT_PROXY_DEBUG] 중복 외부 앱 요청 차단: $triggerKey")
                    }
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] open_app 파싱 예외: ${e.message}")
            } finally {
                outWriter.print("HTTP/1.1 204 No Content\r\n")
                outWriter.print("Connection: close\r\n")
                outWriter.print("Access-Control-Allow-Origin: *\r\n")
                outWriter.print("\r\n")
                outWriter.flush()
            }
        }

        private fun proxyChunkToYouTube(socket: Socket, targetUrl: String, clientHeaders: Map<String, String>) {
            try {
                val requestBuilder = okhttp3.Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", YoutubeUtils.ANDROID_VR_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")

                clientHeaders.forEach { (key, value) ->
                    val lowerKey = key.lowercase()
                    if (lowerKey != "host" && lowerKey != "user-agent" && lowerKey != "origin" && lowerKey != "referer" && lowerKey != "accept-encoding") {
                        requestBuilder.header(key, value)
                    }
                }

                val request = requestBuilder.build()
                proxyHttpClient.newCall(request).execute().use { response ->
                    if (response.code == 403 || response.code == 400) {
                        val output = socket.getOutputStream()
                        val rawOutWriter = java.io.PrintWriter(output, false)
                        rawOutWriter.print("HTTP/1.1 ${response.code} ${response.message}\r\n\r\n")
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
                    rawOutWriter.print("Connection: close\r\n\r\n")
                    rawOutWriter.flush()

                    val body = response.body
                    if (body != null) {
                        body.byteStream().use { input ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    output.flush()
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] 프록시 데이터 전송 실패: ${e.message}")
            }
        }

        // 복호화 캐시를 통해 중복된 키 값은 필터링하여 디코더 서버 통신 횟수를 단축하는 최적화
        private fun decodeBatch(playerId: String, sigs: List<String>, ns: List<String>): Pair<Map<String, String>, Map<String, String>> {
            val sigResults = mutableMapOf<String, String>()
            val nResults = mutableMapOf<String, String>()

            if (sigs.isEmpty() && ns.isEmpty()) {
                return Pair(sigResults, nResults)
            }

            // 1) 이미 해독 완료된 토큰은 캐시 맵에서 결과 즉각 확인
            val missingSigs = sigs.filter { 
                val cached = decodedSigCache[it]
                if (cached != null) sigResults[it] = cached
                cached == null
            }
            val missingNs = ns.filter { 
                val cached = decodedNCache[it]
                if (cached != null) nResults[it] = cached
                cached == null
            }

            // 만약 새로 해독할 시그니처가 없다면 원격 API 호출을 완전히 무시 (0ms 즉시 반환)
            if (missingSigs.isEmpty() && missingNs.isEmpty()) {
                return Pair(sigResults, nResults)
            }

            println("[TRACK] -> decodeBatch API 요청 진행. 미보유 항목 수: s(${missingSigs.size}), n(${missingNs.size})")

            try {
                val urlBuilder = StringBuilder("https://api.pipepipe.dev/decoder/decode")
                urlBuilder.append("?player=").append(playerId)

                if (missingNs.isNotEmpty()) {
                    urlBuilder.append("&n=")
                    urlBuilder.append(missingNs.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") })
                }

                if (missingSigs.isNotEmpty()) {
                    urlBuilder.append("&sig=")
                    urlBuilder.append(missingSigs.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") })
                }

                val request = okhttp3.Request.Builder()
                    .url(urlBuilder.toString())
                    .header("User-Agent", "PipePipe/4.9.0")
                    .build()

                proxyHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.optString("type") != "result") {
                        return Pair(sigResults, nResults)
                    }

                    val responses = jsonResponse.optJSONArray("responses") ?: JSONArray()
                    var responseIndex = 0

                    if (missingNs.isNotEmpty()) {
                        val nResponse = responses.optJSONObject(responseIndex++)
                        if (nResponse != null && nResponse.optString("type") == "result") {
                            val nData = nResponse.optJSONObject("data")
                            if (nData != null) {
                                for (nParam in missingNs) {
                                    val decodedValue = nData.optString(nParam)
                                    if (decodedValue.isNotEmpty()) {
                                        nResults[nParam] = decodedValue
                                        decodedNCache[nParam] = decodedValue
                                    }
                                }
                            }
                        }
                    }

                    if (missingSigs.isNotEmpty()) {
                        val sigResponse = responses.optJSONObject(responseIndex)
                        if (sigResponse != null && sigResponse.optString("type") == "result") {
                            val sigData = sigResponse.optJSONObject("data")
                            if (sigData != null) {
                                for (sig in missingSigs) {
                                    val decodedValue = sigData.optString(sig)
                                    if (decodedValue.isNotEmpty()) {
                                        sigResults[sig] = decodedValue
                                        decodedSigCache[sig] = decodedValue
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[TRACK] 🚨 decodeBatch API 실패: ${e.message}")
            }

            return Pair(sigResults, nResults)
        }

        fun deobfuscateManifestUrl(manifestUrl: String, playerId: String): String {
            if (manifestUrl.isBlank() || playerId.isBlank()) {
                return manifestUrl
            }
            try {
                val match = Regex("""/n/([^/&?]+)""").find(manifestUrl)
                if (match != null) {
                    val n = match.groupValues[1]
                    val decodedNs = decodeBatch(playerId, emptyList(), listOf(n)).second
                    val decodedN = decodedNs[n]
                    if (!decodedN.isNullOrBlank()) {
                        return manifestUrl.replace("/n/$n", "/n/$decodedN")
                    }
                }
            } catch (e: Exception) {
                println("[TRACK] 🚨 deobfuscateManifestUrl 실패: ${e.message}")
            }
            return manifestUrl
        }

        private fun extractHlsStringAttribute(line: String, attribute: String): String? {
            var match = Regex("""$attribute="([^"]+)"""").find(line)
            if (match != null) return match.groupValues[1]
            match = Regex("""$attribute=([^,\s]+)""").find(line)
            if (match != null) return match.groupValues[1]
            return null
        }

        private fun extractHlsXtagsValue(url: String, key: String): String? {
            try {
                val decoded = java.net.URLDecoder.decode(url, "UTF-8")
                val match = Regex("""xtags=([^/&?]+)""").find(decoded)
                if (match != null) {
                    val xtags = match.groupValues[1]
                    for (part in xtags.split(":")) {
                        val kv = part.split("=", limit = 2)
                        if (kv.size == 2 && kv[0] == key) {
                            return kv[1]
                        }
                    }
                }
            } catch (e: Exception) {
                println("[TRACK] 🚨 xtags 파싱 예외: ${e.message}")
            }
            return null
        }

        fun getLanguageLabel(locale: String, targetLang: String): String {
            return try {
                val sysLocale = java.util.Locale(locale)
                val targetLocale = java.util.Locale(targetLang)
                sysLocale.getDisplayName(targetLocale).ifEmpty { locale.uppercase() }
            } catch (e: Exception) {
                locale.uppercase()
            }
        }
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    data class StreamInfo(
        var url: String, 
        val mimeType: String,
        val codec: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?,
        val s: String?,
        val sp: String?,
        val n: String?
    )

    data class AudioInfo(
        var url: String, 
        val mimeType: String,
        val codec: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val locale: String,
        val audioTrackId: String?,
        val displayName: String,
        val s: String?,
        val sp: String?,
        val n: String?
    )

    private fun getAppRedirectUrl(packageName: String, targetUrl: String): String? {
        startServerIfNeeded()
        return if (serverPort != 0) {
            try {
                val encodedPkg = java.net.URLEncoder.encode(packageName, "UTF-8")
                val encodedUrl = java.net.URLEncoder.encode(targetUrl, "UTF-8")
                "http://127.0.0.1:$serverPort/open_app?package=$encodedPkg&url=$encodedUrl"
            } catch (e: Exception) {
                null
            }
        } else {
            null
        }
    }

    // 전역 구성 정보 캐시를 적용하여 매번 발생하는 1.2초의 latest-player 조회를 제거한 고속 추출기
    private suspend fun fetchPlayerResponse(videoId: String): Triple<JSONObject?, Long, String> = withContext(Dispatchers.IO) {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        
        println("[TRACK] 1. Parallel HTML Fetch & Player Config Retrieval")
        
        val watchHtmlDeferred = async {
            try {
                app.get(watchUrl, headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)).text
            } catch (e: Exception) {
                println("[TRACK] 🚨 watch HTML 다운로드 실패: ${e.message}")
                ""
            }
        }

        val pipePlayerDeferred = async {
            val now = System.currentTimeMillis()
            // 캐시 유효 시간(6시간) 이내라면 원격 통신을 건너뛰고 기존 sts 정보 즉각 활용 (속도 극대화)
            if (cachedPlayerId.isNotEmpty() && (now - lastCacheTime < CACHE_TTL)) {
                return@async JSONObject().apply {
                    put("signatureTimestamp", cachedSts)
                    put("player", cachedPlayerId)
                }
            }
            try {
                val pipeReq = okhttp3.Request.Builder()
                    .url("https://api.pipepipe.dev/decoder/latest-player")
                    .header("User-Agent", "PipePipe/4.9.0")
                    .build()
                proxyHttpClient.newCall(pipeReq).execute().use { response ->
                    if (response.isSuccessful) {
                        val body = response.body?.string() ?: ""
                        val json = JSONObject(body)
                        cachedSts = json.optInt("signatureTimestamp", 20002)
                        cachedPlayerId = json.optString("player", "")
                        lastCacheTime = now
                        json
                    } else null
                }
            } catch (e: Exception) {
                println("[TRACK] 🚨 PipePipe API 호출 실패: ${e.message}")
                null
            }
        }

        val html = watchHtmlDeferred.await()
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: ""

        val context = com.lagradost.cloudstream3.AcraApplication.context
        val provider = com.lagradost.cloudstream3.APIHolder.allProviders.find { it.name == "YouTube" }
        val prefs = (provider as? com.lagradost.cloudstream3.ar.youtube.YoutubeProvider)?.appPrefs 
            ?: context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
        
        val langCode = prefs?.getString("youtube_language", "en") ?: "en"
        val (cookies, sapisidVal) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, langCode, visitorData, false)
        val isLoggedIn = !sapisidVal.isNullOrBlank()

        val pipeJson = pipePlayerDeferred.await()
        var sts = 20002
        var playerId = ""
        if (pipeJson != null) {
            sts = pipeJson.optInt("signatureTimestamp", sts)
            playerId = pipeJson.optString("player", "")
        }

        var finalJson: JSONObject? = null
        var lengthSeconds = 3600L

        // 사용자 피드백에 따라 임시 가속 타임아웃을 폐기하고, 원래의 신뢰성 높은 로그인 통신으로 안정적 대기 진행
        if (isLoggedIn) {
            println("[TRACK] 2. SAFARI API Request 처리 중")
            val safariApiUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            val safariPayload = JSONObject(mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "utcOffsetMinutes" to 0,
                        "timeZone" to "UTC",
                        "hl" to langCode,
                        "gl" to if (langCode == "ko") "KR" else "US",
                        "userAgent" to SAFARI_USER_AGENT,
                        "clientName" to "WEB",
                        "clientVersion" to SAFARI_CLIENT_VERSION
                    )
                ),
                "playbackContext" to mapOf(
                    "contentPlaybackContext" to mapOf(
                        "html5Preference" to "HTML5_PREF_WANTS",
                        "signatureTimestamp" to sts
                    )
                ),
                "cpn" to generateCpn(),
                "videoId" to videoId,
                "contentCheckOk" to true,
                "racyCheckOk" to true
            )).toString()

            val requestBody = safariPayload.toRequestBody("application/json".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url(safariApiUrl)
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", SAFARI_USER_AGENT)
                .addHeader("X-YouTube-Client-Name", "1")
                .addHeader("X-Youtube-Client-Version", SAFARI_CLIENT_VERSION)
                .addHeader("Cookie", cookies)
                .addHeader("Authorization", YoutubeUtils.getSapisidHash(sapisidVal!!, "https://www.youtube.com"))
                .addHeader("X-Origin", "https://www.youtube.com")
                .addHeader("DNT", "1")
                .build()

            try {
                proxyHttpClient.newCall(request).execute().use { response ->
                    val res = response.body?.string() ?: ""
                    if (res.isNotEmpty()) {
                        val json = JSONObject(res)
                        val playabilityStatus = json.optJSONObject("playabilityStatus")
                        val status = playabilityStatus?.optString("status")
                        
                        if (status == "OK" || json.has("streamingData")) {
                            finalJson = json
                            lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                        }
                    }
                }
            } catch (e: Exception) { 
                println("[TRACK] 🚨 SAFARI API 호출 중 예외: ${e.message}") 
            }
        }

        if (finalJson == null) {
            println("[TRACK] 3. ANDROID_VR API Request (순차 폴백 활성화)")
            val vrApiUrl = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&key=$VR_API_KEY"
            val vrHeaders = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to YoutubeUtils.ANDROID_VR_USER_AGENT,
                "X-Goog-Api-Format-Version" to "2"
            )
            val vrPayload = mapOf(
                "videoId" to videoId,
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "ANDROID_VR",
                        "clientVersion" to "1.65.10",
                        "androidSdkVersion" to 32,
                        "osName" to "Android",
                        "osVersion" to "12L",
                        "deviceMake" to "Oculus",
                        "deviceModel" to "Quest 3",
                        "hl" to langCode,
                        "gl" to if (langCode == "ko") "KR" else "US",
                        "visitorData" to visitorData
                    )
                ),
                "playbackContext" to mapOf(
                    "contentPlaybackContext" to mapOf(
                        "signatureTimestamp" to sts
                    )
                )
            )

            try {
                val res = app.post(vrApiUrl, json = vrPayload, headers = vrHeaders).text
                val json = JSONObject(res)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")

                if (status == "OK" || json.has("streamingData")) {
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                }
            } catch (e: Exception) { 
                println("[TRACK] 🚨 ANDROID_VR API 호출 실패: ${e.message}") 
            }
        }

        Triple(finalJson, lengthSeconds, playerId)
    }

    private suspend fun parseHlsManifest(
        hlsUrl: String,
        playerId: String,
        langCode: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalHlsUrl = if (playerId.isNotBlank()) deobfuscateManifestUrl(hlsUrl, playerId) else hlsUrl
        if (finalHlsUrl.isBlank()) return

        try {
            val m3u8Body = proxyHttpClient.newCall(
                okhttp3.Request.Builder().url(finalHlsUrl).header("User-Agent", SAFARI_USER_AGENT).build()
            ).execute().use { it.body?.string() ?: "" }

            val lines = m3u8Body.split("\n")
            val hlsStreamsParsedLocal = mutableListOf<HlsParsedStream>()

            var defaultHlsLang = ""
            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
                if (i + 1 >= lines.size) break
                val streamUrl = lines[i + 1].trim()
                val audioContentId = extractHlsStringAttribute(line, "YT-EXT-AUDIO-CONTENT-ID") ?: ""
                val acont = extractHlsXtagsValue(streamUrl, "acont")
                
                if (acont == "original" && audioContentId.isNotEmpty()) {
                    defaultHlsLang = audioContentId.split(".")[0].split("-")[0].lowercase()
                    break
                }
            }

            if (defaultHlsLang.isEmpty()) {
                for (line in lines) {
                    if (line.trim().startsWith("#EXT-X-MEDIA:") && line.contains("TYPE=AUDIO")) {
                        val isDefaultAttr = extractHlsStringAttribute(line, "DEFAULT") ?: ""
                        val languageAttr = extractHlsStringAttribute(line, "LANGUAGE") ?: ""
                        if (isDefaultAttr.equals("YES", ignoreCase = true) && languageAttr.isNotEmpty()) {
                            defaultHlsLang = languageAttr.split("-")[0].lowercase()
                            break
                        }
                    }
                }
            }

            for (i in lines.indices) {
                val line = lines[i].trim()
                if (!line.startsWith("#EXT-X-STREAM-INF:")) continue
                if (i + 1 >= lines.size) break
                val streamUrl = lines[i + 1].trim()
                if (streamUrl.isEmpty() || streamUrl.startsWith("#")) continue

                val resolution = extractHlsStringAttribute(line, "RESOLUTION") ?: ""
                val audioContentId = extractHlsStringAttribute(line, "YT-EXT-AUDIO-CONTENT-ID") ?: ""

                val height = if (resolution.contains("x")) {
                    resolution.split("x").getOrNull(1)?.toIntOrNull() ?: 0
                } else 0

                if (height <= 0) continue

                var isOriginal = false
                var locale = "und"

                if (audioContentId.isNotEmpty()) {
                    val langPart = audioContentId.split(".")[0]
                    locale = langPart.split("-")[0].lowercase()
                    if (locale == defaultHlsLang) {
                        continue
                    }
                } else {
                    isOriginal = true
                    locale = defaultHlsLang.ifEmpty { "und" }
                }

                val langLabel = if (locale != "und") getLanguageLabel(locale, langCode) else ""
                val suffix = if (isOriginal) {
                    when (langCode.lowercase()) {
                        "ko" -> "원본"
                        "ar" -> "الأصلي"
                        else -> "OG"
                    }
                } else {
                    when (langCode.lowercase()) {
                        "ko" -> "더빙"
                        "ar" -> "دبلجة"
                        else -> "Dub"
                    }
                }
                
                val displayName = if (langLabel.isNotEmpty()) "$langLabel($suffix)" else ""
                val labelName = if (displayName.isNotEmpty()) "YouTube ${height}p [$displayName]" else "YouTube ${height}p"

                hlsStreamsParsedLocal.add(HlsParsedStream(labelName, streamUrl, height, isOriginal))
            }

            val sortedStreams = hlsStreamsParsedLocal.sortedWith(
                compareByDescending<HlsParsedStream> { it.height }
                .thenByDescending { it.isOriginal }
            )

            sortedStreams.forEach { stream ->
                callback(
                    newExtractorLink(
                        this@YoutubeExtractor.name,
                        stream.labelName,
                        stream.url,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = mainUrl
                        this.quality = stream.height
                        this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                    }
                )
            }
        } catch (e: Exception) {
            println("[TRACK] 🚨 HLS 파싱 실패: ${e.message}")
        }
    }

    private suspend fun parseAdaptiveFormats(
        streamingData: JSONObject,
        playerId: String,
        durationSeconds: Long,
        langCode: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
        val videoOnlyList = ArrayList<StreamInfo>()
        val audioInfoList = ArrayList<AudioInfo>()

        for (i in 0 until adaptiveFormats.length()) {
            val format = adaptiveFormats.getJSONObject(i)
            val itag = format.optString("itag")
            
            var streamUrl = format.optString("url")
            var s: String? = null
            var sp: String? = null

            if (streamUrl.isNullOrEmpty()) {
                val cipherStr = format.optString("signatureCipher").ifEmpty { format.optString("cipher") }
                if (cipherStr.isNotEmpty()) {
                    val params = cipherStr.split("&").associate { 
                        val parts = it.split("=", limit = 2)
                        parts[0] to java.net.URLDecoder.decode(parts.getOrElse(1) { "" }, "UTF-8")
                    }
                    streamUrl = params["url"] ?: ""
                    s = params["s"]
                    sp = params["sp"] ?: "sig"
                } else {
                    continue
                }
            }

            if (streamUrl.isNullOrEmpty()) continue

            var n: String? = null
            val nMatch = Regex("""[?&]n=([^&]+)""").find(streamUrl)
            if (nMatch != null) {
                n = nMatch.groupValues[1]
            }

            val rawMimeType = format.optString("mimeType", "")
            var cleanMimeType = rawMimeType
            var codec = ""
            
            val semicolonIndex = rawMimeType.indexOf(";")
            if (semicolonIndex != -1) {
                cleanMimeType = rawMimeType.substring(0, semicolonIndex).trim()
                val rawCodecs = rawMimeType.substring(semicolonIndex + 1)
                val codecStart = rawCodecs.indexOf("codecs=")
                if (codecStart != -1) {
                    val afterCodecs = rawCodecs.substring(codecStart + 7).trim()
                    val firstQuote = afterCodecs.indexOf("\"")
                    if (firstQuote != -1) {
                        val secondQuote = afterCodecs.indexOf("\"", firstQuote + 1)
                        codec = if (secondQuote != -1) {
                            afterCodecs.substring(firstQuote + 1, secondQuote)
                        } else {
                            afterCodecs.substring(firstQuote + 1)
                        }
                    } else {
                        codec = afterCodecs
                    }
                }
            }

            val initRange = format.optJSONObject("initRange")
            val indexRange = format.optJSONObject("indexRange")

            val initStart = initRange?.optString("start")
            val initEnd = initRange?.optString("end")
            val idxStart = indexRange?.optString("start")
            val idxEnd = indexRange?.optString("end")

            val initR = if (initStart != null && initEnd != null) "$initStart-$initEnd" else null
            val indexR = if (idxStart != null && idxEnd != null) "$idxStart-$idxEnd" else null

            if (cleanMimeType.startsWith("video/")) {
                val height = format.optInt("height", 0)
                val label = if (height > 0) "${height}p" else "video"
                if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "vp9" else "avc1.4d401f"
                
                videoOnlyList.add(StreamInfo(streamUrl, cleanMimeType, codec, height, label, initR, indexR, s, sp, n))
            } else if (cleanMimeType.startsWith("audio/")) {
                val bitrate = format.optInt("bitrate", 128000)
                if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "opus" else "mp4a.40.2"
                
                var locale = "und"
                var displayName = "Original"

                val audioTrack = format.optJSONObject("audioTrack")
                var audioTrackId: String? = null
                
                if (audioTrack != null) {
                    audioTrackId = audioTrack.optString("id", "")
                    if (audioTrackId.isNotEmpty()) {
                        locale = audioTrackId.split(".")[0].split("-")[0]
                    }
                    val trackDisplayName = audioTrack.optString("displayName", "")
                    if (trackDisplayName.isNotEmpty()) {
                        displayName = trackDisplayName
                    }
                } else {
                    val lang = format.optString("language", "")
                    if (lang.isNotEmpty()) {
                        locale = lang
                        displayName = lang
                    }
                }
                
                audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, locale, audioTrackId, displayName, s, sp, n))
            }
        }

        val groupedAudio = audioInfoList.groupBy { it.locale + it.displayName }
        val bestAudioList = groupedAudio.mapNotNull { (_, tracks) ->
            tracks.maxByOrNull { 
                val bonus = if (it.mimeType.contains("webm")) 1000000 else 0
                it.bitrate + bonus 
            }
        }

        if (videoOnlyList.isNotEmpty() && bestAudioList.isNotEmpty()) {
            startServerIfNeeded()

            val allSigs = mutableSetOf<String>()
            val allNs = mutableSetOf<String>()

            bestAudioList.forEach { 
                if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                if (!it.n.isNullOrBlank()) allNs.add(it.n)
            }
            videoOnlyList.forEach { 
                if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                if (!it.n.isNullOrBlank()) allNs.add(it.n)
            }

            if (playerId.isNotBlank() && (allSigs.isNotEmpty() || allNs.isNotEmpty())) {
                val (decodedSigs, decodedNs) = decodeBatch(playerId, allSigs.toList(), allNs.toList())

                bestAudioList.forEach { audio ->
                    var newUrl = audio.url
                    if (!audio.s.isNullOrBlank() && decodedSigs.containsKey(audio.s)) {
                        newUrl += "&${audio.sp}=${decodedSigs[audio.s]}"
                    }
                    if (!audio.n.isNullOrBlank() && decodedNs.containsKey(audio.n)) {
                        newUrl = newUrl.replace("n=${audio.n}", "n=${decodedNs[audio.n]}")
                    }
                    audio.url = newUrl
                }

                videoOnlyList.forEach { video ->
                    var newUrl = video.url
                    if (!video.s.isNullOrBlank() && decodedSigs.containsKey(video.s)) {
                        newUrl += "&${video.sp}=${decodedSigs[video.s]}"
                    }
                    if (!video.n.isNullOrBlank() && decodedNs.containsKey(video.n)) {
                        newUrl = newUrl.replace("n=${video.n}", "n=${decodedNs[video.n]}")
                    }
                    video.url = newUrl
                }
            }

            for (video in videoOnlyList.distinctBy { it.height }) {
                val dashXml = buildDashManifestXml(video, bestAudioList, durationSeconds)
                val localLink = registerManifestAndGetUrl(dashXml)

                if (localLink != null) {
                    val finalName = "YouTube ${video.label}"
                    callback(
                        newExtractorLink(
                            this@YoutubeExtractor.name, finalName, localLink, type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                            this.quality = video.height
                            this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                        }
                    )
                }
            }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        val context = com.lagradost.cloudstream3.AcraApplication.context
        val provider = com.lagradost.cloudstream3.APIHolder.allProviders.find { it.name == "YouTube" }
        val prefs = (provider as? com.lagradost.cloudstream3.ar.youtube.YoutubeProvider)?.appPrefs 
            ?: context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
            
        val langCode = prefs?.getString("youtube_language", "en") ?: "en"
        val downloaderType = prefs?.getString("downloader_app_type", "none") ?: "none"
        val customPkg = prefs?.getString("downloader_custom_package", "") ?: ""

        val (pkgName, appName) = when (downloaderType) {
            "ytdlnis" -> Pair("com.deniscerri.ytdl", "YTDLnis")
            "seal" -> Pair("com.junkfood.seal", "Seal")
            "seal_plus" -> Pair("com.maheshtechnicals.sealplus", "Seal Plus")
            "custom" -> Pair(customPkg, "Custom Downloader")
            else -> Pair("", "")
        }

        withContext(Dispatchers.IO) {
            try {
                val (playerResponse, durationSeconds, playerId) = fetchPlayerResponse(videoId)

                if (playerResponse == null) {
                    println("[TRACK] 🚨 FATAL: playerResponse 처리 불가")
                    return@withContext
                }

                val streamingData = playerResponse.optJSONObject("streamingData")
                if (streamingData == null) {
                    println("[TRACK] 🚨 FATAL: streamingData 누락")
                    return@withContext
                }

                val hlsUrl = streamingData.optString("hlsManifestUrl")
                val dashUrl = streamingData.optString("dashManifestUrl")

                if (hlsUrl.isNotBlank()) {
                    parseHlsManifest(hlsUrl, playerId, langCode, callback)
                }

                if (dashUrl.isNotBlank()) {
                    val finalDashUrl = if (playerId.isNotBlank()) deobfuscateManifestUrl(dashUrl, playerId) else dashUrl
                    callback(
                        newExtractorLink(
                            this@YoutubeExtractor.name,
                            "YouTube Auto (DASH)",
                            finalDashUrl,
                            type = ExtractorLinkType.DASH
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                        }
                    )
                }

                parseAdaptiveFormats(streamingData, playerId, durationSeconds, langCode, callback)

                val originalUrl = "https://www.youtube.com/watch?v=$videoId"

                if (pkgName.isNotEmpty()) {
                    val redirectUrl = getAppRedirectUrl(pkgName, originalUrl) ?: originalUrl
                    callback(newExtractorLink(
                        source = "YouTube Downloader", 
                        name = "$appName Download", 
                        url = redirectUrl, 
                        type = if (redirectUrl.startsWith("http://127.0.0.1")) ExtractorLinkType.M3U8 else INFER_TYPE
                    ) { 
                        this.quality = 2160 
                        this.referer = mainUrl 
                    })
                }

            } catch (e: Exception) { 
                println("[TRACK] 🚨 getUrl 전역 루프 예외: ${e.message}")
                logError(e) 
            }
        }
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) return
            try {
                val newServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                activeServer = newServer
                serverPort = newServer.localPort
                
                serverScope.launch {
                    try {
                        while (isActive && !newServer.isClosed) {
                            val client = newServer.accept()
                            client.soTimeout = 15000 
                            launch { handleClient(client) }
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
        val id = "m_${streamCounter.getAndIncrement()}"
        manifestMap[id] = xmlContent
        return "http://127.0.0.1:$serverPort/$id.mpd"
    }

    private fun buildDashManifestXml(
        video: StreamInfo,
        audioList: List<AudioInfo>,
        durationSec: Long
    ): String {
        val videoChunkId = "v_${streamCounter.getAndIncrement()}"
        streamUrlCache[videoChunkId] = video.url
        val proxyVideoUrl = "http://127.0.0.1:$serverPort/chunk?id=$videoChunkId"
        val durationString = "PT${durationSec}S"

        val sb = java.lang.StringBuilder(2048)
        sb.append("""<MPD xmlns="urn:mpeg:dash:schema:mpd:2011" profiles="urn:mpeg:dash:profile:isoff-on-demand:2011" type="static" minBufferTime="PT5.0S" mediaPresentationDuration="$durationString">""")
        sb.append("<Period>")

        val vSegmentBase = if (video.initRange != null && video.indexRange != null) {
            """<SegmentBase indexRange="${video.indexRange}"><Initialization range="${video.initRange}" /></SegmentBase>"""
        } else ""

        sb.append("""
            <AdaptationSet mimeType="${video.mimeType}" contentType="video" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="${video.codec}">
                <BaseURL>$proxyVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        audioList.forEachIndexed { index, audio ->
            val audioIdChunk = "a_${streamCounter.getAndIncrement()}"
            streamUrlCache[audioIdChunk] = audio.url
            val proxyAudioUrl = "http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk"

            val audioId = "audio_$index"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            val validLocale = if (audio.locale.length >= 2) audio.locale else "und"
            val langAttribute = """ lang="$validLocale""""
            val safeDisplayName = audio.displayName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

            sb.append("""
                <AdaptationSet mimeType="${audio.mimeType}" contentType="audio"$langAttribute subsegmentAlignment="true" subsegmentStartsWithSAP="1">
                  <Label>$safeDisplayName</Label>
                  <Representation id="$audioId" bandwidth="${if(audio.bitrate>0) audio.bitrate else 128000}" codecs="${audio.codec}">
                    <BaseURL>$proxyAudioUrl</BaseURL>
                    $aSegmentBase
                  </Representation>
                </AdaptationSet>
            """.trimIndent())
        }

        sb.append("</Period>")
        sb.append("</MPD>")
        return sb.toString()
    }
}
