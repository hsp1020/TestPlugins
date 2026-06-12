package com.hsp1020

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.newExtractorLink
import kotlinx.coroutines.CancellationException

object VixSrcExtractor {

    // 안드로이드 로그캣 잘림 방지용 청크 분할 출력 함수
    private fun logLargeString(tag: String, msg: String) {
        if (msg.isBlank()) {
            println("$tag [DEBUG] Body is empty.")
            return
        }
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
            println("[VIXSRC-DEBUG] >>> invokeVixSrc() START <<<")
            
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
            // ⚡ STEP 1: API Request
            // ====================================================================
            val apiUrl = if (isMovie) "$baseUrl/api/movie/$tmdbId" else "$baseUrl/api/tv/$tmdbId/$globalSeason/$globalEpisode"
            println("[VIXSRC-DEBUG] Step 1 -> Sending API Request to: $apiUrl")

            val apiResponseObj = StremioC.customSession.get(apiUrl, headers = commonHeaders)
            println("[VIXSRC-DEBUG] Step 1 -> API Response Code: ${apiResponseObj.code}")
            
            val apiResponse = apiResponseObj.text.replace("\\/", "/") 
            println("[VIXSRC-DEBUG] Step 1 -> API Response Content:")
            logLargeString("[VIXSRC-DEBUG-API-BODY]", apiResponse)
            
            val embedMatch = """(/embed/(\d+)[^"'\s]*)""".toRegex().find(apiResponse)
            val embedPath = embedMatch?.groupValues?.get(1)
            val actualVid = embedMatch?.groupValues?.get(2)
            
            println("[VIXSRC-DEBUG] Step 1 -> Extracted embedPath: $embedPath")
            println("[VIXSRC-DEBUG] Step 1 -> Extracted actualVid: $actualVid")

            if (embedPath == null || actualVid == null) {
                println("[VIXSRC-DEBUG] ❌ Step 1 Failure: embedPath or actualVid is null. Aborting.")
                return
            }

            // ====================================================================
            // ⚡ STEP 2: Embed Page Request
            // ====================================================================
            val embedUrl = baseUrl + embedPath
            println("[VIXSRC-DEBUG] Step 2 -> Sending Embed Request to: $embedUrl")
            
            val embedResponseObj = StremioC.customSession.get(embedUrl.replace("\\u0026", "&"), headers = commonHeaders)
            println("[VIXSRC-DEBUG] Step 2 -> Embed Response Code: ${embedResponseObj.code}")
            println("[VIXSRC-DEBUG] Step 2 -> Embed Response Headers: ${embedResponseObj.headers}")

            val embedHtml = embedResponseObj.text
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("&amp;", "&")
            
            println("[VIXSRC-DEBUG] Step 2 -> Embed HTML Source (Full Dump):")
            logLargeString("[VIXSRC-DEBUG-EMBED-HTML]", embedHtml)

            // ====================================================================
            // ⚡ STEP 3: Decrypt / Parse Token & Expiry
            // ====================================================================
            println("[VIXSRC-DEBUG] Step 3 -> Starting Regex Parsing on Embed HTML...")
            var masterPlaylistUrl: String? = null

            // 3-1. window.masterPlaylist 패턴 시도
            val masterPlaylistMatch = """window\.masterPlaylist\s*=\s*(\{.*?\})""".toRegex().find(embedHtml)?.groupValues?.get(1)
            println("[VIXSRC-DEBUG] Step 3 -> window.masterPlaylist Match Found: ${masterPlaylistMatch != null}")
            
            if (masterPlaylistMatch != null) {
                println("[VIXSRC-DEBUG] Step 3 -> masterPlaylist Content: $masterPlaylistMatch")
                val token = """["']?token["']?\s*[:=]\s*["']([^"']+)["']""".toRegex().find(masterPlaylistMatch)?.groupValues?.get(1)
                val expires = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d+)["']?""".toRegex().find(masterPlaylistMatch)?.groupValues?.get(1)
                
                println("[VIXSRC-DEBUG] Step 3 -> Extracted Token: $token")
                println("[VIXSRC-DEBUG] Step 3 -> Extracted Expires: $expires")

                if (token != null && expires != null) {
                    masterPlaylistUrl = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires&h=1&lang=en"
                }
            } else {
                println("[VIXSRC-DEBUG] Step 3 -> window.masterPlaylist not found. Attempting Global Fallback Patterns...")
                
                val token = """["']?token["']?\s*[:=]\s*["']([a-fA-F0-9]{32})["']""".toRegex().find(embedHtml)?.groupValues?.get(1)
                val expires = """["']?expir(?:es|y)["']?\s*[:=]\s*["']?(\d{10,})["']?""".toRegex().find(embedHtml)?.groupValues?.get(1)
                
                println("[VIXSRC-DEBUG] Step 3 (Fallback) -> Extracted Token: $token")
                println("[VIXSRC-DEBUG] Step 3 (Fallback) -> Extracted Expires: $expires")

                if (token != null && expires != null) {
                    masterPlaylistUrl = "$baseUrl/playlist/$actualVid?token=$token&expires=$expires&h=1&lang=en"
                }
            }

            // ====================================================================
            // ⚡ STEP 4: Resolve Playlist & Filter Resolutions
            // ====================================================================
            if (masterPlaylistUrl != null) {
                println("[VIXSRC-DEBUG] Step 4 -> Final Constructed Master URL: $masterPlaylistUrl")
                try {
                    println("[VIXSRC-DEBUG] Step 4 -> Calling M3u8Helper...")
                    val m3u8Links = M3u8Helper.generateM3u8("VixSrc", masterPlaylistUrl, requestUrl, headers = commonHeaders)
                    println("[VIXSRC-DEBUG] Step 4 -> M3u8Helper parsed ${m3u8Links.size} links.")
                    
                    if (m3u8Links.isNotEmpty()) {
                        val sortedLinks = m3u8Links.sortedByDescending { it.quality }
                        val highQualityLinks = sortedLinks.filter { it.quality >= Qualities.P1080.value }
                        
                        val finalLinks = highQualityLinks.ifEmpty { 
                            val maxQuality = sortedLinks.first().quality
                            sortedLinks.filter { it.quality == maxQuality }
                        }
                        
                        finalLinks.forEach { link ->
                            println("[VIXSRC-DEBUG] Step 4 -> Invoking Callback for Link: Quality ${link.quality} -> ${link.url}")
                            callback.invoke(link)
                        }
                    } else {
                        throw Exception("M3u8Helper returned empty list.")
                    }
                } catch (e: Exception) {
                    println("[VIXSRC-DEBUG] ❌ Step 4 Exception in M3u8Helper: ${e.message}")
                    e.printStackTrace()
                    
                    println("[VIXSRC-DEBUG] Step 4 -> Falling back to raw Master M3U8 directly.")
                    callback.invoke(
                        newExtractorLink("VixSrc", "VixSrc", masterPlaylistUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P1080.value
                            this.headers = commonHeaders
                        }
                    )
                }
            } else {
                println("[VIXSRC-DEBUG] ❌ Step 4 Failure: masterPlaylistUrl is NULL. No stream URL can be generated.")
            }

            println("[VIXSRC-DEBUG] >>> invokeVixSrc() END <<<")

        } catch (c: CancellationException) {
            println("[VIXSRC-DEBUG] Coroutine Cancelled.")
            throw c
        } catch (e: Exception) {
            println("[VIXSRC-DEBUG] ❌ Crash Exception: ${e.message}")
            e.printStackTrace()
        }
    }
}
