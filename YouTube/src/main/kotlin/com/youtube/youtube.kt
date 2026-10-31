package com.hsp1020.youtube

import org.json.JSONObject
import android.content.SharedPreferences
import android.util.Log
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.core.JsonToken
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
import com.lagradost.cloudstream3.CloudStreamApp
import androidx.preference.PreferenceManager
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

fun String.stripWhitespace(): String {
    if (this.isEmpty()) return this
    val sb = java.lang.StringBuilder(this.length)
    for (i in 0 until this.length) {
        val c = this[i]
        if (c != '\r' && c != '\n') {
            sb.append(c)
        }
    }
    return sb.toString().trim()
}

class YoutubeProvider(
    private val sharedPref: SharedPreferences? = null
) : MainAPI() {

    val appPrefs: SharedPreferences?
        get() = sharedPref ?: CloudStreamApp.context?.let { PreferenceManager.getDefaultSharedPreferences(it) }

    data class CustomSection(
        @JsonProperty("name") var name: String = "",
        @JsonProperty("url") var url: String = "",
        @JsonProperty("isEnabled") var isEnabled: Boolean = true
    )
    
    data class CachedChip(
        @JsonProperty("name") val name: String,
        @JsonProperty("token") val token: String
    )

    private data class ChipBootstrapSnapshot(
        val language: String,
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String?,
        val chips: Map<String, String>,
        val createdAtMs: Long
    )

    private data class PreparedHomePage(
        val language: String,
        val items: List<SearchResponse>,
        val nextToken: String?,
        val createdAtMs: Long
    )

    private data class HomeBootstrapPayload(
        val apiKey: String,
        val clientVersion: String,
        val visitorData: String?,
        val chips: LinkedHashMap<String, String>,
        val homeItems: List<SearchResponse>,
        val homeNextToken: String?
    )

    private data class ProcessedChipPayload(
        val items: List<SearchResponse>,
        val nextToken: String?
    )

    private data class DirectPageResult(
        val items: List<SearchResponse>,
        val nextToken: String?
    )

    private data class ChipCatalogSession(
        val bootstrap: ChipBootstrapSnapshot,
        val pageTokens: ConcurrentHashMap<Int, String> = ConcurrentHashMap()
    )

    private val chipCatalogSessions = ConcurrentHashMap<String, ChipCatalogSession>()
    private val chipBootstrapTtlMs = 15_000L
    private val chipBootstrapStaleFallbackMs = 60_000L

    override var mainUrl = "https://www.youtube.com"
    override var name = "YouTube"
    override val hasMainPage = true
    
    override var lang: String
        get() = appPrefs?.getString("youtube_language", "en") ?: "en"
        set(value) {}
        
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Live)

    companion object {
        private val providerScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        private const val YOUTUBE_PAYLOAD_CONCURRENCY = 5
        private val youtubePayloadSemaphore = Semaphore(YOUTUBE_PAYLOAD_CONCURRENCY)

        private const val PREPARED_HOME_REUSE_MS = 2_500L

        private const val STARTUP_DIRECT_REQUEST_SETTLE_MS = 200L
        private val startupGateLock = Any()
        @Volatile
        private var startupFirstDirectRequestAtMs: Long = 0L

        private data class ChipBootstrapInFlight(
            val language: String,
            val deferred: kotlinx.coroutines.Deferred<ChipBootstrapSnapshot?>
        )

        private val chipBootstrapCoordinatorLock = Any()
        @Volatile
        private var sharedChipBootstrapSnapshot: ChipBootstrapSnapshot? = null
        @Volatile
        private var sharedPreparedHomePage: PreparedHomePage? = null
        @Volatile
        private var sharedChipBootstrapInFlight: ChipBootstrapInFlight? = null

        private val mainPageCacheLock = Any()
        @Volatile
        private var sharedCachedMainPageList: List<MainPageData>? = null
        @Volatile
        private var sharedLastLang: String? = null
        @Volatile
        private var sharedLastCustomSectionsJson: String? = null

        internal data class ChannelLink(val name: String, val url: String)

        private val channelLinks = Collections.synchronizedMap(
            object : LinkedHashMap<String, ChannelLink>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ChannelLink>?): Boolean =
                    size > 128
            }
        )

        internal fun channelForUrl(pageUrl: String): ChannelLink? = channelLinks[pageUrl]

        private fun rememberChannel(pageUrl: String, name: String, id: String) {
            if (name.isBlank() || id.isBlank()) return
            val target = when {
                id.startsWith("https://www.youtube.com/") -> id
                id.startsWith("/") -> "https://www.youtube.com$id"
                id.startsWith("@") -> "https://www.youtube.com/$id"
                id.startsWith("UC") -> "https://www.youtube.com/channel/$id"
                else -> "https://www.youtube.com/$id"
            }
            channelLinks[pageUrl] = ChannelLink(name, target)
        }

        private val homeShorts = Collections.synchronizedList(mutableListOf<Episode>())

        /** Number of the tapped home Shorts item in the current home catalog. */
        internal fun homeShortIndex(url: String): Int? {
            if (!url.contains("/shorts/") || !url.contains("ctx=home")) return null
            val videoId = YoutubeUtils.extractYoutubeId(url) ?: return null
            return synchronized(homeShorts) {
                homeShorts.indexOfFirst {
                    YoutubeUtils.extractYoutubeId(it.data) == videoId
                }.takeIf { it >= 0 }
            }
        }

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
                    return size > 500
                }
            }
        )

        // 추천 영상들의 비디오 ID별 멤버십 라벨 텍스트를 실시간 기록하는 캐시 맵
        private val videoMembershipCache: MutableMap<String, String> = Collections.synchronizedMap(
            object : LinkedHashMap<String, String>(128, 0.75f, true) {
                override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
                    return size > 500
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
            "videoViewModel", "lockupViewModel",
            "videoPrimaryInfoRenderer", "videoViewCountRenderer"
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

            providerScope.launch {
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
        
        return continuationTokenCache[pageKey]
    }

    private fun saveContinuationToken(key: String, token: String?) {
        saveContinuationToken(key, 0, token)
    }

    private fun saveContinuationToken(key: String, page: Int, token: String?) {
        val pageKey = if (page > 0) "${key}_page_$page" else key
        
        if (token.isNullOrBlank()) {
            continuationTokenCache.remove(pageKey)
        } else {
            continuationTokenCache[pageKey] = token
        }
    }

    private fun clearContinuationTokens(key: String) {
        for (p in 0..100) {
            val pageKey = if (p > 0) "${key}_page_$p" else key
            continuationTokenCache.remove(pageKey)
        }
    }

    private fun extractChipTokens(initialData: Any?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        val chipBar = safeGet(
            initialData,
            "contents", "twoColumnBrowseResultsRenderer", "tabs", 0,
            "tabRenderer", "content", "richGridRenderer", "header",
            "feedFilterChipBarRenderer", "contents"
        ) as? List<*> ?: return out

        for (chipItem in chipBar) {
            val chipMap = (chipItem as? Map<*, *>)
                ?.get("chipCloudChipRenderer") as? Map<*, *> ?: continue
            val text = getText(chipMap["text"])
            val token = safeGet(
                chipMap,
                "navigationEndpoint", "continuationCommand", "token"
            ) as? String
            if (text.isNotBlank() && !token.isNullOrBlank()) {
                out[text] = token
            }
        }
        return out
    }

    private fun readPersistedChips(): List<CachedChip> {
        val cachedJson = appPrefs?.getString("yt_cached_chips", "[]") ?: "[]"
        return try {
            mapper.readValue<List<CachedChip>>(cachedJson)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private suspend fun fetchAndPrepareHomeBootstrap(requestLang: String): HomeBootstrapPayload? {
        return youtubePayloadSemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            val freshUrl = "$mainUrl/?t=${System.currentTimeMillis()}"
            val html = app.get(
                freshUrl,
                interceptor = ytInterceptor,
                headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
            ).textLarge
            currentCoroutineContext().ensureActive()

            val apiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY")
                ?: savedApiKey
                ?: appPrefs?.getString("yt_api_key", null)
            val visitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")
                ?: savedVisitorData
                ?: appPrefs?.getString("yt_visitor_data", null)
            val clientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION")
                ?: savedClientVersion
                ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION)
                ?: YoutubeUtils.DEFAULT_CLIENT_VERSION

            val initialData = extractHomeInitialData(html) ?: return@withPermit null

            val freshChips = extractChipTokens(initialData)
            val homeResults = mutableListOf<SearchResponse>()
            val homeSeenIds = mutableSetOf<String>()
            processRecursive(
                initialData,
                homeResults,
                homeSeenIds,
                playlistMode = false,
                isSearch = false
            )
            val homeNextToken = findTokenRecursive(initialData)

            HomeBootstrapPayload(
                apiKey = apiKey ?: return@withPermit null,
                clientVersion = clientVersion,
                visitorData = visitorData,
                chips = freshChips,
                homeItems = homeResults.toList(),
                homeNextToken = homeNextToken
            )
        }
    }

    private suspend fun refreshChipBootstrap(requestLang: String): ChipBootstrapSnapshot? {
        val now = System.currentTimeMillis()
        val current = sharedChipBootstrapSnapshot
        val fallback = current?.takeIf {
            it.language == requestLang && now - it.createdAtMs <= chipBootstrapStaleFallbackMs
        }

        try {
            val payload = fetchAndPrepareHomeBootstrap(requestLang)
            if (payload == null || payload.apiKey.isBlank() || payload.chips.isEmpty()) {
                Log.w(
                    "YoutubeUtils",
                    "[CHIP_BOOTSTRAP] REFRESH_INVALID payload=${payload != null} chips=${payload?.chips?.size ?: 0}; fallback=${fallback != null}"
                )
                return fallback
            }

            savedApiKey = payload.apiKey
            savedVisitorData = payload.visitorData
            savedClientVersion = payload.clientVersion
            appPrefs?.edit()
                ?.putString("yt_api_key", payload.apiKey)
                ?.putString("yt_visitor_data", payload.visitorData)
                ?.putString("yt_client_version", payload.clientVersion)
                ?.apply()

            mergeAndSaveChips(payload.chips.map { CachedChip(it.key, it.value) })

            val mergedFreshChips = LinkedHashMap<String, String>()
            val previous = sharedChipBootstrapSnapshot
            if (previous != null &&
                previous.language == requestLang &&
                System.currentTimeMillis() - previous.createdAtMs <= chipBootstrapStaleFallbackMs
            ) {
                mergedFreshChips.putAll(previous.chips)
            }
            mergedFreshChips.putAll(payload.chips)

            val createdAt = System.currentTimeMillis()
            val snapshot = ChipBootstrapSnapshot(
                language = requestLang,
                apiKey = payload.apiKey,
                clientVersion = payload.clientVersion,
                visitorData = payload.visitorData,
                chips = mergedFreshChips,
                createdAtMs = createdAt
            )
            sharedPreparedHomePage = PreparedHomePage(
                language = requestLang,
                items = payload.homeItems,
                nextToken = payload.homeNextToken,
                createdAtMs = createdAt
            )
            sharedChipBootstrapSnapshot = snapshot
            return snapshot
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("YoutubeUtils", "[CHIP_BOOTSTRAP] REFRESH_FAILED fallback=${fallback != null}", e)
            return fallback
        }
    }

    private suspend fun ensureChipBootstrap(): ChipBootstrapSnapshot? {
        val requestLang = lang
        val now = System.currentTimeMillis()
        val cached = sharedChipBootstrapSnapshot
        if (cached != null &&
            cached.language == requestLang &&
            now - cached.createdAtMs <= chipBootstrapTtlMs
        ) {
            return cached
        }

        val deferred = synchronized(chipBootstrapCoordinatorLock) {
            val lockedNow = System.currentTimeMillis()
            val lockedCached = sharedChipBootstrapSnapshot
            if (lockedCached != null &&
                lockedCached.language == requestLang &&
                lockedNow - lockedCached.createdAtMs <= chipBootstrapTtlMs
            ) {
                return@synchronized null
            }

            val existing = sharedChipBootstrapInFlight
            if (existing != null &&
                existing.language == requestLang &&
                existing.deferred.isActive
            ) {
                existing.deferred
            } else {
                val created = providerScope.async {
                    refreshChipBootstrap(requestLang)
                }
                sharedChipBootstrapInFlight = ChipBootstrapInFlight(requestLang, created)
                created.invokeOnCompletion {
                    synchronized(chipBootstrapCoordinatorLock) {
                        if (sharedChipBootstrapInFlight?.deferred === created) {
                            sharedChipBootstrapInFlight = null
                        }
                    }
                }
                created
            }
        }

        if (deferred == null) {
            return sharedChipBootstrapSnapshot?.takeIf {
                it.language == requestLang &&
                    System.currentTimeMillis() - it.createdAtMs <= chipBootstrapTtlMs
            }
        }

        return deferred.await()
    }

    private fun publishChipBootstrapFromHome(
        apiKey: String?,
        clientVersion: String?,
        visitorData: String?,
        chips: Map<String, String>
    ) {
        if (apiKey.isNullOrBlank() || clientVersion.isNullOrBlank() || chips.isEmpty()) return

        val merged = LinkedHashMap<String, String>()
        val previous = sharedChipBootstrapSnapshot
        if (previous != null &&
            previous.language == lang &&
            System.currentTimeMillis() - previous.createdAtMs <= chipBootstrapStaleFallbackMs
        ) {
            merged.putAll(previous.chips)
        }
        merged.putAll(chips)

        sharedChipBootstrapSnapshot = ChipBootstrapSnapshot(
            language = lang,
            apiKey = apiKey,
            clientVersion = clientVersion,
            visitorData = visitorData,
            chips = merged,
            createdAtMs = System.currentTimeMillis()
        )
    }

    private data class ContinuationCandidate(
        val token: String,
        val score: Int,
        val depth: Int
    )

    private data class ContinuationFallbackResult(
        val recursiveToken: String? = null,
        val genericCandidate: ContinuationCandidate? = null
    )

    private fun continuationPathFlag(key: String): Int = when {
        key == "appendContinuationItemsAction" -> 1 shl 0
        key == "reloadContinuationItemsCommand" -> 1 shl 1
        key == "continuationItems" -> 1 shl 2
        key.contains("richGrid") -> 1 shl 3
        key == "continuationItemRenderer" -> 1 shl 4
        key == "buttonRenderer" -> 1 shl 5
        key.contains("loadMore", ignoreCase = true) -> 1 shl 6
        key == "contents" -> 1 shl 7
        key.contains("shelf", ignoreCase = true) -> 1 shl 8
        key.contains("reel", ignoreCase = true) -> 1 shl 9
        key.contains("engagementPanel", ignoreCase = true) -> 1 shl 10
        else -> 0
    }

    private fun continuationCandidateScore(mask: Int): Int {
        var score = 0
        if ((mask and (1 shl 0)) != 0) score += 120
        if ((mask and (1 shl 1)) != 0) score += 120
        if ((mask and (1 shl 2)) != 0) score += 110
        if ((mask and (1 shl 3)) != 0) score += 100
        if ((mask and (1 shl 4)) != 0) score += 90
        if ((mask and (1 shl 5)) != 0) score += 60
        if ((mask and (1 shl 6)) != 0) score += 50
        if ((mask and (1 shl 7)) != 0) score += 20
        if ((mask and (1 shl 8)) != 0) score -= 25
        if ((mask and (1 shl 9)) != 0) score -= 25
        if ((mask and (1 shl 10)) != 0) score -= 50
        return score
    }

    private fun isExcludedContinuationKey(key: String): Boolean = when (key) {
        "header",
        "feedFilterChipBarRenderer",
        "chipCloudRenderer",
        "relatedChipCloudRenderer",
        "chipCloudChipRenderer",
        "chipViewModel",
        "chipCloud" -> true
        else -> false
    }

    private fun preferGenericCandidate(
        current: ContinuationCandidate?,
        candidate: ContinuationCandidate?
    ): ContinuationCandidate? {
        if (candidate == null) return current
        if (current == null) return candidate
        return when {
            candidate.score > current.score -> candidate
            candidate.score < current.score -> current
            candidate.depth > current.depth -> candidate
            else -> current
        }
    }

    private fun extractContinuationRendererToken(renderer: Map<*, *>?): String? {
        if (renderer == null) return null

        val endpoint = renderer["continuationEndpoint"] as? Map<*, *>
        if (endpoint != null) {
            val token = (endpoint["continuationCommand"] as? Map<*, *>)?.get("token") as? String
                ?: (endpoint["browseContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                ?: (endpoint["searchContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
                ?: (endpoint["command"] as? Map<*, *>)?.get("token") as? String
                ?: endpoint["token"] as? String
            if (!token.isNullOrBlank()) return token
        }

        return (renderer["continuationCommand"] as? Map<*, *>)?.get("token") as? String
            ?: (renderer["browseContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
            ?: (renderer["searchContinuationEndpoint"] as? Map<*, *>)?.get("token") as? String
            ?: renderer["token"] as? String
    }

    private data class FallbackAccumulator(
        var recursiveToken: String? = null,
        var genericCandidate: ContinuationCandidate? = null
    )

    private val recursiveContinuationDataKeys = setOf(
        "nextContinuationData",
        "reloadContinuationData",
        "timedContinuationData",
        "invalidationContinuationData"
    )

    private val genericContinuationContainerKeys = setOf(
        "continuationCommand",
        "browseContinuationEndpoint",
        "searchContinuationEndpoint",
        "command"
    )

    /**
     * Streaming tier-2/3 scanner. No JSON subtree is materialized here.
     *
     * Priority is identical to the legacy walk:
     *   recursive continuationItemRenderer / *ContinuationData
     *   -> best-scoring generic continuation command.
     */
    private fun streamScanFallbackNode(
        parser: com.fasterxml.jackson.core.JsonParser,
        accumulator: FallbackAccumulator,
        pathMask: Int = 0,
        depth: Int = 0,
        containerKey: String? = null,
        inContinuationRenderer: Boolean = false
    ) {
        if (depth > 48 || !accumulator.recursiveToken.isNullOrBlank()) {
            parser.skipChildren()
            return
        }

        when (parser.currentToken) {
            JsonToken.START_ARRAY -> {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    if (!accumulator.recursiveToken.isNullOrBlank()) {
                        parser.skipChildren()
                        continue
                    }
                    streamScanFallbackNode(
                        parser,
                        accumulator,
                        pathMask,
                        depth + 1,
                        containerKey,
                        inContinuationRenderer
                    )
                }
            }

            JsonToken.START_OBJECT -> {
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (!accumulator.recursiveToken.isNullOrBlank()) {
                        parser.skipChildren()
                        continue
                    }
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        parser.skipChildren()
                        continue
                    }

                    val field = parser.currentName
                    parser.nextToken()
                    if (isExcludedContinuationKey(field)) {
                        parser.skipChildren()
                        continue
                    }

                    val nextMask = pathMask or continuationPathFlag(field)
                    val nextInContinuationRenderer =
                        inContinuationRenderer || field == "continuationItemRenderer"

                    if (field == "token" &&
                        parser.currentToken == JsonToken.VALUE_STRING &&
                        nextInContinuationRenderer
                    ) {
                        val parentIsAllowed = containerKey == "continuationItemRenderer" ||
                            containerKey == "continuationEndpoint" ||
                            (containerKey != null && containerKey in genericContinuationContainerKeys)
                        if (parentIsAllowed) {
                            val token = parser.valueAsString
                            if (!token.isNullOrBlank()) {
                                accumulator.recursiveToken = token
                                accumulator.genericCandidate = null
                                continue
                            }
                        }
                    }

                    if ((field == "continuation" || field == "token") &&
                        parser.currentToken == JsonToken.VALUE_STRING &&
                        containerKey != null && containerKey in recursiveContinuationDataKeys
                    ) {
                        val token = parser.valueAsString
                        if (!token.isNullOrBlank()) {
                            accumulator.recursiveToken = token
                            accumulator.genericCandidate = null
                            continue
                        }
                    }

                    if (field == "token" &&
                        parser.currentToken == JsonToken.VALUE_STRING &&
                        containerKey != null && containerKey in genericContinuationContainerKeys &&
                        accumulator.recursiveToken.isNullOrBlank()
                    ) {
                        val token = parser.valueAsString
                        if (!token.isNullOrBlank()) {
                            val candidate = ContinuationCandidate(
                                token = token,
                                score = continuationCandidateScore(pathMask),
                                depth = depth
                            )
                            accumulator.genericCandidate =
                                preferGenericCandidate(accumulator.genericCandidate, candidate)
                        }
                    }

                    if (parser.currentToken == JsonToken.START_OBJECT ||
                        parser.currentToken == JsonToken.START_ARRAY
                    ) {
                        streamScanFallbackNode(
                            parser,
                            accumulator,
                            nextMask,
                            depth + 1,
                            field,
                            nextInContinuationRenderer
                        )
                    }
                }
            }

            else -> Unit
        }
    }

    /**
     * Tier 2/3 pass. Invoked only if pass 1 found no authoritative page-level cursor.
     * This pass creates no renderer Map/List objects and never materializes contents branches.
     */
    private fun streamFindFallbackContinuation(body: String): ContinuationFallbackResult {
        val accumulator = FallbackAccumulator()
        try {
            mapper.factory.createParser(body).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) return@use
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (!accumulator.recursiveToken.isNullOrBlank()) break
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        parser.skipChildren()
                        continue
                    }

                    val field = parser.currentName
                    parser.nextToken()
                    if (isExcludedContinuationKey(field)) {
                        parser.skipChildren()
                        continue
                    }

                    if (parser.currentToken == JsonToken.START_OBJECT ||
                        parser.currentToken == JsonToken.START_ARRAY
                    ) {
                        streamScanFallbackNode(
                            parser = parser,
                            accumulator = accumulator,
                            pathMask = continuationPathFlag(field),
                            depth = 1,
                            containerKey = field,
                            inContinuationRenderer = field == "continuationItemRenderer"
                        )
                    }
                }
            }
        } catch (_: Exception) {
            return ContinuationFallbackResult()
        }

        return ContinuationFallbackResult(
            recursiveToken = accumulator.recursiveToken,
            genericCandidate = if (accumulator.recursiveToken.isNullOrBlank()) {
                accumulator.genericCandidate
            } else {
                null
            }
        )
    }

    private data class StreamingChipState(
        val results: MutableList<SearchResponse> = mutableListOf(),
        val seenIds: MutableSet<String> = mutableSetOf(),
        var pageLevelNextToken: String? = null,
        var processedAny: Boolean = false
    )

    private val chipResultRendererKeys = setOf(
        "videoRenderer",
        "compactVideoRenderer",
        "gridVideoRenderer",
        "playlistVideoRenderer",
        "playlistPanelVideoRenderer",
        "richItemRenderer",
        "reelItemRenderer",
        "shortsLockupViewModel",
        "lockupViewModel",
        "channelRenderer"
    )

    private fun processStreamedRenderer(
        field: String,
        parser: com.fasterxml.jackson.core.JsonParser,
        state: StreamingChipState
    ) {
        val rendererValue = mapper.readValue(parser, Any::class.java) ?: return
        state.processedAny = true
        val wrapper = mapOf<String, Any>(field to rendererValue)
        processRecursive(
            wrapper,
            state.results,
            state.seenIds,
            playlistMode = false,
            isSearch = false
        )
    }

    private fun streamContinuationRendererToken(
        parser: com.fasterxml.jackson.core.JsonParser
    ): String? {
        if (parser.currentToken != JsonToken.START_OBJECT) {
            parser.skipChildren()
            return null
        }

        var bestToken: String? = null
        var bestPriority = Int.MAX_VALUE

        fun priorityFor(containerKey: String?): Int = when (containerKey) {
            "continuationCommand" -> 1
            "browseContinuationEndpoint" -> 2
            "searchContinuationEndpoint" -> 3
            "command" -> 4
            "continuationEndpoint" -> 5
            null -> 6 // defensive renderer-level token
            else -> Int.MAX_VALUE
        }

        fun walkObject(containerKey: String?, depth: Int) {
            if (depth > 10 || parser.currentToken != JsonToken.START_OBJECT) {
                parser.skipChildren()
                return
            }
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken != JsonToken.FIELD_NAME) {
                    parser.skipChildren()
                    continue
                }
                val field = parser.currentName
                parser.nextToken()

                if (field == "token" && parser.currentToken == JsonToken.VALUE_STRING) {
                    val token = parser.valueAsString
                    val priority = priorityFor(containerKey)
                    if (!token.isNullOrBlank() && priority < bestPriority) {
                        bestToken = token
                        bestPriority = priority
                    }
                } else if (parser.currentToken == JsonToken.START_OBJECT) {
                    val shouldDescend = field == "continuationEndpoint" ||
                        field in genericContinuationContainerKeys
                    if (shouldDescend) walkObject(field, depth + 1) else parser.skipChildren()
                } else {
                    parser.skipChildren()
                }
            }
        }

        walkObject(null, 0)
        return bestToken
    }

    private fun streamResultTree(
        parser: com.fasterxml.jackson.core.JsonParser,
        state: StreamingChipState,
        depth: Int = 0
    ) {
        if (depth > 48) {
            parser.skipChildren()
            return
        }

        when (parser.currentToken) {
            JsonToken.START_ARRAY -> {
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    streamResultTree(parser, state, depth + 1)
                }
            }

            JsonToken.START_OBJECT -> {
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        parser.skipChildren()
                        continue
                    }

                    val field = parser.currentName
                    parser.nextToken()
                    when {
                        field == "continuationItems" &&
                            parser.currentToken == JsonToken.START_ARRAY ->
                            streamContinuationItems(parser, state)

                        field in chipResultRendererKeys ->
                            processStreamedRenderer(field, parser, state)

                        parser.currentToken == JsonToken.START_OBJECT ||
                            parser.currentToken == JsonToken.START_ARRAY ->
                            streamResultTree(parser, state, depth + 1)

                        else -> Unit
                    }
                }
            }

            else -> Unit
        }
    }

    private fun streamContinuationItems(
        parser: com.fasterxml.jackson.core.JsonParser,
        state: StreamingChipState
    ) {
        if (parser.currentToken != JsonToken.START_ARRAY) {
            parser.skipChildren()
            return
        }

        var pageTokenInThisItems: String? = null
        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken != JsonToken.START_OBJECT) {
                streamResultTree(parser, state, 1)
                continue
            }

            state.processedAny = true
            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken != JsonToken.FIELD_NAME) {
                    parser.skipChildren()
                    continue
                }

                val field = parser.currentName
                parser.nextToken()
                when {
                    field == "continuationItemRenderer" -> {
                        val pageToken = streamContinuationRendererToken(parser)
                        if (!pageToken.isNullOrBlank()) {
                            pageTokenInThisItems = pageToken
                        }
                    }

                    field in chipResultRendererKeys ->
                        processStreamedRenderer(field, parser, state)

                    parser.currentToken == JsonToken.START_OBJECT ||
                        parser.currentToken == JsonToken.START_ARRAY ->
                        streamResultTree(parser, state, 1)

                    else -> Unit
                }
            }
        }

        if (state.pageLevelNextToken.isNullOrBlank() && !pageTokenInThisItems.isNullOrBlank()) {
            state.pageLevelNextToken = pageTokenInThisItems
        }
    }

    private fun streamContinuationCommand(
        parser: com.fasterxml.jackson.core.JsonParser,
        state: StreamingChipState
    ) {
        if (parser.currentToken != JsonToken.START_OBJECT) {
            parser.skipChildren()
            return
        }

        while (parser.nextToken() != JsonToken.END_OBJECT) {
            if (parser.currentToken != JsonToken.FIELD_NAME) {
                parser.skipChildren()
                continue
            }

            val field = parser.currentName
            parser.nextToken()
            if (field == "continuationItems") {
                streamContinuationItems(parser, state)
            } else if (parser.currentToken == JsonToken.START_OBJECT ||
                parser.currentToken == JsonToken.START_ARRAY
            ) {
                streamResultTree(parser, state, 1)
            }
        }
    }

    private fun streamActionArray(
        parser: com.fasterxml.jackson.core.JsonParser,
        state: StreamingChipState
    ) {
        if (parser.currentToken != JsonToken.START_ARRAY) {
            parser.skipChildren()
            return
        }

        while (parser.nextToken() != JsonToken.END_ARRAY) {
            if (parser.currentToken != JsonToken.START_OBJECT) {
                parser.skipChildren()
                continue
            }

            while (parser.nextToken() != JsonToken.END_OBJECT) {
                if (parser.currentToken != JsonToken.FIELD_NAME) {
                    parser.skipChildren()
                    continue
                }

                val field = parser.currentName
                parser.nextToken()
                when (field) {
                    "appendContinuationItemsAction",
                    "reloadContinuationItemsCommand" ->
                        streamContinuationCommand(parser, state)

                    else -> {
                        if (parser.currentToken == JsonToken.START_OBJECT ||
                            parser.currentToken == JsonToken.START_ARRAY
                        ) {
                            streamResultTree(parser, state, 1)
                        } else {
                            parser.skipChildren()
                        }
                    }
                }
            }
        }
    }

    private fun streamProcessChipResponse(body: String): ProcessedChipPayload? {
        return try {
            val state = StreamingChipState()
            mapper.factory.createParser(body).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) return@use null

                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        parser.skipChildren()
                        continue
                    }

                    val field = parser.currentName
                    parser.nextToken()
                    when (field) {
                        "onResponseReceivedActions",
                        "onResponseReceivedEndpoints",
                        "onResponseReceivedCommands" ->
                            streamActionArray(parser, state)

                        "continuationContents", "contents" ->
                            streamResultTree(parser, state)

                        else -> {
                            if (parser.currentToken == JsonToken.START_OBJECT ||
                                parser.currentToken == JsonToken.START_ARRAY
                            ) {
                                streamResultTree(parser, state)
                            } else {
                                parser.skipChildren()
                            }
                        }
                    }
                }
            }

            if (!state.processedAny) return null

            var nextToken = state.pageLevelNextToken
            if (nextToken.isNullOrBlank()) {
                val fallback = streamFindFallbackContinuation(body)
                if (!fallback.recursiveToken.isNullOrBlank()) {
                    nextToken = fallback.recursiveToken
                } else if (fallback.genericCandidate != null) {
                    nextToken = fallback.genericCandidate.token
                }
            }

            ProcessedChipPayload(state.results, nextToken)
        } catch (e: Exception) {
            Log.e(
                "YoutubeUtils",
                "[CHIP] STREAM_PARSE_FAILED ${e.javaClass.simpleName}: ${e.message}"
            )
            null
        }
    }

    private suspend fun fetchAndParseChipPayload(
        bootstrap: ChipBootstrapSnapshot,
        token: String,
        requestKey: String,
        page: Int
    ): ProcessedChipPayload? {
        val apiUrl = "$mainUrl/youtubei/v1/browse?key=${bootstrap.apiKey}"
        val clientContext = linkedMapOf<String, Any>(
            "clientName" to "WEB",
            "clientVersion" to bootstrap.clientVersion,
            "hl" to bootstrap.language,
            "platform" to "DESKTOP"
        )
        if (!bootstrap.visitorData.isNullOrBlank()) {
            clientContext["visitorData"] = bootstrap.visitorData
        }
        val payload = mapOf(
            "context" to mapOf("client" to clientContext),
            "continuation" to token
        )
        val headers = mapOf(
            "Origin" to mainUrl,
            "Referer" to mainUrl,
            "Cache-Control" to "no-cache",
            "Pragma" to "no-cache",
            "X-Youtube-Client-Name" to "WEB",
            "X-Youtube-Client-Version" to bootstrap.clientVersion
        )

        var body = run {
            val rawResponse = app.post(
                apiUrl,
                json = payload,
                headers = headers,
                interceptor = ytInterceptor
            )
            currentCoroutineContext().ensureActive()
            rawResponse.textLarge
        }
        currentCoroutineContext().ensureActive()
        if (body.isBlank()) return null

        val processed = streamProcessChipResponse(body)
        body = ""
        if (processed == null) {
            currentCoroutineContext().ensureActive()
            Log.e("YoutubeUtils", "[CHIP] JSON_PARSE_FAILED key=$requestKey page=$page")
            return null
        }
        return processed
    }

    private suspend fun executeChipBrowseAndProcess(
        bootstrap: ChipBootstrapSnapshot,
        token: String,
        requestKey: String,
        page: Int
    ): ProcessedChipPayload? {
        currentCoroutineContext().ensureActive()
        return youtubePayloadSemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            fetchAndParseChipPayload(bootstrap, token, requestKey, page)
        }
    }

    private suspend fun awaitStartupDirectRequestSettle() {
        val now = System.currentTimeMillis()
        val firstAt = synchronized(startupGateLock) {
            if (startupFirstDirectRequestAtMs == 0L) {
                startupFirstDirectRequestAtMs = now
            }
            startupFirstDirectRequestAtMs
        }
        val remaining = STARTUP_DIRECT_REQUEST_SETTLE_MS - (now - firstAt)
        if (remaining > 0L) {
            delay(remaining)
            currentCoroutineContext().ensureActive()
        }
    }

    private fun mergeAndSaveChips(newChips: List<CachedChip>) {
        val prefs = appPrefs ?: return
        val existingJson = prefs.getString("yt_cached_chips", "[]") ?: "[]"
        
        val existingChips = try {
            mapper.readValue<List<CachedChip>>(existingJson)
        } catch (e: Exception) {
            emptyList()
        }

        val mergedMap = LinkedHashMap<String, CachedChip>()
        existingChips.forEach { chip ->
            mergedMap[chip.name] = chip
        }
        newChips.forEach { chip ->
            mergedMap[chip.name] = chip
        }

        val mergedList = mergedMap.values.toList()
        
        try {
            val outJson = mapper.writeValueAsString(mergedList)
            prefs.edit().putString("yt_cached_chips", outJson).apply()
        } catch (e: Exception) {
            logError(e)
        }

        try {
            val customJson = prefs.getString("custom_homepages_v3", "[]") ?: "[]"
            val currentList = try {
                mapper.readValue<List<CustomSection>>(customJson).toMutableList()
            } catch (e: Exception) {
                mutableListOf()
            }

            var modified = false
            newChips.forEach { chip ->
                val stableUrl = "chip_name_${chip.name}"
                if (currentList.none { section -> section.url == stableUrl }) {
                    currentList.add(CustomSection(name = chip.name, url = stableUrl, isEnabled = false))
                    modified = true
                }
            }

            if (modified) {
                val updatedCustomJson = mapper.writeValueAsString(currentList)
                prefs.edit().putString("custom_homepages_v3", updatedCustomJson).apply()
            }
        } catch (e: Exception) {
            logError(e)
        }
    }

    private fun extractDurationFromOverlays(obj: Any?): String {
        if (obj == null) return ""
        if (obj is Map<*, *>) {
            val timeRenderer = obj.getMapKey("thumbnailOverlayTimeStatusRenderer")
            if (timeRenderer != null) {
                val text = getText(timeRenderer.getMapKey("text"))
                val match = DURATION_REGEX.find(text)
                if (match != null) return match.value
            }

            val badgeRenderer = obj.getMapKey("thumbnailBadgeViewModel")
            if (badgeRenderer != null) {
                val text = getText(badgeRenderer.getString("text"))
                val match = DURATION_REGEX.find(text)
                if (match != null) return match.value
            }

            val contentImage = obj.getMapKey("contentImage")
            if (contentImage != null) {
                val d = extractDurationFromOverlays(contentImage)
                if (d.isNotBlank()) return d
            }
            
            val collectionThumbnail = obj.getMapKey("collectionThumbnailViewModel")
            if (collectionThumbnail != null) {
                val d = extractDurationFromOverlays(collectionThumbnail)
                if (d.isNotBlank()) return d
            }

            val primaryThumbnail = obj.getMapKey("primaryThumbnail")
            if (primaryThumbnail != null) {
                val d = extractDurationFromOverlays(primaryThumbnail)
                if (d.isNotBlank()) return d
            }

            val thumbnailViewModel = obj.getMapKey("thumbnailViewModel")
            if (thumbnailViewModel != null) {
                val d = extractDurationFromOverlays(thumbnailViewModel)
                if (d.isNotBlank()) return d
            }

            val overlays = obj.getListKey("overlays") ?: YoutubeUtils.getListKeyFromAny(obj, "overlays") as? List<*>
            if (overlays != null) {
                for (overlay in overlays) {
                    val d = extractDurationFromOverlays(overlay)
                    if (d.isNotBlank()) return d
                }
            }

            val thumbnailOverlays = obj.getListKey("thumbnailOverlays") ?: YoutubeUtils.getListKeyFromAny(obj, "thumbnailOverlays") as? List<*>
            if (thumbnailOverlays != null) {
                for (overlay in thumbnailOverlays) {
                    val d = extractDurationFromOverlays(overlay)
                    if (d.isNotBlank()) return d
                }
            }
            
            val badges = obj.getListKey("badges") ?: YoutubeUtils.getListKeyFromAny(obj, "badges") as? List<*>
            if (badges != null) {
                for (badge in badges) {
                    val d = extractDurationFromOverlays(badge)
                    if (d.isNotBlank()) return d
                }
            }
            
            val thumbnailBottomOverlay = obj.getMapKey("thumbnailBottomOverlayViewModel")
            if (thumbnailBottomOverlay != null) {
                val d = extractDurationFromOverlays(thumbnailBottomOverlay)
                if (d.isNotBlank()) return d
            }
        } else if (obj is List<*>) {
            for (item in obj) {
                val d = extractDurationFromOverlays(item)
                if (d.isNotBlank()) return d
            }
        }
        return ""
    }

    private fun getEpisodeMetaString(videoData: Map<*, *>?): String {
        if (videoData == null) return ""
        
        var duration = ""
        val lengthText = getText(videoData.getMapKey("lengthText"))
        if (lengthText.isNotBlank()) {
            duration = DURATION_REGEX.find(lengthText)?.value ?: ""
        }

        if (duration.isBlank()) {
            duration = extractDurationFromOverlays(videoData)
        }

        var channel = getText(videoData.getMapKey("longBylineText"))
            .ifBlank { getText(videoData.getMapKey("shortBylineText")) }
            .ifBlank { getText(videoData.getMapKey("ownerText")) }

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
            if (rows != null && rows.isNotEmpty()) {
                if (rows.size == 1) {
                    val singleRow = rows.getOrNull(0)
                    if (singleRow != null) {
                        val parts = singleRow.getListKey("metadataParts") ?: YoutubeUtils.getListKeyFromAny(singleRow, "metadataParts") as? List<*>
                        val firstPart = parts?.getOrNull(0) as? Map<*, *>
                        if (firstPart != null) {
                            val rawViews = getText(firstPart.getMapKey("text") ?: firstPart["text"])
                            if (rawViews.isNotBlank() && rawViews != duration) {
                                views = rawViews
                            }
                        }
                        val secondPart = parts?.getOrNull(1) as? Map<*, *>
                        if (secondPart != null) {
                            val rawDate = getText(secondPart.getMapKey("text") ?: secondPart["text"])
                            if (rawDate.isNotBlank() && rawDate != duration) {
                                date = rawDate
                            }
                        }
                    }
                } else {
                    rows.forEachIndexed { index, row ->
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
            }
        }

        val cleanDur = duration.stripWhitespace()
        val cleanViews = views.stripWhitespace()
        val cleanDate = date.stripWhitespace()
        val cleanChannel = channel.stripWhitespace()

        val partsList = listOf(cleanDur, cleanViews, cleanDate, cleanChannel).filter { it.isNotBlank() && it != "N/A" }
        return partsList.joinToString(" • ")
    }
    
    private fun Map<*, *>.getMapKey(key: String): Map<*, *>? {
        return this[key] as? Map<*, *>
    }

    private fun Map<*, *>.getListKey(key: String): List<Map<*, *>>? {
        return this[key] as? List<Map<*, *>>
    }

    private fun Map<*, *>.getString(key: String): String? {
        val value = this[key] ?: return null
        return if (value is String) value else value.toString()
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

    private fun getMembershipLabel(data: Map<*, *>?): String? {
        if (data == null) return null
        return findMembersBadgeRecursive(data)
    }

    private fun findMembersBadgeRecursive(obj: Any?, depth: Int = 0): String? {
        if (obj == null || depth > 20) return null

        if (obj is Map<*, *>) {
            val style = obj["badgeStyle"] as? String
                ?: obj["style"] as? String
                ?: (obj["metadataBadgeRenderer"] as? Map<*, *>)?.get("style") as? String
                ?: (obj["metadataBadgeViewModel"] as? Map<*, *>)?.get("badgeStyle") as? String
                ?: (obj["badgeViewModel"] as? Map<*, *>)?.get("badgeStyle") as? String

            val iconType = (obj["icon"] as? Map<*, *>)?.get("iconType") as? String
                ?: obj["iconType"] as? String
                ?: obj["iconName"] as? String
                ?: (obj["metadataBadgeRenderer"] as? Map<*, *>)?.getMapKey("icon")?.get("iconType") as? String

            val isMembersBadge = style == "BADGE_MEMBERS_ONLY" ||
                    style == "BADGE_STYLE_TYPE_MEMBERS_ONLY" ||
                    style?.contains("MEMBERS_ONLY", ignoreCase = true) == true ||
                    iconType == "MEMBERS_ONLY" ||
                    iconType?.contains("MEMBERS", ignoreCase = true) == true

            if (isMembersBadge) {
                var label = getText(obj["badgeText"])
                if (label.isBlank()) label = getText(obj["label"])
                if (label.isBlank()) label = getText(obj["text"])
                if (label.isBlank()) label = getText(obj["tooltip"])
                if (label.isBlank()) label = getText(obj["accessibilityData"])
                if (label.isBlank()) label = getText(obj["accessibility"])

                if (label.isNotBlank()) {
                    return label
                }
            }

            for ((_, value) in obj) {
                val found = findMembersBadgeRecursive(value, depth + 1)
                if (found != null) return found
            }
        } else if (obj is List<*>) {
            for (item in obj) {
                val found = findMembersBadgeRecursive(item, depth + 1)
                if (found != null) return found
            }
        }

        return null
    }

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
        playlistId: String? = null,
        isSearch: Boolean
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

                val views = viewText.ifBlank { "N/A" }

                val membershipLabel = getMembershipLabel(videoData) ?: getMembershipLabel(renderer)
                val isMembership = !membershipLabel.isNullOrBlank()

                // 후속 에피소드 화면 구성을 위해 메모리 맵에 멤버십 상태 기록
                if (isMembership && !videoId.isNullOrBlank()) {
                    videoMembershipCache[videoId] = membershipLabel!!
                }

                val finalTitle = when {
                    channel.isNotBlank() && views.isNotBlank() && views != "N/A" -> {
                        if (isMembership) "[$channel | $views | $membershipLabel] $title"
                        else "[$channel | $views] $title"
                    }
                    channel.isNotBlank() -> {
                        if (isMembership) "[$channel | $membershipLabel] $title"
                        else "[$channel] $title"
                    }
                    views.isNotBlank() && views != "N/A" && dateText.isNotBlank() -> {
                        if (isMembership) "[$views | $dateText | $membershipLabel] $title"
                        else "[$views | $dateText] $title"
                    }
                    views.isNotBlank() && views != "N/A" -> {
                        if (isMembership) "[$views | $membershipLabel] $title"
                        else "[$views] $title"
                    }
                    else -> {
                        if (isMembership) "[$membershipLabel] $title"
                        else title
                    }
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
                    this.posterUrl = poster
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

                val currentList = if (isSearch) searchShorts else homeShorts
                val contextTag = if (isSearch) "?ctx=search" else "?ctx=home"
                val finalUrl = "$mainUrl/shorts/$videoId$contextTag"

                val descText = views.replace("\r", "").replace("\n", "").trim()
                if (descText.isNotBlank()) epMetaMapCache[videoId] = descText
                
                val newShortEp = newEpisode(finalUrl) {
                    this.name = title
                    this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                    this.description = descText
                }
                addToShortsList(currentList, newShortEp)

                val actualIndex = synchronized(currentList) { currentList.indexOfFirst { it.data == finalUrl } + 1 }
                if (actualIndex > 0) currentList[actualIndex - 1].episode = actualIndex
                val displayIndex = if (actualIndex > 0) actualIndex else currentList.size

                val finalTitle = "#$displayIndex [Shorts | $views] $title"
                
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
                        this.posterUrl = poster
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

                val membershipLabel = getMembershipLabel(lockup) ?: getMembershipLabel(renderer)
                val isMembership = !membershipLabel.isNullOrBlank()

                // 후속 에피소드 화면 구성을 위해 메모리 맵에 멤버십 상태 기록
                if (isMembership && !videoId.isNullOrBlank()) {
                    videoMembershipCache[videoId] = membershipLabel!!
                }

                if (isShorts) {
                    val currentList = if (isSearch) searchShorts else homeShorts
                    val contextTag = if (isSearch) "?ctx=search" else "?ctx=home"
                    val finalUrl = "$mainUrl/shorts/$videoId$contextTag"
                    
                    poster = YoutubeUtils.getShortsSearchPoster(videoId)
                    
                    val descText = views.stripWhitespace()
                    if (descText.isNotBlank()) epMetaMapCache[videoId] = descText
                    
                    val newShortEp2 = newEpisode(finalUrl) {
                        this.name = title
                        this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                        this.description = descText
                    }
                    addToShortsList(currentList, newShortEp2)

                    val actualIndex = synchronized(currentList) { currentList.indexOfFirst { it.data == finalUrl } + 1 }
                    if (actualIndex > 0) currentList[actualIndex - 1].episode = actualIndex
                    val displayIndex = if (actualIndex > 0) actualIndex else currentList.size

                    finalTitle = "#$displayIndex [Shorts | $views] $title"
                    return newMovieSearchResponse(
                        finalTitle,
                        finalUrl,
                        TvType.Movie
                    ) { this.posterUrl = poster }
                } else {
                    finalTitle = when {
                        channel.isNotBlank() && views.isNotBlank() -> {
                            if (isMembership) "[$channel | $views | $membershipLabel] $title"
                            else "[$channel | $views] $title"
                        }
                        channel.isNotBlank() -> {
                            if (isMembership) "[$channel | $membershipLabel] $title"
                            else "[$channel] $title"
                        }
                        views.isNotBlank() && date.isNotBlank() -> {
                            if (isMembership) "[$views | $date | $membershipLabel] $title"
                            else "[$views | $date] $title"
                        }
                        views.isNotBlank() -> {
                            if (isMembership) "[$views | $membershipLabel] $title"
                            else "[$views] $title"
                        }
                        else -> {
                            if (isMembership) "[$membershipLabel] $title"
                            else title
                        }
                    }
                    poster = YoutubeUtils.getBestThumbnail(
                        lockup.getMapKey("contentImage")?.getMapKey("image")?.getListKey("sources")
                    ) ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                    return newMovieSearchResponse(
                        finalTitle,
                        "$mainUrl/watch?v=$videoId",
                        TvType.Movie
                    ) {
                        this.posterUrl = poster
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
                    this.posterUrl = poster
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
        isSearch: Boolean = false,
        depth: Int = 0,
        playlistId: String? = null
    ) {    
        if (data == null || depth > 32) return 
        if (data is Map<*, *>) {
            val extracted = collectFromRenderer(data, seenIds, playlistId, isSearch)
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
                    processRecursive(data[key], outList, seenIds, playlistMode, isSearch, depth + 1, playlistId)
                    foundContainer = true
                }
            }
            if (!foundContainer) {
                for (value in data.values) {
                    if (value is Map<*, *> || value is List<*>) {
                        processRecursive(value, outList, seenIds, playlistMode, isSearch, depth + 1, playlistId)
                    }
                }
            }
        } else if (data is List<*>) {
            for (item in data) {
                processRecursive(item, outList, seenIds, playlistMode, isSearch, depth + 1, playlistId)
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

    private class StringRangeReader(
        private val source: String,
        private val start: Int,
        private val endExclusive: Int
    ) : java.io.Reader() {
        private var position = start

        override fun read(cbuf: CharArray, off: Int, len: Int): Int {
            if (position >= endExclusive) return -1
            val count = minOf(len, endExclusive - position)
            var i = 0
            while (i < count) {
                cbuf[off + i] = source[position + i]
                i++
            }
            position += count
            return count
        }

        override fun close() = Unit
    }

    private fun findBalancedJsonEndExclusive(input: String, startIdx: Int): Int? {
        var braceCount = 0
        var inString = false
        var escape = false
        for (i in startIdx until input.length) {
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
                    if (braceCount == 0) return i + 1
                }
            }
        }
        return null
    }

    private fun readJsonObjectFromRange(
        input: String,
        startIdx: Int,
        endExclusive: Int,
        allowedTopLevelKeys: Set<String>? = null
    ): Map<String, Any>? {
        return try {
            mapper.factory.createParser(StringRangeReader(input, startIdx, endExclusive)).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) return@use null
                val out = LinkedHashMap<String, Any>()
                while (parser.nextToken() != JsonToken.END_OBJECT) {
                    if (parser.currentToken != JsonToken.FIELD_NAME) {
                        parser.skipChildren()
                        continue
                    }
                    val fieldName = parser.currentName
                    parser.nextToken()
                    if (allowedTopLevelKeys == null || fieldName in allowedTopLevelKeys) {
                        val value = mapper.readValue(parser, Any::class.java)
                        if (value != null) out[fieldName] = value
                    } else {
                        parser.skipChildren()
                    }
                }
                out
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun extractYtInitialDataInternal(
        html: String,
        allowedTopLevelKeys: Set<String>?
    ): Map<String, Any>? {
        var index = 0
        val htmlLen = html.length
        while (true) {
            val found = html.indexOf("ytInitialData", index)
            if (found == -1) break
            val contextEnd = minOf(found + 100, htmlLen)
            var hasAssign = false

            for (i in found until contextEnd) {
                if (html[i] == '=') {
                    hasAssign = true
                    break
                }
            }

            val hasVarOrWindow =
                html.indexOf("var", found, ignoreCase = false) in found until contextEnd ||
                    html.indexOf("window", found, ignoreCase = false) in found until contextEnd ||
                    html.startsWith("ytInitialData", found)

            if (hasAssign && hasVarOrWindow) {
                val braceIndex = html.indexOf("{", found)
                if (braceIndex != -1 && braceIndex - found < 150) {
                    val endExclusive = findBalancedJsonEndExclusive(html, braceIndex)
                    if (endExclusive != null) {
                        val parsed = readJsonObjectFromRange(
                            html,
                            braceIndex,
                            endExclusive,
                            allowedTopLevelKeys
                        )
                        if (parsed != null) return parsed
                    }
                }
            }
            index = minOf(found + 13, htmlLen)
        }
        return null
    }

    private fun extractYtInitialData(html: String): Map<String, Any>? {
        return extractYtInitialDataInternal(html, null)
    }

    /** Reuse the existing watch-page HTML; no additional metadata request. */
    private fun extractVideoDetails(html: String): Map<*, *>? {
        val match = Regex("""(?:var\s+)?ytInitialPlayerResponse\s*=\s*\{""").find(html) ?: return null
        val start = match.range.last
        val end = findBalancedJsonEndExclusive(html, start) ?: return null
        val response = readJsonObjectFromRange(html, start, end, setOf("videoDetails"))
        return response?.get("videoDetails") as? Map<*, *>
    }

    /** CloudStream renders plot as HTML; escape plain text before converting newlines. */
    private fun descriptionAsHtml(text: String): String =
        text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\r\n", "\n").replace('\r', '\n').replace("\n", "<br>")

    private fun channelDescriptionFromData(data: Map<String, Any>?): String =
        ((safeGet(data, "metadata", "channelMetadataRenderer", "description") as? String)
            ?.takeIf { it.isNotBlank() }
            ?: (safeGet(data, "microformat", "microformatDataRenderer", "description") as? String))
            .orEmpty()

    /** New YouTube channel layouts can place the About text in nested view models. */
    private fun aboutViewDescription(node: Any?, depth: Int = 0): String {
        if (depth > 24) return ""
        return when (node) {
            is Map<*, *> -> {
                val about = node["aboutChannelViewModel"] as? Map<*, *>
                val description = getText(about?.get("description"))
                if (description.isNotBlank()) description else
                    node.values.firstNotNullOfOrNull { value ->
                        if (value is Map<*, *> || value is List<*>) {
                            aboutViewDescription(value, depth + 1).takeIf { it.isNotBlank() }
                        } else null
                    }.orEmpty()
            }
            is List<*> -> node.firstNotNullOfOrNull { value ->
                if (value is Map<*, *> || value is List<*>) {
                    aboutViewDescription(value, depth + 1).takeIf { it.isNotBlank() }
                } else null
            }.orEmpty()
            else -> ""
        }
    }

    private fun extractHomeInitialData(html: String): Map<String, Any>? {
        return extractYtInitialDataInternal(html, setOf("contents"))
    }

    private fun findTokenRecursive(data: Any?): String? {
        if (data is Map<*, *>) {
            val renderer = data["continuationItemRenderer"] as? Map<*, *>
            val rendererToken = extractContinuationRendererToken(renderer)
            if (!rendererToken.isNullOrBlank()) {
                return rendererToken
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
            val currentLang = lang
            val currentCustomSectionsJson = appPrefs?.getString("custom_homepages_v3", "[]") ?: "[]"

            val fastCached = sharedCachedMainPageList
            if (fastCached != null &&
                currentLang == sharedLastLang &&
                currentCustomSectionsJson == sharedLastCustomSectionsJson
            ) {
                return fastCached
            }

            return synchronized(mainPageCacheLock) {
                val lockedCached = sharedCachedMainPageList
                if (lockedCached != null &&
                    currentLang == sharedLastLang &&
                    currentCustomSectionsJson == sharedLastCustomSectionsJson
                ) {
                    return@synchronized lockedCached
                }

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
                                list.add(
                                    MainPageData(
                                        activeChip.name,
                                        "chip_name_${activeChip.name}",
                                        horizontalImages = true
                                    )
                                )
                            } else {
                                Log.w(
                                    "YoutubeUtils",
                                    "[CHIP_DEBUG] mainPage - Skipped enabled chip '$chipName' because it's missing from cachedChipsMap!"
                                )
                            }
                        }
                        else -> {
                            var title = section.name
                            if (title.isBlank()) title = extractNameFromUrl(url)
                            list.add(MainPageData(title, url, horizontalImages = true))
                        }
                    }
                }

                sharedLastLang = currentLang
                sharedLastCustomSectionsJson = currentCustomSectionsJson
                sharedCachedMainPageList = list
                list
            }
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
            
            cachedChips.forEach { chip ->
                val stableUrl = "chip_name_${chip.name}"
                if (currentList.none { section -> section.url == stableUrl }) {
                    currentList.add(CustomSection(name = chip.name, url = stableUrl, isEnabled = false))
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

    private fun getFreshPreparedHomePage(): PreparedHomePage? {
        val prepared = sharedPreparedHomePage ?: return null
        val age = System.currentTimeMillis() - prepared.createdAtMs
        return prepared.takeIf {
            it.language == lang && age in 0L..PREPARED_HOME_REUSE_MS
        }
    }

    private suspend fun loadDirectPage1(requestData: String): DirectPageResult? {
        awaitStartupDirectRequestSettle()
        return youtubePayloadSemaphore.withPermit {
            currentCoroutineContext().ensureActive()
            val targetUrl = if (requestData == "Home") mainUrl else requestData
            val html = app.get(
                targetUrl,
                interceptor = ytInterceptor,
                headers = mapOf("Cache-Control" to "no-cache", "Pragma" to "no-cache")
            ).textLarge
            currentCoroutineContext().ensureActive()

            savedApiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY") ?: savedApiKey
            savedVisitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA") ?: savedVisitorData
            savedClientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION")
                ?: savedClientVersion
                ?: YoutubeUtils.DEFAULT_CLIENT_VERSION

            if (!savedApiKey.isNullOrBlank()) {
                appPrefs?.edit()
                    ?.putString("yt_api_key", savedApiKey)
                    ?.putString("yt_visitor_data", savedVisitorData)
                    ?.putString("yt_client_version", savedClientVersion)
                    ?.apply()
            }

            val initialData = if (requestData == "Home") {
                extractHomeInitialData(html)
            } else {
                extractYtInitialData(html)
            } ?: return@withPermit null

            if (requestData == "Home") {
                val chips = extractChipTokens(initialData)
                if (chips.isNotEmpty()) {
                    mergeAndSaveChips(chips.map { CachedChip(it.key, it.value) })
                    publishChipBootstrapFromHome(
                        savedApiKey,
                        savedClientVersion,
                        savedVisitorData,
                        chips
                    )
                }
            }

            val playlistId = if (requestData.contains("list=")) {
                requestData.substringAfter("list=", "").substringBefore("&")
            } else null

            val directResults = mutableListOf<SearchResponse>()
            val directSeenIds = mutableSetOf<String>()
            processRecursive(
                initialData,
                directResults,
                directSeenIds,
                playlistMode = false,
                isSearch = false,
                playlistId = playlistId
            )
            val next = findTokenRecursive(initialData)

            val result = DirectPageResult(directResults.toList(), next)
            if (requestData == "Home") {
                sharedPreparedHomePage = PreparedHomePage(
                    language = lang,
                    items = result.items,
                    nextToken = result.nextToken,
                    createdAtMs = System.currentTimeMillis()
                )
            }
            result
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val rawRequestData = request.data
        val requestData = YoutubeUtils.urlDecode(rawRequestData)
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            return newHomePageResponse(request, emptyList(), hasNext = false)
        }

        val results = mutableListOf<SearchResponse>()
        val seenIds = mutableSetOf<String>()
        var nextContinuation: String? = null
        val isChip = requestData.startsWith("chip_")

        try {
            if (isChip) {
                if (page == 1) {
                    val bootstrap = ensureChipBootstrap()
                    if (bootstrap == null) {
                        Log.w("YoutubeUtils", "[CHIP] page=1 bootstrap unavailable key=$requestData")
                    } else {
                        val persistedChips = readPersistedChips()
                        val chipName: String?
                        val tokenToUse: String?

                        if (requestData.startsWith("chip_name_")) {
                            chipName = requestData.removePrefix("chip_name_")
                            val freshToken = bootstrap.chips[chipName]
                            val persistedToken = persistedChips.firstOrNull { it.name == chipName }?.token
                            tokenToUse = freshToken ?: persistedToken
                        } else {
                            val legacyToken = requestData.removePrefix("chip_")
                            chipName = persistedChips.firstOrNull { it.token == legacyToken }?.name
                            val freshToken = chipName?.let { bootstrap.chips[it] }
                            tokenToUse = freshToken ?: legacyToken
                        }

                        if (tokenToUse.isNullOrBlank()) {
                            Log.w(
                                "YoutubeUtils",
                                "[CHIP] page=1 token missing key=$requestData name=${chipName ?: "unknown"} freshChips=${bootstrap.chips.size} persisted=${persistedChips.size}"
                            )
                        } else {
                            val browse = executeChipBrowseAndProcess(bootstrap, tokenToUse, requestData, 1)
                            if (browse != null) {
                                results.addAll(browse.items)
                                val nextToken = browse.nextToken
                                nextContinuation = nextToken
                                if (results.isEmpty()) {
                                    Log.w("YoutubeUtils", "[CHIP] NO_ITEMS page=1 key=$requestData")
                                }

                                if (results.isNotEmpty() || !nextToken.isNullOrBlank()) {
                                    val newSession = ChipCatalogSession(bootstrap)
                                    if (!nextToken.isNullOrBlank()) {
                                        newSession.pageTokens[2] = nextToken
                                    }
                                    chipCatalogSessions[requestData] = newSession
                                } else {
                                    Log.w("YoutubeUtils", "[CHIP] SESSION_NOT_COMMITTED page=1 key=$requestData")
                                }

                            } else {
                                Log.w("YoutubeUtils", "[CHIP] BROWSE_EMPTY_RESPONSE page=1 key=$requestData")
                            }
                        }
                    }
                } else {
                    val session = chipCatalogSessions[requestData]
                    val tokenToUse = session?.pageTokens?.get(page)
                    if (session == null) {
                        Log.w("YoutubeUtils", "[CHIP] SESSION_MISSING page=$page key=$requestData")
                    } else if (tokenToUse.isNullOrBlank()) {
                        Log.w("YoutubeUtils", "[CHIP] PAGE_TOKEN_MISSING page=$page key=$requestData")
                    } else {
                        val browse = executeChipBrowseAndProcess(session.bootstrap, tokenToUse, requestData, page)
                        if (browse != null) {
                            results.addAll(browse.items)
                            val nextToken = browse.nextToken
                            nextContinuation = nextToken
                            if (results.isEmpty()) {
                                Log.w("YoutubeUtils", "[CHIP] NO_ITEMS page=$page key=$requestData")
                            }
                            if (!nextToken.isNullOrBlank()) {
                                session.pageTokens[page + 1] = nextToken
                            } else {
                                session.pageTokens.remove(page + 1)
                            }

                        } else {
                            Log.w("YoutubeUtils", "[CHIP] BROWSE_EMPTY_RESPONSE page=$page key=$requestData")
                        }
                    }
                }
            } else if (page == 1) {
                if (requestData == "Home") {
                    ensureChipBootstrap()
                    currentCoroutineContext().ensureActive()
                    val prepared = getFreshPreparedHomePage()
                    if (prepared != null) {
                        results.addAll(prepared.items)
                        nextContinuation = prepared.nextToken
                    } else {
                        val direct = loadDirectPage1(requestData)
                        if (direct != null) {
                            results.addAll(direct.items)
                            nextContinuation = direct.nextToken
                        }
                    }
                } else {
                    val direct = loadDirectPage1(requestData)
                    if (direct != null) {
                        results.addAll(direct.items)
                        nextContinuation = direct.nextToken
                    }
                }
            } else {
                var tokenToUse = getSavedContinuationToken(requestData)

                if (!tokenToUse.isNullOrBlank() && results.isEmpty()) {
                    val apiKey = savedApiKey ?: appPrefs?.getString("yt_api_key", null)
                    val cVersion = savedClientVersion
                        ?: appPrefs?.getString("yt_client_version", YoutubeUtils.DEFAULT_CLIENT_VERSION)
                        ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                    val apiUrl = if (requestData.contains("/results")) {
                        "$mainUrl/youtubei/v1/search?key=$apiKey"
                    } else {
                        "$mainUrl/youtubei/v1/browse?key=$apiKey"
                    }
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
                    val headers = mapOf("Origin" to mainUrl, "Referer" to mainUrl)
                    val response = app.post(
                        apiUrl,
                        json = payload,
                        headers = headers,
                        interceptor = ytInterceptor
                    ).parsedSafe<Map<String, Any>>()
                    currentCoroutineContext().ensureActive()

                    if (response != null) {
                        val actions = response["onResponseReceivedActions"]
                            ?: response["onResponseReceivedEndpoints"]
                            ?: response["onResponseReceivedCommands"]
                            ?: response["continuationContents"]
                            ?: response

                        val playlistId = if (requestData.contains("list=")) {
                            requestData.substringAfter("list=", "").substringBefore("&")
                        } else null

                        processRecursive(
                            actions,
                            results,
                            seenIds,
                            playlistMode = false,
                            isSearch = false,
                            playlistId = playlistId
                        )
                        nextContinuation = findTokenRecursive(response)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            logError(e)
        }

        if (!isChip) {
            saveContinuationToken(requestData, nextContinuation)
        }

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

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        val maxPages = appPrefs?.getInt("channel_pages_limit", 36) ?: 36
        if (page > maxPages) {
            return newSearchResponseList(emptyList(), false)
        }

        val results = mutableListOf<SearchResponse>()
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
        
        try {
            if (page == 1) {
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
                    processRecursive(initialData, results, seenIds, playlistMode, isSearch = true)
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
                        processRecursive(actions, results, seenIds, playlistMode, isSearch = true)
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

    private fun findContinuationItemsRecursive(obj: Any?, depth: Int = 0): List<*>? {
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

    private fun extractVideosFromItems(items: List<*>, collectTo: MutableList<Episode>, isShortsTab: Boolean = false) {
        items.forEachIndexed { index, item ->
            try {
                val map = item as? Map<*, *>
                if (map == null) return@forEachIndexed
                if (map.containsKey("continuationItemRenderer")) return@forEachIndexed

                val foundRenderers = mutableListOf<Map<*, *>>()
                findAllRenderers(map, foundRenderers)

                if (foundRenderers.isEmpty()) return@forEachIndexed

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
                        if (vidTitle.isBlank()) vidTitle = getText(safeGet(renderer, "overlayMetadata", "primaryText"))
                        if (vidTitle.isBlank()) {
                            val accessibilityText = renderer["accessibilityText"] as? String ?: ""
                            if (accessibilityText.isNotBlank()) {
                                vidTitle = accessibilityText.substringBefore(",").trim()
                            }
                        }
                        if (vidTitle.isBlank()) vidTitle = getText(renderer["shortBylineText"])
                        if (vidTitle.isBlank()) vidTitle = getText(safeGet(renderer, "metadata", "lockupMetadataViewModel", "title"))
                        if (vidTitle.isBlank()) vidTitle = "Video"

                        val membershipLabel = getMembershipLabel(renderer) ?: getMembershipLabel(item as? Map<*, *>)
                        if (!membershipLabel.isNullOrBlank()) {
                            vidTitle = "[$membershipLabel] $vidTitle"
                        }

                        val thumb = YoutubeUtils.getBestThumbnail(renderer["thumbnail"]) 
                            ?: YoutubeUtils.getBestThumbnail(safeGet(renderer, "contentImage", "image", "sources")) 
                            ?: YoutubeUtils.buildThumbnailFromId(vidId)

                        var metaString = getEpisodeMetaString(renderer)
                        if (metaString.isBlank()) {
                            var viewRaw = getText(safeGet(renderer, "overlayMetadata", "secondaryText"))
                            if (viewRaw.isBlank()) {
                                viewRaw = getText(renderer["viewCountText"] ?: renderer["shortViewCountText"])
                            }
                            if (viewRaw.isBlank()) {
                                viewRaw = getText(safeGet(renderer, "metadata", "lockupMetadataViewModel", "viewCount"))
                            }
                            if (viewRaw.isBlank()) {
                                try {
                                    val rows = safeGet(renderer, "metadata", "lockupMetadataViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                                    val firstRow = rows?.firstOrNull()
                                    val parts = safeGet(firstRow, "metadataParts") as? List<*>
                                    val firstPart = parts?.firstOrNull()
                                    if (firstPart != null) {
                                        viewRaw = getText(safeGet(firstPart, "text"))
                                    }
                                } catch (_: Exception) {}
                            }
                            metaString = viewRaw.stripWhitespace().ifBlank { "\u200b" }
                        }

                        val videoUrl = if (isShortsTab) "$mainUrl/shorts/$vidId" else "$mainUrl/watch?v=$vidId"
                        collectTo.add(newEpisode(videoUrl) {
                            this.name = vidTitle
                            this.posterUrl = if (isShortsTab)
                                vidId?.let(YoutubeUtils::getShortsEpisodeThumbnail) ?: thumb else thumb
                            this.description = metaString
                        })
                        break 
                    }
                }
            } catch (e: Exception) {
            }
        }
    }
    
    private fun extractEpisodesFromData(data: Map<String, Any>?): List<Episode> {
        if (data == null) return emptyList()
        val episodes = mutableListOf<Episode>()
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
            extractVideosFromItems(initialItems, episodes)
        }
        return episodes
    }

    private fun findAllRenderers(obj: Any?, outRenderers: MutableList<Map<*, *>>, depth: Int = 0) {
        if (obj == null || depth > 32) return
        when (obj) {
            is Map<*, *> -> {
                for ((k, v) in obj) {
                    if (k is String && TARGET_RENDERER_KEYS.contains(k)) {
                        val match = v as? Map<*, *>
                        if (match != null) outRenderers.add(match)
                    }
                }
                for (value in obj.values) {
                    findAllRenderers(value, outRenderers, depth + 1)
                }
            }
            is List<*> -> {
                for (item in obj) {
                    findAllRenderers(item, outRenderers, depth + 1)
                }
            }
        }
    }

    private class PlaylistMetadata {
        var title: String = ""
        var author: String = ""
        var videoAuthor: String = ""
        var description: String = ""
        var channelId: String = ""
        var channelAvatar: String = ""
        var subscriberCount: String = ""
        var videoCount: String = ""
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

                val stats = primary["stats"] as? List<*> ?: YoutubeUtils.getListKeyFromAny(primary, "stats")
                if (stats != null && stats.isNotEmpty()) {
                    val vCount = getText(stats[0])
                    if (vCount.isNotBlank()) metadata.videoCount = vCount
                }
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

                val stats = header["stats"] as? List<*> ?: header["briefStats"] as? List<*> ?: YoutubeUtils.getListKeyFromAny(header, "briefStats")
                if (stats != null && stats.isNotEmpty()) {
                    val vCount = getText(stats[0])
                    if (vCount.isNotBlank() && metadata.videoCount.isBlank()) {
                        metadata.videoCount = vCount
                    }
                }
                
                val numVideos = getText(header["numVideosText"] ?: YoutubeUtils.getMapKeyFromAny(header, "numVideosText"))
                if (numVideos.isNotBlank() && metadata.videoCount.isBlank()) metadata.videoCount = numVideos
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

    private val directPageCache = ConcurrentHashMap<String, TvSeriesLoadResponse>()

    /** One-shot detail cache, valid for the entire playback regardless of duration. */
    override suspend fun load(url: String): LoadResponse {
        directPageCache.remove(url)?.let { return it }
        return loadPage(url, direct = false)
    }

    /** Construct the initial player episode list from the home card, with no HTTP. */
    internal suspend fun quickPlaybackPage(card: SearchResponse): TvSeriesLoadResponse {
        val url = card.url
        val videoId = YoutubeUtils.extractYoutubeId(url) ?: error("Invalid YouTube video")
        val shorts = url.contains("/shorts/")
        val list = if (shorts) {
            val source = if (url.contains("ctx=search")) searchShorts else homeShorts
            synchronized(source) { source.map { it.copy() } }
        } else emptyList()
        val episodes = if (list.isNotEmpty() &&
            list.any { YoutubeUtils.extractYoutubeId(it.data) == videoId }) {
            list.onEachIndexed { index, episode -> episode.episode = index + 1 }
        } else listOf(newEpisode(url) {
            name = card.name
            posterUrl = card.posterUrl
            episode = 1
        })
        return newTvSeriesLoadResponse(card.name, url, TvType.TvSeries, episodes) {
            posterUrl = card.posterUrl
            plot = "\u200b"
        }
    }

    /** Supply a network-free detail fallback for Back, for both videos and Shorts. */
    internal fun cacheDirectReturnPage(url: String, page: TvSeriesLoadResponse) {
        if (directPageCache.size >= 16) directPageCache.clear()
        directPageCache[url] = page
    }

    /** Fetch HTML metadata only after stream links resolve; never block initial playback. */
    internal suspend fun prefetchDirectDetails(url: String): TvSeriesLoadResponse? {
        val page = loadPage(url, direct = true)
            as? TvSeriesLoadResponse ?: return null
        if (directPageCache.size >= 16) directPageCache.clear()
        directPageCache[url] = page
        return page
    }

    /** HTML-only detail path; initial playback uses quickPlaybackPage(). */
    internal suspend fun loadForDirectPlayback(url: String): LoadResponse =
        loadPage(url, direct = true)

    private suspend fun loadPage(url: String, direct: Boolean): LoadResponse {
        if (url.contains("/shorts/")) {
            val videoId = YoutubeUtils.extractYoutubeId(url) ?: "video"
            val useSearchList = url.contains("ctx=search")
            val sourceList = if (useSearchList) searchShorts else homeShorts
            var targetEpisodes = synchronized(sourceList) { sourceList.toMutableList() }
            
            targetEpisodes.forEachIndexed { index, ep -> ep.episode = index + 1 }
            
            val currentEp = targetEpisodes.find { YoutubeUtils.extractYoutubeId(it.data) == videoId }
            val currentEpIndex = currentEp?.episode ?: 1

            var shortTitle = currentEp?.name ?: "Shorts Video"
            var channelName = ""
            var channelId = ""
            var channelAvatar = ""
            var subscriberCount = ""
            val watchUrl = "https://www.youtube.com/watch?v=$videoId"
            val response = withContext(Dispatchers.IO) {
                try {
                    app.get(watchUrl, headers = mapOf("Referer" to "https://www.youtube.com/"), interceptor = ytInterceptor)
                } catch (e: Exception) {
                    logError(e)
                    null
                }
            }
            val html = response?.text ?: ""
            val videoDetails = extractVideoDetails(html)
            val htmlTags = (videoDetails?.get("keywords") as? List<*>)
                ?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.take(10).orEmpty()
            val htmlUploader = (videoDetails?.get("author") as? String).orEmpty()
            val data = extractYtInitialData(html)

            val recs = mutableListOf<SearchResponse>()

            if (data != null) {
                val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>
                resultsContents?.forEach { item ->
                    val m = item as? Map<*, *>
                    val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                    if (primary != null) {
                        val t = extractTitle(primary["title"] as? Map<*, *>)
                        if (!t.isNullOrBlank()) shortTitle = t
                    }
                    val secondary = m?.get("videoSecondaryInfoRenderer") as? Map<*, *>
                    if (secondary != null) {
                        val owner = safeGet(secondary, "owner", "videoOwnerRenderer") as? Map<*, *>
                        if (owner != null) {
                            channelName = extractTitle(owner["title"] as? Map<*, *>) ?: ""
                            channelAvatar = YoutubeUtils.getBestThumbnail(owner["thumbnail"]) ?: ""
                            subscriberCount = getText(owner["subscriberCountText"])
                            
                            channelId = safeGet(owner, "navigationEndpoint", "browseEndpoint", "browseId") as? String ?: ""
                            if (channelId.isEmpty()) {
                                val curl = safeGet(owner, "navigationEndpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                                if (!curl.isNullOrBlank()) channelId = curl.substringAfterLast("/")
                            }
                        }
                    }
                }
                
                val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
                if (secondaryResults != null) {
                    processRecursive(secondaryResults, recs, mutableSetOf(videoId), false)
                }
                
                if (targetEpisodes.size <= 1) {
                    val recEpisodes = mutableListOf<Episode>()
                    recEpisodes.add(newEpisode(url) {
                        this.name = shortTitle
                        this.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(videoId)
                        this.episode = 1
                    })
                    var epCount = 2
                    recs.forEach { rec ->
                        if (rec.url.contains("/shorts/")) {
                            val recVidId = YoutubeUtils.extractYoutubeId(rec.url)
                            val cleanName = rec.name.replaceFirst(Regex("^\\[.*?\\]\\s*"), "")
                            
                            // 쇼츠 에피소드 리스트 멤버십 대괄호 복원 적용
                            val recMembership = if (recVidId != null) videoMembershipCache[recVidId] else null
                            val finalShortsName = if (!recMembership.isNullOrBlank()) {
                                "[$recMembership] $cleanName"
                            } else {
                                cleanName
                            }

                            val recUrl = "$mainUrl/shorts/$recVidId?ctx=home"
                            recEpisodes.add(newEpisode(recUrl) {
                                this.name = finalShortsName
                                this.posterUrl = recVidId?.let { YoutubeUtils.getShortsEpisodeThumbnail(it) } ?: rec.posterUrl
                                this.episode = epCount++
                                val extraMeta = if (recVidId != null) epMetaMapCache[recVidId] else ""
                                this.description = extraMeta
                            })
                        }
                    }
                    targetEpisodes = recEpisodes
                }
            } else {
                targetEpisodes.forEach { ep ->
                    val epVidId = YoutubeUtils.extractYoutubeId(ep.data)
                    if (epVidId != null) {
                        ep.posterUrl = YoutubeUtils.getShortsEpisodeThumbnail(epVidId)
                        val cachedMeta = epMetaMapCache[epVidId]
                        if (!cachedMeta.isNullOrBlank()) ep.description = cachedMeta
                    }
                }
            }

            var ep1Views = ""
            val foundRenderers = mutableListOf<Map<*, *>>()
            findAllRenderers(data, foundRenderers)
            
            val viewCountRenderer = foundRenderers.firstOrNull { it.containsKey("shortViewCount") }
            if (viewCountRenderer != null) {
                val vCount = getText(viewCountRenderer["shortViewCount"] ?: viewCountRenderer["viewCount"])
                if (vCount.isNotBlank()) {
                    ep1Views = vCount.stripWhitespace()
                }
            }

            if (ep1Views.isBlank()) {
                val viewMatch = VIEW_COUNT_REGEX.find(html)
                if (viewMatch != null) ep1Views = viewMatch.groupValues[1].stripWhitespace()
            }
            
            val cViews = ep1Views.ifBlank { "Playing" }

            val mainTargetEp = targetEpisodes.find { it.episode == currentEpIndex }
            if (mainTargetEp != null) {
                mainTargetEp.description = cViews
            }

            val finalTitle = "#$currentEpIndex $shortTitle".replace("##", "#")
            val finalPlot = "\u200b"

            rememberChannel(url, channelName, channelId)
            val finalRecs = mutableListOf<SearchResponse>()
            
            finalRecs.addAll(recs.filter { it.url.contains("/shorts/") }) 
            
            val episodeResponses = targetEpisodes.map { ep ->
                val desc = ep.description ?: ""
                val formattedTitle = if (desc.isNotBlank() && desc != "N/A" && !desc.contains("Playing")) {
                    "#${ep.episode} [Shorts | $desc] ${ep.name}"
                } else {
                    "#${ep.episode} ${ep.name}"
                }
                
                newMovieSearchResponse(formattedTitle, ep.data, TvType.Movie) {
                    this.posterUrl = ep.posterUrl
                }
            }            
            
            finalRecs.addAll(episodeResponses)

            Log.i("YouTubeMeta", "[HTML_META] type=shorts id=$videoId html=${response != null} " +
                "player=${videoDetails != null} plot=hidden tags=${htmlTags.size} " +
                "uploader=${if (channelName.isNotBlank()) "initialData" else if (htmlUploader.isNotBlank()) "player" else "none"} direct=$direct")
            return newTvSeriesLoadResponse(finalTitle, url, TvType.TvSeries, targetEpisodes) {
                this.posterUrl = YoutubeUtils.getShortsSearchPoster(videoId)
                this.plot = finalPlot
                this.recommendations = finalRecs

                if (htmlTags.isNotEmpty()) this.tags = htmlTags

                val actorList = mutableListOf<ActorData>()
                if (channelName.isNotBlank()) {
                    actorList.add(ActorData(Actor(channelName, channelAvatar)))
                } else if (htmlUploader.isNotBlank()) {
                    actorList.add(ActorData(Actor(htmlUploader, channelAvatar)))
                }
                if (subscriberCount.isNotBlank()) {
                    actorList.add(ActorData(Actor(subscriberCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Subscribe.jpg")))
                }
                if (actorList.isNotEmpty()) {
                    this.actors = actorList
                }
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
                
                val response = withContext(Dispatchers.IO) {
                    app.get(channelUrl, interceptor = ytInterceptor)
                }

                val html = response.text
                val data = extractYtInitialData(html) ?: throw ErrorLoadingException("Failed to extract channel data")

                val apiKey = YoutubeUtils.findConfig(html, "INNERTUBE_API_KEY")
                val clientVersion = YoutubeUtils.findConfig(html, "INNERTUBE_CLIENT_VERSION") ?: YoutubeUtils.DEFAULT_CLIENT_VERSION
                val visitorData = YoutubeUtils.findConfig(html, "VISITOR_DATA")
                val maxPagesLimit = appPrefs?.getInt("channel_pages_limit", 36) ?: 36

                var channelDescription = channelDescriptionFromData(data)
                var channelDescSource = if (channelDescription.isNotBlank()) "videos_metadata" else "none"
                var aboutFetched = false
                if (channelDescription.isBlank()) {
                    aboutFetched = true
                    try {
                        val aboutResponse = withContext(Dispatchers.IO) {
                            app.get("$cleanUrl/about", interceptor = ytInterceptor)
                        }
                        val aboutData = extractYtInitialData(aboutResponse.text)
                        channelDescription = channelDescriptionFromData(aboutData)
                        if (channelDescription.isNotBlank()) channelDescSource = "about_metadata"
                        if (channelDescription.isBlank()) {
                            channelDescription = aboutViewDescription(aboutData)
                            if (channelDescription.isNotBlank()) channelDescSource = "about_view"
                        }
                        if (channelDescription.isBlank()) {
                            channelDescription = aboutResponse.document
                                .selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
                            if (channelDescription.isNotBlank()) channelDescSource = "about_og"
                        }
                    } catch (e: Exception) {
                        Log.w("YouTubeMeta", "[HTML_META] type=channel id=$cleanUrl about_error=${e.javaClass.simpleName}")
                        logError(e)
                    }
                }
                if (channelDescription.isBlank()) {
                    channelDescription = response.document
                        .selectFirst("meta[property=og:description]")?.attr("content").orEmpty()
                    if (channelDescription.isNotBlank()) channelDescSource = "videos_og"
                }
                Log.i("YouTubeMeta", "[HTML_META] type=channel id=$cleanUrl source=$channelDescSource " +
                    "chars=${channelDescription.length} lines=${channelDescription.count { it == '\n' } + 1} " +
                    "aboutFetched=$aboutFetched")
                
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

                if (channelDescription.isBlank()) {
                    channelDescription = "\u200b"
                }

                var subscriberCount = ""
                var videoCount = ""
                val metaParts = mutableListOf<String>()

                val rows = safeGet(header, "content", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                    ?: safeGet(header, "metadata", "pageHeaderViewModel", "metadata", "contentMetadataViewModel", "metadataRows") as? List<*>
                
                rows?.forEach { row ->
                    val parts = safeGet(row, "metadataParts") as? List<*>
                    parts?.forEach { part ->
                        val text = getText(safeGet(part, "text"))
                        if (text.isNotBlank()) {
                            metaParts.add(text)
                        }
                    }
                }

                if (metaParts.isNotEmpty()) {
                    if (metaParts.size >= 3) {
                        subscriberCount = metaParts[metaParts.size - 2]
                        videoCount = metaParts[metaParts.size - 1]
                    } else if (metaParts.size == 2) {
                        subscriberCount = metaParts[0]
                        videoCount = metaParts[1]
                    }
                } else {
                    subscriberCount = getText(safeGet(header, "subscriberCountText"))
                    videoCount = getText(safeGet(header, "videosCountText") ?: safeGet(header, "videoCountText"))
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

                suspend fun fetchTab(tabUrl: String): List<Episode> {
                    val tabEpisodes = mutableListOf<Episode>()
                    try {
                        val tabHtml = app.get(tabUrl, interceptor = ytInterceptor).text
                        val tabData = extractYtInitialData(tabHtml) ?: return emptyList()

                        var initialItems: List<*>? = null
                        val tabs = safeGet(tabData, "contents", "twoColumnBrowseResultsRenderer", "tabs") as? List<*>
                        if (tabs != null) {
                            val targetPath = when {
                                tabUrl.endsWith("/videos") -> "/videos"
                                tabUrl.endsWith("/shorts") -> "/shorts"
                                else -> ""
                            }

                            val matchingTab = tabs.firstOrNull { tab ->
                                val commandUrl = safeGet(tab, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") as? String
                                commandUrl != null && targetPath.isNotEmpty() && commandUrl.endsWith(targetPath)
                            }

                            val tabToUse = matchingTab ?: run {
                                val selectedTab = tabs.firstOrNull { tab ->
                                    safeGet(tab, "tabRenderer", "selected") == true
                                }
                                val selectedUrl = safeGet(selectedTab, "tabRenderer", "endpoint", "commandMetadata", "webCommandMetadata", "url") as? String

                                if (targetPath == "/videos" && selectedUrl?.endsWith("/shorts") == true) {
                                    return emptyList()
                                }
                                if (targetPath == "/shorts" && selectedUrl?.endsWith("/videos") == true) {
                                    return emptyList()
                                }
                                selectedTab
                            }

                            val activeContent = safeGet(tabToUse, "tabRenderer", "content") as? Map<*, *>
                            if (activeContent?.containsKey("richGridRenderer") == true) {
                                initialItems = safeGet(activeContent, "richGridRenderer", "contents") as? List<*>
                            } else if (activeContent?.containsKey("gridRenderer") == true) {
                                initialItems = safeGet(activeContent, "gridRenderer", "items") as? List<*>
                            }

                            if (initialItems == null && targetPath.isEmpty()) {
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
                            extractVideosFromItems(initialItems, tabEpisodes, tabUrl.endsWith("/shorts"))
                        }

                        var currentToken: String? = findContinuationFromItems(initialItems)
                        if (currentToken.isNullOrBlank()) {
                            val conts = findContinuationItemsRecursive(tabData)
                            currentToken = findContinuationFromItems(conts)
                        }

                        var pagesFetchedLocal = 1
                        while (!currentToken.isNullOrBlank() && pagesFetchedLocal < maxPagesLimit && !apiKey.isNullOrBlank()) {
                            try {
                                pagesFetchedLocal += 1
                                val apiUrl = "$mainUrl/youtubei/v1/browse?key=$apiKey"
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
                                val javaContinuationItems = findContinuationItemsRecursive(jsonResponse) ?: break
                                extractVideosFromItems(javaContinuationItems, tabEpisodes, tabUrl.endsWith("/shorts"))
                                currentToken = findContinuationFromItems(javaContinuationItems)
                            } catch (e: Exception) {
                                break
                            }
                        }
                    } catch (e: Exception) {
                        logError(e)
                    }
                    return tabEpisodes
                }
                
                val pair: Pair<List<Episode>, List<Episode>> = kotlinx.coroutines.coroutineScope {
                    val videosDeferred = async { fetchTab("$cleanUrl/videos") }
                    val shortsDeferred = async { fetchTab("$cleanUrl/shorts") }
                    Pair(videosDeferred.await(), shortsDeferred.await())
                }

                val videosEpisodes = pair.first
                val shortsEpisodes = pair.second

                val allEpisodes = mutableListOf<Episode>()

                if (videosEpisodes.isNotEmpty()) {
                    videosEpisodes.forEachIndexed { index, ep ->
                        ep.season = 1
                        ep.episode = index + 1
                        allEpisodes.add(ep)
                    }
                    if (shortsEpisodes.isNotEmpty()) {
                        shortsEpisodes.forEachIndexed { index, ep ->
                            ep.season = 2
                            ep.episode = index + 1
                            allEpisodes.add(ep)
                        }
                    }
                } else if (shortsEpisodes.isNotEmpty()) {
                    shortsEpisodes.forEachIndexed { index, ep ->
                        ep.season = 1
                        ep.episode = index + 1
                        allEpisodes.add(ep)
                    }
                }

                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, allEpisodes) {
                    this.posterUrl = poster
                    this.plot = descriptionAsHtml(channelDescription).ifBlank { "\u200b" }

                    val actorList = mutableListOf<ActorData>()
                    actorList.add(ActorData(Actor(title, poster ?: "")))
                    
                    if (subscriberCount.isNotBlank()) {
                        actorList.add(ActorData(Actor(subscriberCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Subscribe.jpg")))
                    }
                    if (videoCount.isNotBlank()) {
                        actorList.add(ActorData(Actor(videoCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Videos.jpg")))
                    }
                    if (actorList.isNotEmpty()) {
                        this.actors = actorList
                    }
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

                val seenPlaylistIds = mutableSetOf<String>()
                val episodes = extractPlaylistEpisodes(data, seenPlaylistIds).toMutableList()

                title = YoutubeUtils.cleanMixTitle(title)

                episodes.forEachIndexed { index, ep ->
                    ep.episode = index + 1
                }

                val playlistPoster = episodes.firstOrNull()?.posterUrl ?: response.document.selectFirst("meta[property=og:image]")?.attr("content")

                rememberChannel(url, author, playlistMeta.channelId)
                val recList = mutableListOf<SearchResponse>()
                
                val episodeResponses = episodes.map { ep ->
                    val desc = ep.description ?: ""
                    val formattedTitle = if (desc.isNotBlank() && desc != "\u200b") {
                        "#${ep.episode} [$desc] ${ep.name}"
                    } else {
                        "#${ep.episode} ${ep.name}"
                    }
                    
                    newMovieSearchResponse(formattedTitle, ep.data, TvType.Movie) {
                        this.posterUrl = ep.posterUrl
                    }
                }
               
                recList.addAll(episodeResponses)
                
                return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                    this.posterUrl = playlistPoster
                    this.recommendations = recList
                    
                    this.plot = "\u200b" 
                    
                    val actorList = mutableListOf<ActorData>()
                    if (author.isNotBlank()) {
                        actorList.add(ActorData(Actor(author, playlistMeta.channelAvatar)))
                    }
                    if (playlistMeta.subscriberCount.isNotBlank()) {
                        actorList.add(ActorData(Actor(playlistMeta.subscriberCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Subscribe.jpg")))
                    }
                    if (playlistMeta.videoCount.isNotBlank()) {
                        actorList.add(ActorData(Actor(playlistMeta.videoCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Videos.jpg")))
                    }
                    if (actorList.isNotEmpty()) {
                        this.actors = actorList
                    }
                }
                
            } catch (e: Exception) {
                logError(e)
            }
        }          

        // ==========================================
        // 4. [기본 에피소드(단일 비디오) 처리 블록]
        // ==========================================

        val videoId = YoutubeUtils.extractYoutubeId(url) ?: throw ErrorLoadingException("Invalid YouTube URL")

        epMetaMapCache.clear()

        val response = withContext(Dispatchers.IO) {
            try {
                app.get(url, headers = mapOf("Referer" to "https://www.youtube.com/"), interceptor = ytInterceptor)
            } catch (e: Exception) {
                logError(e)
                null
            }
        }
        val html = response?.text ?: ""
        val videoDetails = extractVideoDetails(html)
        val htmlTags = (videoDetails?.get("keywords") as? List<*>)
            ?.mapNotNull { it as? String }?.filter { it.isNotBlank() }?.take(10).orEmpty()
        val htmlUploader = (videoDetails?.get("author") as? String).orEmpty()
        val data = extractYtInitialData(html)

        var title = "YouTube Video"
        var plot = (videoDetails?.get("shortDescription") as? String).orEmpty()
        var plotSource = if (plot.isNotBlank()) "player" else "none"
        var poster = YoutubeUtils.buildThumbnailFromId(videoId)

        var channelName = ""
        var channelId = ""
        var channelAvatar = ""
        var subscriberCount = ""

        val recommendations = mutableListOf<SearchResponse>()
        val seenRecIds = mutableSetOf<String>()
        
        // 1화 메인 영상 본문의 정보 노드만 따로 담을 변수
        var primaryMap: Map<*, *>? = null

        if (data != null) {
            val resultsContents = safeGet(data, "contents", "twoColumnWatchNextResults", "results", "results", "contents") as? List<*>

            resultsContents?.forEach { item ->
                val m = item as? Map<*, *>

                val primary = m?.get("videoPrimaryInfoRenderer") as? Map<*, *>
                if (primary != null) {
                    primaryMap = primary // 1화 메인 영상 노드 저장
                    val t = extractTitle(primary["title"] as? Map<*, *>)
                    if (!t.isNullOrBlank()) title = t
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
                        
                        subscriberCount = getText(owner["subscriberCountText"])
                    }

                    if (plot.isBlank()) {
                        val descObj = secondary["attributedDescription"] as? Map<*, *>
                            ?: secondary["description"] as? Map<*, *>
                        val fullDesc = getText(descObj)
                        if (fullDesc.isNotBlank()) {
                            plot = fullDesc
                            plotSource = "initialData"
                        }
                    }
                }
            }

            val secondaryResults = safeGet(data, "contents", "twoColumnWatchNextResults", "secondaryResults", "secondaryResults", "results")
            if (secondaryResults != null) {
                processRecursive(secondaryResults, recommendations, seenRecIds, false, isSearch = false)
            }

        } else if (response != null) {
            val doc = response.document
            title = doc.selectFirst("meta[property=og:title]")?.attr("content") ?: title
            poster = doc.selectFirst("meta[property=og:image]")?.attr("content") ?: poster
        }
        if (plot.isBlank() && response != null) {
            plot = response.document.selectFirst("meta[property=og:description]")
                ?.attr("content").orEmpty()
            if (plot.isNotBlank()) plotSource = "og"
        }
        if (channelName.isBlank()) channelName = htmlUploader
        Log.i("YouTubeMeta", "[HTML_META] type=video id=$videoId html=${response != null} " +
            "player=${videoDetails != null} source=$plotSource chars=${plot.length} " +
            "lines=${plot.count { it == '\n' } + if (plot.isBlank()) 0 else 1} " +
            "tags=${htmlTags.size} uploader=${if (channelName.isNotBlank()) "html" else "none"} " +
            "avatar=${channelAvatar.isNotBlank()} direct=$direct")

        rememberChannel(url, channelName, channelId)

        val filteredRecs = recommendations.filter { it.type != TvType.Live && !it.url.contains("/shorts/") }
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
        var ep1Date = ""

        val foundRenderers = mutableListOf<Map<*, *>>()
        findAllRenderers(data, foundRenderers)
        
        val viewCountRenderer = foundRenderers.firstOrNull { it.containsKey("shortViewCount") }
        if (viewCountRenderer != null) {
            val vCount = getText(viewCountRenderer["shortViewCount"] ?: viewCountRenderer["viewCount"])
            if (vCount.isNotBlank()) ep1Views = vCount.stripWhitespace()
        }

        val primaryRenderer = foundRenderers.firstOrNull { it.containsKey("dateText") }
        if (primaryRenderer != null) {
            val dText = getText(primaryRenderer["dateText"])
            if (dText.isNotBlank()) ep1Date = dText.stripWhitespace()
        }

        if (ep1Views.isBlank() && html.isNotEmpty()) {
            val viewMatch = VIEW_COUNT_REGEX.find(html)
            if (viewMatch != null) ep1Views = viewMatch.groupValues[1].stripWhitespace()
        }
        if (ep1Date.isBlank() && html.isNotEmpty()) {
            val dateMatch = PUBLISH_DATE_REGEX.find(html)
            if (dateMatch != null) ep1Date = dateMatch.groupValues[1].stripWhitespace()
        }

        val cDur = ep1Dur.stripWhitespace()
        val cViews = ep1Views.stripWhitespace()
        val cDate = ep1Date.stripWhitespace()
        val cChannel = channelName.stripWhitespace()

        val mainDescParts = listOf(cDur, cViews, cDate, cChannel).filter { it.isNotBlank() && it != "N/A" }
        val mainDescStr = mainDescParts.joinToString(" • ")

        // direct_dub 쿼리를 붙이지 않고 오리지널 URL 그대로 사용
        val episodeUrl = url
    
        // 페이지 전체(data)가 아닌 메인 영상 본문 노드(primaryMap)만 검사하여 가입 버튼/추천 영상 오인 감지 방지
        val mainMembershipLabel = getMembershipLabel(primaryMap)
        val firstEpisodeName = if (!mainMembershipLabel.isNullOrBlank()) "[$mainMembershipLabel] $title" else title

        episodes.add(newEpisode(episodeUrl) {
            this.name = firstEpisodeName
            this.posterUrl = poster
            this.episode = 1
            this.description = mainDescStr.ifBlank { cChannel.ifBlank { "Playing" } }
        })
        
        var epCount = 2
        filteredRecs.forEach { rec ->
            if (rec.url.contains("watch?v=") || rec.url.contains("/shorts/")) {
                val recVidId = YoutubeUtils.extractYoutubeId(rec.url)
                val extraMeta = if (recVidId != null) epMetaMapCache[recVidId] else ""
                var cleanName = rec.name.replaceFirst(Regex("^\\[.*?\\]\\s*"), "")

                val recMembership = if (recVidId != null) videoMembershipCache[recVidId] else null
                if (!recMembership.isNullOrBlank()) {
                    cleanName = "[$recMembership] $cleanName"
                }

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
            this.posterUrl = poster
            this.plot = descriptionAsHtml(plot).ifBlank { "\u200b" }
            this.recommendations = filteredRecs 
            
            if (htmlTags.isNotEmpty()) {
                this.tags = htmlTags
            }

            val actorList = mutableListOf<ActorData>()
            if (channelName.isNotBlank()) {
                actorList.add(ActorData(Actor(channelName, channelAvatar)))
            }

            if (subscriberCount.isNotBlank()) {
                actorList.add(ActorData(Actor(subscriberCount, "https://raw.githubusercontent.com/hsp1020/TestPlugins/master/YouTube/Subscribe.jpg")))
            }

            if (actorList.isNotEmpty()) {
                this.actors = actorList
            }
        }
    }
    
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val videoId = YoutubeUtils.extractYoutubeId(data) ?: data
        val isShort = data.contains("/shorts/")

        val fullUrl = "https://www.youtube.com/watch?v=$videoId"

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
                val desktopApiKey = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
                val apiUrl = "https://www.youtube.com/youtubei/v1/player?key=$desktopApiKey"
                val payload = mapOf(
                    "videoId" to videoId,
                    "context" to mapOf(
                        "client" to mapOf("clientName" to "WEB", "clientVersion" to "2.20240725.01.00", "hl" to lang)
                    )
                )
                val webHeaders = mapOf(
                    "User-Agent" to YoutubeUtils.CHROME_USER_AGENT,
                    "Origin" to "https://www.youtube.com",
                    "Referer" to "https://www.youtube.com/"
                )
                playerResponseJson = app.post(apiUrl, json = payload, headers = webHeaders).text
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

        com.hsp1020.youtube.YoutubeExtractor().getUrl(fullUrl, null, subtitleCallback, callback)

        return true
    }                                             
}
