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
    
    // (?s) 플래그를 사용하여 줄바꿈 포함 매칭을 더 빠르고 코틀린스럽게 최적화
    private val MASTER_PLAYLIST_REGEX = """(?s)window\.masterPlaylist\s*=\s*(\{.*?\})""".toRegex()
    
    private val TOKEN_REGEX_1 = """["']?token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex()
    private val EXPIRY_REGEX_1 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()
    private val TOKEN_REGEX_2 = """["']?token["']?\s*[:=]\s*["']([a-fA-F0-9]{32})["']""".toRegex()
    private val EXPIRY_REGEX_2 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d{10,})["']?""".toRegex()
    private val B_REGEX = """["']?b["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()

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
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

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
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = apiHeaders)
            
            // JSON 응답의 이스케이프된 슬래시(\/)를 먼저 원래대로 복구해야 정규식이 정상 작동합니다.
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

            // 거대한 HTML 텍스트 전체를 치환하지 않고 정규식을 돌려 메모리(RAM) 낭비 차단
            println("[STREMIOSUB-VIXSRC] ⚙️ Step 3: Parsing Tokens from HTML...")
            val masterPlaylistMatch = MASTER_PLAYLIST_REGEX.find(rawHtml)?.groupValues?.get(1)

            if (masterPlaylistMatch != null) {
                println("[STREMIOSUB-VIXSRC] ⚙️ Found window.masterPlaylist match successfully!")
                // 잘라낸 작은 텍스트에만 필터링 적용
                val cleanMatch = masterPlaylistMatch.replace("\\u0026", "&").replace("\\/", "/")
                token = TOKEN_REGEX_1.find(cleanMatch)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_1.find(cleanMatch)?.groupValues?.get(1)
                bVal = B_REGEX.find(cleanMatch)?.groupValues?.get(1)
            } else {
                println("[STREMIOSUB-VIXSRC] ⚠️ window.masterPlaylist NOT found. Falling back to direct HTML search.")
                // Fallback 시에만 HTML 전체를 뒤지도록 구성
                val cleanHtml = rawHtml.replace("\\u0026", "&").replace("\\/", "/")
                token = TOKEN_REGEX_2.find(cleanHtml)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_2.find(cleanHtml)?.groupValues?.get(1)
                bVal = B_REGEX.find(cleanHtml)?.groupValues?.get(1)
            }

            if (token == null || expires == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Step 3 Failed: Token or Expires is null.")
                return 
            }

            val bParam = if (bVal != null) "b=$bVal&" else ""
            val masterPlaylistUrl = "$baseUrl/playlist/$actualVid?${bParam}token=$token&expires=$expires&h=1&lang=en"

            // === [STEP 3] 초고속 쿠키 파싱 (타입 에러 방지 및 메모리 할당 없는 인덱스 기반 파싱) ===
            val cookiesMap = mutableMapOf<String, String>()
            
            try {
                apiResponseObj.headers.forEach { pair ->
                    if (pair.first.equals("Set-Cookie", ignoreCase = true)) {
                        val value = pair.second
                        val semiColonIndex = value.indexOf(';')
                        val cookiePart = if (semiColonIndex > 0) value.substring(0, semiColonIndex) else value
                        val equalsIndex = cookiePart.indexOf('=')
                        if (equalsIndex > 0) {
                            cookiesMap[cookiePart.substring(0, equalsIndex).trim()] = cookiePart.substring(equalsIndex + 1).trim()
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
                            cookiesMap[cookiePart.substring(0, equalsIndex).trim()] = cookiePart.substring(equalsIndex + 1).trim()
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

            // === [STEP 4] 플레이어 전송 및 개별 해상도 파싱 ===
            println("[STREMIOSUB-VIXSRC] ⚡ Final Playlist URL: $masterPlaylistUrl")
            
            val playerHeaders = mapOf(
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
            ).toMutableMap().apply {
                if (cookieHeaderValue.isNotEmpty()) put("Cookie", cookieHeaderValue)
            }

            // 1. 기존 로직 유지: 오디오가 온전히 포함된 'Auto' 마스터 링크를 최우선 송출
            callback.invoke(
                newExtractorLink("VixSrc", "VixSrc Auto", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value // Auto 화질은 Unknown으로 설정하여 앱 UI에서 자동(Auto)으로 인식되게 함
                    this.headers = playerHeaders
                }
            )

            // 2. 추가 로직: 마스터 플레이리스트 내부를 긁어와 해상도별 개별 링크 추출
            try {
                println("[STREMIOSUB-VIXSRC] 📡 Fetching master playlist to extract specific resolutions...")
                val m3u8Response = StremioC.customSession.get(masterPlaylistUrl, headers = playerHeaders)
                
                if (m3u8Response.code == 200) {
                    val m3u8Text = m3u8Response.text
                    // M3U8 구조에서 해상도 라인(RESOLUTION=1920x1080)과 그 바로 다음 줄(URL)을 추출하는 정규식
                    val resolutionRegex = """RESOLUTION=\d+x(\d+)[^\n]*\r?\n([^\r\n]+)""".toRegex()
                    
                    resolutionRegex.findAll(m3u8Text).forEach { match ->
                        val height = match.groupValues[1].toIntOrNull() ?: return@forEach
                        val urlLine = match.groupValues[2].trim()
                        
                        // URL이 상대경로 파라미터(?type=video...)로 시작할 경우 절대 경로로 조립
                        val directUrl = if (urlLine.startsWith("http")) {
                            urlLine
                        } else if (urlLine.startsWith("?")) {
                            "$baseUrl/playlist/$actualVid$urlLine"
                        } else if (urlLine.startsWith("/")) {
                            "$baseUrl$urlLine"
                        } else {
                            urlLine
                        }

                        // Cloudstream 규격에 맞게 해상도 Enum 값 매핑
                        val qualityValue = when (height) {
                            1080 -> Qualities.P1080.value
                            720 -> Qualities.P720.value
                            480 -> Qualities.P480.value
                            360 -> Qualities.P360.value
                            else -> height
                        }

                        // 각 해상도별 링크 송출
                        callback.invoke(
                            newExtractorLink("VixSrc", "VixSrc ${height}p", directUrl, ExtractorLinkType.M3U8) {
                                this.quality = qualityValue
                                this.headers = playerHeaders
                            }
                        )
                    }
                    println("[STREMIOSUB-VIXSRC] ✅ Specific resolutions successfully extracted.")
                }
            } catch (e: Exception) {
                // 개별 링크 추출에 실패하더라도 메인 링크(Auto)가 이미 전송되었으므로 재생 자체에는 문제 없음
                println("[STREMIOSUB-VIXSRC] ⚠️ Failed to extract specific resolutions: ${e.message}")
            }

        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
            e.printStackTrace()
        }
    }
}
