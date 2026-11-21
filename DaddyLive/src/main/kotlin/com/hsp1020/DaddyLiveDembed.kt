package com.hsp1020

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

/**
 * Uses the real embedded page to obtain its authenticated short-lived playlist token.
 * No challenge/ticket bypass or hardcoded tokens. Local HLS rewriting is limited to
 * authenticated dembed streams; ordinary DaddyLive players retain their original path.
 */
internal object DaddyLiveDembed {
    private const val EMBED_ORIGIN = "https://dembed.st"
    private val main = Handler(Looper.getMainLooper())
    private val sessions = ConcurrentHashMap<String, Session>()
    private val io = CoroutineScope(Dispatchers.IO)
    private val attribute = Regex("""URI="([^"]+)"""")
    private var server: ServerSocket? = null
    @Volatile private var port = 0

    private class Resource(val url: String, val playlist: Boolean)
    private class Session(val parent: String) {
        val id: String = UUID.randomUUID().toString().replace("-", "")
        val resources = ConcurrentHashMap<String, Resource>()
        @Volatile var browser: WebView? = null
        @Volatile var url: String = ""
        @Volatile var token: String = ""
        @Volatile var key: ByteArray? = null
        @Volatile var pending: CompletableDeferred<String>? = null
        @Volatile var firstPlaylist: String? = null
        @Volatile var lastUse = android.os.SystemClock.elapsedRealtime()
        @Volatile var userAgent = "Mozilla/5.0"
        val headers: Map<String, String>
            get() = mutableMapOf(
                "Origin" to EMBED_ORIGIN,
                "Referer" to "$EMBED_ORIGIN/",
                "User-Agent" to userAgent,
                "Accept" to "*/*"
            ).also { map ->
                if (url.isNotBlank()) {
                    CookieManager.getInstance().getCookie(url)?.let { map["Cookie"] = it }
                }
            }

        fun onToken(response: ByteArray) {
            val data = runCatching { JSONObject(String(response, Charsets.UTF_8)) }.getOrNull() ?: return
            val encoded = data.optString("k")
            if (encoded.isNotBlank()) {
                val decoded = runCatching {
                    Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                }.getOrNull()
                if (decoded != null && decoded.size in setOf(16, 24, 32)) key = decoded
            }
            val current = data.optString("token").ifBlank { data.optString("t") }
            if (current.isNotBlank()) token = current
        }

        fun captureManifest(address: String) {
            url = address
            val t = Uri.parse(address).getQueryParameter("t")
            if (!t.isNullOrBlank()) token = t
        }

        fun record(text: String) {
            if (!text.startsWith("DADDY_TOKEN:")) return
            val value = text.substringAfter("DADDY_TOKEN:")
            if (value.isNotBlank()) token = value
            pending?.complete(value)
            pending = null
        }

        suspend fun renew(): String {
            val promise = CompletableDeferred<String>()
            pending = promise
            main.post {
                browser?.evaluateJavascript(
                    "try { fetchPlaylistToken().then(function(t) { console.log('DADDY_TOKEN:' + (t || '')) })" +
                        ".catch(function(){console.log('DADDY_TOKEN:')}) } catch(e) { console.log('DADDY_TOKEN:') }",
                    null
                ) ?: promise.complete("")
            }
            return withTimeoutOrNull(4500L) { promise.await() }.orEmpty().also {
                if (pending === promise) pending = null
            }
        }

        fun signed(address: String): String {
            if (token.isBlank() || !address.substringBefore('?').endsWith(".m3u8", true)) return address
            val input = Uri.parse(address)
            val out = input.buildUpon().clearQuery()
            input.queryParameterNames.filterNot { it == "t" || it == "_" }.forEach { name ->
                input.getQueryParameters(name).forEach { out.appendQueryParameter(name, it) }
            }
            out.appendQueryParameter("t", token)
            out.appendQueryParameter("_", System.currentTimeMillis().toString())
            return out.build().toString()
        }

        fun reference(target: String, manifest: Boolean): String {
            val key = UUID.randomUUID().toString().replace("-", "")
            resources[key] = Resource(target, manifest)
            return "http://127.0.0.1:$port/$id/$key"
        }

        fun close() {
            val player = browser
            browser = null
            main.post { runCatching { player?.stopLoading(); player?.destroy() } }
        }
    }

    private fun fetchToken(request: WebResourceRequest, session: Session): WebResourceResponse? {
        val url = request.url.toString()
        val connection = (URL(url).openConnection() as? HttpURLConnection) ?: return null
        return try {
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.setRequestProperty("Accept-Encoding", "identity")
            request.requestHeaders.forEach { (k, v) ->
                if (!k.equals("Host", true) && !k.equals("Accept-Encoding", true)) {
                    connection.setRequestProperty(k, v)
                }
            }
            connection.setRequestProperty("Origin", EMBED_ORIGIN)
            connection.setRequestProperty("Referer", "$EMBED_ORIGIN/")
            CookieManager.getInstance().getCookie(url)?.let { connection.setRequestProperty("Cookie", it) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val bytes = stream?.use { it.readBytes().takeIf { b -> b.size <= 16384 } } ?: ByteArray(0)
            if (status in 200..299) session.onToken(bytes)
            val headers = connection.headerFields
                .mapNotNull { (key, values) ->
                    if (key == null || key.equals("Content-Length", true) || key.equals("Content-Encoding", true)) null
                    else key to values.firstOrNull().orEmpty()
                }.toMap().toMutableMap()
            headers["Access-Control-Allow-Origin"] = EMBED_ORIGIN
            headers["Cache-Control"] = "no-store"
            WebResourceResponse(
                "application/json", "UTF-8", status.coerceIn(100, 599),
                if (status in 200..299) "OK" else "Upstream Error",
                headers, ByteArrayInputStream(bytes)
            )
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    suspend fun extract(embedded: String, parent: String, name: String): ExtractorLink? {
        if (!embedded.startsWith("https://dembed.st/")) return null
        val session = Session(parent)
        val captured = withTimeoutOrNull(20000L) {
            suspendCancellableCoroutine<String?> { continuation ->
                var web: WebView? = null
                val timeout = Runnable {
                    if (continuation.isActive) continuation.resume(null)
                }
                continuation.invokeOnCancellation {
                    main.post {
                        main.removeCallbacks(timeout)
                        runCatching { web?.destroy() }
                    }
                }
                main.post {
                    try {
                        val context = (CloudStreamApp.context ?: app) as android.content.Context
                        web = WebView(context)
                        val view = web!!
                        session.browser = view
                        session.userAgent = view.settings.userAgentString
                        view.settings.javaScriptEnabled = true
                        view.settings.domStorageEnabled = true
                        view.settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                        view.webChromeClient = object : WebChromeClient() {
                            override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                session.record(message?.message().orEmpty())
                                return false
                            }
                        }
                        view.webViewClient = object : WebViewClient() {
                            override fun shouldInterceptRequest(
                                view: WebView?, request: WebResourceRequest?
                            ): WebResourceResponse? {
                                if (request == null) return null
                                val uri = request.url
                                val address = uri.toString()
                                if (uri.scheme == "https" && uri.path == "/api/playlist-token" &&
                                    uri.getQueryParameter("slug")?.startsWith("premium") == true
                                ) {
                                    return fetchToken(request, session)
                                }
                                if (uri.scheme == "https" && uri.path?.endsWith(".m3u8") == true &&
                                    uri.path?.contains("/premium") == true &&
                                    uri.path?.substringAfterLast("/") == "index.m3u8"
                                ) {
                                    session.captureManifest(address)
                                    main.post {
                                        if (continuation.isActive) {
                                            main.removeCallbacks(timeout)
                                            continuation.resume(address)
                                        }
                                    }
                                }
                                return null
                            }
                        }
                        main.postDelayed(timeout, 19000L)
                        val url = if (parent.startsWith("https://") && !parent.contains("dembed.st")) parent else ""
                        if (url.isNotBlank()) {
                            view.loadUrl(url)
                        } else {
                            // The player explicitly requires an iframe (it rejects top-level navigation).
                            val safeUrl = embedded.replace("&", "&amp;").replace('"', '_')
                            val html = """<html><body><iframe src="$safeUrl" style="width:100%;height:100%"></iframe></body></html>"""
                            view.loadDataWithBaseURL("https://thedaddy.li/", html, "text/html", "UTF-8", null)
                        }
                    } catch (_: Exception) {
                        main.removeCallbacks(timeout)
                        if (continuation.isActive) continuation.resume(null)
                    }
                }
            }
        }
        if (captured.isNullOrBlank()) {
            session.close()
            return null
        }
        return try {
            val response = app.get(captured, headers = session.headers, timeout = 5)
            if (!response.isSuccessful) {
                session.close()
                null
            } else {
                val playlist = decodePlaylist(response.text, session.key)
                if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
                    session.close()
                    null
                } else {
                    val signed = Uri.parse(captured).getQueryParameter("t") != null
                    if (!signed && session.key == null && directCompatible(playlist, captured, session.headers)) {
                        // Probe a media segment before bypassing the wrapper decoder.
                        session.close()
                        newExtractorLink(name, name, captured, ExtractorLinkType.M3U8) {
                            referer = "$EMBED_ORIGIN/"
                            quality = Qualities.Unknown.value
                            headers = session.headers
                        }
                    } else {
                        val openPort = startServer()
                        if (openPort == 0) {
                            session.close()
                            null
                        } else {
                            session.firstPlaylist = playlist
                            sessions[session.id] = session
                            val local = "http://127.0.0.1:$openPort/${session.id}/index.m3u8"
                            newExtractorLink(name, name, local, ExtractorLinkType.M3U8) {
                                referer = "$EMBED_ORIGIN/"
                                quality = Qualities.Unknown.value
                            }
                        }
                    }
                }
            }
        } catch (cancel: CancellationException) {
            session.close()
            throw cancel
        } catch (_: Exception) {
            session.close()
            null
        }
    }

    /** A normal unsigned playlist goes directly to Media3 only when its first media
     * segment is genuine TS/fMP4, not a disguised image or a nested playlist. */
    private suspend fun directCompatible(
        playlist: String, base: String, headers: Map<String, String>
    ): Boolean {
        val first = playlist.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
            ?: return false
        val url = absolute(base, first) ?: return false
        if (url.substringBefore('?').endsWith(".m3u8", true)) return false
        return try {
            val response = app.get(url, headers = headers + ("Range" to "bytes=0-575"), timeout = 4)
            if (!response.isSuccessful) return false
            val prefix = ByteArray(376)
            var count = 0
            response.body.byteStream().use { input ->
                while (count < prefix.size) {
                    val read = input.read(prefix, count, prefix.size - count)
                    if (read <= 0) break
                    count += read
                }
            }
            count >= 189 && prefix[0] == 0x47.toByte() && prefix[188] == 0x47.toByte() ||
                (count >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof"))
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (_: Exception) {
            false
        }
    }

    @Synchronized private fun startServer(): Int {
        if (server?.isClosed == false) return port
        return try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            socket.soTimeout = 15000
            server = socket
            port = socket.localPort
            io.launch {
                while (!socket.isClosed) {
                    try {
                        val client = socket.accept()
                        launch { serve(client) }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: Exception) {
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    sessions.forEach { (key, session) ->
                        if (now - session.lastUse > 15 * 60 * 1000L && sessions.remove(key, session)) {
                            session.close()
                        }
                    }
                    if (sessions.isEmpty()) {
                        runCatching { socket.close() }
                        synchronized(DaddyLiveDembed) {
                            if (server === socket) { server = null; port = 0 }
                        }
                    }
                }
            }
            port
        } catch (_: Exception) {
            0
        }
    }

    private fun absolute(base: String, relative: String): String? = runCatching {
        URL(URL(base), relative).toString().takeIf { target ->
            val url = URL(target)
            url.protocol == "https" && url.host.isNotBlank() &&
                url.host != "localhost" && url.host != "127.0.0.1" &&
                !url.host.endsWith(".local")
        }
    }.getOrNull()

    private fun rewrite(playlist: String, source: String, session: Session): String =
        playlist.lineSequence().joinToString("\n") { line ->
            if (line.startsWith("#")) {
                attribute.replace(line) { match ->
                    val absolute = absolute(source, match.groupValues[1])
                    if (absolute == null) match.value
                    else "URI=\"${session.reference(absolute, absolute.substringBefore('?').endsWith(".m3u8", true))}\""
                }
            } else if (line.isBlank()) line else {
                val target = absolute(source, line.trim())
                if (target == null) line
                else session.reference(target, target.substringBefore('?').endsWith(".m3u8", true))
            }
        } + "\n"

    private fun decodePlaylist(body: String, key: ByteArray?): String? {
        if (body.trimStart().startsWith("#EXTM3U")) return body
        if (key == null) return null
        val sequence = Regex("""(?m)^#EXT-X-MEDIA-SEQUENCE:\s*(\d+)""")
            .find(body)?.groupValues?.getOrNull(1)?.toLongOrNull() ?: 0L
        var counter = sequence
        val output = StringBuilder()
        for (raw in body.lineSequence()) {
            val line = raw.trim()
            if (line.isBlank() || line.startsWith("#")) {
                output.append(line).append('\n')
                continue
            }
            val bytes = runCatching { Base64.decode(line, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
                ?: return null
            val iv = ByteBuffer.allocate(16).putLong(8, counter).array()
            val decrypted = runCatching {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
            }.getOrNull() ?: return null
            output.append(decrypted).append('\n')
            counter++
        }
        return output.toString().takeIf { it.trimStart().startsWith("#EXTM3U") }
    }

    private suspend fun loadManifest(session: Session, resource: Resource?): Pair<String, String>? {
        val original = resource?.url ?: session.url
        for (attempt in 0..1) {
            if (attempt == 1) {
                if (session.renew().isBlank()) return null
            }
            val target = session.signed(original)
            val response = runCatching { app.get(target, headers = session.headers, timeout = 5) }.getOrNull()
            if (response?.isSuccessful == true) {
                val decoded = decodePlaylist(response.text, session.key)
                if (decoded != null) return target to decoded
            }
        }
        return null
    }

    private suspend fun serve(client: Socket) {
        try {
            client.use { socket ->
                socket.soTimeout = 12000
                val request = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                val fields = request.split(' ')
                val path = fields.getOrNull(1)?.substringBefore('?').orEmpty()
                val parts = path.trimStart('/').split('/')
                val session = sessions[parts.firstOrNull().orEmpty()]
                var data: ByteArray? = null
                var type = "application/octet-stream"
                if (fields.firstOrNull() == "GET" && session != null && parts.size == 2) {
                    session.lastUse = android.os.SystemClock.elapsedRealtime()
                    val resource = session.resources[parts[1]]
                    when {
                        parts[1] == "index.m3u8" || resource?.playlist == true -> {
                            val first = if (resource == null) session.firstPlaylist else null
                            val original = if (resource == null) session.url else resource.url
                            val manifest = if (first != null) {
                                session.firstPlaylist = null
                                original to first
                            } else loadManifest(session, resource)
                            if (manifest != null) {
                                data = rewrite(manifest.second, manifest.first, session).toByteArray()
                                type = "application/vnd.apple.mpegurl"
                            }
                        }
                        resource != null -> {
                            val response = runCatching {
                                app.get(resource.url, headers = session.headers, timeout = 8)
                            }.getOrNull()
                            if (response?.isSuccessful == true) {
                                val bytes = response.body.bytes()
                                data = DaddyLiveSegment.unwrap(bytes)
                                type = if (data?.firstOrNull() == 0x47.toByte()) "video/mp2t"
                                else "application/octet-stream"
                            }
                        }
                    }
                }
                val status = if (data == null) "502 Bad Gateway" else "200 OK"
                val payload = data ?: ByteArray(0)
                val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
                    "Connection: close\r\nCache-Control: no-store\r\nContent-Length: ${payload.size}\r\n\r\n"
                socket.getOutputStream().apply {
                    write(header.toByteArray(StandardCharsets.US_ASCII))
                    write(payload)
                    flush()
                }
            }
        } catch (_: Exception) {
            runCatching { client.close() }
        }
    }
}
