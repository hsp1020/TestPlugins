package com.ohli24

import android.util.Log

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI

class MichealCDN : Cdndania() {
    override val name = "MichealCDN"
    override val mainUrl = "https://michealcdn.com"
}

open class Cdndania : ExtractorApi() {
    override val name = "CDNdania"
    override val mainUrl = "https://cdndania.com"
    override val requiresReferer = true
    
    // [v1.1 추가] 완벽한 위장을 위한 브라우저 User-Agent
    private val DESKTOP_UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/121.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val trace = android.os.SystemClock.elapsedRealtimeNanos()
        val started = android.os.SystemClock.elapsedRealtime()
        Log.i("OHLI24Trace", "phase=CDN_START trace=" + trace + " extractor=" + name +
            " input=" + ohliSafeUrl(url) + " referer=" + ohliSafeUrl(referer))
        val host = getBaseUrl(url)

        if (url.contains("/video/")) {
            try {
                val videoResponse = app.get(url, referer = referer)
                val script = videoResponse.document.selectFirst("script:containsData(playerjsSubtitle)")?.data().orEmpty()
                val srtRegex = Regex("""playerjsSubtitle\s*=\s*"[^"]*(https?://[^"]+\.srt)"""")
                val srtUrl = srtRegex.find(script)?.groupValues?.get(1) ?: ""
                Log.i("OHLI24Trace", "phase=CDN_PAGE trace=" + trace +
                    " http=" + videoResponse.code + " htmlLength=" + videoResponse.text.length +
                    " subtitleScript=" + script.isNotBlank() + " srtFound=" + srtUrl.isNotBlank())

                val extractedHash = url.substringAfterLast("/")
                val m3u8Url = "$host/player/index.php?data=$extractedHash&do=getVideo"
                val header = mapOf("x-requested-with" to "XMLHttpRequest")
                val safeReferer = referer ?: ""
                val formdata = mapOf("hash" to extractedHash, "r" to safeReferer)
                Log.w("OHLI24Trace", "phase=HASH_INPUT trace=" + trace +
                    " pathIdLength=" + url.substringBefore('?').substringAfterLast('/').length +
                    " sentHashLength=" + extractedHash.length +
                    " includesQuery=" + extractedHash.contains('?') +
                    " queryKeys=" + ohliSafeUrl(url).substringAfter("queryKeys=", "") +
                    " postRefererPresent=" + safeReferer.isNotBlank())

                val postResponse = app.post(m3u8Url, headers = header, data = formdata)
                val response = postResponse.parsedSafe<Response>()
                Log.i("OHLI24Trace", "phase=CDN_POST trace=" + trace +
                    " http=" + postResponse.code + " bodyLength=" + postResponse.text.length +
                    " parsed=" + (response != null) +
                    " rawHasVideoSource=" + postResponse.text.contains("\"videoSource\"") +
                    " rawHasSecuredLink=" + postResponse.text.contains("\"securedLink\"") +
                    " videoSourcePresent=" + !response?.videoSource.isNullOrBlank() +
                    " securedLinkPresent=" + !response?.securedLink.isNullOrBlank())

                response?.videoSource?.let { m3u8 ->
                    val streamHeaders = mapOf(
                        "User-Agent" to DESKTOP_UA,
                        "Referer" to url,
                        "Origin" to host,
                        "Accept" to "*/*",
                        "Accept-Language" to "ko-KR,ko;q=0.9,en-US;q=0.8,en;q=0.7"
                    )
                    var finalUrl = m3u8
                    Log.i("OHLI24Trace", "phase=HLS_CANDIDATE trace=" + trace +
                        " source=videoSource url=" + ohliSafeUrl(m3u8) +
                        " headers=" + streamHeaders.keys.joinToString(","))
                    try {
                        val m3u8Res = app.get(m3u8, headers = streamHeaders)
                        val content = try {
                            m3u8Res.text.trim()
                        } catch (e: Exception) {
                            Log.w("OHLI24Trace", "phase=HLS_TEXT_FALLBACK trace=" + trace, e)
                            m3u8Res.document.text().trim()
                        }
                        var embedded = false
                        if (!content.startsWith("#EXTM3U")) {
                            val innerM3u8Regex = Regex("""(https?://[^"']+\.m3u8[^"']*)""")
                            innerM3u8Regex.find(content)?.let {
                                finalUrl = it.groupValues[1]
                                embedded = true
                            }
                        }
                        Log.i("OHLI24Trace", "phase=HLS_CHECK trace=" + trace +
                            " http=" + m3u8Res.code + " bodyLength=" + content.length +
                            " isM3u8=" + content.startsWith("#EXTM3U") +
                            " embeddedLink=" + embedded +
                            " final=" + ohliSafeUrl(finalUrl))
                    } catch (e: Exception) {
                        Log.w("OHLI24Trace", "phase=HLS_CHECK_ERROR trace=" + trace +
                            " fallbackToOriginal=true", e)
                    }
                    callback(
                        newExtractorLink(
                            "CDN",
                            "CDN",
                            url = finalUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.headers = streamHeaders
                            this.referer = url
                            this.quality = Qualities.P1080.value
                        }
                    )
                    Log.i("OHLI24Trace", "phase=CDN_LINK_EMITTED trace=" + trace +
                        " link=" + ohliSafeUrl(finalUrl))

                    if (srtUrl.isNotBlank()) {
                        subtitleCallback.invoke(newSubtitleFile("Korean", srtUrl))
                        Log.i("OHLI24Trace", "phase=CDN_SUBTITLE_EMITTED trace=" + trace)
                    }
                } ?: run {
                    Log.w("OHLI24Trace", "phase=CDN_NO_VIDEO_SOURCE trace=" + trace +
                        " parsed=" + (response != null))
                }
            } catch (e: Exception) {
                Log.e("OHLI24Trace", "phase=CDN_EXCEPTION trace=" + trace, e)
            }
        } else {
            Log.w("OHLI24Trace", "phase=CDN_UNSUPPORTED_PATH trace=" + trace)
        }
        Log.i("OHLI24Trace", "phase=CDN_END trace=" + trace +
            " elapsedMs=" + (android.os.SystemClock.elapsedRealtime() - started))
    }

    private fun getBaseUrl(url: String): String {
        return URI(url).let { "${it.scheme}://${it.host}" }
    }
}


internal fun ohliSafeUrl(raw: String?): String {
    if (raw.isNullOrBlank()) return "NONE"
    return runCatching {
        val parsed = URI(raw)
        val firstSegment = parsed.path.orEmpty().trimStart('/').substringBefore('/')
        val queryNames = parsed.rawQuery.orEmpty().split('&')
            .filter { it.isNotBlank() }.joinToString(",") { it.substringBefore('=') }
        "host=" + (parsed.host ?: "?") +
            " pathGroup=" + firstSegment + " pathLength=" + parsed.path.orEmpty().length +
            " queryKeys=" + queryNames
    }.getOrDefault("urlParseFailed length=" + raw.length)
}

data class Response(
    val hls: Boolean,
    val videoImage: String,
    val videoSource: String,
    val securedLink: String,
    val downloadLinks: List<Any?>,
    val attachmentLinks: List<Any?>,
    val ck: String,
)
