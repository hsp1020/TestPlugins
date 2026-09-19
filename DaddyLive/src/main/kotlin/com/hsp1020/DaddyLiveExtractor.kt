package com.hsp1020

import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.mapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override var mainUrl = "https://dlive.sx"
    override val name = "DaddyLive"
    override val requiresReferer = false

    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"

    /*
     * 사용자 지정 fallback 순서.
     * 선택된 채널 하나에 대해서만 아래 순서로 탐색한다.
     */
    private val players = listOf(
        "stream",
        "plus",
        "watch",
        "player",
        "cast",
        "casting"
    )

    private data class ExtractorChannelSelection(
        val origin: String,
        val eventTitle: String,
        val channelName: String,
        val streamId: String
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
        currentCoroutineContext().ensureActive()

        val selected = try {
            mapper.readValue<ExtractorChannelSelection>(url)
        } catch (_: Exception) {
            null
        }

        /*
         * 예전처럼 List<Pair<channel, url>> 전체를 받지 않는다.
         * Provider의 Episode.data에서 사용자가 선택한 채널 하나만 받는다.
         */
        if (selected != null) {
            val entryOrigin = selected.origin.trimEnd('/').ifEmpty { mainUrl }
            mainUrl = entryOrigin

            debugLog(
                "[${selected.channelName}] 선택 채널 단일 추출 시작 " +
                    "(streamId=${selected.streamId}, 후보=${players.size})"
            )

            for (player in players) {
                currentCoroutineContext().ensureActive()

                val candidateUrl =
                    "$entryOrigin/$player/stream-${selected.streamId}.php"

                val sourceName =
                    "${selected.channelName} - $player"

                debugLog(
                    "[${selected.channelName}] fallback '$player' 시도 -> $candidateUrl"
                )

                val link = extractVideo(
                    candidateUrl,
                    sourceName
                )

                if (link != null) {
                    debugLog(
                        "[${selected.channelName}] '$player' 성공. " +
                            "나머지 fallback 탐색 중지."
                    )

                    callback(link)
                    return
                }
            }

            debugLog(
                "[${selected.channelName}] 모든 fallback 후보에서 " +
                    "재생 가능한 링크를 찾지 못했습니다."
            )
            return
        }

        /*
         * 직접 URL 호출에 대한 최소 호환성.
         * JSON Episode.data가 아닌 실제 URL이 들어오면 한 번만 추출한다.
         */
        val directLink = extractVideo(
            url,
            this.name
        )

        if (directLink != null) {
            callback(directLink)
        }
    }

    private suspend fun extractVideo(
        url: String,
        sourceName: String
    ): ExtractorLink? {
        try {
            currentCoroutineContext().ensureActive()

            debugLog("================= [DDL EXTRACT START] =================")
            debugLog("1. 소스 정보 -> Name: $sourceName, Target URL: $url")

            val parsedUrl = URL(url)
            val entryOrigin =
                "${parsedUrl.protocol}://${parsedUrl.host}"

            mainUrl = entryOrigin

            val requestHeaders = mapOf(
                "user-agent" to userAgent,
                "referer" to url,
                "accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8"
            )

            /*
             * HTTP/1.1 강제 없음.
             * CloudStream 기본 app/NiceHttp 클라이언트를 그대로 사용한다.
             */
            val resp = withContext(Dispatchers.IO) {
                app.get(
                    url,
                    headers = requestHeaders,
                    timeout = 5
                )
            }

            debugLog("2. 플레이어 페이지 응답 코드: ${resp.code}")

            if (!resp.isSuccessful) {
                debugLog(
                    "플레이어 페이지 실패 HTTP ${resp.code}. " +
                        "다음 fallback으로 이동."
                )
                return null
            }

            val iframeHeaders = requestHeaders.toMutableMap()
            iframeHeaders["referer"] = url
            iframeHeaders["origin"] = entryOrigin

            /*
             * 기존 구조:
             *  - iframe 전부 async 병렬 다운로드
             *  - 그 뒤 일괄 분석
             *
             * 변경 구조:
             *  - 현재 플레이어 HTML 자체부터 분석
             *  - parseAndExtract가 중첩 iframe을 순차적으로 내려간다.
             *  - 첫 성공 즉시 return
             *
             * 선택되지 않은 다른 채널에는 어떤 요청도 보내지 않는다.
             */
            val link = parseAndExtract(
                iframeUrl = url,
                iframeHtml = resp.text,
                iframeHeaders = iframeHeaders,
                entryOrigin = entryOrigin,
                sourceName = sourceName,
                depth = 0
            )

            if (link != null) {
                debugLog("================= [DDL EXTRACT SUCCESS] =================")
                return link
            }

            debugLog(
                "알림: 현재 fallback '$sourceName'에서 " +
                    "유효한 진짜 스트림 주소를 획득하지 못했습니다."
            )
            debugLog("================= [DDL EXTRACT END] =================")

            return null
        } catch (e: CancellationException) {
            debugLog("extractVideo 취소 감지 -> 전체 작업 즉시 중단")
            throw e
        } catch (e: SocketTimeoutException) {
            debugLog(
                "extractVideo 소켓 연결 타임아웃 예외 발생: ${e.message}"
            )
            return null
        } catch (e: IOException) {
            debugLog(
                "extractVideo 네트워크 입출력 에러 발생: ${e.message}"
            )
            return null
        } catch (e: Exception) {
            debugLog(
                "extractVideo 일반 예외 발생: ${e.message}"
            )
            return null
        }
    }

    // 중첩된 껍데기 페이지 및 다중 iframe 깊이를 파고들며 탐색하는 수집 엔진 (더미 출력 로그 전면 제거 완료)
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
                
                // 해독 결과가 가짜 허니팟(/hls/stream...)이 아닌 진짜 절대 경로 CDN 주소일 때만 정상 반환
                if (decodedUrl.startsWith("http")) {
                    debugLog("[PREMIUM] 프리미엄 정석 스트림 해독 성공 -> $decodedUrl")
                    
                    // [수정] 메인 도메인(entryOrigin) 대신 실제 플레이어가 삽입된 iframe 호스트(iframeOrigin)를 강제 사용합니다.
                    val refOrigin = iframeOrigin
                    
                    return newExtractorLink(
                        source = sourceName,
                        name = sourceName,
                        url = decodedUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "$refOrigin/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "referer" to "$refOrigin/",
                            "origin" to refOrigin,
                            "user-agent" to userAgent,
                            "accept" to "*/*",
                            "x-direct-connection" to "1",
                            "sec-fetch-dest" to "empty",
                            "sec-fetch-mode" to "cors",
                            "sec-fetch-site" to "cross-site"
                        )
                    }
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
                
                // stellarthread.com 차단 도메인 감지 시, 현재 정상 동작하는 igniteandship.com으로 동적 자동 치환
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

                // 진짜 영상 토큰이 탑재될 플레이어 페이지 호출
                val playerUrl = "$igniteHost/wiki.php?player=desktop&live=$liveId"
                val playerHeaders = mapOf(
                    "user-agent" to userAgent,
                    "referer" to iframeUrl,
                    "origin" to iframeOrigin
                )
                
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
                            return newExtractorLink(
                                source = sourceName,
                                name = sourceName,
                                url = streamUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$igniteHost/"
                                this.quality = Qualities.Unknown.value
                                this.headers = finalHeaders
                            }
                        }
                    }
                }
            }

            // 2. 자바스크립트 변수로 위장한 동적 iframe 생성 감지 (embedsports.me 동적 생성 우회)
            val edm = Regex("""edm\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            val zmid = Regex("""zmid\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
            if (!edm.isNullOrEmpty() && !zmid.isNullOrEmpty()) {
                val constructedIframeUrl = "https://$edm/court/$zmid.php"
                debugLog("[$depth 단계] 동적 JS Iframe 가상 복원 성공 -> URL: $constructedIframeUrl")
                
                val nestedResp = app.get(constructedIframeUrl, headers = iframeHeaders, timeout = 5)
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
                
                val nestedResp = app.get(nestedIframeUrl, headers = iframeHeaders, timeout = 5)
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
                    return newExtractorLink(
                        source = sourceName,
                        name = "Freeshot Server",
                        url = finalM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://popcdn.day/"
                        this.quality = Qualities.Unknown.value
                        this.headers = mapOf(
                            "referer" to "https://popcdn.day/",
                            "origin" to "https://popcdn.day",
                            "user-agent" to userAgent
                        )
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
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
