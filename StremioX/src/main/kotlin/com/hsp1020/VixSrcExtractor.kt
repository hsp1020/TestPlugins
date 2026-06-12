package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

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
            println("[STREMIOSUB] Initiating invokeVixSrc")
            
            val tmdbId = loadData.tmdbId?.toString() ?: loadData.imdbId ?: return
            
            val baseUrl = "https://vixsrc.to"
            val isMovie = targetType == "movie" || (globalSeason == null && globalEpisode == null)
            
            val requestUrl = if (isMovie) {
                "$baseUrl/movie/$tmdbId"
            } else {
                "$baseUrl/tv/$tmdbId/$globalSeason/$globalEpisode"
            }

            // 1. 초기 HTML 요청
            val response = StremioC.customSession.get(requestUrl, referer = baseUrl).text
            var playlistUrl: String? = null

            // 추출 전략 1: 전체 URL 매칭
            val directUrlRegex = """(https://vixsrc\.to/playlist/[^"'\s\\]+)""".toRegex()
            playlistUrl = directUrlRegex.find(response)?.groupValues?.get(1)

            // 추출 전략 2: 상대 경로 매칭
            if (playlistUrl == null) {
                val relativeRegex = """(/playlist/[^"'\s\\]+)""".toRegex()
                val relativePath = relativeRegex.find(response)?.groupValues?.get(1)
                if (relativePath != null) {
                    playlistUrl = "$baseUrl$relativePath"
                }
            }

            // 추출 전략 3: 파라미터 개별 추출 (token, expires, id)
            if (playlistUrl == null) {
                val token = """token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(response)?.groupValues?.get(1)
                val expires = """expires["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(response)?.groupValues?.get(1)
                val vId = """id["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(response)?.groupValues?.get(1)
                
                if (token != null && expires != null && vId != null) {
                    playlistUrl = "$baseUrl/playlist/$vId?token=$token&expires=$expires"
                }
            }

            if (playlistUrl != null) {
                // 자바스크립트 문자열 이스케이프 해제
                val cleanPlaylistUrl = playlistUrl
                    .replace("\\u0026", "&")
                    .replace("&amp;", "&")

                println("[STREMIOSUB] VixSrc URL Found: $cleanPlaylistUrl")

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
                println("[STREMIOSUB] VixSrc URL Extraction Failed.")
            }
        } catch (e: Exception) {
            println("[STREMIOSUB] invokeVixSrc Crash: ${e.message}")
        }
    }
}
