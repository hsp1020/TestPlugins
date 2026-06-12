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
            println("[STREMIOSUB-VIXSRC] >>> invokeVixSrc() START (Pure API Fast-Track) <<<")
            
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
            
            // 3. VixSrc의 자바스크립트가 사용하는 진짜 템플릿(wrapper) URL만 핀셋으로 추출 (가짜 ub=1 함정 회피)
            val wrapperMatch = """(https://vixsrc\.to/playlist/wrapper\?[^"'\s]+)""".toRegex().find(embedHtml)
            var masterPlaylistUrl: String? = null

            if (wrapperMatch != null) {
                // 자바스크립트가 하던 짓(치환 및 꼬리표 붙이기)을 코틀린으로 구현
                var finalUrl = wrapperMatch.groupValues[1].replace("wrapper", actualVid)
                if (!finalUrl.contains("h=1")) finalUrl += "&h=1"
                if (!finalUrl.contains("lang=en")) finalUrl += "&lang=en"
                
                masterPlaylistUrl = finalUrl
                println("[STREMIOSUB-VIXSRC] ⚡ SUCCESS! Constructed Final Playlist: $masterPlaylistUrl")
            } else {
                // 만약 wrapper 문자가 바뀌었을 경우를 대비한 최후의 안전장치
                val token = """token=([a-fA-F0-9]{32})""".toRegex().find(embedHtml)?.groupValues?.get(1)
                val expires = """expires=(\d+)""".toRegex().find(embedHtml)?.groupValues?.get(1)
                if (token != null && expires != null) {
                    masterPlaylistUrl = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires&h=1&lang=en"
                    println("[STREMIOSUB-VIXSRC] ⚡ SUCCESS (Fallback Constructor): $masterPlaylistUrl")
                }
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
                        throw Exception("M3u8Helper parsing returned empty list.")
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
            } else {
                println("[STREMIOSUB-VIXSRC] ❌ Extraction Failed. Could not find token pattern in Embed HTML.")
            }

        } catch (c: CancellationException) {
            println("[STREMIOSUB-VIXSRC] Cancelled by User.")
            throw c
        } catch (e: Exception) {
            println("[STREMIOSUB-VIXSRC] Crash: ${e.message}")
        }
    }
}
