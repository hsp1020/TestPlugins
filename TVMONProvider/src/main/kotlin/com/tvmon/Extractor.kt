package com.tvmon

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.util.Collections
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread
import kotlin.coroutines.resume

class BunnyPoorCdn : ExtractorApi() {
    override val name = "TVMON"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    companion object {
        @Volatile private var currentProxyServer: ProxyWebServer? = null
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, thumbnailHint: String? = null): Boolean {
        synchronized(this) { if (currentProxyServer != null) { currentProxyServer?.stop(); currentProxyServer = null } }

        var cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val cleanReferer = referer?.replace(Regex("[\\r\\n\\s]"), "")?.trim() ?: "https://tvmon.site/"
        
        if (!cleanUrl.contains("v/f/") && !cleanUrl.contains("v/e/")) {
            try {
                val refRes = app.get(cleanReferer)
                Regex("""(?:src|data-player\d*)=['"](https://player\.bunny-frame\.online/[^"']+)['"]""").find(refRes.text)?.let {
                    cleanUrl = it.groupValues[1].replace("&amp;", "&").trim()
                }
            } catch (e: Exception) { }
        }

        var capturedUrl: String? = cleanUrl
        val currentSessionKeys = Collections.synchronizedSet(mutableSetOf<String>())

        if (!cleanUrl.contains("/c.html")) {
            val webViewResult = runWebViewHook(cleanUrl, cleanReferer, currentSessionKeys)
            if (webViewResult != null) capturedUrl = webViewResult
        }

        if (capturedUrl != null) {
            val cookie = CookieManager.getInstance().getCookie(capturedUrl)
            val headers = mutableMapOf("User-Agent" to DESKTOP_UA, "Referer" to "https://player.bunny-frame.online/", "Origin" to "https://player.bunny-frame.online")
            if (!cookie.isNullOrEmpty()) headers["Cookie"] = cookie

            try {
                var requestUrl = capturedUrl!!.substringBefore("#")
                var content = app.get(requestUrl, headers = headers).text.trim()

                if (!content.startsWith("#EXTM3U")) {
                    Regex("""(https?://[^"']+\.m3u8[^"']*)""").find(content)?.let {
                        requestUrl = it.groupValues[1]; content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                if (content.contains("#EXT-X-STREAM-INF")) {
                    content.lines().lastOrNull { it.isNotBlank() && !it.startsWith("#") }?.let {
                        requestUrl = resolveUrl(try { URI(requestUrl) } catch(e: Exception){null}, requestUrl, it)
                        content = app.get(requestUrl, headers = headers).text.trim()
                    }
                }

                if (content.lines().any { it.startsWith("#EXT-X-KEY") && it.contains("/v/key7") }) {
                    val newProxy = ProxyWebServer(currentSessionKeys).apply { start(); updateSession(headers) }
                    currentProxyServer = newProxy

                    val videoId = Regex("""/v/[ef]/([^/]+)""").find(capturedUrl!!)?.groupValues?.get(1) ?: "video"
                    newProxy.setIv((Regex("""IV=("?)(0x[0-9a-fA-F]+)\1""").find(content)?.groupValues?.get(2) ?: "0x00000000000000000000000000000000").removePrefix("0x").hexToByteArray())

                    val baseUri = try { URI(requestUrl) } catch (e: Exception) { null }
                    val sb = StringBuilder()
                    
                    content.lines().forEach { line ->
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) return@forEach
                        if (trimmed.startsWith("#")) {
                            if (trimmed.startsWith("#EXT-X-KEY") && trimmed.contains("/v/key7")) {
                                val match = Regex("""URI="([^"]+)"""").find(trimmed)
                                if (match != null) sb.append(trimmed.replace(match.groupValues[1], "http://127.0.0.1:${newProxy.port}/key.bin")).append("\n")
                                else sb.append(trimmed).append("\n")
                            } else sb.append(trimmed).append("\n")
                        } else {
                            val absSeg = resolveUrl(baseUri, requestUrl, trimmed)
                            newProxy.setTestSegment(absSeg) 
                            sb.append(absSeg).append("\n")
                        }
                    }
                    
                    newProxy.setPlaylist(sb.toString())
                    callback(newExtractorLink(name, name, "http://127.0.0.1:${newProxy.port}/$videoId/playlist.m3u8", ExtractorLinkType.M3U8) { this.referer = "https://player.bunny-frame.online/"; this.headers = headers })
                    return true
                } else {
                    callback(newExtractorLink(name, name, requestUrl, ExtractorLinkType.M3U8) { this.referer = "https://player.bunny-frame.online/"; this.headers = headers })
                    return true
                }
            } catch (e: Exception) { }
        }
        return false
    }

    private suspend fun runWebViewHook(url: String, referer: String, sessionKeys: MutableSet<String>) = suspendCancellableCoroutine<String?> { cont ->
        val handler = Handler(Looper.getMainLooper())
        var webView: WebView? = null
        var detectedCUrl: String? = null

        cont.invokeOnCancellation {
            handler.post { try { webView?.destroy(); webView = null } catch(e: Exception){} }
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
                        }, configurable: true, writable: true
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
                val context: Context = (AcraApplication.context ?: app) as Context
                webView = WebView(context)
                webView?.settings?.apply { javaScriptEnabled = true; domStorageEnabled = true; userAgentString = DESKTOP_UA }

                val discoveryTimeout = Runnable { if (cont.isActive) { try { webView?.destroy(); webView = null } catch (e: Exception) {}; cont.resume(null) } }
                handler.postDelayed(discoveryTimeout, 15000)

                webView?.webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                        val msg = consoleMessage?.message() ?: ""
                        if (msg.startsWith("CapturedKeyHex:")) {
                            val key = msg.substringAfter("CapturedKeyHex:").removePrefix("[SET]").removePrefix("[CRYPTO]")
                            if (sessionKeys.add(key)) { 
                                if (detectedCUrl != null && cont.isActive) {
                                    handler.removeCallbacksAndMessages(null)
                                    handler.post { try { webView?.destroy(); webView = null } catch(e: Exception){}; cont.resume(detectedCUrl) }
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
                        
                        if (reqUrl.contains("/key.bin") || reqUrl.contains("key7")) {
                            thread {
                                try {
                                    val keyBytes = app.get(reqUrl).body.bytes()
                                    if (keyBytes.size == 16) {
                                        val hex = keyBytes.joinToString("") { "%02x".format(it) }
                                        if (sessionKeys.add(hex) && cont.isActive) {
                                            handler.removeCallbacksAndMessages(null)
                                            handler.post { try { webView?.destroy(); webView = null } catch(e: Exception){}; cont.resume(detectedCUrl) }
                                        }
                                    }
                                } catch (e: Exception) {}
                            }
                        }

                        if (reqUrl.contains("/c.html") && reqUrl.contains("token=")) {
                            detectedCUrl = reqUrl
                            view?.post { view.evaluateJavascript(hookScript, null) }
                            handler.postDelayed({
                                if (cont.isActive) { try { webView?.destroy(); webView = null } catch (e: Exception) {}; cont.resume(detectedCUrl) }
                            }, 5000)
                        }
                        return super.shouldInterceptRequest(view, request)
                    }
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url); view?.evaluateJavascript(hookScript, null)
                    }
                }
                webView?.loadUrl(url, mapOf("Referer" to referer))
            } catch (e: Exception) { if (cont.isActive) cont.resume(null) }
        }
    }

    private fun resolveUrl(baseUri: URI?, baseUrlStr: String, target: String): String {
        if (target.startsWith("http")) return target
        return try { baseUri?.resolve(target).toString() } catch (e: Exception) {
            if (target.startsWith("/")) "${baseUrlStr.substringBefore("/", "https://")}//${baseUrlStr.split("/")[2]}$target"
            else "${baseUrlStr.substringBeforeLast("/")}/$target"
        }
    }

    class ProxyWebServer(private val sessionKeys: MutableSet<String>) {
        private var serverSocket: ServerSocket? = null
        private var isRunning = false
        var port: Int = 0
        @Volatile private var currentHeaders: Map<String, String> = emptyMap()
        @Volatile private var currentPlaylist: String = ""
        @Volatile private var verifiedKey: ByteArray? = null
        @Volatile private var currentIv: ByteArray? = null
        @Volatile private var testSegmentUrl: String? = null

        fun start() {
            try {
                serverSocket = ServerSocket(0).also { port = it.localPort }
                isRunning = true
                CoroutineScope(Dispatchers.IO).launch {
                    while (isRunning && serverSocket != null && !serverSocket!!.isClosed) { 
                        try { 
                            val socket = serverSocket!!.accept()
                            // [수정: 에러 원인 제거] 소켓 처리를 개별 코루틴(launch)으로 분리
                            launch { handleClient(socket) } 
                        } catch (e: Exception) {} 
                    } 
                }
            } catch (e: Exception) { }
        }

        fun stop() { isRunning = false; try { serverSocket?.close() } catch(e: Exception) {} }
        fun updateSession(h: Map<String, String>) { currentHeaders = h }
        fun setPlaylist(p: String) { currentPlaylist = p }
        fun setIv(iv: ByteArray) { currentIv = iv }
        fun setTestSegment(url: String) { if (testSegmentUrl == null) testSegmentUrl = url }

        // [수정: 에러 원인 제거] suspend 키워드 추가
        private suspend fun handleClient(socket: Socket) {
            try {
                socket.soTimeout = 5000
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val path = reader.readLine()?.split(" ")?.getOrNull(1) ?: return
                val output = socket.getOutputStream()

                if (path.contains("playlist.m3u8")) {
                    val payload = currentPlaylist.toByteArray()
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\nConnection: close\r\nContent-Length: ${payload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(payload)
                } else if (path.contains("/key.bin")) {
                    if (verifiedKey == null) verifiedKey = verifyMultipleKeys() // suspend 함수 정상 호출 가능!
                    val keyPayload = verifiedKey ?: ByteArray(16)
                    output.write("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nConnection: close\r\nContent-Length: ${keyPayload.size}\r\nAccess-Control-Allow-Origin: *\r\n\r\n".toByteArray())
                    output.write(keyPayload)
                }
                output.flush()
            } catch (e: Exception) { 
            } finally { try { socket.close() } catch(e: Exception) {} }
        }

        // [수정: 에러 원인 제거] runBlocking 없이 순수 suspend 함수로 작성
        private suspend fun verifyMultipleKeys(): ByteArray? {
            val url = testSegmentUrl ?: return null
            val targetIv = currentIv ?: ByteArray(16)
            try {
                val responseData = app.get(url, headers = currentHeaders).body.bytes()
                val checkSize = 1024 
                val safeCheckSize = if (responseData.size < checkSize) responseData.size else checkSize

                synchronized(sessionKeys) {
                    for ((index, hexKey) in sessionKeys.withIndex()) {
                        try {
                            val keyBytes = hexKey.hexToByteArray()
                            for (offset in 0..512) {
                                if (responseData.size < offset + safeCheckSize) break
                                val testChunk = responseData.copyOfRange(offset, offset + safeCheckSize)
                                val decrypted = decryptAES(testChunk, keyBytes, targetIv)
                                
                                if (decrypted.size >= 377 && decrypted[0] == 0x47.toByte() && decrypted[188] == 0x47.toByte() && decrypted[376] == 0x47.toByte()) {
                                    return keyBytes
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    return null
                }
            } catch (e: Exception) { return null }
        }

        private fun decryptAES(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
            return try {
                val cipher = Cipher.getInstance("AES/CBC/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                cipher.doFinal(data)
            } catch (e: Exception) { ByteArray(0) }
        }
    }
}

fun String.hexToByteArray(): ByteArray {
    val len = length
    val data = ByteArray(len / 2)
    var i = 0
    while (i < len) {
        data[i / 2] = ((Character.digit(this[i], 16) shl 4) + Character.digit(this[i+1], 16)).toByte()
        i += 2
    }
    return data
}
