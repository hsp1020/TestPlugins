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
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume
import kotlin.math.ceil
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

class DaddyLiveExtractor : ExtractorApi() {
    private object Trace {
        private const val TAG = "DaddyLiveTrace"
        fun target(raw: String): String = try {
            val parsed = URL(raw)
            parsed.host + parsed.path
        } catch (_: Exception) { "invalid-url" }
        fun event(phase: String, status: String, detail: String = "") {
            android.util.Log.i(TAG, "phase=$phase status=$status $detail")
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
            if (targetOrigin == "https://dembed.st" &&
                URL(url).path.endsWith(".php", ignoreCase = true)) {
                Trace.event("PLAYER_PAGE", "DEMBED_ROUTE", "target=${Trace.target(url)}")
                DaddyLiveDembed.extract(url, parentReferer.orEmpty(), sourceName)?.let { return it }
                Trace.event("PLAYER_PAGE", "DEMBED_FALLBACK", "reason=extractor_returned_null")
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

    private fun decodeEConfig(encoded: String): Map<String, Any?>? {
        return try {
            val first = base64Decode(encoded)
            val order = intArrayOf(2, 0, 3, 1)
            val partLength = ceil(first.length / 4.0).toInt()

            val parts = mutableListOf<String>()
            var offset = 0

            repeat(4) {
                val end = minOf(offset + partLength, first.length)
                parts += if (offset < first.length) {
                    first.substring(offset, end)
                } else {
                    ""
                }
                offset += partLength
            }

            val decoded = arrayOfNulls<String>(4)
            for (i in 0 until 4) {
                val part = parts[i]
                if (part.length <= 3) return null

                val cleaned =
                    part.substring(0, 3) +
                        part.substring(4)

                decoded[order[i]] = base64Decode(cleaned)
            }

            val joined = decoded.joinToString("") { it.orEmpty() }
            val json = base64Decode(joined)

            mapper.readValue<Map<String, Any?>>(json)
        } catch (e: Exception) {
            null
        }
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
            val valid = body.startsWith("#EXTM3U")
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
    private suspend fun extractEConfigStream(
        pageUrl: String,
        pageHtml: String,
        pageOrigin: String,
        sourceName: String
    ): ExtractorLink? {
        /*
         * v4의 raw-string 정규식은 역슬래시를 이중 이스케이프해서
         * window._econfig를 놓칠 수 있었다. v5에서 정확히 수정.
         */
        val encoded = Regex(
            """(?i)(?:window\.)?_econfig\s*=\s*["']([^"']+)["']"""
        ).find(pageHtml)?.groupValues?.getOrNull(1)
            ?: return null


        val config = decodeEConfig(encoded) ?: return null

        val candidates = listOf(
            "stream_url_nop2p" to config["stream_url_nop2p"]?.toString(),
            "stream_url" to config["stream_url"]?.toString()
        ).mapNotNull { (kind, raw) ->
            val value = raw
                ?.takeIf { it.isNotBlank() && it != "null" }
                ?: return@mapNotNull null

            val resolved = resolveUrl(pageUrl, value)
                ?: return@mapNotNull null

            kind to resolved
        }.distinctBy { it.second }

        val finalHeaders = hlsHeaders(
            origin = pageOrigin,
            referer = "$pageOrigin/"
        )

        for ((kind, candidate) in candidates) {
            currentCoroutineContext().ensureActive()


            val link = buildValidatedLink(
                url = candidate,
                sourceName = sourceName,
                displayName = "$sourceName [$kind]",
                referer = "$pageOrigin/",
                origin = pageOrigin,
                headers = finalHeaders
            )

            if (link != null) {
                return link
            }
        }

        return null
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


        // 1) 최신 tiestep/stream.js _econfig 방식 최우선.
        val eConfigLink = extractEConfigStream(
            pageUrl = pageUrl,
            pageHtml = pageHtml,
            pageOrigin = pageOrigin,
            sourceName = sourceName
        )
        if (eConfigLink != null) return eConfigLink

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

        // 3) embedsports 계열 JS 변수 복원.
        val edm = Regex(
            """(?i)\bedm\s*=\s*["']([^"']+)["']"""
        ).find(pageHtml)?.groupValues?.getOrNull(1)

        val zmid = Regex(
            """(?i)\bzmid\s*=\s*["']([^"']+)["']"""
        ).find(pageHtml)?.groupValues?.getOrNull(1)

        if (!edm.isNullOrBlank() && !zmid.isNullOrBlank()) {
            val constructed = "https://$edm/court/$zmid.php"
            val dynamicLink = crawlChild(
                childUrl = constructed,
                parentUrl = pageUrl,
                sourceName = sourceName,
                depth = depth,
                visited = visited
            )
            if (dynamicLink != null) return dynamicLink
        }

        // 4) 실제/동적 player URL 후보 수집 후 순차 탐색.
        val nestedCandidates = collectNestedCandidates(
            pageUrl = pageUrl,
            pageHtml = pageHtml
        )

        Trace.event("PLAYER_NESTED", "FOUND", "parent=${Trace.target(pageUrl)} count=${nestedCandidates.size} depth=$depth")

        for (nestedUrl in nestedCandidates) {
            currentCoroutineContext().ensureActive()

            val nestedLink = crawlChild(
                childUrl = nestedUrl,
                parentUrl = pageUrl,
                sourceName = sourceName,
                depth = depth,
                visited = visited
            )
            if (nestedLink != null) return nestedLink
        }

        // 5) wiki.js / Ignite legacy resolver.
        val wikiLink = extractWikiStream(
            pageUrl = pageUrl,
            pageHtml = pageHtml,
            pageOrigin = pageOrigin,
            sourceName = sourceName
        )
        if (wikiLink != null) return wikiLink

        // 6) atob 후보는 실제 HLS 검증 후에만 성공 처리.
        val atobMatches = Regex(
            """(?i)atob\(\s*["']([^"']+)["']\s*\)"""
        ).findAll(pageHtml)

        for (match in atobMatches) {
            currentCoroutineContext().ensureActive()

            val decoded = try {
                base64Decode(match.groupValues[1])
                    .replace("\\/", "/")
                    .replace("\"", "")
                    .trim()
            } catch (_: Exception) {
                continue
            }

            val candidate = resolveUrl(pageUrl, decoded) ?: continue
            if (!candidate.contains(".m3u8", ignoreCase = true)) continue

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

            if (link != null) {
                return link
            }

        }

        // 7) Popcdn/Freeshot legacy resolver.
        val popLink = extractPopcdnStream(
            pageUrl = pageUrl,
            pageOrigin = pageOrigin,
            sourceName = sourceName
        )
        if (popLink != null) return popLink

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
        if (originOf(childUrl) == "https://dembed.st" &&
            URL(childUrl).path.endsWith(".php", ignoreCase = true)) {
            Trace.event("PLAYER_NESTED", "DEMBED_ROUTE", "target=${Trace.target(childUrl)}")
            DaddyLiveDembed.extract(childUrl, parentUrl, sourceName)?.let { return it }
            Trace.event("PLAYER_NESTED", "DEMBED_FALLBACK", "reason=extractor_returned_null")
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

    private suspend fun extractWikiStream(
        pageUrl: String,
        pageHtml: String,
        pageOrigin: String,
        sourceName: String
    ): ExtractorLink? {
        val wikiJsMatch = Regex(
            """(?i)src=["'](https?://[^"']+|//[^"']+)wiki\.js"""
        ).find(pageHtml) ?: return null

        var rawWikiHost = wikiJsMatch.groupValues[1]
        if (rawWikiHost.startsWith("//")) {
            rawWikiHost = "https:$rawWikiHost"
        }

        var igniteHost = rawWikiHost.trimEnd('/')
        if (igniteHost.contains("stellarthread.com", ignoreCase = true)) {
            igniteHost = "https://igniteandship.com"
        }

        val pageName = try {
            URL(pageUrl).path
                .substringAfterLast("/")
                .substringBefore(".php")
        } catch (_: Exception) {
            ""
        }

        val fid = Regex(
            """(?i)\bfid\s*=\s*["']([^"']+)["']"""
        ).find(pageHtml)?.groupValues?.getOrNull(1)

        val zmid = Regex(
            """(?i)\bzmid\s*=\s*["']([^"']+)["']"""
        ).find(pageHtml)?.groupValues?.getOrNull(1)

        val liveId = when {
            !fid.isNullOrBlank() -> fid
            !zmid.isNullOrBlank() -> zmid
            pageName.isNotBlank() -> pageName
            else -> return null
        }

        val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$liveId"

        return try {
            val response = app.get(
                playerUrl,
                headers = pageHeaders(
                    parentUrl = pageUrl,
                    parentOrigin = pageOrigin
                ),
                timeout = 5
            )

            if (!response.isSuccessful) return null

            val playerHtml = response.text
            val arrayMatch = Regex(
                """\["h"\s*,\s*"t"\s*,\s*"t"\s*,\s*"p"\s*,\s*"s"\s*,[^\]]+\]"""
            ).find(playerHtml) ?: return null

            var streamUrl = arrayMatch.value
                .replace("[", "")
                .replace("]", "")
                .replace("\"", "")
                .replace("\\/", "/")
                .replace(",", "")
                .trim()

            val idMatch = Regex(
                """document\.getElementById\(["']([^"']+)["']\)"""
            ).find(playerHtml)?.groupValues?.getOrNull(1)

            if (!idMatch.isNullOrBlank()) {
                val safeId = Regex.escape(idMatch)
                val span = Regex(
                    """(?is)id=["']?$safeId["']?[^>]*>([^<]*)"""
                ).find(playerHtml)?.groupValues?.getOrNull(1).orEmpty()
                streamUrl += span
            }

            if (!streamUrl.startsWith("http")) return null

            val headers = hlsHeaders(
                origin = igniteHost,
                referer = "$igniteHost/"
            )

            buildValidatedLink(
                url = streamUrl,
                sourceName = sourceName,
                referer = "$igniteHost/",
                origin = igniteHost,
                headers = headers
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun extractPopcdnStream(
        pageUrl: String,
        pageOrigin: String,
        sourceName: String
    ): ExtractorLink? {
        if (
            !pageUrl.contains("freeshot", ignoreCase = true) &&
            !pageUrl.contains("popcdn", ignoreCase = true)
        ) {
            return null
        }

        val channelCode = Regex(
            """(?i)embed/([^/.?]+)(?:\.php)?"""
        ).find(pageUrl)?.groupValues?.getOrNull(1)
            ?: try {
                URL(pageUrl).path
                    .substringAfterLast("/")
                    .substringBefore(".")
            } catch (_: Exception) {
                ""
            }

        if (channelCode.isBlank()) return null

        val targetUrl = "https://popcdn.day/player/$channelCode"

        return try {
            val response = app.get(
                targetUrl,
                headers = pageHeaders(
                    parentUrl = pageUrl,
                    parentOrigin = pageOrigin
                ),
                timeout = 5
            )

            if (!response.isSuccessful) return null

            val body = response.text
            var finalM3u8 = Regex(
                """(?i)streamUrl\s*:\s*["']([^"']+)["']"""
            ).find(body)?.groupValues?.getOrNull(1)
                ?.replace("\\/", "/")
                .orEmpty()

            if (finalM3u8.isBlank()) {
                val token = Regex(
                    """(?i)token=([^&"']+)"""
                ).find(body)?.groupValues?.getOrNull(1)

                if (!token.isNullOrBlank()) {
                    finalM3u8 =
                        "https://planetary.lovecdn.ru/$channelCode/tracks-v1a1/mono.m3u8?token=$token"
                }
            }

            if (finalM3u8.isBlank()) return null

            val origin = "https://popcdn.day"
            val headers = hlsHeaders(
                origin = origin,
                referer = "$origin/"
            )

            buildValidatedLink(
                url = finalM3u8,
                sourceName = sourceName,
                displayName = "Freeshot Server",
                referer = "$origin/",
                origin = origin,
                headers = headers
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }

    private object DaddyLiveDembed {
        private const val EMBED_ORIGIN = "https://dembed.st"
        private val main = Handler(Looper.getMainLooper())
        private val sessions = ConcurrentHashMap<String, Session>()
        private val io = CoroutineScope(Dispatchers.IO)
        private val attribute = Regex("""URI="([^"]+)"""")
        private var server: ServerSocket? = null
        @Volatile private var port = 0

        private class Resource(val url: String, val playlist: Boolean)
        private class Session(val parent: String, val embedded: String) {
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
            @Volatile var browserHeaders: Map<String, String> = emptyMap()
            @Volatile var authHttpError: Int? = null
            @Volatile var manifestHttpError: Int? = null
            @Volatile var authRequestCount = 0
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

            fun onToken(response: ByteArray) {
                val data = runCatching { JSONObject(String(response, Charsets.UTF_8)) }.getOrNull()
                if (data == null) {
                    Trace.event("AUTH_TOKEN", "PARSE_FAIL", "sid=${id.take(8)} bytes=${response.size} reason=invalid_json")
                    return
                }
                val encoded = data.optString("k")
                if (encoded.isNotBlank()) {
                    val decoded = runCatching {
                        Base64.decode(encoded, Base64.URL_SAFE or Base64.NO_WRAP)
                    }.getOrNull()
                    if (decoded != null && decoded.size in setOf(16, 24, 32)) key = decoded
                }
                val current = data.optString("token").ifBlank { data.optString("t") }
                if (current.isNotBlank()) token = current
                Trace.event("AUTH_TOKEN", if (current.isNotBlank() || key != null) "ACQUIRED" else "MISSING",
                    "sid=${id.take(8)} ticketFieldPresent=${current.isNotBlank()} keyBytes=${key?.size ?: 0} responseBytes=${response.size}")
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

            fun record(text: String) {
                if (!text.startsWith("DADDY_TOKEN:")) return
                val value = text.substringAfter("DADDY_TOKEN:")
                if (value.isNotBlank()) token = value
                pending?.complete(value)
                pending = null
            }

            suspend fun renew(): String {
                Trace.event("AUTH_REFRESH", "START", "sid=${id.take(8)}")
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
                    Trace.event("AUTH_REFRESH", if (it.isNotBlank()) "OK" else "FAILED",
                        "sid=${id.take(8)} reason=${if (it.isBlank()) "empty_or_timeout" else "new_token"}")
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
                            view.webChromeClient = object : WebChromeClient() {
                                override fun onConsoleMessage(message: ConsoleMessage?): Boolean {
                                    val raw = message?.message().orEmpty()
                                    if (raw.startsWith("DADDY_TOKEN:")) {
                                        Trace.event("AUTH_WEBVIEW", "JS_TOKEN_CALLBACK", "sid=${session.id.take(8)} hasValue=${raw.length > 12}")
                                    }
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
                                    session.record(raw)
                                    return false
                                }
                            }
                            view.webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    Trace.event("AUTH_WEBVIEW", "PAGE_LOADED",
                                        "sid=${session.id.take(8)} target=${Trace.target(url.orEmpty())}")
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
            val actual = session.url.ifBlank { captured }
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
                Trace.event("HLS_FETCH", "CLIENT_REQUEST",
                    "sid=${session.id.take(8)} target=${Trace.target(actual)} signed=$signed " +
                        "headerNames=${requestHeaders.keys.sorted().joinToString(",")} " +
                        "cookiePresent=${requestHeaders.keys.any { it.equals("cookie", true) }}")
                val response = app.get(actual, headers = requestHeaders, timeout = 5)
                val errorReason = if (!response.isSuccessful) Trace.bodyReason(response.text) else "none"
                Trace.event("HLS_FETCH", if (response.isSuccessful) "HTTP_OK" else "HTTP_FAIL",
                    "sid=${session.id.take(8)} target=${Trace.target(actual)} http=${response.code} " +
                        "reason=$errorReason browserHlsHttpError=${session.manifestHttpError ?: -1} " +
                        "browserAuthHttpError=${session.authHttpError ?: -1} " +
                        "headerNames=${response.headers.names().sorted().joinToString(",")} " +
                        "contentType=${response.header("Content-Type").orEmpty().substringBefore(';')}")
                if (!response.isSuccessful) {
                    session.close()
                    null
                } else {
                    val playlist = decodePlaylist(response.text, session.key)
                    if (playlist == null || !playlist.trimStart().startsWith("#EXTM3U")) {
                        Trace.event("HLS_DECODE", "FAILED", "sid=${session.id.take(8)} keyPresent=${session.key != null} reason=invalid_playlist")
                        session.close()
                        null
                    } else {
                        Trace.event("HLS_DECODE", "OK", "sid=${session.id.take(8)} chars=${playlist.length} encrypted=${!response.text.trimStart().startsWith("#EXTM3U")}")
                        if (!signed && session.key == null && directCompatible(playlist, actual, session.headers)) {
                            Trace.event("MEDIA3_LINK", "DIRECT", "sid=${session.id.take(8)} reason=ordinary_hls elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
                            // Probe a media segment before bypassing the wrapper decoder.
                            session.close()
                            newExtractorLink(name, name, actual, ExtractorLinkType.M3U8) {
                                referer = "$EMBED_ORIGIN/"
                                quality = Qualities.Unknown.value
                                headers = session.headers
                            }
                        } else {
                            val openPort = startServer()
                            if (openPort == 0) {
                                Trace.event("MEDIA3_LINK", "FAILED", "sid=${session.id.take(8)} reason=proxy_bind_failed")
                                session.close()
                                null
                            } else {
                                Trace.event("MEDIA3_LINK", "PROXY", "sid=${session.id.take(8)} signed=$signed keyPresent=${session.key != null} elapsedMs=${android.os.SystemClock.elapsedRealtime()-startMs}")
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

        /** A normal unsigned playlist goes directly to Media3 only when its first media
         * segment is genuine TS/fMP4, not a disguised image or a nested playlist. */
        private suspend fun directCompatible(
            playlist: String, base: String, headers: Map<String, String>
        ): Boolean {
            val first = playlist.lineSequence()
                .map { it.trim() }
                .firstOrNull { it.isNotEmpty() && !it.startsWith("#") }
                ?: run {
                    Trace.event("SEGMENT_PROBE", "SKIP", "reason=no_media_uri")
                    return false
                }
            val url = absolute(base, first) ?: run {
                Trace.event("SEGMENT_PROBE", "SKIP", "reason=unresolved_url")
                return false
            }
            if (url.substringBefore('?').endsWith(".m3u8", true)) {
                Trace.event("SEGMENT_PROBE", "SKIP", "reason=nested_playlist target=${Trace.target(url)}")
                return false
            }
            return try {
                val response = app.get(url, headers = headers + ("Range" to "bytes=0-575"), timeout = 4)
                if (!response.isSuccessful) {
                    Trace.event("SEGMENT_PROBE", "HTTP_FAIL", "target=${Trace.target(url)} http=${response.code}")
                    return false
                }
                val prefix = ByteArray(376)
                var count = 0
                response.body.byteStream().use { input ->
                    while (count < prefix.size) {
                        val read = input.read(prefix, count, prefix.size - count)
                        if (read <= 0) break
                        count += read
                    }
                }
                val ordinary = count >= 189 && prefix[0] == 0x47.toByte() && prefix[188] == 0x47.toByte() ||
                    (count >= 8 && String(prefix, 4, 4, Charsets.US_ASCII) in setOf("ftyp", "styp", "moof"))
                Trace.event("SEGMENT_PROBE", if (ordinary) "DIRECT_OK" else "PROXY_REQUIRED",
                    "target=${Trace.target(url)} http=${response.code} bytes=$count first=${prefix.take(4).joinToString("") { (it.toInt() and 255).toString(16).padStart(2,'0') }}")
                ordinary
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Trace.event("SEGMENT_PROBE", "ERROR", "reason=${e.javaClass.simpleName}")
                false
            }
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
            if (body.trimStart().startsWith("#EXTM3U")) {
                Trace.event("HLS_DECRYPT", "SKIP", "reason=plain_extm3u")
                return body
            }
            if (key == null) {
                Trace.event("HLS_DECRYPT", "FAILED", "reason=missing_aes_key chars=${body.length}")
                return null
            }
            Trace.event("HLS_DECRYPT", "START", "keyBytes=${key.size} chars=${body.length}")
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
                    ?: run {
                        Trace.event("HLS_DECRYPT", "FAILED", "reason=base64_invalid sequence=$counter")
                        return null
                    }
                val iv = ByteBuffer.allocate(16).putLong(8, counter).array()
                val decrypted = runCatching {
                    val cipher = Cipher.getInstance("AES/CTR/NoPadding")
                    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
                    String(cipher.doFinal(bytes), StandardCharsets.UTF_8)
                }.getOrNull() ?: run {
                    Trace.event("HLS_DECRYPT", "FAILED", "reason=aes_ctr_decrypt_error sequence=$counter")
                    return null
                }
                output.append(decrypted).append('\n')
                counter++
            }
            return output.toString().takeIf { it.trimStart().startsWith("#EXTM3U") }.also {
                Trace.event("HLS_DECRYPT", if (it != null) "OK" else "FAILED",
                    "reason=${if (it != null) "aes_ctr_complete" else "not_extm3u"}")
            }
        }

        private suspend fun loadManifest(session: Session, resource: Resource?): Pair<String, String>? {
            val original = resource?.url ?: session.url
            for (attempt in 0..1) {
                if (attempt == 1) {
                    if (session.renew().isBlank()) {
                        Trace.event("HLS_REFRESH", "FAILED", "sid=${session.id.take(8)} reason=token_refresh_failed")
                        return null
                    }
                }
                val target = session.signed(original)
                Trace.event("HLS_REFRESH", "REQUEST", "sid=${session.id.take(8)} attempt=$attempt target=${Trace.target(target)}")
                val response = runCatching { app.get(target, headers = session.headers, timeout = 5) }.getOrNull()
                if (response?.isSuccessful == true) {
                    val decoded = decodePlaylist(response.text, session.key)
                    if (decoded != null) {
                        Trace.event("HLS_REFRESH", "OK", "sid=${session.id.take(8)} attempt=$attempt chars=${decoded.length}")
                        return target to decoded
                    }
                }
                Trace.event("HLS_REFRESH", "RETRY",
                    "sid=${session.id.take(8)} attempt=$attempt http=${response?.code ?: -1} " +
                        "reason=${if (response == null) "request_exception" else if (!response.isSuccessful) Trace.bodyReason(response.text) else "invalid_playlist"}")
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
                                val response = runCatching {
                                    app.get(resource.url, headers = session.headers, timeout = 8)
                                }.getOrNull()
                                if (response?.isSuccessful == true) {
                                    val bytes = response.body.bytes()
                                    data = DaddyLiveSegment.unwrap(bytes)
                                    type = if (data?.firstOrNull() == 0x47.toByte()) "video/mp2t"
                                    else "application/octet-stream"
                                    Trace.event("PROXY_SEGMENT", if (data != null) "OK" else "FAILED",
                                        "sid=${session.id.take(8)} target=${Trace.target(resource.url)} in=${bytes.size} out=${data?.size ?: 0} format=$type elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
                                } else {
                                    Trace.event("PROXY_SEGMENT", "HTTP_FAIL",
                                        "sid=${session.id.take(8)} target=${Trace.target(resource.url)} http=${response?.code ?: -1} elapsedMs=${android.os.SystemClock.elapsedRealtime()-start}")
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

    private object DaddyLiveSegment {
        private val pngMagic = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)
        private val rawMarker = "TIKTIKRAW".toByteArray()
        private val gzipMarker = "TIKTIKTSGZ".toByteArray()
        private val pixelMarker = "TIKTIKPX".toByteArray()
        private const val MAX_BYTES = 32 * 1024 * 1024

        private fun matches(bytes: ByteArray, at: Int, pattern: ByteArray): Boolean =
            at >= 0 && at + pattern.size <= bytes.size &&
                pattern.indices.all { bytes[at + it] == pattern[it] }

        private fun index(bytes: ByteArray, pattern: ByteArray): Int {
            for (at in 0..(bytes.size - pattern.size).coerceAtLeast(-1)) {
                if (matches(bytes, at, pattern)) return at
            }
            return -1
        }

        private fun ts(bytes: ByteArray, at: Int): Boolean =
            at >= 0 && at + 188 < bytes.size &&
                bytes[at] == 0x47.toByte() && bytes[at + 188] == 0x47.toByte()

        private fun be32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 255 shl 24) or
                (bytes[at + 1].toInt() and 255 shl 16) or
                (bytes[at + 2].toInt() and 255 shl 8) or (bytes[at + 3].toInt() and 255)

        private fun le32(bytes: ByteArray, at: Int): Int =
            (bytes[at].toInt() and 255) or ((bytes[at + 1].toInt() and 255) shl 8) or
                ((bytes[at + 2].toInt() and 255) shl 16) or ((bytes[at + 3].toInt() and 255) shl 24)

        private fun unzip(bytes: ByteArray, offset: Int, length: Int): ByteArray? = runCatching {
            GZIPInputStream(ByteArrayInputStream(bytes, offset, length)).use { it.readLimited() }
                .takeIf { it.isNotEmpty() && it[0] == 0x47.toByte() }
        }.getOrNull()

        private fun java.io.InputStream.readLimited(): ByteArray {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = read(buffer)
                if (count < 0) break
                if (out.size() + count > MAX_BYTES) return ByteArray(0)
                out.write(buffer, 0, count)
            }
            return out.toByteArray()
        }

        fun unwrap(bytes: ByteArray): ByteArray? {
            if (bytes.size < 8 || bytes.size > MAX_BYTES) {
                Trace.event("SEGMENT_FORMAT", "FAILED", "reason=invalid_length bytes=${bytes.size}")
                return null
            }
            if (ts(bytes, 0)) {
                Trace.event("SEGMENT_FORMAT", "PASSTHROUGH", "format=mpegts bytes=${bytes.size}")
                return bytes
            }
            if (matches(bytes, 0, pngMagic)) {
                val result = png(bytes)
                Trace.event("SEGMENT_FORMAT", if (result != null) "UNWRAPPED" else "FAILED",
                    "format=png in=${bytes.size} out=${result?.size ?: 0} reason=${if (result == null) "png_wrapper_unrecognized" else "ts_recovered"}")
                return result
            }
            if (matches(bytes, 0, "RIFF".toByteArray()) && matches(bytes, 8, "WEBP".toByteArray())) {
                var at = 12
                while (at + 8 <= bytes.size) {
                    val size = le32(bytes, at + 4)
                    if (size < 0 || size > bytes.size - at - 8) break
                    if (matches(bytes, at, "EXIF".toByteArray())) {
                        val payload = at + 8
                        val result = bytes.copyOfRange(payload, payload + size).takeIf { ts(it, 0) }
                        Trace.event("SEGMENT_FORMAT", if (result != null) "UNWRAPPED" else "FAILED",
                            "format=webp_exif in=${bytes.size} out=${result?.size ?: 0}")
                        return result
                    }
                    at += 8 + size + (size and 1)
                }
                Trace.event("SEGMENT_FORMAT", "FAILED", "format=webp reason=exif_ts_not_found bytes=${bytes.size}")
                return null
            }
            val raw = index(bytes, rawMarker)
            if (raw >= 0 && ts(bytes, raw + rawMarker.size)) {
                Trace.event("SEGMENT_FORMAT", "UNWRAPPED", "format=raw_marker bytes=${bytes.size}")
                return bytes.copyOfRange(raw + rawMarker.size, bytes.size)
            }
            val zip = index(bytes, gzipMarker)
            if (zip >= 0) {
                val result = unzip(bytes, zip + gzipMarker.size, bytes.size - zip - gzipMarker.size)
                Trace.event("SEGMENT_FORMAT", if (result != null) "UNWRAPPED" else "FAILED",
                    "format=gzip_marker in=${bytes.size} out=${result?.size ?: 0}")
                return result
            }
            for (at in 0 until bytes.size - 188) {
                if (ts(bytes, at)) {
                    Trace.event("SEGMENT_FORMAT", "UNWRAPPED", "format=embedded_ts offset=$at bytes=${bytes.size}")
                    return bytes.copyOfRange(at, bytes.size)
                }
            }
            Trace.event("SEGMENT_FORMAT", "PASSTHROUGH", "format=unknown bytes=${bytes.size}")
            // Preserve supported non-TS formats (e.g. fMP4) rather than altering ordinary streams.
            return bytes
        }

        private fun png(bytes: ByteArray): ByteArray? {
            var at = 8
            var width = 0
            var height = 0
            var depth = 0
            var color = 0
            var interlace = 0
            val compressed = ByteArrayOutputStream()
            while (at + 12 <= bytes.size) {
                val size = be32(bytes, at)
                if (size < 0 || size > bytes.size - at - 12) return null
                val payload = at + 8
                when {
                    matches(bytes, at + 4, "IHDR".toByteArray()) && size >= 13 -> {
                        width = be32(bytes, payload)
                        height = be32(bytes, payload + 4)
                        depth = bytes[payload + 8].toInt() and 255
                        color = bytes[payload + 9].toInt() and 255
                        interlace = bytes[payload + 12].toInt() and 255
                    }
                    matches(bytes, at + 4, "IDAT".toByteArray()) -> {
                        if (compressed.size() + size > MAX_BYTES) return null
                        compressed.write(bytes, payload, size)
                    }
                    matches(bytes, at + 4, "IEND".toByteArray()) -> {
                        val end = payload + size + 4
                        if (ts(bytes, end)) return bytes.copyOfRange(end, bytes.size)
                        break
                    }
                }
                at += size + 12
            }
            if (width < 1 || height < 1 || width.toLong() * height > 2_000_000L ||
                depth != 8 || interlace != 0 || (color != 2 && color != 6)) return null
            val step = if (color == 6) 4 else 3
            val rowSize = width * step
            val inflated = runCatching {
                InflaterInputStream(ByteArrayInputStream(compressed.toByteArray())).use { it.readLimited() }
            }.getOrNull() ?: return null
            if (inflated.size < (rowSize + 1L) * height) return null
            val rgb = ByteArray(width * height * 3)
            var position = 0
            var output = 0
            var previous = ByteArray(rowSize)
            repeat(height) {
                val filter = inflated[position++].toInt() and 255
                val line = ByteArray(rowSize)
                for (i in 0 until rowSize) {
                    val left = if (i >= step) line[i - step].toInt() and 255 else 0
                    val up = previous[i].toInt() and 255
                    val upperLeft = if (i >= step) previous[i - step].toInt() and 255 else 0
                    val predictor = when (filter) {
                        0 -> 0
                        1 -> left
                        2 -> up
                        3 -> (left + up) / 2
                        4 -> {
                            val p = left + up - upperLeft
                            val a = kotlin.math.abs(p - left)
                            val b = kotlin.math.abs(p - up)
                            val c = kotlin.math.abs(p - upperLeft)
                            if (a <= b && a <= c) left else if (b <= c) up else upperLeft
                        }
                        else -> return null
                    }
                    line[i] = ((inflated[position++].toInt() and 255) + predictor).toByte()
                }
                for (i in 0 until rowSize step step) {
                    rgb[output++] = line[i]
                    rgb[output++] = line[i + 1]
                    rgb[output++] = line[i + 2]
                }
                previous = line
            }
            val marker = index(rgb, pixelMarker)
            if (marker < 0 || marker + pixelMarker.size + 4 > rgb.size) return null
            val lengthAt = marker + pixelMarker.size
            val length = be32(rgb, lengthAt)
            val gzipAt = lengthAt + 4
            if (length < 2 || length > rgb.size - gzipAt) return null
            return unzip(rgb, gzipAt, length)
        }
    }
}
