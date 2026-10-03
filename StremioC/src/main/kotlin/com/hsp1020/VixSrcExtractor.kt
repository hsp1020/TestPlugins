// 파일명: hsp1020/VixSrcExtractor.kt
package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
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
        val height: Int
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

            val childUrl = resolveHlsUrl(masterUrl, lines[uriIndex])
            val resolutionHeight = RESOLUTION_REGEX.find(line)
                ?.groupValues
                ?.getOrNull(2)
                ?.toIntOrNull()
            val renditionHeight = RENDITION_HEIGHT_REGEX.find(childUrl)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()

            variants += HlsVariant(
                url = childUrl,
                height = resolutionHeight ?: renditionHeight ?: 0
            )
            index = uriIndex + 1
        }

        return variants.distinctBy { it.url }
    }

    private fun emitMasterSubtitles(
        masterUrl: String,
        masterText: String,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val emittedUrls = mutableSetOf<String>()

        masterText.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#EXT-X-MEDIA:", ignoreCase = true) }
            .forEach { line ->
                val attributes = parseAttributes(line.substringAfter(':'))
                if (!attributes["TYPE"].equals("SUBTITLES", ignoreCase = true)) return@forEach

                val rawUri = attributes["URI"]?.takeIf { it.isNotBlank() } ?: return@forEach
                val subtitleUrl = resolveHlsUrl(masterUrl, rawUri)
                if (!emittedUrls.add(subtitleUrl)) return@forEach

                val label = attributes["NAME"]
                    ?.takeIf { it.isNotBlank() }
                    ?: attributes["LANGUAGE"]?.takeIf { it.isNotBlank() }
                    ?: "VixSrc"

                subtitleCallback(SubtitleFile(label, subtitleUrl))
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
            if (master != null) {
                emitMasterSubtitles(masterPlaylistUrl, master, subtitleCallback)
            }

            val selectedVariant = master
                ?.let { parseVariants(masterPlaylistUrl, it) }
                ?.maxByOrNull { it.height }

            val playbackUrl = selectedVariant?.url ?: masterPlaylistUrl
            val playbackQuality = selectedVariant
                ?.height
                ?.takeIf { it > 0 }
                ?: Qualities.P1080.value

            callback(
                newExtractorLink("VixSrc", "VixSrc", playbackUrl, ExtractorLinkType.M3U8) {
                    quality = playbackQuality
                    headers = finalHeaders
                }
            )
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] ${e.javaClass.simpleName}: ${e.message}")
        }
    }
}
