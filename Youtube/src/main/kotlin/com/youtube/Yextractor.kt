package com.youtube

import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.INFER_TYPE
import com.lagradost.cloudstream3.utils.schemaStripRegex
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.ar.youtube.YoutubeUtils
import kotlinx.coroutines.Dispatchers
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

        private val proxyHttpClient = okhttp3.OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        private val VISITOR_DATA_REGEX = Regex(""""VISITOR_DATA":"([^"]+)"""")
        private val CHUNK_ID_REGEX = Regex("id=([^&]+)")

        private const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/15.5 Safari/605.1.15,gzip(gfe)"
        private const val SAFARI_CLIENT_VERSION = "2.20260114.08.00"
            
        init {
            println("[YT_DEBUG_INIT] 유튜브 DASH 추출기 최적화 가상서버 초기화 완료")
        }

        private fun generateCpn(): String {
            val allowedChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
            return (1..16).map { allowedChars.random() }.joinToString("")
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
                        outWriter.print("HTTP/1.1 204 No Content\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n")
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
                            outWriter.print("HTTP/1.1 200 OK\r\nContent-Type: application/dash+xml\r\nConnection: close\r\nAccess-Control-Allow-Origin: *\r\n\r\n$content")
                            outWriter.flush()
                        } else {
                            outWriter.print("HTTP/1.1 404 Not Found\r\n\r\n")
                            outWriter.flush()
                        }
                    }
                }
            } catch (e: Exception) {
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
                } finally {
                    response.close()
                }
            } catch (e: Exception) {
            }
        }

        private fun decodeBatch(playerId: String, sigs: List<String>, ns: List<String>): Pair<Map<String, String>, Map<String, String>> {
            val sigResults = mutableMapOf<String, String>()
            val nResults = mutableMapOf<String, String>()

            if (sigs.isEmpty() && ns.isEmpty()) return Pair(sigResults, nResults)

            try {
                val urlBuilder = StringBuilder("https://api.pipepipe.dev/decoder/decode")
                urlBuilder.append("?player=").append(playerId)

                if (ns.isNotEmpty()) {
                    urlBuilder.append("&n=")
                    urlBuilder.append(ns.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") })
                }

                if (sigs.isNotEmpty()) {
                    urlBuilder.append("&sig=")
                    urlBuilder.append(sigs.joinToString(",") { java.net.URLEncoder.encode(it, "UTF-8") })
                }

                val request = okhttp3.Request.Builder()
                    .url(urlBuilder.toString())
                    .header("User-Agent", "PipePipe/4.9.0")
                    .build()

                val response = proxyHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""
                response.close()

                val jsonResponse = JSONObject(responseBody)
                if (jsonResponse.optString("type") != "result") return Pair(sigResults, nResults)

                val responses = jsonResponse.optJSONArray("responses") ?: JSONArray()
                var responseIndex = 0

                if (ns.isNotEmpty()) {
                    val nResponse = responses.optJSONObject(responseIndex++)
                    if (nResponse != null && nResponse.optString("type") == "result") {
                        val nData = nResponse.optJSONObject("data")
                        if (nData != null) {
                            for (nParam in ns) {
                                val decodedValue = nData.optString(nParam)
                                if (decodedValue.isNotEmpty()) nResults[nParam] = decodedValue
                            }
                        }
                    }
                }

                if (sigs.isNotEmpty()) {
                    val sigResponse = responses.optJSONObject(responseIndex)
                    if (sigResponse != null && sigResponse.optString("type") == "result") {
                        val sigData = sigResponse.optJSONObject("data")
                        if (sigData != null) {
                            for (sig in sigs) {
                                val decodedValue = sigData.optString(sig)
                                if (decodedValue.isNotEmpty()) sigResults[sig] = decodedValue
                            }
                        }
                    }
                }
                println("[TRACK] -> Batch Decode API Success: Decoded ${sigResults.size} Sigs, ${nResults.size} N params")
            } catch (e: Exception) {
                println("[TRACK] 🚨 Failed to call batch decode API: ${e.message}")
            }

            return Pair(sigResults, nResults)
        }

        fun deobfuscateManifestUrl(manifestUrl: String, playerId: String): String {
            if (manifestUrl.isBlank() || playerId.isBlank()) return manifestUrl
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
            }
            return manifestUrl
        }

        // XML 이스케이프 함수
        fun escapeXml(url: String): String {
            return url.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
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
        val language: String? = null,
        val audioTrackId: String? = null,
        val displayName: String? = null,
        val s: String?,
        val sp: String?,
        val n: String?
    )

    private suspend fun fetchPlayerResponse(videoId: String): Triple<JSONObject?, Long, String> {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        
        val html = app.get(watchUrl, headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)).text
        val visitorData = VISITOR_DATA_REGEX.find(html)?.groupValues?.get(1) ?: ""

        val context = com.lagradost.cloudstream3.AcraApplication.context
        val provider = com.lagradost.cloudstream3.APIHolder.allProviders.find { it.name == "YouTube" }
        val prefs = (provider as? com.lagradost.cloudstream3.ar.youtube.YoutubeProvider)?.appPrefs 
            ?: context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
        
        val langCode = prefs?.getString("youtube_language", "ko") ?: "ko"
        val (cookies, sapisidVal) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, langCode, visitorData, false)
        val isLoggedIn = !sapisidVal.isNullOrBlank()

        var sts = 20002
        var playerId = ""
        try {
            val pipeReq = okhttp3.Request.Builder()
                .url("https://api.pipepipe.dev/decoder/latest-player")
                .header("User-Agent", "PipePipe/4.9.0")
                .build()
            val pipeRes = proxyHttpClient.newCall(pipeReq).execute()
            val pipeBody = pipeRes.body?.string() ?: ""
            pipeRes.close()
            val json = JSONObject(pipeBody)
            sts = json.optInt("signatureTimestamp", sts)
            playerId = json.optString("player", "")
        } catch (e: Exception) {
            println("[TRACK] 🚨 PipePipe API failed: ${e.message}")
        }

        println("[TRACK] =========================================")
        println("[TRACK] 1. Auth Headers Check")
        println("[TRACK] -> SAPISID Extracted: $isLoggedIn")
        println("[TRACK] -> Player ID: $playerId | STS: $sts")

        var finalJson: JSONObject? = null
        var lengthSeconds = 3600L

        if (isLoggedIn) {
            println("[TRACK] 2. Sending SAFARI API Request...")
            
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
                val response = proxyHttpClient.newCall(request).execute()
                val res = response.body?.string() ?: ""
                response.close()
                
                val json = JSONObject(res)
                val playabilityStatus = json.optJSONObject("playabilityStatus")
                val status = playabilityStatus?.optString("status")
                
                if (status == "OK" || json.has("streamingData")) {
                    println("[TRACK] -> SAFARI Response successful.")
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                } else {
                    println("[TRACK] 🚨 SAFARI Request blocked. Status: $status")
                }
            } catch (e: Exception) { 
                println("[TRACK] 🚨 SAFARI Exception: ${e.message}") 
            }
        }

        if (finalJson == null) {
            println("[TRACK] 3. Sending ANDROID_VR API Request (Fallback)...")
            val androidApiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
            val vrApiUrl = "https://youtubei.googleapis.com/youtubei/v1/player?prettyPrint=false&key=$androidApiKey"

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
                if (json.optJSONObject("playabilityStatus")?.optString("status") == "OK" || json.has("streamingData")) {
                    println("[TRACK] -> ANDROID_VR Response successful.")
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                }
            } catch (e: Exception) { 
                println("[TRACK] 🚨 ANDROID_VR Exception: ${e.message}") 
            }
        }

        return Triple(finalJson, lengthSeconds, playerId)
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanedUrl = url.replace(schemaStripRegex, "")
        val videoId = YoutubeUtils.extractYoutubeId(cleanedUrl) ?: cleanedUrl

        withContext(Dispatchers.IO) {
            try {
                val (playerResponse, durationSeconds, playerId) = fetchPlayerResponse(videoId)

                if (playerResponse == null) {
                    println("[TRACK] 🚨 Failed to get playerResponse.")
                    return@withContext
                }

                val streamingData = playerResponse.optJSONObject("streamingData")
                if (streamingData == null) {
                    println("[TRACK] 🚨 streamingData missing.")
                    return@withContext
                }

                var hlsUrl = streamingData.optString("hlsManifestUrl")
                var dashUrl = streamingData.optString("dashManifestUrl")

                if (playerId.isNotBlank()) {
                    if (hlsUrl.isNotBlank()) hlsUrl = deobfuscateManifestUrl(hlsUrl, playerId)
                    if (dashUrl.isNotBlank()) dashUrl = deobfuscateManifestUrl(dashUrl, playerId)
                }

                if (hlsUrl.isNotBlank()) {
                    println("[TRACK] -> Emitting HLS link.")
                    callback(
                        ExtractorLink(
                            source = this@YoutubeExtractor.name,
                            name = "Auto (HLS)",
                            url = hlsUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.M3U8,
                            headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                        )
                    )
                }
                
                if (dashUrl.isNotBlank()) {
                    println("[TRACK] -> Emitting DASH link.")
                    callback(
                        ExtractorLink(
                            source = this@YoutubeExtractor.name,
                            name = "Auto (DASH)",
                            url = dashUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            type = ExtractorLinkType.DASH,
                            headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                        )
                    )
                }

                val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
                println("[TRACK] 4. Parsing adaptiveFormats. Found: ${adaptiveFormats.length()}")
                
                val videoOnlyList = ArrayList<StreamInfo>()
                val audioInfoList = ArrayList<AudioInfo>()

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
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
                            println("[TRACK] ⚠️ Format $i skipped: No URL and no Cipher. Dump: $format")
                            continue
                        }
                    }

                    if (streamUrl.isNullOrEmpty()) {
                        println("[TRACK] ⚠️ Format $i skipped: streamUrl empty after cipher parse.")
                        continue
                    }

                    var n: String? = null
                    val nMatch = Regex("""[?&]n=([^&]+)""").find(streamUrl)
                    if (nMatch != null) n = nMatch.groupValues[1]

                    val rawMimeType = format.optString("mimeType", "")
                    var cleanMimeType = rawMimeType
                    var codec = ""
                    
                    val semicolonIndex = rawMimeType.indexOf(";")
                    if (semicolonIndex != -1) {
                        cleanMimeType = rawMimeType.substring(0, semicolonIndex).trim()
                        val rawCodecs = rawMimeType.substring(semicolonIndex + 1)
                        val codecStart = rawCodecs.indexOf("codecs=")
                        if (codecStart != -1) {
                            codec = rawCodecs.substring(codecStart + 7).trim().replace("\"", "")
                        }
                    }

                    val initStart = format.optJSONObject("initRange")?.optString("start")
                    val initEnd = format.optJSONObject("initRange")?.optString("end")
                    val idxStart = format.optJSONObject("indexRange")?.optString("start")
                    val idxEnd = format.optJSONObject("indexRange")?.optString("end")
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
                        audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, language, audioTrackId, trackDisplayName, s, sp, n))
                    }
                }

                println("[TRACK] 5. Grouping Audio Tracks")
                val audiosByLanguage = audioInfoList.groupBy { it.displayName ?: it.language ?: "Original" }
                println("[TRACK] -> Grouped Languages: ${audiosByLanguage.keys}")

                if (videoOnlyList.isNotEmpty() && audiosByLanguage.isNotEmpty()) {
                    println("[TRACK] 6. Starting Local Server & Generating DASH Links")
                    startServerIfNeeded()
                    
                    if (serverPort == 0) {
                        println("[TRACK] 🚨 FATAL: Local Server Port is 0. Failed to bind socket.")
                    } else {
                        println("[TRACK] -> Local Server bound to port: $serverPort")
                    }

                    // 디코딩 일괄 수행
                    val allSigs = mutableSetOf<String>()
                    val allNs = mutableSetOf<String>()

                    audioInfoList.forEach { 
                        if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                        if (!it.n.isNullOrBlank()) allNs.add(it.n)
                    }
                    videoOnlyList.forEach { 
                        if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                        if (!it.n.isNullOrBlank()) allNs.add(it.n)
                    }

                    if (playerId.isNotBlank() && (allSigs.isNotEmpty() || allNs.isNotEmpty())) {
                        val (decodedSigs, decodedNs) = decodeBatch(playerId, allSigs.toList(), allNs.toList())
                        audioInfoList.forEach { audio ->
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

                    // ✅ 언어별로 독립된 ExtractorLink 방출 (Cloudstream UI 이름 분리)
                    for (video in videoOnlyList.distinctBy { it.height }) {
                        for ((lang, audios) in audiosByLanguage) {
                            
                            val bestAudioForLang = if (video.mimeType.contains("webm")) {
                                audios.maxByOrNull { if (it.mimeType.contains("webm")) it.bitrate + 1000000 else it.bitrate }
                            } else {
                                audios.maxByOrNull { if (it.mimeType.contains("mp4")) it.bitrate + 1000000 else it.bitrate }
                            }

                            if (bestAudioForLang != null) {
                                val singleAudioList = listOf(bestAudioForLang)
                                val dashXml = buildDashManifestXml(video, singleAudioList, durationSeconds)
                                val localLink = registerManifestAndGetUrl(dashXml)

                                if (localLink != null) {
                                    val displayName = "$lang ${video.height}p"
                                    println("[TRACK] -> Emitting DASH link for: $displayName")
                                    callback(
                                        ExtractorLink(
                                            source = this@YoutubeExtractor.name,
                                            name = displayName,
                                            url = localLink,
                                            referer = mainUrl,
                                            quality = video.height,
                                            type = ExtractorLinkType.DASH,
                                            headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                                        )
                                    )
                                }
                            }
                        }
                    }
                } else {
                    println("[TRACK] 🚨 Cannot generate DASH: VideoList Size=${videoOnlyList.size}, AudioGroups Size=${audiosByLanguage.size}")
                }

            } catch (e: Exception) { 
                println("[TRACK] 🚨 Exception in getUrl: ${e.message}")
                logError(e) 
            }
        }
    }

    @Synchronized
    private fun startServerIfNeeded() {
        if (activeServer != null && !activeServer!!.isClosed) return
        try {
            // 다른 개발자 로직과 동일하게 안드로이드 호환성 높은 ServerSocket(0) 사용
            activeServer = ServerSocket(0)
            serverPort = activeServer!!.localPort
            
            thread(name = "YT-DASH-Server-Acceptor") {
                try {
                    while (activeServer != null && !activeServer!!.isClosed) {
                        val client = activeServer!!.accept()
                        client.soTimeout = 15000 
                        serverExecutor.execute { handleClient(client) }
                    }
                } catch (e: Exception) {
                }
            }
        } catch (e: Exception) { 
            println("[TRACK] 🚨 Failed to start Local Server: ${e.message}")
            logError(e) 
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
        val proxyVideoUrl = escapeXml("http://127.0.0.1:$serverPort/chunk?id=$videoChunkId")
        
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

        audioList.forEachIndexed { index, audio ->
            val audioIdChunk = "a_${streamCounter.getAndIncrement()}"
            streamUrlCache[audioIdChunk] = audio.url
            val proxyAudioUrl = escapeXml("http://127.0.0.1:$serverPort/chunk?id=$audioIdChunk")

            val audioId = "audio_$index"
            val aSegmentBase = if (audio.initRange != null && audio.indexRange != null) {
                """<SegmentBase indexRange="${audio.indexRange}"><Initialization range="${audio.initRange}" /></SegmentBase>"""
            } else ""

            // xml에 언어가 굳이 명시되지 않아도, Cloudstream 외부 링크 이름(name)으로 언어를 구별하므로 기능상 문제없음
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
