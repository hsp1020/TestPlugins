// 파일명: hsp1020/VixSrcExtractor.kt
package com.hsp1020

import com.lagradost.cloudstream3.AudioFile
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newAudioFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.URI

object VixSrcExtractor {

    private const val BASE_URL = "https://vixsrc.to"
    private const val MASTER_PLAYLIST_BLOCK_LEN = 2500
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"

    private val EMBED_REGEX = """(/embed/(\d+)[^"'\s]*)""".toRegex()
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
    private val HLS_ATTRIBUTE_REGEX =
        """([A-Z0-9-]+)=("[^"]*"|[^,]*)""".toRegex(RegexOption.IGNORE_CASE)
    private val RESOLUTION_REGEX =
        """RESOLUTION\s*=\s*(\d+)x(\d+)""".toRegex(RegexOption.IGNORE_CASE)
    private val RENDITION_HEIGHT_REGEX =
        """(?:[?&]rendition=|[/_-])(\d{3,4})p(?:[&/?_.-]|$)""".toRegex(RegexOption.IGNORE_CASE)

    private data class MasterPlaylistData(
        val url: String?,
        val token: String,
        val expires: String,
        val b: String?,
        val asn: String?
    )

    private data class HlsVariant(
        val url: String,
        val height: Int,
        val bandwidth: Long,
        val audioGroup: String?
    )

    private data class HlsAudioTrack(
        val groupId: String?,
        val url: String,
        val isDefault: Boolean
    )

    private data class HlsSubtitleTrack(
        val label: String,
        val playlistUrl: String
    )

    private fun normalizeVixUrl(rawUrl: String): String {
        val cleaned = rawUrl
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")
            .trim()

        return when {
            cleaned.startsWith("https://", ignoreCase = true) ||
                cleaned.startsWith("http://", ignoreCase = true) -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> BASE_URL + cleaned
            else -> "$BASE_URL/${cleaned.trimStart('/')}"
        }
    }

    private fun resolveHlsUrl(baseUrl: String, rawUrl: String): String {
        val cleaned = rawUrl
            .trim()
            .trim('"', '\'')
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        if (cleaned.startsWith("http://", ignoreCase = true) ||
            cleaned.startsWith("https://", ignoreCase = true)
        ) {
            return cleaned
        }

        return try {
            URI(baseUrl).resolve(cleaned).toString()
        } catch (_: Exception) {
            when {
                cleaned.startsWith("//") -> "https:$cleaned"
                cleaned.startsWith("/") -> BASE_URL + cleaned
                else -> baseUrl.substringBeforeLast('/', BASE_URL) + "/" + cleaned
            }
        }
    }

