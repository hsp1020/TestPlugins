// 파일명: hsp1020/VixSrcExtractor.kt
package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

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

    private data class MasterPlaylistData(
        val url: String?,
        val token: String,
        val expires: String,
        val b: String?,
        val asn: String?
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

    private fun appendQueryParamIfMissing(url: String, key: String, value: String?): String {
        if (value.isNullOrBlank()) return url
        if (Regex("(?:[?&])${Regex.escape(key)}=").containsMatchIn(url)) return url
        return url + if (url.contains('?')) "&$key=$value" else "?$key=$value"
    }

    /**
     * masterPlaylist 내부에는 params { ... } 같은 중첩 객체가 있어 단순 {.*?} 정규식으로는
     * 실제 url까지 안정적으로 잡을 수 없다. marker 이후의 작은 블록에서 필드를 개별 추출한다.
     */
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

            // API/embed 응답의 세션 쿠키를 플레이리스트 요청에도 전달한다.
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

            val finalHeaders = if (isMovie) {
                // 영화는 VixSrc 측 Referer 검증 변화에 대응해 실제 M3U8을 반환하는 조합을 사용한다.
                val candidates = listOf(
                    playerHeaders("$BASE_URL/", includeOrigin = true),
                    playerHeaders(apiUrl, includeOrigin = true),
                    playerHeaders(embedUrl, includeOrigin = true)
                )

                var verified: MutableMap<String, String>? = null
                for (headers in candidates) {
                    val valid = try {
                        StremioC.customSession
                            .get(masterPlaylistUrl, headers = headers)
                            .text
                            .trimStart()
                            .startsWith("#EXTM3U")
                    } catch (_: Exception) {
                        false
                    }
                    if (valid) {
                        verified = headers
                        break
                    }
                }

                verified ?: run {
                    println("[STREMIOSUB-VIXSRC] Movie playlist verification failed")
                    return
                }
            } else {
                // TV 경로는 기존 정상 동작을 그대로 유지한다.
                playerHeaders(embedUrl, includeOrigin = false)
            }

            callback(
                newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                    quality = Qualities.P1080.value
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
