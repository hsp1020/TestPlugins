
package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.newSubtitleFile
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
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.net.URI

object VixSrcExtractor {

    private const val BASE_URL = "https://vixsrc.to"
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
    private const val MASTER_BLOCK_LEN = 2500
    private const val SOURCE = "StremioC"
    private const val SYNTHETIC_PREFIX = "VIXSRC_SYNTHETIC_MASTER_V1\n"
    private const val SYNTHETIC_PATH = "/__cs3_vixsrc__/"

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
    private val ATTRIBUTE_REGEX =
        """([A-Z0-9-]+)=("[^"]*"|[^,]*)""".toRegex(RegexOption.IGNORE_CASE)
    private val RESOLUTION_REGEX =
        """RESOLUTION\s*=\s*(\d+)x(\d+)""".toRegex(RegexOption.IGNORE_CASE)
    private val RENDITION_HEIGHT_REGEX =
        """(?:[?&]rendition=|[/_-])(\d{3,4})p(?:[&/?_.-]|$)""".toRegex(RegexOption.IGNORE_CASE)

    private data class PlaylistData(
        val url: String?,
        val token: String,
        val expires: String,
        val b: String?,
        val asn: String?
    )

    private data class Variant(
        val url: String,
        val width: Int,
        val height: Int,
        val bandwidth: Long,
        val averageBandwidth: Long,
        val codecs: String?,
        val audioGroup: String?
    )

    private data class AudioTrack(
        val groupId: String,
        val name: String,
        val language: String?,
        val channels: String?,
        val url: String,
        val isDefault: Boolean,
        val autoSelect: Boolean
    )

    private data class SubtitleTrack(
        val label: String,
        val playlistUrl: String
    )

    private data class MasterResult(
        val text: String?,
        val headers: MutableMap<String, String>
    )

    private fun decodeEscapes(value: String): String = value
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")

    private fun cleanUrl(raw: String): String = decodeEscapes(raw).trim().trim('"', '\'')

