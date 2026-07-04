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
    private val MASTER_PLAYLIST_REGEX = """window\.masterPlaylist\s*=\s*(\{.*?\})""".toRegex()
    private val TOKEN_REGEX_1 = """["']?token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex()
    private val EXPIRY_REGEX_1 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex()
    private val TOKEN_REGEX_2 = """["']?token["']?\s*[:=]\s*["']([a-fA-F0-9]{32})["']""".toRegex()
    private val EXPIRY_REGEX_2 = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d{10,})["']?""".toRegex()
    
    // b 파라미터를 파싱하기 위한 정규식
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

            println("[STREMIOSUB-VIXSRC] ℹ️ Media Info: TargetType=$targetType, TMDB/IMDB ID=$tmdbId, isMovie=$isMovie")
            println("[STREMIOSUB-VIXSRC] ℹ️ Parent Page Request URL: $requestUrl")

            // 브라우저 환경과 일관되도록 헤더 세트를 용도별로 독립 선언합니다.
            val userAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Safari/537.36"

            // 1. API 호출 전용 헤더 (JSON 수신)
            val apiHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to userAgent
            )

            // 2. Embed HTML 소스코드 호출 전용 헤더 (HTML 문서 수신용, Origin 제거)
            val embedHeaders = mapOf(
                "Referer" to requestUrl,
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8",
                "User-Agent" to userAgent
            )

            // 3. 비디오 플레이리스트 호출 전용 헤더 프로필 (미디어 수신용, Accept */* 및 Origin/JSON 성격 완전 배제)
            val playerHeaders = mapOf(
                "Referer" to "", // 하단에서 embedUrl로 동적 적용됩니다.
                "Accept" to "*/*",
                "User-Agent" to userAgent
            )

            // 1. API 호출로 Embed URL 및 비디오 ID 획득
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            println("[STREMIOSUB-VIXSRC] 🛰️ Step 1: Requesting Embed API -> $apiUrl")
            
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = apiHeaders)
            val apiResponse = apiResponseObj.text.replace("\\/", "/") 
            println("[STREMIOSUB-VIXSRC] 🛰️ Step 1: API Response Received (Length: ${apiResponse.length})")
            
            val embedMatch = EMBED_REGEX.find(apiResponse)
            val embedPath = embedMatch?.groupValues?.get(1)
            val actualVid = embedMatch?.groupValues?.get(2)
            
            if (embedPath == null || actualVid == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Step 1 Failed: Embed URL extraction failed. Raw API Response: $apiResponse")
                return
            }

            val embedUrl = baseUrl + embedPath
            println("[STREMIOSUB-VIXSRC] 🔗 Step 1 Success: embedUrl = $embedUrl, actualVid = $actualVid")
            
            // 2. Embed HTML 소스코드 가져오기 및 전처리 (독립된 HTML 문서 헤더 사용)
            println("[STREMIOSUB-VIXSRC] 🛰️ Step 2: Requesting Embed HTML...")
            val embedHtmlResponse = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = embedHeaders)
            val embedHtml = embedHtmlResponse.text
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            
            println("[STREMIOSUB-VIXSRC] 🛰️ Step 2: Embed HTML Received (Length: ${embedHtml.length}, HTTP Code: ${embedHtmlResponse.code})")
            
            var masterPlaylistUrl: String? = null

            // 3. window.masterPlaylist 객체 직접 추출 및 b값 동적 매칭
            println("[STREMIOSUB-VIXSRC] ⚙️ Step 3: Parsing Tokens from HTML...")
            val masterPlaylistMatch = MASTER_PLAYLIST_REGEX.find(embedHtml)?.groupValues?.get(1)
            
            var token: String? = null
            var expires: String? = null
            var bVal: String? = null

            if (masterPlaylistMatch != null) {
                println("[STREMIOSUB-VIXSRC] ⚙️ Found window.masterPlaylist match.")
                token = TOKEN_REGEX_1.find(masterPlaylistMatch)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_1.find(masterPlaylistMatch)?.groupValues?.get(1)
                bVal = B_REGEX.find(masterPlaylistMatch)?.groupValues?.get(1)
            } else {
                println("[STREMIOSUB-VIXSRC] ⚠️ window.masterPlaylist NOT found. Falling back to direct HTML search.")
                token = TOKEN_REGEX_2.find(embedHtml)?.groupValues?.get(1)
                expires = EXPIRY_REGEX_2.find(embedHtml)?.groupValues?.get(1)
                bVal = B_REGEX.find(embedHtml)?.groupValues?.get(1)
            }

            println("[STREMIOSUB-VIXSRC] ⚙️ Parsed parameters: token=$token, expires=$expires, bVal=$bVal")

            if (token != null && expires != null) {
                val bParam = if (bVal != null) "b=$bVal&" else ""
                masterPlaylistUrl = "$baseUrl/playlist/$actualVid?${bParam}token=$token&expires=$expires&h=1&lang=en"
            }

            // 4. 플레이어 통신 헤더 확정 및 사전 검증 테스트 수행
            if (masterPlaylistUrl != null) {
                println("[STREMIOSUB-VIXSRC] ⚡ Final Playlist URL generated: $masterPlaylistUrl")
                
                val finalPlayerHeaders = playerHeaders.toMutableMap().apply {
                    put("Referer", embedUrl)
                }

                // [디버깅 추가] 실제 앱 플레이어가 호출하기 전, OkHttp 세션 상에서 사전 검증 요청을 날려 로그를 확보합니다.
                println("[STREMIOSUB-VIXSRC] 🔬 Step 4: Simulating pre-verification request to playlist URL...")
                try {
                    val testResponse = StremioC.customSession.get(masterPlaylistUrl, headers = finalPlayerHeaders)
                    println("[STREMIOSUB-VIXSRC] 🔬 Pre-verification Response Code: ${testResponse.code}")
                    if (testResponse.code != 200) {
                        println("[STREMIOSUB-VIXSRC] ⚠️ Warning: Pre-verification failed with Code ${testResponse.code}!")
                        println("[STREMIOSUB-VIXSRC] ⚠️ Response Content Snippet: ${testResponse.text.take(400)}")
                    } else {
                        println("[STREMIOSUB-VIXSRC] ✅ Pre-verification Successful (200 OK)!")
                    }
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] ⚠️ Pre-verification request threw an exception: ${e.message}")
                    e.printStackTrace()
                }

                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.headers = finalPlayerHeaders
                    }
                )
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed. Could not resolve token pattern from Embed HTML.")
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
            e.printStackTrace()
        }
    }
}
