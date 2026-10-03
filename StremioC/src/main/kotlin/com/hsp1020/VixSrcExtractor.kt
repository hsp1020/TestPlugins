// 파일명: hsp1020/VixSrcExtractor.kt
package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object VixSrcExtractor {

    private val EMBED_REGEX = """(/embed/(\d+)[^"'\s]*)""".toRegex()

    // VixSrc의 masterPlaylist는 params { ... } 중첩 객체 뒤에 url이 오는 구조다.
    // 따라서 {.*?} 방식으로 객체를 자르면 params의 첫 }에서 끝나 url을 놓친다.
    // marker 이후 충분한 길이의 작은 블록을 잘라 개별 필드를 추출한다.
    private const val MASTER_PLAYLIST_BLOCK_LEN = 2500

    private val PLAYLIST_URL_REGEX =
        """\burl\s*:\s*["']([^"']*/playlist/[^"']*)["']""".toRegex()
    private val TOKEN_REGEX =
        """["']?token["']?\s*:\s*["']([^"']+)["']""".toRegex()
    private val EXPIRY_REGEX =
        """["']?expires["']?\s*:\s*["']?(\d{9,})["']?""".toRegex()
    private val ASN_REGEX =
        """["']?asn["']?\s*:\s*["']?([^"',}\s]+)["']?""".toRegex()
    private val B_REGEX =
        """["']?b["']?\s*[:=]\s*["']?([^"'&,}\s]+)["']?""".toRegex()

    private fun normalizeVixUrl(baseUrl: String, rawUrl: String): String {
        val cleaned = rawUrl
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        return when {
            cleaned.startsWith("https://", ignoreCase = true) ||
                cleaned.startsWith("http://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> baseUrl + cleaned
            else -> "$baseUrl/${cleaned.trimStart('/')}"
        }
    }

    private fun hasQueryParam(url: String, key: String): Boolean {
        val escaped = Regex.escape(key)
        return Regex("(?:[?&])$escaped=").containsMatchIn(url)
    }

    private fun appendQueryParamIfMissing(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank() || hasQueryParam(url, key)) return url
        val separator = if (url.contains('?')) "&" else "?"
        return "$url$separator$key=$value"
    }

    private fun buildMoviePlaylistUrl(
        baseUrl: String,
        parsedPlaylistUrl: String?,
        actualVid: String,
        token: String,
        expires: String,
        bVal: String?,
        asn: String?
    ): String {
        // 영화는 현행 VixSrc가 window.masterPlaylist.url에 내려주는 실제 경로를 우선 보존한다.
        // url 추출에 실패하는 경우에만 이전 방식(/playlist/{vid})으로 fallback 한다.
        var url = parsedPlaylistUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { normalizeVixUrl(baseUrl, it) }
            ?: "$baseUrl/playlist/$actualVid"

        url = appendQueryParamIfMissing(url, "b", bVal)
        url = appendQueryParamIfMissing(url, "token", token)
        url = appendQueryParamIfMissing(url, "expires", expires)
        url = appendQueryParamIfMissing(url, "h", "1")
        url = appendQueryParamIfMissing(url, "asn", asn)

        // 기존 코드의 lang=en은 서버가 명시하지 않은 경우에만 유지한다.
        url = appendQueryParamIfMissing(url, "lang", "en")
        return url
    }

    private fun logVixSrcMasterQualities(masterText: String) {
        try {
            val normalized = masterText.replace("\\r", "")
            val lines = normalized.lines().map { it.trim() }

            println("[STREMIOSUB-VIXSRC] 📺 ===== HLS QUALITY INSPECTION =====")

            val streamIndexes = lines.indices.filter {
                lines[it].startsWith("#EXT-X-STREAM-INF", ignoreCase = true)
            }

            if (streamIndexes.isEmpty()) {
                val resolutionTags = Regex(
                    """RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ).findAll(normalized).mapNotNull { match ->
                    val width = match.groupValues.getOrNull(1)?.toIntOrNull()
                    val height = match.groupValues.getOrNull(2)?.toIntOrNull()
                    if (width != null && height != null) width to height else null
                }.toList()

                val renditionHints = Regex(
                    """(?:rendition=|[/_-])(\d{3,4})p(?:[&/?_.-]|$)""",
                    RegexOption.IGNORE_CASE
                ).findAll(normalized)
                    .mapNotNull { it.groupValues.getOrNull(1)?.toIntOrNull() }
                    .toList()

                val allHeights = buildList {
                    addAll(resolutionTags.map { it.second })
                    addAll(renditionHints)
                }

                val maxHeight = allHeights.maxOrNull()
                val found1080 = allHeights.any { it >= 1080 }
                val segmentCount = lines.count {
                    it.isNotBlank() && !it.startsWith("#")
                }

                println(
                    "[STREMIOSUB-VIXSRC] 📺 No #EXT-X-STREAM-INF found -> " +
                        "response is a MEDIA playlist (single rendition) or has no variant table"
                )
                println("[STREMIOSUB-VIXSRC] 📺 Media playlist URI lines=$segmentCount")

                if (resolutionTags.isNotEmpty()) {
                    println(
                        "[STREMIOSUB-VIXSRC] 📺 Resolution tags=" +
                            resolutionTags.distinct().joinToString(", ") { "${it.first}x${it.second}" }
                    )
                }

                if (renditionHints.isNotEmpty()) {
                    println(
                        "[STREMIOSUB-VIXSRC] 📺 Rendition hints=" +
                            renditionHints.distinct().sorted().joinToString(", ") { "${it}p" }
                    )
                }

                println(
                    "[STREMIOSUB-VIXSRC] 📺 SUMMARY: variants=0 " +
                        "maxHeight=${maxHeight?.let { "${it}p" } ?: "UNKNOWN"} " +
                        "has1080=$found1080"
                )

                when {
                    found1080 ->
                        println("[STREMIOSUB-VIXSRC] ✅ PLAYLIST CONTAINS 1080p OR HIGHER HINT")
                    maxHeight != null ->
                        println("[STREMIOSUB-VIXSRC] ❌ PLAYLIST HAS NO 1080p; MAX=${maxHeight}p")
                    else ->
                        println(
                            "[STREMIOSUB-VIXSRC] ⚠️ Single media playlist does not advertise resolution; " +
                                "actual decoder size must be checked in player log"
                        )
                }

                println("[STREMIOSUB-VIXSRC] 📺 ===== END QUALITY INSPECTION =====")
                return
            }

            var maxHeight = 0
            var found1080 = false
            var variantCount = 0

            for (index in streamIndexes) {
                val info = lines[index]

                val resolution = Regex(
                    """RESOLUTION\s*=\s*(\d+)\s*x\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(info)

                val width = resolution?.groupValues?.getOrNull(1)?.toIntOrNull()
                val height = resolution?.groupValues?.getOrNull(2)?.toIntOrNull()

                val bandwidth = Regex(
                    """(?:^|,)BANDWIDTH\s*=\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(info)?.groupValues?.getOrNull(1)?.toLongOrNull()

                val averageBandwidth = Regex(
                    """AVERAGE-BANDWIDTH\s*=\s*(\d+)""",
                    RegexOption.IGNORE_CASE
                ).find(info)?.groupValues?.getOrNull(1)?.toLongOrNull()

                val codecs = Regex(
                    """CODECS\s*=\s*"([^"]+)""",
                    RegexOption.IGNORE_CASE
                ).find(info)?.groupValues?.getOrNull(1)

                val variantUrl = (index + 1 until lines.size)
                    .asSequence()
                    .map { lines[it] }
                    .firstOrNull { it.isNotBlank() && !it.startsWith("#") }

                if (height != null) {
                    maxHeight = maxOf(maxHeight, height)
                    if (height >= 1080) found1080 = true
                }

                val urlRendition = variantUrl?.let {
                    Regex(
                        """rendition=(\d{3,4})p""",
                        RegexOption.IGNORE_CASE
                    ).find(it)?.groupValues?.getOrNull(1)?.toIntOrNull()
                }

                if (urlRendition != null) {
                    maxHeight = maxOf(maxHeight, urlRendition)
                    if (urlRendition >= 1080) found1080 = true
                }

                variantCount++

                println(
                    "[STREMIOSUB-VIXSRC] 📺 VARIANT #$variantCount " +
                        "resolution=${if (width != null && height != null) "${width}x${height}" else "UNKNOWN"} " +
                        "bandwidth=${bandwidth ?: "UNKNOWN"} " +
                        "avgBandwidth=${averageBandwidth ?: "UNKNOWN"} " +
                        "codecs=${codecs ?: "UNKNOWN"}"
                )
                println(
                    "[STREMIOSUB-VIXSRC] 📺 VARIANT #$variantCount URL=${variantUrl ?: "NONE"}"
                )
            }

            println(
                "[STREMIOSUB-VIXSRC] 📺 SUMMARY: variants=$variantCount " +
                    "maxHeight=${if (maxHeight > 0) "${maxHeight}p" else "UNKNOWN"} " +
                    "has1080=$found1080"
            )

            when {
                found1080 ->
                    println("[STREMIOSUB-VIXSRC] ✅ SERVER MASTER CONTAINS 1080p")
                maxHeight > 0 ->
                    println("[STREMIOSUB-VIXSRC] ❌ SERVER MASTER HAS NO 1080p; MAX=${maxHeight}p")
                else ->
                    println("[STREMIOSUB-VIXSRC] ⚠️ Unable to determine maximum resolution")
            }

            println("[STREMIOSUB-VIXSRC] 📺 ===== END QUALITY INSPECTION =====")
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] ⚠️ HLS quality inspection failed: ${e.message}")
        }
    }


    private suspend fun probeForced1080(
        masterText: String,
        playerHeaders: Map<String, String>
    ) {
        println("[STREMIOSUB-VIXSRC] 🧪 ===== FORCE 1080P PROBE =====")

        try {
            val variantUrls = masterText
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") }
                .filter { Regex("""(?:[?&])rendition=\d{3,4}p(?:[&]|$)""", RegexOption.IGNORE_CASE).containsMatchIn(it) }

            if (variantUrls.isEmpty()) {
                println("[STREMIOSUB-VIXSRC] 🧪 PROBE SKIP: no rendition=*p variant URL found")
                return
            }

            fun renditionOf(url: String): Int? =
                Regex("""(?:[?&])rendition=(\d{3,4})p(?:[&]|$)""", RegexOption.IGNORE_CASE)
                    .find(url)
                    ?.groupValues
                    ?.getOrNull(1)
                    ?.toIntOrNull()

            val sourceUrl = variantUrls
                .firstOrNull { renditionOf(it) == 720 }
                ?: variantUrls.maxByOrNull { renditionOf(it) ?: -1 }

            if (sourceUrl == null) {
                println("[STREMIOSUB-VIXSRC] 🧪 PROBE SKIP: no usable source variant")
                return
            }

            val sourceRendition = renditionOf(sourceUrl)
            val renditionMatch = Regex(
                """([?&]rendition=)\d{3,4}p""",
                RegexOption.IGNORE_CASE
            ).find(sourceUrl)

            if (renditionMatch == null) {
                println("[STREMIOSUB-VIXSRC] 🧪 PROBE SKIP: rendition query parameter could not be replaced")
                return
            }

            val forced1080Url = sourceUrl.replaceRange(
                renditionMatch.range,
                "${renditionMatch.groupValues[1]}1080p"
            )

            println("[STREMIOSUB-VIXSRC] 🧪 Source rendition=${sourceRendition?.let { "${it}p" } ?: "UNKNOWN"}")
            println("[STREMIOSUB-VIXSRC] 🧪 Source URL=$sourceUrl")
            println("[STREMIOSUB-VIXSRC] 🧪 Forced URL=$forced1080Url")
            println("[STREMIOSUB-VIXSRC] 🧪 NOTE: token/expires/b are intentionally kept unchanged; only rendition is forced")

            var sourceBody: String? = null
            var sourceCode: Int? = null

            try {
                val sourceResponse = StremioC.customSession.get(sourceUrl, headers = playerHeaders)
                sourceCode = sourceResponse.code
                sourceBody = sourceResponse.text
                println(
                    "[STREMIOSUB-VIXSRC] 🧪 SOURCE GET: code=${sourceResponse.code} " +
                        "isM3U8=${sourceResponse.text.trimStart().startsWith("#EXTM3U")} " +
                        "bodyLength=${sourceResponse.text.length}"
                )
            } catch (e: Exception) {
                println("[STREMIOSUB-VIXSRC] 🧪 SOURCE GET EXCEPTION: ${e.message}")
            }

            try {
                val forcedResponse = StremioC.customSession.get(forced1080Url, headers = playerHeaders)
                val forcedBody = forcedResponse.text
                val forcedTrimmed = forcedBody.trimStart()
                val isM3u8 = forcedTrimmed.startsWith("#EXTM3U")
                val sameAsSource = sourceBody != null && sourceBody == forcedBody
                val preview = forcedTrimmed
                    .replace("\r", " ")
                    .replace("\n", " ")
                    .take(220)

                println(
                    "[STREMIOSUB-VIXSRC] 🧪 FORCED 1080 GET: code=${forcedResponse.code} " +
                        "isM3U8=$isM3u8 bodyLength=${forcedBody.length} sameAsSource=$sameAsSource"
                )
                println("[STREMIOSUB-VIXSRC] 🧪 FORCED 1080 BODY PREFIX=$preview")

                when {
                    forcedResponse.code == 403 ->
                        println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: 403 -> rendition is tied to token/signature; URL text replacement alone cannot unlock 1080p")

                    forcedResponse.code == 404 ->
                        println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: 404 -> forced 1080p rendition endpoint is not present for this asset")

                    forcedResponse.code !in 200..299 ->
                        println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: HTTP ${forcedResponse.code} -> forced 1080p endpoint rejected")

                    !isM3u8 ->
                        println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: HTTP 2xx but response is not an HLS playlist")

                    sameAsSource ->
                        println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: forced 1080 returns byte-identical playlist to source -> server fallback/alias, not a distinct 1080 rendition")

                    else -> {
                        val forcedHints = Regex(
                            """(?:RESOLUTION\s*=\s*\d+x(\d+)|rendition=(\d{3,4})p)""",
                            RegexOption.IGNORE_CASE
                        ).findAll(forcedBody).mapNotNull { m ->
                            m.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                                ?: m.groupValues.getOrNull(2)?.takeIf { it.isNotBlank() }?.toIntOrNull()
                        }.toList()

                        val maxHint = forcedHints.maxOrNull()
                        if (maxHint != null) {
                            println("[STREMIOSUB-VIXSRC] 🧪 FORCED 1080 playlist max hint=${maxHint}p")
                        }

                        println(
                            "[STREMIOSUB-VIXSRC] 🧪 VERDICT: forced URL returned a distinct valid HLS playlist; " +
                                "this is a POSSIBLE hidden 1080 endpoint and must be confirmed by decoder width/height or segment characteristics"
                        )
                    }
                }

                if (sourceCode != null) {
                    println("[STREMIOSUB-VIXSRC] 🧪 Comparison: sourceCode=$sourceCode forcedCode=${forcedResponse.code}")
                }
            } catch (e: Exception) {
                println("[STREMIOSUB-VIXSRC] 🧪 FORCED 1080 EXCEPTION: ${e.message}")
                println("[STREMIOSUB-VIXSRC] 🧪 VERDICT: forced URL request failed before a valid HLS response was obtained")
            }
        } finally {
            println("[STREMIOSUB-VIXSRC] 🧪 ===== END FORCE 1080P PROBE =====")
        }
    }

    suspend fun invokeVixSrc(
        loadData: StremioC.LoadData,
        targetType: String,
        globalSeason: Int?,
        globalEpisode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START <<<")

            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) {
                "$baseUrl/movie/$tmdbId"
            } else {
                "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"
            }

            val userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
            val acceptLanguage = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"

            // 1. API 통신용 헤더
            val apiHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to acceptLanguage,
                "User-Agent" to userAgent
            )

            // 2. Embed 문서 통신용 헤더
            val embedHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to acceptLanguage,
                "User-Agent" to userAgent,
                "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to """"Linux"""",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin"
            )

            // === [STEP 1] API 호출 ===
            val apiUrl = if (isMovie) {
                "$baseUrl/api/movie/$tmdbId"
            } else {
                "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            }
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = apiHeaders)

            // JSON 응답의 이스케이프된 슬래시(\/)를 먼저 원래대로 복구해야 정규식이 정상 작동한다.
            val cleanApiResponse = apiResponseObj.text.replace("\\/", "/")
            val embedMatch = EMBED_REGEX.find(cleanApiResponse)

            if (embedMatch == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Step 1 Failed: Embed URL not found.")
                return
            }

            val embedPath = embedMatch.groupValues[1]
            val actualVid = embedMatch.groupValues[2]
            val embedUrl = baseUrl + embedPath
            println("[STREMIOSUB-VIXSRC] 🔗 Step 1 Success: embedUrl = $embedUrl")

            // === [STEP 2] Embed HTML 호출 ===
            println("[STREMIOSUB-VIXSRC] 🛰️ Step 2: Requesting Embed HTML...")
            val embedHtmlResponse = StremioC.customSession.get(embedUrl, headers = embedHeaders)
            val rawHtml = embedHtmlResponse.text

            var token: String? = null
            var expires: String? = null
            var bVal: String? = null
            var asn: String? = null
            var parsedPlaylistUrl: String? = null

            println("[STREMIOSUB-VIXSRC] ⚙️ Step 3: Parsing masterPlaylist block...")

            val markerIndex = rawHtml.indexOf("window.masterPlaylist")
                .takeIf { it >= 0 }
                ?: rawHtml.indexOf("masterPlaylist").takeIf { it >= 0 }

            if (markerIndex != null) {
                val blockEnd = minOf(rawHtml.length, markerIndex + MASTER_PLAYLIST_BLOCK_LEN)
                val cleanBlock = rawHtml.substring(markerIndex, blockEnd)
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                token = TOKEN_REGEX.find(cleanBlock)?.groupValues?.get(1)
                expires = EXPIRY_REGEX.find(cleanBlock)?.groupValues?.get(1)
                bVal = B_REGEX.find(cleanBlock)?.groupValues?.get(1)
                asn = ASN_REGEX.find(cleanBlock)?.groupValues?.get(1)
                parsedPlaylistUrl = PLAYLIST_URL_REGEX.find(cleanBlock)?.groupValues?.get(1)

                println(
                    "[STREMIOSUB-VIXSRC] ⚙️ masterPlaylist fields: " +
                        "url=${if (parsedPlaylistUrl.isNullOrBlank()) "MISS" else "OK"}, " +
                        "token=${if (token.isNullOrBlank()) "MISS" else "OK"}, " +
                        "expires=${if (expires.isNullOrBlank()) "MISS" else "OK"}, " +
                        "asn=${if (asn.isNullOrBlank()) "NONE" else "OK"}, " +
                        "b=${if (bVal.isNullOrBlank()) "NONE" else "OK"}"
                )
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ window.masterPlaylist marker not found.")
            }

            if (token == null || expires == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Step 3 Failed: Token or Expires is null.")
                return
            }

            // TV는 기존 동작을 그대로 유지한다.
            // 영화만 실제 window.masterPlaylist.url을 우선 사용하여 403 원인을 제거한다.
            val masterPlaylistUrl = if (isMovie) {
                val resolved = buildMoviePlaylistUrl(
                    baseUrl = baseUrl,
                    parsedPlaylistUrl = parsedPlaylistUrl,
                    actualVid = actualVid,
                    token = token,
                    expires = expires,
                    bVal = bVal,
                    asn = asn
                )

                if (parsedPlaylistUrl.isNullOrBlank()) {
                    println("[STREMIOSUB-VIXSRC] ⚠️ Movie masterPlaylist.url missing even after block parsing; using legacy /playlist/$actualVid fallback.")
                } else {
                    println("[STREMIOSUB-VIXSRC] 🎬 Movie masterPlaylist.url detected and preserved.")
                }
                resolved
            } else {
                val bParam = if (bVal != null) "b=$bVal&" else ""
                "$baseUrl/playlist/$actualVid?${bParam}token=$token&expires=$expires&h=1&lang=en"
            }

            // === [STEP 3.5] 세션 쿠키 보존 ===
            val cookiesMap = mutableMapOf<String, String>()

            try {
                apiResponseObj.headers.forEach { pair ->
                    if (pair.first.equals("Set-Cookie", ignoreCase = true)) {
                        val value = pair.second
                        val semiColonIndex = value.indexOf(';')
                        val cookiePart = if (semiColonIndex > 0) value.substring(0, semiColonIndex) else value
                        val equalsIndex = cookiePart.indexOf('=')
                        if (equalsIndex > 0) {
                            cookiesMap[cookiePart.substring(0, equalsIndex).trim()] =
                                cookiePart.substring(equalsIndex + 1).trim()
                        }
                    }
                }

                embedHtmlResponse.headers.forEach { pair ->
                    if (pair.first.equals("Set-Cookie", ignoreCase = true)) {
                        val value = pair.second
                        val semiColonIndex = value.indexOf(';')
                        val cookiePart = if (semiColonIndex > 0) value.substring(0, semiColonIndex) else value
                        val equalsIndex = cookiePart.indexOf('=')
                        if (equalsIndex > 0) {
                            cookiesMap[cookiePart.substring(0, equalsIndex).trim()] =
                                cookiePart.substring(equalsIndex + 1).trim()
                        }
                    }
                }
            } catch (e: Exception) {
                println("[STREMIOSUB-VIXSRC] ⚠️ Cookie parsing warning: ${e.message}")
            }

            val cookieHeaderValue = cookiesMap.entries.joinToString("; ") { "${it.key}=${it.value}" }
            if (cookieHeaderValue.isNotBlank()) {
                println("[STREMIOSUB-VIXSRC] 🍪 Successfully Extracted Session Cookies")
            }

            // === [STEP 4] 플레이어 전송 ===
            println("[STREMIOSUB-VIXSRC] ⚡ Final Playlist URL: $masterPlaylistUrl")

            fun makePlayerHeaders(referer: String, includeOrigin: Boolean): MutableMap<String, String> =
                mapOf(
                    "Referer" to referer,
                    "Accept" to "*/*",
                    "Accept-Language" to acceptLanguage,
                    "User-Agent" to userAgent,
                    "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Linux"""",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ).toMutableMap().apply {
                    if (includeOrigin) put("Origin", baseUrl)
                    if (cookieHeaderValue.isNotEmpty()) put("Cookie", cookieHeaderValue)
                }

            // TV는 현재 정상 동작을 그대로 보존한다.
            // 영화는 master playlist 자체를 사전 조회해서 실제 #EXTM3U를 반환하는 Referer 조합을 선택한다.
            val playerHeaders: MutableMap<String, String>

            if (isMovie) {
                val candidates = listOf(
                    "root" to makePlayerHeaders("$baseUrl/", includeOrigin = true),
                    "api" to makePlayerHeaders(apiUrl, includeOrigin = true),
                    "embed" to makePlayerHeaders(embedUrl, includeOrigin = true)
                )

                var verifiedHeaders: MutableMap<String, String>? = null
                var verifiedMode: String? = null
                var verifiedPlaylistText: String? = null

                for ((mode, headers) in candidates) {
                    try {
                        val verificationResponse =
                            StremioC.customSession.get(masterPlaylistUrl, headers = headers)
                        val verificationText = verificationResponse.text.trimStart()

                        if (verificationText.startsWith("#EXTM3U")) {
                            verifiedHeaders = headers
                            verifiedMode = mode
                            verifiedPlaylistText = verificationResponse.text
                            println("[STREMIOSUB-VIXSRC] ✅ Movie playlist verified with Referer mode=$mode")
                            break
                        } else {
                            val preview = verificationText
                                .replace("\n", " ")
                                .replace("\r", " ")
                                .take(80)
                            println(
                                "[STREMIOSUB-VIXSRC] ⚠️ Movie playlist verify failed mode=$mode " +
                                    "bodyPrefix=$preview"
                            )
                        }
                    } catch (e: Exception) {
                        println(
                            "[STREMIOSUB-VIXSRC] ⚠️ Movie playlist verify exception mode=$mode: ${e.message}"
                        )
                    }
                }

                if (verifiedHeaders == null) {
                    println("[STREMIOSUB-VIXSRC] ❌ Movie playlist verification failed for all Referer modes.")
                    return
                }

                playerHeaders = verifiedHeaders ?: return
                verifiedPlaylistText?.let {
                    logVixSrcMasterQualities(it)
                    probeForced1080(it, playerHeaders)
                }
                println("[STREMIOSUB-VIXSRC] 🧭 Player request mode=MOVIE($verifiedMode referer)")
            } else {
                playerHeaders = makePlayerHeaders(embedUrl, includeOrigin = false)
                println("[STREMIOSUB-VIXSRC] 🧭 Player request mode=TV(legacy embed referer)")
            }

            callback.invoke(
                newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P1080.value
                    this.headers = playerHeaders
                }
            )

        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
            e.printStackTrace()
        }
    }
}
