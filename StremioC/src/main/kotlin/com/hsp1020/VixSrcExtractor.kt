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
    
    // [수정 핵심] [\s\S]*? 를 사용하여 줄바꿈이 있는 다중 라인 JSON 객체도 완벽하게 잡아냅니다.
    private val MASTER_PLAYLIST_REGEX = """window\.masterPlaylist\s*=\s*(\{[\s\S]*?\})""".toRegex()
    
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

            println("[STREMIOSUB-VIXSRC] ℹ️ Media Info: TargetType=$targetType, TMDB/IMDB ID=$tmdbId")

            // 브라우저의 실제 환경에 맞춘 최신 헤더 및 봇 방지 우회 세팅
            val userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/137.0.0.0 Safari/537.36"
            val acceptLanguage = "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7" // [추가] 토큰 해시 일치용 언어 헤더

            // 1. API 호출 전용 헤더
            val apiHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "application/json, text/plain, */*",
                "Accept-Language" to acceptLanguage,
                "User-Agent" to userAgent
            )

            // 2. Embed HTML 호출 전용 헤더
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

            // 3. 비디오 플레이리스트 호출 전용 헤더
            val playerHeaders = mapOf(
                "Referer" to "", // 하단에서 유동 적용
                "Accept" to "*/*",
                "Accept-Language" to acceptLanguage,
                "User-Agent" to userAgent,
                "Sec-Ch-Ua" to """"Chromium";v="137", "Not/A)Brand";v="24"""",
                "Sec-Ch-Ua-Mobile" to "?0",
                "Sec-Ch-Ua-Platform" to """"Linux"""",
                "Sec-Fetch-Dest" to "empty",
                "Sec-Fetch-Mode" to "cors",
                "Sec-Fetch-Site" to "same-origin"
            )

            // 1. API 호출로 Embed URL 획득
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = apiHeaders)
            val apiResponse = apiResponseObj.text.replace("\\/", "/") 
            
            val embedMatch = EMBED_REGEX.find(apiResponse)
            val embedPath = embedMatch?.groupValues?.get(1)
            val actualVid = embedMatch?.groupValues?.get(2)
            
            if (embedPath == null || actualVid == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Step 1 Failed: Embed URL extraction failed.")
                return
            }

            val embedUrl = baseUrl + embedPath
            println("[STREMIOSUB-VIXSRC] 🔗 Step 1 Success: embedUrl = $embedUrl")
            
            // 2. Embed HTML 소스코드 가져오기
            val embedHtmlResponse = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = embedHeaders)
            val embedHtml = embedHtmlResponse.text
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            
            var masterPlaylistUrl: String? = null

            // 3. window.masterPlaylist 객체 직접 추출
            val masterPlaylistMatch = MASTER_PLAYLIST_REGEX.find(embedHtml)?.groupValues?.get(1)
            
            var token: String? = null
            var expires: String? = null
            var bVal: String? = null

            if (masterPlaylistMatch != null) {
                println("[STREMIOSUB-VIXSRC] ⚙️ Found window.masterPlaylist match successfully!")
                token = TOKEN_REGEX_1.find(masterPlaylistMatch)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_1.find(masterPlaylistMatch)?.groupValues?.get(1)
                bVal = B_REGEX.find(masterPlaylistMatch)?.groupValues?.get(1)
            } else {
                println("[STREMIOSUB-VIXSRC] ⚠️ window.masterPlaylist STILL not found! Check HTML structure.")
                token = TOKEN_REGEX_2.find(embedHtml)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_2.find(embedHtml)?.groupValues?.get(1)
                bVal = B_REGEX.find(embedHtml)?.groupValues?.get(1)
            }

            println("[STREMIOSUB-VIXSRC] ⚙️ Extracted tokens: token=$token, expires=$expires")

            if (token != null && expires != null) {
                val bParam = if (bVal != null) "b=$bVal&" else ""
                masterPlaylistUrl = "$baseUrl/playlist/$actualVid?${bParam}token=$token&expires=$expires&h=1&lang=en"
            }

            // 쿠키 파싱
            val cookiesMap = mutableMapOf<String, String>()
            try {
                apiResponseObj.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.forEach { pair ->
                    val parts = pair.second.substringBefore(";").split("=", limit = 2)
                    if (parts.size == 2) cookiesMap[parts[0].trim()] = parts[1].trim()
                }
                embedHtmlResponse.headers.filter { it.first.equals("Set-Cookie", ignoreCase = true) }.forEach { pair ->
                    val parts = pair.second.substringBefore(";").split("=", limit = 2)
                    if (parts.size == 2) cookiesMap[parts[0].trim()] = parts[1].trim()
                }
            } catch (e: Exception) { }

            val cookieHeaderValue = cookiesMap.map { "${it.key}=${it.value}" }.joinToString("; ")

            // 4. 플레이어 통신
            if (masterPlaylistUrl != null) {
                println("[STREMIOSUB-VIXSRC] ⚡ Final Playlist URL: $masterPlaylistUrl")
                
                val finalPlayerHeaders = playerHeaders.toMutableMap().apply {
                    put("Referer", embedUrl)
                    if (cookieHeaderValue.isNotBlank()) put("Cookie", cookieHeaderValue)
                }

                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.headers = finalPlayerHeaders
                    }
                )
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed.")
            }

        } catch (c: CancellationException) {
            throw c
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
