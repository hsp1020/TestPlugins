package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object VixSrcExtractor {

    // 안드로이드 로그캣의 줄바꿈 생략 및 글자수 초과(Truncate) 현상을 막기 위한 대용량 덤프 함수
    private fun logLargeString(tag: String, msg: String) {
        var index = 0
        while (index < msg.length) {
            val end = minOf(index + 3000, msg.length)
            println("$tag [HTML-PART] ${msg.substring(index, end)}")
            index = end
        }
    }

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
            
            // 1. 입력 인자 디버깅
            println("[STREMIOSUB-VIXSRC] Input -> tmdbId: ${loadData.tmdbId}, imdbId: ${loadData.imdbId}, type: $targetType, Season: $globalSeason, Episode: $globalEpisode")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId
            if (tmdbId == null) {
                println("[STREMIOSUB-VIXSRC] Error: TMDB ID and IMDb ID are both null. Aborting.")
                return
            }
            
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            
            val requestUrl = if (isMovie) {
                "$baseUrl/movie/$tmdbId"
            } else {
                "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"
            }

            println("[STREMIOSUB-VIXSRC] Constructed Request URL: $requestUrl")
            println("[STREMIOSUB-VIXSRC] Target Mode: ${if (isMovie) "MOVIE" else "TV/SERIES"}")

            // 2. HTTP 요청 송신
            println("[STREMIOSUB-VIXSRC] Sending GET request to VixSrc...")
            val responseObj = StremioC.customSession.get(
                requestUrl, 
                referer = baseUrl,
                headers = mapOf(
                    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                )
            )

            // 3. HTTP 응답 기본 정보 덤프
            println("[STREMIOSUB-VIXSRC] Response Received!")
            println("[STREMIOSUB-VIXSRC] Response HTTP Status Code: ${responseObj.code}")
            println("[STREMIOSUB-VIXSRC] Response Headers:")
            responseObj.headers.forEach { (name, value) ->
                println("[STREMIOSUB-VIXSRC]   - $name: $value")
            }

            val html = responseObj.text
            println("[STREMIOSUB-VIXSRC] HTML Response Length: ${html.length} chars")

            // 4. Cloudflare 차단 여부 정밀 휴리스틱 판별
            val isCloudflareServer = responseObj.headers["server"]?.contains("cloudflare", ignoreCase = true) == true
            val hasCloudflareIndicators = html.contains("Checking your browser", ignoreCase = true) || 
                                          html.contains("cf-challenge", ignoreCase = true) || 
                                          html.contains("__cf_chl_rt_sig", ignoreCase = true) ||
                                          html.contains("cf_challenge", ignoreCase = true)
            
            if (responseObj.code in 400..503 || isCloudflareServer || hasCloudflareIndicators) {
                println("[STREMIOSUB-VIXSRC] ⚠️ WARNING: Cloudflare / Anti-Bot Security Block Detected!")
                println("[STREMIOSUB-VIXSRC] Detected Markers -> Code: ${responseObj.code}, CF-Server: $isCloudflareServer, CF-HTML-Indicators: $hasCloudflareIndicators")
            } else {
                println("[STREMIOSUB-VIXSRC] Security Check passed cleanly (No obvious Cloudflare blocks).")
            }

            // 5. HTML 전체 구조 덤프 (안전하게 분할 출력)
            println("[STREMIOSUB-VIXSRC] ===== START FULL HTML DUMP =====")
            logLargeString("[STREMIOSUB-VIXSRC]", html)
            println("[STREMIOSUB-VIXSRC] ===== END FULL HTML DUMP =====")

            var playlistUrl: String? = null

            // 6. 정규식 추출 시도 및 과정 로깅
            println("[STREMIOSUB-VIXSRC] Attempting Pattern Match Strategy 1 (Direct Playlist Link)...")
            val directUrlRegex = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex()
            val match1 = directUrlRegex.find(html)?.groupValues?.get(1)
            println("[STREMIOSUB-VIXSRC] Strategy 1 Match Result: ${match1 ?: "NONE"}")
            
            if (match1 != null) {
                playlistUrl = match1
            } else {
                println("[STREMIOSUB-VIXSRC] Attempting Pattern Match Strategy 2 (Relative Playlist Link)...")
                val relativeRegex = """(/playlist/[^"'\s\\]+)""".toRegex()
                val match2 = relativeRegex.find(html)?.groupValues?.get(1)
                println("[STREMIOSUB-VIXSRC] Strategy 2 Match Result: ${match2 ?: "NONE"}")
                
                if (match2 != null) {
                    playlistUrl = "$baseUrl$match2"
                } else {
                    println("[STREMIOSUB-VIXSRC] Attempting Pattern Match Strategy 3 (Individual Param Construct)...")
                    val token = """token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(html)?.groupValues?.get(1)
                    val expires = """expires["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(html)?.groupValues?.get(1)
                    val vId = """id["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(html)?.groupValues?.get(1)
                    println("[STREMIOSUB-VIXSRC] Strategy 3 Raw Values -> token: ${token ?: "NULL"}, expires: ${expires ?: "NULL"}, id: ${vId ?: "NULL"}")
                    
                    if (token != null && expires != null && vId != null) {
                        playlistUrl = "$baseUrl/playlist/$vId?token=$token&expires=$expires"
                        println("[STREMIOSUB-VIXSRC] Strategy 3 Match Result: $playlistUrl")
                    } else {
                        println("[STREMIOSUB-VIXSRC] Strategy 3 Match Result: NONE")
                    }
                }
            }

            // 7. 최종 처리 및 추출 성공 여부 판단
            if (playlistUrl != null) {
                val cleanPlaylistUrl = playlistUrl
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")
                println("[STREMIOSUB-VIXSRC] Success! Final Cleaned Playlist URL: $cleanPlaylistUrl")

                callback.invoke(
                    newExtractorLink(
                        name = "VixSrc",
                        source = "VixSrc",
                        url = cleanPlaylistUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P1080.value
                        this.headers = mapOf(
                            "Referer" to requestUrl,
                            "Origin" to baseUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
            } else {
                println("[STREMIOSUB-VIXSRC] Extraction Failed. No matching pattern found.")
            }

            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() END <<<")
        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] invokeVixSrc Cancelled (Coroutine cancellation).")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] invokeVixSrc Exception Crash: ${e.message}")
            e.printStackTrace()
        }
    }
}
