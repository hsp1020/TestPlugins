// 파일명: hsp1020/VixSrcExtractor.kt
package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import java.net.URI

object VixSrcExtractor {
    private const val BASE_URL = "https://vixsrc.to"
    private const val MIN_QUALITY = 720
    private const val MASTER_BLOCK_LEN = 2500
    private const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
    private const val ACCEPT_LANGUAGE = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"

    private val EMBED_REGEX = """(/embed/(\d+)[^"'\s]*)""".toRegex()
    private val PLAYLIST_URL_REGEX = """\burl\s*:\s*["']([^"']*/playlist/[^"']*)["']""".toRegex()
    private val TOKEN_REGEX = """["']?token["']?\s*:\s*["']([^"']+)["']""".toRegex()
    private val EXPIRY_REGEX = """["']?expires["']?\s*:\s*["']?(\d{9,})["']?""".toRegex()
    private val ASN_REGEX = """["']?asn["']?\s*:\s*["']?([^"',}\s]+)["']?""".toRegex()
    private val B_REGEX = """["']?b["']?\s*[:=]\s*["']?([^"'&,}\s]+)["']?""".toRegex()
    private val RESOLUTION_REGEX = """RESOLUTION\s*=\s*\d+\s*x\s*(\d+)""".toRegex(RegexOption.IGNORE_CASE)
    private val RENDITION_REGEX = """(?:[?&])rendition=(\d{3,4})p(?:[&]|$)""".toRegex(RegexOption.IGNORE_CASE)

    private data class MasterFields(
        val url: String?,
        val token: String,
        val expires: String,
        val b: String?,
        val asn: String?
    )

    private data class HlsVariant(val height: Int, val url: String)

    private fun clean(value: String): String = value
        .replace("\\u0026", "&")
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .trim()

    private fun normalizeUrl(rawUrl: String): String {
        val url = clean(rawUrl)
        return when {
            url.startsWith("http://", true) || url.startsWith("https://", true) -> url
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> BASE_URL + url
            else -> "$BASE_URL/${url.trimStart('/')}"
        }
    }