    private fun appendQueryParamIfMissing(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank()) return url
        if (Regex("(?:[?&])${Regex.escape(key)}=").containsMatchIn(url)) return url
        return url + if (url.contains('?')) "&$key=$value" else "?$key=$value"
    }

    private fun parseMasterPlaylist(rawHtml: String): MasterPlaylistData? {
        val markerIndex = rawHtml.indexOf("window.masterPlaylist")
            .takeIf { it >= 0 }
            ?: rawHtml.indexOf("masterPlaylist").takeIf { it >= 0 }
            ?: return null

        val block = rawHtml
            .substring(markerIndex, minOf(rawHtml.length, markerIndex + MASTER_PLAYLIST_BLOCK_LEN))
            .replace("\\u0026", "&")
            .replace("\\/", "/")
            .replace("&amp;", "&")

        val token = TOKEN_REGEX.find(block)?.groupValues?.get(1) ?: return null
        val expires = EXPIRY_REGEX.find(block)?.groupValues?.get(1) ?: return null

        return MasterPlaylistData(
            url = PLAYLIST_URL_REGEX.find(block)?.groupValues?.get(1),
            token = token,
            expires = expires,
            b = B_REGEX.find(block)?.groupValues?.get(1),
            asn = ASN_REGEX.find(block)?.groupValues?.get(1)
        )
    }

    private fun buildMoviePlaylistUrl(actualVid: String, data: MasterPlaylistData): String {
        var url = data.url
            ?.takeIf { it.isNotBlank() }
            ?.let(::normalizeVixUrl)
            ?: "$BASE_URL/playlist/$actualVid"

        url = appendQueryParamIfMissing(url, "b", data.b)
        url = appendQueryParamIfMissing(url, "token", data.token)
        url = appendQueryParamIfMissing(url, "expires", data.expires)
        url = appendQueryParamIfMissing(url, "h", "1")
        url = appendQueryParamIfMissing(url, "asn", data.asn)
        url = appendQueryParamIfMissing(url, "lang", "en")
        return url
    }

    private fun parseAttributes(line: String): Map<String, String> =
        HLS_ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].uppercase() to match.groupValues[2].trim().trim('"')
        }

    private fun parseVariants(masterUrl: String, masterText: String): List<HlsVariant> {
        val lines = masterText.lineSequence().map { it.trim() }.toList()
        val variants = mutableListOf<HlsVariant>()

        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                index++
                continue
            }

            var uriIndex = index + 1
            while (uriIndex < lines.size &&
                (lines[uriIndex].isBlank() || lines[uriIndex].startsWith('#'))
            ) {
                uriIndex++
            }
            if (uriIndex >= lines.size) break

            val attributes = parseAttributes(line.substringAfter(':'))
            val childUrl = resolveHlsUrl(masterUrl, lines[uriIndex])
            val resolutionHeight = attributes["RESOLUTION"]
                ?.substringAfterLast('x')
                ?.toIntOrNull()
                ?: RESOLUTION_REGEX.find(line)?.groupValues?.getOrNull(2)?.toIntOrNull()
            val renditionHeight = RENDITION_HEIGHT_REGEX.find(childUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            variants += HlsVariant(
                url = childUrl,
                height = resolutionHeight ?: renditionHeight ?: 0,
                bandwidth = attributes["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                audioGroup = attributes["AUDIO"]?.takeIf { it.isNotBlank() }
            )
            index = uriIndex + 1
        }

        return variants
            .distinctBy { it.url }
            .filter { it.height > 0 }
            .groupBy { it.height }
            .values
            .mapNotNull { sameHeight -> sameHeight.maxByOrNull { it.bandwidth } }
            .sortedByDescending { it.height }
    }

    private fun parseAudioTracks(masterUrl: String, masterText: String): List<HlsAudioTrack> =
        masterText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line ->
                val attributes = parseAttributes(line.substringAfter(':'))
                if (!attributes["TYPE"].equals("AUDIO", ignoreCase = true)) return@mapNotNull null

                val rawUri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                HlsAudioTrack(
                    groupId = attributes["GROUP-ID"]?.takeIf { it.isNotBlank() },
                    url = resolveHlsUrl(masterUrl, rawUri),
                    isDefault = attributes["DEFAULT"].equals("YES", ignoreCase = true)
                )
            }
            .distinctBy { it.url }
            .toList()

    private fun selectAudioTrack(
        variant: HlsVariant,
        audioTracks: List<HlsAudioTrack>
    ): HlsAudioTrack? {
        val matching = variant.audioGroup
            ?.let { group -> audioTracks.filter { it.groupId == group } }
            ?.takeIf { it.isNotEmpty() }
            ?: audioTracks

        return matching.firstOrNull { it.isDefault } ?: matching.firstOrNull()
    }

    private fun parseSubtitleTracks(masterUrl: String, masterText: String): List<HlsSubtitleTrack> =
        masterText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .mapNotNull { line ->
                val attributes = parseAttributes(line.substringAfter(':'))
                if (!attributes["TYPE"].equals("SUBTITLES", ignoreCase = true)) return@mapNotNull null

                val rawUri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val label = attributes["NAME"]
                    ?.takeIf { it.isNotBlank() }
                    ?: attributes["LANGUAGE"]?.takeIf { it.isNotBlank() }
                    ?: "VixSrc"

                HlsSubtitleTrack(
                    label = label,
                    playlistUrl = resolveHlsUrl(masterUrl, rawUri)
                )
            }
            .distinctBy { it.label to it.playlistUrl }
            .toList()

    private suspend fun resolveSubtitleVttUrl(
        track: HlsSubtitleTrack,
        headers: Map<String, String>
    ): String? {
        val responseText = StremioC.customSession.get(track.playlistUrl, headers = headers).text
        val trimmed = responseText.trimStart()

        // Some providers may return the subtitle file directly instead of an HLS subtitle playlist.
        if (trimmed.startsWith("WEBVTT", ignoreCase = true)) return track.playlistUrl
        if (!trimmed.startsWith("#EXTM3U", ignoreCase = true)) return null

        val mediaUrls = responseText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith('#') }
            .map { resolveHlsUrl(track.playlistUrl, it) }
            .toList()

        // VixSrc currently exposes one full-length VTT per language. Do not emit a partial
        // subtitle if the server changes to a multi-segment subtitle playlist.
        return mediaUrls.singleOrNull()
            ?.takeIf { it.contains(".vtt", ignoreCase = true) }
    }

    private suspend fun emitResolvedSubtitles(
        masterUrl: String,
        masterText: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(6)
        parseSubtitleTracks(masterUrl, masterText)
            .map { track ->
                async {
                    semaphore.withPermit {
                        try {
                            resolveSubtitleVttUrl(track, headers)
                                ?.let { SubtitleFile(track.label, it) }
                        } catch (c: CancellationException) {
                            throw c
                        } catch (_: Exception) {
                            null
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .forEach(subtitleCallback)
    }

    private suspend fun buildAudioFiles(
        variant: HlsVariant,
        availableAudioTracks: List<HlsAudioTrack>,
        headers: Map<String, String>
    ): List<AudioFile> {
        val selectedAudio = selectAudioTrack(variant, availableAudioTracks) ?: return emptyList()
        return listOf(
            newAudioFile(selectedAudio.url) {
                this.headers = headers
            }
        )
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
            val id = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) {
                "$BASE_URL/movie/$id"
            } else {
                "$BASE_URL/tv/$id/$globalSeason/$globalEpisode"
            }
            val apiUrl = if (isMovie) {
                "$BASE_URL/api/movie/$id"
            } else {
                "$BASE_URL/api/tv/$id/$globalSeason/$globalEpisode"
            }

            val apiHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to ACCEPT_LANGUAGE,
                "User-Agent" to USER_AGENT
            )
            val embedHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "Accept-Language" to ACCEPT_LANGUAGE,
                "User-Agent" to USER_AGENT,
                "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to """"Linux"""",
                "Sec-Fetch-Dest" to "iframe",
                "Sec-Fetch-Mode" to "navigate",
                "Sec-Fetch-Site" to "same-origin"
            )

            val apiResponse = StremioC.customSession.get(apiUrl, headers = apiHeaders)
            val embedMatch = EMBED_REGEX.find(apiResponse.text.replace("\\/", "/")) ?: run {
                println("[STREMIOSUB-VIXSRC] Embed URL not found")
                return
            }

            val actualVid = embedMatch.groupValues[2]
            val embedUrl = BASE_URL + embedMatch.groupValues[1]
            val embedResponse = StremioC.customSession.get(embedUrl, headers = embedHeaders)
            val playlistData = parseMasterPlaylist(embedResponse.text) ?: run {
                println("[STREMIOSUB-VIXSRC] masterPlaylist data not found")
                return
            }

            val masterPlaylistUrl = if (isMovie) {
                buildMoviePlaylistUrl(actualVid, playlistData)
            } else {
                val bParam = playlistData.b?.let { "b=$it&" } ?: ""
                "$BASE_URL/playlist/$actualVid?${bParam}token=${playlistData.token}" +
                    "&expires=${playlistData.expires}&h=1&lang=en"
            }

            val cookies = mutableMapOf<String, String>()
            listOf(apiResponse.headers, embedResponse.headers).forEach { headers ->
                headers.forEach cookieLoop@ { pair ->
                    if (!pair.first.equals("Set-Cookie", ignoreCase = true)) return@cookieLoop
                    val cookie = pair.second.substringBefore(';')
                    val separator = cookie.indexOf('=')
                    if (separator > 0) {
                        cookies[cookie.substring(0, separator).trim()] =
                            cookie.substring(separator + 1).trim()
                    }
                }
            }
            val cookieHeader = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }

            fun playerHeaders(referer: String, includeOrigin: Boolean): MutableMap<String, String> =
                mapOf(
                    "Referer" to referer,
                    "Accept" to "*/*",
                    "Accept-Language" to ACCEPT_LANGUAGE,
                    "User-Agent" to USER_AGENT,
                    "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Linux"""",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ).toMutableMap().apply {
                    if (includeOrigin) put("Origin", BASE_URL)
                    if (cookieHeader.isNotEmpty()) put("Cookie", cookieHeader)
                }

            var masterText: String? = null

            val finalHeaders = if (isMovie) {
                val candidates = listOf(
                    playerHeaders("$BASE_URL/", includeOrigin = true),
                    playerHeaders(apiUrl, includeOrigin = true),
                    playerHeaders(embedUrl, includeOrigin = true)
                )

                var verified: MutableMap<String, String>? = null
                for (headers in candidates) {
                    val text = try {
                        StremioC.customSession.get(masterPlaylistUrl, headers = headers).text
                    } catch (_: Exception) {
                        null
                    }
                    if (text?.trimStart()?.startsWith("#EXTM3U") == true) {
                        verified = headers
                        masterText = text
                        break
                    }
                }

                verified ?: run {
                    println("[STREMIOSUB-VIXSRC] Movie playlist verification failed")
                    return
                }
            } else {
                val headers = playerHeaders(embedUrl, includeOrigin = false)
                masterText = try {
                    StremioC.customSession
                        .get(masterPlaylistUrl, headers = headers)
                        .text
                        .takeIf { it.trimStart().startsWith("#EXTM3U") }
                } catch (_: Exception) {
                    null
                }
                headers
            }

            val master = masterText
            if (master == null) {
                callback(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = finalHeaders
                    }
                )
                return
            }

            emitResolvedSubtitles(masterPlaylistUrl, master, finalHeaders, subtitleCallback)

            val variants = parseVariants(masterPlaylistUrl, master)
            val availableAudioTracks = parseAudioTracks(masterPlaylistUrl, master)

            if (variants.isEmpty()) {
                callback(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = finalHeaders
                    }
                )
                return
            }

            var emittedVariantCount = 0
            for (variant in variants) {
                val separateAudio = buildAudioFiles(variant, availableAudioTracks, finalHeaders)

                // A variant that explicitly references an AUDIO group is video-only.
                // Never expose it without the matching separate audio source.
                if (variant.audioGroup != null && separateAudio.isEmpty()) continue

                callback(
                    newExtractorLink(
                        "VixSrc",
                        "VixSrc ${variant.height}p",
                        variant.url,
                        ExtractorLinkType.M3U8
                    ) {
                        quality = variant.height
                        headers = finalHeaders
                        this.audioTracks = separateAudio
                    }
                )
                emittedVariantCount++
            }

            if (emittedVariantCount == 0) {
                callback(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        quality = Qualities.P1080.value
                        headers = finalHeaders
                    }
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
