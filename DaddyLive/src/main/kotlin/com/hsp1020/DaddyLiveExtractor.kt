package com.hsp1020

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.JavascriptInterface
import java.net.HttpURLConnection
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.json.JSONArray
import org.json.JSONTokener

class DaddyLiveExtractor : ExtractorApi() {
    private object Trace {
        private const val TAG = "DaddyLiveTrace"
        fun target(raw: String): String = try {
            val parsed = URL(raw)
            val path = parsed.path
            parsed.host + if (path.length > 160) path.take(52) + "/[opaquePathChars=${path.length}]" else path
        } catch (_: Exception) { "invalid-url" }
        fun event(phase: String, status: String, detail: String = "") {
            android.util.Log.i(TAG, "phase=$phase status=$status $detail")
        }
        private fun digest(raw: String): String = try {
            MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
                .take(6).joinToString("") { "%02x".format(it.toInt() and 255) }
        } catch (_: Exception) { "unknown" }
        fun key(url: String): String = digest(url)
        fun urlShape(raw: String?): String = try {
            if (raw.isNullOrBlank()) "absent" else {
                val u = URL(raw)
                "host=${u.host} pathId=${pathKey(raw)} urlId=${key(raw)} " +
                    "queryKeys=${Uri.parse(raw).queryParameterNames.sorted().joinToString(",").take(100)} " +
                    "queryChars=${u.query?.length ?: 0}"
            }
        } catch (_: Exception) { "invalid" }
        fun valueId(raw: String?): String = if (raw.isNullOrBlank()) "none" else key(raw)
        fun pathKey(url: String): String = runCatching {
            val parsed = URL(url)
            digest(parsed.host + parsed.path)
        }.getOrDefault("invalid")
        fun fingerprint(raw: String): String = try {
            val parsed = URL(raw)
            "urlId=${key(raw)} pathId=${digest(parsed.host + parsed.path)} " +
                "host=${parsed.host} queryKeys=${Uri.parse(raw).queryParameterNames.sorted().joinToString(",").take(90)}"
        } catch (_: Exception) { "invalid_url" }
        private fun host(raw: String?): String = try {
            if (raw.isNullOrBlank()) "none" else URL(raw).host
        } catch (_: Exception) { "unavailable" }
        fun headerProfile(headers: Map<String, String>): String {
            fun get(key: String) = headers.entries.firstOrNull { it.key.equals(key, true) }?.value
            val names = headers.keys.map { it.lowercase() }.sorted().joinToString(",").take(180)
            return "headerNames=$names originHost=${host(get("Origin"))} refHost=${host(get("Referer"))} " +
                "cookie=${get("Cookie") != null} auth=${get("Authorization") != null} range=${get("Range") != null}"
        }
        fun error(raw: String): String = raw
            .replace(Regex("""https?://[^\s"'<>()]+""", RegexOption.IGNORE_CASE), "[url]")
            .replace(Regex("""(?i)\b(token|ticket|secret|password|authorization|cookie|key)\s*[:=]\s*[^\s,;]+"""), "[secret]")
            .replace(Regex("""\s+"""), " ")
            .take(170)

        fun bodyReason(text: String): String {
            val body = text.take(512).lowercase()
            return when {
                "turnstile" in body || "captcha" in body -> "challenge_required"
                "expired" in body -> "expired"
                "not found" in body || "not_found" in body || "404" in body -> "not_found"
                "unavailable" in body -> "unavailable"
                "invalid ticket" in body -> "invalid_ticket"
                "invalid token" in body -> "invalid_token"
                "rate limit" in body || "too many" in body -> "rate_limited"
                "forbidden" in body -> "forbidden_unspecified"
                "denied" in body || "blocked" in body -> "access_denied_unspecified"
                "<html" in body -> "html_error_page"
                body.isBlank() -> "empty_error_body"
                else -> "unclassified_error_body"
            }
        }
    }

    override var mainUrl = "https://thedaddy.li"
    override val name = "DaddyLive"
    override val requiresReferer = false

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    /*
     * 사용자 지정 우선순서. 순서 변경 금지.
     * 선택된 채널 하나에 대해서만 사용한다.
     */
    private val players = listOf(
        "stream",
        "player",
        "watch",
        "plus",
        "cast",
        "casting"
    )
    private val dembedPlayerPath = Regex("""^/premiumtv/[a-z0-9_-]+\.php$""", RegexOption.IGNORE_CASE)

    private data class ExtractorChannelSelection(
        val origin: String,
        val eventTitle: String,
        val channelName: String,
        val streamId: String
    )

