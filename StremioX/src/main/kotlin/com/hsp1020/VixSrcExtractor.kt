package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
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
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Bulletproof API Track) <<<")
            
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

            // ====================================================================
            // ⚡ 순수 API 타격 (웹뷰 없음, 0.1초 컷)
            // ====================================================================
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = commonHeaders)
            val apiResponse = apiResponseObj.text.replace("\\/", "/") 
            
            // 1. API 응답에서 Embed URL과 실제 영상 고유 ID(actualVid) 추출
            val embedMatch = """(/embed/(\d+)[^"'\s]*)""".toRegex().find(apiResponse)
            val embedPath = embedMatch?.groupValues?.get(1)
            val actualVid = embedMatch?.groupValues?.get(2)
            
            if (embedPath == null || actualVid == null) {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed: Embed URL or Video ID not found in API response.")
                return
            }

            val embedUrl = baseUrl + embedPath
            println("[STREMIOSUB-VIXSRC] ⚡ Fetched Embed URL: $embedUrl")
            
            // 2. Embed HTML을 가져온 뒤 이스케이프 문자(\u0026 등) 전처리
            val embedHtml = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = commonHeaders).text
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            
            var masterPlaylistUrl: String? = null

            // 3. [그물망 정규식] wrapper 단어 유무, 절대경로, 상대경로 상관없이 무조건 플레이리스트 캡처
            var rawPlaylist = """(https://vixsrc\.to/playlist/[^"'\s<>]+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
            if (rawPlaylist == null) {
                val relativePlaylist = """(/playlist/[^"'\s<>]+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
                if (relativePlaylist != null) rawPlaylist = baseUrl + relativePlaylist
            }

            // 만약 URL 구조로 못 찾으면 변수에서 강제 추출 (최후의 보루)
            if (rawPlaylist == null) {
                val token = """token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                val expires = """expires["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                if (token != null && expires != null) {
                    rawPlaylist = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires"
                }
            }

            if (rawPlaylist != null) {
                // 자바스크립트가 하던 짓(치환 및 필수 꼬리표 붙이기)을 코틀린으로 보정
                var finalUrl = rawPlaylist.replace("wrapper", actualVid)
                if (!finalUrl.contains("h=1")) finalUrl += "&h=1"
                if (!finalUrl.contains("lang=en")) finalUrl += "&lang=en"
                
                masterPlaylistUrl = finalUrl
                println("[STREMIOSUB-VIXSRC] ⚡ SUCCESS! Constructed Final Playlist: $masterPlaylistUrl")
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed. Could not find any playlist patterns.")
                println("[STREMIOSUB-VIXSRC] 📝 Dump Preview: ${embedHtml.take(300)}")
            }

            // ====================================================================
            // 🎬 M3U8 화질 필터링 및 플레이어 전송
            // ====================================================================
            if (masterPlaylistUrl != null) {
                try {
                    println("[STREMIOSUB-VIXSRC] 🎬 Parsing M3U8 for 1080p Filtering...")
                    val m3u8Links = M3u8Helper.generateM3u8("VixSrc", masterPlaylistUrl, requestUrl, headers = commonHeaders)
                    
                    if (m3u8Links.isNotEmpty()) {
                        val sortedLinks = m3u8Links.sortedByDescending { it.quality }
                        val highQualityLinks = sortedLinks.filter { it.quality >= Qualities.P1080.value }
                        
                        // 1080p 이상만, 없으면 최대 해상도 1개만
                        val finalLinks = highQualityLinks.ifEmpty { 
                            val maxQuality = sortedLinks.first().quality
                            sortedLinks.filter { it.quality == maxQuality }
                        }
                        
                        finalLinks.forEach { link ->
                            println("[STREMIOSUB-VIXSRC] 🎬 Added Filtered Track: Quality ${link.quality} -> ${link.url}")
                            callback.invoke(link)
                        }
                    } else {
                        throw Exception("M3u8Helper returned empty list.")
                    }
                } catch (e: Exception) {
                    println("[STREMIOSUB-VIXSRC] M3u8Helper failed, safely falling back to Master M3U8: ${e.message}")
                    callback.invoke(
                        newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = commonHeaders
                        }
                    )
                }
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
        }
    }
}
