package com.hsp1020

import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import kotlin.math.ceil

class DaddyLiveExtractor : ExtractorApi() {
    override var mainUrl = "https://dlive.sx"
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

    private fun debugLog(message: String) {
        Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
        println("[DDL-DEBUG] $message")
    }

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

        debugLog(
            "[${selected.channelName}] 선택 채널 단일 추출 시작 " +
                "(streamId=${selected.streamId})"
        )

        val entryUrl = "$entryOrigin/watch.php?id=${selected.streamId}"
        val candidates = discoverPlayerCandidates(
            entryOrigin = entryOrigin,
            entryUrl = entryUrl,
            streamId = selected.streamId
        )

        debugLog(
            "[${selected.channelName}] 최종 player 후보 ${candidates.size}개 -> " +
                candidates.joinToString(" | ") { "${it.player}:${it.url}" }
        )

        for (candidate in candidates) {
            currentCoroutineContext().ensureActive()

            val sourceName = "${selected.channelName} - ${candidate.player}"
            debugLog(
                "[${selected.channelName}] player '${candidate.player}' 시도 -> ${candidate.url}"
            )

            val link = extractVideo(
                url = candidate.url,
                sourceName = sourceName,
                parentReferer = entryUrl
            )

            if (link != null) {
                debugLog(
                    "[${selected.channelName}] '${candidate.player}' 실제 HLS 검증 성공. " +
                        "나머지 player 탐색 중지."
                )
                callback(link)
                return
            }
        }

        debugLog(
            "[${selected.channelName}] 모든 player 후보에서 " +
                "재생 가능한 검증 완료 HLS를 찾지 못했습니다."
        )
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

            debugLog(
                "[ENTRY] $entryUrl -> HTTP ${response.code}"
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

                debugLog(
                    "[ENTRY] 실제 HTML에서 발견한 player -> " +
                        if (discovered.isEmpty()) {
                            "없음"
                        } else {
                            discovered.entries.joinToString(" | ") { "${it.key}:${it.value}" }
                        }
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog(
                "[ENTRY] watch.php 분석 실패. 공식 folder fallback 사용 -> ${e.message}"
            )
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
        parentReferer: String?
    ): ExtractorLink? {
        try {
            currentCoroutineContext().ensureActive()

            debugLog("================= [DDL EXTRACT START] =================")
            debugLog("소스 -> $sourceName | $url")

            val targetOrigin = originOf(url) ?: mainUrl
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

            debugLog("플레이어 페이지 응답 코드: ${response.code}")

            if (!response.isSuccessful) {
                debugLog("플레이어 페이지 HTTP ${response.code} -> 다음 player")
                return null
            }

            val link = parseAndExtract(
                pageUrl = url,
                pageHtml = response.text,
                sourceName = sourceName,
                depth = 0,
                visited = linkedSetOf()
            )

            if (link != null) {
                debugLog("================= [DDL EXTRACT SUCCESS] =================")
                return link
            }

            debugLog("현재 player '$sourceName'에서 검증 완료 HLS를 찾지 못했습니다.")
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        } catch (e: CancellationException) {
            debugLog("extractVideo 취소 감지 -> 전체 작업 즉시 중단")
            throw e
        } catch (e: SocketTimeoutException) {
            debugLog("extractVideo 타임아웃: ${e.message}")
            return null
        } catch (e: IOException) {
            debugLog("extractVideo 네트워크 오류: ${e.message}")
            return null
        } catch (e: Exception) {
            debugLog("extractVideo 오류: ${e.message}")
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

            debugLog("[ECONFIG] 복호화 성공")
            mapper.readValue<Map<String, Any?>>(json)
        } catch (e: Exception) {
            debugLog("[ECONFIG] 복호화 실패: ${e.message}")
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
                debugLog("[HLS-VALIDATE] HTTP ${response.code} 실패 -> $url")
                return false
            }

            val body = response.text.trimStart()
            val valid = body.startsWith("#EXTM3U")

            if (valid) {
                debugLog("[HLS-VALIDATE] #EXTM3U 확인 -> $url")
            } else {
                debugLog("[HLS-VALIDATE] HTTP ${response.code}지만 HLS 아님 -> $url")
            }

            valid
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            debugLog("[HLS-VALIDATE] 요청 실패 -> $url | ${e.message}")
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
        if (!validateHls(url, headers)) return null

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

        debugLog("[ECONFIG] window._econfig 감지 -> $pageUrl")

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

            debugLog("[ECONFIG] $kind 후보 검증 -> $candidate")

            val link = buildValidatedLink(
                url = candidate,
                sourceName = sourceName,
                displayName = "$sourceName [$kind]",
                referer = "$pageOrigin/",
                origin = pageOrigin,
                headers = finalHeaders
            )

            if (link != null) {
                debugLog("[ECONFIG] 실제 재생 가능한 $kind 확정 -> $candidate")
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

        debugLog("[CRAWL] depth=$depth -> $pageUrl")

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

            debugLog("[DIRECT-M3U8] 후보 검증 -> $candidate")
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

        if (nestedCandidates.isNotEmpty()) {
            debugLog(
                "[CRAWL] depth=$depth nested 후보 ${nestedCandidates.size}개 -> " +
                    nestedCandidates.joinToString(" | ")
            )
        }

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

            debugLog("[atob] 후보 검증 -> $candidate")

            val link = buildValidatedLink(
                url = candidate,
                sourceName = sourceName,
                referer = "$pageOrigin/",
                origin = pageOrigin,
                headers = headers
            )

            if (link != null) {
                debugLog("[atob] 실제 HLS 검증 성공 -> $candidate")
                return link
            }

            debugLog("[atob] dead/decoy 후보 폐기 -> $candidate")
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

            if (
                lower.endsWith(".js") ||
                lower.endsWith(".css") ||
                lower.endsWith(".png") ||
                lower.endsWith(".jpg") ||
                lower.endsWith(".jpeg") ||
                lower.endsWith(".gif") ||
                lower.endsWith(".svg") ||
                lower.contains("doubleclick") ||
                lower.contains("googlesyndication")
            ) {
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
                    lower.endsWith(".php")

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
        if (childUrl in visited) return null

        val parentOrigin = originOf(parentUrl) ?: return null

        return try {
            currentCoroutineContext().ensureActive()

            debugLog("[CRAWL] child 시도 -> $childUrl")

            val response = app.get(
                childUrl,
                headers = pageHeaders(
                    parentUrl = parentUrl,
                    parentOrigin = parentOrigin
                ),
                timeout = 5
            )

            if (!response.isSuccessful) {
                debugLog("[CRAWL] child HTTP ${response.code} -> $childUrl")
                return null
            }

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
            debugLog("[CRAWL] child 실패, 다음 후보 계속 -> $childUrl | ${e.message}")
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
            debugLog("[IGNITE] 실패 -> ${e.message}")
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
            debugLog("[POPCDN] 실패 -> ${e.message}")
            null
        }
    }
}
