package com.lagradost.cloudstream3.ar.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.mvvm.logError
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import okhttp3.Interceptor
import okhttp3.Response
import java.net.URLEncoder
import java.net.URLDecoder
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern
import com.lagradost.cloudstream3.AcraApplication
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.ar.youtube.YoutubeProvider.Config.SLEEP_BETWEEN
import java.util.concurrent.ConcurrentHashMap

object YoutubeUtils {
    const val CHROME_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Safari/537.36"
    const val SAFARI_USER_AGENT = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Safari/605.1.15"
    const val IOS_USER_AGENT = "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5_1 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
    const val DEFAULT_CLIENT_VERSION = "2.20240725.01.00"

    private val sha1Digest = ThreadLocal.withInitial {
        MessageDigest.getInstance("SHA-1")
    }

    val YT_ID_REGEX = Regex("""(?:v=|\/videos\/|embed\/|youtu\.be\/|shorts\/)([A-Za-z0-9_-]{11})""")
    val MIX_TITLE_REPLACE_KO = Regex("""\s*\(믹스\s*재생목록\)""", RegexOption.IGNORE_CASE)
    val MIX_TITLE_REPLACE_EN = Regex("""\s*\(Mix\s*Playlist\)""", RegexOption.IGNORE_CASE)

    // 동적 컴파일 Regex 부하를 줄이기 위한 내부 캐시
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
            val authKeys = listOf("SID", "HSID", "SSID", "APISID", "SAPISID")
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
            "https://wsrv.nl/?url=${URLEncoder.encode(poster, "UTF-8")}&w=300&h=450&fit=contain&cbg=black"
        } catch (e: Exception) {
            poster
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
        return MIX_TITLE_REPLACE_EN.replace(MIX_TITLE_REPLACE_KO.replace(title, ""), "").trim()
    }

    fun urlDecode(input: String): String {
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
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? Map<*, *>
    }

    fun getListKeyFromAny(obj: Any?, key: String): List<*>? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? List<*>
    }

    fun getStringFromAny(obj: Any?, key: String): String? {
        val map = obj as? Map<*, *> ?: return null
        val value = map[key] ?: map.entries.firstOrNull { (it.key as? String) == key }?.value
        return value as? String ?: value?.toString()
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

    object Config {
        const val SLEEP_BETWEEN = 1
    }
    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    
    override var lang: String
        get() = appPrefs?.getString("youtube_language", "ko") ?: "ko"
        set(value) {}
        
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    companion object {
        private val homeShorts = mutableListOf<Episode>()
        private val searchShorts = mutableListOf<Episode>()

        // SharedPreferences 연속 I/O 병목을 해결하기 위한 인메모리 캐시 맵
        private val continuationTokenCache = ConcurrentHashMap<String, String>()

        private val VIEW_REPLACE_REGEX = Regex("조회수|views|مشاهدة|시청 중|시청중|watching|명|시청자")
        private val VIEW_FORMAT_REGEX = Regex(".*[천만억KMBkmb].*")
        private val METADATA_VIEW_REGEX = Regex(".*(\\d+[KMBkmb]|views|조회수|회|시청|watching|مشاهدة|명|시청자|مشاهد).*")
        private val METADATA_SKIP_REGEX = Regex(".*(\\d+:\\d+|ago|قبل|전|시간|분|일|주|개월|년|시청|watching).*")
        private val SUBSCRIBER_REGEX = Regex(".*(구독자|subscribers|명|K|M|B|sub).*\\s*", RegexOption.IGNORE_CASE)
        private val SUBSCRIBER_REPLACE_REGEX = Regex("(구독자|subscribers?)\\s*", RegexOption.IGNORE_CASE)
        private val API_KEY_EXTRACT_REGEX = Regex(""""INNERTUBE_API_KEY":"([^"]+)"""")
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
    private var savedVisitorData: String? = null
    private var savedApiKey: String? = null
    private var savedClientVersion: String? = null

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

    private suspend fun triggerPlaybackTracking(playbackTracking: PlaybackTracking?, videoId: String) {
        val playbackBaseUrl = playbackTracking?.videostatsPlaybackUrl?.baseUrl
        val watchtimeBaseUrl = playbackTracking?.videostatsWatchtimeUrl?.baseUrl

        if (playbackBaseUrl.isNullOrBlank() || watchtimeBaseUrl.isNullOrBlank()) {
            println("[YT_DEBUG] playbackTracking base URLs are empty, skipping tracking")
            return
        }

        val cpn = generateCpn()
        println("[YT_DEBUG] Triggering playback tracking for videoId: $videoId with cpn: $cpn")

        try {
            val playbackUrl = buildTrackingUrl(playbackBaseUrl, cpn, cmt = "0.000", state = null, realTime = "0")
            println("[YT_DEBUG] Pinging playbackUrl: $playbackUrl")
            app.get(playbackUrl, interceptor = ytInterceptor)

            val watchtimeUrl = buildTrackingUrl(watchtimeBaseUrl, cpn, cmt = "5.000", state = "playing", realTime = "5")
            println("[YT_DEBUG] Pinging watchtimeUrl: $watchtimeUrl")
            app.get(watchtimeUrl, interceptor = ytInterceptor)
            
            println("[YT_DEBUG] Playback tracking successfully triggered and watch history synced.")
        } catch (e: Exception) {
            println("[YT_DEBUG] Failed to trigger playback tracking: ${e.message}")
            logError(e)
        }
    }
    
    private fun getSavedContinuationToken(key: String): String? {
        val hashedKey = YoutubeUtils.sha1(key)
        val cached = continuationTokenCache[hashedKey]
        if (cached != null) return cached
        
        val token = appPrefs?.getString("cont_token_$hashedKey", null)
        if (token != null) {
            continuationTokenCache[hashedKey] = token
        }
        println("[YT_DEBUG] getSavedContinuationToken for key '$key' (hash: $hashedKey) -> '$token'")
        return token
    }

    private fun saveContinuationToken(key: String, token: String?) {
        val hashedKey = YoutubeUtils.sha1(key)
        println("[YT_DEBUG] saveContinuationToken for key '$key' (hash: $hashedKey) -> '$token'")
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

    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? {
        return (this[key] as? Map<*, *>) 
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? Map<*, *>)
    }

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? {
        return (this[key] as? List<Map<*, *>>)
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? List<Map<*, *>>)
    }

    private fun Map<*, *>.getString(key: String): String? {
        return (this[key] as? String)
            ?: (this.entries.firstOrNull { (it.key as? String) == key }?.value as? String)
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

    private fun extractPartsFromMetadataRows(rows: List<*>?, viewFormatter: (String) -> String): List<String> {
        val collected = mutableListOf<String>()
        rows?.forEach { row ->
            val rowMap = row as? Map<*, *> ?: return@forEach
            val parts = (rowMap["metadataParts"] as? List<*>)
                ?: (rowMap.entries.firstOrNull { (it.key as? String) == "metadataParts" }?.value as? List<*>)
            parts?.forEach { part ->
                val partMap = part as? Map<*, *> ?: return@forEach
                val text = getText(partMap.getMapKey("text") ?: partMap["text"])
                if (text.isNotBlank()) {
                    if (METADATA_VIEW_REGEX.matches(text)) {
                        collected.add(viewFormatter(text))
                    } else {
                        collected.add(text)
                    }
                }
            }
        }
        return collected
    }

    private fun extractLockupMetadata(lockup: Map<*, *>) : Pair<String, String> {
        var channel = ""
        var views = ""

        try {
            val rows = lockup.getMapKey("metadata")
                ?.getMapKey("lockupMetadataViewModel")
                ?.getMapKey("metadata")
                ?.getMapKey("contentMetadataViewModel")
                ?.getListKey("metadataRows")

            val rawParts = extractPartsFromMetadataRows(rows) { formatViews(it) }
            rawParts.forEach { text ->
                if (text.contains("회") || text.contains("시청") || text.contains("조회") || 
                    text.contains("views") || text.contains("watching") || 
                    text.contains("명") || text.contains("시청자") || text.contains("مشاهد")) {
                    views = text
                } else if (!METADATA_SKIP_REGEX.matches(text) && text.length > 1 && !text.contains("•")) {
                    channel = text
                }
            }
        } catch (_: Exception) {
        }

        if (views.isEmpty() || views == "N/A") {
            val direct = getText(
                lockup.getMapKey("metadata")?.getMapKey("lockupMetadataViewModel")
                    ?.getMapKey("viewCount")
            )
            if (direct.isNotBlank()) views = formatViews(direct)
        }

        return Pair(channel, views)
    }

    private fun formatViews(viewText: String?): String {
        if (viewText.isNullOrBlank()) return "N/A"
        var text = viewText.trim()
        
        val isLive = text.contains("명") || text.contains("시청자") || text.contains("watching") || text.contains("시청 중")

        text = VIEW_REPLACE_REGEX.replace(text, "").trim()

        if (VIEW_FORMAT_REGEX.matches(text) && text.length < 15) {
            return if (isLive) {
                if (lang == "ko" && !text.contains("명")) "${text}명" 
                else if (lang != "ko" && !text.contains("watching")) "${text} watching"
                else text
            } else {
                if (lang == "ko" && !text.contains("회")) "${text}회" 
                else text
            }
        }
        
        val sb = java.lang.StringBuilder(text.length)
        for (i in 0 until text.length) {
            val c = text[i]
            if (c in '0'..'9') sb.append(c)
        }
        val digits = sb.toString()
        if (digits.isBlank()) return text
        
        return try {
            val v = digits.toLong()
            if (lang == "ko") {
                val suffix = if (isLive) { "명" } else { "회" }
                when {
                    v < 1000 -> "$v$suffix"
                    v < 10000 -> String.format("%.1f천$suffix", v / 1000.0).replace(".0천", "천")
                    v < 100000 -> String.format("%.1f만$suffix", v / 10000.0).replace(".0만", "만")
                    v < 100000000 -> "${v / 10000}만$suffix"
                    else -> String.format("%.1f억$suffix", v / 100000000.0).replace(".0억", "억")
                }
            } else {
                val suffix = if (isLive) " watching" else ""
                when {
                    v < 1000 -> "$v$suffix"
                    v < 1_000_000 -> String.format("%.1fK$suffix", v / 1000.0).replace(".0K", "K")
                    v < 1_000_000_000 -> String.format("%.1fM$suffix", v / 1_000_000.0).replace(".0M", "M")
                    else -> String.format("%.1fB$suffix", v / 1_000_000_000.0).replace(".0B", "B")
                }
            }
        } catch (e: Exception) {
            text
        }
    }

    private fun getRawText(map: Map<*, *>?, key: String): String? {
        val obj = map?.getMapKey(key) ?: return null
        return obj.getString("simpleText")
            ?: obj.getListKey("runs")?.firstOrNull()?.getString("text")
    }

    private fun collectFromRenderer(
        renderer: Map<*, *>?,
        seenIds: MutableSet<String>
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
                val title = getText(videoData.getMapKey("title")) ?: "Video"
                val viewText = getText(videoData.getMapKey("viewCountText"))
                    ?: getText(videoData.getMapKey("shortViewCountText"))
                val channel = getText(videoData.getMapKey("ownerText"))
                    ?: getText(videoData.getMapKey("shortBylineText")) ?: ""
                val views = formatViews(viewText)
                val finalTitle =
                    if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                var poster = YoutubeUtils.getBestThumbnail(videoData.getMapKey("thumbnail"))
                if (poster.isNullOrBlank()) poster = YoutubeUtils.buildThumbnailFromId(videoId)
                
                println("[YT_DEBUG] collectFromRenderer: extracted Video $videoId: $finalTitle")
                return newMovieSearchResponse(
                    finalTitle,
                    "$mainUrl/watch?v=$videoId",
                    TvType.Movie
                ) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
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
                val views = formatViews(viewRaw)

                var poster =
                    shortsData.getMapKey("thumbnail")?.getListKey("thumbnails")?.lastOrNull()
                        ?.getString("url")
                if (poster.isNullOrBlank()) poster =
                    shortsData.getMapKey("thumbnailViewModel")?.getMapKey("thumbnailViewModel")
                        ?.getMapKey("image")?.getListKey("sources")?.lastOrNull()?.getString("url")
                if (poster.isNullOrBlank()) poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"

                val currentList = if (isSearchContext) searchShorts else homeShorts
                val episodeNum = currentList.size + 1
                val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                val descText = if (views.isNotBlank()) "조회수: $views" else ""
                if (currentList.none { it.data == finalUrl }) {
                    currentList.add(newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = poster
                        this.episode = episodeNum
                        this.description = descText
                    })
                }

                val finalTitle = "#$episodeNum {Short | $views} $title"
                println("[YT_DEBUG] collectFromRenderer: extracted Shorts $videoId: $finalTitle")
                return newMovieSearchResponse(finalTitle, finalUrl, TvType.Movie) {
                    this.posterUrl = poster
                }
            }
        }

        val lockup = renderer.getMapKey("lockupViewModel")
        if (lockup != null) {
            val contentType = lockup.getString("contentType") ?: ""
            if (contentType.contains("AD") || contentType.contains("SPONSORED") || contentType.contains("PROMOTED")) {
                println("[YT_DEBUG] Skipping ad in lockupViewModel (contentType: $contentType)")
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

                    println("[YT_DEBUG] collectFromRenderer: extracted Playlist lockup $playlistId: $title (path: $targetPath, extractedUrl: $extractedUrl)")
                    return newTvSeriesSearchResponse(
                        if (episodeCount.isNotEmpty()) "$title ($episodeCount)" else title,
                        "$mainUrl/$targetPath",
                        TvType.TvSeries
                    ) {
                        this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
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
                    println("[YT_DEBUG] Skipping lockupViewModel $videoId due to empty title (likely an ad or empty placeholder)")
                    return null
                }
                var (channel, views) = extractLockupMetadata(lockup)     
                if (channel.isBlank()) {
                    val label = lockup.getMapKey("accessibility")?.getMapKey("accessibilityData")
                        ?.getString("label") ?: ""
                    val match =
                        Regex("(?:by|من|ع버|قناة)\\s+(.*?)\\s+(?:\\d|view|مشاهدة)").find(label)
                    if (match != null) channel = match.groupValues[1].replace("Shorts", "").trim()
                }
                val isShorts = lockup.getMapKey("content")
                    ?.containsKey("shortsLockupViewModel") == true || lockup.toString()
                    .contains("reelWatchEndpoint")
                val finalTitle: String
                val poster: String

                if (isShorts) {
                    val currentList = if (isSearchContext) searchShorts else homeShorts
                    val episodeNum = currentList.size + 1
                    val contextTag = if (isSearchContext) "&ctx=search" else "&ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    poster = "https://i.ytimg.com/vi/$videoId/oar2.jpg"
                    
                    val descText = buildString {
                        if (channel.isNotBlank()) append("채널: $channel")
                        if (views.isNotBlank()) {
                            if (isNotEmpty()) append(" • ")
                            append("조회수: $views")
                        }
                    }
                    if (currentList.none { it.data == finalUrl }) {
                        currentList.add(newEpisode(finalUrl) {
                            this.name = title
                            this.posterUrl = poster
                            this.episode = episodeNum
                            this.description = descText
                        })
                    }
                    finalTitle = "#$episodeNum [Shorts] {Short | $views} $title"
                    println("[YT_DEBUG] collectFromRenderer: extracted Shorts lockup $videoId: $finalTitle")
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle =
                        if (channel.isNotBlank()) "{$channel | $views} $title" else "{$views} $title"
                    poster = YoutubeUtils.getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    println("[YT_DEBUG] collectFromRenderer: extracted Video lockup $videoId: $finalTitle")
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) {
                        this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
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
                println("[YT_DEBUG] collectFromRenderer: extracted Channel $id: $title ($stats)")
                return newMovieSearchResponse(
                    "$title ($stats)",
                    "$mainUrl/channel/$id",
                    TvType.Live
                ) {
                    this.posterUrl = YoutubeUtils.getProxiedPoster(poster)
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
        depth: Int = 0
    ) {
        if (data == null || depth > 32) return // Stack Overflow 방지용 Depth 안전 보호막
        if (data is Map<*, *>) {
            if (depth < 3) {
                println("[YT_DEBUG] processRecursive Map keys at depth $depth: ${data.keys.take(10)}")
            }
            val extracted = collectFromRenderer(data, seenIds)
            if (extracted != null) {
                println("[YT_DEBUG] processRecursive successfully extracted item: ${extracted.name} (${extracted.url})")
                if (!playlistMode || extracted.type == TvType.TvSeries) {
                    outList.add(extracted)
                }
                return
            }

            val keysToCheck = listOf(
                "contents",
                "items",
                "continuationItems",
                "onResponseReceivedActions",
                "onResponseReceivedEndpoints",
                "appendContinuationItemsAction",
                "onResponseReceivedCommands",
                "gridShelfViewModel",
                "verticalListRenderer",
                "horizontalListRenderer",
                "shelfRenderer",
                "itemSectionRenderer",
                "richShelfRenderer",
                "reelShelfRenderer",
                "richGridRenderer"
            )
            var foundContainer = false
            for (key in keysToCheck) {
                if (data.containsKey(key)) {
                    if (depth < 3) {
                        println("[YT_DEBUG] processRecursive traversing key: '$key'")
                    }
                    processRecursive(data[key], outList, seenIds, playlistMode, depth + 1)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode, depth + 1)
                    }
                }
            }
        } else if (data is List<*>) {
            if (depth < 3) {
                println("[YT_DEBUG] processRecursive List size: ${data.size} at depth $depth")
            }
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode, depth + 1)
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
        println("[YT_DEBUG] extractYtInitialData started. html length: ${html.length}")
        var index = 0
        val htmlLen = html.length
        var matchCount = 0
        while (true) {
            val found = html.indexOf("ytInitialData", index)
            if (found == -1) {
                println("[YT_DEBUG] No more ytInitialData found. matchCount: $matchCount")
                break
            }
            matchCount++
            println("[YT_DEBUG] Found 'ytInitialData' keyword occurrence at index: $found")
            
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
                println("[YT_DEBUG] Found braceIndex: $braceIndex")
                if (braceIndex != -1 && braceIndex - found < 150) {
                    val possibleJson = html.substring(braceIndex)
                    println("[YT_DEBUG] Extracting balanced JSON starting at $braceIndex...")
                    val jsonString = extractBalancedJson(possibleJson)
                    if (jsonString != null) {
                        println("[YT_DEBUG] Balanced JSON extracted successfully. Length: ${jsonString.length}")
                        try {
                            val parsed = parseJson<Map<String, Any>>(jsonString)
                            println("[YT_DEBUG] JSON parsed successfully!")
                            return parsed
                        } catch (e: Exception) {
                            println("[YT_DEBUG] JSON parsing failed: ${e.message}")
                        }
                    } else {
                        println("[YT_DEBUG] Balanced JSON extraction returned null.")
                    }
                } else {
                    println("[YT_DEBUG] Brace index not found or too far from ytInitialData.")
                }
            } else {
                println("[YT_DEBUG] Context does not look like variable assignment.")
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
                    if (!token.isNullOrBlank()) return token
                }
            }

            val nextData = data["nextContinuationData"] as? Map<*, *>
            if (nextData != null) {
                val token = nextData["continuation"] as? String
                if (!token.isNullOrBlank()) return token
            }

            for ((k, v) in data) {
                val keyStr = k as? String ?: continue
                if (keyStr == "feedFilterChipBarRenderer" || 
                    keyStr == "chipCloudRenderer" || 
                    keyStr == "relatedChipCloudRenderer" ||
                    keyStr == "chipCloudChipRenderer") {
                    continue
                }
                val t = findTokenRecursive(v)
                if (t != null) return t
            }
        } else if (data is List<*>) {
            for (i in data) {
                val t = findTokenRecursive(i)
                if (t != null) return t
            }
        }
        return null
    }
    
    override val mainPage: List<MainPageData>
        get() {
            val list = mutableListOf<MainPageData>()
            val homeTitle = when(lang) {
                "ko" -> "홈"
                "ar" -> "الرئيسية"
                else -> "Home"
            }

            if (appPrefs?.getBoolean("show_trending_home", true) == true) {
                list.add(MainPageData(homeTitle, "Home", horizontalImages = true))
                
                val cachedChipsJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
                try {
                    val chips: List<CachedChip> = AppUtils.parseJson(cachedChipsJson)
                    chips.forEach { chip ->
                        list.add(MainPageData(chip.name, "chip_${chip.token}", horizontalImages = true))
                    }
                } catch (e: Exception) {
                    logError(e)
                }
            }

            val customSections = getCustomHomepages()
            customSections.filter { it.isEnabled && it.url != "Home" }.forEach { section ->
                var title = section.name
                if (title.isBlank()) title = extractNameFromUrl(section.url)
                list.add(MainPageData(title, section.url, horizontalImages = true))
            }

            return list
        }

    private fun getCustomHomepages(): List<CustomSection> {
        val json = appPrefs?.getString("custom_homepages_v3", "[]") ?: "[]"
        return try {
            AppUtils.parseJson<List<CustomSection>>(json)
        } catch (e: Exception) {
            emptyList()
        }
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

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        isSearchContext = false
        val rawRequestData = request.data
        val requestData = YoutubeUtils.urlDecode(rawRequestData)
        println("[YT_DEBUG] getMainPage started. page: $page, requestData: $requestData (raw: $rawRequestData)")
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            println("[YT_DEBUG] Page $page exceeds limits. Returning empty.")
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null
        val isChip = requestData.startsWith("chip_")

        try {
            if (page == 1) {
                if (isChip) {
                    savedApiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    savedVisitorData = savedVisitorData ?: appPrefs?.getString("yt_visitor_data", null)
                    savedClientVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION)
                    val tokenToUse = requestData.removePrefix("chip_")
                    println("[YT_DEBUG] Chip category detected. tokenToUse: $tokenToUse")

                    if (!savedApiKey.isNullOrBlank()) {
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
                        
                        println("[YT_DEBUG] POST Chip apiUrl: $apiUrl")
                        println("[YT_DEBUG] POST Chip payload: $payload")
                        val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                        val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        
                        if (response != null) {
                            println("[YT_DEBUG] Chip response success. Keys: ${response.keys}")
                            val actions = response["onResponseReceivedActions"] 
                                ?: response["onResponseReceivedEndpoints"]
                                ?: response["onResponseReceivedCommands"] 
                                ?: response
                            processRecursive(actions, results, seenIds, playlistMode = false)
                            nextContinuation = findTokenRecursive(response)
                            println("[YT_DEBUG] Next continuation extracted: $nextContinuation")
                        } else {
                            println("[YT_DEBUG] Chip response returned null.")
                        }
                    } else {
                        println("[YT_DEBUG] savedApiKey is null. Cannot query Chip API.")
                    }
                } else {
                    val targetUrl = if(requestData == "Home") mainUrl else requestData
                    println("[YT_DEBUG] Loading standard page. targetUrl: $targetUrl")
                    val html = app.get(targetUrl, interceptor = ytInterceptor).text
                    
                    savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
                    savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
                    savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: savedClientVersion ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    
                    println("[YT_DEBUG] Config from HTML: apiKey=$savedApiKey, visitorData=$savedVisitorData, clientVersion=$savedClientVersion")
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
                            println("[YT_DEBUG] Home page tabs chipBar found: ${chipBar != null}")
                            if (chipBar != null) {
                                val jsonArray = org.json.JSONArray()
                                for (chipItem in chipBar) {
                                    val chipMap = (chipItem as? Map<*, *>)?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
                                    val text = getText(chipMap["text"])
                                    val token = safeGet(chipMap, "navigationEndpoint", "continuationCommand", "token") as? String
                                    if (text.isNotBlank() && !token.isNullOrBlank()) {
                                        val obj = org.json.JSONObject()
                                        obj.put("name", text)
                                        obj.put("token", token)
                                        jsonArray.put(obj)
                                    }
                                }
                                if (jsonArray.length() > 0) {
                                    appPrefs?.edit()?.putString("yt_cached_chips", jsonArray.toString())?.apply()
                                }
                            }
                        }
                        processRecursive(initialData, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(initialData)
                        println("[YT_DEBUG] Next continuation from Home parsed: $nextContinuation")
                    } else {
                        println("[YT_DEBUG] InitialData extraction was null.")
                    }
                }
            } else {
                var tokenToUse = getSavedContinuationToken(requestData)
                
                if (tokenToUse.isNullOrBlank() && page == 2) { 
                    println("[YT_DEBUG] Token to use is null/blank on page $page for $requestData. Fetching fallback continuation...")
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION) ?: YoutubeUtils.DEFAULT_CLIENT_VERSION

                    if (isChip) {
                        val rawToken = requestData.removePrefix("chip_")
                        val apiUrl = "$mainUrl/youtubei/v1/browse?key=$apiKey"
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
                        val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                        val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                        if (response != null) {
                            tokenToUse = findTokenRecursive(response)
                            println("[YT_DEBUG] Fallback: Extracted next token from Chip page 1: $tokenToUse")
                            saveContinuationToken(requestData, tokenToUse)
                        }
                    } else {
                        val urlToGet = if(requestData == "Home") mainUrl else requestData
                        val html = app.get(urlToGet, interceptor = ytInterceptor).text
                        val initialData = extractYtInitialData(html)
                        if (initialData != null) {
                            tokenToUse = findTokenRecursive(initialData)
                            println("[YT_DEBUG] Fallback: Extracted next token from Home/Custom page 1 HTML: $tokenToUse")
                            saveContinuationToken(requestData, tokenToUse)
                        }
                    }
                }

                println("[YT_DEBUG] Loading page $page using token: $tokenToUse")
                if (!tokenToUse.isNullOrBlank()) {
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
                    println("[YT_DEBUG] POST Page Continuation URL: $apiUrl")
                    println("[YT_DEBUG] POST Page Continuation payload: $payload")
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        println("[YT_DEBUG] Page Continuation response success. Keys: ${response.keys}")
                        val actions = response["onResponseReceivedActions"] 
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response["continuationContents"] 
                            ?: response
                        processRecursive(actions, results, seenIds, playlistMode = false)
                        nextContinuation = findTokenRecursive(response)
                        println("[YT_DEBUG] Page Continuation next token: $nextContinuation")
                    } else {
                        println("[YT_DEBUG] Page Continuation response returned null.")
                    }
                } else {
                    println("[YT_DEBUG] Page Continuation token was null/blank.")
                }
            }
        } catch (e: Exception) { 
            println("[YT_DEBUG] getMainPage exception: ${e.message}")
            logError(e) 
        }

        saveContinuationToken(requestData, nextContinuation)
        println("[YT_DEBUG] getMainPage completed. Extracted count: ${results.size}, hasNext: ${!nextContinuation.isNullOrBlank()}")
        return newHomePageResponse(request, results, hasNext = !nextContinuation.isNullOrBlank())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        return search(query, 1)?.items ?: emptyList()
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        println("[YT_DEBUG] search called. query: '$query', page: $page")
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            println("[YT_DEBUG] Search page $page exceeds limits. Returning empty.")
            return newSearchResponseList(emptyList(), false)
        }

        val results = mutableListOf<SearchResponse>()
        isSearchContext = true
        if (page == 1) {
            searchShorts.clear()
        }

        val seenIds = mutableSetOf<String>()
        var actualQuery = query
        var playlistMode = false
        var spParam = ""

        val playlistTag = appPrefs?.getString("playlist_search_tag", "{p}") ?: "{p}"
        if (query.contains(playlistTag)) {
            actualQuery = query.replace(playlistTag, "").trim()
            playlistMode = true
            spParam = "&sp=EgIQAw%3D%3D"
        }
        println("[YT_DEBUG] Search target. actualQuery: '$actualQuery', playlistMode: $playlistMode")

        try {
            if (page == 1) {
                saveContinuationToken("search_token", null)
                val encoded = URLEncoder.encode(actualQuery, "utf-8")
                val url = "$mainUrl/results?search_query=$encoded$spParam"
                println("[YT_DEBUG] Search GET url: $url")
                val html = app.get(url, interceptor = ytInterceptor).text

                savedApiKey = API_KEY_EXTRACT_REGEX.find(html)?.groupValues?.get(1)
                savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")
                println("[YT_DEBUG] Search HTML parsed. apiKey=$savedApiKey, visitorData=$savedVisitorData")

                val initialData = extractYtInitialData(html)
                if (initialData != null) {
                    processRecursive(initialData, results, seenIds, playlistMode)
                    savedContinuationToken = findTokenRecursive(initialData)
                    saveContinuationToken("search_token", savedContinuationToken)
                    println("[YT_DEBUG] Search page 1 next token: $savedContinuationToken")
                } else {
                    println("[YT_DEBUG] Search page 1 initialData is null.")
                }
            } else {
                val tokenToUse = getSavedContinuationToken("search_token")
                println("[YT_DEBUG] Search continuation page $page. token: $tokenToUse")
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
                                "gl" to if (lang == "ko") "KR" else "US",
                                "visitorData" to vData
                            )
                        ),
                        "continuation" to tokenToUse
                    )
                    println("[YT_DEBUG] Search Continuation POST url: $apiUrl")
                    println("[YT_DEBUG] Search Continuation POST payload: $payload")
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl) 
                    val response = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor)
                        .parsedSafe<Map<String, Any>>()
                    if (response != null) {
                        println("[YT_DEBUG] Search Continuation response success. Keys: ${response.keys}")
                        val actions = response["onResponseReceivedActions"]
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"] 
                            ?: response
                        processRecursive(actions, results, seenIds, playlistMode)
                        savedContinuationToken = findTokenRecursive(response)
                        saveContinuationToken("search_token", savedContinuationToken)
                        println("[YT_DEBUG] Search Continuation next token: $savedContinuationToken")
                    } else {
                        println("[YT_DEBUG] Search Continuation response is null.")
                    }
                } else {
                    println("[YT_DEBUG] Search Continuation variables are missing.")
                }
            }
            println("[YT_DEBUG] Search page completed. Extracted size: ${results.size}")
            return newSearchResponseList(results, !getSavedContinuationToken("search_token").isNullOrBlank())
        } catch (e: Exception) {
            println("[YT_DEBUG] Search page exception: ${e.message}")
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
        println("[YT_DEBUG] Starting extractPlaylistEpisodes. data type: ${data?.javaClass?.name}")
        
        fun recurse(node: Any?, depth: Int = 0) {
            if (node == null || depth > 32) return
            if (node is Map<*, *>) {
                if (depth < 4) {
                    println("[YT_DEBUG] recurse Map keys at depth $depth: ${node.keys.take(10)}")
                }
                
                val playlistVideo = node.getMapKey("playlistVideoRenderer")
                if (playlistVideo != null) {
                    val videoId = playlistVideo.getString("videoId")
                    println("[YT_DEBUG] Found playlistVideoRenderer. videoId: $videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistVideo.getMapKey("title"))
                        val channel = getText(playlistVideo.getMapKey("shortBylineText"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        println("[YT_DEBUG] Adding playlist video: $title (ID: $videoId)")
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = channel
                        })
                    }
                }
                
                val playlistPanelVideo = node.getMapKey("playlistPanelVideoRenderer")
                if (playlistPanelVideo != null) {
                    val videoId = playlistPanelVideo.getString("videoId")
                    println("[YT_DEBUG] Found playlistPanelVideoRenderer. videoId: $videoId")
                    if (!videoId.isNullOrBlank() && seenIds.add(videoId)) {
                        val title = getText(playlistPanelVideo.getMapKey("title"))
                        val channel = getText(playlistPanelVideo.getMapKey("shortBylineText"))
                        val thumb = YoutubeUtils.getBestThumbnail(playlistPanelVideo.getMapKey("thumbnail")) ?: YoutubeUtils.buildThumbnailFromId(videoId)
                        println("[YT_DEBUG] Adding playlist panel video: $title (ID: $videoId)")
                        episodes.add(newEpisode("$mainUrl/watch?v=$videoId") {
                            this.name = title
                            this.episode = episodes.size + 1
                            this.posterUrl = thumb
                            this.description = channel
                        })
                    }
                }
                
                for (value in node.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        recurse(value, depth + 1)
                    }
                }
            } else if (node is List<*>) {
                if (depth < 4) {
                    println("[YT_DEBUG] recurse List size: ${node.size} at depth $depth")
                }
                for (item in node) {
                    recurse(item, depth + 1)
                }
            }
        }
        
        recurse(data)
        println("[YT_DEBUG] Finished extractPlaylistEpisodes. Extracted episodes: ${episodes.size}")
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
            val keys = obj.keys.map { it.toString() }
            
            if (keys.contains("playlistId") || keys.contains("playlistPanelRenderer") || keys.contains("playlistSidebarPrimaryInfoRenderer")) {
                println("[YT_DEBUG] [Recursive-Scan] 재생목록 핵심 키셋 식별: $keys")
                for (k in keys) {
                    val v = obj[k] ?: obj.entries.firstOrNull { it.key.toString() == k }?.value
                    if (v is String || v is Number || v is Boolean) {
                        println("[YT_DEBUG]   -> [$k] = '$v'")
                    } else if (v is Map<*, *>) {
                        println("[YT_DEBUG]   -> [$k] (Map, 서브 키 세트: ${v.keys})")
                    }
                }
            }

            val primary = YoutubeUtils.getMapKeyFromAny(obj, "playlistSidebarPrimaryInfoRenderer")
            if (primary != null) {
                val t = getText(primary["title"] ?: YoutubeUtils.getMapKeyFromAny(primary, "title"))
                if (t.isNotBlank()) {
                    metadata.title = t
                    println("[YT_DEBUG] [Recursive] Found playlistSidebarPrimaryInfoRenderer. Title: '$t'")
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
                        println("[YT_DEBUG] [Recursive] Found playlistSidebarSecondaryInfoRenderer. Author: '$a'")
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
                println("[YT_DEBUG] [Recursive] Found playlistHeaderRenderer. Title: '${metadata.title}', Author: '${metadata.author}'")
            }

            val panel = YoutubeUtils.getMapKeyFromAny(obj, "playlistPanelRenderer")
            if (panel != null) {
                val t = getText(panel["titleText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "titleText"))
                    .ifBlank { getText(panel["title"] ?: YoutubeUtils.getMapKeyFromAny(panel, "title")) }
                if (t.isNotBlank()) {
                    metadata.title = t
                    println("[YT_DEBUG] [Recursive] Found playlistPanelRenderer. Title: '$t'")
                }
                
                val a = getText(panel["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "shortBylineText"))
                    .ifBlank { getText(panel["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(panel, "longBylineText")) }
                    .ifBlank { getText(panel["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(panel, "ownerName")) }
                if (a.isNotBlank()) {
                    metadata.author = a
                    println("[YT_DEBUG] [Recursive] Found playlistPanelRenderer. Author: '$a'")
                }
            }

            val metaRenderer = YoutubeUtils.getMapKeyFromAny(obj, "playlistMetadataRenderer")
            if (metaRenderer != null) {
                val t = YoutubeUtils.getStringFromAny(metaRenderer, "title") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "title"))
                if (!t.isNullOrBlank()) {
                    metadata.title = t
                    println("[YT_DEBUG] [Recursive] Found playlistMetadataRenderer. Title: '$t'")
                }
                val d = YoutubeUtils.getStringFromAny(metaRenderer, "description") ?: getText(YoutubeUtils.getMapKeyFromAny(metaRenderer, "description"))
                if (!d.isNullOrBlank()) metadata.description = d
            }

            if (keys.contains("playlistId") && (keys.contains("titleText") || keys.contains("title"))) {
                val t = getText(obj["titleText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "titleText"))
                    .ifBlank { getText(obj["title"] ?: YoutubeUtils.getMapKeyFromAny(obj, "title")) }
                if (t.isNotBlank() && metadata.title.isBlank()) {
                    metadata.title = t
                    println("[YT_DEBUG] [Recursive-Failsafe] Found direct playlist body. Title: '$t'")
                }
                val a = getText(obj["shortBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "shortBylineText"))
                    .ifBlank { getText(obj["longBylineText"] ?: YoutubeUtils.getMapKeyFromAny(obj, "longBylineText")) }
                    .ifBlank { getText(obj["ownerName"] ?: YoutubeUtils.getMapKeyFromAny(obj, "ownerName")) }
                if (a.isNotBlank() && metadata.author.isBlank()) {
                    metadata.author = a
                    println("[YT_DEBUG] [Recursive-Failsafe] Found direct playlist body. Author: '$a'")
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
                println("[YT_DEBUG] [Recursive] Found videoOwnerRenderer. VideoAuthor: '$vAuth', SubCount: '$sub', Avatar: '$avatar', ChannelId: '$browseId'")
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
        println("[YT_DEBUG] load called. url: '$url'")
        if (url.contains("/shorts/")) {
            val videoId = YoutubeUtils.extractYoutubeId(url) ?: "video"
            println("[YT_DEBUG] Loading shorts. videoId: $videoId")
            val useSearchList = url.contains("&ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            val targetEpisodes = sourceList.toMutableList()
            var currentEp = targetEpisodes.find { YoutubeUtils.extractYoutubeId(it.data) == videoId }

            if (currentEp == null) {
                val fallbackEp = newEpisode(url) {
                    this.name = "Shorts Video"
                    this.posterUrl = YoutubeUtils.buildThumbnailFromId(videoId)
                    this.episode = targetEpisodes.size + 1
                }
                targetEpisodes.add(0, fallbackEp)
                currentEp = fallbackEp
            }

            val poster = currentEp?.posterUrl ?: YoutubeUtils.buildThumbnailFromId(videoId)

            val shortTitle = currentEp?.name ?: "Shorts Video"
            val shortPlot = buildString {
                append(shortTitle)
                val desc = currentEp?.description
                if (!desc.isNullOrBlank()) {
                    append("\n\n$desc")
                }
            }

            return newTvSeriesLoadResponse(shortTitle, url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = poster
                this.plot = shortPlot
                this.tags = listOf("Shorts")
            }
        }

        if (url.contains("/@") || url.contains("/channel/") || url.contains("/c/") || url.contains("/user/")) {
            println("[YT_DEBUG] Loading Channel path. url: $url")
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
                println("[YT_DEBUG] 1차 채널 요청 URL: $channelUrl")
                var response = app.get(channelUrl, interceptor = ytInterceptor)
                var html = response.text
                var data = extractYtInitialData(html)
                    ?: throw ErrorLoadingException("Failed to extract channel data")

                val tabsCheck = safeGet(data, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                val activeTab = tabsCheck?.firstOrNull { tab ->
                    safeGet(tab, "tabRenderer", "selected") == true
                }
                
                val endpointUrl = safeGet(activeTab, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                println("[YT_DEBUG] 1차 파싱 결과 - 활성 탭 엔드포인트: $endpointUrl")

                if (endpointUrl != null && !endpointUrl.endsWith("/videos")) {
                    val canonicalUrl = response.document.selectFirst("link[rel=canonical]")?.attr("href")
                    println("[YT_DEBUG] 자가치유 작동 - Canonical URL 파싱: $canonicalUrl")
                    if (!canonicalUrl.isNullOrBlank()) {
                        val cleanCanonical = canonicalUrl.substringBefore("?").removeSuffix("/")
                        if (!cleanCanonical.endsWith("/videos")) {
                            val targetUrl = "$cleanCanonical/videos"
                            println("[YT_DEBUG] 2차 채널 핸들 URL 재요청 시작: $targetUrl")
                            response = app.get(targetUrl, interceptor = ytInterceptor)
                            html = response.text
                            val newData = extractYtInitialData(html)
                            if (newData != null) {
                                data = newData
                                println("[YT_DEBUG] 2차 채널 데이터 파싱 성공")
                            } else {
                                println("[YT_DEBUG] 2차 채널 데이터 파싱 실패 (null)")
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
                            if (SUBSCRIBER_REGEX.matches(text)) {
                                subscriberCount = text
                            }
                        }
                    }
                }
                subscriberCount = SUBSCRIBER_REPLACE_REGEX.replace(subscriberCount, "").trim()
                if (subscriberCount.isBlank()) subscriberCount = "N/A"

                val allEpisodes = mutableListOf<Episode>()

                // stack overflow 방지 및 안전 무제한 깊이 재귀 해소
                fun findContinuationItemsRecursive(obj: Any?, depth: Int = 0): List<*>? {
                    if (obj == null || depth > 32) return null
                    when (obj) {
                        is Map<*, *> -> {
                            if (obj.containsKey("continuationItems")) return obj["continuationItems"] as? List<*>
                            val keysToTry = listOf("onResponseReceivedActions", "onResponseReceivedEndpoints", "onResponseReceivedCommands", "onResponseReceivedResults", "continuationContents")
                            for (k in keysToTry) {
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
                    println("[YT_CHANNEL_DEBUG] extractVideosFromItems() 호출됨. 분석할 아이템 개수: ${items.size}")
                    
                    fun findAllRenderers(obj: Any?, targetKeys: List<String>, results: MutableList<Map<*,*>>, depth: Int = 0) {
                        if (obj == null || depth > 32) return
                        when (obj) {
                            is Map<*, *> -> {
                                for (key in targetKeys) {
                                    if (obj.containsKey(key)) {
                                        val match = obj[key] as? Map<*,*>
                                        if (match != null) results.add(match)
                                    }
                                }
                                for (value in obj.values) {
                                    findAllRenderers(value, targetKeys, results, depth + 1)
                                }
                            }
                            is List<*> -> {
                                for (item in obj) {
                                    findAllRenderers(item, targetKeys, results, depth + 1)
                                }
                            }
                        }
                    }

                    val targetRendererKeys = listOf("videoRenderer", "gridVideoRenderer", "compactVideoRenderer", "shortsVideoRenderer", "reelItemRenderer", "shortsLockupViewModel", "videoViewModel", "lockupViewModel")

                    items.forEachIndexed { index, item ->
                        try {
                            val map = item as? Map<*, *>
                            if (map == null) {
                                println("[YT_CHANNEL_DEBUG] Item $index: Map이 아님 (타입: ${item?.javaClass?.name})")
                                return@forEachIndexed
                            }

                            if (map.containsKey("continuationItemRenderer")) {
                                return@forEachIndexed
                            }

                            val foundRenderers = mutableListOf<Map<*,*>>()
                            findAllRenderers(map, targetRendererKeys, foundRenderers)

                            if (foundRenderers.isEmpty()) {
                                println("[YT_CHANNEL_DEBUG] Item $index: 인식 가능한 비디오 렌더러를 깊은 탐색으로도 찾지 못함. (최상위 키 목록: ${map.keys})")
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

                                    val metaList = mutableListOf<String>()

                                    val legacyViews = getText(renderer["viewCountText"])
                                    if (legacyViews.isNotBlank()) metaList.add(formatViews(legacyViews))

                                    val legacyTime = getText(renderer["publishedTimeText"])
                                    if (legacyTime.isNotBlank()) metaList.add(legacyTime)

                                    if (metaList.isEmpty()) {
                                        val rows = safeGet(renderer, "metadata", "lockupMetadataViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                                        rows?.forEach { row ->
                                            val parts = safeGet(row, "metadataParts") as? List<*>
                                            parts?.forEach { part ->
                                                val text = getText(safeGet(part, "text"))
                                                if (text.isNotBlank()) {
                                                    if (METADATA_VIEW_REGEX.matches(text)) {
                                                        metaList.add(formatViews(text))
                                                    } else {
                                                        metaList.add(text)
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    collectTo.add(newEpisode("$mainUrl/watch?v=$vidId") {
                                        this.name = vidTitle
                                        this.posterUrl = thumb
                                        this.description = metaList.joinToString(" • ")
                                    })
                                    println("[YT_CHANNEL_DEBUG] Item $index: 렌더러 파싱 성공 -> $vidTitle (ID: $vidId)")
                                    
                                    break 
                                }
                            }
                        } catch (e: Exception) {
                            println("[YT_CHANNEL_DEBUG] Item $index 파싱 중 깊은 탐색 에러 발생: ${e.message}")
                        }
                    }
                    println("[YT_CHANNEL_DEBUG] 총 파싱 완료된 에피소드 수: ${collectTo.size}")
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
                
                println("[YT_CHANNEL_DEBUG] 추출된 초기 에피소드 덩어리 수: ${initialItems?.size ?: 0}")
                if (initialItems != null) {
                    extractVideosFromItems(initialItems, allEpisodes)
                }

                fun findContinuationTokenFromItems(items: List<*>?): String? {
                    if (items == null) return null
                    for (it in items) {
                        val m = it as? Map<*, *> ?: continue
                        val token = findTokenRecursive(m)
                        if (!token.isNullOrBlank()) return token
                    }
                    return null
                }

                var currentToken: String? = findContinuationTokenFromItems(initialItems)
                if (currentToken.isNullOrBlank()) {
                    val conts = findContinuationItemsRecursive(data)
                    currentToken = findContinuationTokenFromItems(conts)
                }
                println("[YT_DEBUG] 페이지네이션 토큰 발견 여부: ${currentToken != null}")

                var pagesFetchedLocal = 1
                val maxPagesLimit = appPrefs?.getInt("channel_pages_limit", 36) ?: 36

                while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPagesLimit && !apiKey.isNullOrBlank()) {
                    try {
                        pagesFetchedLocal += 1
                        val apiUrl = "https://www.youtube.com/youtubei/v1/browse?key=$apiKey"
                        val payload = mapOf(
                            "context" to mapOf(
                                "client" to mapOf(
                                    "clientName" to "WEB",
                                    "clientVersion" to clientVersion,
                                    "hl" to lang,
                                    "gl" to if (lang == "ko") "KR" else "US",
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
                        currentToken = findContinuationTokenFromItems(continuationItems)
                    } catch (e: Exception) {
                        break
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = "Channel: $title\nSubscribers: ${subscriberCount}\nVideos Fetched: ${allEpisodes.size}"
                    this.tags = listOf(title, "Channel")
                }

            } catch (e: Exception) {
                println("[YT_DEBUG] Channel load exception: ${e.message}")
                logError(e)
            }
        }

        if (url.contains("list=")) {
            println("[YT_DEBUG] Loading Playlist/Mix endpoint. url: $url")
            try {
                var targetUrl = url
                if (url.contains("playlist?list=")) {
                    val listId = url.substringAfter("list=").substringBefore("&")
                    if (listId.startsWith("RD") || listId.startsWith("UL") || listId.startsWith("MM") || listId.startsWith("RDMM")) {
                        targetUrl = "$mainUrl/watch?list=$listId"
                        println("[YT_DEBUG] Rewrote Mix playlist URL to watch URL: $targetUrl")
                    }
                }

                val response = app.get(targetUrl, interceptor = ytInterceptor)
                val html = response.text
                println("[YT_DEBUG] Playlist page request success. HTML size: ${html.length}")
                
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract playlist data")
                println("[YT_DEBUG] Playlist initialData successfully parsed.")

                println("[YT_DEBUG] [load] playlist_browse 시작됨. 데이터 탑레벨 키 세트: ${data.keys}")
                if (data.containsKey("sidebar")) {
                    println("[YT_DEBUG] [load] 루트 레벨 sidebar 키 탐지됨")
                }
                if (data.containsKey("header")) {
                    println("[YT_DEBUG] [load] 루트 레벨 header 키 탐지됨")
                }
                if (data.containsKey("contents")) {
                    val contentsObj = data["contents"] as? Map<*, *>
                    println("[YT_DEBUG] [load] contents 키 탐지됨. 하위 키 목록: ${contentsObj?.keys}")
                }

                val playlistMeta = PlaylistMetadata()
                findPlaylistMetadataRecursive(data, playlistMeta)

                var author = playlistMeta.videoAuthor.ifBlank { playlistMeta.author }.ifBlank { "Unknown Channel" }
                var title = playlistMeta.title.ifBlank { "YouTube Playlist" }
                var description = playlistMeta.description

                println("[YT_DEBUG] [load] 최종 파싱 완료된 메타데이터 결과 -> title: '$title', author: '$author', desc_len: ${description.length}")

                val seenPlaylistIds = mutableSetOf<String>()
                val episodes = extractPlaylistEpisodes(data, seenPlaylistIds).toMutableList()
                println("[YT_DEBUG] Successfully parsed initial episodes count: ${episodes.size}")

                val isMix = url.contains("list=RD") || url.contains("list=MM") || url.contains("list=UL")
                if (isMix) {
                    val firstChannel = episodes.firstOrNull()?.description
                    if (!firstChannel.isNullOrBlank()) {
                        title = if (lang == "ko") {
                            "믹스 - $firstChannel"
                        } else {
                            "Mix - $firstChannel"
                        }
                    } else if (title.contains("YouTube Mix") || title.contains("믹스")) {
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
                    println("[YT_DEBUG] [load] Playlist channel recommendation card injected: '$author' (ID: $cId)")
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    
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
                println("[YT_DEBUG] Playlist/Mix loading exception: ${e.message}")
                logError(e)
            }
        }          

        val videoId = YoutubeUtils.extractYoutubeId(url) ?: throw ErrorLoadingException("Invalid YouTube URL")
        println("[YT_DEBUG] Loading normal video ID: $videoId")

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
            println("[YT_DEBUG] Video initialData parsed successfully. Extraction starting.")
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
            println("[YT_DEBUG] Video initialData was null. Falling back to meta tags.")
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
        println("[YT_DEBUG] Video metadata load complete. Title: $title, Recommendations count: ${filteredRecs.size}")

        val episodes = mutableListOf<Episode>()
        
        episodes.add(newEpisode(url) {
            this.name = title
            this.posterUrl = poster
            this.episode = 1
            this.description = channelName.ifBlank { "재생 중" }
        })

        var epCount = 2
        filteredRecs.forEach { rec ->
            if (rec.url.contains("watch?v=") || rec.url.contains("/shorts/")) {
                episodes.add(newEpisode(rec.url) {
                    this.name = rec.name
                    this.posterUrl = rec.posterUrl
                    this.episode = epCount++
                })
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
            this.posterUrl = poster
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

        var isLive = false
        var playerResponseText = ""
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "WEB",
                        "clientVersion" to YoutubeUtils.DEFAULT_CLIENT_VERSION,
                        "hl" to lang,
                        "gl" to if (lang == "ko") "KR" else "US"
                    )
                ),
                "videoId" to videoId
            )
            val headers = mapOf("Referer" to "$mainUrl/watch?v=$videoId")
            playerResponseText = app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).text
            if (playerResponseText.isNotBlank()) {
                val root = JSONObject(playerResponseText)
                val videoDetails = root.optJSONObject("videoDetails")
                if (videoDetails != null && videoDetails.optBoolean("isLive", false)) {
                    isLive = true
                }
            }
        } catch (e: Exception) {
            logError(e)
        }

        if (isLive) {
            // 라이브 방송은 순정 환경(내장 추출기)으로 처리
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false)
            try {
                loadExtractor(fullUrl, subtitleCallback) { link ->
                    if (!link.url.contains("127.0.0.1") && !link.url.endsWith(".mpd")) {
                        callback(link)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(true)
            }
        } else {
            if (playerResponseText.isNotBlank()) {
                try {
                    val playerResponse = parseJson<PlayerResponse>(playerResponseText)
                    triggerPlaybackTracking(playerResponse.playbackTracking, videoId)
                } catch (e: Exception) {
                    println("[YT_DEBUG] Error parsing playerResponse for tracking: ${e.message}")
                }
            }

            // 1. 고화질 DASH 추출기 (회원님의 Yextractor.kt) 무조건 실행
            com.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback) {
                callback(it)
            }

            // 2. Cloudstream 내장 Extractor 실행 (recloudstream과 완벽히 동일한 조건)
            // 🔥 다른 플러그인처럼 완벽하게 작동하게 만들기 위해 전역 설정을 WEB(false)으로 되돌립니다.
            org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(false)
            try {
                // 이름 충돌이 해결되었으므로 이제 정상적으로 코어 내장 추출기가 실행됩니다.
                loadExtractor(fullUrl, subtitleCallback) { link ->
                    // Yextractor(127.0.0.1) 중복 호출 필터링 후 내장 링크만 전달
                    if (!link.url.contains("127.0.0.1") && !link.url.endsWith(".mpd")) {
                        callback(link)
                    }
                }
            } catch (e: Exception) {
                logError(e)
            } finally {
                // 다음 영상을 위해 다시 iOS 모드를 켭니다.
                org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor.setFetchIosClient(true)
            }

            // 보조 모바일 앱 시뮬레이션 워밍업 유지
            loadLinksAdvanced(videoId, subtitleCallback) { }
        }

        return true
    }

   
    
    private suspend fun loadLinksAdvanced(
        videoId: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        try {
            val apiUrl = "$mainUrl/youtubei/v1/player"
            val payload = mapOf(
                "context" to mapOf(
                    "client" to mapOf(
                        "clientName" to "3",
                        "clientVersion" to "19.29.35",
                        "hl" to lang,
                        "gl" to if (lang == "ko") "KR" else "US"
                    )
                ),
                "videoId" to videoId
            )
            val headers = mapOf("Referer" to "$mainUrl/watch?v=$videoId")
            app.post(apiUrl, json = payload, headers = headers, interceptor = ytInterceptor).text
        } catch (e: Exception) {
            // 예외 억제
        }
        return true
    }
}
