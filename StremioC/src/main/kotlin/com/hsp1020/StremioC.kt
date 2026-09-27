package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getKey
import com.lagradost.cloudstream3.Actor
import com.lagradost.cloudstream3.ActorData
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.LoadResponse.Companion.addAniListId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDate
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.runAllAsync
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.SubtitleHelper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.nicehttp.Requests
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import java.net.URI
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.lang.ref.WeakReference

inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c 
    } catch (t: Throwable) { 
        Result.failure(t)
    }
}

private fun String.urlEncode(): String = try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") } catch (e: Exception) { this }

private fun String.cleanBaseUrl(): String {
    return this.substringBefore("?").replace("/manifest.json", "").trimEnd('/')
}

private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
    return Collections.synchronizedSet(Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
            return size > maxSize
        }
    }))
}

private inline fun <K, V> lruCacheMap(maxSize: Int): MutableMap<K, V> {
    return Collections.synchronizedMap(object : java.util.LinkedHashMap<K, V>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean {
            return size > maxSize
        }
    })
}

private fun JsonNode?.textOrNull(): String? {
    if (this == null || this.isNull || this.isMissingNode) return null
    return try { this.asText().takeIf { it.isNotBlank() && it != "null" } } catch (e: Exception) { null }
}

private fun JsonNode?.intOrNull(): Int? {
    if (this == null || this.isNull || this.isMissingNode) return null
    return try { this.asInt() } catch (e: Exception) { null }
}

