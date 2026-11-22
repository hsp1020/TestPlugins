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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

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
        fun bodyReason(text: String): String {
            val body = text.take(512).lowercase()
            return when {
                "turnstile" in body || "captcha" in body -> "challenge_required"
                "expired" in body -> "expired"
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

        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()

            val sourceName = "${selected.channelName} - ${candidate.player}"
            Trace.event("PLAYER_CANDIDATE", "TRY", "player=${candidate.player} target=${Trace.target(candidate.url)}")

            val link = extractVideo(
                url = candidate.url,
                sourceName = sourceName,
                parentReferer = entryUrl
            )

            if (link != null) {
                Trace.event("MEDIA3_LINK", "EMITTED", "player=${candidate.player} mode=${if (link.url.startsWith("http://127.0.0.1:")) "proxy" else "direct"} target=${Trace.target(link.url)}")
                callback(link)
                return
            }
            Trace.event("PLAYER_CANDIDATE", "FALLBACK", "player=${candidate.player} reason=no_valid_link")
        }
        Trace.event("PLAYER_DISCOVERY", "EXHAUSTED", "candidates=${candidates.size} reason=all_failed")
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
        parentReferer: String?
    ): ExtractorLink? {
        try {
            currentCoroutineContext().ensureActive()


            val targetOrigin = originOf(url) ?: mainUrl
            Trace.event("PLAYER_PAGE", "START", "target=${Trace.target(url)}")
            if (targetOrigin == "https://dembed.st" && URL(url).path.endsWith(".php", true)) {
                Trace.event("PLAYER_PAGE", "DEMBED_ROUTE", "target=" + Trace.target(url))
                return DaddyLiveDembed.extract(url, parentReferer.orEmpty(), sourceName)
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
                visited = linkedSetOf()
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
        visited: MutableSet<String>
    ): ExtractorLink? {
        if (depth > 4) return null
        if (!visited.add(pageUrl)) return null

        currentCoroutineContext().ensureActive()

        val pageOrigin = originOf(pageUrl) ?: return null


        // 2) HTML/JS에 signed m3u8 URL이 직접 노출된 경우.
        val directM3u8Candidates = Regex(
            """(?i)https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?"""
        ).findAll(pageHtml)
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

        val nested = collectNestedCandidates(pageUrl, pageHtml)
        Trace.event("PLAYER_NESTED", "FOUND", "parent=" + Trace.target(pageUrl) + " count=" + nested.size)
        for (candidate in nested) {
            currentCoroutineContext().ensureActive()
            crawlChild(candidate, pageUrl, sourceName, depth, visited)?.let { return it }
        }
        return null
    }
    private fun collectNestedCandidates(
        pageUrl: String,
        pageHtml: String
    ): List<String> {
        val result = linkedSetOf<String>()

        fun add(raw: String) {
            val resolved = resolveUrl(pageUrl, raw) ?: return
            if (resolved == pageUrl) return

            val lower = resolved.lowercase()
            val url = runCatching { URL(resolved) }.getOrNull() ?: return
            val path = url.path.lowercase()
            val extension = path.substringAfterLast('.', "")
            if (extension in setOf("js", "css", "png", "jpg", "jpeg", "gif", "svg", "webp", "ico", "json", "map", "woff", "woff2", "ttf") ||
                lower.contains("doubleclick") || lower.contains("googlesyndication")) {
                if (url.host.equals("dembed.st", true))
                    Trace.event("PLAYER_FILTER", "REJECT", "target=${Trace.target(resolved)} reason=static_resource")
                return
            }
            if (url.host.equals("dembed.st", true) &&
                !dembedPlayerPath.matches(path)) {
                Trace.event("PLAYER_FILTER", "REJECT", "target=${Trace.target(resolved)} reason=not_dembed_player_php")
                return
            }

            val likelyPlayer =
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

            if (likelyPlayer) result += resolved
        }

        // 실제 iframe src.
        Regex(
            """(?is)<iframe\b[^>]*?\bsrc\s*=\s*(?:["']([^"']+)["']|([^\s>]+))"""
        ).findAll(pageHtml).forEach { match ->
            add(match.groupValues[1].ifEmpty { match.groupValues[2] })
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

        return result
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
        visited: MutableSet<String>
    ): ExtractorLink? {
        if (childUrl in visited) {
            Trace.event("PLAYER_NESTED", "SKIP", "reason=already_visited target=${Trace.target(childUrl)}")
            return null
        }
        Trace.event("PLAYER_NESTED", "TRY", "target=${Trace.target(childUrl)} depth=$depth")
        val parentOrigin = originOf(parentUrl) ?: return null
        if (originOf(childUrl) == "https://dembed.st" && URL(childUrl).path.endsWith(".php", true)) {
            Trace.event("PLAYER_NESTED", "DEMBED_ROUTE", "target=" + Trace.target(childUrl))
            return DaddyLiveDembed.extract(childUrl, parentUrl, sourceName)
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
                visited = visited
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
              if (typeof window.fetch === "function") {
                var nativeFetch = window.fetch;
                window.fetch = function(input, init) {
                  var url = typeof input === "string" ? input : input && input.url || "";
                  var method = init && init.method || input && input.method || "GET";
                  var kind = kindOf(url, method);
                  var start = performance.now();
                  var promise = nativeFetch.apply(this, arguments);
                   if (!kind && String(method).toUpperCase()==='GET' && /^\/premium[^/]*\//.test(pathOf(url))) {
                     var opts=init||{}, req=input && input.headers ? input : null;
                     var names="";try{names=headerNames(new Headers(opts.headers||req && req.headers));}catch(_){}
                     var credentials=opts.credentials||req && req.credentials||"default";
                     Promise.resolve(promise).then(function(r) {
                       emit({stage:'native_segment',transport:'fetch',method:method,http:r.status,
                         url:r.url||absoluteOf(url),requestUrl:absoluteOf(url),requestHeaders:names,credentials:credentials,
                         contentType:r.headers.get("content-type")||"",contentLength:r.headers.get("content-length")||"",
                         responseType:r.type||"",elapsedMs:Math.round(performance.now()-start)});
                     },function(e){emit({stage:'native_segment',transport:'fetch',method:method,http:0,
                         url:absoluteOf(url),requestUrl:absoluteOf(url),requestHeaders:names,credentials:credentials,
                         error:String(e && e.name||"network_error"),elapsedMs:Math.round(performance.now()-start)});});
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
                  this.__daddyProbe = {kind:kindOf(url,method),url:String(url),method:String(method),headers:[]};
                  return open.apply(this,arguments);
                };
                XMLHttpRequest.prototype.send = function() {
                  var self=this, entry=this.__daddyProbe;
                   if (entry && !entry.kind && !self.__daddySynthetic && /^\/premium[^/]*\//.test(pathOf(entry.url))) {
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
        private val io = CoroutineScope(Dispatchers.IO)
        private val attribute = Regex("""URI="([^"]+)"""")
        private var server: ServerSocket? = null
        @Volatile private var port = 0

        private class Resource(val url: String, val playlist: Boolean)
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
            @Volatile var preflightStarted = false

            fun observeNativeRequest(address: String, method: String, original: Map<String, String>) {
                if (nativeRequestCount.incrementAndGet() > 12) return
                nativeHeaders[Trace.key(address)] = original.toMap()
                Trace.event("ORIGINAL_REQUEST", "OBSERVED",
                    "sid=${id.take(8)} method=$method ${Trace.fingerprint(address)} ${Trace.headerProfile(original)}")
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
                        "sid=${id.take(8)} ${Trace.fingerprint(address)} method=$method http=$status " +
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
                    "native_segment" -> {
                        val address = message.optString("requestUrl").ifBlank { message.optString("url") }
                        recordNativeResponse(address, message.optInt("http", 0),
                            message.optString("method", "GET"), message.optString("transport"),
                            message.optString("requestHeaders"), message.optString("contentType"),
                            message.optString("contentLength").ifBlank { message.optString("bytes") },
                            message.optLong("elapsedMs"))
                        val responseAddress = message.optString("url")
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
                val key = UUID.nameUUIDFromBytes(target.toByteArray(Charsets.UTF_8)).toString().replace("-", "")
                resources.putIfAbsent(key, Resource(target, manifest))
                return "http://127.0.0.1:$port/$id/$key"
            }

            fun close() {
                pendingResources.values.forEach { it.complete(null) }
                pendingResources.clear()
                val player = browser
                browser = null
                main.post { runCatching { player?.stopLoading(); player?.destroy() } }
            }
        }

        suspend fun extract(embedded: String, parent: String, name: String): ExtractorLink? {
            if (!runCatching {
                    val uri = Uri.parse(embedded)
                    uri.scheme == "https" && uri.host == "dembed.st" &&
                        uri.path?.matches(Regex("""^/premiumtv/[a-z0-9_-]+\.php$""", RegexOption.IGNORE_CASE)) == true
                }.getOrDefault(false)) {
                Trace.event("DEMBED", "SKIP", "target=${Trace.target(embedded)} reason=invalid_player_endpoint")
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
                            Trace.event("AUTH_WEBVIEW", "CREATED", "sid=${session.id.take(8)}")
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
                                    } else if (request?.isForMainFrame == true || path.contains("/premium")) {
                                        val nativeSource = session.url.takeIf { it.isNotBlank() }
                                        if (!session.preflightStarted && nativeSource != null &&
                                            request?.isForMainFrame != true && !path.endsWith(".m3u8", true) &&
                                            request?.url?.host.equals(Uri.parse(nativeSource).host, true) &&
                                            path.startsWith(Uri.parse(nativeSource).path.orEmpty().substringBeforeLast('/') + "/")
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
                                        path.startsWith(Uri.parse(source).path.orEmpty().substringBeforeLast('/') + "/") &&
                                        !path.endsWith(".m3u8", true) &&
                                        request.method.equals("GET", true)
                                    ) {
                                        if (!session.preflightStarted)
                                            session.observeNativeRequest(address, request.method, request.requestHeaders)
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
                        if (!probePlayable(session, playlist, actual)) {
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

        /** A playlist alone is not a playable link: verify newest media segment bytes first. */
        private suspend fun probePlayable(session: Session, playlist: String, manifestUrl: String): Boolean {
            session.preflightStarted = true
            session.encryptedMedia = playlist.contains("#EXT-X-KEY", true)
            val targets = playlist.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { absolute(manifestUrl, it) }
                .filterNot { URL(it).path.endsWith(".m3u8", true) }
                .toList().takeLast(3).asReversed()
            if (targets.isEmpty()) {
                Trace.event("SEGMENT_PREFLIGHT", "FAILED", "sid=" + session.id.take(8) + " reason=no_media_entries")
                return false
            }
            for ((index, target) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val forwarded = session.mediaHeaders(target)
                Trace.event("SEGMENT_ATTEMPT", "CLIENT",
                    "sid=${session.id.take(8)} entry=$index ${Trace.fingerprint(target)} ${Trace.headerProfile(forwarded)}")
                val client = try {
                    app.get(target, headers = forwarded, timeout = 4)
                } catch (e: CancellationException) { throw e } catch (_: Exception) { null }
                val prefix = ByteArray(576)
                val n = try { client?.takeIf { it.isSuccessful }?.body?.byteStream()?.use { readMediaPrefix(it, prefix) } ?: 0 } catch (_: Exception) { 0 }
                session.compare(target, "client_preflight", client?.code ?: -1, forwarded)
                if (client?.isSuccessful == true && validMediaPrefix(prefix, n, playlist)) {
                    session.segmentTransport = "client"; session.directAllowed = true
                    Trace.event("SEGMENT_PREFLIGHT", "OK", "sid=" + session.id.take(8) + " source=client entry=" + index + " http=" + client.code + " bytes=" + n)
                    return true
                }
                Trace.event("SEGMENT_PREFLIGHT", "CLIENT_FAIL",
                    "sid=${session.id.take(8)} entry=$index http=${client?.code ?: -1} bytes=$n " +
                        "mime=${client?.headers?.get("Content-Type")?.substringBefore(';').orEmpty().take(60)} " +
                        "contentLength=${client?.headers?.get("Content-Length").orEmpty().take(15)}")
                val browser = session.browserRequest("segment", target)
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
            Trace.event("SEGMENT_PREFLIGHT", "FAILED", "sid=" + session.id.take(8) + " reason=all_candidates_failed count=" + targets.size)
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
                        if (sessions.isEmpty()) {
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
                    val request = BufferedReader(InputStreamReader(socket.getInputStream())).readLine().orEmpty()
                    val fields = request.split(' ')
                    val path = fields.getOrNull(1)?.substringBefore('?').orEmpty()
                    val parts = path.trimStart('/').split('/')
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
                                    var browser = if (session.segmentTransport == "browser")
                                        session.browserRequest("segment", resource.url) else null
                                    var bytes = browser?.takeIf { it.status in 200..299 }?.bytes
                                    var clientHttp = -1
                                    if (bytes == null) {
                                        val response = runCatching {
                                            app.get(resource.url, headers = session.mediaHeaders(resource.url), timeout = 8)
                                        }.getOrNull()
                                        clientHttp = response?.code ?: -1
                                        session.compare(resource.url, "client_proxy", clientHttp, session.mediaHeaders(resource.url))
                                        if (response?.isSuccessful == true) bytes = response.body.bytes()
                                    }
                                    if (bytes == null && browser == null) {
                                        browser = session.browserRequest("segment", resource.url)
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
