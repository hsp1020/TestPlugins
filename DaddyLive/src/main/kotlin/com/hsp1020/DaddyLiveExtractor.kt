package com.hsp1020

import com.fasterxml.jackson.core.type.TypeReference
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

class DaddyLiveExtractor : ExtractorApi() {
    override var mainUrl = "https://dlhd.st" // Provider와 통일
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    // 글로벌 통일된 Chrome 브라우저 User-Agent 정의
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    private fun debugLog(message: String) {
        Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
        println("[DDL-DEBUG] $message")
    }

    private fun printLargeLog(tag: String, message: String) {
        if (message.length > 30000) {
            Log.d(tag, "[SKIP] 이 파일은 너무 거대한 자바스크립트 파일(크기: ${message.length})이므로 버퍼 오염 방지를 위해 생략합니다.")
            return
        }
        var i = 0
        while (i < message.length) {
            val end = (i + 3500).coerceAtMost(message.length)
            Log.d(tag, "[BODY-CHUNK] ${message.substring(i, end)}")
            i += 3500
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val links = try {
            mapper.readValue(url, object : TypeReference<List<Pair<String, String>>>() {})
        } catch (e: Exception) {
            null
        }
        
        if (links == null) {
            val link = extractVideo(url, this.name)
            if (link != null) callback(link)
            return
        }

        val groupedLinks = links.groupBy { it.first.substringBefore(" - ") }

        groupedLinks.forEach { (channelName, playerPairs) ->
            debugLog("[$channelName] 채널의 스트림 추출 시작 (총 ${playerPairs.size}개 후보)")
            
            for (playerPair in playerPairs) {
                val sourceName = playerPair.first 
                val streamUrl = playerPair.second

                val link = extractVideo(streamUrl, sourceName)
                if (link != null) {
                    callback(link)
                    debugLog("[$channelName] 성공적으로 추출 완료. 해당 채널의 나머지 백업 플레이어 탐색 중지.")
                    break 
                }
            }
        }
    }

    private suspend fun extractVideo(url: String, sourceName: String): ExtractorLink? {
        try {
            debugLog("================= [DDL EXTRACT START] =================")
            debugLog("1. 소스 정보 -> Name: $sourceName, Target URL: $url")
            
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            mainUrl = entryOrigin
            
            val requestHeaders = mapOf(
                "user-agent" to userAgent,
                "referer" to url,
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            val resp = withContext(Dispatchers.IO) {
                app.get(url, headers = requestHeaders, timeout = 8)
            }
            debugLog("2. 플레이어 페이지 응답 코드: ${resp.code}")
            if (!resp.isSuccessful) return null
            val html = resp.text
            
            val iframeMatches = Regex("""<iframe\s+[^>]*src=(?:["']([^"']+)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .distinct()
                .filter { src ->
                    val isStaticAsset = src.endsWith(".js") || src.endsWith(".css") || src.contains("tag.min")
                    val isPlayerPath = src.contains("/court/") || src.contains("/player/") || src.contains("/stream/") || src.contains("/embed/") || src.contains("/wimbledon/") || src.contains("live=") || src.contains("id=")
                    val isPopcdn = src.contains("freeshot") || src.contains("popcdn")
                    !isStaticAsset && (isPlayerPath || isPopcdn)
                }
                .toList()
            
            val iframeHeaders = requestHeaders.toMutableMap()
            iframeHeaders["referer"] = url
            iframeHeaders["origin"] = entryOrigin

            val loadedIframeBodies = coroutineScope {
                iframeMatches.map { rawSrc ->
                    async(Dispatchers.IO) {
                        var iframeUrl = rawSrc
                        if (rawSrc.startsWith("//")) {
                            iframeUrl = "https:$rawSrc"
                        } else if (rawSrc.startsWith("/")) {
                            iframeUrl = "$entryOrigin$rawSrc"
                        } else if (!rawSrc.startsWith("http")) {
                            iframeUrl = "$entryOrigin/${rawSrc.trimStart('/')}"
                        }
                        
                        try {
                            val iframeResp = app.get(iframeUrl, headers = iframeHeaders, timeout = 8)
                            if (iframeResp.isSuccessful) {
                                // [보완] 리다이렉트를 추적하기 위해 원본 요청 주소가 아닌 최종 도착 url 반환
                                iframeResp.url to iframeResp.text 
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }

            for ((iframeUrl, iframeHtml) in loadedIframeBodies) {
                val link = parseAndExtract(iframeUrl, iframeHtml, iframeHeaders, entryOrigin, sourceName)
                if (link != null) return link
            }

            debugLog("알림: 모든 후보 iframe 내부에서 유효한 진짜 스트림 주소를 획득하지 못했습니다.")
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        } catch (e: SocketTimeoutException) {
            debugLog("extractVideo 소켓 연결 타임아웃 예외 발생: ${e.message}")
            return null
        } catch (e: IOException) {
            debugLog("extractVideo 네트워크 입출력 에러 발생: ${e.message}")
            return null
        } catch (e: Exception) {
            debugLog("extractVideo 일반 예외 발생: ${e.message}")
            return null
        }
    }

    private suspend fun parseAndExtract(
        iframeUrl: String, 
        iframeHtml: String, 
        iframeHeaders: Map<String, String>, 
        entryOrigin: String, 
        sourceName: String,
        depth: Int = 1
    ): ExtractorLink? {
        if (depth > 3) return null
        
        try {
            // =======================================================
            // [대박 보완 핵심 - 다이렉트 M3U8 감지기]
            // 서버가 리다이렉트 등으로 HTML 페이지가 아닌 실제 M3U8 비디오 파일을 곧바로 던져준 경우,
            // HTML 파싱을 생략하고 도착한 최종 주소를 즉시 스트림 링크로 승인하여 재생합니다.
            // =======================================================
            if (iframeHtml.trim().startsWith("#EXTM3U") || iframeHtml.contains("#EXTM3U")) {
                debugLog("[$depth 단계] M3U8 플레이리스트 다이렉트 응답 확인! 진짜 스트림 획득 -> $iframeUrl")
                return ExtractorLink(
                    source = sourceName, name = sourceName, url = iframeUrl,
                    referer = iframeHeaders["referer"] ?: "", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "referer" to (iframeHeaders["referer"] ?: ""),
                        "origin" to (iframeHeaders["origin"] ?: ""),
                        "user-agent" to userAgent,
                        "accept" to "*/*"
                    )
                )
            }

            val parsedIframe = URL(iframeUrl)
            val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"

            val scriptBlocks = Regex("""<script[^>]*>([\s\S]*?)</script>""").findAll(iframeHtml).map { it.groupValues[1] }.toList()
            for ((index, script) in scriptBlocks.withIndex()) {
                val hasConfigVars = script.contains("zmid") || script.contains("edm") || script.contains("csrf") || script.contains("v") || script.contains("gatxt")
                if (hasConfigVars && script.length < 30000) {
                    debugLog("[WIMBLEDON-SCRIPT-$index] 감지됨! 크기: ${script.length}")
                    printLargeLog("WIMBLEDON-SCRIPT-$index", script)
                }
            }
            
            val metaCsrf = Regex("""<meta[^>]+(?:csrf|token|auth)[^>]+content=["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)

            // 이스케이프 정화 및 최우선 m3u8 획득 모듈
            val streamUrlMatch = Regex("""["'](https?:[\\/]+[^"']+\.m3u8[^"']*)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)
            if (streamUrlMatch != null) {
                val rawUrl = streamUrlMatch.groupValues[1]
                val cleanStreamUrl = rawUrl.replace("\\", "").trim()
                debugLog("[최종-우회] 이스케이프 정화 및 m3u8 최종 주소 복원 성공 -> $cleanStreamUrl")
                
                return ExtractorLink(
                    source = sourceName, name = sourceName, url = cleanStreamUrl,
                    referer = "$iframeOrigin/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                    headers = mapOf(
                        "referer" to "$iframeOrigin/",
                        "origin" to iframeOrigin,
                        "user-agent" to userAgent,
                        "accept" to "*/*",
                        "x-direct-connection" to "1",
                        "sec-fetch-dest" to "empty",
                        "sec-fetch-mode" to "cors",
                        "sec-fetch-site" to "cross-site"
                    )
                )
            }

            // 프리미엄 채널용 정석 atob 복구 (가짜 허니팟은 필터링)
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val decodedUrl = base64Decode(b64Url).replace("\"", "").trim()
                
                if (decodedUrl.startsWith("http")) {
                    debugLog("[PREMIUM] 프리미엄 정석 스트림 해독 성공 -> $decodedUrl")
                    val refOrigin = if (entryOrigin.isNotEmpty()) entryOrigin else iframeOrigin
                    return ExtractorLink(
                        source = sourceName, name = sourceName, url = decodedUrl,
                        referer = "$refOrigin/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "referer" to "$refOrigin/",
                            "origin" to refOrigin,
                            "user-agent" to userAgent,
                            "accept" to "*/*",
                            "x-direct-connection" to "1",
                            "sec-fetch-dest" to "empty",
                            "sec-fetch-mode" to "cors",
                            "sec-fetch-site" to "cross-site"
                        )
                    )
                }
            }
            
            // 1. wiki.js 플레이어 감지 및 처리
            val wikiJsMatch = Regex("""src=["'](https?://[^"']+|//[^"']+)wiki\.js""").find(iframeHtml)
            if (wikiJsMatch != null) {
                var rawWikiHost = wikiJsMatch.groupValues[1]
                if (rawWikiHost.startsWith("//")) {
                    rawWikiHost = "https:$rawWikiHost"
                }
                var igniteHost = rawWikiHost.trimEnd('/')
                if (igniteHost.contains("stellarthread.com")) {
                    debugLog("[우회] 차단된 도메인 감지! 활성 도메인으로 교체합니다.")
                    igniteHost = "https://igniteandship.com"
                }
                
                val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                val fid = Regex("""fid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                val zmidVar = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                
                val liveId = when {
                    !fid.isNullOrEmpty() -> fid
                    !zmidVar.isNullOrEmpty() -> zmidVar
                    else -> pageName
                }

                val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$liveId"
                val playerHeaders = iframeHeaders.toMutableMap()
                playerHeaders["referer"] = iframeUrl
                playerHeaders["origin"] = iframeOrigin
                
                val playerResp = app.get(playerUrl, headers = playerHeaders, timeout = 8)
                if (playerResp.isSuccessful) {
                    // [중요 보완] wiki.php 가 m3u8을 즉각 반환하는 경우도 완벽 대응
                    if (playerResp.text.contains("#EXTM3U")) {
                        debugLog("[IGNITE-우회] wiki.php에서 M3U8 직접 획득 성공 -> ${playerResp.url}")
                        return ExtractorLink(
                            source = sourceName, name = sourceName, url = playerResp.url,
                            referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                            headers = mapOf(
                                "referer" to "$igniteHost/", "origin" to igniteHost, "user-agent" to userAgent
                            )
                        )
                    }

                    val playerHtml = playerResp.text
                    val arrayMatch = Regex("""\["h"\s*,\s*"t"\s*,\s*"t"\s*,\s*"p"\s*,\s*"s"\s*,[^\]]+\]""").find(playerHtml)
                    if (arrayMatch != null) {
                        var streamUrl = arrayMatch.groupValues[0]
                            .replace("[", "")
                            .replace("]", "")
                            .replace("\"", "")
                            .replace("\\/", "/")
                            .replace(",", "")
                            .trim()
                        
                        val idMatch = Regex("""document\.getElementById\(["']([^"']+)["']\)""").find(playerHtml)?.groupValues?.get(1)
                        val spanMatch = if (idMatch != null) {
                            Regex("""id=["']?$idMatch["']?[^>]*>([^<]*)""").find(playerHtml)?.groupValues?.get(1) ?: ""
                        } else {
                            ""
                        }
                        streamUrl += spanMatch

                        if (streamUrl.startsWith("http")) {
                            debugLog("[IGNITE-우회] 진짜 m3u8 주소 추출 성공 -> $streamUrl")
                            return ExtractorLink(
                                source = sourceName, name = sourceName, url = streamUrl,
                                referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                                headers = mapOf(
                                    "referer" to "$igniteHost/",
                                    "origin" to igniteHost,
                                    "user-agent" to userAgent,
                                    "accept" to "*/*",
                                    "x-direct-connection" to "1",
                                    "sec-fetch-dest" to "empty",
                                    "sec-fetch-mode" to "cors",
                                    "sec-fetch-site" to "same-site"
                                )
                            )
                        }
                    }
                }
            }

            // 2. 자바스크립트 변수로 위장한 동적 iframe 생성 감지 (embedsports.me 동적 생성 우회 및 2세대 sd0embed 구조 우회)
            val edm = Regex("""edm\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
            val zmid = Regex("""zmid\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
            if (!edm.isNullOrEmpty() && !zmid.isNullOrEmpty()) {
                
                val configMatch = Regex("""const\s+siteConfig\s*=\s*(\{[\s\S]*?\});""").find(iframeHtml)
                val configMap = if (configMatch != null) {
                    try { mapper.readValue(configMatch.groupValues[1], object : TypeReference<Map<String, Any>>() {}) } catch (e: Exception) { null }
                } else null
                
                val configCsrf = configMap?.get("csrf") as? String
                val configCsrfIp = configMap?.get("csrf_ip") as? String
                val trackerStr = configMap?.get("tracker") as? String ?: ""
                val gname = Regex("""gname=([^&]+)""").find(trackerStr)?.groupValues?.get(1)?.let {
                    try { URLDecoder.decode(it, "UTF-8") } catch (e: Exception) { it.replace("+", " ") }
                } ?: ""
                
                val csrfVal = when {
                    !configCsrf.isNullOrEmpty() -> configCsrf
                    !metaCsrf.isNullOrEmpty() -> metaCsrf
                    else -> Regex("""(?:csrf|token|auth|key)["']?\s*[:=]\s*["']([a-zA-Z0-9_.-]{50,250})["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
                }
                
                val csrfIpVal = when {
                    !configCsrfIp.isNullOrEmpty() -> configCsrfIp
                    else -> Regex("""(?:csrf_ip|ip|client_ip)["']?\s*[:=]\s*["']([0-9.]+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: ""
                }

                val constructedIframeUrl = if (!csrfVal.isNullOrEmpty()) {
                    val pid = Regex("""pid\s*[:=]\s*["']?([^"';\s]+)["']?""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: "5"
                    val gacat = Regex("""gacat\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: gname
                    
                    val gatxtHtml = Regex("""gatxt\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
                    val gatxtVal = when {
                        !gatxtHtml.isNullOrEmpty() -> gatxtHtml
                        sourceName.contains("tennis", ignoreCase = true) -> "Tennis"
                        sourceName.contains("football", ignoreCase = true) -> "Football"
                        else -> "Tennis" 
                    }
                    val vVal = zmid 
                    
                    val gacatEnc = URLEncoder.encode(gacat, "UTF-8")
                    val gatxtEnc = URLEncoder.encode(gatxtVal, "UTF-8")
                    val pathElement = if (gatxtVal.isNotEmpty()) URLEncoder.encode(gatxtVal, "UTF-8") else zmid
                    
                    "https://$edm/sd0embed/$pathElement?pid=$pid&gacat=$gacatEnc&gatxt=$gatxtEnc&v=$vVal&csrf=$csrfVal&csrf_ip=$csrfIpVal"
                } else {
                    "https://$edm/court/$zmid.php"
                }
                
                debugLog("[$depth 단계] 동적 JS Iframe 가상 복원 성공 -> URL: $constructedIframeUrl")
                
                val nestedHeaders = iframeHeaders.toMutableMap()
                nestedHeaders["referer"] = iframeUrl
                nestedHeaders["origin"] = iframeOrigin
                
                val nestedResp = app.get(constructedIframeUrl, headers = nestedHeaders, timeout = 8)
                debugLog("[$depth 단계] 중첩 요청 응답 코드: ${nestedResp.code} -> $constructedIframeUrl")
                if (nestedResp.isSuccessful) {
                    // [중요 보완] app.get이 302 리다이렉트를 따라갔을 수 있으므로, 응답받은 최종 URL(nestedResp.url)을 전달
                    val link = parseAndExtract(nestedResp.url, nestedResp.text, nestedHeaders, entryOrigin, sourceName, depth + 1)
                    if (link != null) return link
                }
            }

            // 3. 일반적인 중첩 static iframe 수집 및 순회 (재귀적)
            val nestedIframeMatches = Regex("""<iframe\s+[^>]*src=(?:["']([^"']+)["']|([^\s>]+))""", RegexOption.IGNORE_CASE)
                .findAll(iframeHtml)
                .map { match -> match.groupValues[1].ifEmpty { match.groupValues[2] } }
                .distinct()
                .filter { src ->
                    val isStaticAsset = src.endsWith(".js") || src.endsWith(".css") || src.contains("tag.min")
                    val isPlayerPath = src.contains("/court/") || src.contains("/player/") || src.contains("/stream/") || src.contains("/embed/") || src.contains("/wimbledon/") || src.contains("live=") || src.contains("id=")
                    val isPopcdn = src.contains("freeshot") || src.contains("popcdn")
                    !isStaticAsset && (isPlayerPath || isPopcdn)
                }
                .toList()

            for (nestedRawSrc in nestedIframeMatches) {
                var nestedIframeUrl = nestedRawSrc
                val parentOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
                
                if (nestedRawSrc.startsWith("//")) {
                    nestedIframeUrl = "https:$nestedRawSrc"
                } else if (nestedRawSrc.startsWith("/")) {
                    nestedIframeUrl = "$parentOrigin$nestedRawSrc"
                } else if (!nestedRawSrc.startsWith("http")) {
                    nestedIframeUrl = "$parentOrigin/${nestedRawSrc.trimStart('/')}"
                }
                
                val nestedHeaders = iframeHeaders.toMutableMap()
                nestedHeaders["referer"] = iframeUrl
                nestedHeaders["origin"] = iframeOrigin
                
                val nestedResp = app.get(nestedIframeUrl, headers = nestedHeaders, timeout = 8)
                if (nestedResp.isSuccessful) {
                    val link = parseAndExtract(nestedResp.url, nestedResp.text, nestedHeaders, entryOrigin, sourceName, depth + 1)
                    if (link != null) return link
                }
            }
            
            // 4. Popcdn / Freeshot 분기
            if (iframeUrl.contains("freeshot.live") || iframeUrl.contains("popcdn.day")) {
                debugLog("Freeshot / Popcdn 분기 감지")
                var channelCode = ""
                val embedMatch = Regex("""embed/([^/.]+)\.php""").find(iframeUrl)
                if (embedMatch != null) {
                    channelCode = embedMatch.groupValues[1]
                } else {
                    channelCode = iframeUrl.split("/").last().split("?").first()
                }
                
                val targetUrl = "https://popcdn.day/player/$channelCode"
                val popResp = app.get(targetUrl, headers = iframeHeaders, timeout = 8)
                val popBody = popResp.text
                
                var finalM3u8 = ""
                val streamUrlMatch = Regex("""streamUrl\s*:\s*"([^"]+)"""").find(popBody)
                if (streamUrlMatch != null) {
                    finalM3u8 = streamUrlMatch.groupValues[1].replace("\\", "")
                } else {
                    val tokenMatch = Regex("""token=([^&"']+)""").find(popBody)
                    if (tokenMatch != null) {
                        val token = tokenMatch.groupValues[1]
                        finalM3u8 = "https://planetary.lovecdn.ru/$channelCode/tracks-v1a1/mono.m3u8?token=$token"
                    }
                }
                
                if (finalM3u8.isNotEmpty()) {
                    debugLog("Freeshot 추출 성공 -> $finalM3u8")
                    return ExtractorLink(
                        source = sourceName, name = "Freeshot Server", url = finalM3u8,
                        referer = "https://popcdn.day/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                        headers = mapOf(
                            "referer" to "https://popcdn.day/", "origin" to "https://popcdn.day", "user-agent" to userAgent
                        )
                    )
                }
            }
        } catch (e: SocketTimeoutException) {
            debugLog("crawlIframe 타임아웃 발생 (depth=$depth): ${e.message}")
        } catch (e: IOException) {
            debugLog("crawlIframe 네트워크 에러 (depth=$depth): ${e.message}")
        } catch (e: Exception) {
            debugLog("crawlIframe 일반 예외 발생 (depth=$depth): ${e.message}")
        }
        return null
    }
}
