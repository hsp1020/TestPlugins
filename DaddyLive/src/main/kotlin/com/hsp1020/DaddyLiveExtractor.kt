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

class DaddyLiveExtractor : ExtractorApi() {
    override var mainUrl = "https://dlhd.st" // Provider와 통일
    override val name = "DaddyLive"
    override val requiresReferer = false
    
    // 글로벌 통일된 Chrome 브라우저 User-Agent 정의
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "referer" to mainUrl,
        "user-agent" to userAgent,
        "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
    )

    private fun debugLog(message: String) {
        Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
        println("[DDL-DEBUG] $message")
    }

    private fun printLargeLog(tag: String, message: String) {
        // 안드로이드 로그캣 버퍼 오염을 완벽히 방지하기 위해 30,000자 이하의 실제 플레이어 코드만 출력 필터링합니다.
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
            
            // Extractor의 입력 URL에 맞춰 mainUrl 및 Origin 도메인을 실시간으로 동적 바인딩합니다.
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            mainUrl = entryOrigin
            
            val requestHeaders = mapOf(
                "user-agent" to userAgent,
                "referer" to url,
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            // UI 스레드 차단 방지를 위해 백그라운드 스레드풀에서 페이지 호출
            val resp = withContext(Dispatchers.IO) {
                app.get(url, headers = requestHeaders, timeout = 3000)
            }
            debugLog("2. 플레이어 페이지 응답 코드: ${resp.code}")
            if (!resp.isSuccessful) return null
            val html = resp.text
            
            // CORS 및 화이트리스트 기반 중계 전용 iframe 주소만 안전하게 획득
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

            // [비동기 병렬 네트워크 통신]
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
                            val iframeResp = app.get(iframeUrl, headers = iframeHeaders, timeout = 3000)
                            if (iframeResp.isSuccessful) {
                                iframeUrl to iframeResp.text
                            } else null
                        } catch (e: Exception) {
                            null
                        }
                    }
                }.mapNotNull { it.await() }
            }

            // 병렬 로드가 완료된 HTML 소스들을 CPU로 고속 파싱 진행
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

    // 중첩된 페이지 및 다중 iframe 깊이를 재귀적으로 파고들며 탐색하는 핵심 유닛 (더미 출력 로그 전면 제거 완료)
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
            val parsedIframe = URL(iframeUrl)
            val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"

            // =======================================================
            // [복구 및 보안 필터] 프리미엄 채널용 정석 atob 복구 (가짜 허니팟은 필터링)
            // =======================================================
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val decodedUrl = base64Decode(b64Url).replace("\"", "").trim()
                
                // 해독 결과가 가짜 허니팟 주소(/hls/stream...)가 아니고, 정상적인 외부 절대 경로 CDN 주소인 경우에만 승인
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
                } else {
                    debugLog("[atob-필터링] 감지된 주소($decodedUrl)는 가짜 허니팟 주소이므로 우회를 지속합니다.")
                }
            }
            
            // 1. 해당 HTML 본문 내에 wiki.js가 탑재되어 있는지 확인
            val wikiJsMatch = Regex("""src=["'](https?://[^"']+|//[^"']+)wiki\.js""").find(iframeHtml)
            if (wikiJsMatch != null) {
                var rawWikiHost = wikiJsMatch.groupValues[1]
                if (rawWikiHost.startsWith("//")) {
                    rawWikiHost = "https:$rawWikiHost"
                }
                var igniteHost = rawWikiHost.trimEnd('/')
                if (igniteHost.contains("stellarthread.com")) {
                    debugLog("[우회] 차단된 도메인(stellarthread) 감지! 활성 도메인(igniteandship.com)으로 자동 교체합니다.")
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
                debugLog("[IGNITE-우회] 진짜 중계 플레이어 감지! 호스트: $igniteHost, liveId: $liveId")

                val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$liveId"
                val playerHeaders = mapOf(
                    "user-agent" to userAgent,
                    "referer" to iframeUrl,
                    "origin" to iframeOrigin
                )
                
                val playerResp = app.get(playerUrl, headers = playerHeaders)
                if (playerResp.isSuccessful) {
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
                            val finalHeaders = mapOf(
                                "referer" to "$igniteHost/",
                                "origin" to igniteHost,
                                "user-agent" to userAgent,
                                "accept" to "*/*",
                                "x-direct-connection" to "1",
                                "sec-fetch-dest" to "empty",
                                "sec-fetch-mode" to "cors",
                                "sec-fetch-site" to "same-site"
                            )
                            return ExtractorLink(
                                source = sourceName, name = sourceName, url = streamUrl,
                                referer = "$igniteHost/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                                headers = finalHeaders
                            )
                        }
                    }
                }
            }

            // 2. [완전 보완] 자바스크립트 변수로 위장한 동적 iframe 생성 감지 (embedsports.me 동적 생성 우회 및 2세대 sd0embed 구조 우회)
            val edm = Regex("""edm\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
            val zmid = Regex("""zmid\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
            if (!edm.isNullOrEmpty() && !zmid.isNullOrEmpty()) {
                
                // [정밀 보완] 등호(=)와 콜론(:)을 모두 지원하는 초정밀 통합 정규식을 사용하여 csrf 값이 절대 누락되지 않도록 조치합니다.
                val csrf = Regex("""(?:csrf|token|auth|key)["']?\s*[:=]\s*["']([a-zA-Z0-9_-]{50,150})["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1)
                
                val constructedIframeUrl = if (!csrf.isNullOrEmpty()) {
                    val pid = Regex("""pid\s*[:=]\s*["']?([^"';\s]+)["']?""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: "5"
                    val gacat = Regex("""gacat\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: ""
                    val gatxt = Regex("""gatxt\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: ""
                    val v = Regex("""v\s*[:=]\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: ""
                    val csrfIp = Regex("""(?:csrf_ip|ip|client_ip)["']?\s*[:=]\s*["']([0-9.]+)["']""", RegexOption.IGNORE_CASE).find(iframeHtml)?.groupValues?.get(1) ?: ""
                    
                    val gacatEnc = java.net.URLEncoder.encode(gacat, "UTF-8")
                    val gatxtEnc = java.net.URLEncoder.encode(gatxt, "UTF-8")
                    
                    // [정밀 보완] 스크린샷 3&4의 진짜 sd0embed 경로로 동적 변수를 결합하여 완벽한 플레이어 주소를 복원합니다.
                    val pathElement = if (gatxt.isNotEmpty()) gatxt else zmid
                    "https://$edm/sd0embed/$pathElement?pid=$pid&gacat=$gacatEnc&gatxt=$gatxtEnc&v=$v&csrf=$csrf&csrf_ip=$csrfIp"
                } else {
                    "https://$edm/court/$zmid.php"
                }
                
                debugLog("[$depth 단계] 동적 JS Iframe 가상 복원 성공 -> URL: $constructedIframeUrl")
                
                val nestedResp = app.get(constructedIframeUrl, headers = iframeHeaders, timeout = 3000)
                if (nestedResp.isSuccessful) {
                    val link = parseAndExtract(constructedIframeUrl, nestedResp.text, iframeHeaders, entryOrigin, sourceName, depth + 1)
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
                
                val nestedResp = app.get(nestedIframeUrl, headers = iframeHeaders, timeout = 3000)
                if (nestedResp.isSuccessful) {
                    val link = parseAndExtract(nestedIframeUrl, nestedResp.text, iframeHeaders, entryOrigin, sourceName, depth + 1)
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
                val popResp = app.get(targetUrl, headers = iframeHeaders, timeout = 3000)
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
