package com.linkkf

import android.util.Log
import com.lagradost.cloudstream3.app
import java.net.URI

class LinkkfExtractor {
    companion object {
        private const val TAG = "LinkkfExtractor"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    // 최신 자막 도메인 (기본)
    private val SUBTITLE_BASE_URL = "https://subk.xyk2.top/s"

    // 기존 자막 도메인 (Fallback)
    private val SUBTITLE_FALLBACK_BASE_URL = "https://k1.sub1.top/s"

    data class LinkkfResult(
        val m3u8Url: String,
        val subtitleUrl: String,
        val fallbackSubtitleUrl: String = "",
        val needsWebView: Boolean = false,
        val referer: String
    )

    /**
     * Linkani watch 페이지에서 플레이어 정보를 가능한 한 정적으로 추출한다.
     *
     * 1) actual_url 직접 추출
     * 2) HTML/script 안의 m3u8 직접 추출
     * 3) 둘 다 실패하면 null 반환 -> Linkkf.loadLinks()가 WebView fallback 수행
     */
    suspend fun extract(url: String, referer: String): LinkkfResult? {
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to referer
        )

        return try {
            Log.d(TAG, "[START] watchUrl=$url referer=$referer")

            val response = app.get(url, headers = headers)
            val finalPageUrl = response.url
            val doc = response.document
            val html = doc.html()
            val scripts = doc.select("script")
                .map { script ->
                    val data = script.data()
                    if (data.isNotBlank()) data else script.html()
                }
                .filter { it.isNotBlank() }

            Log.d(
                TAG,
                "[PAGE] finalUrl=$finalPageUrl htmlLength=${html.length} scripts=${scripts.size}"
            )

            // 기존 코드의 `var player_ + actual_url` 고정 조건을 없애고 모든 script를 탐색한다.
            val actualUrlRaw = scripts.firstNotNullOfOrNull { extractJsField(it, "actual_url") }
                ?: extractJsField(html, "actual_url")

            val subtitleKey = scripts.firstNotNullOfOrNull { extractJsField(it, "url") }
                ?: extractJsField(html, "url")

            val subtitleUrl = subtitleKey
                ?.takeIf { it.isNotBlank() }
                ?.let { "$SUBTITLE_BASE_URL/$it.vtt" }
                .orEmpty()

            val fallbackSubtitleUrl = subtitleKey
                ?.takeIf { it.isNotBlank() }
                ?.let { "$SUBTITLE_FALLBACK_BASE_URL/$it.vtt" }
                .orEmpty()

            if (!actualUrlRaw.isNullOrBlank()) {
                val actualUrl = resolveUrl(finalPageUrl.ifBlank { url }, actualUrlRaw)
                val directM3u8 = actualUrl.contains(".m3u8", ignoreCase = true)

                Log.d(
                    TAG,
                    "[ACTUAL_URL] found=true directM3u8=$directM3u8 url=$actualUrl subtitleKey=${!subtitleKey.isNullOrBlank()}"
                )

                return LinkkfResult(
                    m3u8Url = actualUrl,
                    subtitleUrl = subtitleUrl,
                    fallbackSubtitleUrl = fallbackSubtitleUrl,
                    needsWebView = !directM3u8,
                    referer = finalPageUrl.ifBlank { url }
                )
            }

            // actual_url 구조가 바뀌어도 HTML/JS에 m3u8가 노출되어 있으면 직접 회수한다.
            val directM3u8Raw = scripts.firstNotNullOfOrNull { findM3u8(it) }
                ?: findM3u8(html)

            if (!directM3u8Raw.isNullOrBlank()) {
                val directM3u8 = resolveUrl(finalPageUrl.ifBlank { url }, directM3u8Raw)
                Log.d(TAG, "[M3U8_STATIC] url=$directM3u8")

                return LinkkfResult(
                    m3u8Url = directM3u8,
                    subtitleUrl = subtitleUrl,
                    fallbackSubtitleUrl = fallbackSubtitleUrl,
                    needsWebView = false,
                    referer = finalPageUrl.ifBlank { url }
                )
            }

            Log.w(
                TAG,
                "[NO_STATIC_STREAM] actual_url/m3u8 not found; WebView fallback required. url=$url"
            )
            null
        } catch (e: Exception) {
            Log.e(TAG, "[EXTRACT_ERROR] url=$url ${e.javaClass.simpleName}: ${e.message}", e)
            null
        }
    }

    /**
     * JSON과 일반 JS object 양쪽을 처리한다.
     * 예:
     *   "actual_url":"..."
     *   'actual_url':'...'
     *   actual_url: "..."
     *   actual_url = '...'
     */
    private fun extractJsField(source: String, key: String): String? {
        if (source.isBlank()) return null

        val escapedKey = Regex.escape(key)
        val patterns = listOf(
            Regex("""[\"']$escapedKey[\"']\s*:\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE),
            Regex("""\b$escapedKey\b\s*[:=]\s*[\"']([^\"']+)[\"']""", RegexOption.IGNORE_CASE)
        )

        for (pattern in patterns) {
            val value = pattern.find(source)?.groupValues?.getOrNull(1)
            if (!value.isNullOrBlank()) return decodeJsUrl(value)
        }
        return null
    }

    private fun findM3u8(source: String): String? {
        if (source.isBlank()) return null

        val normalized = decodeJsUrl(source)
        val absolute = Regex(
            """https?://[^\s\"'<>\\]+?\.m3u8(?:\?[^\s\"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.value
        if (!absolute.isNullOrBlank()) return absolute

        return Regex(
            """//[^\s\"'<>\\]+?\.m3u8(?:\?[^\s\"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        ).find(normalized)?.value
    }

    private fun decodeJsUrl(value: String): String {
        return value
            .trim()
            .replace("\\/", "/")
            .replace("\\u002F", "/", ignoreCase = true)
            .replace("\\u003A", ":", ignoreCase = true)
            .replace("\\u003D", "=", ignoreCase = true)
            .replace("\\u0026", "&", ignoreCase = true)
            .replace("&amp;", "&")
    }

    private fun resolveUrl(baseUrl: String, rawUrl: String): String {
        val value = decodeJsUrl(rawUrl)
        if (value.startsWith("http://", ignoreCase = true) ||
            value.startsWith("https://", ignoreCase = true)
        ) {
            return value
        }
        if (value.startsWith("//")) return "https:$value"

        return try {
            URI(baseUrl).resolve(value).toString()
        } catch (_: Exception) {
            value
        }
    }
}