    private data class PlayerCandidate(
        val player: String,
        val url: String
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        currentCoroutineContext().ensureActive()
        Trace.event("PLAYER_ENTRY", "START", "target=${Trace.target(url)}")
        val selected = try {
            mapper.readValue<ExtractorChannelSelection>(url)
        } catch (_: Exception) {
            null
        }

        if (selected == null) {
            val direct = extractVideo(
                url = url,
                sourceName = name,
                parentReferer = referer
            )
            Trace.event("MEDIA3_LINK", if (direct != null) "EMITTED" else "NO_LINK", "mode=direct_entry")
            if (direct != null) callback(direct)
            return
        }

        val entryOrigin = selected.origin.trimEnd('/').ifEmpty { mainUrl }
        mainUrl = entryOrigin


        val entryUrl = "$entryOrigin/watch.php?id=${selected.streamId}"
        val candidates = discoverPlayerCandidates(
            entryOrigin = entryOrigin,
            entryUrl = entryUrl,
            streamId = selected.streamId
        )
        Trace.event("PLAYER_DISCOVERY", "READY", "channel=${selected.streamId} candidates=${candidates.size}")
        if (candidates.isEmpty()) return

        // Start stream, player and watch in parallel; then try plus, cast and casting.
        val sharedDembed = ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>>()
        val workers = minOf(3, candidates.size)
        val next = AtomicInteger(workers)
        val callbackMutex = Mutex()
        val callbackContext = currentCoroutineContext()
        val emitted = AtomicInteger(0)
        supervisorScope {
            repeat(workers) { slot ->
                Trace.event("PLAYER_CANDIDATE", "SCHEDULED",
                    "slot=${slot + 1} player=${candidates[slot].player}")
                launch(Dispatchers.IO) {
                    var index = slot
                    while (index < candidates.size) {
                        val candidate = candidates[index]
                        try {
                            val sourceName = "${selected.channelName} - ${candidate.player}"
                            Trace.event("PLAYER_CANDIDATE", "TRY",
                                "player=${candidate.player} target=${Trace.target(candidate.url)}")
                            val link = withTimeoutOrNull(35000L) {
                                extractVideo(candidate.url, sourceName, entryUrl, sharedDembed)
                            }
                            if (link == null) {
                                Trace.event("PLAYER_CANDIDATE", "FALLBACK",
                                    "player=${candidate.player} reason=no_valid_link_or_timeout")
                            } else {
                                val original = if (candidate.player == "player")
                                    DaddyLiveDembed.wrapDirectHls(link) else link
                                // One Dembed session can back multiple independent source URLs.
                                // CloudStream deduplicates identical URLs; loopback ignores query.
                                val playing = if (original.url.startsWith("http://127.0.0.1:"))
                                    newExtractorLink(sourceName, sourceName,
                                        Uri.parse(original.url).buildUpon()
                                            .appendQueryParameter("source", candidate.player).build().toString(),
                                        original.type) {
                                        this.referer = original.referer
                                        quality = original.quality
                                        headers = original.headers
                                    }
                                else original
                                callbackMutex.lock()
                                try {
                                    withContext(callbackContext) { callback(playing) }
                                    emitted.incrementAndGet()
                                    Trace.event("MEDIA3_LINK", "EMITTED",
                                        "player=${candidate.player} mode=${if (playing.url.startsWith("http://127.0.0.1:")) "proxy" else "direct"} target=${Trace.target(playing.url)}")
                                } finally {
                                    callbackMutex.unlock()
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (e: Exception) {
                            Trace.event("PLAYER_CANDIDATE", "ERROR",
                                "player=${candidate.player} reason=${e.javaClass.simpleName}")
                        }
                        index = next.getAndIncrement()
                    }
                }
            }
        }
        Trace.event("PLAYER_DIAGNOSTICS", "COMPLETE",
            "channel=${selected.streamId} candidates=${candidates.size} emitted=${emitted.get()}")
        if (emitted.get() == 0) {
            Trace.event("PLAYER_DISCOVERY", "EXHAUSTED",
                "candidates=${candidates.size} reason=all_failed")
        }
    }

    /*
     * v5 핵심:
     * 먼저 /watch.php?id=<id> 를 source-of-truth entry page로 읽는다.
     * entry HTML에 노출된 player URL / player key를 우선 수집하고,
     * 누락된 player만 공식 6개 folder pattern으로 보완한다.
     */
    private suspend fun discoverPlayerCandidates(
        entryOrigin: String,
        entryUrl: String,
        streamId: String
    ): List<PlayerCandidate> {
        val discovered = linkedMapOf<String, String>()

        try {
            val response = app.get(
                entryUrl,
                headers = pageHeaders(
                    parentUrl = "$entryOrigin/",
                    parentOrigin = entryOrigin
                ),
                timeout = 5
            )


            Trace.event("PLAYER_DISCOVERY", if (response.isSuccessful) "HTTP_OK" else "HTTP_FAIL",
                "target=${Trace.target(entryUrl)} http=${response.code}")
            if (response.isSuccessful) {
                val html = response.text

                // 1) HTML 안의 실제 stream-<id>.php URL을 직접 수집.
                val urlPatterns = listOf(
                    Regex(
                        """(?i)(https?://[^\s\"'<>]+/(?:stream|plus|watch|player|cast|casting)/stream-${Regex.escape(streamId)}\.php(?:\?[^\s\"'<>]*)?)"""
                    ),
                    Regex(
                        """(?i)(//[^\s\"'<>]+/(?:stream|plus|watch|player|cast|casting)/stream-${Regex.escape(streamId)}\.php(?:\?[^\s\"'<>]*)?)"""
                    ),
                    Regex(
                        """(?i)(/(?:stream|plus|watch|player|cast|casting)/stream-${Regex.escape(streamId)}\.php(?:\?[^\s\"'<>]*)?)"""
                    )
                )

                for (pattern in urlPatterns) {
                    pattern.findAll(html).forEach { match ->
                        val raw = match.groupValues[1]
                        val absolute = resolveUrl(entryUrl, raw) ?: return@forEach
                        val key = playerKeyFromUrl(absolute) ?: return@forEach
                        discovered.putIfAbsent(key, absolute)
                    }
                }

                // 2) href/src/data-* 속성에 들어간 player URL도 별도 수집.
                Regex(
                    """(?i)(?:href|src|data-src|data-url|data-player-url)\s*=\s*[\"']([^\"']+)[\"']"""
                ).findAll(html).forEach { match ->
                    val raw = match.groupValues[1]
                    val absolute = resolveUrl(entryUrl, raw) ?: return@forEach
                    if (!absolute.contains("stream-$streamId.php", ignoreCase = true)) {
                        return@forEach
                    }
                    val key = playerKeyFromUrl(absolute) ?: return@forEach
                    discovered.putIfAbsent(key, absolute)
                }

                // 3) 버튼/JS가 folder 이름만 갖고 있는 경우를 잡는다.
                val keyPatterns = listOf(
                    Regex(
                        """(?i)(?:data-player|data-folder|data-type|data-server)\s*=\s*[\"'](stream|plus|watch|player|cast|casting)[\"']"""
                    ),
                    Regex(
                        """(?i)(?:switchPlayer|changePlayer|loadPlayer|setPlayer|selectPlayer)\s*\([^)]*[\"'](stream|plus|watch|player|cast|casting)[\"']"""
                    ),
                    Regex(
                        """(?i)[\"'](stream|plus|watch|player|cast|casting)[\"']\s*[,)]"""
                    )
                )

                for (pattern in keyPatterns) {
                    pattern.findAll(html).forEach { match ->
                        val key = match.groupValues[1].lowercase()
                        if (key in players) {
                            discovered.putIfAbsent(
                                key,
                                "$entryOrigin/$key/stream-$streamId.php"
                            )
                        }
                    }
                }

            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Trace.event("PLAYER_DISCOVERY", "ERROR", "reason=${e.javaClass.simpleName}")
        }

        /*
         * watch.php UI가 JS로 player URL을 동적 생성해 raw HTML에는
         * default iframe만 보이는 경우가 있다. 이때 누락된 folder만 보완한다.
         * 선택 채널 하나에 대해서만 최대 6개라 IP 폭주 구조로 돌아가지 않는다.
         */
        for (player in players) {
            discovered.putIfAbsent(
                player,
                "$entryOrigin/$player/stream-$streamId.php"
            )
        }

        Trace.event("PLAYER_DISCOVERY", "DONE", "players=${discovered.keys.joinToString(",")} count=${discovered.size}")
        return players.mapNotNull { player ->
            discovered[player]?.let { PlayerCandidate(player, it) }
        }
    }

    private fun playerKeyFromUrl(url: String): String? {
        val path = try {
            URL(url).path
        } catch (_: Exception) {
            return null
        }

        return players.firstOrNull { player ->
            path.contains("/$player/", ignoreCase = true)
        }
    }

    private suspend fun extractVideo(
        url: String,
        sourceName: String,
        parentReferer: String?,
        sharedDembed: ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>> = ConcurrentHashMap()
    ): ExtractorLink? {
        try {
            currentCoroutineContext().ensureActive()


            val targetOrigin = originOf(url) ?: mainUrl
            Trace.event("PLAYER_PAGE", "START", "target=${Trace.target(url)}")
            if (targetOrigin == "https://dembed.st" && URL(url).path.endsWith(".php", true)) {
                Trace.event("PLAYER_PAGE", "DEMBED_ROUTE", "target=" + Trace.target(url))
                return DaddyLiveDembed.extract(url, parentReferer.orEmpty(), sourceName, sharedDembed)
            }
            val referer = parentReferer ?: "$targetOrigin/"
            val refererOrigin = originOf(referer) ?: targetOrigin

            val response = app.get(
                url,
                headers = pageHeaders(
                    parentUrl = referer,
                    parentOrigin = refererOrigin
                ),
                timeout = 10
            )


            Trace.event("PLAYER_PAGE", if (response.isSuccessful) "HTTP_OK" else "HTTP_FAIL",
                "target=${Trace.target(url)} http=${response.code}")
            if (!response.isSuccessful) {
                return null
            }

            val link = parseAndExtract(
                pageUrl = url,
                pageHtml = response.text,
                sourceName = sourceName,
                depth = 0,
                visited = linkedSetOf(),
                sharedDembed = sharedDembed
            )

            Trace.event("PLAYER_PAGE", if (link != null) "LINK_FOUND" else "NO_LINK",
                "target=${Trace.target(url)}")
            return link
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Trace.event("PLAYER_PAGE", "TIMEOUT", "target=${Trace.target(url)} reason=${e.javaClass.simpleName}")
            return null
        } catch (e: IOException) {
            Trace.event("PLAYER_PAGE", "IO_ERROR", "target=${Trace.target(url)} reason=${e.javaClass.simpleName}")
            return null
        } catch (e: Exception) {
            Trace.event("PLAYER_PAGE", "ERROR", "target=${Trace.target(url)} reason=${e.javaClass.simpleName}")
            return null
        }
    }

    private fun originOf(url: String): String? {
        return try {
            val parsed = URL(url)
            val port = if (
                parsed.port != -1 &&
                parsed.port != parsed.defaultPort
            ) {
                ":${parsed.port}"
            } else {
                ""
            }
            "${parsed.protocol}://${parsed.host}$port"
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveUrl(
        baseUrl: String,
        rawUrl: String
    ): String? {
        val cleaned = rawUrl
            .replace("&amp;", "&")
            .replace("\\/", "/")
            .trim()
            .trim('"', '\'')

        if (cleaned.isEmpty()) return null
        if (cleaned.startsWith("javascript:", ignoreCase = true)) return null
        if (cleaned.startsWith("data:", ignoreCase = true)) return null
        if (cleaned.startsWith("about:", ignoreCase = true)) return null

        return try {
            when {
                cleaned.startsWith("http://", ignoreCase = true) ||
                    cleaned.startsWith("https://", ignoreCase = true) -> cleaned

                cleaned.startsWith("//") ->
                    "${URL(baseUrl).protocol}:$cleaned"

                else -> URL(URL(baseUrl), cleaned).toString()
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun pageHeaders(
        parentUrl: String,
        parentOrigin: String
    ): Map<String, String> {
        return mapOf(
            "user-agent" to userAgent,
            "referer" to parentUrl,
            "origin" to parentOrigin,
            "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8"
        )
    }

    private fun hlsHeaders(
        origin: String,
        referer: String = "$origin/"
    ): Map<String, String> {
        return mapOf(
            "referer" to referer,
            "origin" to origin,
            "user-agent" to userAgent,
            "accept" to "*/*"
        )
    }

    private suspend fun validateHls(
        url: String,
        headers: Map<String, String>
    ): Boolean {
        currentCoroutineContext().ensureActive()

        return try {
            val response = app.get(
                url,
                headers = headers,
                timeout = 5
            )

            if (!response.isSuccessful) {
                Trace.event("HLS_VALIDATE", "HTTP_FAIL", "target=${Trace.target(url)} http=${response.code}")
                return false
            }
            val body = response.text.trimStart()
            val valid = body.startsWith("#EXTM3U") && probeDirectSegments(url, body, headers)
            Trace.event("HLS_VALIDATE", if (valid) "VALID" else "INVALID",
                "target=${Trace.target(url)} http=${response.code} chars=${body.length} reason=${if (valid) "extm3u" else "missing_extm3u"}")
            valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Trace.event("HLS_VALIDATE", "ERROR", "target=${Trace.target(url)} reason=${e.javaClass.simpleName}")
            false
        }
    }

    private suspend fun buildValidatedLink(
        url: String,
        sourceName: String,
        displayName: String = sourceName,
        referer: String,
        origin: String,
        headers: Map<String, String> = hlsHeaders(origin, referer)
    ): ExtractorLink? {
        if (!validateHls(url, headers)) {
            Trace.event("MEDIA3_LINK", "REJECTED", "target=${Trace.target(url)} reason=playlist_validation_failed")
            return null
        }
        Trace.event("MEDIA3_LINK", "BUILD", "mode=direct target=${Trace.target(url)} headerNames=${headers.keys.joinToString(",")}")
        return newExtractorLink(
            source = sourceName,
            name = displayName,
            url = url,
            type = ExtractorLinkType.M3U8
        ) {
            this.referer = referer
            this.quality = Qualities.Unknown.value
            this.headers = headers
        }
    }

    /*
     * stream.js v0.0.26 방식.
     * CloudStream은 P2P service worker를 사용하지 않으므로
     * stream_url_nop2p를 최우선으로 사용한다.
     */
    /** Direct links are returned only after their media URI is readable. */
    private suspend fun probeDirectSegments(url: String, initial: String, headers: Map<String, String>): Boolean {
        var playlist = initial; var base = url
        repeat(2) {
            val entries = playlist.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.toList()
            if (entries.isEmpty()) return false
            var variant: String? = null
            for (item in entries.takeLast(2).asReversed()) {
                currentCoroutineContext().ensureActive()
                val target = resolveUrl(base, item) ?: continue
                if (URL(target).path.endsWith(".m3u8", true)) { variant = target; continue }
                val response = try { app.get(target, headers = headers, timeout = 4) }
                    catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val buf = ByteArray(576)
                val n = try { response?.takeIf { it.isSuccessful }?.body?.byteStream()?.use { readMediaPrefix(it, buf) } ?: 0 } catch (_: Exception) { 0 }
                val valid = validMediaPrefix(buf, n, playlist)
                Trace.event("DIRECT_SEGMENT", if (valid) "OK" else "FAILED",
                    "target=" + Trace.target(target) + " http=" + (response?.code ?: -1) + " bytes=" + n)
                if (valid) return true
            }
            if (variant == null) return false
            val next = try { app.get(variant, headers = headers, timeout = 5) }
                catch (e: CancellationException) { throw e } catch (_: Exception) { null }
            if (next?.isSuccessful != true || !next.text.trimStart().startsWith("#EXTM3U")) return false
            playlist = next.text; base = variant
        }
        return false
    }

    private suspend fun parseAndExtract(
        pageUrl: String,
        pageHtml: String,
        sourceName: String,
        depth: Int,
        visited: MutableSet<String>,
        sharedDembed: ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>>
    ): ExtractorLink? {
        if (depth > 4) return null
        if (!visited.add(pageUrl)) return null

        currentCoroutineContext().ensureActive()

        val pageOrigin = originOf(pageUrl) ?: return null


        // Some player APIs return JSON strings with escaped slashes instead of raw HTML.
        val payload = pageHtml.replace("\\/", "/")
            .replace("\\u002F", "/", true)
            .replace("&amp;", "&")
        // 2) HTML/JS에 signed m3u8 URL이 직접 노출된 경우.
        val directM3u8Candidates = Regex(
            """(?i)https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
        ).findAll(payload)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        for (candidate in directM3u8Candidates) {
            currentCoroutineContext().ensureActive()

            val headers = hlsHeaders(
                origin = pageOrigin,
                referer = "$pageOrigin/"
            )

            val link = buildValidatedLink(
                url = candidate,
                sourceName = sourceName,
                referer = "$pageOrigin/",
                origin = pageOrigin,
                headers = headers
            )
            if (link != null) return link
        }

        val nested = collectNestedCandidates(pageUrl, payload)
        for (candidate in nested) {
            currentCoroutineContext().ensureActive()
            crawlChild(candidate, pageUrl, sourceName, depth, visited, sharedDembed)?.let { return it }
        }
        return null
    }
    private fun collectNestedCandidates(
        pageUrl: String,
        pageHtml: String
    ): List<String> {
        val result = linkedSetOf<String>()
        fun add(raw: String, explicitIframe: Boolean = false) {
            val resolved = resolveUrl(pageUrl, raw) ?: return
            if (resolved == pageUrl) return

            val lower = resolved.lowercase()
            val url = runCatching { URL(resolved) }.getOrNull() ?: return
            val path = url.path.lowercase()
            val extension = path.substringAfterLast('.', "")
            if (extension in setOf("js", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "json", "map", "woff", "woff2", "ttf") ||
                lower.contains("doubleclick") || lower.contains("googlesyndication")) {
                return
            }
            if (url.host.equals("dembed.st", true) &&
                !dembedPlayerPath.matches(path)) {
                return
            }

            val likelyPlayer =
                explicitIframe ||
                lower.contains("tiestep.") ||
                    lower.contains("/e/") ||
                    lower.contains("/embed/") ||
                    lower.contains("/player/") ||
                    lower.contains("/stream/") ||
                    lower.contains("/court/") ||
                    lower.contains("freeshot") ||
                    lower.contains("popcdn") ||
                    lower.contains("ksohls") ||
                    lower.contains("live=") ||
                    lower.contains("zmid=") ||
                    (runCatching { URL(resolved).path.endsWith(".php", true) }.getOrDefault(false)) ||
                    (url.host.equals("dembed.st", true) && path.startsWith("/premiumtv/") && path.endsWith(".php"))

            if (likelyPlayer) result.add(resolved)
        }

        // JSON APIs may nest an iframe URL several objects deep.
        val trimmed = pageHtml.trimStart()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            val root = runCatching { JSONTokener(trimmed).nextValue() }.getOrNull()
            var visitedNodes = 0
            fun walk(value: Any?, depth: Int) {
                if (depth > 4 || visitedNodes++ > 64) return
                when (value) {
                    is JSONObject -> {
                        val keys = value.keys()
                        while (keys.hasNext() && visitedNodes <= 64) {
                            val key = keys.next()
                            walk(value.opt(key), depth + 1)
                        }
                    }
                    is JSONArray -> {
                        for (i in 0 until minOf(value.length(), 32)) walk(value.opt(i), depth + 1)
                    }
                    is String -> {
                        val candidate = value.replace("\\/", "/").replace("\\u002F", "/", true)
                        if (candidate.startsWith("https://") || candidate.startsWith("http://") ||
                            candidate.startsWith("//") || candidate.startsWith("/")) add(candidate)
                    }
                }
            }
            walk(root, 0)
        }

        // 실제 iframe src.
        Regex(
            """(?is)<iframe\b[^>]*?\bsrc\s*=\s*(?:["']([^"']+)["']|([^\s>]+))"""
        ).findAll(pageHtml).forEach { match ->
            add(match.groupValues[1].ifEmpty { match.groupValues[2] }, explicitIframe = true)
        }

        // data-src / data-url / player URL 속성.
        Regex(
            """(?i)(?:data-src|data-url|data-player-url|data-embed|data-href)\s*=\s*["']([^"']+)["']"""
        ).findAll(pageHtml).forEach { match ->
            add(match.groupValues[1])
        }

        // JS iframe.src / src: / url: / embed: 형태.
        Regex(
            """(?i)(?:\.src|\bsrc|\burl|\bembed|\bplayer)\s*[:=]\s*["']([^"']+)["']"""
        ).findAll(pageHtml).forEach { match ->
            add(match.groupValues[1])
        }

        // HTML/JS 문자열에 직접 박힌 절대 URL.
        Regex(
            """(?i)(https?:)?//[^\s"'<>\\]+"""
        ).findAll(pageHtml).forEach { match ->
            add(match.value.replace("\\/", "/"))
        }

        val selected = result
            .sortedByDescending { candidate ->
                val lower = candidate.lowercase()
                when {
                    runCatching { URL(candidate).host.equals("dembed.st", true) && URL(candidate).path.endsWith(".php", true) }.getOrDefault(false) -> 1100
                    lower.contains("tiestep.") -> 1000
                    lower.contains("/e/") -> 900
                    lower.contains("/embed/") -> 800
                    lower.contains("freeshot") || lower.contains("popcdn") -> 700
                    lower.contains("/player/") -> 600
                    lower.contains("/stream/") -> 500
                    lower.contains("/court/") -> 400
                    else -> 100
                }
            }
            .take(12)
        return selected
    }

    /*
     * 중요: v4는 nested iframe 하나에서 DNS/timeout이 나면
     * parseAndExtract 전체 catch로 빠져 나머지 iframe을 건너뛸 수 있었다.
     * v5는 child 하나 단위로 실패를 격리한다.
     */
    private suspend fun crawlChild(
        childUrl: String,
        parentUrl: String,
        sourceName: String,
        depth: Int,
        visited: MutableSet<String>,
        sharedDembed: ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>>
    ): ExtractorLink? {
        if (childUrl in visited) {
            Trace.event("PLAYER_NESTED", "SKIP", "reason=already_visited target=${Trace.target(childUrl)}")
            return null
        }
        Trace.event("PLAYER_NESTED", "TRY", "target=${Trace.target(childUrl)} depth=$depth")
        val parentOrigin = originOf(parentUrl) ?: return null
        if (originOf(childUrl) == "https://dembed.st" && URL(childUrl).path.endsWith(".php", true)) {
            Trace.event("PLAYER_NESTED", "DEMBED_ROUTE", "target=" + Trace.target(childUrl))
            return DaddyLiveDembed.extract(childUrl, parentUrl, sourceName, sharedDembed)
        }

        return try {
            currentCoroutineContext().ensureActive()


            val response = app.get(
                childUrl,
                headers = pageHeaders(
                    parentUrl = parentUrl,
                    parentOrigin = parentOrigin
                ),
                timeout = 5
            )

            if (!response.isSuccessful) {
                Trace.event("PLAYER_NESTED", "HTTP_FAIL", "target=${Trace.target(childUrl)} http=${response.code}")
                return null
            }
            Trace.event("PLAYER_NESTED", "HTTP_OK", "target=${Trace.target(childUrl)} http=${response.code}")
            parseAndExtract(
                pageUrl = childUrl,
                pageHtml = response.text,
                sourceName = sourceName,
                depth = depth + 1,
                visited = visited,
                sharedDembed = sharedDembed
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Trace.event("PLAYER_NESTED", "ERROR", "target=${Trace.target(childUrl)} reason=${e.javaClass.simpleName}")
            null
        }
    }

    private object DaddyLiveDembed {
        private const val EMBED_ORIGIN = "https://dembed.st"

        // Document-start injection executes inside dembed.st's iframe, before daddy.min.js.
        // Copy only token/manifest/ticket responses; never consume or replace the originals.
        private val responseProbe = """
            (function() {
              if (window.__daddyResponseProbe) return;
              window.__daddyResponseProbe = true;
              var bridge = window.DaddyResponseTrace;
              function emit(x) { try { if (bridge) { x.origin = location.origin; bridge.postMessage(JSON.stringify(x)); } } catch (_) {} }
              // Observe resource completion and long main-thread tasks during auth.
              // No request or player behavior is modified by this observer.
              var longTasks=[], longTaskAvailable=false;
              try {
                if(window.PerformanceObserver &&
                    (PerformanceObserver.supportedEntryTypes||[]).indexOf("longtask")>=0){
                  new PerformanceObserver(function(list){
                    list.getEntries().forEach(function(e){
                      longTasks.push({at:Math.round(e.startTime),ms:Math.round(e.duration)});
                      if(longTasks.length>10)longTasks.shift();
                    });
                  }).observe({entryTypes:["longtask"]});
                  longTaskAvailable=true;
                }
              }catch(_){}
              function perfSnapshot(phase){
                try{
                  var all=performance.getEntriesByType("resource");
                  var items=all.map(function(e){
                    var u;try{u=new URL(e.name,location.href)}catch(_){return null}
                    return {host:u.hostname.slice(0,65),path:u.pathname.slice(-65),
                      type:e.initiatorType||"",at:Math.round(e.startTime),
                      end:Math.round(e.responseEnd),ms:Math.round(e.duration),
                      blocking:e.renderBlockingStatus||""};
                  }).filter(Boolean).sort(function(a,b){return b.end-a.end}).slice(0,12);
                  emit({stage:"perf_snapshot",phase:phase,age:Math.round(performance.now()),
                    count:all.length,longTaskAvailable:longTaskAvailable,
                    resources:items,tasks:longTasks.slice()});
                }catch(_){}
              }
              function pathOf(raw) { try { return new URL(raw, location.href).pathname; } catch (_) { return ""; } }
              function absoluteOf(raw) { try { return new URL(raw,location.href).href; } catch (_) { return String(raw); } }
              function kindOf(raw, method) {
                var p = pathOf(raw);
                if (p === "/api/playlist-token") return "token";
                if (/\.m3u8$/i.test(p) && p.indexOf("/premium") !== -1) return "manifest";
                if (p.indexOf("/premiumtv/") === 0 && String(method).toUpperCase() === "POST") return "ticket";
                return "";
              }
              function observed(kind, method, raw, status, type, response, elapsed, headers) {
                var max = kind === "manifest" ? 262144 : 32768;
                var x = {stage:"response", kind:kind, method:String(method), url:String(raw),
                  http:status, responseType:String(type || ""), elapsedMs:Math.round(elapsed),
                  headers:String(headers || ""), length:response ? response.length : 0,
                  truncated:!!(response && response.length > max),
                  body:typeof response === "string" ? response.slice(0, max) : ""};
                emit(x);
              }
              function observedError(kind, method, raw, stage, err) {
                emit({stage:stage, kind:kind, method:String(method), url:String(raw),
                  error:String(err && err.name || "unavailable")});
              }
              function headerNames(h) {
                try { var keys=[]; h.forEach(function(v,k){keys.push(k)}); return keys.join(","); }
                catch (_) { return ""; }
              }

              // Record whether the real player starts media loading at all.
              var mediaHost="", nativeStarts=0;
              function hostOf(raw) { try {return new URL(raw,location.href).host;}catch(_){return "";} }
              function nativeMedia(raw,method,kind,binary) {
                var p=pathOf(raw);
                return !kind && String(method).toUpperCase()==="GET" &&
                  (!!binary || /^\/premium[^/]*\//.test(p) ||
                    (!!mediaHost && hostOf(raw)===mediaHost && !/^\/api\//.test(p) && !/\.m3u8$/i.test(p)));
              }
              if (typeof window.fetch === "function") {
                var nativeFetch = window.fetch;
                window.fetch = function(input, init) {
                  var url = typeof input === "string" ? input : input && input.url || "";
                  var method = init && init.method || input && input.method || "GET";
                  var kind = kindOf(url, method);
                  if (kind==="manifest") mediaHost=hostOf(url);
                  if (nativeMedia(url,method,kind)) {
                    nativeStarts++;
                    if (nativeStarts<=12) emit({stage:"native_start",transport:"fetch",method:method,url:absoluteOf(url)});
                  }
                  if (kind) emit({stage:"auth_start",kind:kind});
                  var start = performance.now();
                  var promise = nativeFetch.apply(this, arguments);
                   if (nativeMedia(url,method,kind)) {
                     var opts=init||{}, req=input && input.headers ? input : null;
                     var names="";try{names=headerNames(new Headers(opts.headers||req && req.headers));}catch(_){}
                     var credentials=opts.credentials||req && req.credentials||"default";
                     Promise.resolve(promise).then(function(r) {
                       
                       emit({stage:'native_segment',transport:'fetch',method:method,http:r.status,
                         url:r.url||absoluteOf(url),requestUrl:absoluteOf(url),requestHeaders:names,credentials:credentials,
                         contentType:r.headers.get("content-type")||"",contentLength:r.headers.get("content-length")||"",
                         responseType:r.type||"",elapsedMs:Math.round(performance.now()-start)});
                     },function(e){
                       emit({stage:'native_segment',transport:'fetch',method:method,http:0,
                         url:absoluteOf(url),requestUrl:absoluteOf(url),requestHeaders:names,credentials:credentials,
                         error:String(e && e.name||"network_error"),elapsedMs:Math.round(performance.now()-start)});});
                   }
                  // A P2P HTTP loader can use fetchSetup() to load binary media
                  // from a different host; inspect headers without copying response bytes.
                  if (!kind && !nativeMedia(url,method,kind) && String(method).toUpperCase()==="GET") {
                    Promise.resolve(promise).then(function(r) {
                      var mime="";try{mime=(r.headers.get("content-type")||"").toLowerCase();}catch(_){}
                      if (!/^(image\/png|video\/|audio\/|application\/octet-stream|application\/mp2t)/.test(mime)) return;
                      nativeStarts++; 
                      var actual=r.url||absoluteOf(url), names="";
                      try{names=headerNames(new Headers(init&&init.headers||input&&input.headers));}catch(_){}
                      if(nativeStarts<=12)
                        emit({stage:"native_start",transport:"fetch_binary_candidate",method:method,url:absoluteOf(url),headers:names});
                      emit({stage:"native_segment",transport:"fetch_binary_candidate",method:method,
                        requestUrl:absoluteOf(url),url:actual,http:r.status,requestHeaders:names,
                        contentType:mime,contentLength:r.headers.get("content-length")||"",
                        responseType:r.type||"",elapsedMs:Math.round(performance.now()-start)});
                    },function(_) {});
                  }
                  if (kind) Promise.resolve(promise).then(function(r) {
                    var ms = performance.now()-start;
                    try {
                      var copy = r.clone();
                      copy.text().then(function(body) {
                        observed(kind,method,r.url || url,r.status,r.type,body,ms,headerNames(r.headers));
                      },function(e){ observedError(kind,method,url,"read_error",e); });
                    } catch(e){ observedError(kind,method,url,"clone_error",e); }
                  },function(e){ observedError(kind,method,url,"network_error",e); });
                  return promise;
                };
              } else emit({stage:"install_error",error:"fetch_missing"});
              if (window.XMLHttpRequest && XMLHttpRequest.prototype) {
                var open = XMLHttpRequest.prototype.open;
                var send = XMLHttpRequest.prototype.send;
                var setHeader = XMLHttpRequest.prototype.setRequestHeader;
                XMLHttpRequest.prototype.setRequestHeader = function(name,value) {
                  if(this.__daddyProbe) this.__daddyProbe.headers.push(String(name).toLowerCase());
                  return setHeader.apply(this,arguments);
                };
                XMLHttpRequest.prototype.open = function(method,url) {
                  var kind=kindOf(url,method);
                  if(kind==="manifest") mediaHost=hostOf(url);
                  this.__daddyProbe = {kind:kind,url:String(url),method:String(method),headers:[]};
                  return open.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  var self=this, entry=this.__daddyProbe;
                   if (entry && !self.__daddySynthetic &&
                        nativeMedia(entry.url,entry.method,entry.kind,
                          self.responseType==="arraybuffer" || self.responseType==="blob")) {
                     nativeStarts++;
                     if(nativeStarts<=12) emit({stage:"native_start",transport:"xhr",method:entry.method,url:absoluteOf(entry.url),headers:entry.headers.join(",")});
                     var begun=performance.now();
                     this.addEventListener('loadend',function(){
                       var mime="",len="",bytes=0;
                       try{mime=self.getResponseHeader("content-type")||"";len=self.getResponseHeader("content-length")||"";}catch(_){}
                       try{bytes=self.response && self.response.byteLength||0;}catch(_){}
                       
                       emit({stage:'native_segment',transport:'xhr',method:entry.method,http:self.status,
                         requestUrl:absoluteOf(entry.url),url:self.responseURL||absoluteOf(entry.url),requestHeaders:entry.headers.join(","),
                         credentials:self.withCredentials?'include':'default',contentType:mime,contentLength:len,
                         responseType:self.responseType||"",bytes:bytes,elapsedMs:Math.round(performance.now()-begun)});
                     });
                   }
                  if (entry && entry.kind) {
                    emit({stage:"auth_start",kind:entry.kind});
                    var start=performance.now();
                    this.addEventListener("loadend",function() {
                      var body="",readError="";
                      try {
                        if (self.responseType === "" || self.responseType === "text") body=self.responseText;
                        else if (self.responseType === "arraybuffer" && self.response) {
                          var bytes=new Uint8Array(self.response);
                          if (bytes.byteLength <= 262144) body=new TextDecoder().decode(bytes);
                          else readError="arraybuffer_too_large";
                        } else readError="unsupported_response_type_"+self.responseType;
                      } catch(e){ readError=String(e && e.name || "read_failed"); }
                      if (readError) observedError(entry.kind,entry.method,entry.url,"read_error",readError);
                      var h="";try{h=self.getAllResponseHeaders().split("\n").map(function(v){return v.split(":")[0]}).join(",");}catch(_){}
                      observed(entry.kind,entry.method,self.responseURL || entry.url,
                        self.status,self.responseType,body,performance.now()-start,h);
                    });
                  }
                  return send.apply(this,arguments);
                };
              } else emit({stage:"install_error",error:"xhr_missing"});
              window.addEventListener("error",function(e) {
                emit({stage:"js_error",error:String(e.message || "script_error").slice(0,180),
                    source:String(e.filename || ""),line:e.lineno || 0});
              });
              window.addEventListener("unhandledrejection",function(e) {
                var r=e.reason;
                emit({stage:"promise_error",error:String(r && (r.message || r.name) || "rejection").slice(0,180)});
              });
              // Proxy requests run in the original authenticated iframe, not a new HTTP session.
              window.addEventListener("message", function(ev) {
                if (ev.source !== window.parent || ev.origin !== "https://thedaddy.li") return;
                var cmd = ev.data;
                if (!cmd || cmd.daddyProxy !== 1 ||
                    !/^[a-f0-9]{32}$/.test(String(cmd.id)) ||
                    (cmd.kind !== "manifest" && cmd.kind !== "segment")) return;
                (async function() {
                  var target = new URL(cmd.url);
                  if (target.protocol !== "https:" || !/^\/premium/.test(target.pathname)) throw Error("invalid_resource_url");
                  if (cmd.kind === "manifest") {
                    // Refresh in the dembed iframe rather than the unrelated parent page.
                    var renew = typeof fetchPlaylistToken === "function" ? fetchPlaylistToken :
                      (typeof window.fetchPlaylistToken === "function" ? window.fetchPlaylistToken : null);
                    if (renew) {
                      try {
                        var next = await renew();
                        var valid = typeof next === "string" && !!next;
                        if (valid) {
                          target.searchParams.set("t", next);
                          target.searchParams.set("_", String(Date.now()));
                        }
                        emit({stage:"proxy_refresh_info", available:true, token:valid});
                      } catch (e) { emit({stage:"proxy_refresh_error", error:String(e && e.name || "refresh_failed")}); }
                    } else emit({stage:"proxy_refresh_info", available:false, token:false});
                  }
                  if (cmd.kind === "segment") {
                    // Match LiveLoader's binary XHR path; fetch() may be handled differently.
                    var xhr = new XMLHttpRequest();
                     xhr.__daddySynthetic = true;
                    xhr.open("GET", target.toString(), true);
                    xhr.responseType = "arraybuffer";
                    xhr.timeout = 5000;
                    var started = performance.now();
                    xhr.onload = function() {
                      if (xhr.status < 200 || xhr.status >= 300) {
                        emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:xhr.status,
                          url:target.toString(),transport:"xhr",method:"GET",contentType:xhr.getResponseHeader("content-type")||"",
                          elapsedMs:Math.round(performance.now()-started),error:"xhr_http_"+xhr.status});
                        return;
                      }
                      try {
                        var buffer = xhr.response;
                        if (!buffer || !buffer.byteLength || buffer.byteLength > 4*1024*1024)
                          throw Error("invalid_segment_length");
                        var bytes = new Uint8Array(buffer), pieces = [], step = 8192;
                        for (var i=0; i<bytes.length; i+=step)
                          pieces.push(String.fromCharCode.apply(null, bytes.subarray(i,i+step)));
                        emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:xhr.status,
                          url:target.toString(),transport:"xhr",method:"GET",contentType:xhr.getResponseHeader("content-type")||"",
                          elapsedMs:Math.round(performance.now()-started),data:btoa(pieces.join(""))});
                      } catch (e) {
                        emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:0,
                          error:String(e && e.message || "segment_decode_failed")});
                      }
                    };
                    xhr.onerror = function() {
                      emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:xhr.status || 0,
                        url:target.toString(),transport:"xhr",method:"GET",elapsedMs:Math.round(performance.now()-started),error:"xhr_network_or_cors"});
                    };
                    xhr.ontimeout = function() {
                      emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:0,
                        url:target.toString(),transport:"xhr",method:"GET",elapsedMs:Math.round(performance.now()-started),error:"xhr_timeout"});
                    };
                    xhr.send();
                    return;
                  }
                  var response = await fetch(target.toString(), {cache:"no-store", credentials:"omit"});
                  if (!response.ok) {
                    emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:response.status,
                      url:target.toString(),error:"upstream_http_"+response.status});
                    return;
                  }
                  var text = await response.text();
                  if (text.length > 262144) throw Error("manifest_too_large");
                  emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:response.status,
                    url:target.toString(),body:text});
                })().catch(function(e) {
                  emit({stage:"proxy_resource",id:cmd.id,kind:cmd.kind,http:0,
                    error:String(e && e.name || e || "browser_request_failed")});
                });
              });
              emit({stage:"installed",kind:"fetch_xhr",origin:location.origin});
              setTimeout(function(){perfSnapshot("probe_installed")},0);
            })();
        """.trimIndent()
        private val responseProbeBytes by lazy { (responseProbe + "\n;\n").toByteArray(Charsets.UTF_8) }
        @Volatile private var observedPlayerJsUrl: String? = null
        /** Intercept only the embedded player's JavaScript, not its auth or media API.
         * Prepend the response probe before daddy.min.js runs while retaining the script body. */
        private data class PrefetchedPlayerScript(
            val url: String, val cookie: String?, val agent: String,
            val referer: String, val body: ByteArray, val savedAt: Long
        )

        private fun scriptCookie(url: String): String? =
            runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()

        // Only static JS is prefetched. Never replay the browser's ticket, token or manifest APIs.
        private fun fetchPlayerScript(
            url: String, requested: Map<String, String>, session: Session, prefetch: Boolean
        ): ByteArray? {
            val started = android.os.SystemClock.elapsedRealtime()
            if (prefetch) Trace.event("SCRIPT_PREFETCH", "START",
                "sid=${session.id.take(8)} ${Trace.urlShape(url)} agentId=${Trace.valueId(session.userAgent)}")
            var connection: HttpURLConnection? = null
            return try {
                val active = URL(url).openConnection() as HttpURLConnection
                connection = active
                active.connectTimeout = 4500
                active.readTimeout = 4500
                active.setRequestProperty("Accept-Encoding", "gzip")
                requested.forEach { (key, value) ->
                    if (!key.equals("Host", true) && !key.equals("Accept-Encoding", true) &&
                        !key.equals("Content-Length", true) && value.isNotBlank())
                        active.setRequestProperty(key, value)
                }
                if (requested.keys.none { it.equals("Referer", true) })
                    active.setRequestProperty("Referer", session.embedded)
                if (requested.keys.none { it.equals("User-Agent", true) })
                    active.setRequestProperty("User-Agent", session.userAgent)
                scriptCookie(url)?.let { active.setRequestProperty("Cookie", it) }
                val code = active.responseCode
                val headersAt = android.os.SystemClock.elapsedRealtime()
                if (!prefetch) session.milestone("script_headers",
                    "http=$code encoding=${active.contentEncoding.orEmpty().take(16)}")
                if (code != 200 || !active.url.host.equals("dembed.st", true)) {
                    Trace.event(if (prefetch) "SCRIPT_PREFETCH" else "RESPONSE_BRIDGE", "HTTP_FAIL",
                        "sid=${session.id.take(8)} http=$code fallback=webview_native")
                    return null
                }
                val input = if (active.contentEncoding.equals("gzip", true))
                    GZIPInputStream(active.inputStream) else active.inputStream
                val out = ByteArrayOutputStream()
                input.use { stream ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = stream.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 2 * 1024 * 1024) {
                            Trace.event(if (prefetch) "SCRIPT_PREFETCH" else "RESPONSE_BRIDGE",
                                "TOO_LARGE", "sid=${session.id.take(8)} fallback=webview_native")
                            return null
                        }
                        out.write(buffer, 0, n)
                    }
                }
                val bytes = out.toByteArray().takeIf { it.isNotEmpty() } ?: return null
                val finished = android.os.SystemClock.elapsedRealtime()
                if (!prefetch) session.milestone("script_body",
                    "readMs=${finished-headersAt} bytes=${bytes.size}")
                Trace.event(if (prefetch) "SCRIPT_PREFETCH" else "RESPONSE_BRIDGE", "FETCH_OK",
                    "sid=${session.id.take(8)} bytes=${bytes.size} elapsedMs=${finished-started} " +
                        "mime=${active.contentType?.substringBefore(';').orEmpty()} " +
                        "redirected=${active.url.toString() != url} final=${Trace.urlShape(active.url.toString())}")
                bytes
            } catch (e: Exception) {
                Trace.event(if (prefetch) "SCRIPT_PREFETCH" else "RESPONSE_BRIDGE", "SCRIPT_ERROR",
                    "sid=${session.id.take(8)} reason=${e.javaClass.simpleName} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} fallback=webview_native")
                null
            } finally {
                connection?.disconnect()
            }
        }

        /** Reuse an early response only with matching URL, UA, Referer and Cookie. */
        private fun instrumentPlayerScript(request: WebResourceRequest, session: Session): WebResourceResponse? {
            val address = request.url.toString()
            val started = android.os.SystemClock.elapsedRealtime()
            observedPlayerJsUrl = address
            session.milestone("script_request")
            val cached = session.prefetchedPlayerScript
            val age = cached?.let { started - it.savedAt } ?: -1L
            val browserRef = request.requestHeaders.entries.firstOrNull {
                it.key.equals("Referer", true)
            }?.value
            val browserAgent = request.requestHeaders.entries.firstOrNull {
                it.key.equals("User-Agent", true)
            }?.value
            val result = when {
                cached == null -> "not_ready"
                cached.url != address -> "url_mismatch"
                age < 0L -> "clock_mismatch"
                cached.cookie != scriptCookie(address) -> "cookie_changed"
                browserRef != null && browserRef != cached.referer -> "referer_changed"
                browserAgent != null && browserAgent != cached.agent -> "agent_changed"
                else -> "hit"
            }
            session.scriptReuseResult = result
            Trace.event("SCRIPT_PREFETCH", if (result == "hit") "HIT" else "MISS",
                "sid=${session.id.take(8)} reason=$result ageMs=$age")
            Trace.event("SCRIPT_PREFETCH", "COMPARE",
                "sid=${session.id.take(8)} saved=${Trace.urlShape(cached?.url)} " +
                    "requested=${Trace.urlShape(address)} " +
                    "sameHost=${cached?.let { runCatching { URL(it.url).host == URL(address).host }.getOrDefault(false) }} " +
                    "samePath=${cached?.let { runCatching { URL(it.url).path == URL(address).path }.getOrDefault(false) }} " +
                    "sameQuery=${cached?.let { runCatching { URL(it.url).query == URL(address).query }.getOrDefault(false) }} " +
                    "sameCookie=${cached?.let { it.cookie == scriptCookie(address) }} " +
                    "sameAgent=${cached?.let { browserAgent == null || browserAgent == it.agent }} " +
                    "sameReferer=${cached?.let { browserRef == null || browserRef == it.referer }} " +
                    "headers=${request.requestHeaders.keys.sorted().joinToString(",").take(150)}")
            Trace.event("RESPONSE_BRIDGE", "SCRIPT_FETCH",
                "sid=${session.id.take(8)} target=${Trace.target(address)} " +
                    "mode=${if (result == "hit") "prefetched_js_with_probe" else "original_js_with_probe"}")
            return try {
                val script = if (result == "hit") {
                    session.milestone("script_headers", "http=200 prefetch=hit")
                    session.milestone("script_body", "bytes=${cached!!.body.size} prefetch=hit")
                    cached.body
                } else fetchPlayerScript(address, request.requestHeaders, session, false) ?: return null
                val probe = responseProbeBytes
                val bytes = ByteArray(probe.size + script.size)
                System.arraycopy(probe, 0, bytes, 0, probe.size)
                System.arraycopy(script, 0, bytes, probe.size, script.size)
                Trace.event("RESPONSE_BRIDGE", "SCRIPT_INJECTED",
                    "sid=${session.id.take(8)} originalBytes=${script.size} probeBytes=${probe.size} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} prefetch=${result == "hit"}")
                session.milestone("script_injected")
                WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                Trace.event("RESPONSE_BRIDGE", "SCRIPT_ERROR",
                    "sid=${session.id.take(8)} reason=${e.javaClass.simpleName} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-started} fallback=webview_native")
                null
            }
        }

        private val main = Handler(Looper.getMainLooper())
        private val sessions = ConcurrentHashMap<String, Session>()
        private val directSessions = ConcurrentHashMap<String, DirectSession>()
        private val io = CoroutineScope(Dispatchers.IO)
        private val attribute = Regex("""URI="([^"]+)"""")
        private var server: ServerSocket? = null
        @Volatile private var port = 0

        private class Resource(val url: String, val playlist: Boolean)
        private class DirectResource(
            val url: String, val playlist: Boolean, val manifest: String, val seenAt: Long
        )
        private data class DirectWindow(
            val sequence: String, val entries: Set<String>, val first: String, val last: String
        )
        private class DirectSession(val url: String, val headers: Map<String, String>) {
            val id = UUID.randomUUID().toString().replace("-", "")
            val resources = ConcurrentHashMap<String, DirectResource>()
            val windows = ConcurrentHashMap<String, DirectWindow>()
            @Volatile var lastUse = android.os.SystemClock.elapsedRealtime()
            fun reference(address: String, playlist: Boolean, manifest: String): String {
                val now = android.os.SystemClock.elapsedRealtime()
                if (resources.size > 256) {
                    resources.forEach { (key, resource) ->
                        if (now - resource.seenAt > 120000L) resources.remove(key, resource)
                    }
                }
                val key = UUID.nameUUIDFromBytes(address.toByteArray(Charsets.UTF_8))
                    .toString().replace("-", "")
                resources[key] = DirectResource(address, playlist, manifest, now)
                return "http://127.0.0.1:$port/$id/$key"
            }
        }
        private class BrowserReply(val status: Int, val address: String, val body: String?, val bytes: ByteArray?)
        private class Session(val parent: String, val embedded: String) {
            private val timingStart = android.os.SystemClock.elapsedRealtime()
            private val timingLast = AtomicLong(timingStart)
            private val timingStages = ConcurrentHashMap.newKeySet<String>()
            private val timingTimes = ConcurrentHashMap<String, Long>()
            private val reported = AtomicInteger()
            @Volatile var scriptReuseResult = "not_requested"
            @Volatile var issuedTokenId = "none"
            @Volatile var issuedTokenAt = 0L
            @Volatile var signedManifestHttp = -1
            @Volatile var unsignedManifestHttp = -1
            @Volatile var rejectedSignedManifest = ""
            fun markRejectedManifest(address: String, code: Int) {
                if (code != 404 || browserManifestBody != null || address != url ||
                    Uri.parse(address).getQueryParameter("t").isNullOrBlank()) return
                rejectedSignedManifest = address
                Trace.event("HLS_BROWSER", "SIGNED_404_TERMINAL",
                    "sid=${id.take(8)} ${Trace.urlShape(address)}")
                if (!browserManifestReady.isCompleted) browserManifestReady.complete(false)
            }
            fun reportOutcome(status: String) {
                if (!reported.compareAndSet(0, 1)) return
                fun gap(a: String, b: String): Long {
                    val start = timingTimes[a] ?: return -1
                    val end = timingTimes[b] ?: return -1
                    return end - start
                }
                Trace.event("AUTH_DIAG_SUMMARY", status,
                    "sid=${id.take(8)} elapsedMs=${android.os.SystemClock.elapsedRealtime()-timingStart} " +
                        "pageStartMs=${gap("extract_start","page_started")} " +
                        "beforeScriptMs=${gap("page_started","script_request")} " +
                        "scriptLoadMs=${gap("script_request","script_injected")} " +
                        "toTicketMs=${gap("script_injected","ticket_request")} " +
                        "ticketToTokenMs=${gap("ticket_request","token_response")} " +
                        "tokenToManifestMs=${gap("token_response","manifest_response")} " +
                        "preflightMs=${gap("preflight_start","preflight_ok")} " +
                        "prefetch=$scriptReuseResult navigations=${mainNavigations.get()} " +
                        "ticketHttp=$browserTicketHttp tokenHttp=$browserTokenHttp " +
                        "unsignedHttp=$unsignedManifestHttp signedHttp=$signedManifestHttp " +
                        "manifestError=${manifestHttpError ?: -1} authRequests=$authRequestCount " +
                        "sealed=$sealedPlaylist tailHeld=$skipInitialTail")
            }
            val mainNavigations = AtomicInteger()
            private val parentResources = AtomicInteger()
            private val embedResources = AtomicInteger()
            private val externalResources = AtomicInteger()
            private val externalScripts = AtomicInteger()
            val blockedAdResources = AtomicInteger()
            private val parentHost = Uri.parse(parent).host.orEmpty()

            fun milestone(step: String, detail: String = "") {
                if (!timingStages.add(step)) return
                val now = android.os.SystemClock.elapsedRealtime()
                val prior = timingLast.getAndSet(now)
                timingTimes[step] = now
                Trace.event("AUTH_TIMING", "MARK", "sid=${id.take(8)} step=$step " +
                    "totalMs=${now-timingStart} gapMs=${now-prior} $detail")
                if (step == "link_ready") reportOutcome("LINK_READY")
            }

            // P2 only measures: the original browser requests remain untouched.
            fun observeResource(request: WebResourceRequest) {
                val host = request.url.host.orEmpty()
                if (host.isBlank()) return
                when {
                    host.equals(parentHost, true) -> parentResources.incrementAndGet()
                    host.equals("dembed.st", true) -> embedResources.incrementAndGet()
                    else -> {
                        val count = externalResources.incrementAndGet()
                        val script = request.url.path?.endsWith(".js", true) == true
                        if (script) externalScripts.incrementAndGet()
                        if (count <= 5) Trace.event("AUTH_RESOURCE", "EXTERNAL",
                            "sid=${id.take(8)} host=${host.take(90)} script=$script " +
                            "totalMs=${android.os.SystemClock.elapsedRealtime()-timingStart}")
                    }
                }
            }

            fun resourceSnapshot(step: String) {
                Trace.event("AUTH_RESOURCE", "SUMMARY",
                    "sid=${id.take(8)} step=$step navigations=${mainNavigations.get()} " +
                    "parent=${parentResources.get()} embed=${embedResources.get()} " +
                    "external=${externalResources.get()} scripts=${externalScripts.get()} " +
                        "adsBlocked=${blockedAdResources.get()}")
            }
            val id: String = UUID.randomUUID().toString().replace("-", "")
            @Volatile var prefetchedPlayerScript: PrefetchedPlayerScript? = null
            val resources = ConcurrentHashMap<String, Resource>()
            private val pendingResources = ConcurrentHashMap<String, CompletableDeferred<BrowserReply?>>()
            val manifestMutex = Mutex()
            val failedSegments = ConcurrentHashMap<String, Long>()
            @Volatile var segmentTransport = "client"
            @Volatile var directAllowed = false
            @Volatile var encryptedMedia = false
            val nativeRequestCount = AtomicInteger()
            val nativeHeaders = ConcurrentHashMap<String, Map<String, String>>()
            val jsMediaRequests = AtomicInteger()
            @Volatile var segmentTokenMode = false
            @Volatile var tokenProbeDone = false
            val syntheticSegments = ConcurrentHashMap<String, Boolean>()
            val expectedUrls = ConcurrentHashMap<String, Boolean>()
            private val expectedPathCounts = ConcurrentHashMap<String, Int>()
            private data class ObservedMedia(val effective: String, val at: Long)
            private val observedMedia = ConcurrentHashMap<String, ObservedMedia>()
            private val observedExact = ConcurrentHashMap<String, ObservedMedia>()
            fun registerManifestSegments(playlist: String, base: String) {
                expectedUrls.clear()
                expectedPathCounts.clear()
                playlist.lineSequence().map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(50).forEach { part ->
                        runCatching { URL(URL(base), part).toString() }.getOrNull()?.let { address ->
                            if (!URL(address).path.endsWith(".m3u8", true)) {
                                expectedUrls[Trace.key(address)] = true
                                val pathId = Trace.pathKey(address)
                                expectedPathCounts[pathId] = (expectedPathCounts[pathId] ?: 0) + 1
                            }
                        }
                    }
            }
            /** Keep only original-player HTTP successes; never infer a mapping from response order. */
            fun recordObservedMedia(request: String, effective: String, status: Int, method: String) {
                if (status !in 200..299 || !method.equals("GET", true)) return
                val valid = runCatching {
                    val source = URL(request)
                    val destination = URL(effective)
                    source.protocol == "https" && destination.protocol == "https" &&
                        destination.host.isNotBlank() && destination.host != "localhost" &&
                        destination.host != "127.0.0.1" && !destination.host.endsWith(".local")
                }.getOrDefault(false)
                if (!valid) return
                if (observedMedia.size >= 32 || observedExact.size >= 64) {
                    observedMedia.clear()
                    observedExact.clear()
                }
                val entry = ObservedMedia(effective, android.os.SystemClock.elapsedRealtime())
                observedExact[Trace.key(request)] = entry
                observedMedia[Trace.pathKey(request)] = entry
            }

            private fun verifiedSegment(address: String): String? {
                val pathId = Trace.pathKey(address)
                val exactKey = Trace.key(address)
                val recorded = observedExact[exactKey]?.takeIf { expectedUrls.containsKey(exactKey) }
                    ?: observedMedia[pathId]?.takeIf { expectedPathCounts[pathId] == 1 }
                    ?: return null
                val age = android.os.SystemClock.elapsedRealtime() - recorded.at
                return recorded.effective.takeIf { age in 0..20000L }
            }
            fun hasVerifiedAlternative(address: String): Boolean =
                verifiedSegment(address)?.let { it != address } == true

            private val scriptErrorCount = AtomicInteger()

            fun errorDetail(source: String, line: Int, raw: String, kind: String) {
                if (scriptErrorCount.incrementAndGet() > 8) return
                Trace.event("PLAYER_SCRIPT_ERROR", kind,
                    "sid=${id.take(8)} source=${Trace.target(source)} line=$line message=${Trace.error(raw)}")
            }

            fun observeNativeRequest(address: String, method: String, original: Map<String, String>) {
                if (nativeRequestCount.incrementAndGet() > 12) return
                nativeHeaders[Trace.key(address)] = original.toMap()
            }

            fun mediaHeaders(address: String): Map<String, String> {
                val original = nativeHeaders[Trace.key(address)]
                val base = original ?: headers
                val result = linkedMapOf<String, String>()
                base.forEach { (name, value) ->
                    if (name.lowercase() !in setOf("host", "connection", "content-length",
                            "accept-encoding", "range") && value.isNotBlank()) result[name] = value
                }
                runCatching { CookieManager.getInstance().getCookie(address) }.getOrNull()?.let { value ->
                    result.keys.firstOrNull { it.equals("Cookie", true) }?.let { result.remove(it) }
                    result["Cookie"] = value
                }
                return result
            }
            @Volatile var browser: WebView? = null
            @Volatile var url: String = ""
            @Volatile var token: String = ""
            @Volatile var sealedPlaylist = false
            private val playKeys = ConcurrentHashMap<String, ByteArray>()
            fun mediaKey(): ByteArray? = playKeys[token]
                ?: runCatching { playKeys[Uri.parse(url).getQueryParameter("t").orEmpty()] }.getOrNull()
            @Volatile var firstPlaylist: String? = null
            @Volatile var recentRootPlaylist: Pair<String, String>? = null
            @Volatile var recentRootAt = 0L
            @Volatile var skipInitialTail = false
            @Volatile var lastUse = android.os.SystemClock.elapsedRealtime()
            @Volatile var browserReleasedAt = 0L
            @Volatile var userAgent = "Mozilla/5.0"
            @Volatile var browserHeaders: Map<String, String> = emptyMap()
            @Volatile var authHttpError: Int? = null
            @Volatile var manifestHttpError: Int? = null
            @Volatile var authRequestCount = 0
            @Volatile var probeAvailable = false
            @Volatile var probeInstalled = false
            @Volatile var browserManifestBody: String? = null
            @Volatile var browserManifestUrl: String = ""
            @Volatile var browserManifestAt = 0L
            @Volatile var browserTokenHttp = -1
            @Volatile var browserTicketHttp = -1
            val browserManifestReady = CompletableDeferred<Boolean>()
            val headers: Map<String, String>
                get() = mutableMapOf(
                    "Origin" to EMBED_ORIGIN,
                    "Referer" to embedded,
                    "User-Agent" to userAgent,
                    "Accept" to "*/*"
                ).also { map ->
                    browserHeaders.forEach { (key, value) ->
                        if (key.lowercase() !in setOf("host", "content-length", "connection",
                                "accept-encoding", "range") && value.isNotBlank()) {
                            map.keys.firstOrNull { it.equals(key, true) }?.let { map.remove(it) }
                            map[key] = value
                        }
                    }
                    if (url.isNotBlank()) {
                        runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                            ?.let {
                                map.keys.firstOrNull { key -> key.equals("Cookie", true) }?.let { key -> map.remove(key) }
                                map["Cookie"] = it
                            }
                    }
                }

            private fun responseState(kind: String, status: Int, address: String, body: String, truncated: Boolean) {
                milestone("${kind}_response", "http=$status")
                if (kind == "ticket") resourceSnapshot("ticket_response")
                val code = if (status in 200..299) "OK" else if (status == 0) "NETWORK_OR_CORS" else "HTTP_FAIL"
                val reason = if (status in 200..299) "none" else Trace.bodyReason(body)
                Trace.event("BROWSER_RESPONSE", code,
                    "sid=${id.take(8)} kind=$kind http=$status target=${Trace.target(address)} " +
                        "bodyChars=${body.length} truncated=$truncated reason=$reason")
                when (kind) {
                    "ticket" -> {
                        browserTicketHttp = status
                        Trace.event("AUTH_TICKET", code, "sid=${id.take(8)} http=$status reason=$reason")
                    }
                    "token" -> {
                        browserTokenHttp = status
                        authHttpError = status.takeIf { it !in 200..299 }
                        Trace.event("AUTH_RESPONSE", if (status in 200..299) "BROWSER_HTTP_OK" else "BROWSER_HTTP_FAIL",
                            "sid=${id.take(8)} http=$status chars=${body.length} " +
                                "reason=$reason truncated=$truncated")
                        if (status in 200..299 && !truncated) onToken(body.toByteArray(Charsets.UTF_8))
                    }
                    "manifest" -> {
                        manifestHttpError = status.takeIf { it !in 200..299 }
                        val decoded = body.trimStart().startsWith("#EXTM3U")
                        val signature = runCatching { Uri.parse(address).getQueryParameter("t") }.getOrNull()
                        val signed = !signature.isNullOrBlank()
                        if (signed) signedManifestHttp = status else unsignedManifestHttp = status
                        if (signed) markRejectedManifest(address, status)
                        Trace.event("AUTH_CHAIN", "MANIFEST_RESPONSE",
                            "sid=${id.take(8)} http=$status ${Trace.urlShape(address)} " +
                                "signed=$signed tokenId=${Trace.valueId(signature)} issuedId=$issuedTokenId " +
                                "matchesIssued=${signed && issuedTokenId != "none" && Trace.valueId(signature) == issuedTokenId} " +
                                "issuedAgeMs=${if (issuedTokenAt > 0) android.os.SystemClock.elapsedRealtime()-issuedTokenAt else -1} " +
                                "bodyChars=${body.length} truncated=$truncated")
                        Trace.event("HLS_BROWSER", code,
                            "sid=${id.take(8)} http=$status signed=$signed extm3u=$decoded " +
                                "chars=${body.length} truncated=$truncated reason=$reason")
                        if (status in 200..299 && !truncated && body.isNotEmpty()) {
                            if (browserManifestBody == null || signed ||
                                Uri.parse(browserManifestUrl).getQueryParameter("t").isNullOrBlank()) {
                                browserManifestBody = body
                                browserManifestUrl = address
                                browserManifestAt = android.os.SystemClock.elapsedRealtime()
                                if (!browserManifestReady.isCompleted) browserManifestReady.complete(true)
                            }
                        }
                    }
                }
            }

            @JavascriptInterface
            fun postMessage(raw: String) = onBrowserMessage(raw)

            fun onBrowserMessage(raw: String) {
                if (raw.length > 6 * 1024 * 1024) {
                    Trace.event("RESPONSE_BRIDGE", "REJECT", "sid=${id.take(8)} reason=message_over_limit bytes=${raw.length}")
                    return
                }
                val message = runCatching { JSONObject(raw) }.getOrNull()
                if (message == null) {
                    Trace.event("RESPONSE_BRIDGE", "ERROR", "sid=${id.take(8)} reason=invalid_json")
                    return
                }
                if (message.optString("origin") != EMBED_ORIGIN) {
                    Trace.event("RESPONSE_BRIDGE", "REJECT",
                        "sid=${id.take(8)} reason=invalid_frame_origin")
                    return
                }
                val stage = message.optString("stage")
                when (stage) {
                    "installed" -> {
                        milestone("probe_installed")
                        probeInstalled = true
                        Trace.event("RESPONSE_BRIDGE", "INSTALLED", "sid=${id.take(8)} type=${message.optString("kind")}")
                    }
                    "perf_snapshot" -> {
                        val phase = message.optString("phase").take(30)
                        val list = message.optJSONArray("resources")
                        Trace.event("AUTH_PERF", "SUMMARY",
                            "sid=${id.take(8)} phase=$phase ageMs=${message.optInt("age")} " +
                            "resources=${message.optInt("count")} longTaskAvailable=${message.optBoolean("longTaskAvailable")}")
                        for (i in 0 until minOf(list?.length() ?: 0, 12)) {
                            val entry = list?.optJSONObject(i) ?: continue
                            Trace.event("AUTH_PERF", "RESOURCE",
                                "sid=${id.take(8)} phase=$phase host=${entry.optString("host").take(65)} " +
                                "path=${entry.optString("path").take(65)} type=${entry.optString("type").take(20)} " +
                                "startMs=${entry.optInt("at")} endMs=${entry.optInt("end")} " +
                                "durationMs=${entry.optInt("ms")} blocking=${entry.optString("blocking").take(20)}")
                        }
                        val tasks = message.optJSONArray("tasks")
                        for (i in 0 until minOf(tasks?.length() ?: 0, 10)) {
                            val item = tasks?.optJSONObject(i) ?: continue
                            Trace.event("AUTH_PERF", "LONG_TASK",
                                "sid=${id.take(8)} phase=$phase startMs=${item.optInt("at")} " +
                                "durationMs=${item.optInt("ms")}")
                        }
                    }
                    "auth_start" -> {
                        val kind = message.optString("kind")
                        if (kind in setOf("ticket", "token", "manifest")) milestone("${kind}_request")
                    }
                    "response" -> {
                        val kind = message.optString("kind")
                        if (kind !in setOf("token", "manifest", "ticket")) return
                        val target = message.optString("url")
                        val urlPath = runCatching { URL(target).path }.getOrDefault("")
                        if (kind == "token" && urlPath != "/api/playlist-token" ||
                            kind == "manifest" && !urlPath.endsWith(".m3u8", true) ||
                            kind == "ticket" && !urlPath.startsWith("/premiumtv/")) {
                            Trace.event("RESPONSE_BRIDGE", "REJECT",
                                "sid=${id.take(8)} kind=$kind reason=wrong_endpoint")
                            return
                        }
                        val body = message.optString("body")
                        val status = message.optInt("http", -1)
                        val truncated = message.optBoolean("truncated", false)
                        val method = message.optString("method")
                        Trace.event("RESPONSE_BRIDGE", "RECEIVED",
                            "sid=${id.take(8)} kind=$kind method=$method http=$status " +
                                "transport=${message.optString("responseType")} elapsedMs=${message.optLong("elapsedMs")} " +
                                "reportedChars=${message.optInt("length")} truncated=$truncated " +
                                "headers=${message.optString("headers").take(180)}")
                        responseState(kind, status, target, body, truncated)
                    }
                    "proxy_refresh_info" -> {
                        Trace.event("AUTH_REFRESH", if (message.optBoolean("token")) "BROWSER_OK" else "BROWSER_UNAVAILABLE",
                            "sid=${id.take(8)} functionAvailable=${message.optBoolean("available")} tokenPresent=${message.optBoolean("token")}")
                    }
                    "proxy_refresh_error" -> {
                        Trace.event("AUTH_REFRESH", "BROWSER_ERROR",
                            "sid=${id.take(8)} reason=${message.optString("error").take(64)}")
                    }
                    "native_start" -> { jsMediaRequests.incrementAndGet() }
                    "native_segment" -> {
                        val address = message.optString("requestUrl").ifBlank { message.optString("url") }
                        recordObservedMedia(address, message.optString("url"),
                            message.optInt("http", 0), message.optString("method", "GET"))
                    }
                    "proxy_resource" -> {
                        val id = message.optString("id")
                        val request = pendingResources[id] ?: return
                        val kind = message.optString("kind")
                        val status = message.optInt("http", 0)
                        val body = if (kind == "manifest") message.optString("body") else null
                        val bytes = if (kind == "segment" && status in 200..299) {
                            runCatching { Base64.decode(message.optString("data"), Base64.DEFAULT) }
                                .getOrNull()?.takeIf { it.isNotEmpty() }
                        } else null
                        val result = BrowserReply(status, message.optString("url"), body, bytes)
                        Trace.event("PROXY_BROWSER", if (status in 200..299 && (body != null || bytes != null)) "OK" else "FAILED",
                            "sid=${this.id.take(8)} kind=$kind http=$status text=${body?.length ?: 0} " +
                                "bytes=${bytes?.size ?: 0} reason=${message.optString("error").take(64)}")
                        if (kind == "segment") {
                            Trace.event("BROWSER_SEGMENT", "RESPONSE",
                                "sid=${this.id.take(8)} ${Trace.fingerprint(message.optString("url"))} " +
                                    "http=$status mime=${message.optString("contentType").take(75)} " +
                                    "elapsedMs=${message.optLong("elapsedMs")}")
                        }
                        request.complete(result)
                    }
                    "network_error", "read_error", "clone_error", "install_error", "js_error", "promise_error" -> {
                        val error = message.optString("error").lowercase()
                        val category = when {
                            "turnstile" in error || "captcha" in error -> "turnstile"
                            "cors" in error -> "cors"
                            "security" in error -> "security"
                            "fetch" in error || "network" in error -> "network"
                            "timeout" in error -> "timeout"
                            "invalid" in error -> "invalid"
                            else -> "other"
                        }
                        Trace.event("RESPONSE_BRIDGE", "ERROR",
                            "sid=${id.take(8)} stage=$stage kind=${message.optString("kind")} " +
                                "category=$category errorClass=${message.optString("error").substringBefore(':').take(50)} " +
                                "source=${Trace.target(message.optString("source"))} line=${message.optInt("line")}")
                        if (stage == "js_error" || stage == "promise_error") {
                            val origin = message.optString("source")
                            if (origin.contains("daddy.min.js", true) || origin.contains("daddy.php", true) || stage == "promise_error")
                                errorDetail(origin, message.optInt("line"), message.optString("error"), stage)
                        }
                    }
                }
            }

            fun onToken(response: ByteArray) {
                val data = runCatching { JSONObject(String(response, Charsets.UTF_8)) }.getOrNull()
                if (data == null) {
                    Trace.event("AUTH_TOKEN", "PARSE_FAIL", "sid=${id.take(8)} bytes=${response.size} reason=invalid_json")
                    return
                }
                val current = data.optString("token").ifBlank { data.optString("t") }
                if (current.isNotBlank()) {
                    token = current
                    issuedTokenId = Trace.valueId(current)
                    issuedTokenAt = android.os.SystemClock.elapsedRealtime()
                }
                Trace.event("AUTH_CHAIN", "TOKEN_ISSUED",
                    "sid=${id.take(8)} tokenId=${Trace.valueId(current)} " +
                        "fields=${data.keys().asSequence().take(12).toList().sorted().joinToString(",")} " +
                        "keyPresent=${data.optString("k").isNotBlank()}")
                val key = data.optString("k")
                if (current.isNotBlank() && key.isNotBlank()) {
                    val decoded = runCatching { Base64.decode(key, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
                    if (decoded != null && decoded.size in setOf(16, 24, 32)) {
                        if (playKeys.size >= 4 && !playKeys.containsKey(current)) playKeys.clear()
                        playKeys[current] = decoded
                        Trace.event("AUTH_KEY", "ACQUIRED", "sid=" + id.take(8) + " bits=" + decoded.size * 8)
                    } else Trace.event("AUTH_KEY", "INVALID", "sid=" + id.take(8))
                }
                Trace.event("AUTH_TOKEN", if (current.isNotBlank()) "ACQUIRED" else "MISSING",
                    "sid=" + id.take(8) + " tokenPresent=" + current.isNotBlank() + " responseBytes=" + response.size)
            }

            fun captureManifest(request: WebResourceRequest) {
                val address = request.url.toString()
                val t = request.url.getQueryParameter("t")
                Trace.event("AUTH_CHAIN", "MANIFEST_REQUEST",
                    "sid=${id.take(8)} ${Trace.urlShape(address)} signed=${!t.isNullOrBlank()} " +
                        "tokenId=${Trace.valueId(t)} issuedId=$issuedTokenId " +
                        "matchesIssued=${!t.isNullOrBlank() && issuedTokenId != "none" && Trace.valueId(t) == issuedTokenId} " +
                        "issuedAgeMs=${if (issuedTokenAt > 0) android.os.SystemClock.elapsedRealtime()-issuedTokenAt else -1} " +
                        "${Trace.headerProfile(request.requestHeaders)}")
                if (token.isNotBlank() && t.isNullOrBlank() &&
                    Uri.parse(url).getQueryParameter("t")?.isNotBlank() == true) {
                    Trace.event("PLAYER_URL", "PRESERVE_SIGNED", "sid=${id.take(8)} reason=ignore_late_unsigned_request")
                    return
                }
                url = address
                browserHeaders = request.requestHeaders.toMap()
                if (!t.isNullOrBlank()) token = t
                Trace.event("PLAYER_URL", "CAPTURED",
                    "sid=${id.take(8)} target=${Trace.target(address)} signed=${!t.isNullOrBlank()} " +
                        "headerNames=${browserHeaders.keys.sorted().joinToString(",")} " +
                        "cookieStored=${runCatching { !CookieManager.getInstance().getCookie(address).isNullOrBlank() }.getOrDefault(false)} " +
                        "authRequests=$authRequestCount authHttpError=${authHttpError ?: -1}")
            }


            suspend fun browserRequest(kind: String, address: String): BrowserReply? {
                if (!probeInstalled || browser == null) {
                    Trace.event("PROXY_BROWSER", "UNAVAILABLE", "sid=${id.take(8)} kind=$kind installed=$probeInstalled")
                    return null
                }
                // Only forward a resource from this specific player's origin and channel directory.
                val valid = runCatching {
                    val source = URL(url)
                    val target = URL(address)
                    source.protocol == "https" && target.protocol == "https" &&
                        source.host.equals(target.host, true) &&
                        target.path.startsWith(source.path.substringBeforeLast('/') + "/")
                }.getOrDefault(false)
                if (!valid) {
                    Trace.event("PROXY_BROWSER", "REJECT", "sid=${id.take(8)} kind=$kind reason=outside_channel")
                    return null
                }
                val requestId = UUID.randomUUID().toString().replace("-", "")
                val result = CompletableDeferred<BrowserReply?>()
                pendingResources[requestId] = result
                if (kind == "segment") syntheticSegments[Trace.key(address)] = true
                val command = JSONObject().put("daddyProxy", 1).put("id", requestId)
                    .put("kind", kind).put("url", address).toString()
                val script = """(function(){
                    var cmd=$command, n=0, frames=document.querySelectorAll('iframe');
                    for(var i=0;i<frames.length;i++) {
                        try {
                            if(new URL(frames[i].src).origin==='https://dembed.st') {
                                frames[i].contentWindow.postMessage(cmd,'https://dembed.st'); n++;
                            }
                        }catch(e){}
                    }
                    return n;
                })();""".trimIndent()
                main.post {
                    val view = browser
                    if (view == null) result.complete(null)
                    else runCatching {
                        view.evaluateJavascript(script) { response ->
                            if (response?.trim()?.toIntOrNull() == 0) {
                                Trace.event("PROXY_BROWSER", "NO_FRAME", "sid=${id.take(8)} kind=$kind")
                                result.complete(null)
                            }
                        }
                    }.onFailure {
                        Trace.event("PROXY_BROWSER", "DISPATCH_ERROR",
                            "sid=${id.take(8)} kind=$kind reason=${it.javaClass.simpleName}")
                        result.complete(null)
                    }
                }
                return try {
                    withTimeoutOrNull(if (kind == "segment") 5500L else 7000L) { result.await() }.also {
                        if (it == null) Trace.event("PROXY_BROWSER", "TIMEOUT",
                            "sid=${id.take(8)} kind=$kind target=${Trace.target(address)}")
                    }
                } finally {
                    pendingResources.remove(requestId)
                    if (kind == "segment") syntheticSegments.remove(Trace.key(address))
                }
            }

            fun segmentWithToken(address: String): String? = runCatching {
                if (token.isBlank() || url.isBlank()) return@runCatching null
                val media = Uri.parse(address)
                val playlist = Uri.parse(url)
                if (media.scheme != "https" || media.host != playlist.host ||
                    !media.path.orEmpty().startsWith(playlist.path.orEmpty().substringBeforeLast('/') + "/") ||
                    media.getQueryParameter("t") != null) return@runCatching null
                media.buildUpon().appendQueryParameter("t", token).build().toString()
            }.getOrNull()

            fun actualSegment(address: String): String {
                val observed = verifiedSegment(address)
                if (observed != null && observed != address) {
                    Trace.event("SEGMENT_URL", "ORIGINAL_CONFIRMED",
                        "sid=${id.take(8)} request=${Trace.fingerprint(address)} " +
                            "effective=${Trace.fingerprint(observed)}")
                    return observed
                }
                return if (segmentTokenMode) segmentWithToken(address) ?: address else address
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
                val key = UUID.nameUUIDFromBytes(target.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
                resources.putIfAbsent(key, Resource(target, manifest))
                return "http://127.0.0.1:$port/$id/$key"
            }

            // Verification only: keep proxy/session state, release just the authentication WebView.
            fun releaseBrowserForTest() {
                val view = browser ?: return
                browser = null
                browserReleasedAt = android.os.SystemClock.elapsedRealtime()
                prefetchedPlayerScript = null
                Trace.event("WEBVIEW_EARLY_CLOSE_TEST", "REQUESTED",
                    "sid=${id.take(8)} signed=${token.isNotBlank()} sealed=$sealedPlaylist")
                main.post {
                    runCatching { view.stopLoading(); view.destroy() }
                        .onSuccess { Trace.event("WEBVIEW_EARLY_CLOSE_TEST", "DESTROYED", "sid=${id.take(8)}") }
                        .onFailure { Trace.event("WEBVIEW_EARLY_CLOSE_TEST", "DESTROY_FAILED",
                            "sid=${id.take(8)} reason=${it.javaClass.simpleName}") }
                }
            }

            fun close() {
                reportOutcome("CLOSED_WITHOUT_LINK")
                pendingResources.values.forEach { it.complete(null) }
                pendingResources.clear()
                val player = browser
                browser = null
                prefetchedPlayerScript = null
                main.post { runCatching { player?.stopLoading(); player?.destroy() } }
            }
        }

        suspend fun wrapDirectHls(link: ExtractorLink): ExtractorLink {
            if (!link.url.substringBefore('?').endsWith(".m3u8", true) ||
                !link.url.startsWith("https://")) return link
            val openPort = startServer()
            if (openPort == 0) {
                Trace.event("DIRECT_HLS_DIAG", "SKIPPED", "reason=proxy_bind_failed")
                return link
            }
            val direct = DirectSession(link.url, link.headers)
            directSessions[direct.id] = direct
            Trace.event("DIRECT_HLS_DIAG", "ENABLED",
                "sid=${direct.id.take(8)} transport=local_proxy_okhttp upstream=${Trace.fingerprint(link.url)}")
            return newExtractorLink(link.source, link.name,
                "http://127.0.0.1:$openPort/${direct.id}/index.m3u8", ExtractorLinkType.M3U8) {
                referer = link.referer
                quality = link.quality
            }
        }

        suspend fun extract(embedded: String, parent: String, name: String, sharedDembed: ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>>): ExtractorLink? {
            if (!runCatching {
                    val uri = Uri.parse(embedded)
                    uri.scheme == "https" && uri.host == "dembed.st" &&
                        uri.path?.matches(Regex("""^/premiumtv/[a-z0-9_-]+\.php$""", RegexOption.IGNORE_CASE)) == true
                }.getOrDefault(false)) {
                Trace.event("DEMBED", "SKIP", "target=${Trace.target(embedded)} reason=invalid_player_endpoint")
                return null
            }
            // Share the in-flight result rather than failing the later candidate.
            val pending = CompletableDeferred<ExtractorLink?>()
            val shared = sharedDembed.putIfAbsent(embedded, pending)
            if (shared != null) {
                val link = shared.await()
                Trace.event("DEMBED", if (link != null) "SHARED_OK" else "SHARED_FAILED",
                    "target=${Trace.target(embedded)}")
                return link
            }
            var result: ExtractorLink? = null
            try {
                result = extractDistinct(embedded, parent, name)
                return result
            } finally {
                pending.complete(result)
            }
        }

        private suspend fun extractDistinct(embedded: String, parent: String, name: String): ExtractorLink? {
            val session = Session(parent, embedded)
            session.milestone("extract_start")
            val startMs = android.os.SystemClock.elapsedRealtime()
            Trace.event("DEMBED", "START", "sid=${session.id.take(8)} target=${Trace.target(embedded)} parent=${Trace.target(parent)}")
            val captured = withTimeoutOrNull(20000L) {
                suspendCancellableCoroutine<String?> { continuation ->
                    var web: WebView? = null
                    val timeout = Runnable {
                        Trace.event("PLAYER_URL", "TIMEOUT", "sid=${session.id.take(8)} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                        if (continuation.isActive) continuation.resume(null)
                    }
                    continuation.invokeOnCancellation {
                        session.close()
                        main.post {
                            main.removeCallbacks(timeout)
                            runCatching { web?.destroy() }
                        }
                    }
                    main.post {
                        if (!continuation.isActive) return@post
                        try {
                            val context = (CloudStreamApp.context ?: app) as android.content.Context
                            web = WebView(context)
                            val view = web!!
                            Trace.event("AUTH_WEBVIEW", "CREATED",
                                "sid=${session.id.take(8)} attached=${view.isAttachedToWindow} parentPresent=${view.parent != null} " +
                                    "shown=${view.isShown} size=${view.width}x${view.height}")
                            session.browser = view
                            session.milestone("webview_created")
                            session.userAgent = view.settings.userAgentString
                            view.settings.javaScriptEnabled = true
                            view.settings.domStorageEnabled = true
                            view.settings.mediaPlaybackRequiresUserGesture = false
                            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
                            // Android framework bridge: available to isolated CloudStream plugins
                            // without depending on AndroidX WebKit classes at runtime.
                            session.probeAvailable = true
                            view.addJavascriptInterface(session, "DaddyResponseTrace")
                            Trace.event("RESPONSE_BRIDGE", "READY",
                                "sid=${session.id.take(8)} mode=framework_js_interface " +
                                    "injection=player_js_prepend")
                            view.webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    val raw = message?.message().orEmpty()
                                    if (message?.message() != null && message.messageLevel() == ConsoleMessage.MessageLevel.ERROR) {
                                        val kind = when {
                                            raw.contains("turnstile", true) || raw.contains("captcha", true) -> "challenge"
                                            raw.contains("ticket", true) -> "ticket"
                                            raw.contains("token", true) -> "token"
                                            raw.contains("cors", true) -> "cors"
                                            raw.contains("network", true) -> "network"
                                            else -> "script_error"
                                        }
                                        Trace.event("AUTH_JS", "ERROR",
                                            "sid=${session.id.take(8)} kind=$kind source=${Trace.target(message.sourceId())} line=${message.lineNumber()}")
                                        if (message.sourceId().contains("daddy.min.js", true) ||
                                            message.sourceId().contains("daddy.php", true))
                                            session.errorDetail(message.sourceId(), message.lineNumber(), raw, kind)
                                    }
                                    return false
                                }
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                    val count = session.mainNavigations.incrementAndGet()
                                    session.milestone("page_started")
                                    Trace.event("AUTH_PAGE", "NAVIGATE",
                                        "sid=${session.id.take(8)} count=$count target=${Trace.target(url.orEmpty())}")
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    session.milestone("page_finished")
                                    session.resourceSnapshot("page_finished")
                                    // Main document performance provides the pre-Dembed JS
                                    // resource waterfall and HTML script async/defer attributes.
                                    view?.evaluateJavascript("""
                                        (function(){
                                          try {
                                            var items=performance.getEntriesByType('resource')
                                              .filter(function(e){return e.initiatorType==='script'||/\.js$/i.test(new URL(e.name).pathname)})
                                              .sort(function(a,b){return b.responseEnd-a.responseEnd}).slice(0,16)
                                              .map(function(e){
                                                var u=new URL(e.name);
                                                return [u.hostname.slice(0,55),u.pathname.slice(-55),
                                                  Math.round(e.startTime),Math.round(e.responseEnd),
                                                  Math.round(e.duration),e.renderBlockingStatus||''].join(':');
                                              });
                                            var scripts=Array.prototype.slice.call(document.scripts).slice(0,24)
                                              .filter(function(x){return !!x.src})
                                              .map(function(x){
                                                var u=new URL(x.src);
                                                return [u.hostname.slice(0,50),u.pathname.slice(-45),
                                                  !!x.async,!!x.defer].join(':');
                                              });
                                            return JSON.stringify({age:Math.round(performance.now()),
                                              total:performance.getEntriesByType('resource').length,
                                              scripts:document.scripts.length,resources:items,attributes:scripts});
                                          }catch(e){return JSON.stringify({error:String(e&&e.name||'unavailable')})}
                                        })();
                                    """.trimIndent()) { raw ->
                                        val decoded = runCatching { JSONTokener(raw).nextValue().toString() }
                                            .getOrDefault("invalid").take(3500)
                                        Trace.event("AUTH_PARENT_PERF", "SNAPSHOT",
                                            "sid=${session.id.take(8)} data=$decoded")
                                    }
                                    Trace.event("AUTH_WEBVIEW", "PAGE_LOADED",
                                        "sid=${session.id.take(8)} target=${Trace.target(url.orEmpty())} " +
                                        "probeAvailable=${session.probeAvailable} installed=${session.probeInstalled}")
                                }

                                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                                    if (request?.isForMainFrame == true || request?.url?.path?.contains("/premium") == true) {
                                        Trace.event("AUTH_WEBVIEW", "LOAD_ERROR",
                                            "sid=${session.id.take(8)} target=${Trace.target(request?.url?.toString().orEmpty())} code=${error?.errorCode} " +
                                                "description=${error?.description?.toString()?.substringBefore('?')?.take(90)}")
                                    }
                                }

                                override fun onReceivedHttpError(
                                    view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                                ) {
                                    val target = request?.url?.toString().orEmpty()
                                    val path = request?.url?.path.orEmpty()
                                    val status = errorResponse?.statusCode ?: -1
                                    val headers = errorResponse?.responseHeaders.orEmpty()
                                    if (path == "/api/playlist-token") {
                                        session.authHttpError = status
                                        Trace.event("AUTH_RESPONSE", "BROWSER_HTTP_FAIL",
                                            "sid=${session.id.take(8)} http=$status reason=${errorResponse?.reasonPhrase?.take(64)} " +
                                                "target=${Trace.target(target)} method=${request?.method} " +
                                                "headerNames=${headers.keys.sorted().joinToString(",")} " +
                                                "cfMitigation=${headers.keys.any { it.equals("cf-mitigated", true) }} " +
                                                "wwwAuth=${headers.keys.any { it.equals("www-authenticate", true) }} " +
                                                "retryAfter=${headers.keys.any { it.equals("retry-after", true) }} " +
                                                "elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                                    } else if (path.endsWith(".m3u8", true)) {
                                        session.manifestHttpError = status
                                        session.markRejectedManifest(target, status)
                                        Trace.event("HLS_BROWSER", "HTTP_FAIL",
                                            "sid=${session.id.take(8)} http=$status target=${Trace.target(target)} " +
                                                "signed=${request?.url?.getQueryParameter("t")?.isNotBlank() == true} " +
                                                "headerNames=${headers.keys.sorted().joinToString(",")}")
                                    } else if (request?.isForMainFrame == true || path.contains("/premium") ||
                                        (session.url.isNotBlank() && request?.url?.host.equals(Uri.parse(session.url).host, true))) {
                                        if (session.syntheticSegments.containsKey(Trace.key(target))) {
                                            Trace.event("SEGMENT_SYNTHETIC_HTTP", "RESPONSE",
                                                "sid=${session.id.take(8)} http=$status " +
                                                    "${Trace.fingerprint(target)} " +
                                                    "contentType=${headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty().take(45)}")
                                        }
                                        Trace.event("AUTH_WEBVIEW", "HTTP_ERROR",
                                            "sid=${session.id.take(8)} target=${Trace.target(target)} http=$status " +
                                                "reason=${errorResponse?.reasonPhrase?.take(64)}")
                                    }
                                    if (request?.url?.host == "dembed.st" &&
                                        path == Uri.parse(embedded).path && status in 400..599) {
                                        Trace.event("PLAYER_URL", "REJECTED",
                                            "sid=${session.id.take(8)} reason=embedded_page_http_$status")
                                        main.post {
                                            if (continuation.isActive) {
                                                main.removeCallbacks(timeout)
                                                continuation.resume(null)
                                            }
                                        }
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView?, request: WebResourceRequest?
                                ): WebResourceResponse? {
                                    if (request == null) return null
                                    val uri = request.url
                                    session.observeResource(request)
                                    val address = uri.toString()
                                    // Suppress documented ad/analytics subresources only, not
                                    // player, challenge, media or navigation requests.
                                    val host = uri.host.orEmpty()
                                    val adDomains = arrayOf("profitableratecpmnetwork.com",
                                        "histats.com", "tynt.com", "dtscout.com",
                                        "dtscdn.com", "xadsmart.com")
                                    if (!request.isForMainFrame && uri.scheme == "https" &&
                                        request.method.equals("GET", true) &&
                                        adDomains.any { host == it || host.endsWith(".$it") }) {
                                        val blocked = session.blockedAdResources.incrementAndGet()
                                        if (blocked <= 4) Trace.event("AUTH_RESOURCE", "BLOCK_AD",
                                            "sid=${session.id.take(8)} host=$host count=$blocked")
                                        return WebResourceResponse("text/plain", "UTF-8",
                                            ByteArrayInputStream(ByteArray(0)))
                                    }
                                    if (request.method.equals("GET", true) &&
                                        uri.scheme == "https" && uri.host.equals("dembed.st", true) &&
                                        uri.path?.equals("/premiumtv/daddy.min.js", true) == true) {
                                        return instrumentPlayerScript(request, session)
                                    }
                                    if (uri.scheme == "https" && uri.path == "/api/playlist-token" &&
                                        (uri.getQueryParameter("slug")?.startsWith("premium") == true ||
                                            uri.getQueryParameter("channel")?.startsWith("premium") == true ||
                                            uri.getQueryParameter("tk") != null)
                                    ) {
                                        session.authHttpError = null
                                        session.authRequestCount++
                                        Trace.event("AUTH_REQUEST", "BROWSER_NATIVE",
                                            "sid=${session.id.take(8)} method=${request.method} " +
                                                "target=${Trace.target(address)} hasTk=${uri.getQueryParameter("tk")?.isNotBlank() == true} " +
                                                "hasTicket=${uri.getQueryParameter("ticket")?.isNotBlank() == true} " +
                                                "headerNames=${request.requestHeaders.keys.sorted().joinToString(",")} " +
                                                "cookieStored=${runCatching { !CookieManager.getInstance().getCookie(address).isNullOrBlank() }.getOrDefault(false)} " +
                                                "note=no_replay_browser_handles_original_request")
                                        return null
                                    }
                                    if (uri.scheme == "https" && uri.path?.endsWith(".m3u8", true) == true &&
                                        uri.path?.contains("/premium") == true &&
                                        uri.path?.substringAfterLast("/") == "index.m3u8"
                                    ) {
                                        session.captureManifest(request)
                                        val signed = !uri.getQueryParameter("t").isNullOrBlank()
                                        // Allow the browser's own token exchange to finish before validating HLS.
                                        main.postDelayed({
                                            if (continuation.isActive && session.url.isNotBlank()) {
                                                main.removeCallbacks(timeout)
                                                continuation.resume(session.url)
                                            }
                                        }, if (signed) 100L else 2400L)
                                    }
                                    val source = session.url.takeIf { it.isNotBlank() }
                                    val path = uri.path.orEmpty()
                                    if (source != null && uri.scheme == "https" &&
                                        uri.host.equals(Uri.parse(source).host, true) &&
                                        !path.endsWith(".m3u8", true) &&
                                        request.method.equals("GET", true)
                                    ) {
                                        if (session.syntheticSegments.containsKey(Trace.key(address))) {
                                            Trace.event("SEGMENT_REQUEST", "SYNTHETIC", "sid=${session.id.take(8)} ${Trace.fingerprint(address)}")
                                        } else {
                                            session.observeNativeRequest(address, request.method, request.requestHeaders)
                                        }
                                    }
                                    return null
                                }
                            }
                            // Prefetch the exact versioned JS URL previously requested by WebView.
                            // For first use or a changed version, the browser's actual request
                            // refreshes this URL. No guessed query-string or duplicate HTML fetch.
                            val expectedJs = runCatching { URL(URL(embedded), "daddy.min.js") }.getOrNull()
                            val scriptUrl = observedPlayerJsUrl?.takeIf { known ->
                                runCatching {
                                    val verified = URL(known)
                                    verified.protocol == "https" && expectedJs != null &&
                                        verified.host.equals(expectedJs.host, true) &&
                                        verified.path == expectedJs.path
                                }.getOrDefault(false)
                            }
                            Trace.event("SCRIPT_PREFETCH", if (scriptUrl == null) "SKIP" else "SCHEDULED",
                                "sid=${session.id.take(8)} reason=${if (scriptUrl == null) "no_known_version" else "real_webview_version"} " +
                                    "url=${Trace.urlShape(scriptUrl)}")
                            if (scriptUrl != null) io.launch {
                                val cookie = scriptCookie(scriptUrl)
                                val agent = session.userAgent
                                val script = fetchPlayerScript(scriptUrl, emptyMap(), session, true)
                                if (script != null && session.browser != null)
                                    session.prefetchedPlayerScript = PrefetchedPlayerScript(
                                        scriptUrl, cookie, agent, session.embedded, script,
                                        android.os.SystemClock.elapsedRealtime()
                                    )
                            }
                            main.postDelayed(timeout, 19000L)
                            val url = if (parent.startsWith("https://") && !parent.contains("dembed.st")) parent else ""
                            if (url.isNotBlank()) {
                                Trace.event("AUTH_WEBVIEW", "LOAD_PARENT", "sid=${session.id.take(8)} target=${Trace.target(url)}")
                                session.milestone("navigate_parent")
                                view.loadUrl(url)
                            } else {
                                // The player explicitly requires an iframe (it rejects top-level navigation).
                                val safeUrl = embedded.replace("&", "&amp;").replace('"', '_')
                                val html = """<html><body><iframe src="$safeUrl" style="width:100%;height:100%"></iframe></body></html>"""
                                Trace.event("AUTH_WEBVIEW", "LOAD_EMBED_IFRAME", "sid=${session.id.take(8)} target=${Trace.target(embedded)}")
                                session.milestone("navigate_iframe")
                                view.loadDataWithBaseURL("https://thedaddy.li/", html, "text/html", "UTF-8", null)
                            }
                        } catch (e: Exception) {
                            Trace.event("AUTH_WEBVIEW", "ERROR", "sid=${session.id.take(8)} reason=${e.javaClass.simpleName}")
                            main.removeCallbacks(timeout)
                            if (continuation.isActive) continuation.resume(null)
                        }
                    }
                }
            }
            if (captured.isNullOrBlank()) {
                Trace.event("PLAYER_URL", "NOT_FOUND", "sid=${session.id.take(8)} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs} reason=no_manifest_request")
                session.close()
                return null
            }
            session.milestone("playlist_url_captured")
            if (session.probeAvailable) {
                val waitStarted = android.os.SystemClock.elapsedRealtime()
                val capturedByBrowser = withTimeoutOrNull(if (session.probeInstalled) 6500L else 3000L) {
                    session.browserManifestReady.await()
                } == true
                Trace.event("RESPONSE_BRIDGE",
                    if (capturedByBrowser) "MANIFEST_CAPTURED" else "MANIFEST_UNAVAILABLE",
                    "sid=${session.id.take(8)} installed=${session.probeInstalled} " +
                        "waitMs=${android.os.SystemClock.elapsedRealtime()-waitStarted} " +
                        "browserTokenHttp=${session.browserTokenHttp} ticketHttp=${session.browserTicketHttp} " +
                        "browserManifestHttp=${session.manifestHttpError ?: -1} " +
                        "reason=${if (capturedByBrowser) "browser_response_body_ready" else if (!session.probeInstalled) "probe_not_installed" else "no_successful_readable_manifest"}")
            } else {
                Trace.event("RESPONSE_BRIDGE", "SKIPPED",
                    "sid=${session.id.take(8)} reason=document_start_or_web_message_not_available")
            }
            session.milestone("manifest_bridge_complete")
            val actual = session.browserManifestUrl.ifBlank { session.url.ifBlank { captured } }
            val signed = !Uri.parse(actual).getQueryParameter("t").isNullOrBlank()
            if (signed && session.browserManifestBody == null &&
                actual == session.rejectedSignedManifest) {
                Trace.event("HLS_FETCH", "SKIP_SIGNED_404",
                    "sid=${session.id.take(8)} ${Trace.urlShape(actual)} " +
                        "reason=browser_404_same_uri_no_replay")
                session.close()
                return null
            }
            Trace.event("PLAYER_URL", "FOUND",
                "sid=${session.id.take(8)} target=${Trace.target(actual)} signed=$signed " +
                    "authRequests=${session.authRequestCount} authHttpError=${session.authHttpError ?: -1} " +
                    "browserHlsError=${session.manifestHttpError ?: -1} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
            if (session.authHttpError == 403 && !signed) {
                Trace.event("AUTH_DIAG", "BLOCKED",
                    "sid=${session.id.take(8)} reason=native_browser_token_403_no_signed_playlist " +
                        "browserManifestHttp=${session.manifestHttpError ?: -1} " +
                        "note=server_rejected_original_browser_request_not_replay")
                session.close()
                return null
            }
            if (!signed) {
                Trace.event("AUTH_DIAG", "UNCONFIRMED",
                    "sid=${session.id.take(8)} reason=no_signed_playlist_observed " +
                        "authRequests=${session.authRequestCount} authHttpError=${session.authHttpError ?: -1} " +
                        "browserManifestHttp=${session.manifestHttpError ?: -1}")
            }
            return try {
                val requestHeaders = session.headers
                val browserBody = session.browserManifestBody
                val response = if (browserBody == null) {
                    Trace.event("AUTH_CHAIN", "CLIENT_FALLBACK",
                        "sid=${session.id.take(8)} ${Trace.urlShape(actual)} " +
                            "tokenId=${Trace.valueId(Uri.parse(actual).getQueryParameter("t"))} " +
                            "issuedId=${session.issuedTokenId} signedBrowserHttp=${session.signedManifestHttp} " +
                            "unsignedBrowserHttp=${session.unsignedManifestHttp} ${Trace.headerProfile(requestHeaders)}")
                    Trace.event("HLS_FETCH", "CLIENT_REQUEST",
                        "sid=${session.id.take(8)} target=${Trace.target(actual)} signed=$signed " +
                            "headerNames=${requestHeaders.keys.sorted().joinToString(",")} " +
                            "cookiePresent=${requestHeaders.keys.any { it.equals("cookie", true) }}")
                    app.get(actual, headers = requestHeaders, timeout = 5)
                } else null
                if (response != null) {
                    Trace.event("HLS_FETCH", if (response.isSuccessful) "HTTP_OK" else "HTTP_FAIL",
                        "sid=${session.id.take(8)} target=${Trace.target(actual)} http=${response.code} " +
                            "reason=${if (response.isSuccessful) "none" else Trace.bodyReason(response.text)} " +
                            "browserHlsHttpError=${session.manifestHttpError ?: -1} " +
                            "browserAuthHttpError=${session.authHttpError ?: -1} browserTokenHttp=${session.browserTokenHttp} " +
                            "headerNames=${response.headers.names().sorted().joinToString(",")} " +
                            "contentType=${response.headers["Content-Type"].orEmpty().substringBefore(';')}")
                } else {
                    Trace.event("HLS_FETCH", "BROWSER_REUSED",
                        "sid=${session.id.take(8)} target=${Trace.target(actual)} " +
                            "chars=${browserBody?.length ?: 0} reason=original_webview_http_200_no_replay")
                }
                val body = browserBody ?: response?.takeIf { it.isSuccessful }?.text
                if (body == null) {
                    Trace.event("HLS_DECODE", "NOT_REACHED",
                        "sid=${session.id.take(8)} reason=manifest_http_failed " +
                            "browserStatus=${session.manifestHttpError ?: -1} clientStatus=${response?.code ?: -1}")
                    session.close()
                    null
                } else {
                    val playlist = decodePlaylist(body, session)
                    if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
                        Trace.event("HLS_DECODE", "FAILED", "sid=${session.id.take(8)} reason=invalid_playlist")
                        session.close()
                        null
                    } else {
                        Trace.event("HLS_DECODE", "OK", "sid=${session.id.take(8)} chars=${playlist.length} encrypted=${!body.trimStart().startsWith("#EXTM3U")}")
                        session.milestone("playlist_decoded", "sealed=${session.sealedPlaylist}")
                        // Sealed AES playlists already contain decrypted media URLs;
                        // retain the browser-observation wait only for plain playlists.
                        val waitForNative = !session.sealedPlaylist &&
                            session.nativeRequestCount.get() == 0 && session.jsMediaRequests.get() == 0
                        Trace.event("SEGMENT_PREFLIGHT", if (waitForNative) "WAIT_NATIVE" else "NO_WAIT",
                            "sid=${session.id.take(8)} sealed=${session.sealedPlaylist} " +
                            "waitMs=${if (waitForNative) 700 else 0}")
                        if (waitForNative) delay(700)
                        session.milestone("preflight_start")
                        if (!probePlayable(session, playlist, actual)) {
                            delay(280)
                            session.close()
                            null
                        } else if (session.directAllowed && (!signed || playlist.contains("#EXT-X-ENDLIST"))) {
                            Trace.event("MEDIA3_LINK", "DIRECT", "sid=${session.id.take(8)} reason=ordinary_hls elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                            // Probe a media segment before bypassing the wrapper decoder.
                            session.milestone("link_ready")
                            session.resourceSnapshot("link_ready")
                            session.close()
                            newExtractorLink(name, name, actual, ExtractorLinkType.M3U8) {
                                referer = "$EMBED_ORIGIN/"
                                quality = Qualities.Unknown.value
                                headers = session.headers
                            }
                        } else {
                            Trace.event("MEDIA3_ROUTE", "PROXY_REQUIRED",
                                "sid=${session.id.take(8)} reason=${if (signed && !playlist.contains("#EXT-X-ENDLIST")) "signed_live_refresh" else "browser_only_segments"}")
                            val openPort = startServer()
                            if (openPort == 0) {
                                Trace.event("MEDIA3_LINK", "FAILED", "sid=${session.id.take(8)} reason=proxy_bind_failed")
                                session.close()
                                null
                            } else {
                                Trace.event("MEDIA3_LINK", "PROXY", "sid=${session.id.take(8)} signed=$signed elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                                session.firstPlaylist = stableInitialPlaylist(playlist, session)
                                session.url = actual
                                sessions[session.id] = session
                                session.milestone("link_ready")
                                session.resourceSnapshot("link_ready")
                                val local = "http://127.0.0.1:$openPort/${session.id}/index.m3u8"
                                val link = newExtractorLink(name, name, local, ExtractorLinkType.M3U8) {
                                    referer = "$EMBED_ORIGIN/"
                                    quality = Qualities.Unknown.value
                                }
                                session.releaseBrowserForTest()
                                link
                            }
                        }
                    }
                }
            } catch (cancel: CancellationException) {
                session.close()
                throw cancel
            } catch (e: Exception) {
                Trace.event("DEMBED", "ERROR", "sid=${session.id.take(8)} reason=${e.javaClass.simpleName} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                session.close()
                null
            }
        }

        /** Log structure, not signed URLs or segment tokens. */
        /** A playlist alone is not a playable link: verify newest media segment bytes first. */
        private suspend fun probePlayable(session: Session, playlist: String, manifestUrl: String): Boolean {
            session.encryptedMedia = playlist.contains("#EXT-X-KEY", true)
            session.registerManifestSegments(playlist, manifestUrl)
            val available = playlist.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { absolute(manifestUrl, it) }
                .filterNot { URL(it).path.endsWith(".m3u8", true) }
                .toList().asReversed()
            val verified = available.filter { session.hasVerifiedAlternative(it) }
            val seq = playlist.lineSequence().firstOrNull {
                it.startsWith("#EXT-X-MEDIA-SEQUENCE:", true)
            }?.substringAfter(':').orEmpty()
            val duration = playlist.lineSequence().firstOrNull {
                it.startsWith("#EXT-X-TARGETDURATION:", true)
            }?.substringAfter(':').orEmpty()
            Trace.event("SEGMENT_WINDOW", "PREFLIGHT",
                "sid=${session.id.take(8)} seq=$seq duration=$duration count=${available.size} " +
                    "hasPdt=${playlist.contains("#EXT-X-PROGRAM-DATE-TIME", true)} " +
                    "sealed=${session.sealedPlaylist} encrypted=${session.encryptedMedia} " +
                    "newestId=${Trace.valueId(available.firstOrNull())} " +
                    "previousId=${Trace.valueId(available.getOrNull(1))} " +
                    "oldestId=${Trace.valueId(available.lastOrNull())}")
            // In a sealed live sliding window, the newest entry can still be incomplete.
            // Prefer the preceding completed entry; retain the newest as a fallback.
            val stableLive = session.sealedPlaylist && available.size >= 3 &&
                playlist.contains("#EXT-X-MEDIA-SEQUENCE", true) &&
                playlist.contains("#EXT-X-TARGETDURATION", true) &&
                !playlist.contains("#EXT-X-ENDLIST", true) &&
                !playlist.contains("#EXT-X-KEY", true) &&
                !playlist.contains("#EXT-X-MAP", true)
            val targets = (verified + if (stableLive)
                (available.drop(1).take(2) + available.take(1)) else available.take(3))
                .distinct().take(3)
            Trace.event("SEGMENT_PREFLIGHT", "SELECTED",
                "sid=${session.id.take(8)} verifiedCandidates=${verified.size} count=${targets.size} " +
                    "stableLive=$stableLive penultimateFirst=${stableLive && verified.isEmpty()}")
            if (targets.isEmpty()) {
                Trace.event("SEGMENT_PREFLIGHT", "FAILED", "sid=" + session.id.take(8) + " reason=no_media_entries")
                return false
            }
            for ((index, target) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val requestUrl = session.actualSegment(target)
                val forwarded = session.mediaHeaders(requestUrl)
                Trace.event("SEGMENT_ATTEMPT", "CLIENT",
                    "sid=${session.id.take(8)} entry=$index mapping=${requestUrl != target} " +
                        "${Trace.fingerprint(requestUrl)} ${Trace.headerProfile(forwarded)}")
                val requestStarted = android.os.SystemClock.elapsedRealtime()
                var clientFailure = "none"
                val client = try {
                    app.get(requestUrl, headers = forwarded, timeout = 4)
                } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    clientFailure = e.javaClass.simpleName
                    null
                }
                val responseAt = android.os.SystemClock.elapsedRealtime()
                val prefix = ByteArray(576)
                var bodyReadMs = 0L
                var unwrapMs = 0L
                var inputBytes = 0
                var decodedBytes = 0
                var decodeState = "none"
                // Separate transfer from PNG/TS restoration without changing validation.
                val n = if (session.sealedPlaylist) {
                    val raw = runCatching {
                        client?.takeIf { it.isSuccessful }?.body?.byteStream()?.use { input ->
                            readLimited(input, 12 * 1024 * 1024)
                        }
                    }.getOrNull()
                    val bodyAt = android.os.SystemClock.elapsedRealtime()
                    bodyReadMs = bodyAt - responseAt
                    inputBytes = raw?.size ?: 0
                    val media = raw?.let { runCatching { unwrapMedia(it) }.getOrNull() }
                    unwrapMs = android.os.SystemClock.elapsedRealtime() - bodyAt
                    decodedBytes = media?.size ?: 0
                    decodeState = when {
                        raw == null -> "body_unavailable"
                        media == null -> "not_ts"
                        else -> "ok"
                    }
                    if (raw != null && media == null) Trace.event("SEGMENT_MEDIA_SHAPE", "UNWRAP_FAILED",
                        "sid=${session.id.take(8)} entry=$index targetId=${Trace.key(target)} " +
                            "mime=${client?.headers?.get("Content-Type").orEmpty().substringBefore(';')} " +
                            describeMediaShape(raw))
                    val size = minOf(decodedBytes, prefix.size)
                    if (size > 0) media!!.copyInto(prefix, 0, 0, size)
                    size
                } else try {
                    val size = client?.takeIf { it.isSuccessful }?.body?.byteStream()
                        ?.use { readMediaPrefix(it, prefix) } ?: 0
                    bodyReadMs = android.os.SystemClock.elapsedRealtime() - responseAt
                    inputBytes = size
                    decodedBytes = size
                    size
                } catch (_: Exception) { 0 }
                val prefixAt = android.os.SystemClock.elapsedRealtime()
                Trace.event("SEGMENT_PREFLIGHT_TIME", "BREAKDOWN",
                    "sid=${session.id.take(8)} entry=$index http=${client?.code ?: -1} " +
                    "requestMs=${responseAt-requestStarted} bodyReadMs=$bodyReadMs unwrapMs=$unwrapMs " +
                    "readUnwrapMs=${prefixAt-responseAt} totalMs=${prefixAt-requestStarted} " +
                    "sealed=${session.sealedPlaylist} inputBytes=$inputBytes decodedBytes=$decodedBytes " +
                    "decode=$decodeState prefixBytes=$n")
                val upstream = client?.code ?: -1
                val reason = if (upstream in 400..599) {
                    runCatching { Trace.bodyReason(client?.text.orEmpty()) }.getOrDefault("read_error")
                } else "none"
                Trace.event("SEGMENT_UPSTREAM", if (upstream in 200..299) "HTTP_OK" else "FAILED",
                    "sid=${session.id.take(8)} entry=$index status=$upstream cause=$reason " +
                        "failure=$clientFailure mime=${client?.headers?.get("Content-Type")?.substringBefore(';').orEmpty().take(45)} " +
                        "server=${client?.headers?.get("Server").orEmpty().take(35)} " +
                        "viaPresent=${client?.headers?.get("Via") != null} " +
                        "cfPresent=${client?.headers?.get("CF-Ray") != null} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-requestStarted} " +
                        "${Trace.fingerprint(target)}")
                if (client?.isSuccessful == true && validMediaPrefix(prefix, n, playlist)) {
                    if (stableLive && index == 0 && target == available[1])
                        session.skipInitialTail = true
                    session.segmentTransport = "client"; session.directAllowed = true
                    Trace.event("SEGMENT_PREFLIGHT", "OK", "sid=" + session.id.take(8) + " source=client entry=" + index + " http=" + client.code + " bytes=" + n)
                    session.milestone("preflight_ok", "source=client")
                    return true
                }
                Trace.event("SEGMENT_PREFLIGHT", "CLIENT_FAIL",
                    "sid=${session.id.take(8)} entry=$index http=${client?.code ?: -1} bytes=$n " +
                        "mime=${client?.headers?.get("Content-Type")?.substringBefore(';').orEmpty().take(60)} " +
                        "contentLength=${client?.headers?.get("Content-Length").orEmpty().take(15)}")
                if (client?.code == 404 && requestUrl == target && !session.tokenProbeDone) {
                    session.tokenProbeDone = true
                    val signedSegment = session.segmentWithToken(target)
                    if (signedSegment != null) {
                        val trial = try {
                            app.get(signedSegment, headers = forwarded, timeout = 4)
                        } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                        val tokenPrefix = ByteArray(576)
                        val tokenBytes = try {
                            trial?.takeIf { it.isSuccessful }?.body?.byteStream()
                                ?.use { readMediaPrefix(it, tokenPrefix) } ?: 0
                        } catch (_: Exception) { 0 }
                        val tokenValid = trial?.isSuccessful == true && validMediaPrefix(tokenPrefix, tokenBytes, playlist)
                        Trace.event("SEGMENT_TOKEN_PROBE", if (tokenValid) "RECOVERED" else "FAILED",
                            "sid=${session.id.take(8)} http=${trial?.code ?: -1} " +
                                "reason=${if (trial?.code in 400..599) runCatching { Trace.bodyReason(trial?.text.orEmpty()) }.getOrDefault("unavailable") else "none"} " +
                                "bytes=$tokenBytes ${Trace.fingerprint(target)} queryAdded=t")
                        if (tokenValid) {
                            session.segmentTokenMode = true
                            session.segmentTransport = "client"
                            session.directAllowed = false
                            return true
                        }
                    }
                }
                val browser = session.browserRequest("segment", requestUrl)
                val chunk = browser?.bytes
                if (browser?.status in 200..299 && chunk != null && validMediaPrefix(chunk, chunk.size, playlist)) {
                    session.segmentTransport = "browser"
                    Trace.event("SEGMENT_PREFLIGHT", "OK", "sid=" + session.id.take(8) + " source=browser entry=" + index + " bytes=" + chunk.size)
                    session.milestone("preflight_ok", "source=browser")
                    return true
                }
                Trace.event("SEGMENT_PREFLIGHT", "BROWSER_FAIL", "sid=" + session.id.take(8) + " entry=" + index +
                    " http=" + (browser?.status ?: -1) + " bytes=" + (chunk?.size ?: 0) + " target=" + Trace.target(target))
            }
            Trace.event("SEGMENT_PREFLIGHT", "FAILED",
                "sid=" + session.id.take(8) + " reason=all_candidates_failed count=" + targets.size)
            return false
        }
        @Synchronized private fun startServer(): Int {
            if (server?.isClosed == false) {
                Trace.event("PROXY", "REUSE", "port=$port")
                return port
            }
            return try {
                val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
                socket.soTimeout = 15000
                server = socket
                port = socket.localPort
                Trace.event("PROXY", "LISTEN", "binding=127.0.0.1 port=$port")
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
                        directSessions.forEach { (key, direct) ->
                            if (now - direct.lastUse > 15 * 60 * 1000L) directSessions.remove(key, direct)
                        }
                        if (sessions.isEmpty() && directSessions.isEmpty()) {
                            runCatching { socket.close() }
                            synchronized(DaddyLiveDembed) {
                                if (server === socket) { server = null; port = 0 }
                            }
                        }
                    }
                }
                port
            } catch (e: Exception) {
                Trace.event("PROXY", "BIND_FAIL", "reason=${e.javaClass.simpleName}")
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

        // Once the penultimate live segment was verified, keep a one-segment
        // safety margin on the initial and refreshed simple root live windows.
        private fun stableInitialPlaylist(playlist: String, session: Session): String {
            if (!session.skipInitialTail || !playlist.startsWith("#EXTM3U") ||
                !playlist.contains("#EXT-X-MEDIA-SEQUENCE", true) ||
                !playlist.contains("#EXT-X-TARGETDURATION", true) ||
                playlist.contains("#EXT-X-ENDLIST", true) ||
                playlist.contains("#EXT-X-KEY", true) ||
                playlist.contains("#EXT-X-MAP", true)) return playlist
            val lines = playlist.lines()
            val segments = lines.indices.filter { index ->
                val line = lines[index].trim()
                line.isNotEmpty() && !line.startsWith("#")
            }
            if (segments.size < 3) return playlist
            val last = segments.last()
            if (last < 1 || lines.drop(last + 1).any { it.isNotBlank() } ||
                !lines[last - 1].trim().startsWith("#EXTINF:", true)) return playlist
            var cut = last - 1
            if (cut > 0 && lines[cut - 1].trim().startsWith("#EXT-X-PROGRAM-DATE-TIME:", true))
                cut--
            if (cut > 0 && lines[cut - 1].trim().equals("#EXT-X-DISCONTINUITY", true))
                cut--
            val stable = lines.take(cut).joinToString("\n").trimEnd() + "\n"
            Trace.event("SEGMENT_WINDOW", "TAIL_HELD",
                "sid=${session.id.take(8)} entries=${segments.size} remaining=${segments.size - 1}")
            return stable
        }

        private fun rewrite(playlist: String, source: String, session: Session): String =
            playlist.lineSequence().joinToString("\n") { line ->
                if (line.startsWith("#")) {
                    attribute.replace(line) { match ->
                        val target = absolute(source, match.groupValues[1])
                        if (target == null) match.value
                        else "URI=\"${session.reference(target, target.substringBefore('?').endsWith(".m3u8", true))}\""
                    }
                } else if (line.isBlank()) line else {
                    val target = absolute(source, line.trim())
                    if (target == null) line
                    else session.reference(target, target.substringBefore('?').endsWith(".m3u8", true))
                }
            } + "\n"

        // Instrument direct HTTP HLS only for the player fallback. This shares the
        // existing loopback server, but never rewrites an unverified CDN URL.
        private fun rewriteDirect(playlist: String, source: String, session: DirectSession): String =
            playlist.lineSequence().joinToString("\n") { line ->
                if (line.startsWith("#")) {
                    attribute.replace(line) { match ->
                        val target = absolute(source, match.groupValues[1])
                        if (target == null) match.value
                        else "URI=\"${session.reference(target, target.substringBefore('?').endsWith(".m3u8", true), source)}\""
                    }
                } else if (line.isBlank()) line else {
                    val target = absolute(source, line.trim())
                    if (target == null) line
                    else session.reference(target, target.substringBefore('?').endsWith(".m3u8", true), source)
                }
            } + "\n"

        private fun trackDirectWindow(session: DirectSession, source: String, playlist: String) {
            val entries = playlist.lineSequence().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { absolute(source, it) }
                .filterNot { it.substringBefore('?').endsWith(".m3u8", true) }
                .toList()
            val sequence = playlist.lineSequence().firstOrNull {
                it.startsWith("#EXT-X-MEDIA-SEQUENCE:")
            }?.substringAfter(':').orEmpty()
            session.windows[source] = DirectWindow(sequence, entries.toSet(),
                entries.firstOrNull().orEmpty(), entries.lastOrNull().orEmpty())
            if (entries.isNotEmpty()) {
                Trace.event("DIRECT_HLS_WINDOW", "UPDATED",
                    "sid=${session.id.take(8)} manifest=${Trace.fingerprint(source)} " +
                        "sequence=$sequence count=${entries.size} " +
                        "first=${Trace.key(entries.first())} last=${Trace.key(entries.last())}")
            }
        }

        private suspend fun direct404Evidence(session: DirectSession, target: String,
            parentManifest: String, http: Int, range: String?
        ) {
            val refreshed = runCatching {
                app.get(parentManifest, headers = session.headers + ("Cache-Control" to "no-cache"), timeout = 5)
            }.getOrNull()
            val body = refreshed?.takeIf { it.isSuccessful }?.text
            if (body?.trimStart()?.startsWith("#EXTM3U") == true)
                trackDirectWindow(session, parentManifest, body)
            val current = session.windows[parentManifest]
            val presence = when {
                body == null -> "refresh_unavailable"
                !body.trimStart().startsWith("#EXTM3U") -> "refresh_invalid"
                current == null -> "no_media_entries"
                target in current.entries -> "still_in_latest_manifest"
                else -> "outside_latest_manifest"
            }
            Trace.event("DIRECT_HLS_404", "CHECKED",
                "sid=${session.id.take(8)} upstreamHttp=$http " +
                    "request=${Trace.fingerprint(target)} path=${Trace.target(target)} " +
                    "parent=${Trace.fingerprint(parentManifest)} range=${range ?: "none"} " +
                    "freshManifestHttp=${refreshed?.code ?: -1} " +
                    "sequence=${current?.sequence.orEmpty()} first=${Trace.key(current?.first.orEmpty())} " +
                    "last=${Trace.key(current?.last.orEmpty())} presence=$presence")
        }

        private suspend fun serveDirect(
            socket: Socket, session: DirectSession, key: String, range: String?
        ) {
            session.lastUse = android.os.SystemClock.elapsedRealtime()
            val resource = session.resources[key]
            val playlist = key == "index.m3u8" || resource?.playlist == true
            val address = if (key == "index.m3u8") session.url else resource?.url
            socket.tcpNoDelay = true
            val output = socket.getOutputStream()
            if (address == null) {
                output.write("HTTP/1.1 404 Not Found\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                return
            }
            val start = android.os.SystemClock.elapsedRealtime()
            val upstreamHeaders = if (range != null) session.headers + ("Range" to range) else session.headers
            val response = try {
                app.get(address, headers = upstreamHeaders, timeout = 12)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Trace.event("DIRECT_HLS_UPSTREAM", "EXCEPTION",
                    "sid=${session.id.take(8)} request=${Trace.fingerprint(address)} " +
                        "reason=${e.javaClass.simpleName}")
                null
            }
            val code = response?.code ?: 502
            if (code !in 200..299) {
                if (!playlist && code == 404 && resource != null) {
                    try {
                        io.launch { direct404Evidence(session, address, resource.manifest, code, range) }
                    } catch (e: Exception) {
                        Trace.event("DIRECT_HLS_404", "DIAGNOSTIC_ERROR",
                            "sid=${session.id.take(8)} reason=${e.javaClass.simpleName}")
                    }
                }
                Trace.event("DIRECT_HLS_UPSTREAM", "HTTP_FAIL",
                    "sid=${session.id.take(8)} manifest=$playlist http=$code " +
                        "request=${Trace.fingerprint(address)} path=${Trace.target(address)} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
                val localCode = code.takeIf { it in 400..599 } ?: 502
                val reason = if (localCode == 404) "Not Found" else "Bad Gateway"
                output.write(("HTTP/1.1 $localCode $reason\r\nConnection: close\r\n" +
                    "Cache-Control: no-store\r\nContent-Length: 0\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                return
            }
            if (playlist) {
                val raw = response!!.text
                if (!raw.trimStart().startsWith("#EXTM3U")) {
                    Trace.event("DIRECT_HLS_PLAYLIST", "INVALID",
                        "sid=${session.id.take(8)} upstream=${Trace.fingerprint(address)}")
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    return
                }
                trackDirectWindow(session, address, raw)
                val data = rewriteDirect(raw, address, session).toByteArray(Charsets.UTF_8)
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: ${data.size}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII))
                output.write(data)
                Trace.event("DIRECT_HLS_PLAYLIST", "OK",
                    "sid=${session.id.take(8)} target=${Trace.fingerprint(address)} " +
                        "bytes=${data.size} elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
                return
            }
            val reply = response!!
            val contentType = reply.headers["Content-Type"]?.substringBefore(';')
                ?.takeIf { it.matches(Regex("[a-zA-Z0-9._+/-]+")) } ?: "application/octet-stream"
            val length = reply.headers["Content-Length"]?.toLongOrNull()?.takeIf { it >= 0L }
            val contentRange = reply.headers["Content-Range"]?.takeIf {
                it.matches(Regex("bytes [0-9*]+-[0-9*]+/[0-9*]+"))
            }
            val status = if (code == 206) "206 Partial Content" else "200 OK"
            val header = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: $contentType\r\n")
                if (contentRange != null) append("Content-Range: $contentRange\r\n")
                append("Accept-Ranges: bytes\r\nCache-Control: no-store\r\nConnection: close\r\n")
                if (length != null) append("Content-Length: $length\r\n")
                else append("Transfer-Encoding: chunked\r\n")
                append("\r\n")
            }
            output.write(header.toByteArray(StandardCharsets.US_ASCII))
            var total = 0L
            try {
                reply.body.byteStream().use { stream ->
                    val buffer = ByteArray(32 * 1024)
                    var firstChunk = true
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (length == null)
                            output.write("${read.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                        output.write(buffer, 0, read)
                        if (length == null) output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
                        if (firstChunk) { output.flush(); firstChunk = false }
                        total += read
                    }
                }
                if (length == null) output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                Trace.event("DIRECT_HLS_SEGMENT",
                    if (length != null && length != total) "SHORT_READ" else "HTTP_OK",
                    "sid=${session.id.take(8)} upstreamHttp=$code " +
                        "url=${Trace.fingerprint(address)} path=${Trace.target(address)} " +
                        "range=${range ?: "none"} bytes=$total expectedBytes=${length ?: -1} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
            } catch (e: Exception) {
                Trace.event("DIRECT_HLS_SEGMENT", "TRANSFER_ERROR",
                    "sid=${session.id.take(8)} url=${Trace.fingerprint(address)} " +
                        "bytes=$total reason=${e.javaClass.simpleName}")
            }
        }

        /** Original daddy.min.js openPlaylist(): AES-CTR, IV = 64-bit HLS sequence
         * in the last eight bytes of a 16-byte counter. */
        private fun decodePlaylist(body: String, session: Session? = null): String? {
            if (!body.trimStart().startsWith("#EXTM3U")) return null
            val first = body.lineSequence().map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") } ?: return body
            if (first.length < 100 || !first.all { it.isLetterOrDigit() || it == '-' || it == '_' }) return body
            val key = session?.mediaKey() ?: run {
                Trace.event("HLS_DECODE", "KEY_MISSING")
                return null
            }
            return try {
                val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                val secret = SecretKeySpec(key, "AES")
                var sequence = 0L
                var count = 0
                val output = buildString {
                    for (entry in body.lineSequence()) {
                        val line = entry.trim()
                        if (line.isEmpty()) continue
                        if (line.startsWith("#")) {
                            if (line.startsWith("#EXT-X-MEDIA-SEQUENCE:"))
                                sequence = line.substringAfter(':').trim().toLong()
                            append(line).append('\n')
                            continue
                        }
                        val iv = ByteArray(16)
                        for (i in 0 until 8) iv[15 - i] = (sequence ushr (i * 8)).toByte()
                        cipher.init(Cipher.DECRYPT_MODE, secret, IvParameterSpec(iv))
                        val url = cipher.doFinal(Base64.decode(line, Base64.URL_SAFE or Base64.NO_WRAP))
                            .toString(Charsets.UTF_8)
                        val parsed = URL(url)
                        if (parsed.protocol != "https" || parsed.host.isBlank() ||
                            parsed.host == "localhost" || parsed.host == "127.0.0.1" ||
                            parsed.host.endsWith(".local")) return null
                        append(url).append('\n')
                        sequence++
                        count++
                    }
                }
                if (count == 0) return null
                session.sealedPlaylist = true
                Trace.event("HLS_DECODE", "SEALED_OK",
                    "sid=" + session.id.take(8) + " entries=" + count)
                output
            } catch (e: Exception) {
                Trace.event("HLS_DECODE", "SEALED_FAILED", "reason=" + e.javaClass.simpleName)
                null
            }
        }

        private fun at(bytes: ByteArray, offset: Int, value: String): Boolean =
            offset >= 0 && offset <= bytes.size - value.length &&
                value.indices.all { (bytes[offset + it].toInt() and 255) == value[it].code }

        private fun int32(bytes: ByteArray, at: Int): Int =
            ((bytes[at].toInt() and 255) shl 24) or
                ((bytes[at + 1].toInt() and 255) shl 16) or
                ((bytes[at + 2].toInt() and 255) shl 8) or (bytes[at + 3].toInt() and 255)

        private fun isTs(bytes: ByteArray, at: Int = 0) =
            at >= 0 && at + 188 < bytes.size &&
                bytes[at] == 0x47.toByte() && bytes[at + 188] == 0x47.toByte()

        private fun readLimited(input: InputStream, max: Int): ByteArray? {
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(16384)
            while (true) {
                val n = input.read(buffer)
                if (n < 0) break
                if (n == 0) continue
                if (output.size() > max - n) return null
                output.write(buffer, 0, n)
            }
            return output.toByteArray()
        }

        /** Original unwrap(): recover TS from WebP EXIF, PNG IEND / pixels,
         * TIKTIKRAW / TIKTIKTSGZ, or raw sync-byte embedded data. */
        // Failure-only PNG structure probe: no raw bytes or segment URLs in the logs.
        private fun describeMediaShape(bytes: ByteArray): String {
            val png = bytes.size >= 8 && bytes[0] == 0x89.toByte() && at(bytes, 1, "PNG")
            val webp = at(bytes, 0, "RIFF") && at(bytes, 8, "WEBP")
            if (!png) return "bytes=${bytes.size} png=false webp=$webp ts=${isTs(bytes)}"
            var offset = 8
            var chunks = 0
            var idat = 0L
            var iend = false
            var broken = false
            var width = 0
            var height = 0
            var color = -1
            while (offset + 12 <= bytes.size && chunks++ < 80) {
                val length = int32(bytes, offset)
                if (length < 0 || length > bytes.size - offset - 12) { broken = true; break }
                if (at(bytes, offset + 4, "IHDR") && length >= 13) {
                    width = int32(bytes, offset + 8)
                    height = int32(bytes, offset + 12)
                    color = bytes[offset + 17].toInt() and 255
                } else if (at(bytes, offset + 4, "IDAT")) idat += length
                else if (at(bytes, offset + 4, "IEND")) { iend = true; break }
                offset += 12 + length
            }
            return "bytes=${bytes.size} png=true dimensions=${width}x$height " +
                "colorType=$color chunks=$chunks idatBytes=$idat iend=$iend invalidChunk=$broken"
        }

        private fun unwrapMedia(bytes: ByteArray): ByteArray? {
            if (isTs(bytes)) return bytes
            if (at(bytes, 0, "RIFF") && at(bytes, 8, "WEBP")) {
                var i = 12
                while (i + 8 <= bytes.size) {
                    val length = (bytes[i + 4].toInt() and 255) or
                        ((bytes[i + 5].toInt() and 255) shl 8) or
                        ((bytes[i + 6].toInt() and 255) shl 16) or
                        ((bytes[i + 7].toInt() and 255) shl 24)
                    if (length < 0 || length > bytes.size - i - 8) break
                    if (at(bytes, i, "EXIF") && isTs(bytes, i + 8))
                        return bytes.copyOfRange(i + 8, bytes.size)
                    i += 8 + length + (length and 1)
                }
            }
            if (bytes.size >= 8 && bytes[0] == 0x89.toByte() && at(bytes, 1, "PNG")) {
                var i = 8
                var width = 0
                var height = 0
                var channels = 0
                val idat = ByteArrayOutputStream()
                while (i + 12 <= bytes.size) {
                    val length = int32(bytes, i)
                    if (length < 0 || length > bytes.size - i - 12) break
                    val begin = i + 8
                    when {
                        at(bytes, i + 4, "IHDR") && length >= 13 -> {
                            width = int32(bytes, begin)
                            height = int32(bytes, begin + 4)
                            channels = when (bytes[begin + 9].toInt() and 255) {
                                2 -> 3
                                6 -> 4
                                else -> 0
                            }
                            if (bytes[begin + 8] != 8.toByte() || bytes[begin + 10] != 0.toByte() ||
                                bytes[begin + 11] != 0.toByte() || bytes[begin + 12] != 0.toByte()) channels = 0
                        }
                        at(bytes, i + 4, "IDAT") -> {
                            if (idat.size() > 12 * 1024 * 1024 - length) return null
                            idat.write(bytes, begin, length)
                        }
                        at(bytes, i + 4, "IEND") -> {
                            i += 12 + length
                            if (isTs(bytes, i)) return bytes.copyOfRange(i, bytes.size)
                            break
                        }
                    }
                    i += 12 + length
                }
                if (channels > 0 && width > 0 && height > 0 &&
                    width.toLong() * height * (channels + 1) <= 12L * 1024 * 1024 &&
                    idat.size() > 0) {
                    val pixels = runCatching {
                        InflaterInputStream(ByteArrayInputStream(idat.toByteArray())).use { input ->
                            val raw = readLimited(input, 12 * 1024 * 1024) ?: return@use null
                            val stride = width * channels
                            if (raw.size < (stride + 1) * height) return@use null
                            val rgb = ByteArray(width * height * 3)
                            var src = 0
                            var dst = 0
                            var previous = ByteArray(stride)
                            repeat(height) {
                                val filter = raw[src++].toInt() and 255
                                if (filter > 4) return@use null
                                val row = ByteArray(stride)
                                for (x in 0 until stride) {
                                    val value = raw[src++].toInt() and 255
                                    val a = if (x >= channels) row[x - channels].toInt() and 255 else 0
                                    val b = previous[x].toInt() and 255
                                    val c = if (x >= channels) previous[x - channels].toInt() and 255 else 0
                                    val prediction = when (filter) {
                                        1 -> a
                                        2 -> b
                                        3 -> (a + b) / 2
                                        4 -> {
                                            val p = a + b - c
                                            val da = kotlin.math.abs(p - a)
                                            val db = kotlin.math.abs(p - b)
                                            val dc = kotlin.math.abs(p - c)
                                            if (da <= db && da <= dc) a else if (db <= dc) b else c
                                        }
                                        else -> 0
                                    }
                                    row[x] = (value + prediction).toByte()
                                }
                                for (x in 0 until width) {
                                    val p = x * channels
                                    rgb[dst++] = row[p]
                                    rgb[dst++] = row[p + 1]
                                    rgb[dst++] = row[p + 2]
                                }
                                previous = row
                            }
                            rgb
                        }
                    }.getOrNull()
                    if (pixels != null) for (p in 0 until pixels.size - 12) {
                        if (!at(pixels, p, "TIKTIKPX")) continue
                        val size = int32(pixels, p + 8)
                        if (size <= 0 || size > pixels.size - p - 12) continue
                        val ts = runCatching {
                            GZIPInputStream(ByteArrayInputStream(pixels, p + 12, size))
                                .use { readLimited(it, 12 * 1024 * 1024) }
                        }.getOrNull()
                        if (ts != null && isTs(ts)) return ts
                    }
                }
            }
            for (i in bytes.indices) {
                if (at(bytes, i, "TIKTIKRAW") && isTs(bytes, i + 9))
                    return bytes.copyOfRange(i + 9, bytes.size)
                if (at(bytes, i, "TIKTIKTSGZ")) {
                    val ts = runCatching {
                        GZIPInputStream(ByteArrayInputStream(bytes, i + 10, bytes.size - i - 10))
                            .use { readLimited(it, 12 * 1024 * 1024) }
                    }.getOrNull()
                    if (ts != null && isTs(ts)) return ts
                }
                if (isTs(bytes, i)) return bytes.copyOfRange(i, bytes.size)
            }
            return null
        }

        private suspend fun loadManifest(
            session: Session, resource: Resource?
        ): Pair<String, String>? {
            session.manifestMutex.lock()
            try {
                val source = resource?.url ?: session.url
                val now = android.os.SystemClock.elapsedRealtime()
                val cached = if (resource == null) session.browserManifestBody else null
                val cacheAge = now - session.browserManifestAt
                // Reuse a recent original-browser live playlist instead of requesting it again.
                if (cached != null && cacheAge in 0..1500L) {
                    decodePlaylist(cached, session)?.let {
                        Trace.event("HLS_REFRESH", "BROWSER_CACHE",
                            "sid=${session.id.take(8)} ageMs=$cacheAge chars=${it.length}")
                        return session.browserManifestUrl to it
                    }
                }
                val browser = session.browserRequest("manifest", session.signed(source))
                if (browser != null && browser.status in 200..299 && !browser.body.isNullOrBlank()) {
                    val decoded = decodePlaylist(browser.body, session)
                    if (decoded != null) {
                        session.browserManifestBody = browser.body
                        session.browserManifestUrl = browser.address
                        session.browserManifestAt = android.os.SystemClock.elapsedRealtime()
                        session.url = browser.address
                        Trace.event("HLS_REFRESH", "BROWSER_OK",
                            "sid=${session.id.take(8)} chars=${decoded.length}")
                        return browser.address to decoded
                    }
                    Trace.event("HLS_REFRESH", "BROWSER_INVALID",
                        "sid=${session.id.take(8)} reason=playlist_decode_failed")
                }
                // A failed browser request must not trigger repeated 403s through a different
                // HTTP client. Use the last good live manifest briefly while the player recovers.
                if (cached != null && cacheAge in 0..8000L) {
                    decodePlaylist(cached, session)?.let {
                        Trace.event("HLS_REFRESH", "LAST_GOOD",
                            "sid=${session.id.take(8)} ageMs=$cacheAge browserHttp=${browser?.status ?: -1}")
                        return session.browserManifestUrl to it
                    }
                }
                if (browser != null && browser.status in 400..599) {
                    Trace.event("HLS_REFRESH", "BROWSER_HTTP_FAIL",
                        "sid=${session.id.take(8)} http=${browser.status} reason=browser_refused")
                    return null
                }
                // Only fallback when the browser bridge is unavailable (e.g. unsupported frame).
                val target = session.signed(source)
                val response = runCatching { app.get(target, headers = session.headers, timeout = 5) }.getOrNull()
                if (session.browserReleasedAt != 0L) {
                    Trace.event("WEBVIEW_EARLY_CLOSE_TEST", "CLIENT_MANIFEST",
                        "sid=${session.id.take(8)} http=${response?.code ?: -1} " +
                            "sinceCloseMs=${android.os.SystemClock.elapsedRealtime()-session.browserReleasedAt} " +
                            "tokenAgeMs=${if (session.issuedTokenAt > 0L) android.os.SystemClock.elapsedRealtime()-session.issuedTokenAt else -1L}")
                }
                if (response?.isSuccessful == true) {
                    val decoded = decodePlaylist(response.text, session)
                    if (decoded != null) {
                        Trace.event("HLS_REFRESH", "CLIENT_OK",
                            "sid=${session.id.take(8)} chars=${decoded.length} fallback=bridge_unavailable")
                        return target to decoded
                    }
                }
                Trace.event("HLS_REFRESH", "FAILED",
                    "sid=${session.id.take(8)} browserHttp=${browser?.status ?: -1} " +
                        "clientHttp=${response?.code ?: -1} reason=${if (response == null) "client_exception" else if (!response.isSuccessful) Trace.bodyReason(response.text) else "invalid_manifest"}")
                return null
            } finally {
                session.manifestMutex.unlock()
            }
        }

        private suspend fun serve(client: Socket) {
            try {
                client.use { socket ->
                    socket.soTimeout = 12000
                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val request = reader.readLine().orEmpty()
                    val fields = request.split(' ')
                    val path = fields.getOrNull(1)?.substringBefore('?').orEmpty()
                    val parts = path.trimStart('/').split('/')
                    val direct = directSessions[parts.firstOrNull().orEmpty()]
                    if (direct != null && fields.firstOrNull() == "GET" && parts.size == 2) {
                        var range: String? = null
                        for (i in 0 until 48) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) break
                            if (line.startsWith("Range:", true)) {
                                val candidate = line.substringAfter(':').trim()
                                if (candidate.matches(Regex("""bytes=\d+-\d*(,\d+-\d*)*""")))
                                    range = candidate
                            }
                        }
                        serveDirect(socket, direct, parts[1], range)
                        return@use
                    }
                    val session = sessions[parts.firstOrNull().orEmpty()]
                    var data: ByteArray? = null
                    var type = "application/octet-stream"
                    if (fields.firstOrNull() == "GET" && session != null && parts.size == 2) {
                        val start = android.os.SystemClock.elapsedRealtime()
                        session.lastUse = android.os.SystemClock.elapsedRealtime()
                        val resource = session.resources[parts[1]]
                        when {
                            parts[1] == "index.m3u8" || resource?.playlist == true -> {
                                val first = if (resource == null) session.firstPlaylist else null
                                val original = if (resource == null) session.url else resource.url
                                if (resource == null) Trace.event("PROXY_WINDOW", "REQUEST",
                                    "sid=${session.id.take(8)} initial=${first != null} " +
                                        "tailHeld=${session.skipInitialTail} chars=${first?.length ?: 0}")
                                val recent = session.recentRootPlaylist?.takeIf {
                                    resource == null && first == null &&
                                        android.os.SystemClock.elapsedRealtime() - session.recentRootAt in 0..1000L
                                }
                                val manifest = if (first != null) {
                                    session.firstPlaylist = null
                                    original to first
                                } else recent ?: loadManifest(session, resource)
                                if (manifest != null) {
                                    // The first or recently published root is already trimmed.
                                    val mediaPlaylist = if (resource == null && first == null && recent == null)
                                        stableInitialPlaylist(manifest.second, session) else manifest.second
                                    session.registerManifestSegments(mediaPlaylist, manifest.first)
                                    if (resource == null) {
                                        session.recentRootPlaylist = manifest.first to mediaPlaylist
                                        session.recentRootAt = android.os.SystemClock.elapsedRealtime()
                                    }
                                    data = rewrite(mediaPlaylist, manifest.first, session).toByteArray()
                                    type = "application/vnd.apple.mpegurl"
                                    if (recent != null) Trace.event("PROXY_PLAYLIST", "QUICK_REUSE",
                                        "sid=${session.id.take(8)} ageMs=${android.os.SystemClock.elapsedRealtime()-session.recentRootAt}")
                                }
                                Trace.event("PROXY_PLAYLIST", if (data != null) "OK" else "FAILED",
                                    "sid=${session.id.take(8)} chars=${data?.size ?: 0} reason=${if (data == null) "manifest_unavailable" else "rewritten"}")

                            }
                            resource != null -> {
                                val retryAfter = session.failedSegments[resource.url] ?: 0L
                                if (start < retryAfter) {
                                    Trace.event("PROXY_SEGMENT", "SKIP_REPEATED_FAILURE",
                                        "sid=${session.id.take(8)} remainingMs=${retryAfter-start}")
                                } else {
                                    val actual = session.actualSegment(resource.url)
                                    var browser = if (session.segmentTransport == "browser")
                                        session.browserRequest("segment", actual) else null
                                    var bytes = browser?.takeIf { it.status in 200..299 }?.bytes
                                    var clientHttp = -1
                                    if (bytes == null) {
                                        val response = runCatching {
                                            app.get(actual, headers = session.mediaHeaders(actual), timeout = 8)
                                        }.getOrNull()
                                        clientHttp = response?.code ?: -1
                                        if (response?.isSuccessful == true) bytes = response.body.bytes()
                                    }
                                    if (bytes == null && browser == null) {
                                        browser = session.browserRequest("segment", actual)
                                        bytes = browser?.takeIf { it.status in 200..299 }?.bytes
                                    }
                                    if (bytes != null) {
                                        val decoded = if (session.sealedPlaylist) unwrapMedia(bytes) else bytes
                                        data = decoded?.takeIf {
                                            validMediaPrefix(it, it.size, if (session.encryptedMedia) "#EXT-X-KEY" else "")
                                        }
                                        type = if (data?.firstOrNull() == 0x47.toByte()) "video/mp2t"
                                            else "application/octet-stream"
                                        Trace.event("PROXY_SEGMENT", if (data != null) "OK" else "DECODE_FAIL",
                                            "sid=${session.id.take(8)} source=${if (browser?.bytes != null) "browser" else "client"} " +
                                                "in=${bytes.size} out=${data?.size ?: 0} type=$type " +
                                                "urlId=${Trace.key(resource.url)} pathId=${Trace.pathKey(resource.url)} " +
                                                "elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
                                    } else {
                                        val http = clientHttp.takeIf { it in 400..599 }
                                            ?: browser?.status?.takeIf { it != 0 } ?: clientHttp
                                        if (data == null) {
                                            if (http in 400..599) {
                                                if (session.failedSegments.size > 32) session.failedSegments.clear()
                                                session.failedSegments[resource.url] = start + 10000L
                                            }
                                            Trace.event("PROXY_SEGMENT", "HTTP_FAIL",
                                                "sid=${session.id.take(8)} browserHttp=${browser?.status ?: -1} " +
                                                    "clientHttp=$clientHttp urlId=${Trace.key(resource.url)} " +
                                                        "pathId=${Trace.pathKey(resource.url)} " +
                                                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val status = if (data == null) "502 Bad Gateway" else "200 OK"
                    if (data == null) Trace.event("PROXY", "NO_DATA", "request=${fields.firstOrNull()} sessionPresent=${session != null} parts=${parts.size}")
                    val payload = data ?: ByteArray(0)
                    val header = "HTTP/1.1 $status\r\nContent-Type: $type\r\n" +
                        "Connection: close\r\nCache-Control: no-store\r\nContent-Length: ${payload.size}\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(header.toByteArray(StandardCharsets.US_ASCII))
                        write(payload)
                        flush()
                    }
                }
            } catch (e: Exception) {
                Trace.event("PROXY", "ERROR", "reason=${e.javaClass.simpleName}")
                runCatching { client.close() }
            }
        }
    }

}

private fun readMediaPrefix(stream: InputStream, target: ByteArray): Int {
    var read = 0
    val required = minOf(376, target.size)
    while (read < required) {
        val n = stream.read(target, read, required - read)
        if (n <= 0) break
        read += n
    }
    return read
}

private fun validMediaPrefix(bytes: ByteArray, length: Int, manifest: String): Boolean {
    if (length < 8) return false
    val prefix = String(bytes, 0, minOf(length, 32), Charsets.US_ASCII).trimStart().lowercase()
    if (prefix.startsWith("<") || prefix.startsWith("{") || prefix.startsWith("#extm3u")) return false
    if (length > 188 && bytes[0] == 0x47.toByte() && bytes[188] == 0x47.toByte()) return true
    if (String(bytes, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof", "moov")) return true
    return manifest.contains("#EXT-X-KEY", true) && length >= 16
}