open class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = if (mainUrl.isNotBlank()) {
        setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    } else {
        emptySet()
    }
    
    // 주소가 있는 실제 애드온만 홈 화면 탭 목록에 표시되도록 설정
    override val hasMainPage = mainUrl.isNotBlank()
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0

    private data class PreparedMainPageManifest(
        val manifestUrl: String,
        val rawSignature: String,
        val manifest: Manifest,
    )
    @Volatile private var preparedMainPageManifest: PreparedMainPageManifest? = null
    // mainPage is a synchronous property, so the manifest warm-up and foreground build share
    // this JVM lock. A matching persisted manifest is parsed by exactly one owner; a concurrent
    // caller waits for that owner and reuses the prepared object instead of parsing twice.
    private val mainPageManifestParseLock = Any()

    private data class MainPageManifestParseResult(
        val manifest: Manifest?,
        val mode: String,
        val lockWaitMs: Long,
        val parseMs: Long,
    )
    
    private val pageMutex = Mutex()
    private class PageFlight(
        val scope: CoroutineScope,
        val deferred: Deferred<HomePageResponse>,
        val traceId: String,
        var waiters: Int = 0
    )
    private val activePageRequests = mutableMapOf<String, PageFlight>()

    private class OneShotPrefetch(
        val scope: CoroutineScope,
        val deferred: Deferred<HomePageResponse>,
        val traceId: String,
        val createdAtMs: Long,
        @Volatile var claimed: Boolean = false
    )
    private val prefetchMutex = Mutex()
    private val prefetchSweepMutex = Mutex()
    private val page1Prefetch = mutableMapOf<String, OneShotPrefetch>()
    private val foregroundSeenPage1 = mutableSetOf<String>()
    private val manifestMutex = Mutex()

    private fun getCineStreamProvider(): MainAPI? {
        if (getKey<Boolean>("ProviderCineStream") == false) return null
        if (cachedCineStreamProvider != null) return cachedCustomCineStreamProvider()

        synchronized(StremioC::class.java) {
            if (cachedCineStreamProvider != null) return cachedCustomCineStreamProvider()

            val provider = getSafeAllProviders().find { it.name.equals("CineStream", true) }
                ?: loadProviderDynamically("megix", "com.megix.CineStreamProvider")
            
            if (provider != null) cachedCineStreamProvider = provider
            return provider
        }
    }

    private fun cachedCustomCineStreamProvider(): MainAPI? = cachedCineStreamProvider

    private fun isStreamPlayApiEnabled(apiName: String): Boolean {
        val context = CloudStreamApp.context ?: return true
        val enabled = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            .getStringSet("enabled_plugins_saved", null)
        return enabled == null || apiName in enabled
    }

    private fun getStreamPlayProvider(): MainAPI? {
        if (!isStreamPlayApiEnabled("StreamPlay")) return null
        if (cachedStreamPlayProvider != null) return cachedCustomStreamPlayProvider()

        synchronized(StremioC::class.java) {
            if (cachedStreamPlayProvider != null) return cachedCustomStreamPlayProvider()

            val provider = getSafeAllProviders().find { it.name.equals("StreamPlay", true) }
                ?: loadProviderDynamically(
                    "phisher", "com.phisher98.StreamPlay", "StreamPlay"
                )
            
            if (provider != null) cachedStreamPlayProvider = provider
            return provider
        }
    }

    private fun cachedCustomStreamPlayProvider(): MainAPI? = cachedStreamPlayProvider

    private fun getStreamPlayAnimeProvider(): MainAPI? {
        if (!isStreamPlayApiEnabled("StreamPlay-Anime")) return null
        getSafeAllProviders().find { it.name.equals("StreamPlay-Anime", true) }?.let { return it }
        return loadProviderDynamically("phisher", "com.phisher98.StreamPlayAnime")
    }

    fun getUnifiedMetaUrl(type: String, id: String): String {
        val (syncUrl, syncMap) = getSyncSettings()
        val currentCleanUrl = mainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        return if (isSyncEnabled && syncUrl.isNotBlank()) {
            val cleanBase = syncUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        } else {
            val cleanBase = mainUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        }
    }

    fun getTargetApiName(): String {
        val (_, syncMap) = getSyncSettings()
        val currentCleanUrl = mainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        return if (isSyncEnabled) "StremioC" else name
    }
    
    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
        
        private val IMDB_ID_REGEX = "tt[0-9]+".toRegex()
        private val JSON_ID_EXTRACT_REGEX = "\"id\":\"([^\"]+)\"".toRegex()
        
        val globalPageCache = lruCacheMap<String, Triple<List<SearchResponse>, Int, Long>>(100)
        val cacheMutexes = ConcurrentHashMap<String, Mutex>()
        const val CACHE_TTL_MS = 3600000L 
        
        private val settingsCache = ConcurrentHashMap<String, Pair<Boolean, Long>>()
        
        @Volatile private var cachedCustomHosts: Set<String>? = null
        @Volatile private var cachedCustomHostsTime: Long = 0
        private const val SETTINGS_CACHE_TTL = 60_000L

        // SharedPreferences 반복 파싱 부하 개선을 위한 3초 생명주기 캐시 바인딩
        private data class SyncSettings(val syncUrl: String, val syncMap: Map<String, Boolean>)
        @Volatile private var cachedSyncSettings: SyncSettings? = null
        @Volatile private var cachedSyncSettingsTime: Long = 0
        private const val SYNC_SETTINGS_TTL = 3000L

        private fun getSyncSettings(): SyncSettings {
            val now = System.currentTimeMillis()
            val cached = cachedSyncSettings
            if (cached != null && (now - cachedSyncSettingsTime) < SYNC_SETTINGS_TTL) {
                return cached
            }
            synchronized(StremioC::class.java) {
                val doubleCached = cachedSyncSettings
                if (doubleCached != null && (now - cachedSyncSettingsTime) < SYNC_SETTINGS_TTL) {
                    return doubleCached
                }
                val context = CloudStreamApp.context ?: return SyncSettings("", emptyMap())
                val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val syncUrl = prefs.getString("history_sync_url", "") ?: ""
                val json = prefs.getString("stremio_saved_links", null)
                val map = mutableMapOf<String, Boolean>()
                if (json != null) {
                    try {
                        val arr = org.json.JSONArray(json)
                        for (i in 0 until arr.length()) {
                            val obj = arr.getJSONObject(i)
                            val link = obj.optString("link", "").cleanBaseUrl().lowercase()
                            if (link.isNotBlank()) {
                                map[link] = obj.optBoolean("isSyncEnabled", true)
                            }
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                val result = SyncSettings(syncUrl, map)
                cachedSyncSettings = result
                cachedSyncSettingsTime = System.currentTimeMillis()
                return result
            }
        }

        val networkSemaphore = Semaphore(35)
        // A/B profile B: previous baseline was foreground=10 / background=2.
        // Keep these explicit so logcat comparisons are reproducible.
        const val HOME_FOREGROUND_CONCURRENCY = 16
        const val HOME_PREFETCH_CONCURRENCY = 4
        val homeNetworkSemaphore = Semaphore(HOME_FOREGROUND_CONCURRENCY)

        // Catalog prefetch is still lower priority than foreground home loading.
        // Results are volatile, page-1 only, one-shot, and expire quickly.
        private val homePrefetchSemaphore = Semaphore(HOME_PREFETCH_CONCURRENCY)
        private val foregroundHomeRequests = AtomicInteger(0)
        private const val HOME_PREFETCH_TTL_MS = 5 * 60 * 1000L
        private const val HOME_PREFETCH_IDLE_POLL_MS = 75L
        private const val HOME_ROW_RETRY_DELAY_MS = 300L
        private const val HOME_ROW_MAX_ATTEMPTS = 2

        private class CatalogHttpException(val statusCode: Int) :
            IllegalStateException("Catalog HTTP $statusCode")
        
        @Volatile private var cachedCineStreamProvider: MainAPI? = null
        @Volatile private var cachedStreamPlayProvider: MainAPI? = null

        @Volatile private var hasSyncedCineStreamSettings = false
        
        @Volatile private var cachedClassLoaderRef: WeakReference<ClassLoader>? = null

        private val tmdbDetailsCache = lruCacheMap<String, Pair<TmdbDetailResponse, Long>>(100)
        private val tmdbSeasonCache = lruCacheMap<String, Pair<TmdbSeasonDetail, Long>>(150)
        private val kitsuMetaCache = lruCacheMap<String, Pair<String, Long>>(100)
        private val tmdbFindCache = lruCacheMap<String, Pair<Pair<String, String>?, Long>>(100)
        private val aniZipCache = lruCacheMap<String, Pair<Pair<Int?, Int?>?, Long>>(100)
        private const val METADATA_CACHE_TTL = 12 * 60 * 60 * 1000L

        val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
        val catalogSkipState = ConcurrentHashMap<String, Int>()
        private val catalogGenerations = ConcurrentHashMap<String, Long>()

        suspend fun getOrFetchKitsu(url: String): String? {
            val now = System.currentTimeMillis()
            kitsuMetaCache[url]?.let { (res, time) ->
                if (now - time < METADATA_CACHE_TTL) return res
            }
            return runSuspendCatching {
                val res = customSession.get(url, timeout = 10L).text
                if (res.isNotBlank()) kitsuMetaCache[url] = Pair(res, now)
                res
            }.onFailure { it.printStackTrace() }.getOrNull()
        }

        val customSession by lazy {
            val newClient = app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .dispatcher(Dispatcher().apply {
                    maxRequests = 70
                    maxRequestsPerHost = 40
                })
                .connectionPool(ConnectionPool(45, 15, TimeUnit.SECONDS))
                .build()
                
            Requests(newClient).apply {
                this.responseParser = app.responseParser
                this.defaultHeaders = app.defaultHeaders
            }
        }

        private data class HomeRequestTrace(
            val traceId: String,
            val provider: String,
            val tab: String,
            val page: Int,
            val origin: String,
            val attempt: Int = 1
        )

        private class HomePerfEventListener : okhttp3.EventListener() {
            private val callStartedNs = System.nanoTime()
            private var dnsStartedNs = 0L
            private var connectStartedNs = 0L
            private var tlsStartedNs = 0L
            private var requestHeadersStartedNs = 0L
            private var requestHeadersEndedNs = 0L
            private var responseBodyStartedNs = 0L

            private fun elapsedMs(startNs: Long): Long =
                if (startNs == 0L) -1L else (System.nanoTime() - startNs) / 1_000_000L

            private fun ctx(call: okhttp3.Call): String {
                val trace = call.request().tag(HomeRequestTrace::class.java)
                return if (trace == null) {
                    "trace=unknown"
                } else {
                    "trace=${trace.traceId} provider=${trace.provider} tab=${trace.tab} page=${trace.page} origin=${trace.origin} attempt=${trace.attempt}"
                }
            }

            override fun dnsStart(call: okhttp3.Call, domainName: String) { dnsStartedNs = System.nanoTime() }
            override fun dnsEnd(call: okhttp3.Call, domainName: String, inetAddressList: List<java.net.InetAddress>) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=DNS ms=${elapsedMs(dnsStartedNs)} addresses=${inetAddressList.size}")
            }
            override fun connectStart(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy) { connectStartedNs = System.nanoTime() }
            override fun secureConnectStart(call: okhttp3.Call) { tlsStartedNs = System.nanoTime() }
            override fun secureConnectEnd(call: okhttp3.Call, handshake: okhttp3.Handshake?) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=TLS ms=${elapsedMs(tlsStartedNs)}")
            }
            override fun connectEnd(call: okhttp3.Call, inetSocketAddress: java.net.InetSocketAddress, proxy: java.net.Proxy, protocol: okhttp3.Protocol?) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=CONNECT_TOTAL ms=${elapsedMs(connectStartedNs)} protocol=$protocol")
            }
            override fun requestHeadersStart(call: okhttp3.Call) { requestHeadersStartedNs = System.nanoTime() }
            override fun requestHeadersEnd(call: okhttp3.Call, request: okhttp3.Request) {
                requestHeadersEndedNs = System.nanoTime()
                println("[StremioC-Home][NET] ${ctx(call)} stage=REQUEST_HEADERS ms=${elapsedMs(requestHeadersStartedNs)}")
            }
            override fun responseHeadersStart(call: okhttp3.Call) {
                val base = if (requestHeadersEndedNs != 0L) requestHeadersEndedNs else callStartedNs
                println("[StremioC-Home][NET] ${ctx(call)} stage=TTFB ms=${elapsedMs(base)}")
            }
            override fun responseBodyStart(call: okhttp3.Call) { responseBodyStartedNs = System.nanoTime() }
            override fun responseBodyEnd(call: okhttp3.Call, byteCount: Long) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=BODY ms=${elapsedMs(responseBodyStartedNs)} bytes=$byteCount")
            }
            override fun callEnd(call: okhttp3.Call) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=CALL_TOTAL ms=${elapsedMs(callStartedNs)}")
            }
            override fun callFailed(call: okhttp3.Call, ioe: java.io.IOException) {
                println("[StremioC-Home][NET] ${ctx(call)} stage=FAILED totalMs=${elapsedMs(callStartedNs)} errorClass=${ioe.javaClass.simpleName}")
            }
        }

        private val homeClient by lazy {
            app.baseClient.newBuilder()
                .eventListenerFactory { HomePerfEventListener() }
                .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .callTimeout(35, TimeUnit.SECONDS)
                .cache(null)
                .dispatcher(Dispatcher().apply {
                    maxRequests = HOME_FOREGROUND_CONCURRENCY
                    maxRequestsPerHost = HOME_FOREGROUND_CONCURRENCY
                })
                .connectionPool(ConnectionPool(HOME_FOREGROUND_CONCURRENCY, 60, TimeUnit.SECONDS))
                .build()
        }
        private val homeHostBackoff = ConcurrentHashMap<String, Long>()
        private val homePrefetchHostBackoff = ConcurrentHashMap<String, Long>()

        private fun isRetryableCatalogStatus(code: Int): Boolean =
            code == 404 || code == 408 || code == 425 || code == 500 || code == 502 || code == 503 || code == 504

        private suspend fun fetchHomeJson(url: String, trace: HomeRequestTrace): String {
            var attempt = 1
            while (true) {
                currentCoroutineContext().ensureActive()
                val attemptTrace = trace.copy(attempt = attempt)
                try {
                    return fetchHomeJsonOnce(url, attemptTrace)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: CatalogHttpException) {
                    if (attempt < HOME_ROW_MAX_ATTEMPTS && isRetryableCatalogStatus(e.statusCode)) {
                        println("[StremioC-Home][ROW_RETRY] trace=${trace.traceId} provider=${trace.provider} tab=${trace.tab} page=${trace.page} origin=${trace.origin} attempt=$attempt nextAttempt=${attempt + 1} reason=HTTP_${e.statusCode} delayMs=$HOME_ROW_RETRY_DELAY_MS")
                        delay(HOME_ROW_RETRY_DELAY_MS)
                        attempt++
                        continue
                    }
                    throw e
                } catch (e: java.io.IOException) {
                    if (attempt < HOME_ROW_MAX_ATTEMPTS) {
                        println("[StremioC-Home][ROW_RETRY] trace=${trace.traceId} provider=${trace.provider} tab=${trace.tab} page=${trace.page} origin=${trace.origin} attempt=$attempt nextAttempt=${attempt + 1} reason=${e.javaClass.simpleName} delayMs=$HOME_ROW_RETRY_DELAY_MS")
                        delay(HOME_ROW_RETRY_DELAY_MS)
                        attempt++
                        continue
                    }
                    throw e
                }
            }
        }

        private suspend fun fetchHomeJsonOnce(url: String, trace: HomeRequestTrace): String {
            val host = url.toHttpUrlOrNull()?.host ?: throw IllegalArgumentException("Invalid catalog URL")
            val now = System.currentTimeMillis()
            val foregroundUntil = homeHostBackoff[host] ?: 0L
            val prefetchUntil = homePrefetchHostBackoff[host] ?: 0L
            val until = if (trace.origin == "PREFETCH") maxOf(foregroundUntil, prefetchUntil) else foregroundUntil
            if (until > now) throw IllegalStateException("Catalog server rate limited; retry later")
            val request = okhttp3.Request.Builder()
                .url(url)
                .tag(HomeRequestTrace::class.java, trace)
                .apply { app.defaultHeaders.forEach { (name, value) -> header(name, value) } }
                .build()
            val started = System.nanoTime()
            return suspendCancellableCoroutine { continuation ->
                val call = homeClient.newCall(request)
                continuation.invokeOnCancellation { call.cancel() }
                call.enqueue(object : okhttp3.Callback {
                    override fun onFailure(call: okhttp3.Call, e: java.io.IOException) {
                        if (continuation.isActive) continuation.resumeWith(Result.failure(e))
                    }
                    override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                        val outcome = runCatching {
                            response.use { res ->
                                println("[StremioC-Home][HTTP_RESPONSE] trace=${trace.traceId} provider=${trace.provider} tab=${trace.tab} page=${trace.page} origin=${trace.origin} attempt=${trace.attempt} status=${res.code} protocol=${res.protocol} networkMs=${(System.nanoTime() - started) / 1_000_000}")
                                if (res.code == 429) {
                                    val raw = res.header("Retry-After")
                                    val delayMs = raw?.toLongOrNull()?.coerceIn(0L, 86400L)?.times(1000L)
                                        ?: runCatching {
                                            java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", java.util.Locale.US)
                                                .parse(raw ?: "")?.time?.minus(System.currentTimeMillis())
                                        }.getOrNull()?.coerceIn(0L, 86400000L) ?: 10000L
                                    if (trace.origin == "PREFETCH") {
                                        synchronized(homePrefetchHostBackoff) {
                                            homePrefetchHostBackoff[host] = maxOf(
                                                homePrefetchHostBackoff[host] ?: 0L,
                                                System.currentTimeMillis() + delayMs
                                            )
                                        }
                                    } else {
                                        synchronized(homeHostBackoff) {
                                            homeHostBackoff[host] = maxOf(
                                                homeHostBackoff[host] ?: 0L,
                                                System.currentTimeMillis() + delayMs
                                            )
                                        }
                                    }
                                    throw CatalogHttpException(429)
                                }
                                if (!res.isSuccessful) throw CatalogHttpException(res.code)
                                res.body?.string() ?: throw IllegalStateException("Empty catalog response body")
                            }
                        }
                        if (continuation.isActive) continuation.resumeWith(outcome)
                    }
                })
            }
        }


        fun loadProviderDynamically(
            pluginKeyword: String,
            className: String,
            preferencesFile: String? = null
        ): MainAPI? {
            fun instantiate(clazz: Class<*>): MainAPI? {
                val context = CloudStreamApp.context
                if (preferencesFile != null && context != null) {
                    val prefs = context.getSharedPreferences(preferencesFile, Context.MODE_PRIVATE)
                    try {
                        return clazz.getConstructor(SharedPreferences::class.java)
                            .newInstance(prefs) as? MainAPI
                    } catch (_: NoSuchMethodException) {
                        // Other provider classes only expose a no-argument constructor.
                    }
                }
                return clazz.newInstance() as? MainAPI
            }
            val loader = cachedClassLoaderRef?.get()
            if (loader != null) {
                try {
                    val clazz = loader.loadClass(className)
                    return instantiate(clazz)
                } catch (e: Exception) { e.printStackTrace() }
            }
            try {
                val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val pmInstance = pmClass.getField("INSTANCE").get(null)

                for (field in pmClass.declaredFields) {
                    field.isAccessible = true
                    val obj = runSuspendCatching { field.get(pmInstance) }.onFailure { it.printStackTrace() }.getOrNull()
                    val items = when (obj) {
                        is Map<*, *> -> obj.values
                        is java.util.Collection<*> -> obj
                        is Array<*> -> obj.toList()
                        else -> null
                    }

                    if (items != null) {
                        for (plugin in items) {
                            val pName = plugin?.javaClass?.name ?: ""
                            if (pName.contains(pluginKeyword, true)) {
                                try {
                                    val candidateLoader = plugin!!.javaClass.classLoader
                                    val provider = instantiate(candidateLoader.loadClass(className))
                                    if (provider != null) {
                                        cachedClassLoaderRef = WeakReference(candidateLoader)
                                        return provider
                                    }
                                } catch (_: ClassNotFoundException) {
                                    // Several plugins may share this publisher; keep searching.
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }
            return null
        }

        fun isIntegrationEnabled(prefKey: String): Boolean {
            val now = System.currentTimeMillis()
            val cached = settingsCache[prefKey]
            if (cached != null && (now - cached.second) < 60000L) {
                return cached.first
            }

            val context = CloudStreamApp.context ?: return true
            val possibleNames = listOf("StremioC", "StremioC_preferences", "stremio_preferences", "com.hsp1020_preferences", context.packageName + "_preferences")
            
            var isEnabled = true
            for (name in possibleNames) {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.contains(prefKey)) {
                    isEnabled = prefs.getBoolean(prefKey, true)
                    break
                }
            }
            
            settingsCache[prefKey] = Pair(isEnabled, now)
            return isEnabled
        }

        suspend fun getOrFetchCachedPage(
            key: String, 
            fetchBlock: suspend () -> Pair<List<SearchResponse>, Int>?
        ): Pair<List<SearchResponse>, Int>? {
            val now = System.currentTimeMillis()
            
            globalPageCache[key]?.let { entry ->
                if (now - entry.third <= CACHE_TTL_MS) return Pair(entry.first, entry.second)
            }

            val mutex = cacheMutexes.getOrPut(key) { Mutex() }

            return mutex.withLock {
                globalPageCache[key]?.let { entry ->
                    if (now - entry.third <= CACHE_TTL_MS) {
                        return@withLock Pair(entry.first, entry.second)
                    }
                }

                val result = fetchBlock()
                if (result != null && result.first.isNotEmpty()) {
                    globalPageCache[key] = Triple(result.first, result.second, System.currentTimeMillis())
                }
                result
            }
        }
    }

    private fun SearchResponse.extractPureId(): String {
        return try {
            JSON_ID_EXTRACT_REGEX.find(this.url)?.groupValues?.get(1) ?: this.url
        } catch (e: Exception) {
            e.printStackTrace()
            this.url
        }
    }

    private fun buildTmdbUrl(endpoint: String, vararg params: Pair<String, String>): String {
        val builder = "$tmdbAPI$endpoint".toHttpUrlOrNull()?.newBuilder() ?: return ""
        val context = CloudStreamApp.context
        val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
        val tmdbKey = prefs?.getString("tmdb_api_key", "") ?: ""
        builder.addQueryParameter("api_key", tmdbKey)
        val langCode = prefs?.getString("tmdb_language_code", "ko-KR") ?: "ko-KR"
        builder.addQueryParameter("language", langCode)
        params.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build().toString()
    }

    private fun extractImdbId(url: String?): String? {
        if (url.isNullOrBlank() || !url.contains("tt")) return null
        return runSuspendCatching { IMDB_ID_REGEX.find(url)?.value }.onFailure { it.printStackTrace() }.getOrNull()
    }

    private fun buildStremioId(type: String?, id: String?, season: Int?, episode: Int?, isSubtitle: Boolean = false): String? {
        if (type == "series" && season != null && episode != null) {
            return when {
                id?.startsWith("kitsu:") == true || id?.startsWith("mal:") == true -> {
                    val parts = id.split(":")
                    val baseId = if (parts.size >= 2) "${parts[0]}:${parts[1]}" else id
                    if (isSubtitle) "$baseId:$season:$episode" else "$baseId:$episode"
                }
                id?.endsWith(":$season:$episode") == true -> id
                else -> "$id:$season:$episode"
            }
        }
        return id
    }

    private fun baseUrl(): String {
        return mainUrl.fixSourceUrl().cleanBaseUrl()
    }

    private fun querySuffix(): String {
        return mainUrl.substringAfter("?", "")
            .takeIf { it.isNotEmpty() }
            ?.let { "?$it" }
            ?: ""
    }

    private fun buildUrl(path: String): String {
        return "${baseUrl()}$path${querySuffix()}"
    }

    private fun getActorUrl(link: String?): String? {
        if (link == null) return null
        return if (link.startsWith("/")) "https://image.tmdb.org/t/p/w300$link" else link
    }

    private suspend fun getManifest(): Manifest? = manifestMutex.withLock {
        fetchManifestLocked()
    }

    private suspend fun fetchManifestLocked(): Manifest? {
        val totalStarted = System.nanoTime()
        val currentUrl = stremioManifestUrl(mainUrl)

        val now = System.currentTimeMillis()
        val isExpired = (now - lastCacheTime) > CACHE_TTL_MS

        if (cachedManifest != null && lastManifestUrl == currentUrl && !isExpired && !cachedManifest?.catalogs.isNullOrEmpty()) {
            println("[StremioC-Home][MANIFEST] provider=$name source=MEMORY catalogs=${cachedManifest?.catalogs?.size ?: 0} totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
            return cachedManifest
        }

        val netStarted = System.nanoTime()
        val resText = runSuspendCatching {
            customSession.get(currentUrl, timeout = 15L).text
        }.onFailure {
            println("[StremioC-Home][MANIFEST] provider=$name stage=HTTP_FAILED ms=${(System.nanoTime() - netStarted) / 1_000_000} errorClass=${it.javaClass.simpleName}")
            it.printStackTrace()
        }.getOrNull()
        val netMs = (System.nanoTime() - netStarted) / 1_000_000

        if (!resText.isNullOrBlank()) {
            val parseStarted = System.nanoTime()
            try {
                val parsed = parseJson<Manifest>(resText)
                val parseMs = (System.nanoTime() - parseStarted) / 1_000_000
                if (parsed?.catalogs?.isNotEmpty() == true) {
                    cachedManifest = parsed
                    preparedMainPageManifest = PreparedMainPageManifest(currentUrl, resText.toUniqueKey(), parsed)
                    lastManifestUrl = currentUrl
                    lastCacheTime = now

                    val writeStarted = System.nanoTime()
                    val context = CloudStreamApp.context
                    context?.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                        ?.edit()?.putString(currentUrl.toUniqueKey(), resText)?.apply()
                    val writeMs = (System.nanoTime() - writeStarted) / 1_000_000

                    println("[StremioC-Home][MANIFEST] provider=$name source=NETWORK httpMs=$netMs parseMs=$parseMs prefsApplyMs=$writeMs bytes=${resText.length} catalogs=${parsed.catalogs.size} totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
                    return parsed
                }
                println("[StremioC-Home][MANIFEST] provider=$name stage=PARSE_EMPTY httpMs=$netMs parseMs=$parseMs bytes=${resText.length}")
            } catch (e: Exception) {
                println("[StremioC-Home][MANIFEST] provider=$name stage=PARSE_FAILED httpMs=$netMs parseMs=${(System.nanoTime() - parseStarted) / 1_000_000} errorClass=${e.javaClass.simpleName}")
                e.printStackTrace()
            }
        } else {
            println("[StremioC-Home][MANIFEST] provider=$name stage=EMPTY_RESPONSE httpMs=$netMs")
        }

        println("[StremioC-Home][MANIFEST] provider=$name source=FALLBACK totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
        return cachedManifest
    }


    private fun parseMainPageManifestSingleFlight(
        raw: String,
        currentUrl: String,
        signature: String,
        reason: String,
    ): MainPageManifestParseResult {
        preparedMainPageManifest?.let { existing ->
            if (existing.manifestUrl == currentUrl && existing.rawSignature == signature) {
                return MainPageManifestParseResult(existing.manifest, "HIT", 0L, 0L)
            }
        }

        val waitStarted = System.nanoTime()
        return synchronized(mainPageManifestParseLock) {
            val lockWaitMs = (System.nanoTime() - waitStarted) / 1_000_000

            // Another caller may have finished while this caller was waiting for the owner.
            val joined = preparedMainPageManifest
            if (joined != null && joined.manifestUrl == currentUrl && joined.rawSignature == signature) {
                println("[StremioC-Home][MANIFEST_SINGLE_FLIGHT] provider=$name reason=$reason mode=JOIN lockWaitMs=$lockWaitMs parseMs=0 catalogs=${joined.manifest.catalogs.size}")
                MainPageManifestParseResult(joined.manifest, "JOIN", lockWaitMs, 0L)
            } else {
                val parseStarted = System.nanoTime()
                val parsed: Manifest? = runCatching { parseJson<Manifest>(raw) }.getOrNull()
                val parseMs = (System.nanoTime() - parseStarted) / 1_000_000
                if (parsed != null && parsed.catalogs.isNotEmpty()) {
                    preparedMainPageManifest = PreparedMainPageManifest(currentUrl, signature, parsed)
                    println("[StremioC-Home][MANIFEST_SINGLE_FLIGHT] provider=$name reason=$reason mode=OWNER lockWaitMs=$lockWaitMs parseMs=$parseMs catalogs=${parsed.catalogs.size}")
                    MainPageManifestParseResult(parsed, "OWNER", lockWaitMs, parseMs)
                } else {
                    println("[StremioC-Home][MANIFEST_SINGLE_FLIGHT] provider=$name reason=$reason mode=OWNER_FAILED lockWaitMs=$lockWaitMs parseMs=$parseMs")
                    MainPageManifestParseResult(null, "OWNER_FAILED", lockWaitMs, parseMs)
                }
            }
        }
    }

    /**
     * Parse the authoritative persisted manifest ahead of the first homepage build.
     * This is not a catalog-result cache: only the manifest definition is retained in RAM.
     * A settings/manifest change is detected by the SHA-256 signature and reparsed automatically.
     */
    suspend fun warmMainPageManifest(reason: String = "BOOT"): Boolean {
        if (mainUrl.isBlank()) return false
        val context = CloudStreamApp.context ?: return false
        val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
        val currentUrl = stremioManifestUrl(mainUrl)
        val readStarted = System.nanoTime()
        val raw = stremioReadManifest(manifestPrefs, mainUrl)
        val readMs = (System.nanoTime() - readStarted) / 1_000_000
        if (raw.isNullOrBlank()) {
            println("[StremioC-Home][MANIFEST_WARM] provider=$name reason=$reason stage=MISS readMs=$readMs")
            return false
        }

        val signatureStarted = System.nanoTime()
        val signature = raw.toUniqueKey()
        val signatureMs = (System.nanoTime() - signatureStarted) / 1_000_000
        val result = withContext(Dispatchers.Default) {
            parseMainPageManifestSingleFlight(raw, currentUrl, signature, "WARM_$reason")
        }
        val parsed = result.manifest
        if (parsed == null || parsed.catalogs.isEmpty()) {
            println("[StremioC-Home][MANIFEST_WARM] provider=$name reason=$reason stage=FAILED mode=${result.mode} readMs=$readMs signatureMs=$signatureMs lockWaitMs=${result.lockWaitMs} parseMs=${result.parseMs}")
            return false
        }

        val stage = when (result.mode) {
            "HIT" -> "HIT"
            "JOIN" -> "JOIN"
            else -> "READY"
        }
        println("[StremioC-Home][MANIFEST_WARM] provider=$name reason=$reason stage=$stage mode=${result.mode} catalogs=${parsed.catalogs.size} readMs=$readMs signatureMs=$signatureMs lockWaitMs=${result.lockWaitMs} parseMs=${result.parseMs}")
        return true
    }


    override val mainPage: List<MainPageData>
        get() {
            val perfStarted = System.nanoTime()
            val context = CloudStreamApp.context ?: return emptyList()
            val prefs = context.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            val manifestPrefs = context.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)

            val currentUrl = stremioManifestUrl(mainUrl)
            val uniqueKey = stremioCatalogKey(mainUrl)
            val migrateStarted = System.nanoTime()
            migrateStremioCatalogSettings(prefs, mainUrl)
            val migrateMs = (System.nanoTime() - migrateStarted) / 1_000_000
            val manifestReadStarted = System.nanoTime()
            val diskCache = stremioReadManifest(manifestPrefs, mainUrl)
            val manifestReadMs = (System.nanoTime() - manifestReadStarted) / 1_000_000
            // Settings refresh writes the authoritative definition. Reuse the typed manifest only
            // while its SHA-256 signature still matches the persisted raw JSON.
            val manifestParseStarted = System.nanoTime()
            var manifestSource = "MEMORY_MANIFEST"
            val rawCatalogs = if (!diskCache.isNullOrBlank()) {
                val signature = diskCache.toUniqueKey()
                val result = parseMainPageManifestSingleFlight(
                    diskCache,
                    currentUrl,
                    signature,
                    "MAINPAGE"
                )
                val parsed = result.manifest
                if (parsed != null) {
                    manifestSource = when (result.mode) {
                        "HIT" -> "PREPARSED_DISK"
                        "JOIN" -> "PREPARSED_JOIN"
                        else -> "DISK_PARSED_OWNER"
                    }
                    parsed.catalogs
                } else {
                    manifestSource = "MEMORY_FALLBACK"
                    cachedManifest?.catalogs.orEmpty()
                }
            } else {
                cachedManifest?.catalogs.orEmpty()
            }
            val manifestParseMs = (System.nanoTime() - manifestParseStarted) / 1_000_000

            if (rawCatalogs.isEmpty()) {
                println("[StremioC-Home][MAINPAGE_BUILD] provider=$name stage=NO_CATALOG migrateMs=$migrateMs manifestReadMs=$manifestReadMs manifestParseMs=$manifestParseMs totalMs=${(System.nanoTime() - perfStarted) / 1_000_000}")
                return emptyList()
            }
            
            val tabBuildStarted = System.nanoTime()
            val flatTabs = mutableListOf<FlatTab>()
            rawCatalogs.forEach { catalog ->
                if (!catalog.supportsHome()) return@forEach
                val catalogId = catalog.id ?: ""
                val catalogType = catalog.type ?: "movie"
                val catalogName = catalog.name ?: catalogId
                val displayType = catalogType.replaceFirstChar { it.uppercase() }
                
                val requiresGenre = catalog.requiresExtra("genre")

                if (!requiresGenre) {
                    flatTabs.add(FlatTab(
                        key = "${catalogId}_${catalogType}_default",
                        displayName = "$catalogName - $displayType",
                        urlPath = "/catalog/$catalogType/$catalogId/skip=###.json"
                    ))
                }

                val genresEnabled = prefs.getBoolean(
                    "genres_enabled_${uniqueKey}_${catalogId}_${catalogType}", true
                )
                val genres = if (genresEnabled) catalog.getGenresList() else emptyList()
                genres.forEach { genre ->
                    val capitalizedGenre = genre.replaceFirstChar { it.uppercase() }
                    flatTabs.add(FlatTab(
                        key = "${catalogId}_${catalogType}_genre_$genre",
                        displayName = "$catalogName : $capitalizedGenre  - $displayType",
                        urlPath = "/catalog/$catalogType/$catalogId/genre=${genre.urlEncodeLocal()}&skip=###.json"
                    ))
                }
            }
            val tabBuildMs = (System.nanoTime() - tabBuildStarted) / 1_000_000

            val orderStarted = System.nanoTime()
            val orderKey = "catalog_order_$uniqueKey"
            val legacyKey = currentUrl.toLegacyUniqueKey()
            val orderJson = prefs.getString(orderKey, null)
                ?: prefs.getString("catalog_order_$legacyKey", null)
            val orderedKeys = mutableListOf<String>()
            if (orderJson != null) {
                try {
                    val arr = org.json.JSONArray(orderJson)
                    for (i in 0 until arr.length()) {
                        orderedKeys.add(arr.getString(i))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            val rank = orderedKeys.withIndex().associate { it.value to it.index }
            val sortedTabs = flatTabs.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
            val orderMs = (System.nanoTime() - orderStarted) / 1_000_000

            val filterStarted = System.nanoTime()
            val enabledTabs = buildList<Pair<String, String>> {
                    sortedTabs.forEach { tab ->
                        val isEnabled = if (tab.key.contains("_genre_")) {
                            val catalogKey = tab.key.substringBefore("_genre_")
                            val masterKey = "genres_enabled_${uniqueKey}_$catalogKey"
                            val tabKey = "tab_enabled_${uniqueKey}_${tab.key}"
                            val isGenresMasterEnabled = if (prefs.contains(masterKey)) prefs.getBoolean(masterKey, true)
                                else prefs.getBoolean("genres_enabled_${legacyKey}_$catalogKey", true)
                            val isIndividualGenreEnabled = if (prefs.contains(tabKey)) prefs.getBoolean(tabKey, true)
                                else prefs.getBoolean("tab_enabled_${legacyKey}_${tab.key}", true)
                            isGenresMasterEnabled && isIndividualGenreEnabled
                        } else {
                            val tabKey = "tab_enabled_${uniqueKey}_${tab.key}"
                            if (prefs.contains(tabKey)) prefs.getBoolean(tabKey, true)
                                else prefs.getBoolean("tab_enabled_${legacyKey}_${tab.key}", true)
                        }
                        
                        if (isEnabled) {
                            add(tab.urlPath to tab.displayName)
                        }
                    }
                }
            val filterMs = (System.nanoTime() - filterStarted) / 1_000_000
            println("[StremioC-Home][MAINPAGE_BUILD] provider=$name source=$manifestSource catalogs=${rawCatalogs.size} flatTabs=${flatTabs.size} enabledTabs=${enabledTabs.size} migrateMs=$migrateMs manifestReadMs=$manifestReadMs manifestParseMs=$manifestParseMs tabBuildMs=$tabBuildMs orderMs=$orderMs filterMs=$filterMs totalMs=${(System.nanoTime() - perfStarted) / 1_000_000}")
            return mainPageOf(*enabledTabs.toTypedArray())
        }

    private fun getCatalogStateKey(request: MainPageRequest): String {
        return "$mainUrl|${request.data}"
    }

    fun homeTuningSummary(): String =
        "foreground=${HOME_FOREGROUND_CONCURRENCY} background=${HOME_PREFETCH_CONCURRENCY} manifestWarm=singleFlight prefetchInFlight=handoff"

    private fun isThisProviderSelectedOnHome(): Boolean =
        DataStoreHelper.currentHomePage?.equals(name, ignoreCase = true) == true

    private suspend fun waitForBackgroundPrefetchWindow(): Long {
        val started = System.nanoTime()
        while (foregroundHomeRequests.get() > 0 || isThisProviderSelectedOnHome()) {
            delay(HOME_PREFETCH_IDLE_POLL_MS)
        }
        return (System.nanoTime() - started) / 1_000_000
    }

    private suspend fun consumeOneShotPrefetch(request: MainPageRequest): HomePageResponse? {
        val stateKey = getCatalogStateKey(request)
        val now = System.currentTimeMillis()
        var expired: OneShotPrefetch? = null
        val entry = prefetchMutex.withLock {
            // Mark this row as foreground-seen for the current visit. It is cleared only after
            // CloudStream switches away from this provider, so background refresh cannot rewind
            // page-2 pagination while this provider is selected.
            foregroundSeenPage1.add(stateKey)
            val candidate = page1Prefetch[stateKey] ?: return@withLock null
            if (now - candidate.createdAtMs > HOME_PREFETCH_TTL_MS) {
                if (page1Prefetch[stateKey] === candidate) page1Prefetch.remove(stateKey)
                expired = candidate
                null
            } else {
                candidate.claimed = true
                candidate
            }
        }

        if (expired != null) {
            expired!!.scope.cancel()
            println("[StremioC-Home][PREFETCH] provider=$name tab=${request.name} stage=MISS reason=EXPIRED")
            return null
        }
        if (entry == null) {
            println("[StremioC-Home][PREFETCH] provider=$name tab=${request.name} stage=MISS reason=NONE")
            return null
        }

        val state = if (entry.deferred.isCompleted) "READY" else "IN_FLIGHT"
        val ageMs = now - entry.createdAtMs
        println("[StremioC-Home][PREFETCH] trace=${entry.traceId} provider=$name tab=${request.name} stage=CONSUME state=$state ageMs=$ageMs")
        return try {
            val awaitStarted = System.nanoTime()
            val result = entry.deferred.await()
            println("[StremioC-Home][PREFETCH] trace=${entry.traceId} provider=$name tab=${request.name} stage=CONSUMED awaitMs=${(System.nanoTime() - awaitStarted) / 1_000_000}")
            result
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) throw e
            println("[StremioC-Home][PREFETCH] trace=${entry.traceId} provider=$name tab=${request.name} stage=FALLBACK reason=CANCELLED")
            null
        } catch (t: Throwable) {
            println("[StremioC-Home][PREFETCH] trace=${entry.traceId} provider=$name tab=${request.name} stage=FALLBACK reason=FAILED errorClass=${t.javaClass.simpleName}")
            null
        } finally {
            prefetchMutex.withLock {
                if (page1Prefetch[stateKey] === entry) page1Prefetch.remove(stateKey)
            }
            entry.scope.cancel()
        }
    }

    private suspend fun prefetchOnePage(request: MainPageRequest) {
        val stateKey = getCatalogStateKey(request)
        val traceId = "p" + (System.nanoTime() and 0xFFFFFFL).toString(16)
        var skipReason: String? = null

        val entry = prefetchMutex.withLock {
            if (foregroundSeenPage1.contains(stateKey)) {
                skipReason = "FOREGROUND_SEEN"
                return@withLock null
            }
            page1Prefetch[stateKey]?.let {
                skipReason = "EXISTS"
                return@withLock null
            }

            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val deferred = scope.async(start = CoroutineStart.LAZY) {
                executeMainPage(1, request, "PREFETCH", traceId)
            }
            OneShotPrefetch(
                scope = scope,
                deferred = deferred,
                traceId = traceId,
                createdAtMs = System.currentTimeMillis()
            ).also { page1Prefetch[stateKey] = it }
        }

        if (entry == null) {
            println("[StremioC-Home][PREFETCH] provider=$name tab=${request.name} stage=SKIP reason=${skipReason ?: "UNKNOWN"}")
            return
        }

        println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=START")
        entry.deferred.start()
        try {
            val started = System.nanoTime()
            entry.deferred.await()
            println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=READY ms=${(System.nanoTime() - started) / 1_000_000}")

            // Keep only the completed one-shot result. It is never persisted and is removed on
            // first foreground use or after the short TTL.
            entry.scope.launch {
                delay(HOME_PREFETCH_TTL_MS)
                var removed = false
                prefetchMutex.withLock {
                    if (page1Prefetch[stateKey] === entry && !entry.claimed) {
                        page1Prefetch.remove(stateKey)
                        removed = true
                    }
                }
                if (removed) {
                    println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=EXPIRE ttlMs=$HOME_PREFETCH_TTL_MS")
                    entry.scope.cancel()
                }
            }
        } catch (e: CancellationException) {
            if (!currentCoroutineContext().isActive) {
                // HOME_SWITCH return cancels the refill coordinator, not the independent row scope.
                // Hold this background permit until the exact request completes, so BG=4 remains a
                // real upper bound. Foreground may await the same Deferred concurrently.
                println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=HANDOFF_IN_FLIGHT reason=COORDINATOR_CANCELLED")
                val completed = withContext(NonCancellable) {
                    runCatching { entry.deferred.await() }.isSuccess
                }
                if (completed) {
                    println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=READY_HANDOFF")
                    entry.scope.launch {
                        delay(HOME_PREFETCH_TTL_MS)
                        var removed = false
                        prefetchMutex.withLock {
                            if (page1Prefetch[stateKey] === entry && !entry.claimed) {
                                page1Prefetch.remove(stateKey)
                                removed = true
                            }
                        }
                        if (removed) {
                            println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=EXPIRE ttlMs=$HOME_PREFETCH_TTL_MS")
                            entry.scope.cancel()
                        }
                    }
                } else {
                    prefetchMutex.withLock {
                        if (page1Prefetch[stateKey] === entry) page1Prefetch.remove(stateKey)
                    }
                    if (!entry.claimed) entry.scope.cancel()
                    println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=HANDOFF_FAILED")
                }
                throw e
            }

            // The row Deferred itself was cancelled (for example explicit INVALIDATE), so remove it.
            prefetchMutex.withLock {
                if (page1Prefetch[stateKey] === entry) page1Prefetch.remove(stateKey)
            }
            println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=CANCELLED")
            entry.scope.cancel()
        } catch (t: Throwable) {
            prefetchMutex.withLock {
                if (page1Prefetch[stateKey] === entry) page1Prefetch.remove(stateKey)
            }
            println("[StremioC-Home][PREFETCH] trace=$traceId provider=$name tab=${request.name} stage=FAILED errorClass=${t.javaClass.simpleName}")
            entry.scope.cancel()
        }
    }

    /**
     * Volatile page-1 prefetch for this registered Stremio provider.
     *
     * - boot-time fill + re-fill after the provider is left
     * - never writes catalog results to disk/SharedPreferences
     * - never prefetches page 2+
     * - never starts new prefetch work while this provider is the selected homepage
     * - A/B profile B allows at most four background catalog fetches globally
     * - successful results are consumed once and then removed, or expire after 5 minutes
     */
    suspend fun prefetchEnabledMainPageCatalogs(reason: String = "BOOT") {
        if (mainUrl.isBlank()) return
        prefetchSweepMutex.withLock {
            val tabs = try {
                mainPage
            } catch (t: Throwable) {
                println("[StremioC-Home][PREFETCH] provider=$name stage=PROVIDER_FAILED point=MAINPAGE reason=$reason errorClass=${t.javaClass.simpleName}")
                return@withLock
            }
            if (tabs.isEmpty()) {
                println("[StremioC-Home][PREFETCH] provider=$name stage=PROVIDER_EMPTY reason=$reason")
                return@withLock
            }

            println("[StremioC-Home][PREFETCH] provider=$name stage=PROVIDER_START reason=$reason tabs=${tabs.size}")
            var startedCount = 0
            for (tab in tabs) {
                currentCoroutineContext().ensureActive()
                val request = MainPageRequest(tab.name, tab.data, tab.horizontalImages)
                if (prefetchMutex.withLock { foregroundSeenPage1.contains(getCatalogStateKey(request)) }) {
                    println("[StremioC-Home][PREFETCH] provider=$name tab=${tab.name} stage=SKIP reason=FOREGROUND_SEEN cycle=$reason")
                    continue
                }

                val foregroundWaitMs = waitForBackgroundPrefetchWindow()
                if (foregroundWaitMs > 0) {
                    println("[StremioC-Home][PREFETCH] provider=$name tab=${tab.name} stage=WAIT_WINDOW cycle=$reason waitMs=$foregroundWaitMs selected=${DataStoreHelper.currentHomePage ?: "none"}")
                }
                homePrefetchSemaphore.withPermit {
                    // Re-check after acquiring a background slot. A provider switch or foreground
                    // pagination can happen while this worker is queued.
                    val afterSlotWaitMs = waitForBackgroundPrefetchWindow()
                    if (afterSlotWaitMs > 0) {
                        println("[StremioC-Home][PREFETCH] provider=$name tab=${tab.name} stage=WAIT_WINDOW_AFTER_SLOT cycle=$reason waitMs=$afterSlotWaitMs selected=${DataStoreHelper.currentHomePage ?: "none"}")
                    }
                    if (prefetchMutex.withLock { foregroundSeenPage1.contains(getCatalogStateKey(request)) }) {
                        println("[StremioC-Home][PREFETCH] provider=$name tab=${tab.name} stage=SKIP reason=FOREGROUND_SEEN_AFTER_WAIT cycle=$reason")
                    } else {
                        prefetchOnePage(request)
                        startedCount++
                    }
                }
            }
            println("[StremioC-Home][PREFETCH] provider=$name stage=PROVIDER_DONE reason=$reason attempted=$startedCount tabs=${tabs.size}")
        }
    }

    /**
     * Called after CloudStream switches away from this provider. The consumed one-shot rows are
     * replenished in RAM while another provider is selected. Existing unconsumed READY entries
     * are preserved; only missing rows are fetched again. No catalog result is persisted to disk.
     */
    suspend fun rearmOneShotCatalogPrefetch(reason: String = "HOME_SWITCH") {
        if (mainUrl.isBlank()) return
        val clearedSeen = prefetchMutex.withLock {
            val count = foregroundSeenPage1.size
            foregroundSeenPage1.clear()
            count
        }
        println("[StremioC-Home][PREFETCH_REFRESH] provider=$name stage=REARM reason=$reason clearedForegroundSeen=$clearedSeen existing=${prefetchMutex.withLock { page1Prefetch.size }}")
        prefetchEnabledMainPageCatalogs("REARM_$reason")
    }


    suspend fun cancelOneShotCatalogPrefetch(reason: String = "INVALIDATE") {
        val entries = prefetchMutex.withLock {
            val copy = page1Prefetch.values.toList()
            page1Prefetch.clear()
            foregroundSeenPage1.clear()
            copy
        }
        entries.forEach { it.scope.cancel() }
        if (entries.isNotEmpty()) {
            println("[StremioC-Home][PREFETCH] provider=$name stage=INVALIDATE reason=$reason entries=${entries.size}")
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        foregroundHomeRequests.incrementAndGet()
        try {
            return try {
                if (page <= 1) {
                    val prefetched = consumeOneShotPrefetch(request)
                    prefetched ?: executeMainPage(page, request, "FOREGROUND")
                } else {
                    executeMainPage(page, request, "FOREGROUND")
                }
            } catch (e: CancellationException) {
                // Navigation/provider switches must still cancel promptly. Swallowing cancellation
                // would keep obsolete network work alive and corrupt pagination generations.
                throw e
            } catch (t: Throwable) {
                val status = (t as? CatalogHttpException)?.statusCode?.toString() ?: "none"
                println("[StremioC-Home][ROW_FAIL_ISOLATED] provider=$name tab=${request.name} page=$page status=$status errorClass=${t.javaClass.simpleName}")
                newHomePageResponse(listOf(HomePageList(request.name, emptyList<SearchResponse>())), hasNext = false)
            }
        } finally {
            foregroundHomeRequests.decrementAndGet()
        }
    }

    private suspend fun executeMainPage(
        page: Int,
        request: MainPageRequest,
        origin: String,
        forcedTraceId: String? = null
    ): HomePageResponse {
        val totalStarted = System.nanoTime()
        val requestedTraceId = forcedTraceId ?: (System.nanoTime() and 0xFFFFFFL).toString(16)
        var traceId = requestedTraceId
        val stateKey = getCatalogStateKey(request)
        val key = "$stateKey|page=$page"
        var owner = false

        val dispatchLockStarted = System.nanoTime()
        val flight = pageMutex.withLock {
            val dispatchLockMs = (System.nanoTime() - dispatchLockStarted) / 1_000_000
            val existing = activePageRequests[key]
            if (existing != null) {
                existing.waiters++
                traceId = existing.traceId
                println("[StremioC-Home][PERF][$traceId][DISPATCH] provider=$name tab=${request.name} page=$page origin=$origin mode=JOIN waiters=${existing.waiters} lockWaitMs=$dispatchLockMs")
                existing
            } else {
                owner = true
                val generation = if (page <= 1) {
                    catalogSentIds.remove(stateKey)
                    catalogSkipState.remove(stateKey)
                    ((catalogGenerations[stateKey] ?: 0L) + 1L).also { catalogGenerations[stateKey] = it }
                } else catalogGenerations[stateKey] ?: 0L
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val deferred = scope.async(start = CoroutineStart.LAZY) {
                    fetchTabCatalogData(request, page, generation, requestedTraceId, origin)
                }
                println("[StremioC-Home][PERF][$requestedTraceId][DISPATCH] provider=$name tab=${request.name} page=$page origin=$origin mode=OWNER generation=$generation lockWaitMs=$dispatchLockMs")
                PageFlight(scope, deferred, requestedTraceId, 1).also { activePageRequests[key] = it }
            }
        }

        try {
            val awaitStarted = System.nanoTime()
            val response = flight.deferred.await()
            println("[StremioC-Home][PERF][$traceId][AWAIT] provider=$name tab=${request.name} page=$page origin=$origin owner=$owner awaitMs=${(System.nanoTime() - awaitStarted) / 1_000_000}")
            return if (!owner && page > 1) newHomePageResponse(emptyList(), hasNext = true) else response
        } finally {
            val cleanupStarted = System.nanoTime()
            withContext(NonCancellable) {
                pageMutex.withLock {
                    flight.waiters--
                    if (flight.waiters == 0) {
                        if (activePageRequests[key] === flight) activePageRequests.remove(key)
                        flight.scope.cancel()
                    }
                }
            }
            println("[StremioC-Home][PERF][$traceId][TOTAL] provider=$name tab=${request.name} page=$page origin=$origin cleanupMs=${(System.nanoTime() - cleanupStarted) / 1_000_000} totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
        }
    }

    private suspend fun fetchTabCatalogData(
        request: MainPageRequest,
        page: Int,
        generation: Long,
        traceId: String,
        origin: String
    ): HomePageResponse {
        val totalStarted = System.nanoTime()
        val stateKey = getCatalogStateKey(request)

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            println("[StremioC-Home][PERF][$traceId][STALE] provider=$name tab=${request.name} page=$page origin=$origin point=ENTRY")
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        if (page > 1 && !catalogSkipState.containsKey(stateKey)) {
            val coldStarted = System.nanoTime()
            println("[StremioC-Home][PERF][$traceId][COLD_START] provider=$name tab=${request.name} page=$page origin=$origin action=PREFETCH_PAGE1")
            fetchTabCatalogData(request, 1, generation, traceId, origin)
            println("[StremioC-Home][PERF][$traceId][COLD_START] provider=$name tab=${request.name} page=$page origin=$origin prefetchMs=${(System.nanoTime() - coldStarted) / 1_000_000}")
        }

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            println("[StremioC-Home][PERF][$traceId][STALE] provider=$name tab=${request.name} page=$page origin=$origin point=BEFORE_FETCH")
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val currentSkip = catalogSkipState[stateKey] ?: 0
        val targetPath = request.data.replace("###", currentSkip.toString())
        val targetUrl = buildUrl(targetPath)
        println("[StremioC-Home][PERF][$traceId][START] provider=$name tab=${request.name} page=$page origin=$origin skip=$currentSkip")

        val queueStarted = System.nanoTime()
        val jsonText = StremioC.homeNetworkSemaphore.withPermit {
            val queueWaitMs = (System.nanoTime() - queueStarted) / 1_000_000
            println("[StremioC-Home][PERF][$traceId][QUEUE] provider=$name tab=${request.name} page=$page origin=$origin waitMs=$queueWaitMs")
            val httpStarted = System.nanoTime()
            try {
                val text = fetchHomeJson(targetUrl, HomeRequestTrace(traceId, name, request.name, page, origin))
                println("[StremioC-Home][PERF][$traceId][HTTP] provider=$name tab=${request.name} page=$page origin=$origin ms=${(System.nanoTime() - httpStarted) / 1_000_000} bytes=${text.length}")
                text
            } catch (t: Throwable) {
                println("[StremioC-Home][PERF][$traceId][HTTP_FAILED] provider=$name tab=${request.name} page=$page origin=$origin ms=${(System.nanoTime() - httpStarted) / 1_000_000} errorClass=${t.javaClass.simpleName}")
                throw t
            }
        }

        val parseStarted = System.nanoTime()
        val metas = try {
            parseJson<HomeCatalogResponse>(jsonText).metas
                ?: throw IllegalStateException("Catalog response is missing metas")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("[StremioC-Home][PERF][$traceId][PARSE_FAILED] provider=$name tab=${request.name} page=$page origin=$origin ms=${(System.nanoTime() - parseStarted) / 1_000_000} errorClass=${e.javaClass.simpleName}")
            throw IllegalStateException("Invalid catalog response", e)
        }
        val parseMs = (System.nanoTime() - parseStarted) / 1_000_000
        println("[StremioC-Home][PERF][$traceId][PARSE] provider=$name tab=${request.name} page=$page origin=$origin ms=$parseMs metas=${metas.size}")

        if ((catalogGenerations[stateKey] ?: 0L) != generation) {
            println("[StremioC-Home][PERF][$traceId][STALE] provider=$name tab=${request.name} page=$page origin=$origin point=AFTER_PARSE")
            return newHomePageResponse(emptyList(), hasNext = false)
        }

        val mapStarted = System.nanoTime()
        val mappedItems = metas.map { meta ->
            val type = meta.type ?: "movie"
            val title = meta.name?.takeIf { it.isNotBlank() }
                ?: "[${type.replaceFirstChar { it.uppercase() }}] ${meta.id}"
            newMovieSearchResponse(title, getUnifiedMetaUrl(type, meta.id ?: ""), TvType.Others) {
                posterUrl = meta.poster
            }
        }
        val mapMs = (System.nanoTime() - mapStarted) / 1_000_000
        println("[StremioC-Home][PERF][$traceId][MAP] provider=$name tab=${request.name} page=$page origin=$origin ms=$mapMs items=${mappedItems.size}")
        val resultPair = Pair(mappedItems, metas.size)

        val finalizeLockStarted = System.nanoTime()
        return pageMutex.withLock {
            val finalizeLockMs = (System.nanoTime() - finalizeLockStarted) / 1_000_000
            if ((catalogGenerations[stateKey] ?: 0L) != generation) {
                println("[StremioC-Home][PERF][$traceId][STALE] provider=$name tab=${request.name} page=$page origin=$origin point=FINALIZE lockWaitMs=$finalizeLockMs")
                return@withLock newHomePageResponse(emptyList(), hasNext = false)
            }

            val stateStarted = System.nanoTime()
            catalogSkipState[stateKey] = currentSkip + resultPair.second

            var seenForThisCatalog = catalogSentIds[stateKey]
            if (seenForThisCatalog == null) {
                synchronized(catalogSentIds) {
                    seenForThisCatalog = catalogSentIds.getOrPut(stateKey) {
                        lruSet(500)
                    }
                }
            }

            var duplicateCount = 0
            val dedupStarted = System.nanoTime()
            val filteredItems = resultPair.first.filter { item ->
                val pureId = item.extractPureId()
                val isNew = seenForThisCatalog!!.add(pureId)
                if (!isNew) duplicateCount++
                isNew
            }
            val dedupMs = (System.nanoTime() - dedupStarted) / 1_000_000
            val stateMs = (System.nanoTime() - stateStarted) / 1_000_000
            println("[StremioC-Home][PERF][$traceId][FINALIZE] provider=$name tab=${request.name} page=$page origin=$origin lockWaitMs=$finalizeLockMs stateMs=$stateMs dedupMs=$dedupMs duplicates=$duplicateCount returned=${filteredItems.size} nextSkip=${catalogSkipState[stateKey]} totalFetchMs=${(System.nanoTime() - totalStarted) / 1_000_000}")

            newHomePageResponse(
                listOf(HomePageList(request.name, filteredItems)),
                hasNext = resultPair.second > 0
            )
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val supportedCatalogs = manifest?.catalogs?.filter { it.supportsSearch() } ?: emptyList()
        
        val searchResults = coroutineScope {
            supportedCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try { catalog.search(query, this@StremioC) } catch (e: CancellationException) { throw e } catch (e: Exception) { e.printStackTrace(); emptyList() }
                }
            }.awaitAll().flatten()
        }

        val seenIds = mutableSetOf<String>()
        return searchResults.filter { item ->
            val extractedId = item.extractPureId()
            if (seenIds.contains(extractedId)) false else { seenIds.add(extractedId); true }
        }
    }
    
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        var initialMetaFromResponse: CatalogEntry? = null
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson<CatalogEntry>(url)
        } else {
            val responseText = customSession.get(url).text
            try {
                val rootNode = parseJson<JsonNode>(responseText)
                val metaNode = if (rootNode.has("meta")) rootNode.get("meta") else rootNode
                val parsedEntry = parseJson<CatalogEntry>(metaNode.toString())
                    ?: throw IllegalStateException("Failed to bind JSON to CatalogEntry")
                if (rootNode.has("meta") && metaNode.isObject) {
                    initialMetaFromResponse = parsedEntry
                }
                parsedEntry
            } catch (e: Exception) {
                e.printStackTrace()
                throw IllegalStateException("Metadata not found or parsing failed: $url", e)
            }
        }
        
        val normalizedId = try { normalizeId(res.id ?: "") } catch (e: Exception) { e.printStackTrace(); res.id ?: "" }
        val encodedId = normalizedId.urlEncode()

        val addonDeferred = async(Dispatchers.IO) {
            runSuspendCatching {
                val syncMetaUrl = this@StremioC.getUnifiedMetaUrl(res.type ?: "movie", res.id ?: "")
                if (syncMetaUrl == url && initialMetaFromResponse != null) {
                    initialMetaFromResponse
                } else {
                    val text = customSession.get(syncMetaUrl).text
                    val response = if (text.isNotBlank()) parseJson<CatalogResponse>(text) else null
                    response?.meta ?: response?.metas?.firstOrNull { it.id == res.id } ?: response?.metas?.firstOrNull()
                }
            }.onFailure { it.printStackTrace() }.getOrNull()
        }

        val isKitsuOrMal = res.id?.startsWith("kitsu:") == true || res.id?.startsWith("mal:") == true
        var fetchedAniListId: Int? = null
        var fetchedMalId: Int? = null
        var resolvedImdbId: String? = null
        var resolvedTmdbId: String? = null
        var kitsuVideos: List<Video>? = null
        
        if (isKitsuOrMal) {
            val targetType = if (res.type == "movie") "movie" else "series"
            val kitsuUrl = "https://anime-kitsu.strem.fun/meta/$targetType/${res.id}.json"
            val kitsuRes = getOrFetchKitsu(kitsuUrl)
            
            if (!kitsuRes.isNullOrBlank()) {
                runSuspendCatching {
                    val root = parseJson<JsonNode>(kitsuRes)
                    val metaObj = root.get("meta")
                    if (metaObj != null && !metaObj.isNull) {
                        resolvedImdbId = metaObj.get("imdb_id").textOrNull()?.takeIf { it.startsWith("tt") }
                        resolvedTmdbId = metaObj.get("tmdb_id").textOrNull() 
                            ?: metaObj.get("moviedb_id").intOrNull()?.takeIf { it > 0 }?.toString()
                        
                        fetchedAniListId = metaObj.get("anilist_id").intOrNull()
                        fetchedMalId = metaObj.get("mal_id").intOrNull()

                        val videosNode = metaObj.get("videos")
                        if (videosNode != null && videosNode.isArray) {
                            val vids = mutableListOf<Video>()
                            for (i in 0 until videosNode.size()) {
                                val v = videosNode.get(i)
                                if (v != null && !v.isNull) {
                                    runSuspendCatching {
                                        val vid = Video(
                                            id = v.get("id").textOrNull(),
                                            title = v.get("title").textOrNull(),
                                            name = v.get("name").textOrNull(),
                                            seasonNumber = v.get("season").intOrNull()?.takeIf { it > 0 },
                                            number = v.get("number").intOrNull()?.takeIf { it > 0 },
                                            episode = v.get("episode").intOrNull()?.takeIf { it > 0 },
                                            thumbnail = v.get("thumbnail").textOrNull(),
                                            overview = v.get("overview").textOrNull(),
                                            description = v.get("description").textOrNull(),
                                            firstAired = v.get("firstAired").textOrNull(),
                                            released = v.get("released").textOrNull(),
                                            tvdb_id = v.get("tvdb_id").intOrNull()?.takeIf { it > 0 },
                                            imdbSeason = v.get("imdbSeason").intOrNull()?.takeIf { it > 0 },
                                            imdbEpisode = v.get("imdbEpisode").intOrNull()?.takeIf { it > 0 },
                                            imdb_id = v.get("imdb_id").textOrNull()
                                        )
                                        vids.add(vid)
                                    }.onFailure { it.printStackTrace() }
                                }
                            }
                            if (vids.isNotEmpty()) kitsuVideos = vids
                        }
                    }
                }.onFailure { it.printStackTrace() }
            }
        }

        val tmdbIdFromRes = res.moviedb_id?.toString() ?: res.tmdbIdExt
        val targetTmdbId = resolvedTmdbId ?: if (res.id?.startsWith("tmdb:") == true) res.id.removePrefix("tmdb:") else tmdbIdFromRes
        
        val tmdbDeferred = targetTmdbId?.let { id ->
            async(Dispatchers.IO) {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val detailAppend = if (mediaType == "movie") "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = buildTmdbUrl("/$mediaType/$id", "append_to_response" to detailAppend)
                val cached = tmdbDetailsCache[detailUrl]
                if (cached != null && System.currentTimeMillis() - cached.second < METADATA_CACHE_TTL) {
                    cached.first
                } else {
                    runSuspendCatching {
                        val text = customSession.get(detailUrl).text
                        val fetched = if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                        if (fetched != null) {
                            tmdbDetailsCache[detailUrl] = Pair(fetched, System.currentTimeMillis())
                        }
                        fetched
                    }.onFailure { it.printStackTrace() }.getOrNull()
                }
            }
        }

        val preFetchedTmdbDetail = tmdbDeferred?.await()
        
        var finalProcessedId = res.id
        var imdbId = resolvedImdbId ?: preFetchedTmdbDetail?.external_ids?.imdb_id

        if (!imdbId.isNullOrBlank() && (imdbId.startsWith("tt") || imdbId.startsWith("kitsu:"))) {
            finalProcessedId = imdbId
        }

        val resolvedIdHolder = arrayOf<String?>(null, null)

        val earlyMdbId = resolvedTmdbId ?: resolvedImdbId ?: when {
            res.id?.startsWith("tt") == true -> res.id
            res.id?.startsWith("tmdb:") == true -> res.id.removePrefix("tmdb:")
            res.moviedb_id != null -> res.moviedb_id.toString()
            res.tmdbIdExt != null -> res.tmdbIdExt
            else -> null
        }
        val mdbEnabled = isIntegrationEnabled("mdblist_enabled")
        val earlyMdbIsTmdb = (earlyMdbId == resolvedTmdbId) || (earlyMdbId?.startsWith("tt") == false)
        val mdbDeferred: Deferred<List<String>>? = if (mdbEnabled && earlyMdbId != null) {
            async(Dispatchers.IO) {
                runSuspendCatching { fetchMdbListRatings(earlyMdbId, earlyMdbIsTmdb) }
                    .getOrDefault(emptyList<String>())
            }
        } else {
            null
        }

        val loadRes = res.toLoadResponse(
            provider = this@StremioC, 
            imdbId = finalProcessedId, 
            addonDeferred = addonDeferred, 
            preFetchedDetail = preFetchedTmdbDetail, 
            fetchedAniListId = fetchedAniListId, 
            fetchedMalId = fetchedMalId, 
            resolvedTmdbId = resolvedTmdbId,
            kitsuVideos = kitsuVideos,
            resolvedIdHolder = resolvedIdHolder
        )
        
        val primaryMdbId = resolvedTmdbId ?: resolvedImdbId ?: when {
            res.id?.startsWith("tt") == true -> res.id
            res.id?.startsWith("tmdb:") == true -> res.id.removePrefix("tmdb:")
            res.moviedb_id != null -> res.moviedb_id.toString()
            res.tmdbIdExt != null -> res.tmdbIdExt
            else -> null
        }
        val primaryMdbIsTmdb = (primaryMdbId == resolvedTmdbId) || (primaryMdbId?.startsWith("tt") == false)

        var actualMdbId = primaryMdbId
        var actualIsTmdb = primaryMdbIsTmdb

        if (actualMdbId == null) {
            val resolvedImdb = resolvedIdHolder[0]?.takeIf { it.startsWith("tt") }
            val resolvedTmdb = resolvedIdHolder[1]?.takeIf { it.isNotBlank() }
            actualMdbId = resolvedImdb ?: resolvedTmdb
            actualIsTmdb = (actualMdbId == resolvedTmdb)
        }

        if (actualMdbId != null && mdbEnabled) {
            val mdbRatings = if (mdbDeferred != null && actualMdbId == earlyMdbId && actualIsTmdb == earlyMdbIsTmdb) {
                mdbDeferred.await()
            } else runSuspendCatching {
                fetchMdbListRatings(actualMdbId, actualIsTmdb)
            }.onFailure { it.printStackTrace() }.getOrDefault(emptyList())

            if (mdbRatings.isNotEmpty()) {
                val ratingStr = mdbRatings.joinToString("  ")
                val currentPlot = loadRes.plot 
                
                loadRes.plot = if (!currentPlot.isNullOrBlank()) "$ratingStr<br>$currentPlot" else ratingStr
                loadRes.score = null
            }
        }
        
        return@coroutineScope loadRes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) { e.printStackTrace(); null } ?: return false
        
        val primaryId = if (loadData.id?.startsWith("tmdb:") == true && !loadData.imdbId.isNullOrBlank()) {
            loadData.imdbId
        } else {
            loadData.id ?: ""
        }

        val globalId = if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else primaryId
        val targetType = loadData.type ?: if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) "series" else "movie"
        
        val actualIsMovie = targetType == "movie" || (loadData.season == null && loadData.episode == null)
        
        val globalSeason = if (actualIsMovie) null else loadData.imdbSeason?.takeIf { it > 0 } ?: loadData.season?.takeIf { it > 0 } ?: 1
        val globalEpisode = if (actualIsMovie) null else loadData.imdbEpisode?.takeIf { it > 0 } ?: loadData.episode?.takeIf { it > 0 } ?: 1

        val primaryStremioId = buildStremioId(targetType, primaryId, loadData.season, loadData.episode) ?: primaryId
        val encodedPrimaryId = primaryStremioId.urlEncode()

        val seenLinks = Collections.synchronizedSet(mutableSetOf<Triple<String, String, Map<String, String>>>())
        // Only deduplicate subtitles supplied directly by Stremio addons.
        // CloudStream extractors/integrations must keep using the original callback.
        val seenStremioSubtitles = Collections.synchronizedSet(mutableSetOf<String>())
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(Triple(link.url, link.referer, link.headers.toMap()))) {
                callback(link)
            }
        }

        val copyId = loadData.imdbId ?: loadData.id ?: ""
        if (!copyId.isNullOrBlank()) {
            wrappedCallback.invoke(
                newExtractorLink(
                    "Copy to clipboard",
                    "📋 Video ID: $copyId",
                    copyId,
                    ExtractorLinkType.VIDEO
                )
                {
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        val currentMainUrl = loadData.addonUrl?.takeIf { it.isNotBlank() } ?: mainUrl
        val cleanBaseUrl = currentMainUrl.fixSourceUrl().cleanBaseUrl()
        val querySuffix = currentMainUrl.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""

        val (syncUrl, syncMap) = getSyncSettings()
        val currentCleanUrl = currentMainUrl.cleanBaseUrl().lowercase()
        val isSyncEnabled = syncMap[currentCleanUrl] ?: false

        val customHosts = withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            if (cachedCustomHosts != null && now - cachedCustomHostsTime < SETTINGS_CACHE_TTL) {
                return@withContext cachedCustomHosts!!
            }
            
            val context = CloudStreamApp.context
            
            try {
                if (context != null) {
                    val sharedPrefsDir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
                    if (sharedPrefsDir.exists() && sharedPrefsDir.isDirectory) {
                        sharedPrefsDir.listFiles()?.forEach { file ->
                            val name = file.name.removeSuffix(".xml")
                            val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                            if (p.contains("stremio_addons")) {
                                println("[STREMIOSUB] Setup Scan: Found 'stremio_addons' key in file: '$name'")
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                println("[STREMIOSUB] Setup Scan Warning: ${e.message}")
                e.printStackTrace()
            }

            val raw = try { 
                val rebuildPrefs = context?.getSharedPreferences("rebuild_preference", Context.MODE_PRIVATE)
                var rawValue = rebuildPrefs?.getString("stremio_addons", "") ?: ""
                
                if (rawValue.isBlank()) {
                    val rebuildPrefsBackup = context?.getSharedPreferences("rebuild_key", Context.MODE_PRIVATE)
                    rawValue = rebuildPrefsBackup?.getString("stremio_addons", "") ?: ""
                }
                
                println("[STREMIOSUB] Attempt 1 (rebuild_preference/key) -> raw value length: ${rawValue.length}")
                if (rawValue.startsWith("\"") && rawValue.endsWith("\"") && rawValue.length >= 2) {
                    rawValue.substring(1, rawValue.length - 1)
                } else {
                    rawValue
                }
            } catch (t: Throwable) {
                println("[STREMIOSUB] Attempt 1 (rebuild_preference) Crash: ${t.message}. Trying Fallback.")
                t.printStackTrace()
                try {
                    val prefs = CloudStreamApp.context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
                    val v = prefs?.getString("stremio_addons", "") ?: ""
                    println("[STREMIOSUB] Attempt 2 (default SharedPreferences) -> length: ${v.length}")
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
                } catch (e: Exception) {
                    println("[STREMIOSUB] Attempt 2 (default SharedPreferences) Failed: ${e.message}")
                    e.printStackTrace()
                    ""
                }
            }
            
            val hosts = raw.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { URI(it.split("|").getOrElse(1) { "" }).host }.onFailure { it.printStackTrace() }.getOrNull() }
                .toSet() 
                
            println("[STREMIOSUB] Final parsed customHosts count: ${hosts.size}")
            cachedCustomHosts = hosts
            cachedCustomHostsTime = now
            hosts
        }
        val builtInHosts = setOf("opensubtitles.stremio.homes", "subsense.nepiraw.com", "opensubtitles-v3.strem.io")

        // Keep the original URL-based label handling for subtitles returned by
        // CineStream, StreamPlay and VixSrc. These providers can also return
        // CloudStream subtitles, so do not deduplicate them here.
        val integrationSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            val subtitleUrl = sub.url
            try {
                val start = subtitleUrl.indexOf("://")
                val host = if (start != -1) {
                    val hostStart = start + 3
                    val end = subtitleUrl.indexOf('/', hostStart)
                    val hostPort = if (end == -1) subtitleUrl.substring(hostStart) else subtitleUrl.substring(hostStart, end)
                    val portIdx = hostPort.indexOf(':')
                    if (portIdx == -1) hostPort else hostPort.substring(0, portIdx)
                } else ""

                val isCustom = customHosts.contains(host)
                val isBuiltIn = builtInHosts.contains(host)
                val isContainsStrem = host.contains("strem", ignoreCase = true)

                val isStremio = isCustom || isBuiltIn || isContainsStrem

                if (isStremio && !sub.lang.contains("(Stremio)", ignoreCase = true)) {
                    val newLang = "${sub.lang} (Stremio)"
                    subtitleCallback.invoke(SubtitleFile(newLang, subtitleUrl))
                } else {
                    subtitleCallback.invoke(sub)
                }
            } catch (e: Exception) {
                subtitleCallback.invoke(sub)
            }
        }

        val stremioSubtitleCallback: (SubtitleFile) -> Unit = { sub ->
            if (sub.url.isNotBlank() && seenStremioSubtitles.add(sub.url)) {
                integrationSubtitleCallback.invoke(sub)
            }
        }

        runAllAsync(
            {
                if (cleanBaseUrl.isNotBlank() && encodedPrimaryId.isNotBlank()) {
                    runSuspendCatching {
                        val streamRequestUrl = "$cleanBaseUrl/stream/$targetType/${encodedPrimaryId}.json$querySuffix"
                        println("[STREMIOSUB] Requesting streams from current addon")
                        val text = customSession.get(streamRequestUrl, timeout = 25L).text
                        val res = if (text.isNotBlank()) parseJson<StreamsResponse>(text) else null
                        res?.streams?.forEach { stream ->
                            stream.runCallback(
                                this@StremioC,
                                stremioSubtitleCallback,
                                subtitleCallback,
                                wrappedCallback
                            )
                        }
                    }.onFailure { it.printStackTrace() }
                }
            },
            {
                if (isSyncEnabled && syncUrl.isNotBlank()) {
                    runSuspendCatching {
                        val subId = buildStremioId(targetType, globalId ?: primaryId, globalSeason, globalEpisode, isSubtitle = true) ?: globalId ?: primaryId
                        val encodedSubId = subId.urlEncode()
                        
                        val cleanBase = syncUrl.cleanBaseUrl()
                        val suffix = syncUrl.substringAfter("?", "")
                            .takeIf { it.isNotEmpty() }
                            ?.let { "?$it" }
                            ?: ""
                        val subtitleRequestUrl = "$cleanBase/subtitles/$targetType/${encodedSubId}.json$suffix"
                        
                        println("[STREMIOSUB] Requesting subtitles from sync addon")
                        val text = customSession.get(subtitleRequestUrl, timeout = 15L).text
                        val res = if (text.isNotBlank()) parseJson<SubtitlesResponse>(text) else null
                        
                        res?.subtitles?.forEach { sub ->
                            val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                            stremioSubtitleCallback.invoke(newSubtitleFile(lang, sub.url ?: return@forEach))
                        }
                    }.onFailure { it.printStackTrace() }
                }
            },
            { 
                println("[STREMIOSUB] Initiating invokeCineStream")
                invokeCineStream(loadData, targetType, globalId, globalSeason, globalEpisode, isCasting, integrationSubtitleCallback, wrappedCallback) 
            },
            { 
                println("[STREMIOSUB] Initiating invokeStreamPlay")
                invokeStreamPlay(loadData, targetType, globalId, globalSeason, globalEpisode, isCasting, integrationSubtitleCallback, wrappedCallback) 
            },
            {
                if (isIntegrationEnabled("vixsrc_integration_enabled")) {
                    VixSrcExtractor.invokeVixSrc(loadData, targetType, globalSeason, globalEpisode, integrationSubtitleCallback, wrappedCallback)
                }
            }
        )
        return true
    }
        
    private data class IntegrationData(
        val imdbId: String?,
        val tmdbId: Int?,
        val kitsuId: String?,
        val isAnime: Boolean,
        val isMovie: Boolean,
        val title: String,
        val season: Int?,
        val episode: Int?,
        val imdbSeason: Int?,
        val imdbEpisode: Int?,
        val year: Int?,
        val airedYear: Int?
    )

    private fun integrationData(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?
    ): IntegrationData {
        val imdbId = listOf(loadData.imdbId, globalId, loadData.id)
            .firstNotNullOfOrNull { candidate ->
                candidate?.substringBefore(':')?.takeIf { IMDB_ID_REGEX.matches(it) }
            }
        val tmdbId = loadData.tmdbId?.takeIf { it > 0 }
            ?: loadData.id?.takeIf { it.startsWith("tmdb:") }
                ?.removePrefix("tmdb:")?.substringBefore(':')?.toIntOrNull()?.takeIf { it > 0 }
        val kitsuId = (loadData.kitsuId
            ?: loadData.id?.takeIf { it.startsWith("kitsu:") }
                ?.removePrefix("kitsu:"))
            ?.removePrefix("kitsu:")?.substringBefore(':')
            ?.takeIf { it.isNotBlank() && it.all { digit -> digit.isDigit() } }
        val isAnime = loadData.isAnime ||
            loadData.id?.startsWith("kitsu:") == true ||
            loadData.id?.startsWith("mal:") == true ||
            kitsuId != null || loadData.anilistId != null || loadData.malId != null
        val isMovie = targetType == "movie" ||
            (loadData.season == null && loadData.episode == null)
        return IntegrationData(
            imdbId = imdbId,
            tmdbId = tmdbId,
            kitsuId = kitsuId,
            isAnime = isAnime,
            isMovie = isMovie,
            title = loadData.englishTitle?.takeIf { it.isNotBlank() }
                ?: loadData.title?.takeIf { it.isNotBlank() }
                ?: loadData.originalTitle?.takeIf { it.isNotBlank() }
                ?: loadData.id ?: "Unknown",
            season = if (isMovie) null else loadData.season?.takeIf { it > 0 } ?: globalSeason,
            episode = if (isMovie) null else loadData.episode?.takeIf { it > 0 } ?: globalEpisode,
            imdbSeason = if (isMovie) null else loadData.imdbSeason?.takeIf { it > 0 } ?: globalSeason,
            imdbEpisode = if (isMovie) null else loadData.imdbEpisode?.takeIf { it > 0 } ?: globalEpisode,
            year = loadData.year,
            airedYear = loadData.airedYear
        )
    }

    private fun cinePayload(
        loadData: LoadData,
        targetType: String,
        media: IntegrationData,
        animeRoute: Boolean,
        japaneseTitle: Boolean = false,
        standardImdbRoute: Boolean = false
    ): Map<String, Any?> {
        val title = if (japaneseTitle) loadData.originalTitle?.takeIf { it.isNotBlank() }
            ?: media.title else media.title
        val splitIds = media.isAnime && !animeRoute &&
            media.imdbId != null && media.tmdbId != null &&
            (media.imdbSeason != media.season || media.imdbEpisode != media.episode)
        val useImdbEpisode = media.isAnime && !animeRoute && media.imdbId != null &&
            (!splitIds || standardImdbRoute)
        val requestImdbId = if (splitIds && !standardImdbRoute) null else media.imdbId
        val requestTmdbId = if (splitIds && standardImdbRoute) null else media.tmdbId
        val originalYear = if (media.isAnime && (media.season ?: 0) > 1)
            media.airedYear ?: media.year else media.year
        return mapOf(
            "title" to title,
            "id" to (requestImdbId ?: ""),
            "tmdbId" to requestTmdbId,
            "tvtype" to targetType,
            "year" to originalYear?.toString(),
            "season" to (if (useImdbEpisode) media.imdbSeason else media.season),
            "episode" to (if (useImdbEpisode) media.imdbEpisode else media.episode),
            "firstAired" to loadData.firstAired,
            "isAnime" to media.isAnime,
            "isBollywood" to loadData.isBollywood,
            "isAsian" to loadData.isAsian,
            "isCartoon" to loadData.isCartoon,
            "imdb_id" to requestImdbId,
            "imdbSeason" to media.imdbSeason,
            "imdbEpisode" to media.imdbEpisode,
            "isKitsu" to animeRoute,
            "anilistId" to loadData.anilistId,
            "malId" to loadData.malId,
            "kitsuId" to media.kitsuId
        )
    }

    private fun streamPlayPayload(
        loadData: LoadData,
        targetType: String,
        media: IntegrationData,
        generalAnimeRoute: Boolean = false
    ): Map<String, Any?> {
        // The unmodified StreamPlay accepts one season/episode pair. Its
        // anime sources need the original pair. For its general-only route,
        // TMDB takes the original pair and IMDb-only content uses the mapping.
        val mapped = generalAnimeRoute && media.tmdbId == null
        val season = if (mapped) media.imdbSeason else media.season
        val episode = if (mapped) media.imdbEpisode else media.episode
        val differentMapping = media.isAnime &&
            (media.imdbSeason != media.season || media.imdbEpisode != media.episode)
        val requestImdbId = if (differentMapping && !mapped) null else media.imdbId
        val year = if (media.isAnime && (media.season ?: 0) > 1)
            media.airedYear ?: media.year else media.year
        return mapOf(
            "id" to media.tmdbId,
            "imdbId" to requestImdbId,
            "type" to targetType,
            "season" to season,
            "episode" to episode,
            "aniId" to loadData.anilistId?.toString(),
            "title" to media.title,
            "year" to year,
            "orgTitle" to loadData.originalTitle,
            "isAnime" to (media.isAnime && !generalAnimeRoute),
            "airedYear" to media.airedYear,
            "lastSeason" to (media.season?.takeIf { it > 1 } ?: 1),
            "jpTitle" to loadData.originalTitle,
            "date" to loadData.firstAired,
            "airedDate" to loadData.firstAired,
            "isAsian" to loadData.isAsian,
            "isBollywood" to loadData.isBollywood,
            "isCartoon" to loadData.isCartoon,
            "isMovie" to media.isMovie
        )
    }
    
    
    private suspend fun invokeCineStream(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isIntegrationEnabled("cinestream_integration_enabled")) return
        if (targetType != "movie" && targetType != "series") return

        try {
            val cineStreamProvider = getCineStreamProvider() ?: return
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode)
            val linksFound = java.util.concurrent.atomic.AtomicInteger(0)
            val trackedCallback: (ExtractorLink) -> Unit = { link ->
                linksFound.incrementAndGet()
                callback(link)
            }
            suspend fun callCine(
                animeRoute: Boolean,
                japaneseTitle: Boolean = false,
                standardImdbRoute: Boolean = false
            ) {
                try {
                    cineStreamProvider.loadLinks(
                        cinePayload(
                            loadData, targetType, media, animeRoute,
                            japaneseTitle, standardImdbRoute
                        ).toJson(),
                        isCasting,
                        subtitleCallback,
                        trackedCallback
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (media.isAnime) {
                // The original CineStream dispatches disjoint anime and
                // standard implementations. Both entry points are required
                // to reach standard-only providers for anime.
                withTimeoutOrNull(55000L) {
                    supervisorScope {
                        val anime = async(Dispatchers.IO) { callCine(animeRoute = true) }
                        val standard = async(Dispatchers.IO) { callCine(animeRoute = false) }
                        val mappedImdb = if (
                            media.imdbId != null && media.tmdbId != null &&
                            (media.imdbSeason != media.season || media.imdbEpisode != media.episode)
                        ) async(Dispatchers.IO) {
                            callCine(animeRoute = false, standardImdbRoute = true)
                        } else null
                        listOfNotNull(anime, standard, mappedImdb).awaitAll()
                    }
                }
                if (linksFound.get() == 0 &&
                    !loadData.originalTitle.isNullOrBlank() &&
                    !loadData.originalTitle.equals(media.title, ignoreCase = true)
                ) {
                    withTimeoutOrNull(15000L) {
                        callCine(animeRoute = true, japaneseTitle = true)
                    }
                }
            } else {
                withTimeoutOrNull(55000L) { callCine(animeRoute = false) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun invokeStreamPlay(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (!isIntegrationEnabled("streamplay_integration_enabled")) return
        if (targetType != "movie" && targetType != "series") return

        try {
            val media = integrationData(loadData, targetType, globalId, globalSeason, globalEpisode)
            val streamPlayProvider = getStreamPlayProvider()

            if (!media.isAnime) {
                if (streamPlayProvider != null) {
                    withTimeoutOrNull(55000L) {
                        streamPlayProvider.loadLinks(
                            streamPlayPayload(loadData, targetType, media).toJson(),
                            isCasting, subtitleCallback, callback
                        )
                    }
                }
                return
            }

            supervisorScope {
                // This is a separate original StreamPlay API with its own anime
                // invokers. Call it even if the main API found a link.
                val dedicatedAnime = getStreamPlayAnimeProvider()?.let { animeProvider ->
                    async(Dispatchers.IO) {
                        try {
                            val animePayload = mapOf(
                                "imdbId" to media.imdbId,
                                "tmdbId" to media.tmdbId,
                                "type" to targetType,
                                "season" to media.season,
                                "episode" to media.episode,
                                "aniId" to loadData.anilistId,
                                "malId" to loadData.malId,
                                "title" to media.title,
                                "year" to (media.airedYear ?: media.year),
                                "orgTitle" to loadData.originalTitle,
                                "isAnime" to true,
                                "airedYear" to media.airedYear,
                                "lastSeason" to (media.season?.takeIf { it > 1 } ?: 1),
                                "jpTitle" to loadData.originalTitle,
                                "date" to loadData.firstAired,
                                "airedDate" to loadData.firstAired,
                                "isAsian" to loadData.isAsian,
                                "isBollywood" to loadData.isBollywood,
                                "isCartoon" to loadData.isCartoon
                            )
                            withTimeoutOrNull(35000L) {
                                animeProvider.loadLinks(
                                    animePayload.toJson(), isCasting, subtitleCallback, callback
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }
                if (streamPlayProvider != null) {
                    val anime = async(Dispatchers.IO) {
                        try {
                            withTimeoutOrNull(35000L) {
                                streamPlayProvider.loadLinks(
                                    streamPlayPayload(loadData, targetType, media).toJson(),
                                    isCasting, subtitleCallback, callback
                                )
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    val general = if (media.imdbId != null || media.tmdbId != null) {
                        async(Dispatchers.IO) {
                            try {
                                withTimeoutOrNull(35000L) {
                                    streamPlayProvider.loadLinks(
                                        streamPlayPayload(
                                            loadData, targetType, media, generalAnimeRoute = true
                                        ).toJson(),
                                        isCasting, subtitleCallback, callback
                                    )
                                }
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                    } else null
                    anime.await()
                    general?.await()
                }
                dedicatedAnime?.await()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class LoadData(
        @JsonProperty("type") val type: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("season") val season: Int? = null, 
        @JsonProperty("episode") val episode: Int? = null, 
        @JsonProperty("imdbId") val imdbId: String? = null, 
        @JsonProperty("year") val year: Int? = null, 
        @JsonProperty("title") val title: String? = null, 
        @JsonProperty("tmdbId") val tmdbId: Int? = null, 
        @JsonProperty("isAnime") val isAnime: Boolean = false,
        @JsonProperty("isBollywood") val isBollywood: Boolean = false,
        @JsonProperty("isAsian") val isAsian: Boolean = false,
        @JsonProperty("isCartoon") val isCartoon: Boolean = false,
        @JsonProperty("firstAired") val firstAired: String? = null,
        @JsonProperty("airedYear") val airedYear: Int? = null, 
        @JsonProperty("originalTitle") val originalTitle: String? = null, 
        @JsonProperty("englishTitle") val englishTitle: String? = null, 
        @JsonProperty("imdbSeason") val imdbSeason: Int? = null,
        @JsonProperty("imdbEpisode") val imdbEpisode: Int? = null,
        @JsonProperty("anilistId") val anilistId: Int? = null,
        @JsonProperty("malId") val malId: Int? = null,
        @JsonProperty("kitsuId") val kitsuId: String? = null,
        @JsonProperty("addonUrl") val addonUrl: String? = null
    )
    
    data class Manifest(val catalogs: List<Catalog>)
    
    data class Extra(
        @JsonProperty("name") val name: String?, 
        @JsonProperty("isRequired") val isRequired: Boolean? = false,
        @JsonProperty("options") val options: List<String>? = null
    )

    data class Catalog(
        @JsonProperty("name") var name: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("type") val type: String? = null, 
        @JsonProperty("types") val parsedTypes: List<String>? = null, 
        @JsonProperty("extra") val extra: List<Extra>? = null, 
        @JsonProperty("extraSupported") val extraSupported: List<String>? = null,
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("extraRequired") val extraRequired: List<String>? = null
    ) {
        fun requiresExtra(name: String): Boolean =
            extra?.any { it.name == name && it.isRequired == true } == true ||
                extraRequired?.contains(name) == true

        fun supportsHome(): Boolean {
            val required = extra.orEmpty().filter { it.isRequired == true }.mapNotNull { it.name } +
                extraRequired.orEmpty()
            return !id.isNullOrBlank() && required.all { it == "genre" || it == "skip" } &&
                (!requiresExtra("genre") || getGenresList().isNotEmpty())
        }

        val activeTypes: List<String> get() = if (!parsedTypes.isNullOrEmpty()) parsedTypes else listOfNotNull(type)

        fun getGenresList(): List<String> {
            val list = mutableListOf<String>()
            genres?.let { list.addAll(it) }
            if (list.isEmpty()) {
                extra?.find { it.name == "genre" }?.options?.let { list.addAll(it) }
            }
            return list
        }

        fun isSearchRequired(): Boolean = extra?.any { it.name == "search" && it.isRequired == true } == true
        fun supportsSearch(): Boolean = extra?.any { it.name == "search" } == true || extraSupported?.contains("search") == true

        private suspend fun fetchMetas(provider: StremioC, path: String): List<CatalogEntry> {
            return try {
                val text = StremioC.customSession.get(provider.buildUrl(path), timeout = 120L).text
                if (text.isNotBlank()) parseJson<CatalogResponse>(text).metas ?: emptyList() else emptyList()
            } catch (e: Throwable) { e.printStackTrace(); emptyList() }
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetasWithType = coroutineScope {
                activeTypes.map { type ->
                    async(Dispatchers.IO) {
                        val path = "/catalog/${type}/${id}/search=${query.urlEncode()}.json"
                        fetchMetas(provider, path).map { it to type }
                    }
                }.awaitAll().flatten()
            }
            return allMetasWithType.distinctBy { it.first.id }.map { (meta, type) ->
                meta.toSearchResponse(provider, catalogName = name ?: id ?: "", typeName = type)
            }
        }

        suspend fun toHomePageList(provider: StremioC, skip: Int): Pair<HomePageList, Int> {
            val allMetas = coroutineScope {
                activeTypes.map { type ->
                    async(Dispatchers.IO) {
                        val path = if (skip > 0) "/catalog/$type/$id/skip=$skip.json" else "/catalog/$type/$id.json"
                        fetchMetas(provider, path)
                    }
                }.awaitAll().flatten()
            }
            val distinctEntries = allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
            val displayType = type?.replaceFirstChar { it.uppercase() } ?: ""
            return Pair(HomePageList("${name ?: id} - $displayType", distinctEntries), allMetas.size)
        }
    }

    private data class HomeCatalogResponse(
        @JsonProperty("metas") val metas: List<HomeCatalogEntry>? = null
    )

    private data class HomeCatalogEntry(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster") val poster: String? = null
    )

    private data class CatalogResponse(
        @JsonProperty("metas") val metas: List<CatalogEntry>?, 
        @JsonProperty("meta") val meta: CatalogEntry?
    )

    private data class Trailer(
        @JsonProperty("source") val source: String?, 
        @JsonProperty("type") val type: String?
    )
    
    private data class TrailerStream(
        @JsonProperty("ytId") val ytId: String?, 
        @JsonProperty("title") val title: String? = null
    )
    
    private data class Link(
        @JsonProperty("name") val name: String? = null, 
        @JsonProperty("category") val category: String? = null, 
        @JsonProperty("url") val url: String? = null
    )

    private data class FetchedTmdbData(
        val fetchedTitle: String?, val fetchedOverview: String?, val fetchedLogo: String?,
        val fetchedTrailers: List<String>, val fetchedRecommendations: List<SearchResponse>?,
        val fetchedRuntime: Int?, val fetchedAgeRating: String?, val fetchedActors: List<ActorData>?,
        val fetchedYear: Int?, val fetchedStatus: ShowStatus?, val episodeTmdbMeta: Map<String, TmdbEpisode>,
        val fetchedOriginCountry: List<String>?,
        val fetchedOriginalTitle: String?,
        val fetchedEnglishTitle: String?,
        val fetchedVoteAverage: Double? = null
    )

    private data class CatalogEntry(
        @JsonProperty("name") val name: String? = null, 
        @JsonProperty("id") val id: String? = null, 
        @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("_tmdbId") val tmdbIdExt: String? = null, 
        @JsonProperty("_malId") val malIdExt: String? = null,
        @JsonProperty("_kitsuId") val kitsuIdExt: String? = null,
        @JsonProperty("poster") val poster: String? = null, 
        @JsonProperty("background") val background: String? = null, 
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String? = null, 
        @JsonProperty("imdbRating") val imdbRating: String? = null, 
        @JsonProperty("type") val type: String? = null,
        @JsonProperty("videos") val videos: List<Video>? = null, 
        @JsonProperty("genre") val genre: List<String>? = null, 
        @JsonProperty("genres") val genres: List<String>? = null,
        @JsonProperty("cast") val cast: List<String>? = null, 
        @JsonProperty("trailers") val trailersSources: List<Trailer>? = null,
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream>? = null, 
        @JsonProperty("links") val links: List<Link>? = null
    ) {

        fun toSearchResponse(
            provider: StremioC, 
            catalogName: String? = null, 
            typeName: String? = null
        ): SearchResponse {
            val targetType = this.type ?: typeName ?: "movie"
            val metaUrl = provider.getUnifiedMetaUrl(targetType, this.id ?: "")

            val baseName = if (!name.isNullOrBlank()) {
                name
            } else {
                id ?: ""
            }

            val finalName = if (!catalogName.isNullOrBlank() && !typeName.isNullOrBlank()) {
                val capType = typeName.replaceFirstChar { it.uppercase() }
                "[$catalogName-$capType] $baseName"
            } else {
                if (!name.isNullOrBlank()) {
                    name
                } else {
                    val capitalizedType = targetType.replaceFirstChar { it.uppercase() }
                    "[$capitalizedType] $id"
                }
            }

            return provider.newMovieSearchResponse(finalName, metaUrl, TvType.Others) { 
                posterUrl = poster 
            }
        }
        
        private fun isSingleMovie(finalType: String?, finalVideos: List<Video>?): Boolean {
            return (finalType == "movie" && finalVideos?.size == 1 && finalVideos[0].seasonNumber == 1 && (finalVideos[0].episode == 1 || finalVideos[0].number == 1)) ||
                   (finalVideos?.size == 1 && (finalVideos[0].seasonNumber ?: 0) == 0 && (finalVideos[0].episode ?: finalVideos[0].number ?: 0) == 0)
        }

        private suspend fun fetchTmdbDetails(
            provider: StremioC, tmdbMediaType: String, tmdbIdStr: String, isMovie: Boolean, finalImdbId: String?,
            stremioType: String?, targetVideos: List<Video>?, preFetchedDetail: TmdbDetailResponse? = null
        ): FetchedTmdbData {
            var fetchedRecommendations: List<SearchResponse>? = null
            
            val detailAppend = if (isMovie) "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
            val detailUrl = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr", "append_to_response" to detailAppend)
            val cacheKey = detailUrl
            val cached = tmdbDetailsCache[cacheKey]
            val detailRes = preFetchedDetail ?: if (cached != null && System.currentTimeMillis() - cached.second < METADATA_CACHE_TTL) {
                cached.first
            } else {
                val text = StremioC.customSession.get(detailUrl).text
                val fetched = if (text.isNotBlank()) parseJson<TmdbDetailResponse>(text) else null
                if (fetched != null) {
                    tmdbDetailsCache[cacheKey] = Pair(fetched, System.currentTimeMillis())
                }
                fetched
            }
            
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null, null)
            }

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
            val langCode = prefs?.getString("tmdb_language_code", "ko-KR") ?: "ko-KR"
            val customPosterEnabled = prefs?.getBoolean("custom_poster_enabled", false) ?: false
            val customPosterBase = prefs?.getString("custom_poster_url", "") ?: ""
            
            coroutineScope {
                val shouldFocusEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType != "movie" && stremioType != "series" && !isMovie)
                
                val episodeDeferred = if (shouldFocusEpisodes) {
                    var requiredSeasons = targetVideos?.mapNotNull { it.seasonNumber }?.filter { it > 0 }?.distinct() ?: emptyList()
                    if (requiredSeasons.isEmpty()) {
                        val totalSeasons = detailRes.number_of_seasons ?: 1
                        requiredSeasons = if (totalSeasons > 0) (1..totalSeasons).toList() else listOf(1)
                    }
                    
                    requiredSeasons.map { seasonNum ->
                        async(Dispatchers.IO) {
                            StremioC.networkSemaphore.withPermit {
                                withTimeoutOrNull(10000L) {
                                    runSuspendCatching {
                                        val seasonUrl = provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")
                                        val seasonCacheKey = seasonUrl
                                        val cachedSeason = tmdbSeasonCache[seasonCacheKey]
                                        val seasonRes = if (cachedSeason != null && System.currentTimeMillis() - cachedSeason.second < METADATA_CACHE_TTL) {
                                            cachedSeason.first
                                        } else {
                                            val fetchedSeasonText = StremioC.customSession.get(seasonUrl).text
                                            val fetchedSeason = if (fetchedSeasonText.isNotBlank()) parseJson<TmdbSeasonDetail>(fetchedSeasonText) else null
                                            if (fetchedSeason != null) {
                                                tmdbSeasonCache[seasonCacheKey] = Pair(fetchedSeason, System.currentTimeMillis())
                                            }
                                            fetchedSeason
                                        }
                                        seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                    }.onFailure { it.printStackTrace() }
                                }
                            }
                        }
                    }
                } else null

                val collectionDeferred = async(Dispatchers.IO) {
                    if (isMovie && detailRes.belongs_to_collection?.id != null) {
                        runSuspendCatching {
                            val colUrl = provider.buildTmdbUrl("/collection/${detailRes.belongs_to_collection.id}")
                            val colText = StremioC.customSession.get(colUrl).text
                            if (colText.isNotBlank()) parseJson<TmdbCollectionDetail>(colText).parts ?: emptyList() else emptyList()
                        }.getOrDefault(emptyList())
                    } else emptyList()
                }

                val collectionParts = collectionDeferred.await()
                episodeDeferred?.awaitAll()

                val tmdbRecs = detailRes.recommendations?.results ?: emptyList()
                val finalCombinedMedia = (collectionParts + tmdbRecs)
                    .distinctBy { it.id }
                    .filter { it.id != null && it.id != tmdbIdStr.toIntOrNull() }

                fetchedRecommendations = finalCombinedMedia.map { rec ->
                    val recTitle = rec.title ?: rec.name ?: ""
                    val isRecMovie = rec.mediaType == "movie" || rec.title != null
                    val recTypeStr = if (isRecMovie) "movie" else "series"
                    val recType = if (isRecMovie) TvType.Movie else TvType.TvSeries
                    
                    val recPoster = if (customPosterEnabled && customPosterBase.isNotBlank()) {
                        val rawUrl = customPosterBase.trim()
                        val mediaTypeParam = if (isRecMovie) "movie" else "tv"
                        rawUrl.replace("\$posterType", mediaTypeParam)
                              .replace("posterType", mediaTypeParam)
                              .replace("{type}", mediaTypeParam)
                              .replace("\$recTmdbId", rec.id.toString())
                              .replace("recTmdbId", rec.id.toString())
                              .replace("{tmdb_id}", rec.id.toString())
                    } else {
                        // rec.posterPath -> rec.poster_path로 교정
                        if (!rec.poster_path.isNullOrBlank()) "https://image.tmdb.org/t/p/w500${rec.poster_path}" else TRANSPARENT_PIXEL
                    }
                    
                    val thinData = LoadData(id = "tmdb:${rec.id}", type = recTypeStr, addonUrl = provider.mainUrl)
                    provider.newMovieSearchResponse(recTitle, thinData.toJson(), recType) {
                        this.posterUrl = recPoster
                    }
                }.take(50)
            }
            
            val countryCode = langCode.substringAfter("-", "KR").uppercase()

            val fetchedAgeRating = if (isMovie) {
                detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
                    ?: detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == "US" }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
            } else {
                detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == countryCode }?.rating
                    ?: detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == "US" }?.rating
            }

            val crewList = detailRes.credits?.crew?.filter { it.job == "Director" || it.job == "Writer" }?.groupBy { it.name ?: it.originalName }?.mapNotNull { (name, roles) ->
                if (name == null) return@mapNotNull null
                val sortedJobs = roles.mapNotNull { it.job }.distinct().sortedBy { if (it == "Director") 1 else 2 }.joinToString(", ")
                val img = roles.firstNotNullOfOrNull { it.profilePath }?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                ActorData(Actor(name, img), roleString = sortedJobs)
            }?.sortedBy { if (it.roleString?.contains("Director, Writer") == true) 1 else if (it.roleString?.contains("Director") == true) 2 else 3 } ?: emptyList()

            val castList = detailRes.credits?.cast?.mapNotNull { cast ->
                val actorName = cast.name ?: cast.originalName ?: return@mapNotNull null
                val profileImg = cast.profilePath?.let { provider.getActorUrl(it) } ?: TRANSPARENT_PIXEL
                ActorData(Actor(actorName, profileImg), roleString = cast.character)
            } ?: emptyList()

            val fetchedTrailers = (detailRes.videos?.results ?: emptyList()).filter { it.type == "Trailer" || it.type == "Teaser" }.sortedWith(compareBy({ if (it.type == "Trailer") 0 else 1 }, { it.publishedAt ?: "9999-12-31" })).mapNotNull { it.key }.map { "https://m.youtube.com/watch?v=$it" }

            val translationData = detailRes.translations?.translations?.firstOrNull { 
                it.iso_3166_1 == "US" || it.iso_639_1 == "en" 
            }?.data
            val enTranslation = (translationData?.title ?: translationData?.name)?.takeIf { it.isNotBlank() }

            val extractedEnglishTitle = if (detailRes.original_language == "en") {
                detailRes.original_title ?: detailRes.original_name
            } else {
                enTranslation ?: detailRes.original_title ?: detailRes.original_name
            }

            return FetchedTmdbData(
                if (isMovie) detailRes.title ?: detailRes.original_title else detailRes.name ?: detailRes.original_name,
                detailRes.overview, detailRes.images?.logos?.firstOrNull()?.file_path?.let { "https://image.tmdb.org/t/p/w500$it" },
                fetchedTrailers, fetchedRecommendations, if (isMovie) detailRes.runtime else null, fetchedAgeRating, crewList + castList,
                (detailRes.releaseDate ?: detailRes.firstAirDate)?.split("-")?.first()?.toIntOrNull(),
                if (detailRes.status == "Returning Series") ShowStatus.Ongoing else ShowStatus.Completed, episodeTmdbMeta, detailRes.origin_country,
                detailRes.original_title ?: detailRes.original_name,
                extractedEnglishTitle,
                detailRes.vote_average
            )
        }

        private fun processEpisodes(
            provider: StremioC, 
            finalImdbId: String?, 
            episodeTmdbMeta: Map<String, TmdbEpisode>, 
            targetVideos: List<Video>?, 
            type: String?, 
            showTitle: String?, 
            tmdbId: Int?, 
            isAnime: Boolean, 
            isBollywood: Boolean, 
            isAsian: Boolean, 
            isCartoon: Boolean, 
            originalTitle: String?, 
            englishTitle: String?, 
            aniListId: Int?, 
            malId: Int?,
            kitsuId: String?,
            year: Int? = null // ★ year 매개변수 추가
        ): List<Episode> {
            val videos = targetVideos ?: emptyList()
            
            val maxRegularSeason = videos.mapNotNull { it.seasonNumber }.filter { it > 0 }.maxOrNull() ?: 0
            val targetSeasonForZero = maxRegularSeason + 1
            
            val videosByDisplaySeason = videos.groupBy { 
                val s = it.seasonNumber ?: 0
                if (s == 0) targetSeasonForZero else s
            }

            val processedEpisodes = mutableListOf<Episode>()

            for ((displaySeason, seasonVideos) in videosByDisplaySeason) {
                val normalEpisodes = seasonVideos.filter { (it.episode ?: it.number ?: 0) > 0 }
                    .sortedBy { it.episode ?: it.number ?: 0 }
                
                val zeroEpisodes = seasonVideos.filter { (it.episode ?: it.number ?: 0) <= 0 }
                    .sortedBy { it.title ?: it.name ?: "" }
                
                val uniqueEpisodeChecker = mutableSetOf<Int>()
                val finalSeasonVideos = mutableListOf<Pair<Video, Int>>()

                var maxEpisodeNum = 0
                for (video in normalEpisodes) {
                    val epNum = video.episode ?: video.number ?: 1
                    maxEpisodeNum = maxOf(maxEpisodeNum, epNum)
                    uniqueEpisodeChecker.add(epNum)
                    finalSeasonVideos.add(video to epNum)
                }

                var nextZeroEpNum = maxEpisodeNum + 1
                for (video in zeroEpisodes) {
                    while (uniqueEpisodeChecker.contains(nextZeroEpNum)) {
                        nextZeroEpNum++
                    }
                    uniqueEpisodeChecker.add(nextZeroEpNum)
                    finalSeasonVideos.add(video to nextZeroEpNum)
                    maxEpisodeNum = maxOf(maxEpisodeNum, nextZeroEpNum)
                }

                for ((video, displayEpisode) in finalSeasonVideos) {
                    val imdbSeason = video.imdbSeason ?: video.seasonNumber ?: 0
                    val imdbEpisode = video.imdbEpisode ?: video.episode ?: video.number ?: 0
                    
                    val rawEpImdbId = video.imdb_id ?: finalImdbId
                    val epImdbId = rawEpImdbId?.let { idStr ->
                        val parts = idStr.split(":")
                        if (idStr.startsWith("kitsu:") || idStr.startsWith("mal:") || idStr.startsWith("tmdb:")) {
                            if (parts.size >= 2) "${parts[0]}:${parts[1]}" else idStr
                        } else {
                            parts[0]
                        }
                    }
                    
                    val ep = video.toEpisode(
                        provider, type, epImdbId, episodeTmdbMeta, 
                        displaySeason, displayEpisode, showTitle, tmdbId, 
                        isAnime, isBollywood, isAsian, isCartoon, 
                        imdbSeason, imdbEpisode, originalTitle, englishTitle, 
                        aniListId, malId, kitsuId,
                        provider.mainUrl,
                        year // ★ toEpisode 메서드로 year 매개변수 위임 전달
                    )
                    processedEpisodes.add(ep)
                }
            }

            return processedEpisodes.sortedWith(compareBy({ it.season }, { it.episode }))
        }
        
        suspend fun toLoadResponse(
            provider: StremioC, 
            imdbId: String?, 
            addonDeferred: Deferred<CatalogEntry?>?, 
            preFetchedDetail: TmdbDetailResponse? = null, 
            fetchedAniListId: Int? = null, 
            fetchedMalId: Int? = null, 
            resolvedTmdbId: String? = null,
            kitsuVideos: List<Video>? = null,
            resolvedIdHolder: Array<String?>? = null
        ): LoadResponse = coroutineScope {
            val detailedEntry = addonDeferred?.await() ?: this@CatalogEntry

            var finalImdbId = if (this@CatalogEntry.id?.startsWith("tt") == true) this@CatalogEntry.id 
                              else imdbId?.takeIf { it.startsWith("tt") || it.startsWith("kitsu:") || it.startsWith("mal:") }
            
            if (finalImdbId == null && detailedEntry.links?.isNotEmpty() == true) {
                finalImdbId = detailedEntry.links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }
            if (finalImdbId == null) {
                finalImdbId = provider.extractImdbId(detailedEntry.logo) ?: provider.extractImdbId(detailedEntry.poster) ?: provider.extractImdbId(detailedEntry.background)
            }

            var tmdbIdStr: String? = resolvedTmdbId ?: if (this@CatalogEntry.id?.startsWith("tmdb:") == true) this@CatalogEntry.id.removePrefix("tmdb:") else this@CatalogEntry.moviedb_id?.toString() ?: this@CatalogEntry.tmdbIdExt ?: detailedEntry.tmdbIdExt

            val findApiDeferred: Deferred<Pair<String, String>?>? = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    val cacheKey = finalImdbId
                    val cachedFind = tmdbFindCache[cacheKey]
                    if (cachedFind != null && System.currentTimeMillis() - cachedFind.second < METADATA_CACHE_TTL) {
                        cachedFind.first
                    } else {
                        val fetchedFind = runSuspendCatching {
                            val fetchedFindText = StremioC.customSession.get(provider.buildTmdbUrl("/find/$finalImdbId", "external_source" to "imdb_id")).text
                            val findRes = if (fetchedFindText.isNotBlank()) parseJson<TmdbFindResponse>(fetchedFindText) else null
                            val movieMatch = findRes?.movie_results?.firstOrNull()
                            if (movieMatch?.id != null) Pair(movieMatch.id.toString(), "movie") else findRes?.tv_results?.firstOrNull()?.id?.let { Pair(it.toString(), "tv") }
                        }.getOrNull()
                        if (fetchedFind != null) {
                            tmdbFindCache[cacheKey] = Pair(fetchedFind, System.currentTimeMillis())
                        }
                        fetchedFind
                    }
                }
            } else null

            var finalVideos = kitsuVideos ?: detailedEntry.videos ?: this@CatalogEntry.videos
            val finalTrailersSources = detailedEntry.trailersSources ?: this@CatalogEntry.trailersSources
            val finalTrailerStreams = detailedEntry.trailerStreams ?: this@CatalogEntry.trailerStreams
            val originalName = detailedEntry.name ?: this@CatalogEntry.name
            val finalGenre = detailedEntry.genre ?: detailedEntry.genres ?: this@CatalogEntry.genre ?: this@CatalogEntry.genres
            val finalPoster = detailedEntry.poster ?: this@CatalogEntry.poster
            val finalBackground = detailedEntry.background ?: this@CatalogEntry.background
            val finalImdbRating = detailedEntry.imdbRating ?: this@CatalogEntry.imdbRating
            val finalType = detailedEntry.type ?: this@CatalogEntry.type

            var foundMediaType: String? = null
            if (findApiDeferred != null) {
                val findResult = findApiDeferred.await()
                if (findResult != null) { tmdbIdStr = findResult.first; foundMediaType = findResult.second }
            }

            var tmdbData: FetchedTmdbData? = null
            if (tmdbIdStr != null) {
                try {
                    val refinedMediaType = foundMediaType ?: if (finalType == "series" || finalType == "tv") "tv" else if (isSingleMovie(finalType, finalVideos) || finalType == "movie" || finalVideos.isNullOrEmpty()) "movie" else "tv"
                    tmdbData = fetchTmdbDetails(provider, refinedMediaType, tmdbIdStr!!, refinedMediaType == "movie", finalImdbId, finalType, finalVideos, preFetchedDetail)
                } catch (e: Exception) { e.printStackTrace() }
            }

            var finalName = originalName ?: this@CatalogEntry.name ?: "Unknown Content"
            var finalDescription = detailedEntry.description ?: this@CatalogEntry.description
            
            if (tmdbData != null) {
                if (!tmdbData.fetchedTitle.isNullOrBlank()) finalName = tmdbData.fetchedTitle
                finalDescription = tmdbData.fetchedOverview?.takeIf { it.isNotBlank() } ?: finalDescription
            }
            
            if (finalType != "movie" && finalType != "series") {
                finalDescription = if (finalDescription.isNullOrBlank()) originalName else "$finalDescription<br>$originalName"
            }

            val movieId = this@CatalogEntry.kitsuIdExt?.let { "kitsu:$it" } ?: this@CatalogEntry.id ?: ""
            if (tmdbData != null && finalVideos.isNullOrEmpty() && tmdbData.episodeTmdbMeta.isNotEmpty()) {
                val generatedVideos = tmdbData.episodeTmdbMeta.mapNotNull { (key, tmdbEp) ->
                    val parts = key.split("_")
                    if (parts.size == 2) {
                        val s = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val e = parts[1].toIntOrNull() ?: return@mapNotNull null
                        Video(
                            id = movieId, 
                            title = tmdbEp.name,
                            seasonNumber = s,
                            episode = e,
                            overview = tmdbEp.overview,
                            released = tmdbEp.airDate
                        )
                    } else null
                }
                if (generatedVideos.isNotEmpty()) {
                    finalVideos = generatedVideos
                }
            }

            val fallbackTrailers = finalTrailerStreams?.mapNotNull { it.ytId }?.ifEmpty { finalTrailersSources?.mapNotNull { it.source } }?.distinct()?.map { if (it.startsWith("http")) it else "https://m.youtube.com/watch?v=$it" } ?: emptyList()
            val finalTrailers = tmdbData?.fetchedTrailers?.ifEmpty { fallbackTrailers } ?: fallbackTrailers

            val countries = tmdbData?.fetchedOriginCountry ?: emptyList()
            val isBollywood = countries.any { it.contains("IN", true) || it.contains("India", true) }
            val isAsian = countries.any { it.contains("KR", true) || it.contains("JP", true) || it.contains("CN", true) || it.contains("TH", true) || it.contains("TW", true) }
            val isCartoon = finalGenre?.any { it.contains("Animation", true) } == true
            val isAnimeMedia = fetchedMalId != null || finalImdbId?.startsWith("mal:") == true || finalImdbId?.startsWith("kitsu:") == true || finalGenre?.contains("Anime") == true || (countries.any { it.contains("JP", true) } && isCartoon)

            var finalMalId = fetchedMalId
            var finalAniId = fetchedAniListId

            // 중복 비동기 리소스 방지 조건식 정밀 교정: 두 ID중 어느 하나라도 유실된 상태에서만 연동 호출 트리거
            val aniZipDeferred: Deferred<Pair<Int?, Int?>?>? = if (isAnimeMedia && finalImdbId != null && (finalMalId == null || finalAniId == null)) {
                async(Dispatchers.IO) {
                    val cacheKey = finalImdbId
                    val cachedZip = aniZipCache[cacheKey]
                    if (cachedZip != null && System.currentTimeMillis() - cachedZip.second < METADATA_CACHE_TTL) {
                        cachedZip.first
                    } else {
                        val fetchedZip = runSuspendCatching {
                            val mappingRes = StremioC.customSession.get("https://api.ani.zip/mappings?imdb_id=$finalImdbId", timeout = 5L).text
                            val node = parseJson<JsonNode>(mappingRes)
                            val mappings = node.get("mappings")
                            if (mappings != null && !mappings.isNull) {
                                val mal = mappings.get("mal_id").intOrNull()?.takeIf { it > 0 }
                                val ani = mappings.get("anilist_id").intOrNull()?.takeIf { it > 0 }
                                Pair(mal, ani)
                            } else null
                        }.onFailure { it.printStackTrace() }.getOrNull()
                        if (fetchedZip != null) {
                            aniZipCache[cacheKey] = Pair(fetchedZip, System.currentTimeMillis())
                        }
                        fetchedZip
                    }
                }
            } else null

            if (isAnimeMedia && finalImdbId != null && (finalMalId == null || finalAniId == null)) {
                val aniZipResult = aniZipDeferred?.await()
                if (aniZipResult != null) {
                    finalMalId = finalMalId ?: aniZipResult.first
                    finalAniId = finalAniId ?: aniZipResult.second
                }
            }

            val cleanImdbId = finalImdbId?.let { idStr ->
                val parts = idStr.split(":")
                if (idStr.startsWith("kitsu:") || idStr.startsWith("mal:") || idStr.startsWith("tmdb:")) {
                    if (parts.size >= 2) "${parts[0]}:${parts[1]}" else idStr
                } else {
                    parts[0]
                }
            }
            val cleanTmdbId = tmdbIdStr?.substringBefore(":")

            resolvedIdHolder?.let {
                it[0] = cleanImdbId
                it[1] = cleanTmdbId
            }

            val finalRatingValue = finalImdbRating?.takeIf { it.isNotBlank() && it != "null" } 
                                   ?: tmdbData?.fetchedVoteAverage?.toString()

            val hasAnimeTrackerIds = finalMalId != null || finalAniId != null || this@CatalogEntry.kitsuIdExt != null
            val finalMovieType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.AnimeMovie else TvType.Movie
            val finalSeriesType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.Anime else TvType.TvSeries

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "movie", this@CatalogEntry.id ?: "")
                val resolvedMovieId = if (finalType == "other" && finalVideos?.size == 1) {
                    finalVideos[0].id ?: movieId
                } else {
                    movieId
                }

                return@coroutineScope provider.newMovieLoadResponse(
                    finalName, metaUrl, finalMovieType, 
                    LoadData(
                        type = finalType, 
                        id = resolvedMovieId, 
                        imdbId = cleanImdbId, 
                        year = tmdbData?.fetchedYear, 
                        title = finalName, 
                        tmdbId = cleanTmdbId?.toIntOrNull(), 
                        isAnime = isAnimeMedia,
                        isBollywood = isBollywood,
                        isAsian = isAsian,
                        isCartoon = isCartoon,
                        anilistId = finalAniId,
                        malId = finalMalId,
                        kitsuId = this@CatalogEntry.kitsuIdExt,
                        originalTitle = tmdbData?.fetchedOriginalTitle,
                        englishTitle = tmdbData?.fetchedEnglishTitle,
                        addonUrl = provider.mainUrl
                    )
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = tmdbData?.fetchedYear
                    this.plot = finalDescription
                    this.actors = tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations 
                    addTrailer(finalTrailers); this.duration = tmdbData?.fetchedRuntime; this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }

                    val validImdb = cleanImdbId?.takeIf { it.startsWith("tt") }
                    validImdb?.let { addImdbId(it) }
                    if (validImdb == null) {
                        cleanTmdbId?.takeIf { it.isNotBlank() }?.let { addTMDbId(it) }
                    }
                    
                    finalMalId?.let { addMalId(it) }
                    finalAniId?.let { addAniListId(it) }

                    this.apiName = provider.getTargetApiName()
                }

            } else {
                val finalEpisodes = processEpisodes(
                    provider, 
                    cleanImdbId, 
                    tmdbData?.episodeTmdbMeta ?: emptyMap(), 
                    finalVideos, 
                    finalType, 
                    finalName, 
                    cleanTmdbId?.toIntOrNull(), 
                    isAnimeMedia, 
                    isBollywood, 
                    isAsian, 
                    isCartoon, 
                    tmdbData?.fetchedOriginalTitle, 
                    tmdbData?.fetchedEnglishTitle, 
                    finalAniId, 
                    finalMalId, 
                    this@CatalogEntry.kitsuIdExt,
                    tmdbData?.fetchedYear // ★ 수집된 출시 연도(year) 인자 전달 추가
                )
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "series", this@CatalogEntry.id ?: "")
                return@coroutineScope provider.newTvSeriesLoadResponse(
                    finalName, metaUrl, finalSeriesType, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalRatingValue); year = tmdbData?.fetchedYear
                    this.showStatus = tmdbData?.fetchedStatus 
                    this.plot = finalDescription
                    this.actors = tmdbData?.fetchedActors
                    this.tags = finalGenre
                    this.recommendations = tmdbData?.fetchedRecommendations
                    addTrailer(finalTrailers.firstOrNull()); this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }
                    
                    val validImdb = cleanImdbId?.takeIf { it.startsWith("tt") }
                    validImdb?.let { addImdbId(it) }
                    if (validImdb == null) {
                        cleanTmdbId?.takeIf { it.isNotBlank() }?.let { addTMDbId(it) }
                    }
                    
                    finalMalId?.let { addMalId(it) }
                    finalAniId?.let { addAniListId(it) }

                    this.apiName = provider.getTargetApiName()
                }
            }           
        }
    }

    private data class Video(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("season") val seasonNumber: Int? = null,
        @JsonProperty("number") val number: Int? = null,
        @JsonProperty("episode") val episode: Int? = null,
        @JsonProperty("thumbnail") val thumbnail: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("description") val description: String? = null,
        @JsonProperty("firstAired") val firstAired: String? = null,
        @JsonProperty("released") val released: String? = null,
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null,
        @JsonProperty("imdbSeason") val imdbSeason: Int? = null,
        @JsonProperty("imdbEpisode") val imdbEpisode: Int? = null,
        @JsonProperty("imdb_id") val imdb_id: String? = null
    ) {
        fun toEpisode(
            provider: StremioC, 
            type: String?, 
            imdbId: String?, 
            tmdbMetaMap: Map<String, TmdbEpisode>, 
            displaySeason: Int, 
            displayEpisode: Int, 
            showTitle: String?, 
            tmdbId: Int?, 
            isAnime: Boolean, 
            isBollywood: Boolean, 
            isAsian: Boolean, 
            isCartoon: Boolean, 
            imdbSeason: Int?, 
            imdbEpisode: Int?, 
            originalTitle: String?, 
            englishTitle: String?, 
            aniListId: Int?, 
            malId: Int?, 
            kitsuId: String?, 
            addonUrl: String? = null,
            year: Int? = null // ★ year 매개변수 추가
        ): Episode { // ★ 누락되었던 닫는 괄호, 반환 타입, 시작 중괄호 추가
            val tmdbEp = tmdbMetaMap["${displaySeason}_${displayEpisode}"] ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
            val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
            val airedYearInt = finalAirDate?.split("-")?.first()?.toIntOrNull()
            
            return provider.newEpisode(LoadData(
                type = type, 
                id = id, 
                season = displaySeason, 
                episode = displayEpisode, 
                imdbId = imdbId, 
                year = year, // ★ null 대신 전달받은 정수형 year 바인딩
                title = showTitle, 
                tmdbId = tmdbId, 
                isAnime = isAnime,
                isBollywood = isBollywood,
                isAsian = isAsian,
                isCartoon = isCartoon,
                firstAired = finalAirDate, 
                airedYear = airedYearInt, 
                imdbSeason = imdbSeason, 
                imdbEpisode = imdbEpisode,
                originalTitle = originalTitle,
                englishTitle = englishTitle,
                anilistId = aniListId,
                malId = malId,
                kitsuId = kitsuId,
                addonUrl = addonUrl
            )) {
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) rawTitle.substringAfter("📄").replace("\n", "").trim() else rawTitle.replace("\n", "").trim()

                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL

                var finalEpDesc = (tmdbEp?.overview ?: this@Video.overview ?: this@Video.description)?.takeIf { it != "null" }
                if (type != "movie" && type != "series") {
                    finalEpDesc = if (finalEpDesc.isNullOrBlank()) cleanOriginalTitle else "$finalEpDesc<br>$cleanOriginalTitle"
                }
                
                this.description = finalEpDesc
                this.season = displaySeason
                this.episode = displayEpisode

                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }
            }
        }
    }

    private data class StreamsResponse(@JsonProperty("streams") val streams: List<Stream>)    
    private data class SubtitlesResponse(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    private data class Subtitle(@JsonProperty("url") val url: String?, @JsonProperty("lang") val lang: String?, @JsonProperty("lang_code") val lang_code: String?, @JsonProperty("id") val id: String?)    
    private data class ProxyHeaders(@JsonProperty("request") val request: Map<String, String>?)   
    private data class BehaviorHints(@JsonProperty("proxyHeaders") val proxyHeaders: ProxyHeaders?, @JsonProperty("headers") val headers: Map<String, String>?)

    private data class Stream(
        @JsonProperty("name") val name: String?,
        @JsonProperty("title") val title: String?,
        @JsonProperty("url") val url: String?,
        @JsonProperty("description") val description: String?,
        @JsonProperty("ytId") val ytId: String?,
        @JsonProperty("externalUrl") val externalUrl: String?,
        @JsonProperty("behaviorHints") val behaviorHints: BehaviorHints?,
        @JsonProperty("infoHash") val infoHash: String?,
        @JsonProperty("sources") val sources: List<String> = emptyList(),
        @JsonProperty("subtitles") val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(
            provider: StremioC,
            stremioSubtitleCallback: (SubtitleFile) -> Unit,
            extractorSubtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ) {
            if (url != null) {
                val lowerUrl = url.lowercase()
                val linkType = when {
                    lowerUrl.contains(".m3u8") || 
                    lowerUrl.contains("m3u8") || 
                    lowerUrl.contains("playlist") || 
                    name?.contains("m3u8", true) == true || 
                    title?.contains("m3u8", true) == true -> ExtractorLinkType.M3U8
                    
                    lowerUrl.contains(".mpd") -> ExtractorLinkType.DASH
                    lowerUrl.contains(".torrent") -> ExtractorLinkType.TORRENT
                    else -> ExtractorLinkType.VIDEO 
                }

                callback.invoke(newExtractorLink(name ?: "", fixSourceName(name, title), url, linkType) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
            }

            if (ytId != null) loadExtractor("https://m.youtube.com/watch?v=$ytId", extractorSubtitleCallback, callback)
            if (externalUrl != null) loadExtractor(externalUrl, extractorSubtitleCallback, callback)
            if (infoHash != null) callback.invoke(newExtractorLink(name ?: "", title ?: name ?: "", "magnet:?xt=urn:btih:${infoHash}") { this.quality = Qualities.Unknown.value })

            val hasPlayableTarget = url != null || ytId != null || externalUrl != null || infoHash != null
            if (hasPlayableTarget) {
                subtitles.forEach { sub ->
                    val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                    val parsedLang = SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang
                    val subtitleUrl = sub.url ?: return@forEach
                    stremioSubtitleCallback.invoke(newSubtitleFile(parsedLang, subtitleUrl))
                }
            }
        }
    }
}

private data class TmdbFindResponse(
    @JsonProperty("movie_results") val movie_results: List<TmdbFindResult>? = null,
    @JsonProperty("tv_results") val tv_results: List<TmdbFindResult>? = null
)

private data class TmdbFindResult(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("media_type") val media_type: String? = null
)

private data class TmdbCollection(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("backdrop_path") val backdrop_path: String? = null
)

private data class TmdbCollectionDetail(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("parts") val parts: List<TmdbMedia>? = null
)

private data class TmdbTranslations(@JsonProperty("translations") val translations: List<TmdbTranslationItem>?)
private data class TmdbTranslationItem(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("iso_639_1") val iso_639_1: String?, @JsonProperty("data") val data: TmdbTranslationData?)
private data class TmdbTranslationData(@JsonProperty("title") val title: String?, @JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?)

private data class TmdbDetailResponse(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("number_of_seasons") val number_of_seasons: Int? = null,
    @JsonProperty("title") val title: String? = null, 
    @JsonProperty("name") val name: String? = null, 
    @JsonProperty("original_title") val original_title: String? = null, 
    @JsonProperty("original_name") val original_name: String? = null, 
    @JsonProperty("overview") val overview: String? = null, 
    @JsonProperty("release_date") val releaseDate: String? = null, 
    @JsonProperty("first_air_date") val firstAirDate: String? = null, 
    @JsonProperty("status") val status: String? = null, 
    @JsonProperty("poster_path") val posterPath: String? = null, 
    @JsonProperty("origin_country") val origin_country: List<String>? = null, 
    @JsonProperty("original_language") val original_language: String? = null, 
    @JsonProperty("belongs_to_collection") val belongs_to_collection: TmdbCollection? = null, 
    @JsonProperty("recommendations") val recommendations: TmdbRecommendations? = null,
    @JsonProperty("runtime") val runtime: Int? = null, 
    @JsonProperty("release_dates") val release_dates: TmdbReleaseDates? = null, 
    @JsonProperty("content_ratings") val content_ratings: TmdbContentRatings? = null, 
    @JsonProperty("credits") val credits: TmdbCredits? = null, 
    @JsonProperty("images") val images: TmdbImages? = null, 
    @JsonProperty("videos") val videos: ResultsTrailer? = null, 
    @JsonProperty("external_ids") val external_ids: ExternalIds? = null, 
    @JsonProperty("translations") val translations: TmdbTranslations? = null,
    @JsonProperty("vote_average") val vote_average: Double? = null
) {
    fun toTmdbMedia(mediaType: String): TmdbMedia {
        return TmdbMedia(
            id = this.id,
            name = this.name ?: this.title,
            title = this.title ?: this.name,
            originalTitle = this.original_title ?: this.original_name,
            mediaType = mediaType,
            poster_path = this.posterPath,
            overview = this.overview,
            origin_country = this.origin_country,
            original_language = this.original_language,
            releaseDate = this.releaseDate,
            firstAirDate = this.firstAirDate
        )
    }
}

private data class TmdbImages(@JsonProperty("logos") val logos: List<TmdbLogo>? = null)
private data class TmdbLogo(@JsonProperty("file_path") val file_path: String? = null)
private data class TmdbRecommendations(@JsonProperty("results") val results: List<TmdbMedia>? = null)
private data class TmdbMedia(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("original_title") val originalTitle: String? = null,
    @JsonProperty("media_type") val mediaType: String? = null,
    @JsonProperty("poster_path") val poster_path: String? = null,
    @JsonProperty("overview") val overview: String? = null,
    @JsonProperty("origin_country") val origin_country: List<String>? = null,
    @JsonProperty("original_language") val original_language: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("first_air_date") val firstAirDate: String? = null
)

private data class TmdbReleaseDates(@JsonProperty("results") val results: List<TmdbReleaseDateResult>?)
private data class TmdbReleaseDateResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("release_dates") val release_dates: List<TmdbReleaseDate>?)
private data class TmdbReleaseDate(@JsonProperty("certification") val certification: String?)
private data class TmdbContentRatings(@JsonProperty("results") val results: List<TmdbContentRatingResult>?)
private data class TmdbContentRatingResult(@JsonProperty("iso_3166_1") val iso_3166_1: String?, @JsonProperty("rating") val rating: String?)
private data class TmdbCredits(@JsonProperty("cast") val cast: List<TmdbCast>? = null, @JsonProperty("crew") val crew: List<TmdbCrew>? = null)
private data class TmdbCast(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("character") val character: String?, @JsonProperty("profile_path") val profilePath: String?)
private data class TmdbCrew(@JsonProperty("name") val name: String?, @JsonProperty("original_name") val originalName: String?, @JsonProperty("job") val job: String?, @JsonProperty("profile_path") val profilePath: String?)
private data class TmdbSeasonDetail(@JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(@JsonProperty("name") val name: String?, @JsonProperty("overview") val overview: String?, @JsonProperty("episode_number") val episodeNumber: Int?, @JsonProperty("air_date") val airDate: String?, @JsonProperty("vote_average") val voteAverage: Double?, @JsonProperty("runtime") val runtime: Int?)

data class Trailers(@JsonProperty("key") val key: String? = null, @JsonProperty("type") val type: String? = null, @JsonProperty("published_at") val publishedAt: String? = null)
data class ResultsTrailer(@JsonProperty("results") val results: ArrayList<Trailers>? = arrayListOf())
data class ExternalIds(@JsonProperty("imdb_id") val imdb_id: String? = null, @JsonProperty("tvdb_id") val tvdb_id: String? = null)

private fun String.toUniqueKey(): String {
    val bytes = java.security.MessageDigest.getInstance("SHA-256")
        .digest(this.toByteArray(Charsets.UTF_8))
    return "v2_" + bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

private fun String.toLegacyUniqueKey(): String {
    return this.lowercase().filter { it.isLetter() }
}

private fun String.urlEncodeLocal(): String = try {
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
} catch (e: Exception) {
    this
}

fun getSafeAllProviders(): List<MainAPI> {
    return try {
        val method = APIHolder::class.java.methods.find { it.name == "getAllProviders" }
        val obj = method?.invoke(APIHolder)
        when (obj) {
            is Iterable<*> -> obj.filterIsInstance<MainAPI>()
            is Array<*> -> obj.filterIsInstance<MainAPI>()
            else -> emptyList()
        }
    } catch (e: Exception) {
        e.printStackTrace()
        emptyList()
    }
}

private data class FlatTab(val key: String, val displayName: String, val urlPath: String)


// Shared by the provider and SettingsBottomFragment. Keep these functions together.
internal fun stremioManifestUrl(source: String): String {
    val base = source.substringBefore("?").trimEnd('/').removeSuffix("/manifest.json")
    val query = source.substringAfter("?", "").takeIf { it.isNotEmpty() }?.let { "?$it" } ?: ""
    return "$base/manifest.json$query"
}

internal fun stremioCatalogKey(source: String): String = stremioManifestUrl(source).toUniqueKey()

private fun stremioOldCatalogKeys(source: String): List<String> = listOf(
    source.toLegacyUniqueKey(),
    stremioManifestUrl(source).substringBefore("?").toLegacyUniqueKey(),
    stremioManifestUrl(source).toLegacyUniqueKey(),
    stremioManifestUrl(source).substringBefore("?").toUniqueKey()
).distinct()

internal fun migrateStremioCatalogSettings(prefs: SharedPreferences, source: String) {
    val key = stremioCatalogKey(source)
    val marker = "catalog_keys_migrated_$key"
    synchronized(StremioC::class.java) {
        if (prefs.getBoolean(marker, false)) return
        val all = prefs.all
        val editor = prefs.edit()
        val written = mutableSetOf<String>()
        for (old in stremioOldCatalogKeys(source)) {
            if (old == key) continue
            for ((name, value) in all) {
                val target = when {
                    name == "catalog_order_$old" -> "catalog_order_$key"
                    name.startsWith("tab_enabled_${old}_") -> "tab_enabled_${key}_" + name.removePrefix("tab_enabled_${old}_")
                    name.startsWith("genres_enabled_${old}_") -> "genres_enabled_${key}_" + name.removePrefix("genres_enabled_${old}_")
                    else -> continue
                }
                // The supplied settings UI wrote legacy keys exclusively. On first
                // migration these are authoritative; subsequent saves use v2 only.
                if (!written.add(target)) continue
                when (value) {
                    is Boolean -> editor.putBoolean(target, value)
                    is String -> editor.putString(target, value)
                }
            }
        }
        editor.putBoolean(marker, true).apply()
    }
}

internal fun stremioReadManifest(prefs: SharedPreferences, source: String): String? {
    prefs.getString(stremioCatalogKey(source), null)?.let { return it }
    for (old in stremioOldCatalogKeys(source)) {
        prefs.getString(old, null)?.let { return it }
    }
    return null
}
