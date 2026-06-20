package com.hsp1020.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.mapper
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import com.lagradost.cloudstream3.AcraApplication
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

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

    fun getProxiedPosterBottom(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return try {
            "https://wsrv.nl/?url=${URLEncoder.encode(poster, "UTF-8")}&w=800&h=1200&fit=contain&cbg=black"
        } catch (e: Exception) {
            poster
        }
    }

    fun getProxiedPosterNormal(poster: String?): String? {
        if (poster.isNullOrBlank()) return null
        return try {
            "https://wsrv.nl/?url=${URLEncoder.encode(poster, "UTF-8")}&w=800&h=1200&fit=contain&cbg=black"
        } catch (e: Exception) {
            poster
        }
    }

    fun getShortsSearchPoster(videoId: String): String {
        return try {
            val targetUrl = "https://i.ytimg.com/vi/$videoId/oardefault.jpg?sqp=-oaymwEoCJUDENAFSFqQAgHyq4qpAxcIARUAAIhC2AEB4gEKCBgQAhgGOAFAAQ==&rs=AOn4CLDWlHo9hImueeIb7JfDzDGNRuQ5tg&usqp=CCk"
            val encoded = URLEncoder.encode(targetUrl, "UTF-8")
            "https://wsrv.nl/?url=$encoded&w=800&h=1200&fit=contain&cbg=black"
        } catch (e: Exception) {
            "https://i.ytimg.com/vi/$videoId/oar2.jpg"
        }
    }

    fun getShortsEpisodeThumbnail(videoId: String): String {
        return try {
            val targetUrl = "https://i.ytimg.com/vi/$videoId/oardefault.jpg?sqp=-oaymwEoCJUDENAFSFqQAgHyq4qpAxcIARUAAIhC2AEB4gEKCBgQAhgGOAFAAQ==&rs=AOn4CLDWlHo9hImueeIb7JfDzDGNRuQ5tg&usqp=CCk"
            val encoded = URLEncoder.encode(targetUrl, "UTF-8")
            "https://wsrv.nl/?url=$encoded&w=1280&h=720&fit=contain&cbg=black&a=right"
        } catch (e: Exception) {
            "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
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
        val direct = map[key]
        if (direct is Map<*, *>) return direct
        val entriesList = map.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                return entry.value as? Map<*, *>
            }
        }
        return null
    }

    fun getListKeyFromAny(obj: Any?, key: String): List<*>? {
        val map = obj as? Map<*, *> ?: return null
        val direct = map[key]
        if (direct is List<*>) return direct as? List<Map<*, *>>
        val entriesList = map.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                return entry.value as? List<*>
            }
        }
        return null
    }

    fun getStringFromAny(obj: Any?, key: String): String? {
        val map = obj as? Map<*, *> ?: return null
        val direct = map[key]
        if (direct != null) {
            return if (direct is String) direct else direct.toString()
        }
        val entriesList = map.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                val value = entry.value
                return if (value is String) value else value?.toString()
            }
        }
        return null
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

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {

    val appPrefs: SharedPreferences?
        get() = sharedPref ?: AcraApplication.context?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true
    )
    
    data class CachedChip(
        @JsonProperty("name") val name: String,
        @JsonProperty("token") val token: String
    )

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    
    override var lang: String
        get() = appPrefs?.getString("youtube_language", "en") ?: "en"
        set(value) {}
        
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    companion object {
        private val homeShorts = Collections.synchronizedList(mutableListOf<Episode>())
        private val searchShorts = Collections.synchronizedList(mutableListOf<Episode>())

        private val continuationTokenCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 500
                }
            }
        )

        private val epMetaMapCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 2000
                }
            }
        )

        @Volatile
        private var savedVisitorData: String? = null
        @Volatile
        private var savedApiKey: String? = null
        @Volatile
        private var savedClientVersion: String? = null

        private val API_KEY_EXTRACT_REGEX = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
        
        private val DURATION_REGEX = Regex("""(\d{1,2}:)?\d{1,2}:\d{2}""")
        private val BY_CHANNEL_REGEX = Regex("(?:by)\\s+(.*?)\\s+(?:\\d|view)", RegexOption.IGNORE_CASE)
        private val LENGTH_SECONDS_REGEX = Regex(""""lengthSeconds"\s*:\s*"?(\d+)"""")
        private val VIEW_COUNT_REGEX = Regex(""""viewCount"\s*:\s*"?(\d+)"""")
        private val PUBLISH_DATE_REGEX = Regex(""""publishDate"\s*:\s*"?([^"]+)"""")

        private val KEYS_TO_CHECK = listOf(
            "contents", "items", "continuationItems", "onResponseReceivedActions",
            "onResponseReceivedEndpoints", "appendContinuationItemsAction", "onResponseReceivedCommands",
            "gridShelfViewModel", "verticalListRenderer", "horizontalListRenderer", "shelfRenderer",
            "itemSectionRenderer", "richShelfRenderer", "reelShelfRenderer", "richGridRenderer"
        )

        private val TARGET_RENDERER_KEYS = setOf(
            "videoRenderer", "gridVideoRenderer", "compactVideoRenderer",
            "shortsVideoRenderer", "reelItemRenderer", "shortsLockupViewModel",
            "videoViewModel", "lockupViewModel"
        )

        private fun addToShortsList(list: MutableList<Episode>, ep: Episode) {
            synchronized(list) {
                if (list.none { it.data == ep.data }) {
                    list.add(ep)
                    if (list.size > 200) {
                        list.removeAt(0)
                    }
                }
            }
        }
    }

    private var isSearchContext = false

    class YouTubeInterceptor(private val provider: YoutubeProvider) : Interceptor {
        private val prefs get() = provider.appPrefs

        override fun intercept(chain: Interceptor.Chain): Response {
            val request = chain.request()
            val urlStr = request.url.toString()
            val isSearchRequest = urlStr.contains("/results") || urlStr.contains("/v1/search")

            val requestBuilder = request.newBuilder()
                .header("User-Agent", YoutubeUtils.CHROME_USER_AGENT)

            val origReferer = request.header("Referer")
            if (origReferer != null && (origReferer.contains("/results") || origReferer.contains("/v1/search"))) {
                requestBuilder.header("Referer", "https://www.youtube.com/")
            }

            if (!isSearchRequest) {
                val (cookies, sapisidVal) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, provider.lang, "_Mk3UVhY40g", isSearch = false)
                requestBuilder.addHeader("Cookie", cookies)
                requestBuilder.addHeader("Accept-Language", "${provider.lang},en-US;q=0.9,en;q=0.8")

                if (!sapisidVal.isNullOrBlank()) {
                    try {
                        val authHeader = YoutubeUtils.getSapisidHash(sapisidVal)
                        requestBuilder.addHeader("Authorization", authHeader)
                        requestBuilder.addHeader("X-Origin", "https://www.youtube.com")
                        requestBuilder.addHeader("X-Goog-AuthUser", "0")
                    } catch (e: Exception) {
                        logError(e)
                    }
                }
            } else {
                val (cookies, _) = YoutubeUtils.buildCookiesAndExtractSapisid(prefs, provider.lang, "_Mk3UVhY40g", isSearch = true)
                requestBuilder.addHeader("Cookie", cookies)
                requestBuilder.addHeader("Accept-Language", "${provider.lang},en-US;q=0.9,en;q=0.8")
            }

            return chain.proceed(requestBuilder.build())
        }
    }
    
    private val ytInterceptor = YouTubeInterceptor(this)

    private var savedContinuationToken: String? = null

    @Suppress("PropertyName")
    private data class PlayerResponse(
        @JsonProperty("streamingData") val streamingData: StreamingData?,
        @JsonProperty("playbackTracking") val playbackTracking: PlaybackTracking? 
    )
    private data class StreamingData(
        @JsonProperty("hlsManifestUrl") val hlsManifestUrl: String?
    )

    private data class PlaybackTracking(
        @JsonProperty("videostatsPlaybackUrl") val videostatsPlaybackUrl: TrackingUrl?,
        @JsonProperty("videostatsWatchtimeUrl") val videostatsWatchtimeUrl: TrackingUrl?
    )
    private data class TrackingUrl(
        @JsonProperty("baseUrl") val baseUrl: String?
    )

    private fun generateCpn(): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_"
        return (1..16).map { alphabet.random() }.joinToString("")
    }

    private fun buildTrackingUrl(base: String, cpn: String, cmt: String, state: String?, realTime: String? = null): String {
        val uri = android.net.Uri.parse(base).buildUpon()
        uri.appendQueryParameter("cpn", cpn)
        uri.appendQueryParameter("cmt", cmt)
        uri.appendQueryParameter("ver", "2")
        if (state != null) {
            uri.appendQueryParameter("state", state)
            uri.appendQueryParameter("st", "0.000")
            uri.appendQueryParameter("et", cmt)
        }
        if (realTime != null) {
            uri.appendQueryParameter("rt", realTime)
        }
        return uri.build().toString()
    }

    private suspend fun triggerPlaybackTracking(playbackTracking: PlaybackTracking?, videoId: String, isShort: Boolean = false) {
        val playbackBaseUrl = playbackTracking?.videostatsPlaybackUrl?.baseUrl
        val watchtimeBaseUrl = playbackTracking?.videostatsWatchtimeUrl?.baseUrl

        if (playbackBaseUrl.isNullOrBlank() || watchtimeBaseUrl.isNullOrBlank()) {
            return
        }

        val cpn = generateCpn()

        try {
            var playbackUrl = buildTrackingUrl(playbackBaseUrl, cpn, cmt = "0.000", state = null, realTime = "0")
            var watchtimeUrl = buildTrackingUrl(watchtimeBaseUrl, cpn, cmt = "10.000", state = "playing", realTime = "10")

            playbackUrl = playbackUrl.replace(Regex("([&?])c=[^&]+"), "$1c=WEB")
                .replace(Regex("([&?])cver=[^&]+"), "$1cver=2.20240101.00")
            watchtimeUrl = watchtimeUrl.replace(Regex("([&?])c=[^&]+"), "$1c=WEB")
                .replace(Regex("([&?])cver=[^&]+"), "$1cver=2.20240101.00")

            if (isShort) {
                playbackUrl = playbackUrl.replace("el=detailpage", "el=shortspage").replace("el=embedded", "el=shortspage")
                if (!playbackUrl.contains("el=shortspage")) playbackUrl = "$playbackUrl&el=shortspage"
                
                watchtimeUrl = watchtimeUrl.replace("el=detailpage", "el=shortspage").replace("el=embedded", "el=shortspage")
                if (!watchtimeUrl.contains("el=shortspage")) watchtimeUrl = "$watchtimeUrl&el=shortspage"
            }

            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    val pRes = app.get(playbackUrl, interceptor = ytInterceptor)
                    kotlinx.coroutines.delay(2500L)
                    val wRes = app.get(watchtimeUrl, interceptor = ytInterceptor)
                } catch (e: Exception) {
                    logError(e)
                }
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun getSavedContinuationToken(key: String): String? {
        return getSavedContinuationToken(key, 0)
    }

    private fun getSavedContinuationToken(key: String, page: Int): String? {
        val pageKey = if (page > 0) "${key}_page_$page" else key
        val hashedKey = YoutubeUtils.sha1(pageKey)
        val cached = continuationTokenCache[hashedKey]
        
        if (key.startsWith("chip_") || key.contains("chip", ignoreCase = true)) {
            Log.d("YoutubeUtils", "[CHIP] getSavedContinuationToken - Page: $page, Key: $pageKey, HashedKey: $hashedKey, CachedToken: $cached")
        }

        if (cached != null) return cached
        
        val token = appPrefs?.getString("cont_token_$hashedKey", null)
        if (key.startsWith("chip_") || key.contains("chip", ignoreCase = true)) {
            Log.d("YoutubeUtils", "[CHIP] getSavedContinuationToken - Page: $page, Loaded from Prefs: $token")
        }

        if (token != null) {
            continuationTokenCache[hashedKey] = token
        }
        return token
    }

    private fun saveContinuationToken(key: String, token: String?) {
        saveContinuationToken(key, 0, token)
    }

    private fun saveContinuationToken(key: String, page: Int, token: String?) {
        val pageKey = if (page > 0) "${key}_page_$page" else key
        val hashedKey = YoutubeUtils.sha1(pageKey)
        
        if (key.startsWith("chip_") || key.contains("chip", ignoreCase = true)) {
            Log.d("YoutubeUtils", "[CHIP] saveContinuationToken - Page: $page, Key: $pageKey, HashedKey: $hashedKey, TokenToSave: $token")
        }

        if (token.isNullOrBlank()) {
            val removed = continuationTokenCache.remove(hashedKey)
            if (removed != null) {
                appPrefs?.edit()?.remove("cont_token_$hashedKey")?.apply()
            }
        } else {
            val oldToken = continuationTokenCache.put(hashedKey, token)
            if (oldToken != token) {
                appPrefs?.edit()?.putString("cont_token_$hashedKey", token)?.apply()
            }
        }
    }

    private fun clearContinuationTokens(key: String) {
        // 새로고침 시 1페이지부터 100페이지까지의 기존 페이지네이션 토큰 캐시를 비워 꼬임을 차단합니다.
        for (p in 0..100) {
            val pageKey = if (p > 0) "${key}_page_$p" else key
            val hashedKey = YoutubeUtils.sha1(pageKey)
            continuationTokenCache.remove(hashedKey)
            appPrefs?.edit()?.remove("cont_token_$hashedKey")?.apply()
        }
    }
    
    private fun getEpisodeMetaString(videoData: Map<*, *>?): String {
        if (videoData == null) return ""
        
        var duration = ""
        val lengthText = getText(videoData.getMapKey("lengthText"))
        if (lengthText.isNotBlank()) {
            duration = DURATION_REGEX.find(lengthText)?.value ?: ""
        } else {
            val overlays = videoData.getListKey("thumbnailOverlays") ?: YoutubeUtils.getListKeyFromAny(videoData, "thumbnailOverlays") as? List<*>
            overlays?.forEach { overlay ->
                if (overlay is Map<*, *>) {
                    val ts = overlay.getMapKey("thumbnailOverlayTimeStatusRenderer")
                    if (ts != null) {
                        val text = getText(ts.getMapKey("text"))
                        val match = DURATION_REGEX.find(text)
                        if (match != null) duration = match.value
                    }
                }
            }
        }

        var channel = getText(videoData.getMapKey("longBylineText"))
            .ifBlank { getText(videoData.getMapKey("shortBylineText")) }
            .ifBlank { getText(videoData.getMapKey("ownerText")) }

        // 💡 [중복 제거 완료]: Conflicting declarations 빌드 예외를 유발하던 기존의 이중 선언문을 단일 선언문으로 완벽 정리
        var views = ""
        var date = ""

        val shortViewCountText = getText(videoData.getMapKey("shortViewCountText"))
        val viewCountText = getText(videoData.getMapKey("viewCountText"))
        val publishedTimeText = getText(videoData.getMapKey("publishedTimeText"))
        val videoInfo = getText(videoData.getMapKey("videoInfo"))

        var rawViews = shortViewCountText.ifBlank { viewCountText }
        date = publishedTimeText

        if (rawViews.isBlank() && videoInfo.isNotBlank()) {
            val parts = videoInfo.split("•", "·").map { it.trim() }
            if (parts.isNotEmpty()) rawViews = parts[0]
            if (parts.size > 1 && date.isBlank()) date = parts[1]
        }

        if (rawViews.isNotBlank()) {
            views = rawViews
        }

        if (views.isBlank() || date.isBlank()) {
            val rows = videoData.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")?.getMapKey("metadata")?.getMapKey("contentMetadataViewModel")?.getListKey("metadataRows")
            rows?.forEachIndexed { index, row ->
                val parts = row.getListKey("metadataParts") ?: YoutubeUtils.getListKeyFromAny(row, "metadataParts") as? List<*>
                parts?.forEachIndexed { pIndex, part ->
                    if (part is Map<*, *>) {
                        val t = getText(part.getMapKey("text"))
                        if (t.isNotBlank() && t != duration && t != channel) {
                            if (index == 0) {
                                channel = t
                            } else if (index == 1) {
                                if (pIndex == 0) {
                                    views = t
                                } else {
                                    date = t
                                }
                            }
                        }
                    }
                }
            }
        }

        val partsList = listOf(duration, channel, views, date).filter { it.isNotBlank() && it != "N/A" }
        return partsList.joinToString(" • ")
    }
    

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? {
        val direct = this[key]
        if (direct is Map<*, *>) return direct
        val entriesList = this.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                return entry.value as? Map<*, *>
            }
        }
        return null
    }

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? {
        val direct = this[key]
        if (direct is List<*>) return direct as? List<Map<*, *>>
        val entriesList = this.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                return entry.value as? List<Map<*, *>>
            }
        }
        return null
    }

    private fun Map<*, *>.getString(key: String): String? {
        val direct = this[key]
        if (direct != null) return if (direct is String) direct else direct.toString()
        val entriesList = this.entries
        for (entry in entriesList) {
            if ((entry.key as? String) == key) {
                val value = entry.value
                return if (value is String) value else value?.toString()
            }
        }
        return null
    }

    private fun getText(obj: Any?): String {
        if (obj == null) return ""
        if (obj is String) return obj
        if (obj is Map<*, *>) {
            return obj.getString("simpleText")
                ?: obj.getString("text")
                ?: obj.getString("content")
                ?: obj.getString("label")
                ?: (obj.getListKey("runs") ?: YoutubeUtils.getListKeyFromAny(obj, "runs"))?.joinToString("") { run ->
                    when (run) {
                        is String -> run
                        is Map<*, *> -> (run["text"] as? String)
                            ?: run.getString("simpleText")
                            ?: ""

                        else -> ""
                    }
                }.orEmpty()
                ?: obj.getMapKey("text")?.let { getText(it) }.orEmpty()
        }
        return ""
    }

    // 💡 [순정 직결]: 메타데이터 파트의 순정 텍스트("12만회" 등)를 직접 대입
    private fun extractLockupMetadata(lockup: Map<*, *>) : Triple<String, String, String> {
        var channel = ""
        var views = ""
        var date = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            if (rows != null && rows.isNotEmpty()) {
                if (rows.size == 1) {
                    val singleRow = rows.getOrNull(0)
                    if (singleRow != null) {
                        val parts = singleRow.getListKey("metadataParts")
                            ?: YoutubeUtils.getListKeyFromAny(singleRow, "metadataParts") as? List<*>
                        
                        val firstPart = parts?.getOrNull(0) as? Map<*, *>
                        if (firstPart != null) {
                            val rawViews = getText(firstPart.getMapKey("text") ?: firstPart["text"])
                            if (rawViews.isNotBlank()) {
                                views = rawViews
                            }
                        }
                        
                        val secondPart = parts?.getOrNull(1) as? Map<*, *>
                        if (secondPart != null) {
                            date = getText(secondPart.getMapKey("text") ?: secondPart["text"])
                        }
                    }
                } else {
                    val firstRow = rows.getOrNull(0)
                    if (firstRow != null) {
                        val parts = firstRow.getListKey("metadataParts")
                            ?: YoutubeUtils.getListKeyFromAny(firstRow, "metadataParts") as? List<*>
                        val firstPart = parts?.getOrNull(0) as? Map<*, *>
                        if (firstPart != null) {
                            channel = getText(firstPart.getMapKey("text") ?: firstPart["text"])
                        }
                    }

                    val secondRow = rows.getOrNull(1)
                    if (secondRow != null) {
                        val parts = secondRow.getListKey("metadataParts")
                            ?: YoutubeUtils.getListKeyFromAny(secondRow, "metadataParts") as? List<*>
                        
                        val firstPart = parts?.getOrNull(0) as? Map<*, *>
                        if (firstPart != null) {
                            val rawViews = getText(firstPart.getMapKey("text") ?: firstPart["text"])
                            if (rawViews.isNotBlank()) {
                                views = rawViews
                            }
                        }
                        
                        val secondPart = parts?.getOrNull(1) as? Map<*, *>
                        if (secondPart != null) {
                            date = getText(secondPart.getMapKey("text") ?: secondPart["text"])
                        }
                    }
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (direct.isNotBlank()) {
                views = direct
            }
        }

        return Triple(channel, views, date)
    }

    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>,
        playlistId: String? = null
    ): SearchResponse? {
        if (renderer == null) return null

        val videoData = renderer.getMapKey("videoRenderer")
            ?: renderer.getMapKey("compactVideoRenderer")
            ?: renderer.getMapKey("gridVideoRenderer")
            ?: renderer.getMapKey("playlistVideoRenderer")       
            ?: renderer.getMapKey("playlistPanelVideoRenderer")  

        if (videoData != null) {
            val videoId = videoData.getString("videoId")
            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                
                val metaString = getEpisodeMetaString(videoData)
                if (metaString.isNotBlank()) epMetaMapCache[videoId] = metaString

                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("shortViewCountText") ?: videoData.getMapKey("viewCountText"))
                val dateText = getText(videoData.getMapKey("publishedTimeText"))

                val channel = getText(videoData.getMapKey("ownerText"))
                    .ifBlank { getText(videoData.getMapKey("shortBylineText")) }
                    .ifBlank { getText(videoData.getMapKey("longBylineText")) }

                // 💡 [컴파일러 쉴드]: views를 가변 변수가 아닌 불변 'val' 상수로 선언하여 재할당 에러를 차단합니다.
                val views = viewText.ifBlank { "N/A" }
                
                val finalTitle = when {
                    channel.isNotBlank() && views.isNotBlank() && views != "N/A" -> "{$channel | $views} $title"
                    channel.isNotBlank() -> "{$channel} $title"
                    views.isNotBlank() && views != "N/A" && dateText.isNotBlank() -> "{$views | $dateText} $title"
                    views.isNotBlank() && views != "N/A" -> "{$views} $title"
                    else -> title
                }
                
                var poster = YoutubeUtils.getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = YoutubeUtils.buildThumbnailFromId(videoId)
                
                val watchUrl = if (!playlistId.isNullOrBlank()) {
                    "$mainUrl/watch?v=$videoId&list=$playlistId"
                } else {
                    "$mainUrl/watch?v=$videoId"
                }

                return newMovieSearchResponse(
                    finalTitle,
                    watchUrl,
                    TvType.Movie
                ) {
                    this.posterUrl = if (isSearchContext) YoutubeUtils.getProxiedPoster(poster) else poster
                }
            }
            return null
        }

        val richContent = renderer.getMapKey("richItemRenderer")?.getMapKey("content")
        val shortsData = renderer.getMapKey("reelItemRenderer")
            ?: renderer.getMapKey("shortsLockupViewModel")
            ?: richContent?.getMapKey("shortsLockupViewModel")

        if (shortsData != null) {
            val onTap = shortsData.getMapKey("onTap")
            val videoId = onTap?.getMapKey("innertubeCommand")?.getMapKey("reelWatchEndpoint")
                ?.getString("videoId")
                ?: shortsData.getString("videoId")
                ?: shortsData.getString("entityId")?.replace("shorts-shelf-item-", "")

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val overlay = shortsData.getMapKey("overlayMetadata")
                val accessibilityText = shortsData.getString("accessibilityText") ?: ""

                var title = overlay?.getMapKey("primaryText")?.getString("content")
                if (title.isNullOrBlank()) title = getText(shortsData.getMapKey("headline"))
                if (title.isNullOrBlank()) title =
                    overlay?.getMapKey("primaryText")?.getString("simpleText")
                if (title.isNullOrBlank() && accessibilityText.contains(",")) title =
                    accessibilityText.substringBefore(",").trim()
                if (title.isNullOrBlank()) title = "Shorts Clip"

                var viewRaw = overlay?.getMapKey("secondaryText")?.getString("content")
                if (viewRaw.isNullOrBlank()) viewRaw =
                    getText(shortsData.getMapKey("viewCountText"))
                if (viewRaw.isNullOrBlank() && accessibilityText.isNotBlank()) {
                    val match = Regex(",\\s*(.*?)\\s*-").find(accessibilityText)
                    if (match != null) viewRaw = match.groupValues[1].trim()
                }
                val views = viewRaw.ifBlank { "N/A" }

                val poster = YoutubeUtils.getShortsSearchPoster(videoId)

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = synchronized(currentList) { currentList.size } + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                val descText = if (views.isNotBlank()) "$views" else ""
                
                val newShortEp = newEpisode(finalUrl) {
                    this.name = title
                    this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                    this.episode = episodeNum
                    this.description = descText
                }
                addToShortsList(currentList, newShortEp)

                val finalTitle = "#$episodeNum {Short | $views} $title"
                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            val contentType = lockup.getString("contentType") ?: ""
            if (contentType.contains("AD") || contentType.contains("SPONSORED") || contentType.contains("PROMOTED")) {
                return null
            }

            if (contentType == "LOCKUP_CONTENT_TYPE_PLAYLIST") {
                val playlistId = lockup.getString("contentId")
                if (!playlistId.isNullOrBlank() && seenIds.add(playlistId)) {
                    var title = lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")?.getString("content") ?: "Playlist"

                    if (playlistId.startsWith("RD") || playlistId.startsWith("UL") || playlistId.startsWith("MM") || playlistId.startsWith("RDMM")) {
                        title = YoutubeUtils.cleanMixTitle(title)
                    }

                    val episodeCount =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getListKey("overlays")?.firstOrNull()
                            ?.getMapKey("thumbnailOverlayBadgeViewModel")
                            ?.getListKey("thumbnailBadges")?.firstOrNull()
                            ?.getMapKey("thumbnailBadgeViewModel")?.getString("text") ?: ""
                    val poster =
                        lockup.getMapKey("contentImage")?.getMapKey("collectionThumbnailViewModel")
                            ?.getMapKey("primaryThumbnail")?.getMapKey("thumbnailViewModel")
                            ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()
                            ?.getString("url")

                    val extractedUrl = YoutubeUtils.extractUrlFromLockup(lockup)
                    val targetPath = if (!extractedUrl.isNullOrBlank()) {
                        extractedUrl.removePrefix("/")
                    } else {
                        if (playlistId.startsWith("RD") || playlistId.startsWith("UL") || playlistId.startsWith("MM") || playlistId.startsWith("RDMM")) {
                            "watch?list=$playlistId"
                        } else {
                            "playlist?list=$playlistId"
                        }
                    }

                    return newTvSeriesSearchResponse(
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title,
                        "$mainUrl/$targetPath",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = if (isSearchContext) {
                            YoutubeUtils.getProxiedPosterBottom(poster)
                        } else {
                            YoutubeUtils.getProxiedPosterNormal(poster)
                        }
                    }
                }
            }

            val videoId = lockup.getString("contentId")
                ?: lockup.getMapKey("content")?.getString("videoId")
                ?: (lockup.getMapKey("content")?.getMapKey("videoRenderer")?.getString("videoId"))

            if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                val title = getText(
                    lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                        ?.getMapKey("title")
                )
                if (title.isBlank()) {
                    return null
                }
                var (channel, views, date) = extractLockupMetadata(lockup)     
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match = BY_CHANNEL_REGEX.find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                val metaString = getEpisodeMetaString(lockup.getMapKey("content") ?: lockup)
                if (metaString.isNotBlank()) epMetaMapCache[videoId] = metaString

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = synchronized(currentList) { currentList.size } + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    
                    poster = YoutubeUtils.getShortsSearchPoster(videoId)
                    
                    val descText = buildString {
                        if (channel.isNotBlank()) append("Channel: $channel")
                        if (views.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append("$views")
                        }
                    }
                    
                    val newShortEp2 = newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                        this.episode = episodeNum
                        this.description = descText
                    }
                    addToShortsList(currentList, newShortEp2)

                    finalTitle = "#$episodeNum [Shorts] {Short | $views} $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle = when {
                        channel.isNotBlank() && views.isNotBlank() -> "{$channel | $views} $title"
                        channel.isNotBlank() -> "{$channel} $title"
                        views.isNotBlank() && date.isNotBlank() -> "{$views | $date} $title"
                        views.isNotBlank() -> "{$views} $title"
                        else -> title
                    }
                    poster = YoutubeUtils.getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) {
                        this.posterUrl = if (isSearchContext) {
                            YoutubeUtils.getProxiedPosterBottom(poster)
                        } else {
                            YoutubeUtils.getProxiedPosterNormal(poster)
                        }
                    }
                }
            }
        }

        val channelData = renderer.getMapKey("channelRenderer")
        if (channelData != null) {
            val id = channelData.getString("channelId")
            if (!id.isNullOrBlank() && seenIds.add(id)) {
                val title = getText(channelData.getMapKey("title")) ?: "Channel"
                val stats = (getText(channelData.getMapKey("videoCountText"))
                    ?: getText(channelData.getMapKey("subscriberCountText"))) ?: ""
                val poster = YoutubeUtils.getBestThumbnail(channelData.getMapKey("thumbnail"))
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) {
                    this.posterUrl = if (isSearchContext) {
                        YoutubeUtils.getProxiedPosterBottom(poster)
                    } else {
                        YoutubeUtils.getProxiedPosterNormal(poster)
                    }
                }
            }
        }
        return null
    }

    private fun processRecursive(
        data: Any?,
        outList: MutableList<SearchResponse>,
        seenIds: MutableSet<String>,
        playlistMode: Boolean,
        depth: Int = 0,
        playlistId: String? = null
    ) {
        if (data == null || depth > 32) return 
        if (data is Map<*, *>) {
            val extracted = collectFromRenderer(data, seenIds, playlistId)
            if (extracted != null) {
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            var foundContainer = false
            val keysSize = KEYS_TO_CHECK.size
            for (i in 0 until keysSize) {
                val key = KEYS_TO_CHECK[i]
                if (data.containsKey(key)) {
                    processRecursive(data[key], outList, seenIds, playlistMode, depth + 1, playlistId)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode, depth + 1, playlistId)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode, depth + 1, playlistId)
            }
        }
    }

    private fun extractBalancedJson(input: String): String? {
        var braceCount = 0
        var inString = false
        var escape = false
        val len = input.length
        for (i in 0 until len) {
            val char = input[i]
            if (escape) {
                escape = false
                continue
            }
            if (char == '\\') {
                escape = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (char == '{') {
                    braceCount++
                } else if (char == '}') {
                    braceCount--
                    if (braceCount == 0) {
                        return input.substring(0, i + 1)
                    }
                }
            }
        }
        return null
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        var index = 0
        val htmlLen = html.length
        while (true) {
            val found = html.indexOf("ytInitialData", index)
            if (found == -1) break
            val contextEnd = minOf(found + 100, htmlLen)
            var hasAssign = false
            var hasVarOrWindow = false
            
            for (i in found until contextEnd) {
                if (html[i] == '=') {
                    hasAssign = true
                    break
                }
            }
            
            if (html.indexOf("var", found, ignoreCase = false) in found until contextEnd ||
                html.indexOf("window", found, ignoreCase = false) in found until contextEnd ||
                html.startsWith("ytInitialData", found)) {
                hasVarOrWindow = true
            }

            if (hasAssign && hasVarOrWindow) {
                val braceIndex = html.indexOf("{", found)
                if (braceIndex != -1 && braceIndex - found < 150) {
                    val possibleJson = html.substring(braceIndex)
                    val jsonString = extractBalancedJson(possibleJson)
                    if (jsonString != null) {
                        try {
                            return mapper.readValue<Map<String, Any>>(jsonString)
                            
                        } catch (e: Exception) {
                        }
                    }
                }
            }
            index = minOf(found + 13, htmlLen)
        }
        return null
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            val renderer = data["continuationItemRenderer"] as? Map<*, *>
            if (renderer != null) {
                val endpoint = renderer["continuationEndpoint"] as? Map<*, *>
                if (endpoint != null) {
                    val token = (endpoint["continuationCommand"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["browseContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["searchContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                        ?: (endpoint["command"] as? Map<*, *>)?.get("token") as? String
                        ?: endpoint["token"] as? String
                    if (!token.isNullOrBlank()) {
                        return token
                    }
                }
            }

            val nextData = data["nextContinuationData"] as? Map<*, *>
                ?: data["reloadContinuationData"] as? Map<*, *>
                ?: data["timedContinuationData"] as? Map<*, *>
                ?: data["invalidationContinuationData"] as? Map<*, *>
            if (nextData != null) {
                val token = nextData["continuation"] as? String
                    ?: nextData["token"] as? String
                if (!token.isNullOrBlank()) {
                    return token
                }
            }

            for ((k, v) in data) {
                val keyStr = k as? String ?: continue
                if (keyStr == "header" ||
                    keyStr == "feedFilterChipBarRenderer" || 
                    keyStr == "chipCloudRenderer" || 
                    keyStr == "relatedChipCloudRenderer" ||
                    keyStr == "chipCloudChipRenderer" ||
                    keyStr == "chipViewModel" ||
                    keyStr == "chipCloud") {
                    continue
                }
                val t = findTokenRecursive(v)
                if (t != null) return t
            }
        } else if (data is List<*>) {
            for (item in data) {
                val t = findTokenRecursive(item)
                if (t != null) return t
            }
        }
        return null
    }

    override val mainPage: List<MainPageData>
        get() {
            val customSections = getCustomHomepages()
            val list = mutableListOf<MainPageData>()

            val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
            val cachedChipsMap = try {
                mapper.readValue<List<CachedChip>>(cachedChipsJson).associateBy { it.name }
            } catch (e: Exception) {
                emptyMap()
            }

            customSections.filter { section -> section.isEnabled }.forEach { section ->
                val url = section.url
                when {
                    url == "Home" -> {
                        list.add(MainPageData("Home", "Home", horizontalImages = true))
                    }
                    url.startsWith("chip_name_") -> {
                        val chipName = url.removePrefix("chip_name_")
                        val activeChip = cachedChipsMap[chipName]
                        if (activeChip != null) {
                            list.add(MainPageData(activeChip.name, "chip_name_${activeChip.name}", horizontalImages = true))
                        }
                    }
                    else -> {
                        var title = section.name
                        if (title.isBlank()) title = extractNameFromUrl(url)
                        list.add(MainPageData(title, url, horizontalImages = true))
                    }
                }
            }

            return list
        }

    private fun getCustomHomepages(): List<CustomSection> {
        val json = appPrefs?.getString("custom_homepages_v3", "[]") ?: "[]"
        val currentList = try {
            mapper.readValue<List<CustomSection>>(json).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }

        var modified = false

        if (currentList.none { section -> section.url == "Home" }) {
            currentList.add(0, CustomSection(name = "Home", url = "Home", isEnabled = true))
            modified = true
        }

        val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
        try {
            val cachedChips = mapper.readValue<List<CachedChip>>(cachedChipsJson)
            
            // [해결책 1] 캐시에 현재 들어있지 않더라도 기존에 설정되어 정렬된 칩 카테고리를 custom_homepages_v3 설정에서 제거하지 않습니다.
            // 이를 통해 사용자가 설정한 순서와 숨김 여부가 새로고침 시 초기화되던 현상을 해결합니다.
            /* 
            val cachedNames = cachedChips.map { it.name }.toSet()
            val iterator = currentList.iterator()
            while (iterator.hasNext()) {
                val sec = iterator.next()
                if (sec.url.startsWith("chip_name_")) {
                    val name = sec.url.removePrefix("chip_name_")
                    if (!cachedNames.contains(name)) {
                        iterator.remove()
                        modified = true
                    }
                }
            }
            */

            cachedChips.forEach { chip ->
                val stableUrl = "chip_name_${chip.name}"
                if (currentList.none { section -> section.url == stableUrl }) {
                    currentList.add(CustomSection(name = chip.name, url = stableUrl, isEnabled = true))
                    modified = true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (modified) {
            try {
                val updatedJson = mapper.writeValueAsString(currentList)
                appPrefs?.edit()?.putString("custom_homepages_v3", updatedJson)?.apply()
            } catch (e: Exception) {
                logError(e)
            }
        }
        
        return currentList
    }

    private fun extractNameFromUrl(url: String): String {
        return when {
            url.contains("/@") -> "@" + url.substringAfter("/@").substringBefore("/")
            url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/")
            url.contains("/channel/") -> "Channel " + url.substringAfter("/channel/").take(5)
            url.contains("list=") -> "Playlist: " + url.substringAfter("list=").take(8)
            else -> "Custom Section"
        }
    }

    private fun resetContinuation() {
        savedContinuationToken = null
    }

    // 💡 [단일 전형화]: 중복적인 영문 이중 병렬 호출(en)을 전면 제거하고 오직 설정한 로컬 언어로 단 1회만 고속 동수 수집
    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        isSearchContext = false
        val rawRequestData = request.data
        val requestData = YoutubeUtils.urlDecode(rawRequestData)
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        val isChip = requestData.startsWith("chip_name_")

        if (page > maxPages) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null

        try {
            if (page == 1) {
                // [해결책 2] 새로고침 시 이전에 캐싱된 하위 페이지들의 토큰을 전부 비워 갱신이 꼬이는 문제를 차단합니다.
                clearContinuationTokens(requestData)

                if (isChip) {
                    // [해결책 2] 칩 카테고리 새로고침 시, 먼저 메인 홈을 무캐시로 접근하여 실시간으로 갱신된 칩 토큰을 미리 긁어옵니다.
                    try {
                        val html = app.get(mainUrl, interceptor = ytInterceptor, headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")).text
                        savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                        savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                        savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                        
                        if (!savedApiKey.isNullOrBlank()) {
                            appPrefs?.edit()
                                ?.putString("yt_api_key", savedApiKey)
                                ?.putString("yt_visitor_data", savedVisitorData)
                                ?.putString("yt_client_version", savedClientVersion)
                                ?.apply()
                        }

                        val initialData = extractYtInitialData(html)
                        if (initialData != null) {
                            val chipBar = safeGet(initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "richGridRenderer", "header", "feedFilterChipBarRenderer", "contents") as? List<*>
                            if (chipBar != null) {
                                val cachedChipsMap = mutableMapOf<String, CachedChip>()
                                for (chipItem in chipBar) {
                                    val chipMap = (chipItem as? Map<*, *>)?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
                                    val text = getText(chipMap["text"])
                                    val token = safeGet(chipMap, "navigationEndpoint", "continuationCommand", "token") as? String
                                    if (text.isNotBlank() && !token.isNullOrBlank()) {
                                        cachedChipsMap[text] = CachedChip(text, token)
                                    }
                                }
                                
                                val jsonArray = org.json.JSONArray()
                                cachedChipsMap.values.forEach { chip ->
                                    val obj = org.json.JSONObject()
                                    obj.put("name", chip.name)
                                    obj.put("token", chip.token)
                                    jsonArray.put(obj)
                                }
                                appPrefs?.edit()?.putString("yt_cached_chips", jsonArray.toString())?.apply()
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("YoutubeUtils", "[CHIP] Failed to refresh chip tokens from mainUrl", e)
                    }

                    savedApiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    savedVisitorData = savedVisitorData ?: appPrefs?.getString("yt_visitor_data", null)
                    savedClientVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION)

                    if (savedApiKey.isNullOrBlank()) {
                        try {
                            val html = app.get(mainUrl, interceptor = ytInterceptor, headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")).text
                            savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                            savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                            savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                            
                            if (!savedApiKey.isNullOrBlank()) {
                                appPrefs?.edit()
                                    ?.putString("yt_api_key", savedApiKey)
                                    ?.putString("yt_visitor_data", savedVisitorData)
                                    ?.putString("yt_client_version", savedClientVersion)
                                    ?.apply()
                            }
                        } catch (e: Exception) {
                            Log.e("YoutubeUtils", "[CHIP] Failed to fetch mainUrl for configs", e)
                        }
                    }

                    val chipCategoryName = requestData.removePrefix("chip_name_")
                    val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
                    val cachedChips = try {
                        mapper.readValue<List<CachedChip>>(cachedChipsJson)
                    } catch (e: Exception) {
                        emptyList<CachedChip>()
                    }
                    val activeChip = cachedChips.find { it.name == chipCategoryName }
                    val tokenToUse = activeChip?.token

                    if (!tokenToUse.isNullOrBlank() && !savedApiKey.isNullOrBlank()) {
                        val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to (savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION),
                                    "platform" to "DESKTOP",
                                    "hl" to lang
                                )
                            ),
                            "continuation" to tokenToUse
                        )
                        
                        // [해결책 2] 헤더에 Cache-Control을 주입하여 캐시 데이터가 아닌 신선한 응답을 강제합니다.
                        val reqHeaders = mapOf(
                            "Origin" to mainUrl, 
                            "Referer" to mainUrl,
                            "Cache-Control" to "no-cache",
                            "Pragma" to "no-cache"
                        )
                        val response = app.post(apiUrl, json = payload, headers = reqHeaders, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        
                        if (response != null) {
                            val actions = response["onResponseReceivedActions"] 
                                ?: response["onResponseReceivedEndpoints"]
                                ?: response["onResponseReceivedCommands"] 
                                ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                        }
                    }
                } else {
                    // [해결책 2] 일반 홈 및 채널 데이터를 무캐시 헤더와 함께 호출합니다.
                    val targetUrl = if(requestData == "Home") mainUrl else requestData
                    val html = app.get(targetUrl, interceptor = ytInterceptor, headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")).text
                    
                    savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                    savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                    savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    
                    if (!savedApiKey.isNullOrBlank()) {
                        appPrefs?.edit()
                            ?.putString("yt_api_key", savedApiKey)
                            ?.putString("yt_visitor_data", savedVisitorData)
                            ?.putString("yt_client_version", savedClientVersion)
                            ?.apply()
                    }

                    val initialData = extractYtInitialData(html)
                    if (initialData != null) {
                        if (requestData == "Home") {
                            val chipBar = safeGet(initialData, "contents", "twoColumnBrowseResultsRenderer", "tabs", 0, "tabRenderer", "content", "richGridRenderer", "header", "feedFilterChipBarRenderer", "contents") as? List<*>
                            if (chipBar != null) {
                                val cachedChipsMap = mutableMapOf<String, CachedChip>()
                                for (chipItem in chipBar) {
                                    val chipMap = (chipItem as? Map<*, *>)?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
                                    val text = getText(chipMap["text"])
                                    val token = safeGet(chipMap, "navigationEndpoint", "continuationCommand", "token") as? String
                                    if (text.isNotBlank() && !token.isNullOrBlank()) {
                                        cachedChipsMap[text] = CachedChip(text, token)
                                    }
                                }
                                
                                val jsonArray = org.json.JSONArray()
                                cachedChipsMap.values.forEach { chip ->
                                    val obj = org.json.JSONObject()
                                    obj.put("name", chip.name)
                                    obj.put("token", chip.token)
                                    jsonArray.put(obj)
                                }
                                appPrefs?.edit()?.putString("yt_cached_chips", jsonArray.toString())?.apply()
                            }
                        }

                        val playlistId = if (requestData.contains("list=")) {
                            requestData.substringAfter("list=", "").substringBefore("&")
                        } else null

                        processRecursive(initialData, results, seenIds, playlistMode = false, playlistId = playlistId)
                        nextContinuation = findTokenRecursive(initialData)
                    }
                }
            } else {
                // page > 1 (기존 코드 유지)
                var tokenToUse = getSavedContinuationToken(requestData, page)

                if (isChip && tokenToUse.isNullOrBlank() && page >= 2) {
                    var rCount = 0
                    while (tokenToUse.isNullOrBlank() && rCount < 15) {
                        kotlinx.coroutines.delay(300L)
                        tokenToUse = getSavedContinuationToken(requestData, page)
                        rCount++
                    }
                }
                
                if (tokenToUse.isNullOrBlank() && page == 2) { 
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION

                    if (isChip && !apiKey.isNullOrBlank()) {
                        val chipCategoryName = requestData.removePrefix("chip_name_")
                        val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
                        val cachedChips = try {
                            mapper.readValue<List<CachedChip>>(cachedChipsJson)
                        } catch (e: Exception) {
                            emptyList<CachedChip>()
                        }
                        val activeChip = cachedChips.find { it.name == chipCategoryName }
                        val rawToken = activeChip?.token

                        if (!rawToken.isNullOrBlank()) {
                            val apiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                            val payload = mapOf(
                                "context" to mapOf(
                                    "client" to mapOf(
                                        "clientName" to "WEB", 
                                        "clientVersion" to cVersion, 
                                        "hl" to lang,
                                        "platform" to "DESKTOP"
                                    )
                                ),
                                "continuation" to rawToken
                            )
                            val reqHeaders = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                            val response = app.post(apiUrl, json = payload, headers = reqHeaders, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                            if (response != null) {
                                tokenToUse = findTokenRecursive(response)
                                if (!tokenToUse.isNullOrBlank()) {
                                    saveContinuationToken(requestData, page, tokenToUse)
                                    
                                    val innerApiUrl = "$mainUrl/youtubei/v1/browse?key=$savedApiKey"
                                    val innerPayload = mapOf(
                                        "context" to mapOf(
                                            "client" to mapOf(
                                                "clientName" to "WEB", 
                                                "clientVersion" to cVersion, 
                                                "hl" to lang,
                                                "platform" to "DESKTOP"
                                            )
                                        ),
                                        "continuation" to tokenToUse
                                    )
                                    val innerResponse = app.post(innerApiUrl, json = innerPayload, headers = reqHeaders, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                                    if (innerResponse != null) {
                                        val actions = innerResponse["onResponseReceivedActions"] 
                                            ?: innerResponse["onResponseReceivedEndpoints"]
                                            ?: innerResponse["onResponseReceivedCommands"] 
                                            ?: innerResponse["continuationContents"] 
                                            ?: innerResponse
                                        
                                        val playlistId = if (requestData.contains("list=")) {
                                            requestData.substringAfter("list=", "").substringBefore("&")
                                        } else null

                                        processRecursive(actions, results, seenIds, playlistMode = false, playlistId = playlistId)
                                        nextContinuation = findTokenRecursive(innerResponse)
                                    }
                                }
                            }
                        }
                    }
                }

                if (!tokenToUse.isNullOrBlank() && results.isEmpty()) {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    val apiUrl = if (requestData.contains("/results")) "$mainUrl/youtubei/v1/search?key=$apiKey" else "$mainUrl/youtubei/v1/browse?key=$apiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB", 
                                "clientVersion" to cVersion, 
                                "hl" to lang,
                                "platform" to "DESKTOP"
                            )
                        ),
                        "continuation" to tokenToUse
                    )
                    val reqHeaders = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                    val response = app.post(apiUrl, json = payload, headers = reqHeaders, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedActions"] 
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response["continuationContents"] 
                            ?: response

                        val playlistId = if (requestData.contains("list=")) {
                            requestData.substringAfter("list=", "").substringBefore("&")
                        } else null

                        processRecursive(actions, results, seenIds, playlistMode = false, playlistId = playlistId)
                        nextContinuation = findTokenRecursive(response)
                    }
                }
            }
        } catch (e: Exception) { 
            logError(e) 
        }

        saveContinuationToken(requestData, page + 1, nextContinuation)

        if (requestData.contains("history", ignoreCase = true)) {
            val sorted = mutableListOf<SearchResponse>()
            val tempShorts = mutableListOf<SearchResponse>()
            val tempVods = mutableListOf<SearchResponse>()
            
            for (item in results) {
                val isShort = item.url.contains("/shorts/")
                if (isShort) {
                    if (tempVods.isNotEmpty()) {
                        sorted.addAll(tempVods)
                        tempVods.clear()
                        sorted.addAll(tempShorts)
                        tempShorts.clear()
                    }
                    tempShorts.add(item)
                } else {
                    tempVods.add(item)
                }
            }
            if (tempVods.isNotEmpty() || tempShorts.isNotEmpty()) {
                sorted.addAll(tempVods)
                sorted.addAll(tempShorts)
            }
            results.clear()
            results.addAll(sorted)
        }

        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    // 💡 [단일 전형화]: 영문 병렬 수집 로직(en)을 완전히 폐기하고 오직 설정한 로컬 언어로 단 1회만 고속 동수 수집
    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            return newSearchResponseList(emptyList(), false)
        }

        val results = mutableListOf<SearchResponse>()
        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }

        val seenIds = mutableSetOf<String>()
        
        // 💡 [컴파일러 쉴드]: 기존의 'val actualQuery' 상수를 'var actualQuery' 변수로 교정하여 플레이리스트 분기 시 재할당 에러 소거
        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = appPrefs?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }
        
        try {
            if (page == 1) {
                // 토큰 캐시 targetLang별 완벽 격리 설계 유지
                saveContinuationToken("search_token_$lang", null)
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                val html = app.get(url, interceptor = ytInterceptor).text

                val localApiKey = API_KEY_EXTRACT_REGEX.find(html)?.groupValues?.get(1)
                val localVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")
                if (!localApiKey.isNullOrBlank()) {
                    savedApiKey = localApiKey
                    savedVisitorData = localVisitorData
                }

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    val localContToken = findTokenRecursive(initialData)
                    saveContinuationToken("search_token_$lang", localContToken)
                }
            } else {
                val tokenToUse = getSavedContinuationToken("search_token_$lang")
                if (!tokenToUse.isNullOrBlank()) {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val vData = savedVisitorData ?: appPrefs?.getString("yt_visitor_data", null) ?: ""
                    val apiUrl = "$mainUrl/youtubei/v1/search?key=$apiKey"
                    val payload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB",
                                "clientVersion" to "2.20240101.00",
                                "hl" to lang,
                                "gl" to "US",
                                "visitorData" to vData
                            )
                        ),
                        "continuation" to tokenToUse
                    )
                    val reqHeaders = mapOf("Origin" to mainUrl, "Referer" to mainUrl) 
                    val response = app.post(apiUrl, json = payload, headers = reqHeaders, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        val actions = response["onResponseReceivedActions"]
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        val localContToken = findTokenRecursive(response)
                        saveContinuationToken("search_token_$lang", localContToken)
                    }
                }
            }
            return newSearchResponseList(results, !getSavedContinuationToken("search_token_$lang").isNullOrBlank())
        } catch (e: Exception) {
            return newSearchResponseList(emptyList(), false)
        }
    }

    private val collectedShorts = mutableListOf<Episode>()

    private fun addShortToCache(title: String, url: String, poster: String?) {
        if (collectedShorts.none { it.data == url }) {
             collectedShorts.add(
                newEpisode(url) {
                    this.name = title
                    this.posterUrl = poster
                    this.episode = collectedShorts.size + 1
                }
             )
        }
    }

    private fun safeGet(data: Any?, vararg keys: Any): Any? {
        var current = data
        for (key in keys) {
            current = when {
                current is Map<*, *> && key is String -> current[key]
                current is List<*> && key is Int -> current.getOrNull(key)
                else -> return null
            }
        }
        return current
    }

    private fun extractTitle(titleObject: Map<*, *>?): String? {
        if (titleObject == null) return null
        return titleObject.getString("simpleText")
            ?: titleObject.getListKey("runs")?.joinToString("") { it.getString("text") ?: "" }
            ?: titleObject.getString("text")
    }

    private fun extractPlaylistEpisodes(data: Any?, seenIds: MutableSet<String>): List<Episode> {
        val episodes = mutableListOf<Episode>()
        
        fun recurse(node: Any?, depth: Int = 0) {
            if (node == null || depth > 32) return
            if (node is Map<*, *>) {
                val playlistVideo = node.getMapKey("playlistVideoRenderer")
                if (playlistVideo != null) {
                    val videoId = playlistVideo.getString("videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistVideo.getMapKey("title"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = getEpisodeMetaString(playlistVideo)
                        })
                    }
                }
                
                val playlistPanelVideo = node.getMapKey("playlistPanelVideoRenderer")
                if (playlistPanelVideo != null) {
                    val videoId = playlistPanelVideo.getString("videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistPanelVideo.getMapKey("title"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistPanelVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = getEpisodeMetaString(playlistPanelVideo)
                        })
                    }
                }
                
                for (value in node.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        recurse(value, depth + 1)
                    }
                }
            } else if (node is List<*>) {
                for (item in node) {
                    recurse(item, depth + 1)
                }
            }
        }
        
        recurse(data)
        return episodes
    }

    private class PlaylistMetadata {
        var title: String = ""
        var author: String = ""
        var videoAuthor: String = ""
        var description: String = ""
        var channelId: String = ""
        var channelAvatar: String = ""
        var subscriberCount: String = ""
    }

    private fun findPlaylistMetadataRecursive(obj: Any?, metadata: PlaylistMetadata, depth: Int = 0) {
        if (obj == null || depth > 32) return
        if (obj is Map<*, *>) {

            val primary = YoutubeUtils.getMapKeyFromAny(obj, "playlistSidebarPrimaryInfoRenderer")
            if (primary != null) {
                val t = getText(primary["title"] ?: YoutubeUtils.getMapKeyFromAny(primary, "title"))
                if (t.isNotBlank()) {
                    metadata.title = t
                }
                val d = getText(primary["description"] ?: YoutubeUtils.getMapKeyFromAny(primary, "description"))
                if (d.isNotBlank()) metadata.description = d
            }

            val secondary = YoutubeUtils.getMapKeyFromAny(obj, "playlistSidebarSecondaryInfoRenderer")
            if (secondary != null) {
                val owner = YoutubeUtils.getMapKeyFromAny(secondary, "videoOwner")?.let { YoutubeUtils.getMapKeyFromAny(it, "videoOwnerRenderer") }
                if (owner != null) {
                    val a = getText(owner["title"] ?: YoutubeUtils.getMapKeyFromAny(owner, "title"))
                    if (a.isNotBlank()) {
                        metadata.author = a
                    }
                }
            }

            val header = YoutubeUtils.getMapKeyFromAny(obj, "playlistHeaderRenderer")
            if (header != null) {
                val t = getText(header["title"] ?: YoutubeUtils.getMapKeyFromAny(header, "title"))
                if (t.isNotBlank()) metadata.title = t
                val ownerText = YoutubeUtils.getMapKeyFromAny(header, "ownerText")
                if (ownerText != null) {
                    val a = getText(ownerText)
                    if (a.isNotBlank()) metadata.author = a
                }
                val d = getText(header["description"] ?: YoutubeUtils.getMapKeyFromAny(header, "description"))
                if (d.isNotBlank()) metadata.description = d
            }

            val panel = YoutubeUtils.getMapKeyFromAny(obj, "playlistPanelRenderer")
            if (panel != null) {
                val t = getText(panel["titleText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "titleText"))
                    .ifBlank { getText(panel["title"] ?: YoutubeUtils.getMapKeyFromAny(panel, "title")) }
                if (t.isNotBlank()) {
                    metadata.title = t
                }
                
                val a = getText(panel["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "shortBylineText"))
                    .ifBlank { getText(panel["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "longBylineText")) }
                    .ifBlank { getText(panel["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(panel, "ownerName")) }
                if (a.isNotBlank()) {
                    metadata.author = a
                }
            }

            val metaRenderer = YoutubeUtils.getMapKeyFromAny(obj, "playlistMetadataRenderer")
            if (metaRenderer != null) {
                val t = YoutubeUtils.getStringFromAny(metaRenderer, "title") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "title"))
                if (!t.isNullOrBlank()) {
                    metadata.title = t
                }
                val d = YoutubeUtils.getStringFromAny(metaRenderer, "description") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "description"))
                if (!d.isNullOrBlank()) metadata.description = d
            }

            val hasPlaylistId = obj.containsKey("playlistId")
            val hasTitleInfo = obj.containsKey("titleText") || obj.containsKey("title")
            if (hasPlaylistId && hasTitleInfo) {
                val t = getText(obj["titleText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "titleText"))
                    .ifBlank { getText(obj["title"] ?: YoutubeUtils.getMapKeyFromAny(obj, "title")) }
                if (t.isNotBlank() && metadata.title.isBlank()) {
                    metadata.title = t
                }
                val a = getText(obj["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "shortBylineText"))
                    .ifBlank { getText(obj["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "longBylineText")) }
                    .ifBlank { getText(obj["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(obj, "ownerName")) }
                if (a.isNotBlank() && metadata.author.isBlank()) {
                    metadata.author = a
                }
            }

            val ownerRenderer = obj["videoOwnerRenderer"] as? Map<*, *>
                ?: YoutubeUtils.getMapKeyFromAny(obj, "videoOwnerRenderer")
            if (ownerRenderer != null) {
                val sub = getText(ownerRenderer["subscriberCountText"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "subscriberCountText"))
                if (sub.isNotBlank()) metadata.subscriberCount = sub
                
                val avatar = YoutubeUtils.getBestThumbnail(ownerRenderer["thumbnail"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "thumbnail"))
                if (!avatar.isNullOrBlank()) metadata.channelAvatar = avatar
                
                val browseId = YoutubeUtils.getMapKeyFromAny(ownerRenderer, "navigationEndpoint")
                    ?.let { YoutubeUtils.getMapKeyFromAny(it, "browseEndpoint") }
                    ?.let { YoutubeUtils.getStringFromAny(it, "browseId") }
                if (!browseId.isNullOrBlank()) metadata.channelId = browseId
                
                val vAuth = getText(ownerRenderer["title"] ?: YoutubeUtils.getMapKeyFromAny(ownerRenderer, "title"))
                if (vAuth.isNotBlank()) {
                    metadata.videoAuthor = vAuth
                }
            }

            for (value in obj.values) {
                findPlaylistMetadataRecursive(value, metadata, depth + 1)
            }
        } else if (obj is List<*>) {
            for (item in obj) {
                findPlaylistMetadataRecursive(item, metadata, depth + 1)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse {
        if (url.contains("/shorts/")) {
            val videoId = YoutubeUtils.extractYoutubeId(url) ?: "video"
            val useSearchList = url.contains("ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            var targetEpisodes = synchronized(sourceList) { sourceList.toMutableList() }
            val currentEp = targetEpisodes.find { YoutubeUtils.extractYoutubeId(it.data) == videoId }

            var shortTitle = currentEp?.name ?: "Shorts Video"
            val poster = YoutubeUtils.getShortsSearchPoster(videoId)
            var shortPlot = currentEp?.description ?: ""

            if (currentEp == null || targetEpisodes.size <= 1) {
                try {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    
                    val playerUrl = "https://www.youtube.com/youtubei/v1/player?key=$apiKey"
                    val playerPayload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB", 
                                "clientVersion" to cVersion, 
                                "hl" to lang
                            )
                        ),
                        "videoId" to videoId
                    )
                    val pResponse = app.post(playerUrl, json = playerPayload, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (pResponse != null) {
                        val details = pResponse["videoDetails"] as? Map<*, *>
                        val fetchedTitle = details?.getString("title")
                        val channelName = details?.getString("author")
                        if (!fetchedTitle.isNullOrBlank()) {
                            shortTitle = fetchedTitle
                            shortPlot = if (!channelName.isNullOrBlank()) "Channel: $channelName" else ""
                        }
                    }

                    val nextUrl = "https://www.youtube.com/youtubei/v1/next?key=$apiKey"
                    val nextPayload = mapOf(
                        "context" to mapOf(
                            "client" to mapOf(
                                "clientName" to "WEB", 
                                "clientVersion" to cVersion, 
                                "hl" to lang
                            )
                        ),
                        "videoId" to videoId
                    )
                    val nResponse = app.post(nextUrl, json = nextPayload, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    val recEpisodes = mutableListOf<Episode>()
                    
                    recEpisodes.add(newEpisode(url) {
                        this.name = shortTitle
                        this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                        this.episode = 1
                        this.description = shortPlot
                    })

                    if (nResponse != null) {
                        val recs = mutableListOf<SearchResponse>()
                        val seenRecIds = mutableSetOf<String>().apply { add(videoId) }
                        val secondaryResults = safeGet(nResponse, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
                        if (secondaryResults != null) {
                            processRecursive(secondaryResults, recs, seenRecIds, false)
                        }
                        
                        var epCount = 2
                        recs.forEach { rec ->
                            if (rec.url.contains("watch?v=") || rec.url.contains("/shorts/")) {
                                val recVidId = YoutubeUtils.extractYoutubeId(rec.url)
                                val cleanName = rec.name.replaceFirst(Regex("^\\{.*?\\}\\s*"), "")
                                val recUrl = "$mainUrl/shorts/$recVidId?ctx=home"
                                recEpisodes.add(newEpisode(recUrl) {
                                    this.name = cleanName
                                    this.posterUrl = recVidId?.let { YoutubeUtils.getShortsEpisodeThumbnail(it) } ?: rec.posterUrl
                                    this.episode = epCount++
                                })
                            }
                        }
                    }
                    targetEpisodes = recEpisodes
                } catch (e: Exception) {
                    logError(e)
                }
            } else {
                targetEpisodes.forEach { ep ->
                    val epVidId = YoutubeUtils.extractYoutubeId(ep.data)
                    if (epVidId != null) {
                        ep.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(epVidId)
                    }
                }
            }

            val finalPlot = buildString {
                append(shortTitle)
                if (shortPlot.isNotBlank()) {
                    append("\n\n$shortPlot")
                }
            }

            return newTvSeriesLoadResponse(shortTitle, url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = finalPlot
                this.tags = listOf("Shorts")
            }
        }

        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            try {
                var cleanUrl = url.substringBefore("?").removeSuffix("/")
                val tabKeywords = listOf("/featured", "/shorts", "/streams", "/playlists", "/community", "/about", "/videos")
                for (keyword in tabKeywords) {
                    if (cleanUrl.endsWith(keyword)) {
                        cleanUrl = cleanUrl.removeSuffix(keyword)
                        break
                    }
                }
                val channelUrl = "$cleanUrl/videos"
                var response = app.get(channelUrl, interceptor = ytInterceptor)
                var html = response.text
                var data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val tabsCheck = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                val activeTab = tabsCheck?.firstOrNull { tab ->
                    safeGet(tab, "tabRenderer", "selected") == true
                }
                
                val endpointUrl = safeGet(activeTab, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") as? String

                if (endpointUrl != null && !endpointUrl.endsWith("/videos")) {
                    val canonicalUrl = response.document.selectFirst("link[rel=canonical]")?.attr("href")
                    if (!canonicalUrl.isNullOrBlank()) {
                        val cleanCanonical = canonicalUrl.substringBefore("?").removeSuffix("/")
                        if (!cleanCanonical.endsWith("/videos")) {
                            val targetUrl = "$cleanCanonical/videos"
                            response = app.get(targetUrl, interceptor = ytInterceptor)
                            html = response.text
                            val newData = extractYtInitialData(html)
                            if (newData != null) {
                                data = newData
                            }
                        }
                    }
                }

                val apiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                val visitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")

                val header = safeGet(data, "header", "c4TabbedHeaderRenderer")
                    ?: safeGet(data, "header", "pageHeaderRenderer")

                val title = extractTitle(safeGet(header, "title") as? Map<*, *>)
                    ?: extractTitle(safeGet(header, "pageTitle") as? Map<*, *>)
                    ?: safeGet(header, "content", "pageHeaderViewModel", "title", "dynamicTextViewModel", "text", "content") as? String
                    ?: response.document.selectFirst("meta[property=og:title]")?.attr("content")
                    ?: "YouTube Channel"

                val poster = YoutubeUtils.getBestThumbnail(safeGet(header, "avatar"))
                    ?: YoutubeUtils.getBestThumbnail(safeGet(header, "content", "pageHeaderViewModel", "image", "decoratedAvatarViewModel", "avatar", "avatarViewModel", "image"))
                    ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                var subscriberCount = getText(safeGet(header, "subscriberCountText"))
                if (subscriberCount.isBlank()) {
                    val rows = safeGet(header, "content", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                        ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                    
                    rows?.forEach { row ->
                        val parts = safeGet(row, "metadataParts") as? List<*>
                        parts?.forEach { part ->
                            val text = getText(safeGet(part, "text"))
                            if (text.contains("subscribers", ignoreCase = true) || text.contains("구독자", ignoreCase = true)) {
                                subscriberCount = text
                            }
                        }
                    }
                }
                subscriberCount = subscriberCount.replace(Regex("(subscribers?|구독자)\\s*", RegexOption.IGNORE_CASE), "").trim()
                if (subscriberCount.isBlank()) subscriberCount = "N/A"

                val allEpisodes = mutableListOf<Episode>()

                fun findContinuationItemsRecursive(obj: Any?, depth: Int = 0): List<*>? {
                    if (obj == null || depth > 32) return null
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands", "onResponseReceivedResults", "continuationContents")
                            val keysToTrySize = keysToTry.size
                            for (i in 0 until keysToTrySize) {
                                val k = keysToTry[i]
                                val v = obj[k]
                                val r = findContinuationItemsRecursive(v, depth + 1)
                                if (r != null) return r
                            }
                            for (v in obj.values) {
                                val r = findContinuationItemsRecursive(v, depth + 1)
                                if (r != null) return r
                            }
                        }
                        is List<*> -> {
                            for (i in obj) {
                                val r = findContinuationItemsRecursive(i, depth + 1)
                                if (r != null) return r
                            }
                        }
                    }
                    return null
                }

                fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>) {
                    
                    fun findAllRenderers(obj: Any?, results: MutableList<Map<*,*>>, depth: Int = 0) {
                        if (obj == null || depth > 32) return
                        when (obj) {
                            is Map<*, *> -> {
                                for ((k, v) in obj) {
                                    if (k is String && TARGET_RENDERER_KEYS.contains(k)) {
                                        val match = v as? Map<*,*>
                                        if (match != null) results.add(match)
                                    }
                                }
                                for (value in obj.values) {
                                    findAllRenderers(value, results, depth + 1)
                                }
                            }
                            is List<*> -> {
                                for (item in obj) {
                                    findAllRenderers(item, results, depth + 1)
                                }
                            }
                        }
                    }

                    items.forEachIndexed { index, item ->
                        try {
                            val map = item as? Map<*, *>
                            if (map == null) {
                                return@forEachIndexed
                            }

                            if (map.containsKey("continuationItemRenderer")) {
                                return@forEachIndexed
                            }

                            val foundRenderers = mutableListOf<Map<*,*>>()
                            findAllRenderers(map, foundRenderers)

                            if (foundRenderers.isEmpty()) {
                                return@forEachIndexed
                            }

                            for (renderer in foundRenderers) {
                                var vidId = renderer["videoId"] as? String
                                
                                if (vidId.isNullOrBlank() && renderer.containsKey("contentId")) {
                                    vidId = renderer["contentId"] as? String
                                }
                                if (vidId.isNullOrBlank()) {
                                    vidId = safeGet(renderer, "onTap", "innertubeCommand", "reelWatchEndpoint", "videoId") as? String
                                }

                                if (!vidId.isNullOrBlank()) {
                                    var vidTitle = getText(renderer["title"])
                                    if (vidTitle.isBlank()) vidTitle = getText(renderer["headline"])
                                    if (vidTitle.isBlank()) vidTitle = getText(renderer["shortBylineText"])
                                    if (vidTitle.isBlank()) vidTitle = getText(safeGet(renderer, "metadata", "lockupMetadataViewModel", "title"))
                                    if (vidTitle.isBlank()) vidTitle = "Video"

                                    val thumb = YoutubeUtils.getBestThumbnail(renderer["thumbnail"]) 
                                        ?: YoutubeUtils.getBestThumbnail(safeGet(renderer, "contentImage", "image", "sources")) 
                                        ?: YoutubeUtils.buildThumbnailFromId(vidId)

                                    collectTo.add(newEpisode("$mainUrl/watch?v=$vidId") {
                                        this.name = vidTitle
                                        this.posterUrl = thumb
                                        this.description = getEpisodeMetaString(renderer)
                                    })
                                    
                                    break 
                                }
                            }
                        } catch (e: Exception) {
                        }
                    }
                }

                var initialItems: List<*>? = null
                val tabs = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                if (tabs != null) {
                    val actualActiveTab = tabs.firstOrNull { tab ->
                        safeGet(tab, "tabRenderer", "selected") == true
                    }

                    val activeContent = safeGet(actualActiveTab, "tabRenderer", "content") as? Map<*, *>

                    if (activeContent?.containsKey("richGridRenderer") == true) {
                        initialItems = safeGet(activeContent, "richGridRenderer", "contents") as? List<*>
                    } else if (activeContent?.containsKey("gridRenderer") == true) {
                        initialItems = safeGet(activeContent, "gridRenderer", "items") as? List<*>
                    }

                    if (initialItems == null) {
                        for (tab in tabs) {
                            val tabMap = tab as? Map<*, *>
                            val tabRenderer = tabMap?.get("tabRenderer") as? Map<*, *>
                            val tabContent = tabRenderer?.get("content") as? Map<*, *>
                            if (tabContent?.containsKey("richGridRenderer") == true) {
                                initialItems = safeGet(tabContent, "richGridRenderer", "contents") as? List<*>
                                break
                            }
                            if (tabContent?.containsKey("gridRenderer") == true) {
                                initialItems = safeGet(tabContent, "gridRenderer", "items") as? List<*>
                                break
                            }
                        }
                    }
                }
                
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                fun findContinuationFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = findTokenRecursive(m)
                        if (!token.isNullOrBlank()) return token
                    }
                    return null
                }

                var currentToken: String? = findContinuationFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationFromItems(conts)
                }

                var pagesFetchedLocal = 1
                val maxPagesLimit = appPrefs?.getInt("channel_pages_limit", 36) ?: 36

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPagesLimit && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "hl" to lang,
                                    "gl" to "US",
                                    "visitorData" to (visitorData ?: ""),
                                    "platform" to "DESKTOP"
                                )
                            ),
                            "continuation" to currentToken
                        )
                        val headers = mapOf("X-Youtube-Client-Name" to "WEB", "X-Youtube-Client-Version" to clientVersion)
                        val jsonResponse = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>() ?: break
                        val continuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                        extractVideosFromItems(continuationItems, allEpisodes)
                        currentToken = findContinuationFromItems(continuationItems)
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {
                logError(e)
            }
        }

        if (url.contains("list=")) {
            try {
                var targetUrl = url
                if (url.contains("playlist?list=")) {
                    val listId = url.substringAfter("list=").substringBefore("&")
                    if (listId.startsWith("RD") || listId.startsWith("UL") || listId.startsWith("MM") || listId.startsWith("RDMM")) {
                        targetUrl = "$mainUrl/watch?list=$listId"
                    }
                }

                val response = app.get(targetUrl, interceptor = ytInterceptor)
                val html = response.text
                
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")

                val playlistMeta = PlaylistMetadata()
                findPlaylistMetadataRecursive(data, playlistMeta)

                var author = playlistMeta.videoAuthor.ifBlank { playlistMeta.author }.ifBlank { "Unknown Channel" }
                var title = playlistMeta.title.ifBlank { "YouTube Playlist" }
                var description = playlistMeta.description

                val seenPlaylistIds = mutableSetOf<String>()
                val episodes = extractPlaylistEpisodes(data, seenPlaylistIds).toMutableList()

                val isMix = url.contains("list=RD") || url.contains("list=MM") || url.contains("list=UL")
                if (isMix) {
                    val firstChannel = episodes.firstOrNull()?.description?.split("•")?.getOrNull(1)?.trim()
                    if (!firstChannel.isNullOrBlank()) {
                        title = "Mix - $firstChannel"
                    } else if (title.contains("YouTube Mix") || title.contains("Mix")) {
                        title = "YouTube Mix"
                    }
                }

                title = YoutubeUtils.cleanMixTitle(title)

                episodes.forEachIndexed { index, ep ->
                    ep.episode = index + 1
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                val recList = mutableListOf<SearchResponse>()
                if (author.isNotBlank() && playlistMeta.channelId.isNotBlank()) {
                    val cId = playlistMeta.channelId
                    val channelUrlFull = if (cId.startsWith("UC") || cId.startsWith("@")) "$mainUrl/channel/$cId" else "$mainUrl/$cId"
                    val channelCard = newMovieSearchResponse(
                        "Channel: $author",
                        channelUrlFull,
                        TvType.Live
                    ) {
                        this.posterUrl = playlistMeta.channelAvatar
                    }
                    recList.add(channelCard)
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(playlistPoster)
                    
                    val finalDescription = buildString {
                        append("Channel: $author")
                        if (playlistMeta.subscriberCount.isNotBlank()) {
                            append(" (${playlistMeta.subscriberCount})")
                        }
                        if (description.isNotBlank()) {
                            append("\n\n$description")
                        }
                    }
                    this.plot = finalDescription
                    this.tags = listOf(author)
                    this.recommendations = recList
                }
            } catch (e: Exception) {
                logError(e)
            }
        }          

        val videoId = YoutubeUtils.extractYoutubeId(url) ?: throw ErrorLoadingException("Invalid YouTube URL")

        epMetaMapCache.clear()

        val headers = mapOf("Referer" to "https://www.youtube.com/")
        val response = app.get(url, headers = headers, interceptor = ytInterceptor)
        val html = response.text
        val data = extractYtInitialData(html)
        
        var title = "YouTube Video"
        var plot = ""
        var poster = YoutubeUtils.buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()

        if (data != null) {
            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t

                    val dateText = extractTitle(primary["dateText"] as? Map<*, *>)
                    if (!dateText.isNullOrBlank()) plot += "$dateText\n\n"
                }

                val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                if (secondary != null) {
                    val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                    if (owner != null) {
                        channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                        channelAvatar = YoutubeUtils.getBestThumbnail(owner["thumbnail"]) ?: ""
                        channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                        if (channelId.isEmpty()) {
                            val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                            if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                        }
                    }

                    val descObj = secondary["attributedDescription"] as? Map<*, *>
                        ?: secondary["description"] as? Map<*, *>

                    val fullDesc = getText(descObj)
                    if (fullDesc.isNotBlank()) {
                        plot += fullDesc
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false)
            }

        } else {
            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
            plot = doc.selectFirst("meta[property=og:description]")?.attr("content") ?: plot
        }

        if (channelName.isNotBlank() && channelId.isNotBlank()) {
            val channelUrlFull = if (channelId.startsWith("UC") || channelId.startsWith("@")) "$mainUrl/channel/$channelId" else "$mainUrl/$channelId"

            val channelCard = newMovieSearchResponse(
                "Channel: $channelName",
                channelUrlFull,
                TvType.Live
            ) {
                this.posterUrl = channelAvatar
            }

            recommendations.add(0, channelCard)
        }

        val filteredRecs = recommendations.filter { !it.url.contains("/shorts/") }

        val episodes = mutableListOf<Episode>()
        
        var ep1Dur = ""
        val lengthMatch = LENGTH_SECONDS_REGEX.find(html)
        if (lengthMatch != null) {
            try {
                val s = lengthMatch.groupValues[1].toLong()
                ep1Dur = if (s >= 3600) String.format("%d:%02d:%02d", s/3600, (s%3600)/60, s%60) else String.format("%d:%02d", s/60, s%60)
            } catch (e: Exception) {}
        }
        
        var ep1Views = ""
        val viewMatch = VIEW_COUNT_REGEX.find(html)
        if (viewMatch != null) {
            ep1Views = viewMatch.groupValues[1]
        }
        
        var ep1Date = ""
        val dateMatch = PUBLISH_DATE_REGEX.find(html)
        if (dateMatch != null) {
            ep1Date = dateMatch.groupValues[1]
        }
        
        val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>
        resultsContents?.forEach { item ->
            val m = item as? Map<*, *>
            val primary = m?.getMapKey("videoPrimaryInfoRenderer")
            if (primary != null) {
                val viewCountRenderer = primary.getMapKey("videoViewCountRenderer")
                val vCount = if (viewCountRenderer != null) {
                    getText(viewCountRenderer.getMapKey("extraShortViewCount") ?: viewCountRenderer.getMapKey("viewCount"))
                } else {
                    getText(primary.getMapKey("viewCount"))
                }
                
                // 💡 [순정 직결]: extraShortViewCount 또는 순정 뷰카운트 값을 그대로 직접 매핑
                if (vCount.isNotBlank()) ep1Views = vCount
                val dText = getText(primary.getMapKey("dateText"))
                if (dText.isNotBlank()) ep1Date = dText
            }
        }

        val mainDescParts = listOf(ep1Dur, channelName, ep1Views, ep1Date).filter { it.isNotBlank() && it != "N/A" }
        val mainDescStr = mainDescParts.joinToString(" • ")

        episodes.add(newEpisode(url) {
            this.name = title
            this.posterUrl = poster
            this.episode = 1
            this.description = mainDescStr.ifBlank { channelName.ifBlank { "Playing" } }
        })

        var epCount = 2
        filteredRecs.forEach { rec ->
            if (rec.url.contains("watch?v=") || rec.url.contains("/shorts/")) {
                val recVidId = YoutubeUtils.extractYoutubeId(rec.url)
                val extraMeta = if (recVidId != null) epMetaMapCache[recVidId] else ""
                
                var cleanName = rec.name.replaceFirst(Regex("^\\{.*?\\}\\s*"), "")

                episodes.add(newEpisode(rec.url) {
                    this.name = cleanName
                    this.posterUrl = rec.posterUrl
                    this.episode = epCount++
                    if (!extraMeta.isNullOrBlank()) {
                        this.description = extraMeta
                    }
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
            this.plot = plot

            if (channelName.isNotBlank()) {
                this.tags = listOf(channelName)
            }

            this.recommendations = filteredRecs 
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = YoutubeUtils.extractYoutubeId(data) ?: data
        val fullUrl = "https://www.youtube.com/watch?v=$videoId"
        val isShort = data.contains("/shorts/")

        var playerResponseJson: String? = null
        try {
            val htmlUrl = if (isShort) "$mainUrl/shorts/$videoId" else fullUrl
            val htmlResponse = app.get(htmlUrl, interceptor = ytInterceptor).text
            
            val marker1 = "ytInitialPlayerResponse = "
            var startIdx = htmlResponse.indexOf(marker1)
            if (startIdx != -1) {
                playerResponseJson = extractBalancedJson(htmlResponse.substring(startIdx + marker1.length))
            }
            
            if (playerResponseJson == null) {
                val marker2 = "var ytInitialPlayerResponse = "
                startIdx = htmlResponse.indexOf(marker2)
                if (startIdx != -1) {
                    playerResponseJson = extractBalancedJson(htmlResponse.substring(startIdx + marker2.length))
                }
            }
            
            if (playerResponseJson == null) {
                val androidApiKey = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
                val apiUrl = "https://www.youtube.com/player?key=$androidApiKey"
                val payload = mapOf(
                    "videoId" to videoId,
                    "context" to mapOf(
                        "client" to mapOf("clientName" to "ANDROID_VR", "clientVersion" to "1.65.10", "hl" to lang)
                    )
                )
                val vrHeaders = mapOf(
                    "User-Agent" to YoutubeUtils.ANDROID_VR_USER_AGENT,
                    "X-YouTube-Client-Name" to "28",
                    "X-YouTube-Client-Version" to "1.65.10",
                    "Origin" to "https://www.youtube.com"
                )
                playerResponseJson = app.post(apiUrl, json = payload, headers = vrHeaders).text
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (!playerResponseJson.isNullOrBlank()) {
            try {
                val root = org.json.JSONObject(playerResponseJson)
                val targetJson = if (root.has("playerResponse")) {
                    root.getJSONObject("playerResponse").toString()
                } else {
                    playerResponseJson
                }
                val playerResponse = mapper.readValue<PlayerResponse>(targetJson)
                
                triggerPlaybackTracking(playerResponse.playbackTracking, videoId, isShort)
            } catch (e: Exception) {
                logError(e)
            }
        }

        com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, callback)

        return true
    }
}
