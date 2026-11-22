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
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
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
        "plus",
        "watch",
        "player",
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

        // A slow stream player must not delay an independently playable fallback.
        // Limit outstanding work to three candidates (including any embedded WebView).
        val attemptedDembed = ConcurrentHashMap.newKeySet<String>()
        // Independent, bounded diagnostics: returning the first playable link must
        // not cancel stream's token, manifest, segment and HTTP/P2P observations.
        val scanJob = SupervisorJob()
        val scanScope = CoroutineScope(scanJob + Dispatchers.IO)
        val permits = Semaphore(3)
        val remaining = AtomicInteger(candidates.size)
        val first = CompletableDeferred<Pair<PlayerCandidate, ExtractorLink>?>()
        candidates.forEach { candidate ->
            scanScope.launch {
                try {
                    permits.acquire()
                    try {
                        val sourceName = "${selected.channelName} - ${candidate.player}"
                        Trace.event("PLAYER_CANDIDATE", "TRY",
                            "player=${candidate.player} target=${Trace.target(candidate.url)}")
                        val link = withTimeoutOrNull(35000L) {
                            extractVideo(candidate.url, sourceName, entryUrl, attemptedDembed)
                        }
                        if (link == null) {
                            Trace.event("PLAYER_CANDIDATE", "FALLBACK",
                                "player=${candidate.player} reason=no_valid_link_or_timeout")
                        } else if (!first.complete(candidate to link)) {
                            DaddyLiveDembed.discardUnused(link.url)
                            Trace.event("PLAYER_CANDIDATE", "DIAGNOSTIC_LINK",
                                "player=${candidate.player} result=validated_not_selected")
                        }
                    } finally {
                        permits.release()
                    }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (e: Exception) {
                    Trace.event("PLAYER_CANDIDATE", "ERROR",
                        "player=${candidate.player} reason=${e.javaClass.simpleName}")
                } finally {
                    if (remaining.decrementAndGet() == 0) {
                        first.complete(null)
                        Trace.event("PLAYER_DIAGNOSTICS", "COMPLETE",
                            "channel=${selected.streamId} candidates=${candidates.size}")
                        scanJob.cancel()
                    }
                }
            }
        }
        val selectedLink = try {
            first.await()
        } catch (cancel: CancellationException) {
            scanJob.cancel()
            throw cancel
        }
        if (selectedLink != null) {
            Trace.event("PLAYER_DIAGNOSTICS", "CONTINUE",
                "channel=${selected.streamId} remaining=${remaining.get()}")
        }
        if (selectedLink != null) {
            val (candidate, link) = selectedLink
            val playing = if (candidate.player == "player") DaddyLiveDembed.wrapDirectHls(link) else link
            Trace.event("MEDIA3_LINK", "EMITTED",
                "player=${candidate.player} mode=${if (playing.url.startsWith("http://127.0.0.1:")) "proxy" else "direct"} target=${Trace.target(playing.url)}")
            callback(playing)
        } else {
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
        attemptedDembed: MutableSet<String> = hashSetOf()
    ): ExtractorLink? {
        try {
            currentCoroutineContext().ensureActive()


            val targetOrigin = originOf(url) ?: mainUrl
            Trace.event("PLAYER_PAGE", "START", "target=${Trace.target(url)}")
            if (targetOrigin == "https://dembed.st" && URL(url).path.endsWith(".php", true)) {
                Trace.event("PLAYER_PAGE", "DEMBED_ROUTE", "target=" + Trace.target(url))
                if (attemptedDembed.contains(url)) {
                    Trace.event("DEMBED", "SKIP_REPEAT", "target=${Trace.target(url)}")
                    return null
                }
                return DaddyLiveDembed.extract(url, parentReferer.orEmpty(), sourceName, attemptedDembed)
            }
            val referer = parentReferer ?: "$targetOrigin/"
            val refererOrigin = originOf(referer) ?: targetOrigin

            val response = app.get(
                url,
                headers = pageHeaders(
                    parentUrl = referer,
                    parentOrigin = refererOrigin
                ),
                timeout = 5
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
                attemptedDembed = attemptedDembed
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
        attemptedDembed: MutableSet<String>
    ): ExtractorLink? {
        if (depth > 4) return null
        if (!visited.add(pageUrl)) return null

        currentCoroutineContext().ensureActive()

        val pageOrigin = originOf(pageUrl) ?: return null


        // Some player APIs return JSON strings with escaped slashes instead of raw HTML.
        val payload = pageHtml.replace("\\/", "/")
            .replace("\\u002F", "/", true)
            .replace("&amp;", "&")
        if (depth <= 1) {
            val trimmed = payload.trimStart()
            val json = if (trimmed.startsWith("{") || trimmed.startsWith("["))
                runCatching { JSONTokener(trimmed).nextValue() }.getOrNull() else null
            val keys = if (json is JSONObject) json.keys().asSequence().take(12).joinToString(",") else ""
            Trace.event("PLAYER_PAYLOAD", if (json != null) "JSON" else "HTML",
                "host=${URL(pageUrl).host} depth=$depth chars=${payload.length} keys=$keys " +
                    "iframeTags=${Regex("(?i)<iframe\\b").findAll(payload).count()} " +
                    "scriptTags=${Regex("(?i)<script\\b").findAll(payload).count()} " +
                    "hlsMarker=${payload.contains(".m3u8", true)} legacyMarker=${payload.contains("stream_url_nop2p", true)}")
        }

        if (depth <= 1) {
            val iframeTags = Regex("""(?is)<iframe\b[^>]*>""").findAll(payload).take(4).toList()
            iframeTags.forEachIndexed { i, tag ->
                val source = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")
                    .find(tag.value)?.groupValues?.getOrNull(1).orEmpty()
                val resolved = resolveUrl(pageUrl, source)
                val classification = when {
                    source.isBlank() -> "missing_src"
                    resolved == null -> "not_http"
                    else -> "resolved"
                }
                Trace.event("PLAYER_IFRAME", classification.uppercase(),
                    "page=${Trace.target(pageUrl)} index=$i srcChars=${source.length} srcId=${Trace.key(source)} " +
                        "resolved=${Trace.fingerprint(resolved.orEmpty())}")
            }
            val scripts = Regex("""(?is)<script\b([^>]*)>""").findAll(payload).take(12).toList()
            scripts.take(10).forEachIndexed { i, match ->
                val source = Regex("""(?i)\bsrc\s*=\s*["']([^"']+)["']""")
                    .find(match.groupValues[1])?.groupValues?.getOrNull(1).orEmpty()
                if (source.isNotBlank()) {
                    val resolved = resolveUrl(pageUrl, source)
                    Trace.event("PLAYER_SCRIPT_SRC", "OBSERVED",
                        "page=${Trace.target(pageUrl)} index=$i src=${Trace.fingerprint(resolved.orEmpty())}")
                }
            }
        }

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
        Trace.event("PLAYER_NESTED", "FOUND", "parent=" + Trace.target(pageUrl) + " count=" + nested.size)
        for (candidate in nested) {
            currentCoroutineContext().ensureActive()
            crawlChild(candidate, pageUrl, sourceName, depth, visited, attemptedDembed)?.let { return it }
        }
        val isJson = payload.trimStart().let { it.startsWith('{') || it.startsWith('[') }
        Trace.event("PLAYER_PARSE", "NO_LINK",
            "page=${Trace.target(pageUrl)} depth=$depth directHls=${directM3u8Candidates.size} " +
                "nested=${nested.size} json=$isJson")
        return null
    }
    private fun collectNestedCandidates(
        pageUrl: String,
        pageHtml: String
    ): List<String> {
        val result = linkedSetOf<String>()
        val rejected = linkedMapOf<String, Int>()
        var examined = 0
        fun reject(reason: String, address: String = "") {
            rejected[reason] = (rejected[reason] ?: 0) + 1
            if (examined <= 8 && address.isNotEmpty())
                Trace.event("PLAYER_FILTER", reason.uppercase(),
                    "page=${Trace.target(pageUrl)} ${Trace.fingerprint(address)}")
        }

        fun add(raw: String, explicitIframe: Boolean = false) {
            examined++
            val resolved = resolveUrl(pageUrl, raw) ?: run {
                reject("invalid_url")
                return
            }
            if (resolved == pageUrl) { reject("self_link", resolved); return }

            val lower = resolved.lowercase()
            val url = runCatching { URL(resolved) }.getOrNull() ?: return
            val path = url.path.lowercase()
            val extension = path.substringAfterLast('.', "")
            if (extension in setOf("js", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "json", "map", "woff", "woff2", "ttf") ||
                lower.contains("doubleclick") || lower.contains("googlesyndication")) {
                if (url.host.equals("dembed.st", true))
                    Trace.event("PLAYER_FILTER", "REJECT", "target=${Trace.target(resolved)} reason=static_resource")
                reject("static_resource", resolved)
                return
            }
            if (url.host.equals("dembed.st", true) &&
                !dembedPlayerPath.matches(path)) {
                Trace.event("PLAYER_FILTER", "REJECT", "target=${Trace.target(resolved)} reason=not_dembed_player_php")
                reject("non_player_dembed", resolved)
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

            if (likelyPlayer) {
                if (result.add(resolved) && result.size <= 8)
                    Trace.event("PLAYER_FILTER", if (explicitIframe) "IFRAME_ACCEPTED" else "ACCEPTED",
                        "page=${Trace.target(pageUrl)} ${Trace.fingerprint(resolved)}")
            } else reject("not_player_url", resolved)
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
        val rejectedSummary = rejected.entries.joinToString(",") { it.key + ":" + it.value }
        Trace.event("PLAYER_FILTER_SUMMARY", "DONE",
            "page=${Trace.target(pageUrl)} examined=$examined accepted=${result.size} " +
                "returned=${selected.size} rejected=$rejectedSummary")
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
        attemptedDembed: MutableSet<String>
    ): ExtractorLink? {
        if (childUrl in visited) {
            Trace.event("PLAYER_NESTED", "SKIP", "reason=already_visited target=${Trace.target(childUrl)}")
            return null
        }
        Trace.event("PLAYER_NESTED", "TRY", "target=${Trace.target(childUrl)} depth=$depth")
        val parentOrigin = originOf(parentUrl) ?: return null
        if (originOf(childUrl) == "https://dembed.st" && URL(childUrl).path.endsWith(".php", true)) {
            Trace.event("PLAYER_NESTED", "DEMBED_ROUTE", "target=" + Trace.target(childUrl))
            if (attemptedDembed.contains(childUrl)) {
                Trace.event("DEMBED", "SKIP_REPEAT", "target=${Trace.target(childUrl)}")
                return null
            }
            return DaddyLiveDembed.extract(childUrl, parentUrl, sourceName, attemptedDembed)
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
                attemptedDembed = attemptedDembed
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

              // Instrument transport without reading media payloads or modifying return values.
              var transportStats={rtc:0,rtcCandidates:0,rtcDataChannels:0,rtcDataMessages:0,
                rtcDataBytes:0,rtcRemoteCandidates:0,ws:0,wsMessages:0,wsBytes:0,workers:0,workerMessages:0,
                mseSources:0,mseBuffers:0,mseAppends:0,mseBytes:0,mseErrors:0};
              var rtcPeers=[];
              function byteSize(data) {
                try {return data && (data.byteLength || data.size || data.length) || 0;} catch(_) {return 0;}
              }
              function transport(kind,extra) {
                try {var evt={stage:"transport",kind:kind};if(extra)for(var k in extra)evt[k]=extra[k];emit(evt);}catch(_){}
              }
              function traceChannel(channel,owner) {
                if(!channel || channel.__daddyObserved) return;
                try {channel.__daddyObserved=true;}catch(_){}
                transportStats.rtcDataChannels++;
                transport("rtc_channel",{owner:owner,labelLength:String(channel.label||"").length,
                  ordered:channel.ordered,protocolLength:String(channel.protocol||"").length});
                ["open","close","error"].forEach(function(type){
                  channel.addEventListener(type,function(){
                    transport("rtc_channel_"+type,{ready:channel.readyState,id:channel.id});
                  });
                });
                channel.addEventListener("message",function(e){
                  var bytes=byteSize(e.data);transportStats.rtcDataMessages++;
                  transportStats.rtcDataBytes+=bytes;
                  if(transportStats.rtcDataMessages<=2 || transportStats.rtcDataMessages===10)
                    transport("rtc_data",{messages:transportStats.rtcDataMessages,bytes:bytes,
                      totalBytes:transportStats.rtcDataBytes});
                });
              }
              try {
                var NativeRTC=window.RTCPeerConnection||window.webkitRTCPeerConnection;
                if(typeof NativeRTC==="function"){
                  function ProbedRTC(config,constraints){
                    var pc=new NativeRTC(config,constraints);
                    transportStats.rtc++;rtcPeers.push(pc);
                    var id=transportStats.rtc;
                    transport("rtc_create",{id:id,iceServers:config&&config.iceServers?config.iceServers.length:0});
                    ["connectionstatechange","iceconnectionstatechange","icegatheringstatechange",
                      "signalingstatechange","negotiationneeded"].forEach(function(type){
                      pc.addEventListener(type,function(){
                        transport("rtc_state",{id:id,event:type,connection:pc.connectionState,
                          ice:pc.iceConnectionState,gathering:pc.iceGatheringState,signaling:pc.signalingState});
                      });
                    });
                    pc.addEventListener("icecandidate",function(ev){
                      if(ev.candidate){transportStats.rtcCandidates++;
                        if(transportStats.rtcCandidates<=2)
                          transport("rtc_candidate",{id:id,count:transportStats.rtcCandidates});}
                      else transport("rtc_gather_complete",{id:id,count:transportStats.rtcCandidates});
                    });
                    pc.addEventListener("datachannel",function(ev){traceChannel(ev.channel,"remote");});
                    ["createOffer","createAnswer","setLocalDescription","setRemoteDescription",
                      "addIceCandidate"].forEach(function(action){
                      var nativeAction=pc[action];
                      if(typeof nativeAction!=="function")return;
                      pc[action]=function(){
                        var count=action==="addIceCandidate"?++transportStats.rtcRemoteCandidates:1;
                        if(count<=3)transport("rtc_action",{id:id,event:action});
                        var promise=nativeAction.apply(this,arguments);
                        if(promise&&promise.then)promise.then(function(){
                          if(count<=3)transport("rtc_action_ok",{id:id,event:action});
                        },function(e){
                          transport("rtc_action_fail",{id:id,event:action,
                            reason:String(e&&e.name||"error")});
                        });
                        return promise;
                      };
                    });
                    var create=pc.createDataChannel;
                    if(typeof create==="function")pc.createDataChannel=function(){
                      var channel=create.apply(this,arguments);traceChannel(channel,"local");return channel;
                    };
                    return pc;
                  }
                  ProbedRTC.prototype=NativeRTC.prototype;
                  Object.setPrototypeOf(ProbedRTC,NativeRTC);
                  window.RTCPeerConnection=ProbedRTC;
                  if(window.webkitRTCPeerConnection===NativeRTC)window.webkitRTCPeerConnection=ProbedRTC;
                  transport("rtc_hook_ready",{});
                }else transport("rtc_unavailable",{});
              }catch(e){transport("rtc_hook_error",{reason:String(e&&e.name||"error")});}
              try {
                if(typeof window.WebSocket==="function"){
                  var NativeWS=window.WebSocket;
                  function ProbedWS(url,protocols){
                    var socket=arguments.length>1?new NativeWS(url,protocols):new NativeWS(url);
                    transportStats.ws++;
                    var id=transportStats.ws;
                    transport("ws_create",{id:id,url:String(url)});
                    ["open","close","error"].forEach(function(type){
                      socket.addEventListener(type,function(ev){
                        transport("ws_"+type,{id:id,state:socket.readyState,code:type==="close"?ev.code:-1});
                      });
                    });
                    socket.addEventListener("message",function(ev){
                      var bytes=byteSize(ev.data);transportStats.wsMessages++;transportStats.wsBytes+=bytes;
                      if(transportStats.wsMessages<=2||transportStats.wsMessages===10)
                        transport("ws_data",{id:id,count:transportStats.wsMessages,
                          bytes:bytes,totalBytes:transportStats.wsBytes});
                    });
                    return socket;
                  }
                  ProbedWS.prototype=NativeWS.prototype;
                  Object.setPrototypeOf(ProbedWS,NativeWS);
                  window.WebSocket=ProbedWS;
                }
              }catch(e){transport("ws_hook_error",{reason:String(e&&e.name||"error")});}
              try {
                if(typeof window.Worker==="function"){
                  var NativeWorker=window.Worker;
                  function ProbedWorker(url,options){
                    var worker=arguments.length>1?new NativeWorker(url,options):new NativeWorker(url);
                    transportStats.workers++;
                    var id=transportStats.workers;
                    transport("worker_create",{id:id,url:String(url)});
                    worker.addEventListener("error",function(e){
                      transport("worker_error",{id:id,line:e.lineno||0,filename:String(e.filename||"")});
                    });
                    worker.addEventListener("message",function(e){
                      transportStats.workerMessages++;
                      if(transportStats.workerMessages<=2)
                        transport("worker_message",{id:id,count:transportStats.workerMessages,
                          type:typeof e.data,bytes:byteSize(e.data)});
                    });
                    return worker;
                  }
                  ProbedWorker.prototype=NativeWorker.prototype;
                  Object.setPrototypeOf(ProbedWorker,NativeWorker);
                  window.Worker=ProbedWorker;
                }
              }catch(e){transport("worker_hook_error",{reason:String(e&&e.name||"error")});}
              try {
                if(window.URL&&typeof window.URL.createObjectURL==="function"){
                  var originalCreateUrl=window.URL.createObjectURL;
                  window.URL.createObjectURL=function(obj){
                    var result=originalCreateUrl.apply(this,arguments);
                    if(window.MediaSource && obj instanceof window.MediaSource){
                      transportStats.mseSources++;
                      transport("mse_create",{count:transportStats.mseSources});
                      ["sourceopen","sourceended","sourceclose"].forEach(function(type){
                        obj.addEventListener(type,function(){
                          transport("mse_"+type,{ready:obj.readyState});
                        });
                      });
                    }
                    return result;
                  };
                }
                if(window.MediaSource&&window.MediaSource.prototype){
                  var nativeAdd=MediaSource.prototype.addSourceBuffer;
                  if(typeof nativeAdd==="function") MediaSource.prototype.addSourceBuffer=function(type){
                    var buffer=nativeAdd.apply(this,arguments);
                    transportStats.mseBuffers++;
                    transport("mse_buffer",{count:transportStats.mseBuffers,mime:String(type).slice(0,90)});
                    buffer.addEventListener("error",function(){transportStats.mseErrors++;transport("mse_buffer_error",{});});
                    return buffer;
                  };
                }
                if(window.SourceBuffer&&window.SourceBuffer.prototype){
                  var nativeAppend=SourceBuffer.prototype.appendBuffer;
                  if(typeof nativeAppend==="function") SourceBuffer.prototype.appendBuffer=function(data){
                    var bytes=byteSize(data);
                    transportStats.mseAppends++;transportStats.mseBytes+=bytes;
                    if(transportStats.mseAppends<=3||transportStats.mseAppends===10)
                      transport("mse_append",{count:transportStats.mseAppends,bytes:bytes,
                        totalBytes:transportStats.mseBytes});
                    try{return nativeAppend.apply(this,arguments);}
                    catch(e){transportStats.mseErrors++;transport("mse_append_error",
                        {reason:String(e&&e.name||"error")});throw e;}
                  };
                }
              }catch(e){transport("mse_hook_error",{reason:String(e&&e.name||"error")});}
              // Record whether the real player starts media loading at all.
              var mediaHost="", nativeStarts=0, nativeEnds=0, mediaPlayAttempts=0, mediaEvents=0;
              function hostOf(raw) { try {return new URL(raw,location.href).host;}catch(_){return "";} }
              function nativeMedia(raw,method,kind,binary) {
                var p=pathOf(raw);
                return !kind && String(method).toUpperCase()==="GET" &&
                  (!!binary || /^\/premium[^/]*\//.test(p) ||
                    (!!mediaHost && hostOf(raw)===mediaHost && !/^\/api\//.test(p) && !/\.m3u8$/i.test(p)));
              }
              function sampleRtc(reason) {
                for(var i=0;i<Math.min(rtcPeers.length,3);i++){
                  (function(peer,id){
                    try {
                      peer.getStats().then(function(stats){
                        var incoming=0,inPackets=0,pairs=0,succeeded=0,selected=0;
                        stats.forEach(function(s){
                          if(s.type==="inbound-rtp"){incoming+=s.bytesReceived||0;inPackets+=s.packetsReceived||0;}
                          if(s.type==="candidate-pair"){pairs++;if(s.state==="succeeded")succeeded++;
                            if(s.selected || s.nominated && s.state==="succeeded")selected++;}
                        });
                        transport("rtc_stats",{id:id,reason:reason,inboundBytes:incoming,
                          inboundPackets:inPackets,candidatePairs:pairs,succeeded:succeeded,selected:selected});
                      },function(e){transport("rtc_stats_error",{id:id,reason:String(e&&e.name||"error")});});
                    }catch(e){transport("rtc_stats_error",{id:id,reason:String(e&&e.name||"error")});}
                  })(rtcPeers[i],i+1);
                }
              }
              function mediaState(reason) {
                try {
                  var videos=document.querySelectorAll("video,audio"), list=[];
                  for (var i=0;i<Math.min(3,videos.length);i++) {
                    var v=videos[i];
                    list.push({tag:v.tagName,ready:v.readyState,network:v.networkState,paused:v.paused,
                      autoPlay:v.autoplay,muted:v.muted,error:v.error?v.error.code:0,
                      src:v.currentSrc||v.src||"",sourceCount:v.querySelectorAll("source").length});
                  }
                  sampleRtc(reason);
                  var resources=performance.getEntriesByType("resource"), edgeItems=[];
                  if (mediaHost) for(var r=0;r<resources.length && r<500;r++) {
                    if (hostOf(resources[r].name)===mediaHost && edgeItems.length<12)
                      edgeItems.push(resources[r].name);
                  }
                  emit({stage:"runtime_state",reason:String(reason).slice(0,32),
                    ready:document.readyState,visibility:document.visibilityState,hasFocus:document.hasFocus(),
                    videos:videos.length,iframeCount:document.querySelectorAll("iframe").length,
                    hlsConstructor:typeof window.Hls,playerHls:!!(window.hls||window.hlsPlayer),
                    videojs:typeof window.videojs,mediaSource:typeof window.MediaSource,
                    fetchHook:window.fetch===probeFetch,xhrHook:XMLHttpRequest.prototype.send===probeSend,
                    mediaHost:mediaHost,perfResources:resources.length,perfEdge:edgeItems.length,
                    perfSamples:edgeItems.slice(-3),workerSupported:typeof window.Worker,
                    serviceWorkerControlled:!!(navigator.serviceWorker && navigator.serviceWorker.controller),
                    transport:transportStats,rtcStates:rtcPeers.slice(0,3).map(function(p){return {ice:p.iceConnectionState,connection:p.connectionState,gather:p.iceGatheringState};}),
                    nativeStarts:nativeStarts,nativeEnds:nativeEnds,playAttempts:mediaPlayAttempts,events:mediaEvents,
                    list:list});
                } catch(e) {emit({stage:"runtime_error",reason:String(e && e.name||"state_error")});}
              }
              var probeFetch=null,probeSend=null;
              try {
                ["loadstart","play","playing","loadedmetadata","waiting","stalled","error"].forEach(function(event){
                  document.addEventListener(event,function(e){
                    if (!e.target || !/^(VIDEO|AUDIO)$/.test(e.target.tagName) || ++mediaEvents>12) return;
                    emit({stage:"media_event",event:event,ready:e.target.readyState,
                      network:e.target.networkState,error:e.target.error?e.target.error.code:0});
                  },true);
                });
                if (window.HTMLMediaElement && HTMLMediaElement.prototype.play) {
                  var nativePlay=HTMLMediaElement.prototype.play;
                  HTMLMediaElement.prototype.play=function(){
                    mediaPlayAttempts++;
                    var result=nativePlay.apply(this,arguments);
                    if (result && result.then) result.then(null,function(e){
                      emit({stage:"play_rejected",reason:String(e && e.name||"rejected")});
                    });
                    return result;
                  };
                }
                window.addEventListener("load",function(){setTimeout(function(){mediaState("iframe_load");},450);});
              } catch(e) {emit({stage:"runtime_error",reason:String(e && e.name||"media_hooks")});}
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
                  var start = performance.now();
                  var promise = nativeFetch.apply(this, arguments);
                   if (nativeMedia(url,method,kind)) {
                     var opts=init||{}, req=input && input.headers ? input : null;
                     var names="";try{names=headerNames(new Headers(opts.headers||req && req.headers));}catch(_){}
                     var credentials=opts.credentials||req && req.credentials||"default";
                     Promise.resolve(promise).then(function(r) {
                       nativeEnds++;
                       emit({stage:'native_segment',transport:'fetch',method:method,http:r.status,
                         url:r.url||absoluteOf(url),requestUrl:absoluteOf(url),requestHeaders:names,credentials:credentials,
                         contentType:r.headers.get("content-type")||"",contentLength:r.headers.get("content-length")||"",
                         responseType:r.type||"",elapsedMs:Math.round(performance.now()-start)});
                     },function(e){nativeEnds++;
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
                      nativeStarts++; nativeEnds++;
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
                probeFetch=window.fetch;
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
                       nativeEnds++;
                       emit({stage:'native_segment',transport:'xhr',method:entry.method,http:self.status,
                         requestUrl:absoluteOf(entry.url),url:self.responseURL||absoluteOf(entry.url),requestHeaders:entry.headers.join(","),
                         credentials:self.withCredentials?'include':'default',contentType:mime,contentLength:len,
                         responseType:self.responseType||"",bytes:bytes,elapsedMs:Math.round(performance.now()-begun)});
                     });
                   }
                  if (entry && entry.kind) {
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
                probeSend=XMLHttpRequest.prototype.send;
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
                    (cmd.kind !== "manifest" && cmd.kind !== "segment" && cmd.kind !== "diagnostic")) return;
                  if (cmd.kind === "diagnostic") { mediaState(cmd.reason||"requested"); return; }
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
            })();
        """.trimIndent()
        /** Intercept only the embedded player's JavaScript, not its auth or media API.
         * Prepend the response probe before daddy.min.js runs while retaining the script body. */
        private fun instrumentPlayerScript(request: WebResourceRequest, session: Session): WebResourceResponse? {
            val address = request.url.toString()
            val begun = android.os.SystemClock.elapsedRealtime()
            Trace.event("RESPONSE_BRIDGE", "SCRIPT_FETCH",
                "sid=${session.id.take(8)} target=${Trace.target(address)} mode=original_js_with_probe")
            var connection: HttpURLConnection? = null
            return try {
                val active = URL(address).openConnection() as HttpURLConnection
                connection = active
                active.connectTimeout = 4500
                active.readTimeout = 4500
                active.setRequestProperty("Accept-Encoding", "identity")
                request.requestHeaders.forEach { (name, value) ->
                    if (!name.equals("Host", true) && !name.equals("Accept-Encoding", true) &&
                        !name.equals("Content-Length", true) && value.isNotBlank()) {
                        active.setRequestProperty(name, value)
                    }
                }
                if (request.requestHeaders.keys.none { it.equals("Referer", true) }) {
                    active.setRequestProperty("Referer", session.embedded)
                }
                if (request.requestHeaders.keys.none { it.equals("User-Agent", true) }) {
                    active.setRequestProperty("User-Agent", session.userAgent)
                }
                runCatching { CookieManager.getInstance().getCookie(address) }.getOrNull()?.let {
                    active.setRequestProperty("Cookie", it)
                }
                val status = active.responseCode
                if (status != 200 || !active.url.host.equals("dembed.st", true)) {
                    Trace.event("RESPONSE_BRIDGE", "SCRIPT_HTTP_FAIL",
                        "sid=${session.id.take(8)} http=$status redirectedHost=${active.url.host} " +
                            "elapsedMs=${android.os.SystemClock.elapsedRealtime()-begun} fallback=browser_native")
                    return null
                }
                val stream = if (active.contentEncoding.equals("gzip", true)) {
                    GZIPInputStream(active.inputStream)
                } else active.inputStream
                val out = ByteArrayOutputStream()
                stream.use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (out.size() + n > 2 * 1024 * 1024) {
                            Trace.event("RESPONSE_BRIDGE", "SCRIPT_TOO_LARGE",
                                "sid=${session.id.take(8)} fallback=browser_native")
                            return null
                        }
                        out.write(buffer, 0, n)
                    }
                }
                val original = out.toByteArray()
                if (original.isEmpty()) {
                    Trace.event("RESPONSE_BRIDGE", "SCRIPT_EMPTY",
                        "sid=${session.id.take(8)} fallback=browser_native")
                    return null
                }
                val code = String(original, Charsets.UTF_8)
                val markers = listOf("LiveLoader", "fetchPlaylistToken", "MediaSource",
                    "SourceBuffer", "hls.js", "XMLHttpRequest", "createObjectURL", "segment", "atob")
                    .filter { code.contains(it, true) }
                Trace.event("PLAYER_SCRIPT_SHAPE", "OBSERVED",
                    "sid=${session.id.take(8)} bytes=${original.size} markers=${markers.joinToString(",")} " +
                        "scriptHash=${Trace.key(address + ":" + original.size)}")
                val probeBytes = (responseProbe + "\n;\n").toByteArray(Charsets.UTF_8)
                val result = ByteArray(probeBytes.size + original.size)
                System.arraycopy(probeBytes, 0, result, 0, probeBytes.size)
                System.arraycopy(original, 0, result, probeBytes.size, original.size)
                Trace.event("RESPONSE_BRIDGE", "SCRIPT_INJECTED",
                    "sid=${session.id.take(8)} originalBytes=${original.size} " +
                        "probeBytes=${probeBytes.size} elapsedMs=${android.os.SystemClock.elapsedRealtime()-begun}")
                WebResourceResponse(
                    "application/javascript", "UTF-8",
                    200, "OK", mapOf("Cache-Control" to "no-store"),
                    ByteArrayInputStream(result)
                )
            } catch (e: Exception) {
                Trace.event("RESPONSE_BRIDGE", "SCRIPT_ERROR",
                    "sid=${session.id.take(8)} reason=${e.javaClass.simpleName} " +
                        "elapsedMs=${android.os.SystemClock.elapsedRealtime()-begun} fallback=browser_native")
                null
            } finally {
                connection?.disconnect()
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
            val id: String = UUID.randomUUID().toString().replace("-", "")
            val resources = ConcurrentHashMap<String, Resource>()
            private val pendingResources = ConcurrentHashMap<String, CompletableDeferred<BrowserReply?>>()
            val manifestMutex = Mutex()
            val failedSegments = ConcurrentHashMap<String, Long>()
            @Volatile var segmentTransport = "client"
            @Volatile var directAllowed = false
            @Volatile var encryptedMedia = false
            val nativeSegmentCount = AtomicInteger()
            val nativeRequestCount = AtomicInteger()
            val nativeHeaders = ConcurrentHashMap<String, Map<String, String>>()
            val nativeStatuses = ConcurrentHashMap<String, Int>()
            val jsMediaRequests = AtomicInteger()
            val runtimeSnapshots = AtomicInteger()
            val mediaEvents = AtomicInteger()
            val transportEvents = AtomicInteger()
            @Volatile var preflightStarted = false
            @Volatile var segmentTokenMode = false
            @Volatile var tokenProbeDone = false
            @Volatile var allMedia404 = false
            val syntheticSegments = ConcurrentHashMap<String, Boolean>()
            val expectedUrls = ConcurrentHashMap<String, Boolean>()
            val expectedPaths = ConcurrentHashMap<String, Boolean>()
            private val expectedPathCounts = ConcurrentHashMap<String, Int>()
            private data class ObservedMedia(val effective: String, val at: Long)
            private val observedMedia = ConcurrentHashMap<String, ObservedMedia>()
            private val observedExact = ConcurrentHashMap<String, ObservedMedia>()
            fun registerManifestSegments(playlist: String, base: String) {
                expectedUrls.clear()
                expectedPaths.clear()
                expectedPathCounts.clear()
                playlist.lineSequence().map { it.trim() }
                    .filter { it.isNotBlank() && !it.startsWith("#") }
                    .take(50).forEach { part ->
                        runCatching { URL(URL(base), part).toString() }.getOrNull()?.let { address ->
                            if (!URL(address).path.endsWith(".m3u8", true)) {
                                expectedUrls[Trace.key(address)] = true
                                val pathId = Trace.pathKey(address)
                                expectedPaths[pathId] = true
                                expectedPathCounts[pathId] = (expectedPathCounts[pathId] ?: 0) + 1
                            }
                        }
                    }
                Trace.event("MANIFEST_SEGMENTS", "INDEXED",
                    "sid=${id.take(8)} uriCount=${expectedUrls.size} uniquePaths=${expectedPaths.size}")
            }
            fun segmentRelation(address: String): String = when {
                expectedUrls.containsKey(Trace.key(address)) -> "EXACT_URI"
                expectedPaths.containsKey(Trace.pathKey(address)) -> "SAME_PATH_OTHER_QUERY"
                runCatching { URL(address).host == URL(url).host }.getOrDefault(false) -> "SAME_HOST_DIFFERENT_PATH"
                else -> "OTHER_HOST_OR_UNAVAILABLE"
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
                Trace.event("ORIGINAL_REQUEST", "OBSERVED",
                    "sid=${id.take(8)} method=$method relation=${segmentRelation(address)} " +
                        "${Trace.fingerprint(address)} ${Trace.headerProfile(original)}")
            }

            fun recordNativeResponse(address: String, status: Int, method: String, transport: String,
                headerNames: String, mime: String, bytes: String, elapsed: Long
            ) {
                val key = Trace.key(address)
                if (nativeStatuses.size < 24 && (status != 0 || nativeStatuses[key] == null)) {
                    nativeStatuses[key] = status
                }
                if (nativeSegmentCount.incrementAndGet() <= 12) {
                    Trace.event("ORIGINAL_SEGMENT", "RESPONSE",
                        "sid=${id.take(8)} ${Trace.fingerprint(address)} relation=${segmentRelation(address)} method=$method http=$status " +
                            "transport=$transport requestNames=${headerNames.take(160)} mime=${mime.take(75)} " +
                            "bytes=$bytes elapsedMs=$elapsed")
                }
            }

            fun compare(address: String, source: String, http: Int, mediaHeaders: Map<String, String>) {
                val original = nativeStatuses[Trace.key(address)]
                val previous = nativeHeaders[Trace.key(address)]
                val headerDifference = previous?.keys?.map { it.lowercase() }?.toSet()
                    ?.let { it != mediaHeaders.keys.map { key -> key.lowercase() }.toSet() } ?: false
                val result = when {
                    original == null -> "ORIGINAL_NOT_OBSERVED"
                    original in 200..299 && http !in 200..299 -> "ORIGINAL_OK_REPLAY_FAILED"
                    original == 404 && http == 404 -> "BOTH_404"
                    original == 0 && http == 404 -> "CORS_OR_NETWORK_VS_404"
                    original == http -> "SAME_HTTP"
                    else -> "DIFFERENT_HTTP"
                }
                Trace.event("SEGMENT_COMPARE", result,
                    "sid=${id.take(8)} source=$source originalHttp=${original ?: -1} replayHttp=$http " +
                        "headerNameMismatch=$headerDifference ${Trace.fingerprint(address)} " +
                        "original=[${Trace.headerProfile(previous.orEmpty())}] replay=[${Trace.headerProfile(mediaHeaders)}]")
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
            @Volatile var firstPlaylist: String? = null
            @Volatile var lastUse = android.os.SystemClock.elapsedRealtime()
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
                        val signed = runCatching { !Uri.parse(address).getQueryParameter("t").isNullOrBlank() }
                            .getOrDefault(false)
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
                        probeInstalled = true
                        Trace.event("RESPONSE_BRIDGE", "INSTALLED", "sid=${id.take(8)} type=${message.optString("kind")}")
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
                    "transport" -> {
                        val kind = message.optString("kind")
                        val count = transportEvents.incrementAndGet()
                        if (count <= 75 || kind == "rtc_state" || kind == "rtc_stats" ||
                            kind.endsWith("_error") || kind == "rtc_action_fail") {
                            val address = message.optString("url").ifBlank { message.optString("filename") }
                            val safeSource = if (address.isBlank()) "none" else
                                (if (address.startsWith("blob:")) "blob:" else "url:") + Trace.key(address)
                            Trace.event("PLAYER_TRANSPORT", kind.uppercase(),
                                "sid=${id.take(8)} id=${message.optInt("id")} " +
                                    "source=$safeSource iceServers=${message.optInt("iceServers", -1)} " +
                                    "event=${message.optString("event").take(30)} " +
                                    "connection=${message.optString("connection").take(30)} " +
                                    "ice=${message.optString("ice").take(30)} gather=${message.optString("gathering").take(30)} " +
                                    "state=${message.optString("state").take(25)} ready=${message.optString("ready").take(25)} " +
                                    "code=${message.optInt("code", -1)} messages=${message.optInt("messages", -1)} " +
                                    "bytes=${message.optLong("bytes", -1)} totalBytes=${message.optLong("totalBytes", -1)} " +
                                    "count=${message.optInt("count", -1)} inbound=${message.optLong("inboundBytes", -1)} " +
                                    "packets=${message.optLong("inboundPackets", -1)} pairs=${message.optInt("candidatePairs", -1)} " +
                                    "selected=${message.optInt("selected", -1)} " +
                                    "reason=${Trace.error(message.optString("reason"))} " +
                                    "mime=${message.optString("mime").take(80)}")
                        }
                    }
                    "native_start" -> {
                        val count = jsMediaRequests.incrementAndGet()
                        if (count <= 15) Trace.event("ORIGINAL_START", "OBSERVED",
                            "sid=${id.take(8)} transport=${message.optString("transport")} " +
                                "method=${message.optString("method")} " +
                                "relation=${segmentRelation(message.optString("url"))} " +
                                "${Trace.fingerprint(message.optString("url"))} " +
                                "headerNames=${message.optString("headers").take(130)}")
                    }
                    "runtime_state" -> {
                        val count = runtimeSnapshots.incrementAndGet()
                        val videos = message.optJSONArray("list")
                        val details = buildString {
                            if (videos != null) for (i in 0 until minOf(3, videos.length())) {
                                val video = videos.optJSONObject(i) ?: continue
                                val src = video.optString("src")
                                append(" media").append(i).append("=")
                                append(video.optString("tag")).append(":ready").append(video.optInt("ready"))
                                append(":net").append(video.optInt("network"))
                                append(":paused").append(video.optBoolean("paused"))
                                append(":autoplay").append(video.optBoolean("autoPlay"))
                                append(":error").append(video.optInt("error"))
                                append(":srcType").append(if (src.startsWith("blob:")) "blob" else if (src.startsWith("http")) "http" else "other")
                                append(":srcId").append(Trace.key(src))
                                append(":sources").append(video.optInt("sourceCount"))
                            }
                        }
                        if (count <= 8) {
                            val t = message.optJSONObject("transport")
                            val rtc = message.optJSONArray("rtcStates")
                            val rtcState = rtc?.optJSONObject(0)
                            Trace.event("PLAYER_TRANSPORT_SUMMARY", "SNAPSHOT",
                                "sid=${id.take(8)} reason=${message.optString("reason")} " +
                                    "rtc=${t?.optInt("rtc") ?: -1} candidates=${t?.optInt("rtcCandidates") ?: -1} " +
                                    "channels=${t?.optInt("rtcDataChannels") ?: -1} " +
                                    "remoteCandidates=${t?.optInt("rtcRemoteCandidates") ?: -1} " +
                                    "dataMsgs=${t?.optInt("rtcDataMessages") ?: -1} " +
                                    "dataBytes=${t?.optLong("rtcDataBytes") ?: -1} " +
                                    "ws=${t?.optInt("ws") ?: -1} wsMsgs=${t?.optInt("wsMessages") ?: -1} " +
                                    "wsBytes=${t?.optLong("wsBytes") ?: -1} " +
                                    "workers=${t?.optInt("workers") ?: -1} workerMsgs=${t?.optInt("workerMessages") ?: -1} " +
                                    "sources=${t?.optInt("mseSources") ?: -1} " +
                                    "buffers=${t?.optInt("mseBuffers") ?: -1} appends=${t?.optInt("mseAppends") ?: -1} " +
                                    "mseBytes=${t?.optLong("mseBytes") ?: -1} mseErrors=${t?.optInt("mseErrors") ?: -1} " +
                                    "rtcConnection=${rtcState?.optString("connection") ?: "none"} " +
                                    "ice=${rtcState?.optString("ice") ?: "none"}")
                            Trace.event("PLAYER_RUNTIME", "SNAPSHOT",
                            "sid=${id.take(8)} reason=${message.optString("reason")} " +
                                "document=${message.optString("ready")} visibility=${message.optString("visibility")} " +
                                "focused=${message.optBoolean("hasFocus")} media=${message.optInt("videos")} " +
                                "iframes=${message.optInt("iframeCount")} " +
                                "hlsConstructor=${message.optString("hlsConstructor")} playerHls=${message.optBoolean("playerHls")} " +
                                "videojs=${message.optString("videojs")} mediaSource=${message.optString("mediaSource")} " +
                                "hooksFetch=${message.optBoolean("fetchHook")} hooksXhr=${message.optBoolean("xhrHook")} " +
                                "edgeHost=${message.optString("mediaHost")} " +
                                "perfAll=${message.optInt("perfResources")} perfEdge=${message.optInt("perfEdge")} " +
                                "worker=${message.optString("workerSupported")} " +
                                "serviceWorker=${message.optBoolean("serviceWorkerControlled")} " +
                                "perfSamples=${(0 until minOf(3, message.optJSONArray("perfSamples")?.length() ?: 0))
                                    .joinToString(",") { Trace.key(message.optJSONArray("perfSamples")?.optString(it).orEmpty()) }} " +
                                "nativeStart=${message.optInt("nativeStarts")} nativeEnd=${message.optInt("nativeEnds")} " +
                                "playCalls=${message.optInt("playAttempts")} mediaEvents=${message.optInt("events")}$details")
                        }
                    }
                    "media_event", "play_rejected", "runtime_error" -> {
                        val count = mediaEvents.incrementAndGet()
                        if (count <= 15) Trace.event("PLAYER_MEDIA", stage.uppercase(),
                            "sid=${id.take(8)} event=${message.optString("event")} " +
                                "ready=${message.optInt("ready", -1)} network=${message.optInt("network", -1)} " +
                                "error=${message.optInt("error")} reason=${Trace.error(message.optString("reason"))}")
                    }
                    "native_segment" -> {
                        val address = message.optString("requestUrl").ifBlank { message.optString("url") }
                        recordNativeResponse(address, message.optInt("http", 0),
                            message.optString("method", "GET"), message.optString("transport"),
                            message.optString("requestHeaders"), message.optString("contentType"),
                            message.optString("contentLength").ifBlank { message.optString("bytes") },
                            message.optLong("elapsedMs"))
                        val responseAddress = message.optString("url")
                        recordObservedMedia(address, responseAddress, message.optInt("http", 0),
                            message.optString("method", "GET"))
                        if (responseAddress != address && responseAddress.isNotBlank() && nativeSegmentCount.get() <= 12)
                            Trace.event("ORIGINAL_REDIRECT", "CHANGED",
                                "sid=${id.take(8)} request=${Trace.fingerprint(address)} response=${Trace.fingerprint(responseAddress)}")
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
                if (current.isNotBlank()) token = current
                Trace.event("AUTH_TOKEN", if (current.isNotBlank()) "ACQUIRED" else "MISSING",
                    "sid=" + id.take(8) + " tokenPresent=" + current.isNotBlank() + " responseBytes=" + response.size)
            }

            fun captureManifest(request: WebResourceRequest) {
                val address = request.url.toString()
                val t = request.url.getQueryParameter("t")
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


            /** Non-invasive state capture in the existing authenticated iframe. */
            fun snapshot(reason: String) {
                if (!probeInstalled || browser == null) {
                    Trace.event("PLAYER_RUNTIME", "UNAVAILABLE",
                        "sid=${id.take(8)} reason=$reason installed=$probeInstalled browserPresent=${browser != null}")
                    return
                }
                val command = JSONObject().put("daddyProxy", 1).put("kind", "diagnostic")
                    .put("reason", reason).put("id", id).toString()
                val script = """(function(){
                    var cmd=$command,n=0,frames=document.querySelectorAll('iframe');
                    for(var i=0;i<frames.length;i++) {
                        try {
                            if(new URL(frames[i].src).origin==='https://dembed.st') {
                                frames[i].contentWindow.postMessage(cmd,'https://dembed.st');n++;
                            }
                        }catch(e){}
                    }
                    return n;
                })();""".trimIndent()
                main.post {
                    val view = browser
                    if (view == null) {
                        Trace.event("PLAYER_RUNTIME", "NO_BROWSER", "sid=${id.take(8)} reason=$reason")
                    } else runCatching {
                        Trace.event("WEBVIEW_STATE", "CHECK",
                            "sid=${id.take(8)} attached=${view.isAttachedToWindow} " +
                                "parent=${view.parent != null} shown=${view.isShown} " +
                                "size=${view.width}x${view.height} reason=$reason")
                        view.evaluateJavascript(script) { raw ->
                            if (raw?.trim()?.toIntOrNull() == 0)
                                Trace.event("PLAYER_RUNTIME", "NO_IFRAME", "sid=${id.take(8)} reason=$reason")
                        }
                    }.onFailure {
                        Trace.event("PLAYER_RUNTIME", "DISPATCH_ERROR",
                            "sid=${id.take(8)} reason=$reason exception=${it.javaClass.simpleName}")
                    }
                }
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

            fun close() {
                Trace.event("SESSION_SUMMARY", "CLOSED",
                    "sid=${id.take(8)} ticketHttp=$browserTicketHttp tokenHttp=$browserTokenHttp " +
                        "manifestPresent=${browserManifestBody != null} originalRequests=${nativeRequestCount.get()} " +
                        "jsStarts=${jsMediaRequests.get()} originalResponses=${nativeSegmentCount.get()} " +
                        "runtimeSnapshots=${runtimeSnapshots.get()} mediaEvents=${mediaEvents.get()} transportEvents=${transportEvents.get()} " +
                        "preflight=$preflightStarted allMedia404=$allMedia404")
                pendingResources.values.forEach { it.complete(null) }
                pendingResources.clear()
                val player = browser
                browser = null
                main.post { runCatching { player?.stopLoading(); player?.destroy() } }
            }
        }

        fun wrapDirectHls(link: ExtractorLink): ExtractorLink {
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

        fun discardUnused(address: String) {
            val uri = Uri.parse(address)
            if (uri.scheme != "http" || uri.host != "127.0.0.1") return
            uri.pathSegments.firstOrNull()?.let { id -> sessions.remove(id)?.close() }
        }

        suspend fun extract(embedded: String, parent: String, name: String, known404: MutableSet<String>): ExtractorLink? {
            if (!runCatching {
                    val uri = Uri.parse(embedded)
                    uri.scheme == "https" && uri.host == "dembed.st" &&
                        uri.path?.matches(Regex("""^/premiumtv/[a-z0-9_-]+\.php$""", RegexOption.IGNORE_CASE)) == true
                }.getOrDefault(false)) {
                Trace.event("DEMBED", "SKIP", "target=${Trace.target(embedded)} reason=invalid_player_endpoint")
                return null
            }
            // Atomic deduplication across concurrently searched player pages.
            if (!known404.add(embedded)) {
                Trace.event("DEMBED", "SKIP_REPEAT", "target=${Trace.target(embedded)}")
                return null
            }
            val session = Session(parent, embedded)
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
                                override fun onPageFinished(view: WebView?, url: String?) {
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
                                        val nativeSource = session.url.takeIf { it.isNotBlank() }
                                        if (nativeSource != null &&
                                            request?.isForMainFrame != true && !path.endsWith(".m3u8", true) &&
                                            request?.url?.host.equals(Uri.parse(nativeSource).host, true) &&
                                            !session.syntheticSegments.containsKey(Trace.key(target))
                                        ) {
                                            session.recordNativeResponse(target, status, request?.method ?: "GET", "webview",
                                                request?.requestHeaders?.keys?.joinToString(",").orEmpty(),
                                                headers.entries.firstOrNull { it.key.equals("Content-Type", true) }?.value.orEmpty(),
                                                headers.entries.firstOrNull { it.key.equals("Content-Length", true) }?.value.orEmpty(), 0)
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
                                    val address = uri.toString()
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
                            main.postDelayed(timeout, 19000L)
                            val url = if (parent.startsWith("https://") && !parent.contains("dembed.st")) parent else ""
                            if (url.isNotBlank()) {
                                Trace.event("AUTH_WEBVIEW", "LOAD_PARENT", "sid=${session.id.take(8)} target=${Trace.target(url)}")
                                view.loadUrl(url)
                            } else {
                                // The player explicitly requires an iframe (it rejects top-level navigation).
                                val safeUrl = embedded.replace("&", "&amp;").replace('"', '_')
                                val html = """<html><body><iframe src="$safeUrl" style="width:100%;height:100%"></iframe></body></html>"""
                                Trace.event("AUTH_WEBVIEW", "LOAD_EMBED_IFRAME", "sid=${session.id.take(8)} target=${Trace.target(embedded)}")
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
            if (session.probeAvailable) {
                val capturedByBrowser = withTimeoutOrNull(if (session.probeInstalled) 6500L else 3000L) {
                    session.browserManifestReady.await()
                } == true
                Trace.event("RESPONSE_BRIDGE",
                    if (capturedByBrowser) "MANIFEST_CAPTURED" else "MANIFEST_UNAVAILABLE",
                    "sid=${session.id.take(8)} installed=${session.probeInstalled} " +
                        "browserTokenHttp=${session.browserTokenHttp} ticketHttp=${session.browserTicketHttp} " +
                        "browserManifestHttp=${session.manifestHttpError ?: -1} " +
                        "reason=${if (capturedByBrowser) "browser_response_body_ready" else if (!session.probeInstalled) "probe_not_installed" else "no_successful_readable_manifest"}")
            } else {
                Trace.event("RESPONSE_BRIDGE", "SKIPPED",
                    "sid=${session.id.take(8)} reason=document_start_or_web_message_not_available")
            }
            val actual = session.browserManifestUrl.ifBlank { session.url.ifBlank { captured } }
            val signed = !Uri.parse(actual).getQueryParameter("t").isNullOrBlank()
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
                    val playlist = decodePlaylist(body)
                    if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
                        Trace.event("HLS_DECODE", "FAILED", "sid=${session.id.take(8)} reason=invalid_playlist")
                        session.close()
                        null
                    } else {
                        Trace.event("HLS_DECODE", "OK", "sid=${session.id.take(8)} chars=${playlist.length} encrypted=${!body.trimStart().startsWith("#EXTM3U")}")
                        session.snapshot("before_preflight")
                        if (session.nativeRequestCount.get() == 0 && session.jsMediaRequests.get() == 0) delay(700)
                        if (!probePlayable(session, playlist, actual)) {
                            session.snapshot("after_preflight")
                            delay(280)
                            session.close()
                            null
                        } else if (session.directAllowed && (!signed || playlist.contains("#EXT-X-ENDLIST"))) {
                            Trace.event("MEDIA3_LINK", "DIRECT", "sid=${session.id.take(8)} reason=ordinary_hls elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                            // Probe a media segment before bypassing the wrapper decoder.
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
                                session.firstPlaylist = playlist
                                session.url = actual
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
            } catch (e: Exception) {
                Trace.event("DEMBED", "ERROR", "sid=${session.id.take(8)} reason=${e.javaClass.simpleName} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                session.close()
                null
            }
        }

        /** Log structure, not signed URLs or segment tokens. */
        private fun tracePlaylistShape(manifest: String, base: String, sid: String) {
            val lines = manifest.lineSequence().map { it.trim() }.toList()
            val media = lines.filter { it.isNotBlank() && !it.startsWith("#") }
            val hlsKeys = listOf("#EXT-X-KEY", "#EXT-X-MAP", "#EXT-X-BYTERANGE",
                "#EXT-X-STREAM-INF", "#EXT-X-MEDIA-SEQUENCE", "#EXT-X-ENDLIST")
            val flags = hlsKeys.filter { tag -> lines.any { it.startsWith(tag) } }.joinToString(",")
            val sequence = lines.firstOrNull { it.startsWith("#EXT-X-MEDIA-SEQUENCE:") }
                ?.substringAfter(':')?.toLongOrNull()
            val targetDuration = lines.firstOrNull { it.startsWith("#EXT-X-TARGETDURATION:") }
                ?.substringAfter(':')?.toIntOrNull()
            val segments = lines.count { it.startsWith("#EXTINF:") }
            val discontinuities = lines.count { it.startsWith("#EXT-X-DISCONTINUITY") }
            Trace.event("MANIFEST_SHAPE", "OBSERVED",
                "sid=$sid bytes=${manifest.length} entries=${media.size} extinf=$segments " +
                    "targetDuration=${targetDuration ?: -1} discontinuities=$discontinuities " +
                    "sequence=${sequence ?: -1} flags=$flags " +
                    "base=${Trace.fingerprint(base)}")
            media.takeLast(3).forEachIndexed { i, entry ->
                val resolved = absolute(base, entry)
                val uri = runCatching { URL(resolved ?: "") }.getOrNull()
                val path = uri?.path.orEmpty()
                val extension = path.substringAfterLast('/').substringAfterLast('.', "none").take(10)
                Trace.event("SEGMENT_URI", if (resolved == null) "UNRESOLVED" else "RESOLVED",
                    "sid=$sid index=$i rawLen=${entry.length} rawAbsolute=${entry.startsWith("http")} " +
                        "percentEscapes=${entry.count { it == '%' }} containsQuery=${entry.contains('?')} " +
                        "resolvedLen=${resolved?.length ?: 0} pathLen=${path.length} pathParts=${path.count { it == '/' }} " +
                        "suffix=$extension rawId=${Trace.key(entry)} resolved=${Trace.fingerprint(resolved.orEmpty())}")
            }
        }

        /** A playlist alone is not a playable link: verify newest media segment bytes first. */
        private suspend fun probePlayable(session: Session, playlist: String, manifestUrl: String): Boolean {
            session.preflightStarted = true
            session.encryptedMedia = playlist.contains("#EXT-X-KEY", true)
            tracePlaylistShape(playlist, manifestUrl, session.id.take(8))
            session.registerManifestSegments(playlist, manifestUrl)
            Trace.event("ORIGINAL_MEDIA", if (session.nativeRequestCount.get() == 0 && session.nativeSegmentCount.get() == 0)
                "NOT_OBSERVED_BEFORE_PREFLIGHT" else "OBSERVED",
                "sid=${session.id.take(8)} nativeRequests=${session.nativeRequestCount.get()} " +
                    "nativeResponses=${session.nativeSegmentCount.get()} note=observation_not_playback_proof")
            val available = playlist.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { absolute(manifestUrl, it) }
                .filterNot { URL(it).path.endsWith(".m3u8", true) }
                .toList().asReversed()
            val verified = available.filter { session.hasVerifiedAlternative(it) }
            val targets = (verified + available.take(3)).distinct().take(3)
            Trace.event("SEGMENT_PREFLIGHT", "SELECTED",
                "sid=${session.id.take(8)} verifiedCandidates=${verified.size} count=${targets.size}")
            if (targets.isEmpty()) {
                Trace.event("SEGMENT_PREFLIGHT", "FAILED", "sid=" + session.id.take(8) + " reason=no_media_entries")
                return false
            }
            var all404 = true
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
                val prefix = ByteArray(576)
                val n = try { client?.takeIf { it.isSuccessful }?.body?.byteStream()?.use { readMediaPrefix(it, prefix) } ?: 0 } catch (_: Exception) { 0 }
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
                session.compare(target, "client_preflight", upstream, forwarded)
                if (client?.code != 404) all404 = false
                if (client?.isSuccessful == true && validMediaPrefix(prefix, n, playlist)) {
                    session.segmentTransport = "client"; session.directAllowed = true
                    Trace.event("SEGMENT_PREFLIGHT", "OK", "sid=" + session.id.take(8) + " source=client entry=" + index + " http=" + client.code + " bytes=" + n)
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
                    return true
                }
                session.compare(target, "browser_preflight", browser?.status ?: -1, forwarded)
                Trace.event("SEGMENT_PREFLIGHT", "BROWSER_FAIL", "sid=" + session.id.take(8) + " entry=" + index +
                    " http=" + (browser?.status ?: -1) + " bytes=" + (chunk?.size ?: 0) + " target=" + Trace.target(target))
            }
            session.allMedia404 = all404 && targets.isNotEmpty()
            Trace.event("SEGMENT_PREFLIGHT", "FAILED",
                "sid=" + session.id.take(8) + " reason=all_candidates_failed count=" + targets.size + " allHttp404=" + session.allMedia404)
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
                        direct404Evidence(session, address, resource.manifest, code, range)
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
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        if (read == 0) continue
                        if (length == null)
                            output.write("${read.toString(16)}\r\n".toByteArray(StandardCharsets.US_ASCII))
                        output.write(buffer, 0, read)
                        if (length == null) output.write("\r\n".toByteArray(StandardCharsets.US_ASCII))
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

        private fun decodePlaylist(body: String): String? =
            body.takeIf { it.trimStart().startsWith("#EXTM3U") }

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
                    decodePlaylist(cached)?.let {
                        Trace.event("HLS_REFRESH", "BROWSER_CACHE",
                            "sid=${session.id.take(8)} ageMs=$cacheAge chars=${it.length}")
                        return session.browserManifestUrl to it
                    }
                }
                val browser = session.browserRequest("manifest", session.signed(source))
                if (browser != null && browser.status in 200..299 && !browser.body.isNullOrBlank()) {
                    val decoded = decodePlaylist(browser.body)
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
                    decodePlaylist(cached)?.let {
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
                if (response?.isSuccessful == true) {
                    val decoded = decodePlaylist(response.text)
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
                                val manifest = if (first != null) {
                                    session.firstPlaylist = null
                                    original to first
                                } else loadManifest(session, resource)
                                if (manifest != null) {
                                    session.registerManifestSegments(manifest.second, manifest.first)
                                    data = rewrite(manifest.second, manifest.first, session).toByteArray()
                                    type = "application/vnd.apple.mpegurl"
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
                                        session.compare(resource.url, "client_proxy", clientHttp, session.mediaHeaders(resource.url))
                                        if (response?.isSuccessful == true) bytes = response.body.bytes()
                                    }
                                    if (bytes == null && browser == null) {
                                        browser = session.browserRequest("segment", actual)
                                        bytes = browser?.takeIf { it.status in 200..299 }?.bytes
                                        session.compare(resource.url, "browser_proxy", browser?.status ?: -1, session.mediaHeaders(resource.url))
                                    }
                                    if (bytes != null) {
                                        data = bytes.takeIf { validMediaPrefix(it, it.size, if (session.encryptedMedia) "#EXT-X-KEY" else "") }
                                        type = if (data?.firstOrNull() == 0x47.toByte()) "video/mp2t"
                                            else "application/octet-stream"
                                        Trace.event("PROXY_SEGMENT", if (data != null) "OK" else "DECODE_FAIL",
                                            "sid=${session.id.take(8)} source=${if (browser?.bytes != null) "browser" else "client"} " +
                                                "in=${bytes.size} out=${data?.size ?: 0} type=$type " +
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
                                                    "clientHttp=$clientHttp elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
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