    private fun appendParam(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank() || Regex("(?:[?&])${Regex.escape(key)}=").containsMatchIn(url)) return url
        return "$url${if ('?' in url) '&' else '?'}$key=$value"
    }

    private fun parseMasterFields(html: String): MasterFields? {
        val marker = html.indexOf("window.masterPlaylist").takeIf { it >= 0 }
            ?: html.indexOf("masterPlaylist").takeIf { it >= 0 }
            ?: return null
        val block = clean(html.substring(marker, minOf(html.length, marker + MASTER_BLOCK_LEN)))
        return MasterFields(
            url = PLAYLIST_URL_REGEX.find(block)?.groupValues?.get(1),
            token = TOKEN_REGEX.find(block)?.groupValues?.get(1) ?: return null,
            expires = EXPIRY_REGEX.find(block)?.groupValues?.get(1) ?: return null,
            b = B_REGEX.find(block)?.groupValues?.get(1),
            asn = ASN_REGEX.find(block)?.groupValues?.get(1)
        )
    }

    private fun buildMasterUrl(isMovie: Boolean, actualVid: String, fields: MasterFields): String {
        var url = if (isMovie && !fields.url.isNullOrBlank()) normalizeUrl(fields.url)
        else "$BASE_URL/playlist/$actualVid"
        url = appendParam(url, "b", fields.b)
        url = appendParam(url, "token", fields.token)
        url = appendParam(url, "expires", fields.expires)
        url = appendParam(url, "h", "1")
        url = appendParam(url, "asn", fields.asn)
        return appendParam(url, "lang", "en")
    }

    private fun browserHeaders(
        referer: String,
        accept: String,
        dest: String,
        mode: String,
        site: String,
        origin: Boolean = false,
        cookie: String? = null
    ) = mutableMapOf(
        "Referer" to referer,
        "Accept" to accept,
        "Accept-Language" to ACCEPT_LANGUAGE,
        "User-Agent" to USER_AGENT,
        "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
        "Sec-Ch-Ua-Mobile" to "?0",
        "Sec-Ch-Ua-Platform" to """"Linux"""",
        "Sec-Fetch-Dest" to dest,
        "Sec-Fetch-Mode" to mode,
        "Sec-Fetch-Site" to site
    ).apply {
        if (origin) put("Origin", BASE_URL)
        if (!cookie.isNullOrBlank()) put("Cookie", cookie)
    }

    private fun collectCookies(vararg headerSets: Iterable<Pair<String, String>>): String {
        val cookies = linkedMapOf<String, String>()
        headerSets.forEach { headers ->
            headers.forEach { (name, value) ->
                if (name.equals("Set-Cookie", true)) {
                    val cookie = value.substringBefore(';')
                    val separator = cookie.indexOf('=')
                    if (separator > 0) {
                        cookies[cookie.substring(0, separator).trim()] = cookie.substring(separator + 1).trim()
                    }
                }
            }
        }
        return cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    private fun absoluteUrl(masterUrl: String, rawUrl: String): String = try {
        URI(masterUrl).resolve(clean(rawUrl)).toString()
    } catch (_: Throwable) {
        normalizeUrl(rawUrl)
    }

    private fun parseAllowedVariants(masterUrl: String, masterText: String): List<HlsVariant> {
        val lines = masterText.replace("\r", "").lines().map { it.trim() }
        val variants = mutableListOf<HlsVariant>()

        for (index in lines.indices) {
            val info = lines[index]
            if (!info.startsWith("#EXT-X-STREAM-INF", true)) continue

            val rawUrl = (index + 1 until lines.size).asSequence()
                .map { lines[it] }
                .firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: continue
            val height = RESOLUTION_REGEX.find(info)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: RENDITION_REGEX.find(rawUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
                ?: continue

            if (height >= MIN_QUALITY) variants += HlsVariant(height, absoluteUrl(masterUrl, rawUrl))
        }

        if (variants.isEmpty() && lines.none { it.startsWith("#EXT-X-STREAM-INF", true) }) {
            val height = RENDITION_REGEX.find(masterUrl)?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (height != null && height >= MIN_QUALITY) variants += HlsVariant(height, masterUrl)
        }

        return variants.distinctBy { it.height to it.url }.sortedByDescending { it.height }
    }

    private suspend fun fetchMaster(
        masterUrl: String,
        candidates: List<MutableMap<String, String>>
    ): Pair<String, MutableMap<String, String>>? {
        for (headers in candidates) {
            try {
                val response = StremioC.customSession.get(masterUrl, headers = headers)
                if (response.code in 200..299 && response.text.trimStart().startsWith("#EXTM3U")) {
                    return response.text to headers
                }
            } catch (_: Exception) {
                // 다음 Referer 후보 사용
            }
        }
        return null
    }

    @Suppress("UNUSED_PARAMETER")
    suspend fun invokeVixSrc(
        loadData: StremioC.LoadData,
        targetType: String,
        globalSeason: Int?,
        globalEpisode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val mediaId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$BASE_URL/movie/$mediaId"
            else "$BASE_URL/tv/$mediaId/$globalSeason/$globalEpisode"
            val apiUrl = if (isMovie) "$BASE_URL/api/movie/$mediaId"
            else "$BASE_URL/api/tv/$mediaId/$globalSeason/$globalEpisode"

            val apiHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to ACCEPT_LANGUAGE,
                "User-Agent" to USER_AGENT
            )
            val embedHeaders = browserHeaders(
                requestUrl,
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "iframe", "navigate", "same-origin"
            )

            val apiResponse = StremioC.customSession.get(apiUrl, headers = apiHeaders)
            val embedMatch = EMBED_REGEX.find(apiResponse.text.replace("\\/", "/")) ?: return
            val embedUrl = BASE_URL + embedMatch.groupValues[1]
            val actualVid = embedMatch.groupValues[2]

            val embedResponse = StremioC.customSession.get(embedUrl, headers = embedHeaders)
            val fields = parseMasterFields(embedResponse.text) ?: return
            val masterUrl = buildMasterUrl(isMovie, actualVid, fields)
            val cookie = collectCookies(apiResponse.headers, embedResponse.headers)

            val playerHeaders: (String, Boolean) -> MutableMap<String, String> = { referer, origin ->
                browserHeaders(referer, "*/*", "empty", "cors", "same-origin", origin, cookie)
            }
            val candidates = if (isMovie) listOf(
                playerHeaders("$BASE_URL/", true),
                playerHeaders(apiUrl, true),
                playerHeaders(embedUrl, true)
            ) else listOf(playerHeaders(embedUrl, false))

            val (masterText, selectedHeaders) = fetchMaster(masterUrl, candidates) ?: run {
                println("[STREMIOSUB-VIXSRC] Master playlist unavailable")
                return
            }
            val variants = parseAllowedVariants(masterUrl, masterText)
            if (variants.isEmpty()) {
                println("[STREMIOSUB-VIXSRC] No variant >= ${MIN_QUALITY}p (480p filtered)")
                return
            }

            println("[STREMIOSUB-VIXSRC] Available=${variants.joinToString(",") { "${it.height}p" }}")
            variants.forEach { variant ->
                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc ${variant.height}p", variant.url, ExtractorLinkType.M3U8) {
                        this.quality = variant.height
                        this.headers = selectedHeaders
                    }
                )
            }
        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] ${e.message}")
            e.printStackTrace()
        }
    }
}
