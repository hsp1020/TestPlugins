package com.hsp1020.elevate

import com.lagradost.cloudstream3.app
import okhttp3.Interceptor
import org.json.JSONObject

/**
 * Passive, bounded metadata capture from normal Stremio list responses.
 * Does not issue requests or consume the response body handed to the provider.
 * Existing independently-built HTTP clients may not inherit this interceptor.
 */
internal class ElevateSubtitleSourceObserver {
    @Volatile private var enabled = false
    private val observer = Interceptor { chain ->
        val request = chain.request()
        val response = chain.proceed(request)
        if (enabled && response.isSuccessful && request.method == "GET" &&
            request.url.encodedPath.endsWith(".json") &&
            (request.url.encodedPath.contains("/subtitles/") ||
                request.url.encodedPath.contains("/stream/"))) {
            val length = response.body?.contentLength() ?: -1L
            val kind = response.body?.contentType()?.toString().orEmpty()
            if (length in 1L..(512L * 1024L) &&
                (kind.contains("json", true) || kind.isBlank())) {
                runCatching {
                    val json = JSONObject(response.peekBody(length).string())
                    val addon = request.url.toString()
                        .substringBefore("/subtitles/").substringBefore("/stream/")
                    val listUrl = if (request.url.encodedPath.contains("/subtitles/"))
                        request.url.toString() else null
                    var recorded = 0
                    fun addTrack(item: JSONObject, pos: Int, lookup: String?) {
                        val url = item.optString("url")
                        if (!url.startsWith("http://", true) &&
                            !url.startsWith("https://", true)) return
                        val headers = mutableMapOf<String, String>()
                        item.optJSONObject("headers")?.let { objectHeaders ->
                            objectHeaders.keys().forEach { key ->
                                headers[key] = objectHeaders.optString(key)
                            }
                        }
                        recorded++
                        ElevateSubtitleRefreshRegistry.remember(
                            url, addon, item.optString("id").takeIf(String::isNotBlank),
                            item.optString("lang", item.optString("lang_code")),
                            lookup, pos, headers
                        )
                    }
                    json.optJSONArray("subtitles")?.let { tracks ->
                        for (pos in 0 until tracks.length()) {
                            tracks.optJSONObject(pos)?.let { addTrack(it, pos, listUrl) }
                        }
                    }
                    json.optJSONArray("streams")?.let { streams ->
                        for (index in 0 until streams.length()) {
                            streams.optJSONObject(index)?.optJSONArray("subtitles")?.let { tracks ->
                                for (pos in 0 until tracks.length()) {
                                    tracks.optJSONObject(pos)?.let { addTrack(it, pos, null) }
                                }
                            }
                        }
                    }
                    android.util.Log.d("ElevateRefreshProvenance",
                        "sourceResponse endpoint=${if (listUrl != null) "subtitles" else "stream"} " +
                            "addonHash=${addon.hashCode()} recorded=$recorded " +
                            "subCount=${json.optJSONArray("subtitles")?.length() ?: 0} " +
                            "streamCount=${json.optJSONArray("streams")?.length() ?: 0} bodyLength=$length")
                }.onFailure {
                    android.util.Log.w("ElevateRefreshProvenance",
                        "captureFailed type=${it.javaClass.simpleName} pathHash=${request.url.encodedPath.hashCode()}")
                }
            }
        }
        response
    }

    fun start() {
        enabled = true
        val client = app.baseClient
        if (client.interceptors.none { it === observer }) {
            app.baseClient = client.newBuilder().addInterceptor(observer).build()
        }
    }

    fun stop() {
        enabled = false
        val client = app.baseClient
        if (client.interceptors.any { it === observer }) {
            app.baseClient = client.newBuilder().apply {
                interceptors().removeAll { it === observer }
            }.build()
        }
    }
}
