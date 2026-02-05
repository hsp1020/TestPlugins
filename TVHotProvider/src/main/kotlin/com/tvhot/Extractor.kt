package com.tvhot

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper

class BunnyPoorCdn : ExtractorApi() {
    override val name = "BunnyPoorCdn"
    override val mainUrl = "https://player.bunny-frame.online"
    override val requiresReferer = true

    private val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to USER_AGENT,
        "Accept" to "*/*",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Origin" to "https://player.bunny-frame.online",
        "Sec-Fetch-Dest" to "empty",
        "Sec-Fetch-Mode" to "cors",
        "Sec-Fetch-Site" to "cross-site",
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        extract(url, referer, subtitleCallback, callback)
    }

    suspend fun extract(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        thumbnailHint: String? = null, // 👈 썸네일 힌트(도메인/경로 추정용)
    ): Boolean {
        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer

        return try {
            val response = app.get(cleanUrl, headers = headers)
            val text = response.text
            val finalUrl = response.url

            val pathRegex = Regex("""/v/[ef]/[a-zA-Z0-9]+""")
            val pathMatch = pathRegex.find(text) ?: pathRegex.find(cleanUrl)

            // 👇 썸네일 힌트에서도 path를 보조로 탐색
            val thumbPathMatch = if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            val finalPathMatch = pathMatch ?: thumbPathMatch

            if (finalPathMatch == null) return false
            val path = finalPathMatch.value

            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)

            val domain = when {
                domainMatch != null -> domainMatch.groupValues[1]

                finalUrl.contains(path) -> {
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }

                // 👇 썸네일 힌트에서 도메인 추정 시도
                thumbnailHint != null && thumbnailHint.contains(path) ->
                    (try {
                        val uri = java.net.URI(thumbnailHint)
                        if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                    } catch (e: Exception) {
                        null
                    }) ?: run {
                        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                        "https://every${serverNum}.poorcdn.com"
                    }

                else -> {
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    "https://every${serverNum}.poorcdn.com"
                }
            }

            val cleanPath = path.replace("//v/", "/v/")
            val tokenUrl = "$domain$cleanPath/c.html"
            val directM3u8 = "$domain$cleanPath/index.m3u8"

            val tokenHeaders = browserHeaders.toMutableMap().apply {
                put("Referer", cleanUrl)
            }

            try {
                val tokenRes = app.get(tokenUrl, headers = tokenHeaders)
                val tokenText = tokenRes.text

                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    .findAll(tokenText)
                    .forEach {
                        cookieMap[it.groupValues[1]] = it.groupValues[2]
                    }

                val realM3u8Match =
                    Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                        ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)

                var realM3u8 = realM3u8Match?.groupValues?.get(1) ?: directM3u8

                if (!realM3u8.startsWith("http")) {
                    realM3u8 = "$domain$cleanPath/$realM3u8"
                        .replace("$cleanPath/$cleanPath", cleanPath)
                }

                loadM3u8(realM3u8, cleanUrl, tokenHeaders, cookieMap, callback)
                true
            } catch (e: Exception) {
                // 토큰 실패 시 direct index.m3u8 fallback
                loadM3u8(directM3u8, cleanUrl, tokenHeaders, emptyMap(), callback)
                true
            }
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadM3u8(
        url: String,
        referer: String,
        baseHeaders: Map<String, String>,
        cookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = baseHeaders.toMutableMap()
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }

        M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        ).forEach(callback)
    }
}