    private fun absoluteVixUrl(raw: String): String {
        val url = cleanUrl(raw)
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> BASE_URL + url
            else -> "$BASE_URL/${url.trimStart('/')}"
        }
    }

    private fun resolveUrl(baseUrl: String, raw: String): String {
        val url = cleanUrl(raw)
        if (url.startsWith("http://", true) || url.startsWith("https://", true)) return url

        return try {
            URI(baseUrl).resolve(url).toString()
        } catch (_: Exception) {
            when {
                url.startsWith("//") -> "https:$url"
                url.startsWith("/") -> BASE_URL + url
                else -> baseUrl.substringBeforeLast('/', BASE_URL) + "/" + url
            }
        }
    }

    private fun appendQuery(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank()) return url
        if (Regex("(?:[?&])${Regex.escape(key)}=").containsMatchIn(url)) return url
        return url + if ('?' in url) "&$key=$value" else "?$key=$value"
    }

    private fun parsePlaylistData(html: String): PlaylistData? {
        val start = html.indexOf("window.masterPlaylist")
            .takeIf { it >= 0 }
            ?: html.indexOf("masterPlaylist").takeIf { it >= 0 }
            ?: return null

        val block = decodeEscapes(
            html.substring(start, minOf(html.length, start + MASTER_BLOCK_LEN))
        )
        val token = TOKEN_REGEX.find(block)?.groupValues?.get(1) ?: return null
        val expires = EXPIRY_REGEX.find(block)?.groupValues?.get(1) ?: return null

        return PlaylistData(
            url = PLAYLIST_URL_REGEX.find(block)?.groupValues?.get(1),
            token = token,
            expires = expires,
            b = B_REGEX.find(block)?.groupValues?.get(1),
            asn = ASN_REGEX.find(block)?.groupValues?.get(1)
        )
    }

    private fun buildMasterUrl(vid: String, data: PlaylistData, isMovie: Boolean): String {
        var url = if (isMovie) {
            data.url?.takeIf(String::isNotBlank)?.let(::absoluteVixUrl) ?: "$BASE_URL/playlist/$vid"
        } else {
            "$BASE_URL/playlist/$vid"
        }

        url = appendQuery(url, "b", data.b)
        url = appendQuery(url, "token", data.token)
        url = appendQuery(url, "expires", data.expires)
        url = appendQuery(url, "h", "1")
        if (isMovie) url = appendQuery(url, "asn", data.asn)
        return appendQuery(url, "lang", "en")
    }

    private fun attributes(line: String): Map<String, String> =
        ATTRIBUTE_REGEX.findAll(line).associate { match ->
            match.groupValues[1].uppercase() to match.groupValues[2].trim().trim('"')
        }

    private fun parseVariants(masterUrl: String, master: String): List<Variant> {
        val lines = master.lineSequence().map(String::trim).toList()
        val result = mutableListOf<Variant>()
        var index = 0

        while (index < lines.size) {
            val line = lines[index]
            if (!line.startsWith("#EXT-X-STREAM-INF", true)) {
                index++
                continue
            }

            var uriIndex = index + 1
            while (uriIndex < lines.size &&
                (lines[uriIndex].isBlank() || lines[uriIndex].startsWith('#'))
            ) uriIndex++
            if (uriIndex >= lines.size) break

            val attrs = attributes(line.substringAfter(':'))
            val childUrl = resolveUrl(masterUrl, lines[uriIndex])
            val resolution = attrs["RESOLUTION"]?.split('x', limit = 2)
            val width = resolution?.getOrNull(0)?.toIntOrNull() ?: 0
            val height = resolution?.getOrNull(1)?.toIntOrNull()
                ?: RESOLUTION_REGEX.find(line)?.groupValues?.getOrNull(2)?.toIntOrNull()
                ?: RENDITION_HEIGHT_REGEX.find(childUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: 0

            if (height > 0) {
                result += Variant(
                    url = childUrl,
                    width = width,
                    height = height,
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull() ?: 0L,
                    averageBandwidth = attrs["AVERAGE-BANDWIDTH"]?.toLongOrNull() ?: 0L,
                    codecs = attrs["CODECS"]?.takeIf(String::isNotBlank),
                    audioGroup = attrs["AUDIO"]?.takeIf(String::isNotBlank)
                )
            }
            index = uriIndex + 1
        }

        return result
            .distinctBy(Variant::url)
            .groupBy(Variant::height)
            .values
            .mapNotNull { variants -> variants.maxByOrNull(Variant::bandwidth) }
            .sortedByDescending(Variant::height)
    }

    private fun mediaEntries(master: String, type: String): Sequence<Map<String, String>> =
        master.lineSequence()
            .map(String::trim)
            .filter { it.startsWith("#EXT-X-MEDIA:", true) }
            .map { attributes(it.substringAfter(':')) }
            .filter { it["TYPE"].equals(type, true) }

    private fun parseAudioTracks(masterUrl: String, master: String): List<AudioTrack> =
        mediaEntries(master, "AUDIO")
            .mapNotNull { attrs ->
                val groupId = attrs["GROUP-ID"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val uri = attrs["URI"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                AudioTrack(
                    groupId = groupId,
                    name = attrs["NAME"]?.takeIf(String::isNotBlank) ?: "Audio",
                    language = attrs["LANGUAGE"]?.takeIf(String::isNotBlank),
                    channels = attrs["CHANNELS"]?.takeIf(String::isNotBlank),
                    url = resolveUrl(masterUrl, uri),
                    isDefault = attrs["DEFAULT"].equals("YES", true),
                    autoSelect = attrs["AUTOSELECT"].equals("YES", true)
                )
            }
            .distinctBy { it.groupId to it.url }
            .toList()

    private fun quote(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    private fun buildSyntheticMaster(variant: Variant, allAudio: List<AudioTrack>): String? {
        val audio = variant.audioGroup
            ?.let { group -> allAudio.filter { it.groupId == group } }
            .orEmpty()

        if (variant.audioGroup != null && audio.isEmpty()) return null
        val hasDefault = audio.any(AudioTrack::isDefault)

        return buildString {
            appendLine("#EXTM3U")
            appendLine("#EXT-X-VERSION:3")
            appendLine("#EXT-X-INDEPENDENT-SEGMENTS")

            audio.forEachIndexed { index, track ->
                val attrs = mutableListOf(
                    "TYPE=AUDIO",
                    "GROUP-ID=${quote(track.groupId)}",
                    "NAME=${quote(track.name)}",
                    "DEFAULT=${if (track.isDefault || (!hasDefault && index == 0)) "YES" else "NO"}",
                    "AUTOSELECT=${if (track.autoSelect || index == 0) "YES" else "NO"}"
                )
                track.language?.let { attrs += "LANGUAGE=${quote(it)}" }
                track.channels?.let { attrs += "CHANNELS=${quote(it)}" }
                attrs += "URI=${quote(track.url)}"
                appendLine("#EXT-X-MEDIA:${attrs.joinToString(",")}")
            }

            val streamAttrs = mutableListOf("BANDWIDTH=${variant.bandwidth.coerceAtLeast(1L)}")
            if (variant.averageBandwidth > 0) {
                streamAttrs += "AVERAGE-BANDWIDTH=${variant.averageBandwidth}"
            }
            if (variant.width > 0) streamAttrs += "RESOLUTION=${variant.width}x${variant.height}"
            variant.codecs?.let { streamAttrs += "CODECS=${quote(it)}" }
            variant.audioGroup?.let { streamAttrs += "AUDIO=${quote(it)}" }

            appendLine("#EXT-X-STREAM-INF:${streamAttrs.joinToString(",")}")
            appendLine(variant.url)
        }
    }

    private fun parseSubtitleTracks(masterUrl: String, master: String): List<SubtitleTrack> =
        mediaEntries(master, "SUBTITLES")
            .mapNotNull { attrs ->
                val uri = attrs["URI"]?.takeIf(String::isNotBlank) ?: return@mapNotNull null
                val label = attrs["NAME"]?.takeIf(String::isNotBlank)
                    ?: attrs["LANGUAGE"]?.takeIf(String::isNotBlank)
                    ?: "VixSrc"

                SubtitleTrack(label, resolveUrl(masterUrl, uri))
            }
            .distinctBy { it.label to it.playlistUrl }
            .toList()

    private suspend fun resolveVtt(track: SubtitleTrack, headers: Map<String, String>): String? {
        val text = StremioC.customSession.get(track.playlistUrl, headers = headers).text
        val trimmed = text.trimStart()
        if (trimmed.startsWith("WEBVTT", true)) return track.playlistUrl
        if (!trimmed.startsWith("#EXTM3U", true)) return null

        val urls = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .map { resolveUrl(track.playlistUrl, it) }
            .toList()

        return urls.singleOrNull()?.takeIf { it.contains(".vtt", true) }
    }

    private suspend fun emitSubtitles(
        masterUrl: String,
        master: String,
        headers: Map<String, String>,
        callback: (SubtitleFile) -> Unit
    ) = coroutineScope {
        val semaphore = Semaphore(6)
        parseSubtitleTracks(masterUrl, master)
            .map { track ->
                async {
                    semaphore.withPermit {
                        try {
                            resolveVtt(track, headers)?.let { url ->
                                newSubtitleFile(track.label, url) { this.headers = headers }
                            }
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
            .forEach(callback)
    }

    private fun apiHeaders(referer: String) = mapOf(
        "Referer" to referer,
        "Accept" to "application/json, text/plain, */*",
        "Accept-Language" to ACCEPT_LANGUAGE,
        "User-Agent" to USER_AGENT
    )

    private fun embedHeaders(referer: String) = mapOf(
        "Referer" to referer,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
        "Accept-Language" to ACCEPT_LANGUAGE,
        "User-Agent" to USER_AGENT,
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\"",
        "Sec-Fetch-Dest" to "iframe",
        "Sec-Fetch-Mode" to "navigate",
        "Sec-Fetch-Site" to "same-origin"
    )

    private fun collectCookies(vararg headerSets: Iterable<Pair<String, String>>): String {
        val cookies = linkedMapOf<String, String>()
        for (headers in headerSets) {
            for ((name, value) in headers) {
                if (!name.equals("Set-Cookie", true)) continue
                val cookie = value.substringBefore(';')
                val separator = cookie.indexOf('=')
                if (separator > 0) {
                    cookies[cookie.substring(0, separator).trim()] =
                        cookie.substring(separator + 1).trim()
                }
            }
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun playerHeaders(
        referer: String,
        cookie: String,
        includeOrigin: Boolean
    ): MutableMap<String, String> = linkedMapOf(
        "Referer" to referer,
        "Accept" to "*/*",
        "Accept-Language" to ACCEPT_LANGUAGE,
        "User-Agent" to USER_AGENT,
        "Sec-Ch-Ua" to "\"Chromium\";v=\"137\", \"Not/A)Brand\";v=\"24\"",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to "\"Linux\"",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "same-origin"
    ).apply {
        if (includeOrigin) put("Origin", BASE_URL)
        if (cookie.isNotEmpty()) put("Cookie", cookie)
    }

    private suspend fun fetchMaster(
        isMovie: Boolean,
        apiUrl: String,
        embedUrl: String,
        masterUrl: String,
        cookie: String
    ): MasterResult? {
        if (!isMovie) {
            val headers = playerHeaders(embedUrl, cookie, includeOrigin = false)
            val text = try {
                StremioC.customSession.get(masterUrl, headers = headers).text
                    .takeIf { it.trimStart().startsWith("#EXTM3U") }
            } catch (_: Exception) {
                null
            }
            return MasterResult(text, headers)
        }

        val candidates = listOf(
            playerHeaders("$BASE_URL/", cookie, includeOrigin = true),
            playerHeaders(apiUrl, cookie, includeOrigin = true),
            playerHeaders(embedUrl, cookie, includeOrigin = true)
        )

        for (headers in candidates) {
            val text = try {
                StremioC.customSession.get(masterUrl, headers = headers).text
            } catch (_: Exception) {
                null
            }
            if (text?.trimStart()?.startsWith("#EXTM3U") == true) {
                return MasterResult(text, headers)
            }
        }
        return null
    }

    private suspend fun emitFallback(
        url: String,
        headers: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        callback(
            newExtractorLink("VixSrc", "VixSrc", url, ExtractorLinkType.M3U8) {
                quality = Qualities.P1080.value
                this.headers = headers
            }
        )
    }

    fun getVideoInterceptor(extractorLink: ExtractorLink): Interceptor? {
        val master = extractorLink.extractorData
            ?.takeIf { it.startsWith(SYNTHETIC_PREFIX) }
            ?.removePrefix(SYNTHETIC_PREFIX)
            ?: return null

        return Interceptor { chain ->
            val request = chain.request()
            if (request.url.host.equals("vixsrc.to", true) &&
                request.url.encodedPath.startsWith(SYNTHETIC_PATH)
            ) {
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .header("Content-Type", "application/vnd.apple.mpegurl")
                    .header("Cache-Control", "no-store")
                    .body(master.toResponseBody("application/vnd.apple.mpegurl".toMediaType()))
                    .build()
            } else {
                chain.proceed(request)
            }
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
            val id = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val suffix = if (isMovie) "movie/$id" else "tv/$id/$globalSeason/$globalEpisode"
            val requestUrl = "$BASE_URL/$suffix"
            val apiUrl = "$BASE_URL/api/$suffix"

            val apiResponse = StremioC.customSession.get(apiUrl, headers = apiHeaders(requestUrl))
            val embed = EMBED_REGEX.find(apiResponse.text.replace("\\/", "/")) ?: return
            val vid = embed.groupValues[2]
            val embedUrl = BASE_URL + embed.groupValues[1]

            val embedResponse = StremioC.customSession.get(
                embedUrl,
                headers = embedHeaders(requestUrl)
            )
            val playlistData = parsePlaylistData(embedResponse.text) ?: return
            val masterUrl = buildMasterUrl(vid, playlistData, isMovie)
            val cookie = collectCookies(apiResponse.headers, embedResponse.headers)
            val masterResult = fetchMaster(isMovie, apiUrl, embedUrl, masterUrl, cookie) ?: return
            val master = masterResult.text

            if (master == null) {
                emitFallback(masterUrl, masterResult.headers, callback)
                return
            }

            emitSubtitles(masterUrl, master, masterResult.headers, subtitleCallback)

            val variants = parseVariants(masterUrl, master)
            val audioTracks = parseAudioTracks(masterUrl, master)
            var emitted = false

            for (variant in variants) {
                val syntheticMaster = buildSyntheticMaster(variant, audioTracks) ?: continue
                callback(
                    newExtractorLink(
                        SOURCE,
                        "VixSrc ${variant.height}p",
                        "$BASE_URL$SYNTHETIC_PATH$vid/${variant.height}p.m3u8",
                        ExtractorLinkType.M3U8
                    ) {
                        quality = variant.height
                        headers = masterResult.headers
                        extractorData = SYNTHETIC_PREFIX + syntheticMaster
                    }
                )
                emitted = true
            }

            if (!emitted) emitFallback(masterUrl, masterResult.headers, callback)
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {

        }
    }
}
