package com.kktv

import android.net.Uri
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

class CUTVProvider : MainAPI() {
    override var mainUrl = "https://cu-tv.com"
    override var name = "CUTV"
    override val supportedTypes = setOf(TvType.Live)
    override var lang = "ko"
    override val hasMainPage = true

    override val mainPage = mainPageOf(
        "/main/index.php" to "실시간 스포츠 중계"
    )

    private val userAgent = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"

    private val defaultHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7",
        "Upgrade-Insecure-Requests" to "1"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = "$mainUrl${request.data}"
        val items = mutableListOf<SearchResponse>()
        
        try {
            val doc = app.get(url, headers = defaultHeaders).document
            
            val iframeSrc = doc.select("iframe").mapNotNull { it.attr("src") }
                .firstOrNull { it.contains("galaxy-stream") || it.contains("hudtv") || it.contains("cli") }

            if (iframeSrc.isNullOrBlank()) {
                println("[CUTV_DEBUG] FAIL: iframeSrc를 찾을 수 없습니다.")
                return newHomePageResponse(request.name, emptyList())
            }

            val iframeHtml = app.get(iframeSrc, headers = defaultHeaders, referer = url).text
            
            // 디버깅 덤프 출력
            println("[CUTV_DEBUG] === START CUTV 팩트 체크 디버깅 ===")
            println("[CUTV_DEBUG] 접속 메인 URL: $url")
            println("[CUTV_DEBUG] 추출된 iframe 주소: $iframeSrc")
            println("[CUTV_DEBUG] 메인 HTML 크기: ${doc.html().length}자")
            println("[CUTV_DEBUG] iframe HTML 크기: ${iframeHtml.length}자")

            // 1. iframeHtml 내에서 'oncahot' 근처의 문자열 구조를 확인용 덤프
            val indexOfOncahot = iframeHtml.indexOf("oncahot", ignoreCase = true)
            if (indexOfOncahot != -1) {
                val start = maxOf(0, indexOfOncahot - 100)
                val end = minOf(iframeHtml.length, indexOfOncahot + 100)
                val context = iframeHtml.substring(start, end)
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] iframe 내 'oncahot' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
            } else {
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] iframe 내부에 'oncahot' 문자열 자체가 존재하지 않습니다.")
            }

            // 2. doc.html (메인페이지) 내의 'thumbs' 주변 문자열 확인용 덤프
            val indexOfThumbsDoc = doc.html().indexOf("thumbs", ignoreCase = true)
            if (indexOfThumbsDoc != -1) {
                val start = maxOf(0, indexOfThumbsDoc - 100)
                val end = minOf(doc.html().length, indexOfThumbsDoc + 100)
                val context = doc.html().substring(start, end)
                println("[CUTV_DEBUG] 🔍 [FACT DUMP] 메인 HTML 내 'thumbs' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
            }

            // 썸네일 베이스 URL 동적 추출
            var dynamicThumbBase = "" 
            
            // [방안 1] 순수 도메인 부분만 도려내어 thumbs를 구성하는 정규식
            val hostRegex = """([a-zA-Z0-9.-]*oncahot\.com)""".toRegex(RegexOption.IGNORE_CASE)
            val hostMatch = hostRegex.find(iframeHtml)
            
            if (hostMatch != null) {
                val cleanDomain = hostMatch.groupValues[1]
                dynamicThumbBase = "https://$cleanDomain/thumbs"
                println("[CUTV_DEBUG] SUCCESS: [방안1] 도메인 다이렉트 매칭 성공 -> $dynamicThumbBase")
            } else {
                println("[CUTV_DEBUG] INFO: [방안1] 매칭 실패. [방안2] 자바스크립트 파일 추적으로 진입합니다.")
                
                // [방안 2] script.bootstrap.js 파싱 추적
                val iframeDoc = Jsoup.parse(iframeHtml)
                val scriptSrc = iframeDoc.select("script").map { it.attr("src") }
                    .firstOrNull { it.contains("script.bootstrap.js") }

                println("[CUTV_DEBUG] 찾은 script.bootstrap.js 경로: $scriptSrc")

                if (!scriptSrc.isNullOrBlank()) {
                    val iframeUri = Uri.parse(iframeSrc)
                    val scriptUrl = if (scriptSrc.startsWith("http")) {
                        scriptSrc
                    } else {
                        val cleanPath = scriptSrc.replace("../", "")
                        "${iframeUri.scheme}://${iframeUri.host}/$cleanPath"
                    }
                    println("[CUTV_DEBUG] 타겟 스크립트 절대경로: $scriptUrl")
                    
                    try {
                        val scriptHtml = app.get(scriptUrl, headers = defaultHeaders, referer = iframeSrc).text
                        println("[CUTV_DEBUG] 스크립트 내용 수신 완료. 크기: ${scriptHtml.length}자")

                        // 스크립트 내부 thumbs 키워드 위치 추적
                        val indexOfThumbsScript = scriptHtml.indexOf("thumbs", ignoreCase = true)
                        if (indexOfThumbsScript != -1) {
                            val start = maxOf(0, indexOfThumbsScript - 100)
                            val end = minOf(scriptHtml.length, indexOfThumbsScript + 100)
                            val context = scriptHtml.substring(start, end)
                            println("[CUTV_DEBUG] 🔍 [FACT DUMP] 스크립트 내 'thumbs' 주변 200자 원본 데이터:\n[DUMP_START]\n$context\n[DUMP_END]")
                        }

                        // 역슬래시 이스케이프(\/)까지 완전 무시하고 프로토콜과 도메인, thumbs 서브디렉토리를 통째로 집어내는 정규식
                        val fallbackRegex = """(https?(?::|\\:)(?:/|\\/)+[a-zA-Z0-9.-]+(?:/|\\/)+thumbs)""".toRegex(RegexOption.IGNORE_CASE)
                        val scriptMatch = fallbackRegex.find(scriptHtml)
                        if (scriptMatch != null) {
                            dynamicThumbBase = scriptMatch.groupValues[1].replace("\\", "")
                            println("[CUTV_DEBUG] SUCCESS: [방안2] 스크립트 내부에서 완전한 주소 추출 성공 -> $dynamicThumbBase")
                        } else {
                            println("[CUTV_DEBUG] FAIL: [방안2] 스크립트는 로드했으나 패턴 매칭 실패")
                        }
                    } catch (e: Exception) {
                        println("[CUTV_DEBUG] FAIL: [방안2] script.bootstrap.js 네트워크 요청 실패: ${e.message}")
                    }
                } else {
                    println("[CUTV_DEBUG] FAIL: iframe 내부에서 script.bootstrap.js 태그 자체를 찾을 수 없음")
                }
            }

            if (dynamicThumbBase.isBlank()) {
                println("[CUTV_DEBUG] WARNING: 모든 동적 추출 알고리즘이 최종 실패했습니다. 빈 주소가 부여됩니다.")
            }
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                matches.forEach { matchItem ->
                    val ssid = matchItem["ssid"] ?: ""
                    val name = matchItem["name"] ?: ""
                    
                    val surl1 = matchItem["surl"] ?: ""
                    val surl2 = matchItem["surl2"] ?: ""
                    val surl3 = matchItem["surl3"] ?: ""
                    
                    if (ssid.isNotBlank() && name.isNotBlank() && (surl1.isNotBlank() || surl2.isNotBlank() || surl3.isNotBlank())) {
                        
                        val title = name 
                        val srcy = matchItem["srcy"] ?: ""
                        val thumbId = if (srcy.isNotBlank()) srcy else ssid
                        val timestamp = System.currentTimeMillis()
                        
                        val posterUrl = if (dynamicThumbBase.isNotBlank()) "$dynamicThumbBase/$thumbId.jpg?a=$timestamp" else ""
                        
                        val encodedTitle = URLEncoder.encode(title, "UTF-8")
                        val encodedIframe = URLEncoder.encode(iframeSrc, "UTF-8")
                        val encodedThumbBase = URLEncoder.encode(dynamicThumbBase, "UTF-8")
                        
                        val fakeUrl = "$mainUrl/live_stream/$ssid?title=$encodedTitle&iframe=$encodedIframe&thumbBase=$encodedThumbBase"

                        items.add(
                            newLiveSearchResponse(name = title, url = fakeUrl) {
                                this.posterUrl = posterUrl
                                this.posterHeaders = mapOf(
                                    "Referer" to iframeSrc,
                                    "User-Agent" to userAgent,
                                    "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                                    "Sec-Fetch-Dest" to "image",
                                    "Sec-Fetch-Mode" to "no-cors",
                                    "Sec-Fetch-Site" to "cross-site"
                                )
                            }
                        )
                    }
                }
            } else {
                println("[CUTV_DEBUG] FAIL: window.galaxystream.data2 데이터를 찾지 못했습니다.")
            }
        } catch (e: Exception) {
            println("[CUTV_DEBUG] ERROR 발생: ${e.message}")
            e.printStackTrace()
        }

        return newHomePageResponse(
            list = listOf(HomePageList(request.name, items, isHorizontalImages = true)),
            hasNext = false
        )
    }

    override suspend fun load(url: String): LoadResponse {
        val uri = Uri.parse(url)
        val rawTitle = uri.getQueryParameter("title")
        val title = if (!rawTitle.isNullOrBlank()) URLDecoder.decode(rawTitle, "UTF-8") else "Live Stream"
        
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else "https://sol13.galaxy-stream.live/cli/box"

        val rawThumbBase = uri.getQueryParameter("thumbBase")
        val thumbBase = if (!rawThumbBase.isNullOrBlank()) URLDecoder.decode(rawThumbBase, "UTF-8") else ""

        println("[CUTV_DEBUG] load() 진입 -> 전달받은 thumbBase: $thumbBase")

        val ssid = url.substringBefore("?").substringAfterLast("/")
        val posterUrl = if (thumbBase.isNotBlank()) "$thumbBase/$ssid.jpg?a=${System.currentTimeMillis()}" else ""

        return newLiveStreamLoadResponse(
            name = title,
            url = url,
            dataUrl = url
        ) {
            this.posterUrl = posterUrl
            this.posterHeaders = mapOf(
                "Referer" to iframeUrl,
                "User-Agent" to userAgent,
                "Accept" to "image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8",
                "Sec-Fetch-Dest" to "image",
                "Sec-Fetch-Mode" to "no-cors",
                "Sec-Fetch-Site" to "cross-site"
            )
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val uri = Uri.parse(data)
        val ssid = data.substringBefore("?").substringAfterLast("/")
        val rawIframe = uri.getQueryParameter("iframe")
        val iframeUrl = if (!rawIframe.isNullOrBlank()) URLDecoder.decode(rawIframe, "UTF-8") else return false

        try {
            val iframeHtml = app.get(iframeUrl, headers = defaultHeaders, referer = "$mainUrl/main/index.php").text
            
            val jsonRegex = """window\.galaxystream\.data2\s*=\s*'(.*?)';""".toRegex()
            val matchResult = jsonRegex.find(iframeHtml)
            
            if (matchResult != null) {
                val jsonData = matchResult.groupValues[1]
                val matches = AppUtils.parseJson<List<Map<String, String>>>(jsonData)
                
                val targetMatch = matches.find { it["ssid"] == ssid }
                
                if (targetMatch != null) {
                    val urls = listOfNotNull(targetMatch["surl"], targetMatch["surl2"], targetMatch["surl3"])
                        .filter { it.isNotBlank() }
                        .distinct()

                    urls.forEachIndexed { index, link ->
                        var actualUrl = link.replace("\\/", "/") 
                        
                        var targetReferer = iframeUrl
                        var targetOrigin = "https://${Uri.parse(iframeUrl).host}"
                        
                        if (actualUrl.startsWith("EMBED#")) {
                            actualUrl = actualUrl.removePrefix("EMBED#")
                        }
                        
                        if (actualUrl.contains("liveUrl=")) {
                            targetReferer = actualUrl 
                            try {
                                targetOrigin = "https://${Uri.parse(actualUrl).host}" 
                                Uri.parse(actualUrl).getQueryParameter("liveUrl")?.let { extractedUrl ->
                                    actualUrl = extractedUrl 
                                }
                            } catch (e: Exception) {}
                        }

                        val qualityName = if (link.contains("coupangstreaming")) "채널 1" 
                                          else if (link.contains("hudtv")) "채널 2" 
                                          else "채널 ${index + 1}"

                        val isM3u8 = actualUrl.contains(".m3u8", ignoreCase = true)
                        val linkType = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO

                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = qualityName,
                                url = actualUrl,
                                type = linkType
                            ) {
                                this.referer = targetReferer
                                this.headers = mapOf(
                                    "Origin" to targetOrigin,
                                    "Accept" to "*/*",
                                    "Sec-Fetch-Dest" to "empty",
                                    "Sec-Fetch-Mode" to "cors",
                                    "Sec-Fetch-Site" to "cross-site",
                                    "User-Agent" to userAgent
                                )
                            }
                        )
                    }
                    return true
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }

        return true
    }
}
