// DaddyLiveExtractor.kt
// v1.1
package com.hsp1020

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import java.net.URI

data class ServerLookupResponse(
    @JsonProperty("server_url") val serverUrl: String?,
    @JsonProperty("md5") val md5: String?,
    @JsonProperty("expires") val expires: String?,
    @JsonProperty("stream_url") val streamUrl: String?
)

class DaddyLiveExtractor : ExtractorApi() {
    override val name: String = "DaddyLive"
    override val mainUrl: String = "https://dlhd.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var currentUrl = url
        val initialDomain = getBaseUrl(currentUrl)
        
        var response = app.get(currentUrl, referer = referer)
        var html = response.text

        val iframeSrcMatches = Regex("""<iframe[^>]+src=["']([^"']+)["']""").findAll(html)
        var iframeSrc: String? = null
        
        for (match in iframeSrcMatches) {
            val src = match.groupValues[1]
            if (src.contains("stream") || src.contains("embed") || src.contains("dlhd") || src.contains("php")) {
                iframeSrc = src
                break
            }
        }
        
        if (iframeSrc == null) {
            iframeSrc = iframeSrcMatches.firstOrNull()?.groupValues?.get(1)
        }

        if (iframeSrc != null) {
            currentUrl = if (iframeSrc.startsWith("//")) {
                "https:$iframeSrc"
            } else if (iframeSrc.startsWith("/")) {
                "$initialDomain$iframeSrc"
            } else {
                iframeSrc
            }
            response = app.get(currentUrl, referer = url)
            html = response.text
        }

        val currentDomain = getBaseUrl(currentUrl)

        val channelIdMatch = Regex("""channel\s*[:=]\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""['"]?id['"]?\s*:\s*['"]([^'"]+)['"]""").find(html)
            ?: Regex("""source\s*=\s*['"]([^'"]+)['"]""").find(html)
            
        val channelId = channelIdMatch?.groupValues?.get(1)

        val directM3u8Regex = Regex("""(https?://[^"']+\.m3u8\?md5=[^&"']+(?:&amp;|&)expires=\d+)""")
        val directMatch = directM3u8Regex.find(html)
        
        if (directMatch != null) {
            val directUrl = directMatch.groupValues[1].replace("&amp;", "&")
            callback.invoke(
                ExtractorLink(
                    name,
                    "DaddyLive HD (Direct)",
                    directUrl,
                    currentUrl,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
            return
        }

        if (channelId != null) {
            try {
                val lookupUrl = "$currentDomain/server_lookup.php?channel=$channelId"
                val lookupHtml = app.get(lookupUrl, headers = mapOf("Referer" to currentUrl)).text
                val lookupData = parseJson<ServerLookupResponse>(lookupHtml)
                
                val m3u8Url = if (!lookupData.serverUrl.isNullOrEmpty() && !lookupData.md5.isNullOrEmpty() && !lookupData.expires.isNullOrEmpty()) {
                    var serverUrl = lookupData.serverUrl
                    if (serverUrl.startsWith("//")) {
                        serverUrl = "https:$serverUrl"
                    }
                    "$serverUrl?md5=${lookupData.md5}&expires=${lookupData.expires}"
                } else if (!lookupData.streamUrl.isNullOrEmpty()) {
                    lookupData.streamUrl
                } else {
                    null
                }

                if (m3u8Url != null) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "DaddyLive HD (Lookup API)",
                            m3u8Url,
                            currentUrl,
                            Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return
                }
            } catch (e: Exception) {
                // Pass to auth fallback
            }

            try {
                val authUrl = "$currentDomain/auth.php?channel=$channelId"
                val authResponse = app.get(authUrl, headers = mapOf("Referer" to currentUrl), allowRedirects = false)
                val redirectUrl = authResponse.headers["location"] ?: authResponse.headers["Location"]
                
                if (redirectUrl != null && redirectUrl.contains("m3u8") && redirectUrl.contains("md5=")) {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            "DaddyLive HD (Auth Redirect)",
                            redirectUrl,
                            currentUrl,
                            Qualities.Unknown.value,
                            isM3u8 = true
                        )
                    )
                    return
                }
            } catch (e: Exception) {
                // Pass to fallback regex
            }
        }
        
        val fallbackRegex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
        val fallbackMatch = fallbackRegex.find(html)
        if (fallbackMatch != null) {
            callback.invoke(
                ExtractorLink(
                    name,
                    "DaddyLive HD (Fallback)",
                    fallbackMatch.groupValues[1].replace("&amp;", "&"),
                    currentUrl,
                    Qualities.Unknown.value,
                    isM3u8 = true
                )
            )
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url
        }
    }
}
