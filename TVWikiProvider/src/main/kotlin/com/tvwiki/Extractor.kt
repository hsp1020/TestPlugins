// 파일명: hsp1020/testplugins/TestPlugins-d1a26fae88cb55c8f1d621560cf4e45bd7fd78f6/TVWikiProvider/src/main/kotlin/com/tvwiki/Extractor.kt
package com.tvwiki

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.net.Socket
import java.net.URI
import java.util.Collections
import org.json.JSONObject
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVWiki"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"


    companion object {
        private val proxyLock = Any()
        @Volatile private var sharedProxy: ProxyWebServer? = null
        private fun server(): ProxyWebServer? = synchronized(proxyLock) {
            sharedProxy?.takeIf { it.running } ?: ProxyWebServer().takeIf { it.start() }
                ?.also { sharedProxy = it }
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null,
    ): Boolean {
        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = if (referer.isNullOrEmpty() || (referer.contains("tvwiki") && !referer.contains(TVWiki.currentMainUrl))) {
            TVWiki.currentMainUrl + "/"
        } else {
            referer.replace(Regex("[\\r\\n\\s]"), "").trim()
        }
        
        if (!cleanUrl.contains("/v/")) {
            try {
                val refRes = app.get(cleanReferer, headers = mapOf("User-Agent" to DESKTOP_UA))
                val iframeUrl = refRes.document.selectFirst("iframe#view_iframe")?.attr("src")
                    ?.takeIf { it.startsWith("https://") || it.startsWith("http://") }
                if (iframeUrl != null) cleanUrl = iframeUrl
                else {
                    val iframeMatch = Regex("""src=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                        ?: Regex("""data-player\d*=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)
                    if (iframeMatch != null) cleanUrl = iframeMatch.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { }
        }

        var capturedUrl: String? = cleanUrl
        val trace = android.os.SystemClock.elapsedRealtimeNanos()
        val started = android.os.SystemClock.elapsedRealtime()
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())
        Log.i("TVWikiProxyTrace", "phase=EXTRACT_START trace=${trace} needHook=${!cleanUrl.contains("/c.html")}")

        if (!cleanUrl.contains("/c.html")) {
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer, currentSessionKeys)
            if (webViewResult != null) capturedUrl = webViewResult
            Log.i("TVWikiProxyTrace", "phase=WEBVIEW_RESULT trace=${trace} captured=${webViewResult != null} " +
                "keyCandidates=${currentSessionKeys.size}")
        }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val playerOrigin = try {
                URI(cleanUrl).let { "${it.scheme}://${it.authority}" }
            } catch (e: Exception) { mainUrl }
            val headers = mutableMapOf(
                "User-Agent" to DESKTOP_UA,
                "Referer" to "$playerOrigin/",
                "Origin" to playerOrigin
            )
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                var requestUrl = capturedUrl!!.substringBefore("#")
                var response = app.get(requestUrl, headers = headers)
                var content = response.text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    Regex("""https?://[^\s"'<>]+\.m3u8(?:\?[^\s"'<>]*)?""").find(content)?.let {
                        requestUrl = it.value
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }
                if (!content.startsWith("#EXTM3U")) {
                    Log.w("TVWikiProxyTrace", "phase=INVALID_PLAYLIST trace=${trace}")
                    return false
                }

                if (content.contains("#EXT-X-STREAM-INF")) {
                    val subUrlLine = content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }
                    if (subUrlLine != null) {
                        val originalUri = try { URI(requestUrl) } catch (e: Exception) { null }
                        requestUrl = resolveUrl(originalUri, requestUrl, subUrlLine)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                val playlistLines = content.lines()
                val isKey7 = playlistLines.any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }
                Log.i("TVWikiProxyTrace", "phase=PLAYLIST trace=${trace} key7=${isKey7} " +
                    "bytes=${content.length} keyCandidates=${currentSessionKeys.size}")

                if (isKey7) {
                    val newProxy = server() ?: run {
                        Log.e("TVWikiProxyTrace", "phase=PROXY_BIND_FAILED trace=${trace}")
                        return false
                    }
                    val sessionId = java.util.UUID.randomUUID().toString().replace("-", "")
                    val prefix = "http://127.0.0.1:${newProxy.port}/${sessionId}"
                    var keyUrlForSession: String? = null
                    var testSegmentForSession: String? = null
                    Log.i("TVWikiProxyTrace", "phase=SESSION_CREATED trace=${trace} session=${sessionId.take(8)}")

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    val keyLine = playlistLines.firstOrNull {
                        it.trim().startsWith("#EXT-X-KEY") && it.contains("/v/key7")
                    }
                    val ivHex = keyLine?.let {
                        Regex("""IV=\"?(0x[0-9a-fA-F]{32})\"?""").find(it)?.groupValues?.get(1)
                    }
                    val parsedIv = if (ivHex != null) {
                        ivHex.removePrefix("0x").hexToByteArray()
                    } else {
                        // HLS AES-128: without an explicit IV, the first segment uses its sequence number.
                        val sequence = playlistLines.firstOrNull {
                            it.trim().startsWith("#EXT-X-MEDIA-SEQUENCE:")
                        }?.substringAfter(":")?.trim()?.toLongOrNull() ?: 0L
                        ByteArray(16).also { iv ->
                            for (i in 0 until 8) iv[15 - i] = (sequence ushr (i * 8)).toByte()
                        }
                    }
                    

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    
                    playlistLines.forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) {
                                    keyUrlForSession = resolveUrl(baseUri, requestUrl, match.groupValues[1])
                                    val newKeyLine = trimmed.replace(match.groupValues[1], "${prefix}/key.bin")
                                    sb.append(newKeyLine).append("\n")
                                } else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            if (testSegmentForSession == null) testSegmentForSession = absSeg
                            sb.append(absSeg).append("\n")
                        }
                    }
                    
                    newProxy.addSession(sessionId, sb.toString(), currentSessionKeys, headers,
                        parsedIv, keyUrlForSession, testSegmentForSession)
                    
                    val finalUrl = "${prefix}/$videoId/playlist.m3u8"
                    
                    callback(newExtractorLink(name, name, finalUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$playerOrigin/"; this.headers = headers
                    })
                    Log.i("TVWikiProxyTrace", "phase=LINK trace=${trace} mode=proxy elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - started))
                    return true
                } else {
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) {
                        this.referer = "$playerOrigin/"; this.headers = headers
                    })
                    Log.i("TVWikiProxyTrace", "phase=LINK trace=${trace} mode=direct elapsedMs=" +
                        (android.os.SystemClock.elapsedRealtime() - started))
                    return true
                }
            } catch (e: Exception) {
                Log.e("TVWikiProxyTrace", "phase=EXTRACT_EXCEPTION trace=${trace}", e)
            }
        }
        Log.w("TVWikiProxyTrace", "phase=EXTRACT_NO_LINK trace=${trace} elapsedMs=" +
            (android.os.SystemClock.elapsedRealtime() - started))
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<String?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var detectedCUrl: String? = null

        cont.invokeOnCancellation {
            handler.post {
                try { webView?.destroy(); webView = null } catch (e: Exception) {}
            }
        }

        val hookScript = """
            (function() {
                window.G = false;
                if (window.crypto && window.crypto.subtle) {
                    const originalImportKey = window.crypto.subtle.importKey;
                    Object.defineProperty(window.crypto.subtle, 'importKey', {
                        value: function(format, keyData, algorithm, extractable, keyUsages) {
                            if (format === 'raw' && (keyData.byteLength === 16 || keyData.length === 16)) {
                                try {
                                    let bytes = new Uint8Array(keyData);
                                    let hex = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join('');
                                    console.log("CapturedKeyHex:[CRYPTO]" + hex);
                                } catch(e) {}
                            }
                            return originalImportKey.apply(this, arguments);
                        },
                        configurable: true,
                        writable: true
                    });
                }
                const originalSet = Uint8Array.prototype.set;
                Uint8Array.prototype.set = function(source, offset) {
                    if (source && source.length === 16) {
                        try {
                            let hex = Array.from(source).map(b => b.toString(16).padStart(2, '0')).join('');
                            console.log("CapturedKeyHex:[SET]" + hex);
                        } catch(e) {}
                    }
                    return originalSet.apply(this, arguments);
                };
            })();
        """.trimIndent()

        handler.post {
            try {
                val context: Context = (CloudStreamApp.context ?: app) as Context
                webView = WebView(context)
                
                webView?.settings?.apply {
                    javaScriptEnabled = true; domStorageEnabled = true; userAgentString = DESKTOP_UA
                }

                val discoveryTimeout = Runnable {
                    if (cont.isActive) {
                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                        cont.resume(null)
                    }
                }
                handler.postDelayed(discoveryTimeout, 15000)

                webView?.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) { 
                                if (detectedCUrl != null && cont.isActive) {
                                    // [수정: 불완전한 콜백 해제 차단]
                                    handler.removeCallbacksAndMessages(null)
                                    handler.post {
                                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                        cont.resume(detectedCUrl)
                                    }
                                }
                            }
                        }
                        return true
                    }
                }

                webView?.webViewClient = object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, url, favicon); view?.evaluateJavascript(hookScript, null)
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: ""
                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            detectedCUrl = reqUrl
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            
                            if (sessionKeys.isNotEmpty() && cont.isActive) {
                                handler.removeCallbacksAndMessages(null)
                                handler.post {
                                    try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                    cont.resume(detectedCUrl)
                                }
                            } else {
                                handler.postDelayed({
                                    if (cont.isActive) {
                                        handler.removeCallbacksAndMessages(null)
                                        try { webView?.destroy(); webView = null } catch (e: Exception) {}
                                        cont.resume(detectedCUrl)
                                    }
                                }, 5000)
                            }
                        }
                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url); view?.evaluateJavascript(hookScript, null)
                    }
                }
                webView?.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    class ProxyWebServer {
        private data class Session(
            val id: String,
            val playlist: ByteArray,
            val capturedKeys: MutableSet<String>,
            val headers: Map<String, String>,
            val iv: ByteArray,
            val keyUrl: String?,
            val segmentUrl: String?,
            val keyMutex: Mutex = Mutex(),
            @Volatile var verifiedKey: ByteArray? = null,
            @Volatile var lastAccess: Long = android.os.SystemClock.elapsedRealtime()
        )

        private val sessions = ConcurrentHashMap<String, Session>()
        private var socket: ServerSocket? = null
        @Volatile var running = false
            private set
        var port = 0
            private set

        fun start(): Boolean = try {
            val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            server.soTimeout = 60000
            socket = server
            port = server.localPort
            running = true
            Log.i("TVWikiProxyTrace", "phase=SERVER_START port=$port bind=loopback")
            CoroutineScope(Dispatchers.IO).launch {
                while (running) {
                    try {
                        val client = server.accept()
                        launch { serve(client) }
                    } catch (_: SocketTimeoutException) {
                    } catch (e: Exception) {
                        if (running) Log.w("TVWikiProxyTrace", "phase=ACCEPT_ERROR type=${e.javaClass.simpleName}")
                    }
                    expire()
                }
            }
            true
        } catch (error: Exception) {
            Log.e("TVWikiProxyTrace", "phase=SERVER_BIND_FAILED", error)
            false
        }

        fun addSession(
            id: String, playlist: String, capturedKeys: MutableSet<String>,
            headers: Map<String, String>, iv: ByteArray, keyUrl: String?, segmentUrl: String?
        ) {
            sessions[id] = Session(id, playlist.toByteArray(Charsets.UTF_8), capturedKeys,
                headers.toMap(), iv, keyUrl, segmentUrl)
            Log.i("TVWikiProxyTrace", "phase=SESSION_ADD id=${id.take(8)} active=${sessions.size} " +
                "playlistBytes=${sessions[id]?.playlist?.size}")
        }

        private fun expire() {
            val now = android.os.SystemClock.elapsedRealtime()
            for ((id, state) in sessions) {
                if (now - state.lastAccess >= 24L * 60 * 60 * 1000 && sessions.remove(id, state)) {
                    Log.i("TVWikiProxyTrace", "phase=SESSION_EXPIRE id=${id.take(8)} active=${sessions.size}")
                }
            }
            if (sessions.isEmpty()) {
                running = false
                try { socket?.close() } catch (_: Exception) { }
                socket = null
                Log.i("TVWikiProxyTrace", "phase=SERVER_STOP reason=idle")
            }
        }

        private suspend fun serve(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 5000
                    val first = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                    val path = first.split(" ").getOrNull(1)?.substringBefore('?').orEmpty()
                    val parts = path.trimStart('/').split('/')
                    val id = parts.firstOrNull().orEmpty()
                    val session = sessions[id]
                    val data = if (session == null) null else when {
                        parts.size == 3 && parts[2] == "playlist.m3u8" -> session.playlist
                        parts.size == 2 && parts[1] == "key.bin" ->
                            session.keyMutex.withLock {
                                session.verifiedKey ?: verifyMultipleKeys(session)?.also {
                                    session.verifiedKey = it
                                }
                            }
                        else -> null
                    }
                    if (data != null) session.lastAccess = android.os.SystemClock.elapsedRealtime()
                    val type = if (path.endsWith(".m3u8")) "application/vnd.apple.mpegurl" else "application/octet-stream"
                    val status = when {
                        session == null -> "404 Not Found"
                        data == null && parts.size == 2 && parts[1] == "key.bin" -> "502 Bad Gateway"
                        data == null -> "404 Not Found"
                        else -> "200 OK"
                    }
                    val payload = data ?: ByteArray(0)
                    val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
                        "Connection: close\r\nContent-Length: ${payload.size}\r\n" +
                        "Access-Control-Allow-Origin: *\r\n\r\n"
                    val output = socket.getOutputStream()
                    output.write(header.toByteArray(Charsets.US_ASCII))
                    output.write(payload)
                    output.flush()
                    Log.i("TVWikiProxyTrace", "phase=HTTP id=${id.take(8)} resource=${if (path.endsWith(".m3u8")) "playlist" else "key"} " +
                        "status=$status bytes=${payload.size}")
                }
            } catch (e: Exception) {
                Log.w("TVWikiProxyTrace", "phase=HTTP_ERROR type=${e.javaClass.simpleName}")
                try { client.close() } catch (_: Exception) { }
            }
        }

        private suspend fun verifyMultipleKeys(session: Session): ByteArray? {
            val url = session.segmentUrl ?: return null
            val iv = session.iv
            val started = android.os.SystemClock.elapsedRealtime()
            return try {
                val response = app.get(url, headers = session.headers + ("Range" to "bytes=0-575"))
                val prefix = ByteArray(576)
                var count = 0
                response.body.byteStream().use { input ->
                    while (count < prefix.size) {
                        val n = input.read(prefix, count, prefix.size - count)
                        if (n <= 0) break
                        count += n
                    }
                }
                val bytes = prefix.copyOf(count - count % 16)
                Log.i("TVWikiProxyTrace", "phase=SEGMENT_PROBE session=${session.id.take(8)} http=${response.code} bytes=$count elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
                if (bytes.size < 16) return null
                val captured = synchronized(session.capturedKeys) { session.capturedKeys.toList() }
                val candidates = captured.filter { it.matches(Regex("[0-9a-fA-F]{32}")) }
                    .map { it.hexToByteArray() }.toMutableList()
                session.keyUrl?.let { keyUrl ->
                    try {
                        val keyResponse = app.get(keyUrl, headers = session.headers)
                        val rawKey = keyResponse.body.bytes()
                        if (rawKey.size == 16) candidates.add(rawKey)
                        else decodeLevel7Key(rawKey)?.let { candidates.add(it) }
                        Log.i("TVWikiProxyTrace", "phase=KEY_SOURCE session=${session.id.take(8)} http=${keyResponse.code} bytes=${rawKey.size} candidates=${candidates.size}")
                    } catch (e: Exception) {
                        Log.w("TVWikiProxyTrace", "phase=KEY_SOURCE_ERROR session=${session.id.take(8)} type=${e.javaClass.simpleName}")
                    }
                }
                for ((idx, key) in candidates.withIndex()) {
                    if (looksLikeMedia(decryptAES(bytes, key, iv))) {
                        Log.i("TVWikiProxyTrace", "phase=KEY_VERIFIED session=${session.id.take(8)} index=$idx candidates=${candidates.size} elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}")
                        synchronized(session.capturedKeys) { session.capturedKeys.clear() }
                        return key
                    }
                }
                Log.w("TVWikiProxyTrace", "phase=KEY_INVALID session=${session.id.take(8)} candidates=${candidates.size}")
                null
            } catch (e: Exception) {
                Log.e("TVWikiProxyTrace", "phase=KEY_VERIFY_ERROR session=${session.id.take(8)}", e)
                null
            }
        }

        // Same seven inverse steps used by the site's player.js decodeL7().
        private fun decodeLevel7Key(payload: ByteArray): ByteArray? {
            return try {
                val json = JSONObject(String(payload, Charsets.UTF_8))
                if (json.getInt("version") != 7 || json.getInt("key_length") != 16) return null
                val layers = json.getJSONArray("layers")
                val byName = (0 until layers.length()).associate { i ->
                    val layer = layers.getJSONObject(i)
                    layer.getString("name") to layer
                }
                fun layer(name: String): JSONObject = byName.getValue(name)
                fun b64(value: String): ByteArray = Base64.decode(value, Base64.URL_SAFE or Base64.NO_WRAP)
                fun ints(obj: JSONObject, field: String): List<Int> {
                    val array = obj.getJSONArray(field)
                    return (0 until array.length()).map { array.getInt(it) }
                }

                val encrypted = b64(json.getString("encrypted_key"))
                val mask = b64(layer("final_encrypt").getString("xor_mask"))
                val lengths = ints(layer("decoy_shuffle"), "segment_lengths")
                require(mask.isNotEmpty() && lengths.all { it > 0 })
                val total = lengths.sum()
                require(total in 16..encrypted.size && total <= 65536)
                val combined = ByteArray(total) { i ->
                    ((encrypted[i].toInt() and 255) xor (mask[i % mask.size].toInt() and 255)).toByte()
                }
                var offset = 0
                val segments = lengths.map { length ->
                    combined.copyOfRange(offset, offset + length).also { offset += length }
                }
                val positions = ints(layer("decoy_shuffle"), "real_positions")
                require(positions.size == 16 && positions.all { it in segments.indices })
                val chained = positions.map { segments[it] }

                var previous = b64(layer("xor_chain").getString("init_key"))
                require(previous.isNotEmpty())
                val noisy = chained.map { segment ->
                    val decoded = ByteArray(segment.size) { i ->
                        ((segment[i].toInt() and 255) xor (previous[i % previous.size].toInt() and 255)).toByte()
                    }
                    previous = segment.copyOfRange(0, minOf(4, segment.size))
                    decoded
                }
                val segmentPerm = ints(layer("segment_noise"), "perm")
                require(segmentPerm.size == 16 && segmentPerm.toSet().size == 16 && segmentPerm.all { it in 0..15 })
                val level3 = ByteArray(16)
                for (i in 0 until 16) level3[segmentPerm[i]] = noisy[i][0]

                val rotations = ints(layer("bit_rotate"), "rotations")
                require(rotations.isNotEmpty() && rotations.all { it >= 0 })
                val level2 = ByteArray(16) { i ->
                    val shift = rotations[i % rotations.size] % 8
                    val value = level3[i].toInt() and 255
                    ((value ushr shift) or (value shl (8 - shift))).toByte()
                }
                val inverse = b64(layer("sbox").getString("inverse_sbox"))
                require(inverse.size == 256)
                val level1 = ByteArray(16) { i -> inverse[level2[i].toInt() and 255] }

                val bitPerm = ints(layer("bit_interleave"), "perm")
                require(bitPerm.size == 128 && bitPerm.toSet().size == 128 && bitPerm.all { it in 0..127 })
                val bits = IntArray(128)
                for (i in 0 until 128) {
                    bits[bitPerm[i]] = ((level1[i / 8].toInt() and 255) ushr (7 - i % 8)) and 1
                }
                val key = ByteArray(16) { i ->
                    var value = 0
                    for (bit in 0 until 8) value = (value shl 1) or bits[i * 8 + bit]
                    value.toByte()
                }
                key
            } catch (e: Exception) {
                null
            }
        }

        private fun looksLikeMedia(data: ByteArray): Boolean {
            if (data.size >= 189 && data[0] == 0x47.toByte() &&
                data[188] == 0x47.toByte() &&
                (data.size < 377 || data[376] == 0x47.toByte())) return true
            if (data.size >= 3 && String(data, 0, 3, Charsets.US_ASCII) == "ID3") return true
            if (data.size >= 8) {
                val box = String(data, 4, 4, Charsets.US_ASCII)
                if (box == "moof" || box == "styp" || box == "ftyp" ||
                    box == "sidx" || box == "emsg") return true
            }
            return false
        }
        
        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                // Decrypt only block-aligned prefix for key verification.
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}
