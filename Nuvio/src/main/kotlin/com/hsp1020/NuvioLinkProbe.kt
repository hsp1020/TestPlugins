package com.hsp1020

import android.os.SystemClock
import com.lagradost.api.Log
import com.lagradost.cloudstream3.app
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import okhttp3.Request
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Temporary, bounded diagnostics for root-path links whose query may point to a landing page. */
internal class NuvioLinkProbe(private val scope: CoroutineScope) {
    private val sampled = ConcurrentHashMap.newKeySet<String>()

    fun inspect(scraper: String, url: String, headers: Map<String, String>) {
        val uri = runCatching { URI(url) }.getOrNull() ?: return
        val host = uri.host ?: return
        if (uri.scheme !in listOf("http", "https") ||
            (uri.path != null && uri.path != "" && uri.path != "/") ||
            uri.rawQuery.isNullOrEmpty()) return

        val queryKeys = uri.rawQuery.split('&').map { it.substringBefore('=').take(32) }
            .distinct().take(8).joinToString(",")
        Log.d("NuvioLinkProbe", "phase=CANDIDATE scraper=$scraper host=$host queryKeys=$queryKeys")
        // A single representative request for each origin; never probe every signed link.
        val origin = "${uri.scheme}://$host:${uri.port}${uri.path.orEmpty()}"
        if (!sampled.add(origin) || sampled.size > 8) return

        scope.launch(Dispatchers.IO) {
            val started = SystemClock.elapsedRealtime()
            try {
                val builder = Request.Builder().url(url).header("Range", "bytes=0-2047")
                for ((name, value) in headers) {
                    if (name.equals("Host", true) || name.equals("Content-Length", true) ||
                        name.equals("Range", true)) continue
                    runCatching { builder.header(name, value) }
                }
                val client = app.baseClient.newBuilder()
                    .followRedirects(true).followSslRedirects(true)
                    .callTimeout(4, TimeUnit.SECONDS).build()
                client.newCall(builder.build()).execute().use { response ->
                    var redirects = 0
                    var previous = response.priorResponse
                    while (previous != null) { redirects++; previous = previous.priorResponse }
                    val contentType = response.header("Content-Type").orEmpty()
                    val snippet = response.peekBody(2048).string().lowercase()
                    val isHtml = contentType.contains("html", true) ||
                        snippet.contains("<!doctype html") || snippet.contains("<html")
                    val markers = listOfNotNull(
                        "form".takeIf { snippet.contains("<form") },
                        "meta-refresh".takeIf { snippet.contains("http-equiv") && snippet.contains("refresh") },
                        "js-redirect".takeIf { snippet.contains("location.href") ||
                            snippet.contains("location.replace") },
                        "anchor".takeIf { snippet.contains("<a ") },
                    ).joinToString(",")
                    val destination = response.request.url
                    val kind = if (isHtml) "HTML"
                        else if (contentType.startsWith("video/", true) ||
                            contentType.contains("mpegurl", true) ||
                            contentType.contains("dash+xml", true)) "MEDIA"
                        else if (contentType.contains("json", true)) "JSON"
                        else "OTHER"
                    Log.d("NuvioLinkProbe", "phase=RESULT scraper=$scraper host=$host " +
                        "status=${response.code} redirects=$redirects finalHost=${destination.host} " +
                        "finalPath=${destination.encodedPath.take(96)} mime=${contentType.take(80)} " +
                        "kind=$kind markers=$markers range=${response.header("Content-Range") != null} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - started}")
                }
            } catch (e: Exception) {
                Log.d("NuvioLinkProbe", "phase=ERROR scraper=$scraper host=$host " +
                    "cause=${e.javaClass.simpleName} elapsedMs=${SystemClock.elapsedRealtime() - started}")
            }
        }
    }
}
