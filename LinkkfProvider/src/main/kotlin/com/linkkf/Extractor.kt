package com.linkkf

import com.lagradost.cloudstream3.app
import java.net.URI

class LinkkfExtractor {
    data class Result(
        val url: String,
        val needsWebView: Boolean,
        val referer: String
    )

    suspend fun extract(url: String, referer: String): Result? {
        return try {
            val response = app.get(
                url,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Referer" to referer
                )
            )
            val pageUrl = response.url.ifBlank { url }
            val doc = response.document
            val html = doc.html()
            val playerScript = doc.select("script").firstOrNull {
                val source = it.data().ifBlank { it.html() }
                source.contains("actual_url", true) || source.contains(".m3u8", true)
            }?.let { it.data().ifBlank { it.html() } }

            extractField(playerScript.orEmpty(), "actual_url")
                ?.let { resolve(pageUrl, it) }
                ?.let { return Result(it, !it.contains(".m3u8", true), pageUrl) }

            findM3u8(playerScript.orEmpty())
                ?.let { resolve(pageUrl, it) }
                ?.let { return Result(it, false, pageUrl) }

            findM3u8(html)
                ?.let { resolve(pageUrl, it) }
                ?.let { Result(it, false, pageUrl) }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractField(source: String, key: String): String? {
        if (source.isBlank()) return null
        return Regex(
            """(?i)[\"']?${Regex.escape(key)}[\"']?\s*[:=]\s*[\"']([^\"']+)[\"']"""
        ).find(source)?.groupValues?.getOrNull(1)?.let(::decode)
    }

    private fun findM3u8(source: String): String? {
        if (source.isBlank()) return null
        return Regex(
            """(?i)(?:https?:)?//[^\s\"'<>\\]+?\.m3u8(?:\?[^\s\"'<>\\]*)?"""
        ).find(decode(source))?.value
    }

    private fun decode(value: String): String = value
        .trim()
        .replace("\\/", "/")
        .replace("\\u002F", "/", true)
        .replace("\\u003A", ":", true)
        .replace("\\u003D", "=", true)
        .replace("\\u0026", "&", true)
        .replace("&amp;", "&")

    private fun resolve(baseUrl: String, rawUrl: String): String {
        val value = decode(rawUrl)
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            else -> runCatching { URI(baseUrl).resolve(value).toString() }.getOrDefault(value)
        }
    }

    private companion object {
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }
}
