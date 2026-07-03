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
    override var mainUrl = "https://dlhd.st" 
    override val name = "DaddyLive"
    override val requiresReferer = false
    
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
            debugLog("[Tennis-Extract] 1. 소스 정보 -> Name: $sourceName, Target URL: $url")
            
            val parsedUrl = URL(url)
            val entryOrigin = "${parsedUrl.protocol}://${parsedUrl.host}"
            mainUrl = entryOrigin
            
            val requestHeaders = mapOf(
                "user-agent" to userAgent,
                "referer" to url,
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            val resp = withContext(Dispatchers.IO) {
                app.get(url, headers = requestHeaders, timeout = 5)
            }
            debugLog("[Tennis-Extract] 2. 부모 플레이어 페이지 응답 코드: ${resp.code}")
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
            
            debugLog("[Tennis-Extract] 3. 필터링 통과한 유효 Iframe 목록 수집 완료: $iframeMatches")
            
            val iframeHeaders = requestHeaders.toMutableMap()
            iframeHeaders["referer"] = url
            iframeHeaders["origin"] = entryOrigin

            // 비동기 병렬 네트워크 통신 적용
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
                        
                        // wikisport.wiki의 유효하지 않은 SSL 오설정 예외 우회를 위해 강제로 http:// 호출 처리
                        if (iframeUrl.startsWith("https://wikisport.wiki")) {
                            iframeUrl = iframeUrl.replace("https://wikisport.wiki", "http://wikisport.wiki")
                        }
                        
                        try {
                            val iframeResp = app.get(iframeUrl, headers = iframeHeaders, timeout = 5)
                            if (iframeResp.isSuccessful) {
                                iframeUrl to iframeResp.text
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

            debugLog("[Tennis-Extract] 알림: 수집된 모든 iframe 내부에서 비디오 스트림 주소를 획득하지 못했습니다.")
            debugLog("================= [DDL EXTRACT END] =================")
            return null
        } catch (e: SocketTimeoutException) {
            debugLog("[Tennis-Error] 소켓 연결 타임아웃 예외 발생: ${e.message}")
            return null
        } catch (e: IOException) {
            debugLog("[Tennis-Error] 네트워크 데이터 입출력 에러 발생: ${e.message}")
            return null
        } catch (e: Exception) {
            debugLog("[Tennis-Error] 예외 발생: ${e.message}")
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
            val parsedIframe = URL(iframeUrl)
            val iframeOrigin = "${parsedIframe.protocol}://${parsedIframe.host}"
            debugLog("[$depth 단계-진입] Iframe 대상 데이터 분석 개시 -> $iframeUrl")

            // 1. 프리미엄 채널용 정석 atob 복구 (가짜 허니팟 필터 차단 적용)
            val atobMatch = Regex("""atob\(['"](.*?)['"]\)""").find(iframeHtml)
            if (atobMatch != null) {
                val b64Url = atobMatch.groupValues[1]
                val decodedUrl = base64Decode(b64Url).replace("\"", "").trim()
                if (decodedUrl.startsWith("http")) {
                    debugLog("[Tennis-Player] 프리미엄 정석 스트림 해독 성공 -> $decodedUrl")
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
                    debugLog("[Tennis-Iframe] [$depth 단계] 감지된 주소($decodedUrl)는 가짜 허니팟 주소이므로 우회를 지속합니다.")
                }
            }
            
            // 2. [정교한 무탐욕 정규식] 모든 동적 플레이어 자바스크립트 수집 (wiki, pavel, viktor 및 향후 생성될 모든 임의의 파일명 오차 없이 자동 포착)
            val jsMatches = Regex("""src=["']((?:https?:)?//[^"']+/)([^/"'#?]+)\.js""", RegexOption.IGNORE_CASE).findAll(iframeHtml)
            for (match in jsMatches) {
                var rawWikiHost = match.groupValues[1]
                if (rawWikiHost.startsWith("//")) {
                    rawWikiHost = "https:$rawWikiHost"
                }
                var igniteHost = rawWikiHost.trimEnd('/')
                val jsName = match.groupValues[2] // wiki, pavel, viktor 등만 순수 추출
                
                // 광고 플랫폼 및 무관한 스크립트는 원천 제외
                if (jsName == "suv4" || jsName == "suv" || jsName == "js15_as" || jsName == "beacon" || jsName == "tag" || jsName == "embed" || jsName == "adcashign" || jsName.contains("analytics")) continue
                
                // 차단 도메인 우회 대응 (owledge.cc 백업 미러군 포함)
                val hostCandidates = if (igniteHost.contains("stellarthread.com") || igniteHost.contains("igniteandship.com") || igniteHost.contains("owledge.cc")) {
                    listOf("https://igniteandship.com", "https://owledge.cc")
                } else {
                    listOf(igniteHost)
                }

                val pageName = parsedIframe.path.substringAfterLast("/").substringBefore(".php")
                val fid = Regex("""fid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                val zmidVar = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
                
                val liveId = when {
                    !fid.isNullOrEmpty() -> fid
                    !zmidVar.isNullOrEmpty() -> zmidVar
                    else -> pageName
                }
                debugLog("[Tennis-Player] [$depth 단계] 진짜 중계 플레이어 감지! 스크립트명: ${jsName}.js, liveId: $liveId")

                for (host in hostCandidates) {
                    try {
                        val playerUrl = "$host/${jsName}.php?player=desktop&live=$liveId"
                        val playerHeaders = mapOf(
                            "user-agent" to userAgent,
                            "referer" to iframeUrl,
                            "origin" to iframeOrigin
                        )
                        debugLog("[Tennis-Player] 플레이어 페이지 우회 요청 시도 ($host) -> URL: $playerUrl")
                        val playerResp = app.get(playerUrl, headers = playerHeaders, timeout = 5)
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
                                    debugLog("[Tennis-Player] 진짜 m3u8 주소 추출 성공 -> $streamUrl")
                                    val finalHeaders = mapOf(
                                        "referer" to "$host/",
                                        "origin" to host,
                                        "user-agent" to userAgent,
                                        "accept" to "*/*",
                                        "x-direct-connection" to "1",
                                        "sec-fetch-dest" to "empty",
                                        "sec-fetch-mode" to "cors",
                                        "sec-fetch-site" to "same-site"
                                    )
                                    return ExtractorLink(
                                        source = sourceName, name = sourceName, url = streamUrl,
                                        referer = "$host/", quality = Qualities.Unknown.value, type = ExtractorLinkType.M3U8,
                                        headers = finalHeaders
                                    )
                                }
                            }
                        }
                    } catch (e: Exception) {
                        debugLog("[Tennis-Error] 호스트($host) 플레이어 데이터 수집 실패: ${e.message}")
                    }
                }
            }

            // [우회 - Referer/Origin 동적 교정 적용] 자바스크립트 변수 위장 동적 iframe 생성 감지 (tc12.php 등)
            val edm = Regex("""edm\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            val zmid = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            if (!edm.isNullOrEmpty() && !zmid.isNullOrEmpty()) {
                val constructedIframeUrl = "https://$edm/court/$zmid.php"
                debugLog("[Tennis-Iframe] [$depth 단계] 동적 JS Iframe 가상 복원 성공 -> URL: $constructedIframeUrl")
                
                // [CORS 우회 통과 적용] Referer와 Origin 헤더를 직전 부모의 주소(iframeUrl)로 정밀 치환하여 403/404 차단 해제
                val nextHeaders = iframeHeaders.toMutableMap()
                nextHeaders["referer"] = iframeUrl
                nextHeaders["origin"] = iframeOrigin

                val nestedResp = app.get(constructedIframeUrl, headers = nextHeaders, timeout = 5)
                if (nestedResp.isSuccessful) {
                    val link = parseAndExtract(constructedIframeUrl, nestedResp.text, nextHeaders, entryOrigin, sourceName, depth + 1)
                    if (link != null) return link
                } else {
                    debugLog("[Tennis-Iframe] [$depth 단계] 가상 조립 Iframe 호출 실패. 코드: ${nestedResp.code}")
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
                
                // 상대 경로 복원 처리
                if (nestedRawSrc.startsWith("//")) {
                    nestedIframeUrl = "https:$nestedRawSrc"
                } else if (nestedRawSrc.startsWith("/")) {
                    nestedIframeUrl = "$parentOrigin$nestedRawSrc"
                } else if (!nestedRawSrc.startsWith("http")) {
                    nestedIframeUrl = "$parentOrigin/${nestedRawSrc.trimStart('/')}"
                }
                
                // [보완] wikisport.wiki의 오설정된 SSL 인증 우회를 위해 강제로 http:// 호출 처리
                if (nestedIframeUrl.startsWith("https://wikisport.wiki")) {
                    nestedIframeUrl = nestedIframeUrl.replace("https://wikisport.wiki", "http://wikisport.wiki")
                }
                
                if (nestedIframeUrl.endsWith(".js") || nestedIframeUrl.endsWith(".css") || nestedIframeUrl.contains("tag.min")) continue
                
                val nextHeaders = iframeHeaders.toMutableMap()
                nextHeaders["referer"] = iframeUrl
                nextHeaders["origin"] = iframeOrigin

                val nestedResp = app.get(nestedIframeUrl, headers = nextHeaders, timeout = 5)
                if (nestedResp.isSuccessful) {
                    val link = parseAndExtract(nestedIframeUrl, nestedResp.text, nextHeaders, entryOrigin, sourceName, depth + 1)
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
                val popResp = app.get(targetUrl, headers = iframeHeaders, timeout = 5)
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
            debugLog("crawlIframe depth=$depth 에서 예외 발생: ${e.message}")
        } catch (e: IOException) {
            debugLog("crawlIframe depth=$depth 에서 예외 발생: ${e.message}")
        } catch (e: Exception) {
            debugLog("crawlIframe depth=$depth 에서 예외 발생: ${e.message}")
        }
        return null
    }
}
