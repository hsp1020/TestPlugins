package com.hsp1020

import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.WebChromeClient
import android.webkit.JavascriptInterface
import java.net.HttpURLConnection
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
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
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume
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
import org.json.JSONTokener
import org.json.JSONArray

class DaddyLiveExtractor : ExtractorApi() {
    companion object {
        internal fun releaseSessionsOnPlayerExit(reason: String) =
            DaddyLiveDembed.releaseAllSessions(reason)
    }

    private object UrlKey {
        private const val HEX = "0123456789abcdef"
        private fun digest(raw: String): String = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
            val chars = CharArray(12)
            for (i in 0 until 6) {
                val value = digest[i].toInt() and 255
                chars[i * 2] = HEX[value ushr 4]
                chars[i * 2 + 1] = HEX[value and 15]
            }
            String(chars)
        } catch (_: Exception) { "unknown" }
        fun key(url: String): String = digest(url)
        fun pathKey(url: String): String = runCatching {
            val parsed = URL(url)
            digest(parsed.host + parsed.path)
        }.getOrDefault("invalid")
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
        if (candidates.isEmpty()) return

        // Start stream, player and watch in parallel; then try plus, cast and casting.
        val sharedDembed = ConcurrentHashMap<String, CompletableDeferred<ExtractorLink?>>()
        val workers = minOf(3, candidates.size)
        val next = AtomicInteger(workers)
        val callbackMutex = Mutex()
        val callbackContext = currentCoroutineContext()
        // A shared Dembed result can arrive through stream and watch together.
        // Publish each actual URL once, favoring stream regardless of completion order.
        val publishedUrls = HashSet<String>()
        var pendingWatch: Pair<String, ExtractorLink>? = null
        var streamCompleted = false
        supervisorScope {
            repeat(workers) { slot ->
                launch(Dispatchers.IO) {
                    var index = slot
                    while (index < candidates.size) {
                        val candidate = candidates[index]
                        try {
                            val sourceName = "${selected.channelName} - ${candidate.player}"
                            val link = withTimeoutOrNull(35000L) {
                                extractVideo(candidate.url, sourceName, entryUrl, sharedDembed)
                            }
                            if (link != null) {
                                val original = if (candidate.player == "player")
                                    DaddyLiveDembed.wrapDirectHls(link) else link
                                // Preserve each candidate's display name even when a
                                // shared Dembed extraction was initiated by another candidate.
                                val playing = newExtractorLink(
                                    candidate.player, sourceName,
                                    if (original.url.startsWith("http://127.0.0.1:"))
                                        Uri.parse(original.url).buildUpon()
                                            .appendQueryParameter("source", candidate.player).build().toString()
                                    else original.url,
                                    original.type
                                ) {
                                    this.referer = original.referer
                                    quality = original.quality
                                    headers = original.headers
                                    extractorData = original.extractorData
                                    audioTracks = original.audioTracks
                                }
                                callbackMutex.lock()
                                try {
                                    val canonical = original.url
                                    if (candidate.player == "watch" && !streamCompleted) {
                                        pendingWatch = canonical to playing
                                    } else if (publishedUrls.add(canonical)) {
                                        withContext(callbackContext) { callback(playing) }
                                    }
                                } finally {
                                    callbackMutex.unlock()
                                }
                            }
                        } catch (cancel: CancellationException) {
                            throw cancel
                        } catch (_: Exception) {
                        } finally {
                            if (candidate.player == "stream") {
                                callbackMutex.lock()
                                try {
                                    streamCompleted = true
                                    pendingWatch?.let { (canonical, link) ->
                                        if (publishedUrls.add(canonical)) {
                                            withContext(callbackContext) { callback(link) }
                                        }
                                    }
                                    pendingWatch = null
                                } finally {
                                    callbackMutex.unlock()
                                }
                            }
                        }
                        index = next.getAndIncrement()
                    }
                }
            }
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
            if (targetOrigin == "https://dembed.st" && URL(url).path.endsWith(".php", true)) {
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

            return link
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            return null
        } catch (e: IOException) {
            return null
        } catch (e: Exception) {
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
                return false
            }
            val body = response.text.trimStart()
            val valid = body.startsWith("#EXTM3U") && probeDirectSegments(url, body, headers)
            valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
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
            return null
        }
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
            return null
        }
        val parentOrigin = originOf(parentUrl) ?: return null
        if (originOf(childUrl) == "https://dembed.st" && URL(childUrl).path.endsWith(".php", true)) {
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
                return null
            }
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
            null
        }
    }

    private object DaddyLiveDembed {
        private const val EMBED_ORIGIN = "https://dembed.st"
        private val adDomains = arrayOf("profitableratecpmnetwork.com",
            "histats.com", "tynt.com", "dtscout.com", "dtscdn.com", "xadsmart.com")
        private val excludedHeaders = setOf("host", "connection", "content-length",
            "accept-encoding", "range")

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
                    if (nativeStarts<=12) emit({stage:"native_start"});
                  }
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
                        emit({stage:"native_start"});
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
                     if(nativeStarts<=12) emit({stage:"native_start"});
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
                      } catch (_) {}
                    }
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
            url: String, requested: Map<String, String>, session: Session
        ): ByteArray? {
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
                if (code != 200 || !active.url.host.equals("dembed.st", true)) {
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
                            return null
                        }
                        out.write(buffer, 0, n)
                    }
                }
                val bytes = out.toByteArray().takeIf { it.isNotEmpty() } ?: return null
                bytes
            } catch (e: Exception) {
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
            return try {
                val script = if (result == "hit") {
                    cached!!.body
                } else fetchPlayerScript(address, request.requestHeaders, session) ?: return null
                val probe = responseProbeBytes
                val bytes = ByteArray(probe.size + script.size)
                System.arraycopy(probe, 0, bytes, 0, probe.size)
                System.arraycopy(script, 0, bytes, probe.size, script.size)
                WebResourceResponse("application/javascript", "UTF-8", 200, "OK",
                    mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(bytes))
            } catch (e: Exception) {
                null
            }
        }

        private val main = Handler(Looper.getMainLooper())
        private val sessions = ConcurrentHashMap<String, Session>()
        private val directSessions = ConcurrentHashMap<String, DirectSession>()
        private data class SessionRecipe(val embedded: String, val parent: String, val name: String)
        private data class DirectRecipe(val url: String, val headers: Map<String, String>)
        private val sessionRecipes = ConcurrentHashMap<String, SessionRecipe>()
        private val directRecipes = ConcurrentHashMap<String, DirectRecipe>()
        private val revivalMutex = Mutex()
        private val selectionLock = Any()
        @Volatile private var activeSessionId: String? = null
        @Volatile private var sessionEpoch = 0L
        private const val MAX_SAVED_SOURCES = 128
        private fun <T> remember(cache: ConcurrentHashMap<String, T>, id: String, recipe: T) {
            cache[id] = recipe
            if (cache.size > MAX_SAVED_SOURCES) {
                cache.keys.firstOrNull { it != id && it != activeSessionId }?.let { cache.remove(it) }
            }
        }
        private val io = CoroutineScope(Dispatchers.IO)
        private val attribute = Regex("""URI="([^"]+)"""")
        private var server: ServerSocket? = null
        @Volatile private var port = 0

        private class Resource(val url: String, val playlist: Boolean)
        private class DirectResource(
            val url: String, val playlist: Boolean, val seenAt: Long
        )
        private class DirectSession(
            val url: String, val headers: Map<String, String>,
            val id: String = UUID.randomUUID().toString().replace("-", "")
        ) {
            val resources = ConcurrentHashMap<String, DirectResource>()
            @Volatile var lastUse = android.os.SystemClock.elapsedRealtime()
            fun reference(address: String, playlist: Boolean): String {
                val now = android.os.SystemClock.elapsedRealtime()
                if (resources.size > 256) {
                    resources.forEach { (key, resource) ->
                        if (now - resource.seenAt > 120000L) resources.remove(key, resource)
                    }
                }
                val key = UUID.nameUUIDFromBytes(address.toByteArray(Charsets.UTF_8))
                    .toString().replace("-", "")
                resources[key] = DirectResource(address, playlist, now)
                return "http://127.0.0.1:$port/$id/$key"
            }
        }
        private class BrowserReply(val status: Int, val address: String, val body: String?, val bytes: ByteArray?)
        private class Session(
            val parent: String, val embedded: String,
            val id: String = UUID.randomUUID().toString().replace("-", "")
        ) {
            @Volatile var rejectedSignedManifest = ""
            fun markRejectedManifest(address: String, code: Int) {
                if (code != 404 || browserManifestBody != null || address != url ||
                    Uri.parse(address).getQueryParameter("t").isNullOrBlank()) return
                rejectedSignedManifest = address
                if (!browserManifestReady.isCompleted) browserManifestReady.complete(false)
            }
            @Volatile var prefetchedPlayerScript: PrefetchedPlayerScript? = null
            val resources = ConcurrentHashMap<String, Resource>()
            private val pendingResources = ConcurrentHashMap<String, CompletableDeferred<BrowserReply?>>()
            val manifestMutex = Mutex()
            val failedSegments = ConcurrentHashMap<String, Long>()
            val syntheticSegments = ConcurrentHashMap<String, Boolean>()
            @Volatile var segmentTransport = "client"
            @Volatile var directAllowed = false
            @Volatile var encryptedMedia = false
            val nativeRequestCount = AtomicInteger()
            val nativeHeaders = ConcurrentHashMap<String, Map<String, String>>()
            val jsMediaRequests = AtomicInteger()
            @Volatile var segmentTokenMode = false
            @Volatile var tokenProbeDone = false
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
                                expectedUrls[UrlKey.key(address)] = true
                                val pathId = UrlKey.pathKey(address)
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
                observedExact[UrlKey.key(request)] = entry
                observedMedia[UrlKey.pathKey(request)] = entry
            }

            private fun verifiedSegment(address: String): String? {
                val pathId = UrlKey.pathKey(address)
                val exactKey = UrlKey.key(address)
                val recorded = observedExact[exactKey]?.takeIf { expectedUrls.containsKey(exactKey) }
                    ?: observedMedia[pathId]?.takeIf { expectedPathCounts[pathId] == 1 }
                    ?: return null
                val age = android.os.SystemClock.elapsedRealtime() - recorded.at
                return recorded.effective.takeIf { age in 0..20000L }
            }
            fun hasVerifiedAlternative(address: String): Boolean =
                verifiedSegment(address)?.let { it != address } == true

            fun observeNativeRequest(address: String, method: String, original: Map<String, String>) {
                if (nativeRequestCount.incrementAndGet() > 12) return
                nativeHeaders[UrlKey.key(address)] = original.toMap()
            }

            fun mediaHeaders(address: String): Map<String, String> {
                val original = nativeHeaders[UrlKey.key(address)]
                val base = original ?: headers
                val result = linkedMapOf<String, String>()
                base.forEach { (name, value) ->
                    if (name.lowercase() !in excludedHeaders && value.isNotBlank()) result[name] = value
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
            @Volatile var userAgent = "Mozilla/5.0"
            @Volatile var browserHeaders: Map<String, String> = emptyMap()
            @Volatile var authHttpError: Int? = null
            @Volatile var probeAvailable = false
            @Volatile var probeInstalled = false
            @Volatile var browserManifestBody: String? = null
            @Volatile var browserManifestUrl: String = ""
            @Volatile var browserManifestAt = 0L
            val browserManifestReady = CompletableDeferred<Boolean>()
            val headers: Map<String, String>
                get() = mutableMapOf(
                    "Origin" to EMBED_ORIGIN,
                    "Referer" to embedded,
                    "User-Agent" to userAgent,
                    "Accept" to "*/*"
                ).also { map ->
                    browserHeaders.forEach { (key, value) ->
                        if (key.lowercase() !in excludedHeaders && value.isNotBlank()) {
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
                when (kind) {
                    "token" -> {
                        authHttpError = status.takeIf { it !in 200..299 }
                        if (status in 200..299 && !truncated)
                            onToken(body.toByteArray(Charsets.UTF_8))
                    }
                    "manifest" -> {
                        val signed = !Uri.parse(address).getQueryParameter("t").isNullOrBlank()
                        if (signed) markRejectedManifest(address, status)
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
                    return
                }
                val message = runCatching { JSONObject(raw) }.getOrNull()
                if (message == null) {
                    return
                }
                if (message.optString("origin") != EMBED_ORIGIN) {
                    return
                }
                val stage = message.optString("stage")
                when (stage) {
                    "installed" -> {
                        probeInstalled = true
                    }
                    "response" -> {
                        val kind = message.optString("kind")
                        if (kind !in setOf("token", "manifest", "ticket")) return
                        val target = message.optString("url")
                        val urlPath = runCatching { URL(target).path }.getOrDefault("")
                        if (kind == "token" && urlPath != "/api/playlist-token" ||
                            kind == "manifest" && !urlPath.endsWith(".m3u8", true) ||
                            kind == "ticket" && !urlPath.startsWith("/premiumtv/")) {
                            return
                        }
                        val body = message.optString("body")
                        val status = message.optInt("http", -1)
                        val truncated = message.optBoolean("truncated", false)
                        responseState(kind, status, target, body, truncated)
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

                        request.complete(result)
                    }
                }
            }

            fun onToken(response: ByteArray) {
                val data = runCatching { JSONObject(String(response, Charsets.UTF_8)) }.getOrNull()
                if (data == null) {
                    return
                }
                val current = data.optString("token").ifBlank { data.optString("t") }
                if (current.isNotBlank()) {
                    token = current
                }
                val key = data.optString("k")
                if (current.isNotBlank() && key.isNotBlank()) {
                    val decoded = runCatching { Base64.decode(key, Base64.URL_SAFE or Base64.NO_WRAP) }.getOrNull()
                    if (decoded != null && decoded.size in setOf(16, 24, 32)) {
                        if (playKeys.size >= 4 && !playKeys.containsKey(current)) playKeys.clear()
                        playKeys[current] = decoded
                    }
                }
            }

            fun captureManifest(request: WebResourceRequest) {
                val address = request.url.toString()
                val t = request.url.getQueryParameter("t")
                if (token.isNotBlank() && t.isNullOrBlank() &&
                    Uri.parse(url).getQueryParameter("t")?.isNotBlank() == true) {
                    return
                }
                url = address
                browserHeaders = request.requestHeaders.toMap()
                if (!t.isNullOrBlank()) token = t
            }


            suspend fun browserRequest(kind: String, address: String): BrowserReply? {
                if (!probeInstalled || browser == null) {
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
                    return null
                }
                val requestId = UUID.randomUUID().toString().replace("-", "")
                val result = CompletableDeferred<BrowserReply?>()
                pendingResources[requestId] = result
                if (kind == "segment") syntheticSegments[UrlKey.key(address)] = true
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
                                result.complete(null)
                            }
                        }
                    }.onFailure {
                        result.complete(null)
                    }
                }
                return try {
                    withTimeoutOrNull(if (kind == "segment") 5500L else 7000L) { result.await() }
                } finally {
                    pendingResources.remove(requestId)
                    if (kind == "segment") syntheticSegments.remove(UrlKey.key(address))
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
                return link
            }
            val direct = DirectSession(link.url, link.headers)
            directSessions[direct.id] = direct
            remember(directRecipes, direct.id, DirectRecipe(link.url, link.headers))
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
                return null
            }
            // Share the in-flight result rather than failing the later candidate.
            val pending = CompletableDeferred<ExtractorLink?>()
            val shared = sharedDembed.putIfAbsent(embedded, pending)
            if (shared != null) {
                val link = shared.await()
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

        private suspend fun extractDistinct(
            embedded: String, parent: String, name: String, previousId: String? = null
        ): ExtractorLink? {
            val initialEpoch = sessionEpoch
            val initiallySelected = activeSessionId
            val session = Session(parent, embedded, previousId ?: UUID.randomUUID().toString().replace("-", ""))
            val captured = withTimeoutOrNull(20000L) {
                suspendCancellableCoroutine<String?> { continuation ->
                    var web: WebView? = null
                    val timeout = Runnable {
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
                            view.webChromeClient = WebChromeClient()
                            view.webViewClient = object : WebViewClient() {
                                override fun onReceivedHttpError(
                                    view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?
                                ) {
                                    val target = request?.url?.toString().orEmpty()
                                    val path = request?.url?.path.orEmpty()
                                    val status = errorResponse?.statusCode ?: -1
                                    if (path == "/api/playlist-token") {
                                        session.authHttpError = status
                                    } else if (path.endsWith(".m3u8", true)) {
                                        session.markRejectedManifest(target, status)
                                    }
                                    if (request?.url?.host == "dembed.st" &&
                                        path == Uri.parse(embedded).path && status in 400..599) {
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
                                    // Suppress documented ad/analytics subresources only, not
                                    // player, challenge, media or navigation requests.
                                    val host = uri.host.orEmpty()
                                    if (!request.isForMainFrame && uri.scheme == "https" &&
                                        request.method.equals("GET", true) &&
                                        adDomains.any { host == it || host.endsWith(".$it") }) {
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
                                        if (!session.syntheticSegments.containsKey(UrlKey.key(address)))
                                            session.observeNativeRequest(address, request.method, request.requestHeaders)
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
                            if (scriptUrl != null) io.launch {
                                val cookie = scriptCookie(scriptUrl)
                                val agent = session.userAgent
                                val script = fetchPlayerScript(scriptUrl, emptyMap(), session)
                                if (script != null && session.browser != null)
                                    session.prefetchedPlayerScript = PrefetchedPlayerScript(
                                        scriptUrl, cookie, agent, session.embedded, script,
                                        android.os.SystemClock.elapsedRealtime()
                                    )
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
                        } catch (e: Exception) {
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
            if (session.probeAvailable) {
                val waitStarted = android.os.SystemClock.elapsedRealtime()
                val capturedByBrowser = withTimeoutOrNull(if (session.probeInstalled) 6500L else 3000L) {
                    session.browserManifestReady.await()
                } == true
            } else {
            }
            val actual = session.browserManifestUrl.ifBlank { session.url.ifBlank { captured } }
            val signed = !Uri.parse(actual).getQueryParameter("t").isNullOrBlank()
            if (signed && session.browserManifestBody == null &&
                actual == session.rejectedSignedManifest) {
                session.close()
                return null
            }
            if (session.authHttpError == 403 && !signed) {
                session.close()
                return null
            }
            if (!signed) {
            }
            return try {
                val requestHeaders = session.headers
                val browserBody = session.browserManifestBody
                val response = if (browserBody == null) {
                    app.get(actual, headers = requestHeaders, timeout = 5)
                } else null
                if (response != null) {
                } else {
                }
                val body = browserBody ?: response?.takeIf { it.isSuccessful }?.text
                if (body == null) {
                    session.close()
                    null
                } else {
                    val playlist = decodePlaylist(body, session)
                    if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
                        session.close()
                        null
                    } else {
                        // Sealed AES playlists already contain decrypted media URLs;
                        // retain the browser-observation wait only for plain playlists.
                        val waitForNative = !session.sealedPlaylist &&
                            session.nativeRequestCount.get() == 0 && session.jsMediaRequests.get() == 0
                        if (waitForNative) delay(700)
                        if (!probePlayable(session, playlist, actual)) {
                            delay(280)
                            session.close()
                            null
                        } else if (initialEpoch != sessionEpoch) {
                            session.close()
                            null
                        } else if (previousId == null && session.directAllowed &&
                            (!signed || playlist.contains("#EXT-X-ENDLIST"))) {
                            // Probe a media segment before bypassing the wrapper decoder.
                            session.close()
                            newExtractorLink(name, name, actual, ExtractorLinkType.M3U8) {
                                referer = "$EMBED_ORIGIN/"
                                quality = Qualities.Unknown.value
                                headers = session.headers
                            }
                        } else {
                            val openPort = if (initialEpoch != sessionEpoch ||
                                (previousId != null && activeSessionId != previousId))
                                0 else startServer()
                            if (openPort == 0) {
                                session.close()
                                null
                            } else {
                                session.firstPlaylist = stableInitialPlaylist(playlist, session)
                                session.url = actual
                                if (previousId != null && activeSessionId != previousId) {
                                    session.close()
                                    return null
                                }
                                sessions[session.id] = session
                                if (initialEpoch != sessionEpoch) {
                                    if (sessions.remove(session.id, session)) session.close()
                                    return null
                                }
                                remember(sessionRecipes, session.id, SessionRecipe(embedded, parent, name))
                                if (initiallySelected != activeSessionId &&
                                    activeSessionId != null && activeSessionId != session.id) {
                                    if (sessions.remove(session.id, session)) session.close()
                                }
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
                session.close()
                null
            }
        }

        /** Verify actual media bytes before publishing a playable HLS link. */
        private suspend fun probePlayable(session: Session, playlist: String, manifestUrl: String): Boolean {
            session.encryptedMedia = playlist.contains("#EXT-X-KEY", true)
            session.registerManifestSegments(playlist, manifestUrl)
            val available = playlist.lineSequence().map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .mapNotNull { absolute(manifestUrl, it) }
                .filterNot { URL(it).path.endsWith(".m3u8", true) }
                .toList().asReversed()
            val verified = available.filter { session.hasVerifiedAlternative(it) }
            // The newest entry of a sealed sliding window may still be incomplete.
            val stableLive = session.sealedPlaylist && available.size >= 3 &&
                playlist.contains("#EXT-X-MEDIA-SEQUENCE", true) &&
                playlist.contains("#EXT-X-TARGETDURATION", true) &&
                !playlist.contains("#EXT-X-ENDLIST", true) &&
                !playlist.contains("#EXT-X-KEY", true) &&
                !playlist.contains("#EXT-X-MAP", true)
            val targets = (verified + if (stableLive)
                (available.drop(1).take(2) + available.take(1)) else available.take(3))
                .distinct().take(3)
            if (targets.isEmpty()) return false
            for ((index, target) in targets.withIndex()) {
                currentCoroutineContext().ensureActive()
                val requestUrl = session.actualSegment(target)
                val forwarded = session.mediaHeaders(requestUrl)
                val client = try {
                    app.get(requestUrl, headers = forwarded, timeout = 4)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
                val prefix = ByteArray(576)
                val n = if (session.sealedPlaylist) {
                    val raw = runCatching {
                        client?.takeIf { it.isSuccessful }?.body?.byteStream()?.use { input ->
                            readLimited(input, 12 * 1024 * 1024)
                        }
                    }.getOrNull()
                    val media = raw?.let { runCatching { unwrapMedia(it) }.getOrNull() }
                    val size = minOf(media?.size ?: 0, prefix.size)
                    if (size > 0) media!!.copyInto(prefix, 0, 0, size)
                    size
                } else try {
                    client?.takeIf { it.isSuccessful }?.body?.byteStream()
                        ?.use { readMediaPrefix(it, prefix) } ?: 0
                } catch (_: Exception) { 0 }
                if (client?.isSuccessful == true && validMediaPrefix(prefix, n, playlist)) {
                    if (stableLive && index == 0 && target == available[1])
                        session.skipInitialTail = true
                    session.segmentTransport = "client"
                    session.directAllowed = true
                    return true
                }
                if (client?.code == 404 && requestUrl == target && !session.tokenProbeDone) {
                    session.tokenProbeDone = true
                    val signedSegment = session.segmentWithToken(target)
                    if (signedSegment != null) {
                        val trial = try {
                            app.get(signedSegment, headers = forwarded, timeout = 4)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (_: Exception) { null }
                        val tokenPrefix = ByteArray(576)
                        val tokenBytes = try {
                            trial?.takeIf { it.isSuccessful }?.body?.byteStream()
                                ?.use { readMediaPrefix(it, tokenPrefix) } ?: 0
                        } catch (_: Exception) { 0 }
                        if (trial?.isSuccessful == true &&
                            validMediaPrefix(tokenPrefix, tokenBytes, playlist)) {
                            session.segmentTokenMode = true
                            session.segmentTransport = "client"
                            session.directAllowed = false
                            return true
                        }
                    }
                }
                val browser = session.browserRequest("segment", requestUrl)
                val chunk = browser?.bytes
                if (browser?.status in 200..299 && chunk != null &&
                    validMediaPrefix(chunk, chunk.size, playlist)) {
                    session.segmentTransport = "browser"
                    return true
                }
            }
            return false
        }

        // A root playlist request identifies the selected mirror/episode, not merely a
        // prefetched candidate. Release every nonselected WebView immediately.
        private fun sourceActivated(id: String) {
            synchronized(selectionLock) {
                activeSessionId = id
                sessions.forEach { (other, session) ->
                    if (other != id && sessions.remove(other, session)) session.close()
                }
                directSessions.keys.forEach { other ->
                    if (other != id) directSessions.remove(other)
                }
            }
        }

        // A CloudStream source list can outlive its browser authorization and WebView.
        // Rebuild under its original loopback id so a cached URL remains reachable.
        private suspend fun recoverSession(id: String): Session? {
            val recipe = sessionRecipes[id] ?: return null
            revivalMutex.lock()
            return try {
                sessions[id] ?: run {
                    if (extractDistinct(recipe.embedded, recipe.parent, recipe.name, id) == null)
                        null
                    else sessions[id]
                }
            } finally {
                revivalMutex.unlock()
            }
        }

        fun releaseAllSessions(reason: String) {
            synchronized(selectionLock) {
                activeSessionId = null
                sessionEpoch++
            }
            sessions.forEach { (id, session) ->
                if (sessions.remove(id, session)) session.close()
            }
            directSessions.clear()
            // A player exit must not invalidate the cached localhost links.
            // Unloading the plugin, on the other hand, releases everything.
            if (reason == "plugin_unload") {
                sessionRecipes.clear()
                directRecipes.clear()
                synchronized(this) {
                    runCatching { server?.close() }
                    server = null
                    port = 0
                }
            }
        }

        @Synchronized private fun startServer(): Int {
            if (server?.isClosed == false) {
                return port
            }
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
                            if (now - session.lastUse > 5 * 60 * 1000L && sessions.remove(key, session)) {
                                session.close()
                            }
                        }
                        directSessions.forEach { (key, direct) ->
                            if (now - direct.lastUse > 5 * 60 * 1000L) directSessions.remove(key, direct)
                        }
                        if (sessions.isEmpty() && directSessions.isEmpty() && sessionRecipes.isEmpty() && directRecipes.isEmpty()) {
                            runCatching { socket.close() }
                            synchronized(DaddyLiveDembed) {
                                if (server === socket) { server = null; port = 0 }
                            }
                        }
                    }
                }
                port
            } catch (e: Exception) {
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

        // Proxy validated direct HLS through the existing loopback server.
        private fun rewriteDirect(playlist: String, source: String, session: DirectSession): String =
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
            val upstreamHeaders = if (range != null) session.headers + ("Range" to range) else session.headers
            val response = try {
                app.get(address, headers = upstreamHeaders, timeout = 12)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            val code = response?.code ?: 502
            if (code !in 200..299) {
                val localCode = code.takeIf { it in 400..599 } ?: 502
                val reason = if (localCode == 404) "Not Found" else "Bad Gateway"
                output.write(("HTTP/1.1 $localCode $reason\r\nConnection: close\r\n" +
                    "Cache-Control: no-store\r\nContent-Length: 0\r\n\r\n").toByteArray(StandardCharsets.US_ASCII))
                return
            }
            if (playlist) {
                val raw = response!!.text
                if (!raw.trimStart().startsWith("#EXTM3U")) {
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\nContent-Length: 0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
                    return
                }
                val data = rewriteDirect(raw, address, session).toByteArray(Charsets.UTF_8)
                output.write(("HTTP/1.1 200 OK\r\nContent-Type: application/vnd.apple.mpegurl\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: ${data.size}\r\n\r\n")
                    .toByteArray(StandardCharsets.US_ASCII))
                output.write(data)
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
                    }
                }
                if (length == null) output.write("0\r\n\r\n".toByteArray(StandardCharsets.US_ASCII))
            } catch (e: Exception) {
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
                output
            } catch (e: Exception) {
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
                        return browser.address to decoded
                    }
                }
                // A failed browser request must not trigger repeated 403s through a different
                // HTTP client. Use the last good live manifest briefly while the player recovers.
                if (cached != null && cacheAge in 0..8000L) {
                    decodePlaylist(cached, session)?.let {
                        return session.browserManifestUrl to it
                    }
                }
                if (browser != null && browser.status in 400..599) {
                    return null
                }
                // Only fallback when the browser bridge is unavailable (e.g. unsupported frame).
                val target = session.signed(source)
                val response = runCatching { app.get(target, headers = session.headers, timeout = 5) }.getOrNull()
                if (response?.isSuccessful == true) {
                    val decoded = decodePlaylist(response.text, session)
                    if (decoded != null) {
                        return target to decoded
                    }
                }
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
                    val id = parts.firstOrNull().orEmpty()
                    val root = fields.firstOrNull() == "GET" &&
                        parts.size == 2 && parts[1] == "index.m3u8"
                    val direct = directSessions[id] ?: if (root) directRecipes[id]?.let {
                        DirectSession(it.url, it.headers, id).also { session ->
                            directSessions.putIfAbsent(id, session)
                        }
                    } else null
                    if (direct != null && fields.firstOrNull() == "GET" && parts.size == 2) {
                        if (root) sourceActivated(id)
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
                    if (root && (sessions.containsKey(id) || sessionRecipes.containsKey(id)))
                        sourceActivated(id)
                    val session = sessions[id] ?: if (root) recoverSession(id) else null
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
                                }

                            }
                            resource != null -> {
                                val retryAfter = session.failedSegments[resource.url] ?: 0L
                                if (start < retryAfter) {
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
                                    } else {
                                        val http = clientHttp.takeIf { it in 400..599 }
                                            ?: browser?.status?.takeIf { it != 0 } ?: clientHttp
                                        if (data == null) {
                                            if (http in 400..599) {
                                                if (session.failedSegments.size > 32) session.failedSegments.clear()
                                                session.failedSegments[resource.url] = start + 10000L
                                            }
                                        }
                                    }
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
            } catch (e: Exception) {
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
    if (String(bytes, 4, 4, Charsets.US_ASCII).let { it == "ftyp" || it == "styp" || it == "moof" || it == "moov" }) return true
    return manifest.contains("#EXT-X-KEY", true) && length >= 16
}
