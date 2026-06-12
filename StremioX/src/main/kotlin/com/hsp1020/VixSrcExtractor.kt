package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object VixSrcExtractor {

    suspend fun invokeVixSrc(
        loadData: StremioC.LoadData,
        targetType: String,
        globalSeason: Int?,
        globalEpisode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Direct Master Output) <<<")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            val requestUrl = if (isMovie) "$baseUrl/movie/$tmdbId" else "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"

            val commonHeaders = mapOf(
                "Referer" to requestUrl,
                "Origin" to baseUrl,
                "Accept" to "application/json, text/plain, */*",
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
            )

            // 1. API 호출로 Embed URL 및 비디오 ID 획득
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = commonHeaders)
            val apiResponse = apiResponseObj.text.replace("\\/", "/") 
            
            val embedMatch = """(/embed/(\d+)[^"'\s]*)""".toRegex().find(apiResponse)
            val embedPath = embedMatch?.groupValues?.get(1)
            val actualVid = embedMatch?.groupValues?.get(2)
            
            if (embedPath == null || actualVid == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Embed URL extraction failed.")
                return
            }

            val embedUrl = baseUrl + embedPath
            
            // 2. Embed HTML 소스코드 가져오기 및 전처리
            val embedHtml = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = commonHeaders).text
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            
            var masterPlaylistUrl: String? = null

            // 3. window.masterPlaylist 객체 직접 추출
            val masterPlaylistMatch = """window\.masterPlaylist\s*=\s*(\{.*?\})""".toRegex().find(embedHtml)?.groupValues?.get(1)
            
            if (masterPlaylistMatch != null) {
                val token = """["']?token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(masterPlaylistMatch)?.groupValues?.get(1)
                val expires = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex().find(masterPlaylistMatch)?.groupValues?.get(1)
                
                if (token != null && expires != null) {
                    masterPlaylistUrl = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires&h=1&lang=en"
                }
            } else {
                val token = """["']?token["']?\s*[:=]\s*["']([a-fA-F0-9]{32})["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                val expires = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d{10,})["']?""".toRegex().find(embedHtml)?.groupValues?.get(1)
                
                if (token != null && expires != null) {
                    masterPlaylistUrl = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires&h=1&lang=en"
                }
            }

            // 4. 플레이어가 영상과 사운드 트랙을 스스로 믹싱할 수 있도록 마스터 링크 직접 전송!
            if (masterPlaylistUrl != null) {
                println("[STREMIOSUB-VIXSRC] ⚡ SUCCESS! Final Playlist URL: $masterPlaylistUrl")
                
                callback.invoke(
                    newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.headers = commonHeaders
                    }
                )
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed. Could not resolve token pattern.")
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
        }
    }
}
