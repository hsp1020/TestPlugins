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
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=start ok=true url=${trunc(url)} referer=${trunc(referer)} thumbnailHint=${trunc(thumbnailHint)}")

        val cleanUrl = url.replace(Regex("[\\r\\n\\s]"), "").trim()
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=clean_url ok=${cleanUrl.isNotBlank()} cleanUrl=${trunc(cleanUrl)}")

        val headers = browserHeaders.toMutableMap()
        if (referer != null) headers["Referer"] = referer
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=headers_ready ok=true hasReferer=${referer != null}")

        return try {
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=fetch_page_begin ok=true GET=${trunc(cleanUrl)}")
            val response = app.get(cleanUrl, headers = headers)
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=fetch_page_ok ok=true finalUrl=${trunc(response.url)}")

            val text = response.text
            val finalUrl = response.url
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=page_text_ok ok=${text.isNotBlank()} textLen=${text.length}")

            val pathRegex = Regex("""/v/[a-z]/[a-zA-Z0-9]+""")

            val pathMatchFromText = pathRegex.find(text)
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=path_match_text ok=${pathMatchFromText != null} match=${trunc(pathMatchFromText?.value)}")

            val pathMatchFromUrl = pathRegex.find(cleanUrl)
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=path_match_url ok=${pathMatchFromUrl != null} match=${trunc(pathMatchFromUrl?.value)}")

            val pathMatch = pathMatchFromText ?: pathMatchFromUrl

            val thumbPathMatch = if (thumbnailHint != null) pathRegex.find(thumbnailHint) else null
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=path_match_thumb ok=${thumbPathMatch != null} match=${trunc(thumbPathMatch?.value)}")

            val finalPathMatch = pathMatch ?: thumbPathMatch
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=path_final_selected ok=${finalPathMatch != null} path=${trunc(finalPathMatch?.value)}")

            if (finalPathMatch == null) {
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=fail_no_path ok=false msg=No /v/x/<id> path found")
                return false
            }
            val path = finalPathMatch.value

            val domainRegex = Regex("""(https?://[^"' \t\n]+)$path""")
            val domainMatch = domainRegex.find(text)
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_match_text ok=${domainMatch != null} domain=${trunc(domainMatch?.groupValues?.getOrNull(1))}")

            val domain = when {
                domainMatch != null -> {
                    println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_source ok=true src=text_match")
                    domainMatch.groupValues[1]
                }

                finalUrl.contains(path) -> {
                    println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_source ok=true src=finalUrl_contains_path finalUrl=${trunc(finalUrl)}")
                    val uri = java.net.URI(finalUrl)
                    "${uri.scheme}://${uri.host}"
                }

                thumbnailHint != null && thumbnailHint.contains(path) -> {
                    println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_source ok=true src=thumbnailHint thumbnailHint=${trunc(thumbnailHint)}")
                    (try {
                        val uri = java.net.URI(thumbnailHint)
                        if (uri.scheme != null && uri.host != null) "${uri.scheme}://${uri.host}" else null
                    } catch (e: Exception) {
                        println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_from_thumb_exception ok=false e=${trunc(e.message)}")
                        null
                    }) ?: run {
                        val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                        val fallback = "https://every${serverNum}.poorcdn.com"
                        println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_fallback ok=true serverNum=$serverNum domain=$fallback")
                        fallback
                    }
                }

                else -> {
                    val serverNum = Regex("""[?&]s=(\d+)""").find(cleanUrl)?.groupValues?.get(1) ?: "9"
                    val fallback = "https://every${serverNum}.poorcdn.com"
                    println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_fallback ok=true serverNum=$serverNum domain=$fallback")
                    fallback
                }
            }

            println("DEBUG_EXTRACTOR name=$name req=$reqId step=domain_final ok=${domain.isNotBlank()} domain=${trunc(domain)} path=${trunc(path)}")

            val cleanPath = path.replace(Regex("//v/"), "/v/")
            val tokenUrl = "$domain$cleanPath/c.html"
            val directM3u8 = "$domain$cleanPath/index.m3u8"
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=urls_built ok=true tokenUrl=${trunc(tokenUrl)} directM3u8=${trunc(directM3u8)}")

            val tokenHeaders = browserHeaders.toMutableMap().apply { put("Referer", cleanUrl) }
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_headers_ready ok=true Referer=${trunc(cleanUrl)}")

            try {
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_request_begin ok=true GET=${trunc(tokenUrl)}")
                val tokenRes = app.get(tokenUrl, headers = tokenHeaders)
                val tokenText = tokenRes.text

                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_request_ok ok=true tokenFinalUrl=${trunc(tokenRes.url)}")
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_text_len ok=true tokenTextLen=${tokenText.length}")
                // 핵심: 16바이트 내용이 뭔지 바로 보자 (너무 길면 앞부분만)
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_text_head ok=true head=${trunc(head(tokenText, 300), 340)}")
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_text_flags ok=true hasM3u8=${tokenText.contains(\".m3u8\")} hasLocation=${tokenText.contains(\"location\")} hasCookieJs=${tokenText.contains(\"document.cookie\")}")

                val cookieMap = mutableMapOf<String, String>()
                cookieMap.putAll(tokenRes.cookies)
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_cookies_initial ok=true cookieCount=${cookieMap.size}")

                Regex("""document\.cookie\s*=\s*["']([^=]+)=([^; "']+)""")
                    .findAll(tokenText)
                    .forEach {
                        cookieMap[it.groupValues[1]] = it.groupValues[2]
                    }
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_cookies_total ok=true cookieCount=${cookieMap.size}")

                val realM3u8Match =
                    Regex("""["']([^"']+\.m3u8[^"']*)["']""").find(tokenText)
                        ?: Regex("""location\.href\s*=\s*["']([^"']+)["']""").find(tokenText)

                val rawM3u8 = realM3u8Match?.groupValues?.getOrNull(1)
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=real_m3u8_match ok=${rawM3u8 != null} raw=${trunc(rawM3u8)}")

                var realM3u8 = rawM3u8 ?: directM3u8
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=real_m3u8_selected ok=true realM3u8=${trunc(realM3u8)} fallback=${rawM3u8 == null}")

                if (!realM3u8.startsWith("http")) {
                    val before = realM3u8
                    realM3u8 = "$domain$cleanPath/$realM3u8"
                        .replace("$cleanPath/$cleanPath", cleanPath)
                    println("DEBUG_EXTRACTOR name=$name req=$reqId step=real_m3u8_make_abs ok=true before=${trunc(before)} after=${trunc(realM3u8)}")
                }

                println("DEBUG_EXTRACTOR name=$name req=$reqId step=load_m3u8_begin ok=true url=${trunc(realM3u8)} cookieCount=${cookieMap.size}")
                loadM3u8(realM3u8, cleanUrl, tokenHeaders, cookieMap, callback, reqId)
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=load_m3u8_done ok=true via=token_flow")
                true
            } catch (e: Exception) {
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=token_flow_exception ok=false e=${trunc(e.message)}")
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=load_m3u8_begin ok=true url=${trunc(directM3u8)} cookieCount=0 via=direct_fallback")
                loadM3u8(directM3u8, cleanUrl, tokenHeaders, emptyMap(), callback, reqId)
                println("DEBUG_EXTRACTOR name=$name req=$reqId step=load_m3u8_done ok=true via=direct_fallback")
                true
            }
        } catch (e: Exception) {
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=extract_exception ok=false e=${trunc(e.message)}")
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
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_headers_ready ok=true cookieCount=${cookies.size}")

        // 핵심: 실제로 index.m3u8에서 뭐가 내려오는지 먼저 덤프
        try {
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_prefetch_begin ok=true url=${trunc(url)}")
            val pre = app.get(url, headers = headers)
            val body = pre.text
            val firstLine = body.lineSequence().firstOrNull() ?: ""
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_prefetch_ok ok=true finalUrl=${trunc(pre.url)} len=${body.length}")
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_prefetch_flags ok=true hasEXTM3U=${body.contains(\"#EXTM3U\")} firstLine=${trunc(firstLine, 260)}")
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_prefetch_head ok=true head=${trunc(head(body, 300), 340)}")
        } catch (e: Exception) {
            println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_prefetch_exception ok=false e=${trunc(e.message)}")
        }

        println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_generate_begin ok=true url=${trunc(url)} referer=${trunc(referer)}")
        val links = M3u8Helper.generateM3u8(
            name,
            url,
            referer,
            headers = headers
        )
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_generate_ok ok=true linkCount=${links.size}")

        links.forEach(callback)
        println("DEBUG_EXTRACTOR name=$name req=$reqId step=m3u8_callback_done ok=true emitted=${links.size}")
    }
}
