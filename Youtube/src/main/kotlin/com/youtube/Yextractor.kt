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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

open class YoutubeExtractor : ExtractorApi() {
    override val mainUrl = "https://www.youtube.com"
    override val requiresReferer = false
    override val name = "YouTube"

    companion object {
        @Volatile
        private var activeServer: ServerSocket? = null
        private var serverPort: Int = 0
        private val serverLock = Any()

        private val streamCounter = AtomicLong(0)

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
                }
            }
        )

        private val streamUrlCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 1000
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

        private val serverExecutor = ThreadPoolExecutor(
            4, 
            16, 
            30L, 
            TimeUnit.SECONDS,
            LinkedBlockingQueue(128),
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

        private val VISITOR_DATA_REGEX = Regex(""""VISITOR_DATA":"([^"]+)"""")
        private val STS_REGEX = Regex(""""signatureTimestamp":\s*(\d+)"""")
        private val CHUNK_ID_REGEX = Regex("id=([^&]+)")
        private val FMT_REPLACE_REGEX = Regex("&fmt=[^&]+")

        // PipePipe Safari 위장 상수
        private const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
        private const val SAFARI_CLIENT_VERSION = "2.20260114.08.00"
            
        init {
            println("[YT_DEBUG_INIT] 유튜브 DASH 추출기 최적화 가상서버 초기화 완료")
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
                    e.printStackTrace()
                    try {
                        val appName = when (packageName) {
                            "com.junkfood.seal" -> "Seal"
                            "com.maheshtechnicals.sealplus" -> "Seal Plus"
                            "com.deniscerri.ytdl" -> "YTDLnis"
                            else -> "The downloader"
                        }
                        android.widget.Toast.makeText(
                            context,
                            "$appName is not installed. Please install the app and try again.",
                            android.widget.Toast.LENGTH_LONG
                        ).show()
                    } catch (_: Exception) {}
                }
            }
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

                    if (cleanPath.startsWith("/open_app")) {
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
                                println("[YT_PROXY_DEBUG] 중복된 앱 실행 요청 감지되어 인텐트 발송 차단: $triggerKey")
                            }
                        }
                        
                        outWriter.print("HTTP/1.1 204 No Content\r\n")
                        outWriter.print("Connection: close\r\n")
                        outWriter.print("Access-Control-Allow-Origin: *\r\n")
                        outWriter.print("\r\n")
                        outWriter.flush()
                    }
                    else if (cleanPath.startsWith("/chunk")) {
                        val idMatch = CHUNK_ID_REGEX.find(queryParams)
                        val chunkId = idMatch?.groupValues?.get(1)
                        val targetYoutubeUrl = if (chunkId != null) streamUrlCache[chunkId] else null

                        if (targetYoutubeUrl != null) {
                            proxyChunkToYouTube(client, targetYoutubeUrl, clientHeaders)
                        } else {
                            outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                            outWriter.flush()
                        }
                    } 
                    else if (cleanPath.endsWith(".mpd", ignoreCase = true)) {
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
                val response = proxyHttpClient.newCall(request).execute()
                
                try {
                    if (response.code == 403 || response.code == 400) {
                        println("[YT_PROXY_DEBUG] 🚨 유튜브 CDN 에러 발생: ${response.code}")
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
                    rawOutWriter.print("Connection: close\r\n")
                    rawOutWriter.print("\r\n")
                    rawOutWriter.flush()

                    val body = response.body
                    if (body != null) {
                        body.byteStream().use { input ->
                            input.copyTo(output, bufferSize = 65536)
                        }
                    }
                    output.flush()
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
                println("[YT_PROXY_DEBUG] 프록시 릴레이 중 예외 발생: ${e.message}")
            }
        }
    }

    override fun getExtractorUrl(id: String): String {
        return "$mainUrl/watch?v=$id"
    }

    data class StreamInfo(
        val url: String,
        val mimeType: String,
        val codec: String,
        val height: Int,
        val label: String,
        val initRange: String?,
        val indexRange: String?
    )

    data class AudioInfo(
        val url: String,
        val mimeType: String,
        val codec: String,
        val bitrate: Int,
        val initRange: String?,
        val indexRange: String?,
        val language: String? = null,
        val audioTrackId: String? = null,
        val displayName: String? = null
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

    private suspend fun fetchPlayerResponse(videoId: String): Pair<JSONObject?, Long> {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val html = app.get(watchUrl, headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)).text
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: ""
        val sts = STS_REGEX.find(html)?.groupValues?.get(1)?.toIntOrNull() ?: 0

        val context = com.lagradost.cloudstream3.AcraApplication.context
        val provider = com.lagradost.cloudstream3.APIHolder.allProviders.find { it.name == "YouTube" }
        val prefs = (provider as? com.lagradost.cloudstream3.ar.youtube.YoutubeProvider)?.appPrefs 
            ?: context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
        
        val langCode = prefs?.getString("youtube_language", "ko") ?: "ko"
        val (cookies, sapisidVal) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, langCode, visitorData, false)
        val isLoggedIn = !sapisidVal.isNullOrBlank()

        println("[TRACK] =========================================")
        println("[TRACK] 1. Auth Headers Check")
        println("[TRACK] -> Extracted Cookies length: ${cookies.length}")
        println("[TRACK] -> SAPISID Extracted: $isLoggedIn")
        println("[TRACK] =========================================")

        var finalJson: JSONObject? = null
        var lengthSeconds = 3600L

        if (isLoggedIn) {
            println("[TRACK] 2. Sending SAFARI API Request (WITHOUT URL API Key)...")
            
            // ✅ PipePipe와 동일하게 ?key= 없이 prettyPrint=false 만 붙여 요청
            val safariApiUrl = "https://www.youtube.com/youtubei/v1/player?prettyPrint=false"
            
            val safariHeaders = mutableMapOf(
                "Content-Type" to "application/json",
                "User-Agent" to SAFARI_USER_AGENT,
                "X-YouTube-Client-Name" to "1",
                "X-Youtube-Client-Version" to SAFARI_CLIENT_VERSION,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com",
                "Cookie" to cookies,
                "Authorization" to YoutubeUtils.getSapisidHash(sapisidVal!!, "https://www.youtube.com"),
                "DNT" to "1"
            )

            val safariPayload = mapOf(
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
            )

            try {
                val res = app.post(safariApiUrl, json = safariPayload, headers = safariHeaders).text
                val json = JSONObject(res)
                val status = json.optJSONObject("playabilityStatus")?.optString("status")
                
                println("[TRACK] -> SAFARI API Response Status: $status")
                
                if (status == "OK" || json.has("streamingData")) {
                    println("[TRACK] -> SAFARI Response successful.")
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                } else {
                    println("[TRACK] 🚨 SAFARI Request blocked/failed (Status: $status). Falling back to ANDROID_VR...")
                }
            } catch (e: Exception) { 
                println("[TRACK] 🚨 SAFARI Request Exception: ${e.message}") 
            }
        }

        // 로그인 안 되어있거나, Safari가 UNPLAYABLE 등으로 막혔을 때 ANDROID_VR로 폴백
        if (finalJson == null) {
            println("[TRACK] 3. Sending ANDROID_VR API Request (No Auth fallback)...")
            val androidApiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
            val vrApiUrl = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false" // 모바일은 googleapis.com 사용

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
                
                println("[TRACK] -> ANDROID_VR API Response Status: $status")

                if (status == "OK" || json.has("streamingData")) {
                    println("[TRACK] -> ANDROID_VR Response successful.")
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                }
            } catch (e: Exception) { 
                println("[TRACK] -> Exception during ANDROID_VR Request: ${e.message}") 
            }
        }

        return Pair(finalJson, lengthSeconds)
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
        val sharedPref = (provider as? com.lagradost.cloudstream3.ar.youtube.YoutubeProvider)?.appPrefs 
            ?: context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
            
        val downloaderType = sharedPref?.getString("downloader_app_type", "none") ?: "none"
        val customPkg = sharedPref?.getString("downloader_custom_package", "") ?: ""

        val (pkgName, appName) = when (downloaderType) {
            "ytdlnis" -> Pair("com.deniscerri.ytdl", "YTDLnis")
            "seal" -> Pair("com.junkfood.seal", "Seal")
            "seal_plus" -> Pair("com.maheshtechnicals.sealplus", "Seal Plus")
            "custom" -> Pair(customPkg, "Custom Downloader")
            else -> Pair("", "")
        }

        withContext(Dispatchers.IO) {
            try {
                val (playerResponse, durationSeconds) = fetchPlayerResponse(videoId)

                if (playerResponse == null) {
                    println("[TRACK] 🚨 Failed to get playerResponse.")
                    return@withContext
                }

                val streamingData = playerResponse.optJSONObject("streamingData")
                if (streamingData == null) {
                    println("[TRACK] 🚨 streamingData object is missing in response.")
                    return@withContext
                }

                val hlsUrl = streamingData.optString("hlsManifestUrl")
                if (!hlsUrl.isNullOrBlank()) {
                    callback(
                        newExtractorLink(
                            this@YoutubeExtractor.name,
                            "YouTube Live",
                            hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    return@withContext
                }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                println("[TRACK] 4. Parsing adaptiveFormats")
                println("[TRACK] -> Total items in adaptiveFormats: ${adaptiveFormats.length()}")
                
                val videoOnlyList = ArrayList<StreamInfo>(adaptiveFormats.length())
                val audioInfoList = ArrayList<AudioInfo>(adaptiveFormats.length())

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val streamUrl = format.optString("url")
                    
                    if (streamUrl.isNullOrEmpty()) continue

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
                        
                        videoOnlyList.add(StreamInfo(streamUrl, cleanMimeType, codec, height, label, initR, indexR))
                    } else if (cleanMimeType.startsWith("audio/")) {
                        val bitrate = format.optInt("bitrate", 128000)
                        if (codec.isEmpty()) codec = if (cleanMimeType.contains("webm")) "opus" else "mp4a.40.2"
                        
                        var language = format.optString("language", "").ifEmpty { null }
                        var audioTrackId = format.optString("audioTrackId", "").ifEmpty { null }
                        var trackDisplayName = format.optString("language", "")
                        
                        val audioTrack = format.optJSONObject("audioTrack")

                        if (audioTrack != null) {
                            audioTrackId = audioTrack.optString("id", audioTrackId)
                            trackDisplayName = audioTrack.optString("displayName", trackDisplayName)
                            if (language == null && trackDisplayName.isNotBlank()) {
                                language = trackDisplayName 
                            }
                        }
                        
                        if (language == null && trackDisplayName.isBlank()) {
                            language = "Original" 
                        }
                        
                        audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, language, audioTrackId, trackDisplayName))
                    }
                }

                println("[TRACK] 5. Filtering & Grouping Audio Tracks")
                val groupedAudio = audioInfoList.groupBy { it.language ?: it.audioTrackId ?: "Original" }
                println("[TRACK] -> Grouped Languages (Keys): ${groupedAudio.keys}")
                
                val bestAudioList = groupedAudio.mapNotNull { (_, tracks) ->
                    tracks.maxByOrNull { 
                        val bonus = if (it.mimeType.contains("webm")) 1000000 else 0
                        it.bitrate + bonus 
                    }
                }

                if (videoOnlyList.isNotEmpty() && bestAudioList.isNotEmpty()) {
                    startServerIfNeeded()

                    for (video in videoOnlyList.distinctBy { it.height }) {
                        val dashXml = buildDashManifestXml(video, bestAudioList, durationSeconds)
                        val localLink = registerManifestAndGetUrl(dashXml)

                        if (localLink != null) {
                            val finalName = "YouTube ${video.label}"
                            val sourceName = "YouTube"
                            
                            callback(
                                newExtractorLink(
                                    sourceName, finalName, localLink, type = ExtractorLinkType.DASH
                                ) {
                                    this.referer = mainUrl
                                    this.quality = video.height
                                    this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                                }
                            )
                        }
                    }
                }

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
                println("[TRACK] 🚨 Exception in getUrl: ${e.message}")
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
            } catch (e: Exception) { logError(e) }
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
            <AdaptationSet mimeType="${video.mimeType}" subsegmentAlignment="true" subsegmentStartsWithSAP="1">
              <Representation id="video" bandwidth="4000000" width="0" height="${video.height}" codecs="${video.codec}">
                <BaseURL>$proxyVideoUrl</BaseURL>
                $vSegmentBase
              </Representation>
            </AdaptationSet>
        """.trimIndent())

        println("[TRACK] 6. Writing DASH Manifest")
        audioList.forEachIndexed { index, audio ->
            val audioIdChunk = "a_${streamCounter.getAndIncrement()}"
            streamUrlCache[audioIdChunk] = audio.url
            val proxyAudioUrl = "http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk"

            val audioId = "audio_$index"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            val langAttribute = if (!audio.language.isNullOrBlank()) """ lang="${audio.language}"""" else ""

            sb.append("""
                <AdaptationSet mimeType="${audio.mimeType}"$langAttribute subsegmentAlignment="true" subsegmentStartsWithSAP="1">
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
