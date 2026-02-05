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

    private fun trunc(s: String?, max: Int = 240): String {
        if (s == null) return "null"
        val clean = s.replace("\n", "\\n").replace("\r", "\\r")
        return if (clean.length <= max) clean else clean.substring(0, max) + "...(truncated)"
    }

    private fun head(s: String?, max: Int = 300): String {
        if (s == null) return "null"
        val clean = s.replace("\r", "")
        return if (clean.length <= max) clean else clean.substring(0, max) + "...(truncated)"
    }

    /** println은 무조건 문자열 1개만 찍도록 강제 */
    private fun pl(
        reqId: String,
        step: String,
        ok: Boolean,
        msg: String = "",
        extra: Map<String, Any?> = emptyMap(),
    ) {
        val extraStr = if (extra.isEmpty()) "" else {
            " " + extra.entries.joinToString(" ") { (k, v) -> "$k=${trunc(v?.toString(), 300)}" }
        }
        val msgStr = if (msg.isBlank()) "" else " msg=${trunc(msg, 400)}"
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=$step ok=$ok$msgStr$extraStr")
    }

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
        thumbnailHint: String? = null,
    ): Boolean {
        val reqId = System.currentTimeMillis().toString()
        pl(reqId, "start", ok = true, extra = mapOf(
            "url" to url,
            "referer" to referer,
            "thumbnailHint" to thumbnailHint
        ))

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        pl(reqId, "clean_url", ok = cleanUrl.isNotBlank(), extra = mapOf("cleanUrl" to cleanUrl))

        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer
        pl(reqId, "headers_ready", ok = true, extra = mapOf("hasReferer" to (referer != null)))

        return try {
            pl(reqId, "fetch_page_begin", ok = true, extra = mapOf("GET" to cleanUrl))
            val response = app.get(cleanUrl, headers = headers)
            pl(reqId, "fetch_page_ok", ok = true, extra = mapOf("finalUrl" to response.url))

            val text = response.text
            val finalUrl = response.url
            pl(reqId, "page_text_ok", ok = text.isNotBlank(), extra = mapOf("textLen" to text.length))

            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")

            val pathMatchFromText = pathRegex.find(text)
            pl(reqId, "path_match_text", ok = (pathMatchFromText != null), extra = mapOf("match" to pathMatchFromText?.value))

            val pathMatchFromUrl = pathRegex.find(cleanUrl)
            pl(reqId, "path_match_url", ok = (pathMatchFromUrl != null), extra = mapOf("match" to pathMatchFromUrl?.value))

            val pathMatch = pathMatchFromText ?: pathMatchFromUrl

            val thumbPathMatch = if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            pl(reqId, "path_match_thumb", ok = (thumbPathMatch != null), extra = mapOf("match" to thumbPathMatch?.value))

            val finalPathMatch = pathMatch ?: thumbPathMatch
            pl(reqId, "path_final_selected", ok = (finalPathMatch != null), extra = mapOf("path" to finalPathMatch?.value))

            if (finalPathMatch == null) {
                pl(reqId, "fail_no_path", ok = false, msg = "No /v/x/<id> path found")
                return false
            }
            val path = finalPathMatch.value

            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)
            pl(reqId, "domain_match_text", ok = (domainMatch != null), extra = mapOf("domain" to domainMatch?.groupValues?.getOrNull(1)))

            val domain = when {
                domainMatch != null -> domainMatch.groupValues[1]

                finalUrl.contains(path) -> {
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }

                thumbnailHint != null && thumbnailHint.contains(path) -> {
                    (try {
                        val uri = java.net.URI(thumbnailHint)
                        if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                    } catch (e: Exception) {
                        pl(reqId, "domain_from_thumb_exception", ok = false, msg = e.message ?: "null")
                        null
                    }) ?: run {
                        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                        "https://every${serverNum}.poorcdn.com"
                    }
                }

                else -> {
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    "https://every${serverNum}.poorcdn.com"
                }
            }

            pl(reqId, "domain_final", ok = domain.isNotBlank(), extra = mapOf("domain" to domain, "path" to path))

            val cleanPath = path.replace(Regex("//v/"), "/v/")
            val tokenUrl = "$domain$cleanPath/c.html"
            val directM3u8 = "$domain$cleanPath/index.m3u8"
            pl(reqId, "urls_built", ok = true, extra = mapOf("tokenUrl" to tokenUrl, "directM3u8" to directM3u8))

            val tokenHeaders = browserHeaders.toMutableMap().apply { put("Referer", cleanUrl) }
            pl(reqId, "token_headers_ready", ok = true, extra = mapOf("Referer" to cleanUrl))

            try {
                pl(reqId, "token_request_begin", ok = true, extra = mapOf("GET" to tokenUrl))
                val tokenRes = app.get(tokenUrl, headers = tokenHeaders)
                val tokenText = tokenRes.text

                pl(reqId, "token_request_ok", ok = true, extra = mapOf("tokenFinalUrl" to tokenRes.url))
                pl(reqId, "token_text_len", ok = true, extra = mapOf("tokenTextLen" to tokenText.length))
                pl(reqId, "token_text_head", ok = true, extra = mapOf("head" to head(tokenText, 300)))

                val hasM3u8 = tokenText.contains(".m3u8")
                val hasLocation = tokenText.contains("location")
                val hasCookieJs = tokenText.contains("document.cookie")
                pl(reqId, "token_text_flags", ok = true, extra = mapOf(
                    "hasM3u8" to hasM3u8,
                    "hasLocation" to hasLocation,
                    "hasCookieJs" to hasCookieJs
                ))

                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                pl(reqId, "token_cookies_initial", ok = true, extra = mapOf("cookieCount" to cookieMap.size))

                Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    .findAll(tokenText)
                    .forEach { m -> cookieMap[m.groupValues[1]] = m.groupValues[2] }
                pl(reqId, "token_cookies_total", ok = true, extra = mapOf("cookieCount" to cookieMap.size))

                val realM3u8Match =
                    Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                        ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)

                val rawM3u8 = realM3u8Match?.groupValues?.getOrNull(1)
                pl(reqId, "real_m3u8_match", ok = (rawM3u8 != null), extra = mapOf("raw" to rawM3u8))

                var realM3u8 = rawM3u8 ?: directM3u8
                pl(reqId, "real_m3u8_selected", ok = true, extra = mapOf("realM3u8" to realM3u8, "fallback" to (rawM3u8 == null)))

                if (!realM3u8.startsWith("http")) {
                    val before = realM3u8
                    realM3u8 = "$domain$cleanPath/$realM3u8".replace("$cleanPath/$cleanPath", cleanPath)
                    pl(reqId, "real_m3u8_make_abs", ok = true, extra = mapOf("before" to before, "after" to realM3u8))
                }

                pl(reqId, "load_m3u8_begin", ok = true, extra = mapOf("url" to realM3u8, "cookieCount" to cookieMap.size))
                loadM3u8(realM3u8, cleanUrl, tokenHeaders, cookieMap, callback, reqId)
                pl(reqId, "load_m3u8_done", ok = true, extra = mapOf("via" to "token_flow"))
                true
            } catch (e: Exception) {
                pl(reqId, "token_flow_exception", ok = false, msg = e.message ?: "null", extra = mapOf("fallback" to "directM3u8"))
                loadM3u8(directM3u8, cleanUrl, tokenHeaders, emptyMap(), callback, reqId)
                pl(reqId, "load_m3u8_done", ok = true, extra = mapOf("via" to "direct_fallback"))
                true
            }
        } catch (e: Exception) {
            pl(reqId, "extract_exception", ok = false, msg = e.message ?: "null")
            e.printStackTrace()
            false
        }
    }

    private suspend fun loadM3u8(
        url: String,
        referer: String,
        baseHeaders: Map<String, String>,
        cookies: Map<String, String>,
        callback: (ExtractorLink) -> Unit,
        reqId: String,
    ) {
        val headers = baseHeaders.toMutableMap()
        if (cookies.isNotEmpty()) {
            headers["Cookie"] = cookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
        }
        pl(reqId, "m3u8_headers_ready", ok = true, extra = mapOf("cookieCount" to cookies.size))

        // m3u8 본문 덤프(원인 확정용)
        try {
            pl(reqId, "m3u8_prefetch_begin", ok = true, extra = mapOf("url" to url))
            val pre = app.get(url, headers = headers)
            val body = pre.text
            val firstLine = body.lineSequence().firstOrNull() ?: ""
            pl(reqId, "m3u8_prefetch_ok", ok = true, extra = mapOf("finalUrl" to pre.url, "len" to body.length))
            pl(reqId, "m3u8_prefetch_flags", ok = true, extra = mapOf(
                "hasEXTM3U" to body.contains("#EXTM3U"),
                "firstLine" to firstLine
            ))
            pl(reqId, "m3u8_prefetch_head", ok = true, extra = mapOf("head" to head(body, 300)))
        } catch (e: Exception) {
            pl(reqId, "m3u8_prefetch_exception", ok = false, msg = e.message ?: "null")
        }

        pl(reqId, "m3u8_generate_begin", ok = true, extra = mapOf("url" to url, "referer" to referer))
        val links = M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        )
        pl(reqId, "m3u8_generate_ok", ok = true, extra = mapOf("linkCount" to links.size))

        links.forEach(callback)
        pl(reqId, "m3u8_callback_done", ok = true, extra = mapOf("emitted" to links.size))
    }
}
