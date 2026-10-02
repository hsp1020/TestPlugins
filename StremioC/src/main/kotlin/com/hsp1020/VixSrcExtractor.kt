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

    // window.masterPlaylist 객체를 먼저 좁혀서 token / expires / url을 추출한다.
    private val MASTER_PLAYLIST_REGEX = """(?s)window\.masterPlaylist\s*=\s*(\{.*?\})""".toRegex()

    private val PLAYLIST_URL_REGEX = """["']?url["']?\s*[:=]\s*["']([^"']+)["']""".toRegex()
    private val TOKEN_REGEX_1 = """["']?token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex()
    private val EXPIRY_REGEX_1 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()
    private val TOKEN_REGEX_2 = """["']?token["']?\s*[:=]\s*["']([a-fA-F0-9]{32})["']""".toRegex()
    private val EXPIRY_REGEX_2 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d{10,})["']?""".toRegex()
    private val B_REGEX = """["']?b["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()

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
        bVal: String?
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

        // 기존 코드의 lang=en은 서버가 명시하지 않은 경우에만 유지한다.
        url = appendQueryParamIfMissing(url, "lang", "en")
        return url
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
            var parsedPlaylistUrl: String? = null

            println("[STREMIOSUB-VIXSRC] ⚙️ Step 3: Parsing Tokens from HTML...")
            val masterPlaylistMatch = MASTER_PLAYLIST_REGEX.find(rawHtml)?.groupValues?.get(1)

            if (masterPlaylistMatch != null) {
                println("[STREMIOSUB-VIXSRC] ⚙️ Found window.masterPlaylist match successfully!")
                val cleanMatch = masterPlaylistMatch
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                token = TOKEN_REGEX_1.find(cleanMatch)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_1.find(cleanMatch)?.groupValues?.get(1)
                bVal = B_REGEX.find(cleanMatch)?.groupValues?.get(1)
                parsedPlaylistUrl = PLAYLIST_URL_REGEX.find(cleanMatch)?.groupValues?.get(1)
            } else {
                println("[STREMIOSUB-VIXSRC] ⚠️ window.masterPlaylist NOT found. Falling back to direct HTML search.")
                val cleanHtml = rawHtml
                    .replace("\\u0026", "&")
                    .replace("\\/", "/")
                    .replace("&amp;", "&")

                token = TOKEN_REGEX_2.find(cleanHtml)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_2.find(cleanHtml)?.groupValues?.get(1)
                bVal = B_REGEX.find(cleanHtml)?.groupValues?.get(1)
                parsedPlaylistUrl = PLAYLIST_URL_REGEX.find(cleanHtml)?.groupValues?.get(1)
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
                    bVal = bVal
                )

                if (parsedPlaylistUrl.isNullOrBlank()) {
                    println("[STREMIOSUB-VIXSRC] ⚠️ Movie masterPlaylist.url missing; using legacy /playlist/$actualVid fallback.")
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

            // 중요:
            // - TV: 기존 정상 동작을 보존하기 위해 Referer=embedUrl 그대로 유지.
            // - Movie: 현재 VixSrc의 master playlist 요청 방식에 맞춰 Referer=apiUrl + Origin을 사용.
            val playerHeaders = if (isMovie) {
                mapOf(
                    "Referer" to apiUrl,
                    "Origin" to baseUrl,
                    "Accept" to "*/*",
                    "Accept-Language" to acceptLanguage,
                    "User-Agent" to userAgent,
                    "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Linux"""",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ).toMutableMap()
            } else {
                mapOf(
                    "Referer" to embedUrl,
                    "Accept" to "*/*",
                    "Accept-Language" to acceptLanguage,
                    "User-Agent" to userAgent,
                    "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                    "Sec-Ch-Ua-Mobile" to "?0",
                    "Sec-Ch-Ua-Platform" to """"Linux"""",
                    "Sec-Fetch-Dest" to "empty",
                    "Sec-Fetch-Mode" to "cors",
                    "Sec-Fetch-Site" to "same-origin"
                ).toMutableMap()
            }.apply {
                if (cookieHeaderValue.isNotEmpty()) put("Cookie", cookieHeaderValue)
            }

            println(
                "[STREMIOSUB-VIXSRC] 🧭 Player request mode=${if (isMovie) "MOVIE(api referer)" else "TV(legacy embed referer)"}"
            )

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
