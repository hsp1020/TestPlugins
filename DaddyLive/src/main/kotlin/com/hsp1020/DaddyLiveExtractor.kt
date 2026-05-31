package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.exc.MismatchedInputException
import com.lagradost.api.Log
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.base64Decode
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.jsoup.Jsoup
import java.net.URL

class DaddyLiveExtractor : ExtractorApi() {
    override val mainUrl = "https://dlhd.pk"
    override val name = "DaddyLive"
    override val requiresReferer = false
    private val userAgent =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/132.0.0.0 Safari/537.36"
    private val headers = mapOf(
        "Referer" to mainUrl,
        "user-agent" to userAgent
    )

    private fun debugLog(message: String, throwable: Throwable? = null) {
        val errMessage = throwable?.message ?: "Unknown Error"
        val formatted = if (throwable != null) {
            "[DDL-DEBUG] $message | Exception: $errMessage"
        } else {
            "[DDL-DEBUG] $message"
        }
        
        if (throwable != null) {
            Log.e("DDL-DEBUG", "[DDL-DEBUG] $message: $errMessage")
            println(formatted)
        } else {
            Log.d("DDL-DEBUG", "[DDL-DEBUG] $message")
            println(formatted)
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        debugLog("getUrl: Triggered extractor. Raw URL input payload: $url")
        val links = tryParseJson<List<Pair<String, String>>>(url)
        
        val extractors = if (links != null) {
            debugLog("getUrl: Parsed payload list of size ${links.size}. Initializing extraction queue.")
            links.map {
                extractVideo(it.second, it.first)
            }
        } else {
            debugLog("getUrl: Input payload is not list-formatted. Single link extraction initiated: $url")
            listOf(extractVideo(url))
        }

        var successfullyExtractedCount = 0
        extractors.forEach {
            if (it != null) {
                successfullyExtractedCount++
                callback(it)
            }
        }
        debugLog("getUrl SUCCESS: Extracted $successfullyExtractedCount stream link(s) out of ${extractors.size} candidates.")
    }

    private suspend fun extractVideo(url: String, sourceName: String = this.name): ExtractorLink? {
        debugLog("extractVideo: Loading player host page: $url")
        if (!url.contains("dlhd")) {
            debugLog("extractVideo ERROR: URL does not belong to dlhd domain. Terminating.")
            return null
        }

        return try {
            val resp = app.post(url, headers = headers).document
            val iframes = resp.select("iframe")
            val iframeSrc = iframes.attr("src")
            debugLog("extractVideo: Extracted iframe elements. Candidate src attribute: '$iframeSrc'")
            
            if (iframeSrc.isNullOrEmpty()) {
                debugLog("extractVideo ERROR: FAILED to find valid iframe source inside document.")
                return null
            }
            
            val url1 = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            val parsedUrl = URL(url1)
            val refererBase = "${parsedUrl.protocol}://${parsedUrl.host}"
            debugLog("extractVideo: Formatted iframe source: '$url1'. Parsed referer base: '$refererBase'")

            val finalUrl = if (url1.contains("vidembed")) {
                debugLog("extractVideo: Vidembed signature matched. Executing extractFromVidembed.")
                extractFromVidembed(url1)
            } else {
                debugLog("extractVideo: Routing to extractFromNewzar with HTML caching.")
                extractFromNewzar(url1, refererBase)
            }

            if (finalUrl == null) {
                debugLog("extractVideo ERROR: Media extraction failed. Final media URL resolved to null.")
                return null
            }

            debugLog("extractVideo: Construction of ExtractorLink payload initiated with finalUrl: $finalUrl")
            newExtractorLink(
                sourceName,
                sourceName,
                finalUrl,
                type = ExtractorLinkType.M3U8,
            ) {
                this.referer = "$refererBase/"
                this.quality = Qualities.Unknown.value
                this.headers = mapOf(
                    "Referer" to "$refererBase/",
                    "Origin" to refererBase,
                    "Connection" to "Keep-Alive",
                    "User-Agent" to userAgent
                )
            }
        } catch (e: Exception) {
            debugLog("extractVideo EXCEPTION at target URL: $url", e)
            null
        }
    }

    private suspend fun extractFromVidembed(urlNextPage: String): String? {
        debugLog("extractFromVidembed: Started parsing for Vidembed stream: $urlNextPage")
        return try {
            val vidembedHost = URL(urlNextPage).host
            val liveId = urlNextPage.substringAfterLast("/").substringBefore("#")
            val liveUrl = "https://www.$vidembedHost/api/source/$liveId?type=live"
            val requestBody = "{\"r\":\"https://thedaddy.top/\",\"d\":\"www.$vidembedHost\"}"
            val referer = if (urlNextPage.contains("//www.")) urlNextPage
            else {
                "https://www." + urlNextPage.substringAfter("https://").substringBefore("#")
            }
            val headers = mapOf(
                "User-Agent" to userAgent,
                "Referer" to referer,
                "Origin" to referer.substringBefore("/stream"),
                "X-Requested-With" to "XMLHttpRequest"
            )
            debugLog("extractFromVidembed: Requesting API $liveUrl with payload $requestBody")
            val resp = app.post(liveUrl, headers, referer = referer, json = requestBody).body.string()
            
            val data = parseJson<VidembedResponse>(resp)
            debugLog("extractFromVidembed: API payload fetched successfully. Player: ${data.player}")
            null
        } catch (e: Exception) {
            debugLog("extractFromVidembed FAILED", e)
            null
        }
    }

    private suspend fun extractFromNewzar(urlNextPage: String, serverUrl: String): String? {
        debugLog("extractFromNewzar: Loading iframe contents: $urlNextPage")
        return try {
            val pageResponse = app.get(urlNextPage, headers)
            debugLog("extractFromNewzar: Page response received with code ${pageResponse.code}")
            
            val pageHtml = pageResponse.body.string()
            val page = Jsoup.parse(pageHtml)
            
            val id = try {
                urlNextPage.substringAfter("id=").substringBefore("&")
            } catch (e: Exception) {
                "1"
            }
            
            val scriptElement = page.select("script").firstOrNull { 
                it.data().contains("channelKey") || it.data().contains("CHANNEL_KEY") 
            }
            
            if (scriptElement != null) {
                val script = scriptElement.data()
                debugLog("extractFromNewzar: Script payload identified. Length: ${script.length} characters.")

                // 1. 채널 키 동적 파싱
                val channelKey = Regex("""channelKey\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                    ?: Regex("""CHANNEL_KEY\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                
                if (channelKey != null) {
                    debugLog("extractFromNewzar: Successfully extracted channelKey: '$channelKey'")

                    // 2. 인증 파라미터 동적 파싱 및 Fallback 처리
                    var authTs = Regex("""authTs\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                        ?: Regex("""authTs\s*=\s*([0-9]+)""").find(script)?.groupValues?.get(1)
                    var authRnd = Regex("""authRnd\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                    var authSig = Regex("""authSig\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)

                    if (authTs == null || authRnd == null || authSig == null) {
                        debugLog("extractFromNewzar: Direct authentication parameters are missing. Attempting IJXX bundle fallback parsing.")
                        val bundleBase64 = Regex("""IJXX\s*=\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1)
                        if (bundleBase64 != null) {
                            val bundle = base64Decode(bundleBase64)
                            val bundleObj = tryParseJson<Bundle>(bundle)
                            if (bundleObj != null) {
                                authTs = base64Decode(bundleObj.bTs)
                                authRnd = base64Decode(bundleObj.bRnd)
                                authSig = base64Decode(bundleObj.bSig)
                                debugLog("extractFromNewzar: Successfully recovered fallback parameters from IJXX bundle.")
                            }
                        }
                    }

                    if (authTs != null && authRnd != null && authSig != null) {
                        debugLog("extractFromNewzar: Active auth values: ts='$authTs', rnd='$authRnd', sig='$authSig'")

                        val authHost = Regex("""(https?://[^\s]+/auth\.php\?channel_id=)""").find(script)?.groupValues?.get(1)
                            ?: "https://top2new.newkso.ru/auth.php?channel_id="
                        debugLog("extractFromNewzar: Designated validation endpoint: $authHost$channelKey")

                        val params = mapOf(
                            "channel_id" to channelKey,
                            "ts" to authTs,
                            "rnd" to authRnd,
                            "sig" to authSig,
                        )

                        // 3. auth.php 세션 인증 트리거 호출
                        val authResponse = app.get(
                            "$authHost$channelKey",
                            params = params,
                            headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to "$serverUrl/",
                                "Origin" to serverUrl,
                                "Connection" to "Keep-Alive"
                            )
                        )
                        debugLog("extractFromNewzar: auth.php execution finished. Server code: ${authResponse.code}")
                        if (authResponse.code != 403) {
                            // 4. server_lookup.php 호출
                            val serverLookupUrl = "$serverUrl/server_lookup.php?channel_id=$channelKey"
                            debugLog("extractFromNewzar: Fetching hosting key from: $serverLookupUrl")
                            val serverKeyResp = app.get(serverLookupUrl).body.string()
                            debugLog("extractFromNewzar: Received server key payload raw string: '$serverKeyResp'")
                            
                            val data = try { 
                                parseJson<DataResponse>(serverKeyResp) 
                            } catch (e: Exception) {
                                null
                            }

                            if (data != null) {
                                debugLog("extractFromNewzar: Extracted serverKey token: '${data.serverKey}'")

                                // 5. iframe 내부의 HLS 호스트 주소 파싱
                                val host = Regex("""serverKey\s*\+\s*["']([^"']+)["']""").find(script)?.groupValues?.get(1) 
                                    ?: "new.newkso.ru"
                                debugLog("extractFromNewzar: Extracted hosting domain suffix pattern: '$host'")

                                val serverKey = data.serverKey
                                val m3u8Url = if (serverKey == "top1/cdn") {
                                    "https://top1.newkso.ru/top1/cdn/$channelKey/mono.m3u8"
                                } else {
                                    "https://${serverKey}$host/${serverKey}/$channelKey/mono.m3u8"
                                }
                                debugLog("extractFromNewzar: Successfully built initial m3u8 stream location: $m3u8Url")

                                // 6. HTTP 리다이렉트(302) 추적하여 최종 md5 & expires 주소 도출
                                val streamHeaders = mapOf(
                                    "User-Agent" to userAgent,
                                    "Referer" to "$serverUrl/",
                                    "Origin" to serverUrl,
                                    "Connection" to "Keep-Alive"
                                )
                                
                                debugLog("extractFromNewzar: Initiating HTTP stream redirect analysis to capture secure tokens...")
                                val streamResponse = app.get(m3u8Url, headers = streamHeaders, allowRedirects = false)
                                val location = streamResponse.headers["Location"] ?: streamResponse.headers["location"]
                                debugLog("extractFromNewzar: Redirect response status: ${streamResponse.code}. Redirect Location header: '$location'")
                                
                                val finalUrl = if (!location.isNullOrEmpty()) {
                                    if (location.startsWith("/")) {
                                        val parsedM3u8 = URL(m3u8Url)
                                        "${parsedM3u8.protocol}://${parsedM3u8.host}$location"
                                    } else {
                                        location
                                    }
                                } else {
                                    streamResponse.url
                                }

                                debugLog("extractFromNewzar SUCCESS: Resolved dynamic tokenized playback URL: $finalUrl")
                                return finalUrl
                            }
                        }
                    }
                }
            }

            debugLog("extractFromNewzar: Script-based parsing skipped. Falling back to general HTML/Packer parser on cached DOM.")
            findM3u8InHtml(pageHtml, id)
        } catch (e: Exception) {
            debugLog("extractFromNewzar EXCEPTION encountered during Newzar parsing.", e)
            null
        }
    }

    private fun encodeBase(num: Int, radix: Int): String {
        val chars = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ"
        if (radix <= 36) {
            return num.toString(radix)
        }
        var n = num
        var result = ""
        while (n > 0) {
            result = chars[n % radix] + result
            n /= radix
        }
        return result.ifEmpty { "0" }
    }

    private fun findM3u8InDecodedStrings(decodedEven: String, decodedOdd: String, id: String): String? {
        val m3u8Regex = Regex("""(?:https?:)?//[^"'`\s]+\.m3u8[^"'`\s]*""", RegexOption.IGNORE_CASE)
        
        // 1. 디코딩된 자바스크립트 소스 풀 내에 온전한 스트림 주소가 잡히는지 확인
        val match = m3u8Regex.find(decodedEven) ?: m3u8Regex.find(decodedOdd)
        if (match != null) {
            val cleanUrl = match.value.replace("\\/", "/").trim()
            val finalUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl
            debugLog("findM3u8InDecodedStrings: Found complete .m3u8 URL: $finalUrl")
            return finalUrl
        }
        
        // 2. 만약 조각나 수집될 경우, 디코딩 정보 내부에서 동적 스트리밍 엣지 도메인 대역을 색출
        val domainRegex = Regex("""[^"'`\s/]+\.(?:hlsjs\.ru|onlinehdhls\.ru|openhd\.lol|mizhls\.ru|newkso\.ru)""", RegexOption.IGNORE_CASE)
        val pathRegex = Regex("""/[^"'`\s]+\.m3u8[^"'`\s]*""", RegexOption.IGNORE_CASE)
        
        val host = domainRegex.find(decodedEven)?.value 
            ?: domainRegex.find(decodedOdd)?.value 
            ?: "webhdrus.onlinehdhls.ru"
            
        val path = pathRegex.find(decodedEven)?.value 
            ?: pathRegex.find(decodedOdd)?.value
            ?: "/lb/premium$id/index.m3u8"
            
        debugLog("findM3u8InDecodedStrings: Reconstructed URL from Host: '$host', Path: '$path'")
        return "https://$host$path"
    }

    private fun findM3u8InHtml(html: String, id: String): String? {
        // 1. 문자열 테이블 변수 'f' 검출 및 교차 해독기 가동
        val fValue = Regex("""var\s+f\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""const\s+f\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            ?: Regex("""let\s+f\s*=\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            
        if (fValue != null) {
            // [Step 1] 난독화 원문 전체 로깅
            debugLog("findM3u8InHtml: [Step 1] Raw Encrypted fValue = $fValue")
            val kEven = StringBuilder()
            val kOdd = StringBuilder()
            for (i in fValue.indices) {
                if (i % 2 == 0) {
                    kEven.append(fValue[i])
                } else {
                    kOdd.append(fValue[i])
                }
            }
            
            val decodedEven = kEven.toString().reversed()
            val decodedOdd = kOdd.toString()
            
            // [Step 2 & 3] 분리된 짝수 영역과 홀수 영역 분석 데이터 로깅
            debugLog("findM3u8InHtml: [Step 2] Decoded Even (Reversed) = $decodedEven")
            debugLog("findM3u8InHtml: [Step 3] Decoded Odd = $decodedOdd")
            
            val evenWords = decodedEven.split("z")
            val oddWords = decodedOdd.split("z")
            
            val reconstructedWords = mutableListOf<String>()
            val minSize = minOf(evenWords.size, oddWords.size)
            for (i in 0 until minSize) {
                val evenWord = evenWords[evenWords.size - 1 - i]
                val oddWord = oddWords[i]
                reconstructedWords.add(evenWord + oddWord)
            }
            val fullyDecryptedText = reconstructedWords.joinToString(" ")
            
            // [Step 4] 대칭 교차 복원을 마친 최종 평문 로깅
            debugLog("findM3u8InHtml: [Step 4] Fully Decrypted Text = $fullyDecryptedText")
            
            val resolvedUrl = findM3u8InDecodedStrings(fullyDecryptedText, "", id)
            
            // [Step 5] 난독화 본문에서 도출된 최종 재생 소스 주소 로깅
            debugLog("findM3u8InHtml: [Step 5] Final Resolved Stream URL = $resolvedUrl")
            if (resolvedUrl != null) {
                return resolvedUrl
            }
        } else {
            debugLog("findM3u8InHtml: Obfuscated variable 'f' was not found in page source.")
        }

        // 2. 일반적인 Packer 형태의 자바스크립트 해독 처리 및 탐색
        var unpackedHtml = html
        val packedRegex = Regex("""eval\s*\(\s*function\s*\(\s*[^)]+\s*\).*?\}\s*\(\s*(['"])(.*?)\1\s*,\s*(\d+)\s*,\s*(\d+)\s*,\s*(['"])(.*?)\5\.split""")
        
        packedRegex.findAll(html).forEach { match ->
            try {
                val p = match.groupValues[2]
                val a = match.groupValues[3].toInt()
                val c = match.groupValues[4].toInt()
                val k = match.groupValues[6].split('|')
                
                var result = p
                for (i in k.indices) {
                    val word = k[i]
                    if (word.isNotEmpty()) {
                        val key = encodeBase(i, a)
                        result = result.replace(Regex("\\b$key\\b"), word)
                    }
                }
                unpackedHtml += "\n$result"
                debugLog("findM3u8InHtml: Unpacked packed script. Unpacked size: ${result.length}")
            } catch (e: Exception) {
                debugLog("findM3u8InHtml: Failed to unpack packed script", e)
            }
        }

        val m3u8Regex = Regex("""(?:https?:)?//[^"'`\s]+\.m3u8[^"'`\s]*""", RegexOption.IGNORE_CASE)
        val match = m3u8Regex.find(unpackedHtml)
        if (match != null) {
            val cleanUrl = match.value.replace("\\/", "/").trim()
            val finalCleanUrl = if (cleanUrl.startsWith("//")) "https:$cleanUrl" else cleanUrl
            debugLog("findM3u8InHtml: Found .m3u8 URL: $finalCleanUrl")
            return finalCleanUrl
        }
        
        // 3. 미디어 링크가 어떠한 형태로도 포착되지 않을 때, 도출해 둔 채널 ID로 주소를 복구하여 생존율 보장
        debugLog("findM3u8InHtml: No .m3u8 detected in decrypted plain text. Launching ultimate reconstruction fallback.")
        val resolvedUrl = findM3u8InDecodedStrings(unpackedHtml, "", id)
        if (resolvedUrl != null) {
            return resolvedUrl
        }
        
        return null
    }

    data class DataResponse(@JsonProperty("server_key") val serverKey: String)
    data class VidembedResponse(
        val success: Boolean,
        val player: String
    )
    data class Bundle(
        @JsonProperty("b_host")
        val bHost: String,
        @JsonProperty("b_rnd")
        val bRnd: String,
        @JsonProperty("b_script")
        val bScript: String,
        @JsonProperty("b_sig")
        val bSig: String,
        @JsonProperty("b_ts")
        val bTs: String
    )
}
