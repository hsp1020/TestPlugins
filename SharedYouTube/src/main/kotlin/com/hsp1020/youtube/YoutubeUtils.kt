package com.hsp1020.youtube

import android.content.SharedPreferences
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap

object YoutubeUtils {
    const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    const val IOS_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    const val ANDROID_VR_USER_AGENT = "Mozilla/5.0 (Linux; Android 12L; Quest 3) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36"
    const val DEFAULT_CLIENT_VERSION = "2.20240725.01.00"

    private val sha1Digest = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-1")
    }

    val YT_ID_REGEX = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
    val MIX_TITLE_REPLACE_EN = Regex("""\s*\(Mix\s*Playlist\)""", RegexOption.IGNORE_CASE)

    private val regexCache = ConcurrentHashMap<String, Regex>()

    fun sha1(input: String): String {
        val md = sha1Digest.get()
        md.reset()
        val bytes = md.digest(input.toByteArray(StandardCharsets.UTF_8))
        val sb = java.lang.StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    fun getSapisidHash(sapisid: String, origin: String = "https://www.youtube.com"): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val msg = "$timestamp $sapisid $origin"
        val hash = sha1(msg)
        return "SAPISIDHASH ${timestamp}_$hash"
    }

    fun buildCookiesAndExtractSapisid(
        prefs: SharedPreferences?, 
        lang: String, 
        fallbackVisitor: String,
        isSearch: Boolean = false
    ): Pair<String, String?> {
        val cookieBuilder = java.lang.StringBuilder()
        val visitor = prefs?.getString("VISITOR_INFO1_LIVE", null)
        if (!visitor.isNullOrBlank()) {
            cookieBuilder.append("VISITOR_INFO1_LIVE=$visitor; ")
        } else {
            cookieBuilder.append("VISITOR_INFO1_LIVE=$fallbackVisitor; ")
        }

        var sapisidVal: String? = null
        if (!isSearch) {
            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID", "__Secure-1PSID", "__Secure-3PAPISID")
            authKeys.forEach { key ->
                val value = prefs?.getString(key, null)
                if (!value.isNullOrBlank()) {
                    cookieBuilder.append("$key=$value; ")
                    if (key == "SAPISID") sapisidVal = value
                }
            }
        }
        cookieBuilder.append("PREF=f6=40000000&hl=$lang; CONSENT=YES+fx.456722336;")
        return Pair(cookieBuilder.toString(), sapisidVal)
    }

    fun getProxiedPoster(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return try {
            "https://wsrv.nl/?url=${URLEncoder.encode(poster, "UTF-8")}&w=800&h=1200&fit=contain&cbg=black"
        } catch (e: Exception) {
            poster
        }
    }

    fun getProxiedPosterBottom(poster: String?): String? = getProxiedPoster(poster)
    fun getProxiedPosterNormal(poster: String?): String? = getProxiedPoster(poster)

    fun getShortsSearchPoster(videoId: String): String {
        val prefix = "https%3A%2F%2Fi.ytimg.com%2Fvi%2F"
        val suffix = "%2Foardefault.jpg%3Fsqp%3D-oaymwEoCJUDENAFSFqQAgHyq4qpAxcIARUAAIhC2AEB4gEKCBgQAhgGOAFAAQ%3D%3D%26rs%3DAOn4CLDWlHo9hImueeIb7JfDzDGNRuQ5tg%26usqp%3DCCk"
        return "https://wsrv.nl/?url=$prefix$videoId$suffix&w=800&h=1200&fit=contain&cbg=black"
    }

    fun getShortsEpisodeThumbnail(videoId: String): String {
        return getShortsSearchPoster(videoId)
    }
    
    fun findConfig(html: String, key: String): String? {
        return try {
            val regex = regexCache.computeIfAbsent(key) { k ->
                Regex("\"$k\"\\s*:\\s*\"([^\"]+)\"")
            }
            regex.find(html)?.groupValues?.getOrNull(1)
        } catch (e: Exception) {
            null
        }
    }

    fun getBestThumbnail(thumbData: Any?): String? {
        return try {
            val thumbs = when (thumbData) {
                is Map<*, *> -> (thumbData["thumbnails"] as? List<*>)
                    ?: (thumbData["sources"] as? List<*>)
                is List<*> -> thumbData
                else -> null
            }
            val lastThumb = thumbs?.lastOrNull() as? Map<*, *>
            var url = lastThumb?.get("url") as? String
            if (url?.startsWith("//") == true) url = "https:$url"
            url
        } catch (e: Exception) {
            null
        }
    }

    fun buildThumbnailFromId(videoId: String?): String? {
        if (videoId.isNullOrBlank()) return null
        return "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
    }

    fun extractYoutubeId(url: String): String? {
        return YT_ID_REGEX.find(url)?.groupValues?.getOrNull(1) ?: run {
            if (url.length == 11 && url.matches(Regex("[A-Za-z0-9_-]{11}"))) {
                url
            } else {
                null
            }
        }
    }

    fun cleanMixTitle(title: String): String {
        if (!title.contains("mix", ignoreCase = true)) return title
        return MIX_TITLE_REPLACE_EN.replace(title, "").trim()
    }

    fun urlDecode(input: String): String {
        if (!input.contains('%')) return input
        var current = input
        var decodeCount = 0
        while (current.contains('%') && decodeCount < 3) {
            val decoded = try {
                URLDecoder.decode(current, "UTF-8")
            } catch (e: Exception) {
                break
            }
            if (decoded == current) break
            current = decoded
            decodeCount++
        }
        return current
    }

    fun getMapKeyFromAny(obj: Any?, key: String): Map<*, *>? {
        val map = obj as? Map<*, *> ?: return null
        return map[key] as? Map<*, *>
    }

    fun getListKeyFromAny(obj: Any?, key: String): List<*>? {
        val map = obj as? Map<*, *> ?: return null
        return map[key] as? List<*>
    }

    fun getStringFromAny(obj: Any?, key: String): String? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: return null
        return if (value is String) value else value.toString()
    }

    fun extractUrlFromLockup(lockup: Map<*, *>?): String? {
        if (lockup == null) return null
        val command = getMapKeyFromAny(lockup, "onTap")?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "rendererContext")?.let { getMapKeyFromAny(it, "commandContext") }?.let { getMapKeyFromAny(it, "onTap") }?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "itemPlayback")?.let { getMapKeyFromAny(it, "inlinePlayerData") }?.let { getMapKeyFromAny(it, "onSelect") }?.let { getMapKeyFromAny(it, "innertubeCommand") }
            ?: getMapKeyFromAny(lockup, "navigationEndpoint")
        
        val webCommandUrl = getMapKeyFromAny(command, "commandMetadata")?.let { getMapKeyFromAny(it, "webCommandMetadata") }?.let { getStringFromAny(it, "url") }
        if (!webCommandUrl.isNullOrBlank()) return webCommandUrl

        val watch = getMapKeyFromAny(command, "watchEndpoint") ?: command
        val videoId = getStringFromAny(watch, "videoId")
        val playlistId = getStringFromAny(watch, "playlistId")
        if (!playlistId.isNullOrBlank()) {
            return if (!videoId.isNullOrBlank()) {
                "/watch?v=$videoId&list=$playlistId"
            } else {
                "/watch?list=$playlistId"
            }
        }
        return null
    }
}
