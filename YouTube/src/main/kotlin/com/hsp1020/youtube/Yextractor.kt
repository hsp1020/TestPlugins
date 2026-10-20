package com.hsp1020.youtube

import com.lagradost.cloudstream3.CloudStreamApp
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
import com.hsp1020.youtube.YoutubeUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.io.IOException
import java.net.ServerSocket
import java.net.Socket
import java.net.InetAddress
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            if (continuation.isCancelled) {
                try { response.close() } catch (_: Exception) {}
                return
            }
            continuation.resume(response)
        }
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isCancelled) return
            continuation.resumeWithException(e)
        }
    })
    continuation.invokeOnCancellation {
        try { cancel() } catch (_: Exception) {}
    }
}

internal suspend fun <T> traceYouTubeHttp(stage: String, videoId: String, block: suspend () -> T): T {
    val started = android.os.SystemClock.elapsedRealtime()
    android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=$stage event=start")
    try {
        val result = block()
        android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=$stage event=end elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started} result=completed")
        return result
    } catch (e: Exception) {
        android.util.Log.w("YT_HTTP_TRACE", "video=$videoId stage=$stage event=end elapsed_ms=${android.os.SystemClock.elapsedRealtime() - started} result=error type=${e.javaClass.simpleName}")
        throw e
    }
}

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
        private var serverJob: kotlinx.coroutines.Job? = null
        private val streamCounter = AtomicLong(0)

        @Volatile
        private var cachedSts: Int = 20002
        @Volatile
        private var cachedPlayerId: String = ""
        @Volatile
        private var lastCacheTime: Long = 0L
        private const val CACHE_TTL = 6 * 60 * 60 * 1000L

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

        private val manifestMap: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(64, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 30 
                }
            }
        )

        private val streamUrlCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 100 
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

        private val CHUNK_ID_REGEX = Regex("id=([^&]+)")
        private val FMT_REPLACE_REGEX = Regex("&fmt=[^&]+")

        const val DESKTOP_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
        const val VISIONOS_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15"

        fun extractBalancedJson(input: String): String? {
            var braceCount = 0
            var inString = false
            var escape = false
            val len = input.length
            for (i in 0 until len) {
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

        private fun openInExternalApp(packageName: String, targetUrl: String) {
            val context = CloudStreamApp.context ?: return
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
                    println("[YT_PROXY_DEBUG] 외부 앱 연동 에러: ${e.message}")
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
                    }
                }
            } catch (e: Exception) {
            } finally {
                outWriter.print("HTTP/1.1 204 No Content\r\n")
                outWriter.print("Connection: close\r\n")
                outWriter.print("Access-Control-Allow-Origin: *\r\n")
                outWriter.print("\r\n")
                outWriter.flush()
            }
        }

        private suspend fun proxyChunkToYouTube(
            socket: Socket, 
            targetUrl: String, 
            clientHeaders: Map<String, String>
        ) = withContext(Dispatchers.IO) {
            try {
                val requestBuilder = okhttp3.Request.Builder()
                    .url(targetUrl)
                    .header("User-Agent", YoutubeUtils.CHROME_USER_AGENT)
                    .header("Accept", "*/*")
                    .header("Connection", "keep-alive")

                clientHeaders.forEach { (key, value) ->
                    val lowerKey = key.lowercase()
                    if (lowerKey != "host" && lowerKey != "user-agent" && lowerKey != "origin" && lowerKey != "referer" && lowerKey != "accept-encoding") {
                        requestBuilder.header(key, value)
                    }
                }

                val request = requestBuilder.build()

                proxyHttpClient.newCall(request).await().use { response ->
                    val output = socket.getOutputStream()
                    val rawOutWriter = java.io.PrintWriter(output, false)

                    if (response.code == 403 || response.code == 400) {
                        rawOutWriter.print("HTTP/1.1 ${response.code} ${response.message}\r\n\r\n")
                        rawOutWriter.flush()
                        return@use
                    }

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
            }
        }

        private suspend fun decodeBatch(
            playerId: String, 
            sigs: List<String>, 
            ns: List<String>
        ): Pair<Map<String, String>, Map<String, String>> = withContext(Dispatchers.IO) {
            val sigResults = mutableMapOf<String, String>()
            val nResults = mutableMapOf<String, String>()

            if (sigs.isEmpty() && ns.isEmpty()) {
                return@withContext Pair(sigResults, nResults)
            }

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

            if (missingSigs.isEmpty() && missingNs.isEmpty()) {
                return@withContext Pair(sigResults, nResults)
            }

            val decodeStarted = android.os.SystemClock.elapsedRealtime()
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
                    .header("User-Agent", "PipePipe/5.3.0")
                    .build()

                android.util.Log.i("YT_HTTP_TRACE", "stage=pipe_decode event=start sig_count=${missingSigs.size} n_count=${missingNs.size}")
                proxyHttpClient.newCall(request).await().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    android.util.Log.i("YT_HTTP_TRACE", "stage=pipe_decode http=${response.code} elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted}")
                    val jsonResponse = JSONObject(responseBody)
                    if (jsonResponse.optString("type") != "result") {
                        android.util.Log.w("YT_HTTP_TRACE", "stage=pipe_decode result=invalid_response elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted}")
                        return@withContext Pair(sigResults, nResults)
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
                android.util.Log.i("YT_HTTP_TRACE", "stage=pipe_decode event=end elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted} decoded_sig=${sigResults.size} decoded_n=${nResults.size}")
            } catch (e: Exception) {
                android.util.Log.w("YT_HTTP_TRACE", "stage=pipe_decode result=error elapsed_ms=${android.os.SystemClock.elapsedRealtime() - decodeStarted} type=${e.javaClass.simpleName}")
            }

            Pair(sigResults, nResults)
        }

        fun deobfuscateManifestUrl(manifestUrl: String, playerId: String): String {
            if (manifestUrl.isBlank() || playerId.isBlank()) {
                return manifestUrl
            }
            try {
                val match = Regex("""/n/([^/&?]+)""").find(manifestUrl)
                if (match != null) {
                    val n = match.groupValues[1]
                    val decodedNs = run {
                        var tempResult: Map<String, String>? = null
                        kotlinx.coroutines.runBlocking {
                            try {
                                tempResult = decodeBatch(playerId, emptyList(), listOf(n)).second
                            } catch (e: Exception) {
                                tempResult = emptyMap()
                            }
                        }
                        tempResult ?: emptyMap()
                    }

                    val decodedN = decodedNs[n]
                    if (!decodedN.isNullOrBlank()) {
                        return manifestUrl.replace("/n/$n", "/n/$decodedN")
                    }
                }
            } catch (e: Exception) {
            }
            return manifestUrl
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
        val isDefault: Boolean,
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

    private suspend fun fetchPlayerResponse(
        videoId: String
    ): Triple<JSONObject?, Long, String> = withContext(Dispatchers.IO) {
        val watchUrl = "https://www.youtube.com/watch?v=$videoId"
        val context = CloudStreamApp.context

        val prefs = context?.getSharedPreferences("YouTube", android.content.Context.MODE_PRIVATE)

        val langCode = prefs?.getString("youtube_language", "en") ?: "en"
        val countryCode = java.util.Locale.getDefault().country.ifEmpty { "US" }
        val visitorData = prefs?.getString("yt_visitor_data", null) ?: "_Mk3UVhY40g"

        var sts = 20002
        var playerId = ""
        try {
            val now = System.currentTimeMillis()
            if (cachedPlayerId.isNotEmpty() && (now - lastCacheTime < CACHE_TTL)) {
                sts = cachedSts
                playerId = cachedPlayerId
                android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=pipe_latest event=cache_hit")
            } else {
                val pipeReq = okhttp3.Request.Builder()
                    .url("https://api.pipepipe.dev/decoder/latest-player")
                    .header("User-Agent", "PipePipe/5.3.0")
                    .build()
                traceYouTubeHttp("pipe_latest", videoId) {
                    proxyHttpClient.newCall(pipeReq).await().use { response ->
                        android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=pipe_latest http=${response.code}")
                        if (response.isSuccessful) {
                            val body = response.body?.string() ?: ""
                            val json = JSONObject(body)
                            cachedSts = json.optInt("signatureTimestamp", 20002)
                            cachedPlayerId = json.optString("player", "")
                            lastCacheTime = now
                            sts = cachedSts
                            playerId = cachedPlayerId
                        }
                    }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("YT_HTTP_TRACE", "video=$videoId stage=pipe_latest result=parse_error type=${e.javaClass.simpleName}")
        }

        var finalJson: JSONObject? = null
        var lengthSeconds = 3600L

        try {
            val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$DESKTOP_API_KEY&prettyPrint=false"
            val visionHeaders = mapOf(
                "Content-Type" to "application/json",
                "User-Agent" to VISIONOS_USER_AGENT,
                "Origin" to "https://www.youtube.com",
                "Referer" to "https://www.youtube.com/"
            )
            val visionPayload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "VISIONOS",
                        "clientVersion" to "1.02",
                        "deviceMake" to "Apple",
                        "deviceModel" to "RealityDevice17,1",
                        "osName" to "visionOS",
                        "osVersion" to "26.0",
                        "hl" to langCode,
                        "gl" to countryCode,
                        "visitorData" to visitorData,
                        "userAgent" to VISIONOS_USER_AGENT
                    )
                ),
                "videoId" to videoId,
                "playbackContext" to mapOf(
                    "contentPlaybackContext" to mapOf(
                        "html5Preference" to "HTML5_PREF_WANTS",
                        "signatureTimestamp" to sts
                    )
                ),
                "racyCheckOk" to true,
                "contentCheckOk" to true
            )
            val res = traceYouTubeHttp("visionos", videoId) {
                val response = app.post(apiUrl, json = visionPayload, headers = visionHeaders)
                android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=visionos http=${response.code}")
                response.text
            }
            val json = JSONObject(res)
            android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=visionos streaming=${json.has("streamingData")}")
            if (json.has("streamingData")) {
                finalJson = json
                lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
            }
        } catch (e: Exception) {
            android.util.Log.w("YT_HTTP_TRACE", "video=$videoId stage=visionos result=parse_error type=${e.javaClass.simpleName}")
        }

        if (finalJson == null || !finalJson.has("streamingData")) {
            try {
                val embedApiUrl = "https://www.youtube.com/youtubei/v1/player?key=$DESKTOP_API_KEY&prettyPrint=false"
                val embedHeaders = mapOf(
                    "Content-Type" to "application/json",
                    "User-Agent" to YoutubeUtils.CHROME_USER_AGENT,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/"
                )
                val embedPayload = mapOf(
                    "context" to mapOf(
                        "client" to mapOf(
                            "clientName" to "WEB_EMBEDDED_PLAYER",
                            "clientVersion" to "1.20240725.00.00",
                            "hl" to langCode,
                            "gl" to countryCode
                        ),
                        "thirdParty" to mapOf(
                            "embedUrl" to "https://www.youtube.com/watch?v=$videoId"
                        )
                    ),
                    "videoId" to videoId,
                    "playbackContext" to mapOf(
                        "contentPlaybackContext" to mapOf(
                            "html5Preference" to "HTML5_PREF_WANTS",
                            "signatureTimestamp" to sts
                        )
                    ),
                    "racyCheckOk" to true,
                    "contentCheckOk" to true
                )
                val res = traceYouTubeHttp("embedded", videoId) {
                    val response = app.post(embedApiUrl, json = embedPayload, headers = embedHeaders)
                    android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=embedded http=${response.code}")
                    response.text
                }
                val json = JSONObject(res)
                android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=embedded streaming=${json.has("streamingData")}")
                if (json.has("streamingData")) {
                    finalJson = json
                    lengthSeconds = json.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                }
            } catch (e: Exception) {
                android.util.Log.w("YT_HTTP_TRACE", "video=$videoId stage=embedded result=parse_error type=${e.javaClass.simpleName}")
            }
        }

        if (finalJson == null || !finalJson.has("streamingData")) {
            try {
                val safariHeaders = mapOf(
                    "User-Agent" to SAFARI_USER_AGENT,
                    "Accept-Language" to "$langCode,en-US;q=0.9,en;q=0.8",
                    "Cookie" to "CONSENT=YES+fx.456722336; PREF=f6=40000000&hl=$langCode;"
                )
                val html = traceYouTubeHttp("safari_html", videoId) {
                    val response = app.get(watchUrl, headers = safariHeaders)
                    android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=safari_html http=${response.code}")
                    response.text
                }
                val marker = "ytInitialPlayerResponse = "
                val startIdx = html.indexOf(marker)
                if (startIdx != -1) {
                    val jsonStr = extractBalancedJson(html.substring(startIdx + marker.length))
                    if (!jsonStr.isNullOrBlank()) {
                        val parsed = JSONObject(jsonStr)
                        if (parsed.has("streamingData")) {
                            finalJson = parsed
                            lengthSeconds = parsed.optJSONObject("videoDetails")?.optString("lengthSeconds")?.toLongOrNull() ?: 3600L
                        }
                    }
                }
                android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=safari_html streaming=${finalJson != null}")
            } catch (e: Exception) {
                android.util.Log.w("YT_HTTP_TRACE", "video=$videoId stage=safari_html result=parse_error type=${e.javaClass.simpleName}")
            }
        }

        android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=player_response final_streaming=${finalJson != null}")
        Triple(finalJson, lengthSeconds, playerId)
    }

    private suspend fun parseHlsManifest(
        hlsUrl: String,
        playerId: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val finalHlsUrl = if (playerId.isNotBlank()) deobfuscateManifestUrl(hlsUrl, playerId) else hlsUrl
        if (finalHlsUrl.isBlank()) return

        callback(
            newExtractorLink(
                this@YoutubeExtractor.name,
                "YouTube Auto (HLS)",
                finalHlsUrl,
                type = ExtractorLinkType.M3U8
            ) {
                this.referer = mainUrl
                this.quality = Qualities.Unknown.value
                this.headers = mapOf("User-Agent" to SAFARI_USER_AGENT)
            }
        )
    }

    private suspend fun parseAdaptiveFormats(
        streamingData: JSONObject,
        playerId: String,
        durationSeconds: Long,
        langCode: String,
        callback: (ExtractorLink) -> Unit
    ) {

        val muxedFormats = streamingData.optJSONArray("formats")
        if (muxedFormats != null) {
            for (i in 0 until muxedFormats.length()) {
                val fmt = muxedFormats.getJSONObject(i)
                val directUrl = fmt.optString("url")
                val height = fmt.optInt("height", 0)
                if (directUrl.isNotEmpty() && height > 0) {
                    callback(
                        newExtractorLink(
                            this@YoutubeExtractor.name,
                            "YouTube ${height}p (MP4)",
                            directUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = height
                            this.headers = mapOf("User-Agent" to YoutubeUtils.CHROME_USER_AGENT)
                        }
                    )
                }
            }
        }

        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: JSONArray()
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

                val audioTrackObj = format.optJSONObject("audioTrack")

                val isDefault = if (audioTrackObj != null) {
                    audioTrackObj.optBoolean("audioIsDefault", false)
                } else {
                    true
                }

                val audioTrackId = audioTrackObj?.optString("id", "")?.ifEmpty { null }
                val locale = if (audioTrackId != null) {
                    audioTrackId.split(".").firstOrNull()?.split("-")?.firstOrNull()?.lowercase() ?: "und"
                } else {
                    format.optString("language", "und").lowercase()
                }

                val rawDisplayName = audioTrackObj?.optString("displayName", "") ?: ""
                val displayName = if (rawDisplayName.isNotBlank()) rawDisplayName else locale.uppercase()

                audioInfoList.add(AudioInfo(streamUrl, cleanMimeType, codec, bitrate, initR, indexR, locale, audioTrackId, displayName, isDefault, s, sp, n))
            }
        }

        val groupedAudio = audioInfoList.groupBy { "${it.locale}_${it.audioTrackId}_${it.isDefault}" }
        val bestAudioList = groupedAudio.mapNotNull { (_, tracks) ->
            tracks.maxByOrNull { 
                val bonus = if (it.mimeType.contains("webm")) 1000000 else 0
                it.bitrate + bonus 
            }
        }

        val selectedAudioList = mutableListOf<AudioInfo>()
        val targetLang = langCode.lowercase()

        val userLangTrack = bestAudioList.firstOrNull { it.locale == targetLang }
        if (userLangTrack != null) {
            selectedAudioList.add(userLangTrack)
        }

        val originalTrack = bestAudioList.firstOrNull { it.isDefault }
        if (originalTrack != null && !selectedAudioList.contains(originalTrack)) {
            selectedAudioList.add(originalTrack)
        }

        if (selectedAudioList.isEmpty() && bestAudioList.isNotEmpty()) {
            selectedAudioList.add(bestAudioList.first())
        }

        if (videoOnlyList.isNotEmpty() && selectedAudioList.isNotEmpty()) {
            startServerIfNeeded()

            val allSigs = mutableSetOf<String>()
            val allNs = mutableSetOf<String>()

            selectedAudioList.forEach { 
                if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                if (!it.n.isNullOrBlank()) allNs.add(it.n)
            }
            videoOnlyList.forEach { 
                if (!it.s.isNullOrBlank()) allSigs.add(it.s)
                if (!it.n.isNullOrBlank()) allNs.add(it.n)
            }

            if (playerId.isNotBlank() && (allSigs.isNotEmpty() || allNs.isNotEmpty())) {
                val (decodedSigs, decodedNs) = decodeBatch(playerId, allSigs.toList(), allNs.toList())

                selectedAudioList.forEach { audio ->
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
                val dashXml = buildDashManifestXml(video, selectedAudioList, durationSeconds)
                val localLink = registerManifestAndGetUrl(dashXml)

                if (localLink != null) {
                    val finalName = "YouTube ${video.label} (DASH)"
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

        val context = CloudStreamApp.context
        val prefs = context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }

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

        val extractorStarted = android.os.SystemClock.elapsedRealtime()
        android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=extractor_total event=start")
        var firstLinkEmitted = false
        val timedCallback: (ExtractorLink) -> Unit = { link ->
            if (!firstLinkEmitted) {
                firstLinkEmitted = true
                android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=first_link elapsed_ms=${android.os.SystemClock.elapsedRealtime() - extractorStarted}")
            }
            callback(link)
        }
        withContext(Dispatchers.IO) {
            try {
                val (playerResponse, durationSeconds, playerId) = fetchPlayerResponse(videoId)

                if (playerResponse == null) {
                    return@withContext
                }

                val captions = playerResponse.optJSONObject("captions")
                    ?.optJSONObject("playerCaptionsTracklistRenderer")
                    ?.optJSONArray("captionTracks")

                if (captions != null) {
                    val seenLangs = mutableSetOf<String>()
                    for (i in 0 until captions.length()) {
                        val track = captions.getJSONObject(i)
                        val kind = track.optString("kind", "")
                        val vssId = track.optString("vssId", "")

                        if (kind.equals("asr", ignoreCase = true) || vssId.startsWith("a.")) {
                            continue
                        }

                        val subUrl = track.optString("baseUrl")
                        val lang = track.optString("languageCode")
                        if (subUrl.isEmpty() || lang.isEmpty() || !seenLangs.add(lang)) {
                            continue
                        }

                        val nameObj = track.optJSONObject("name")
                        var displayName = nameObj?.optString("simpleText") ?: ""
                        if (displayName.isBlank()) {
                            val runs = nameObj?.optJSONArray("runs")
                            if (runs != null && runs.length() > 0) {
                                displayName = runs.optJSONObject(0)?.optString("text") ?: ""
                            }
                        }
                        if (displayName.isBlank()) {
                            displayName = lang.uppercase()
                        }

                        var finalUrl = subUrl
                        if (finalUrl.startsWith("http")) {
                            finalUrl = finalUrl.replace(FMT_REPLACE_REGEX, "")
                            if (!finalUrl.contains("fmt=srt")) {
                                finalUrl = "$finalUrl&fmt=srt"
                            }
                        }
                        subtitleCallback(SubtitleFile(displayName, finalUrl))
                    }
                }

                val streamingData = playerResponse.optJSONObject("streamingData")
                if (streamingData == null) {
                    return@withContext
                }

                val hlsUrl = streamingData.optString("hlsManifestUrl")
                val dashUrl = streamingData.optString("dashManifestUrl")
                val isLive = playerResponse.optJSONObject("videoDetails")?.optBoolean("isLive") ?: false

                if (isLive && hlsUrl.isNotBlank()) {
                    timedCallback(
                        newExtractorLink(
                            this@YoutubeExtractor.name,
                            "YouTube Live (HLS)",
                            hlsUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                            this.headers = mapOf("User-Agent" to SAFARI_USER_AGENT)
                        }
                    )
                    return@withContext
                }

                if (hlsUrl.isNotBlank()) {
                    parseHlsManifest(hlsUrl, playerId, timedCallback)
                }

                parseAdaptiveFormats(streamingData, playerId, durationSeconds, langCode, timedCallback)

                if (dashUrl.isNotBlank()) {
                    val finalDashUrl = if (playerId.isNotBlank()) deobfuscateManifestUrl(dashUrl, playerId) else dashUrl
                    timedCallback(
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

                val originalUrl = "https://www.youtube.com/watch?v=$videoId"
                if (pkgName.isNotEmpty()) {
                    val redirectUrl = getAppRedirectUrl(pkgName, originalUrl) ?: originalUrl
                    timedCallback(newExtractorLink(
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
                logError(e)
            }
        }
        android.util.Log.i("YT_HTTP_TRACE", "video=$videoId stage=extractor_total event=end elapsed_ms=${android.os.SystemClock.elapsedRealtime() - extractorStarted} first_link=$firstLinkEmitted")
    }

    private fun startServerIfNeeded() {
        synchronized(serverLock) {
            val server = activeServer
            if (server != null && !server.isClosed) return

            try {
                stopServerGracefully()
                val newServer = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
                newServer.soTimeout = 15000
                activeServer = newServer
                serverPort = newServer.localPort

                serverJob = serverScope.launch {
                    try {
                        while (isActive && !newServer.isClosed) {
                            val client = try {
                                newServer.accept()
                            } catch (e: java.net.SocketTimeoutException) {
                                continue
                            }
                            client.soTimeout = 15000 
                            launch { handleClient(client) }
                        }
                    } catch (e: Exception) {
                    } finally {
                        closeServerInstance(newServer)
                    }
                }
            } catch (e: Exception) { 
                logError(e) 
            }
        }
    }

    private fun stopServerGracefully() {
        serverJob?.cancel()
        serverJob = null
        val server = activeServer
        if (server != null) {
            closeServerInstance(server)
        }
    }

    private fun closeServerInstance(server: ServerSocket) {
        try {
            if (!server.isClosed) {
                server.close()
            }
        } catch (e: Exception) {
        }
        if (activeServer === server) {
            activeServer = null
            serverPort = 0
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
