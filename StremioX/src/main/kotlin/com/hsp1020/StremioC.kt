package com.hsp1020

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonElement
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.CloudStreamApp
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
import com.lagradost.cloudstream3.imdbUrlToIdNullable
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
import com.lagradost.cloudstream3.utils.USER_PROVIDER_API
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.hsp1020.SubsExtractors.invokeOpenSubs
import com.hsp1020.SubsExtractors.invokeWatchsomuch
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

private fun JsonElement?.textOrNull(): String? {
    if (this == null || this.isJsonNull) return null
    return try { this.asString.takeIf { it.isNotBlank() && it != "null" } } catch (e: Exception) { null }
}

private fun JsonElement?.intOrNull(): Int? {
    if (this == null || this.isJsonNull) return null
    return try { this.asInt } catch (e: Exception) { null }
}

open class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    
    private val pageMutex = Mutex()
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()

    private fun getCineStreamProvider(forceSearch: Boolean = false): MainAPI? {
        if (cachedCineStreamProvider != null) return cachedCustomCineStreamProvider()
        if (!forceSearch && hasSearchedCineStream) return null

        synchronized(StremioC::class.java) {
            if (cachedCineStreamProvider != null) return cachedCustomCineStreamProvider()
            hasSearchedCineStream = true

            val provider = APIHolder.allProviders.find { it.name.equals("CineStream", true) }
                ?: loadProviderDynamically("megix", "com.megix.CineStreamProvider")
            
            if (provider != null) cachedCineStreamProvider = provider
            return provider
        }
    }

    private fun cachedCustomCineStreamProvider(): MainAPI? = cachedCineStreamProvider

    private fun getStreamPlayProvider(forceSearch: Boolean = false): MainAPI? {
        if (cachedStreamPlayProvider != null) return cachedCustomStreamPlayProvider()
        if (!forceSearch && hasSearchedStreamPlay) return null

        synchronized(StremioC::class.java) {
            if (cachedStreamPlayProvider != null) return cachedCustomStreamPlayProvider()
            hasSearchedStreamPlay = true

            val provider = APIHolder.allProviders.find { it.name.equals("StreamPlay", true) }
                ?: loadProviderDynamically("phisher", "com.phisher98.StreamPlay")
            
            if (provider != null) cachedStreamPlayProvider = provider
            return provider
        }
    }

    private fun cachedCustomStreamPlayProvider(): MainAPI? = cachedStreamPlayProvider

    fun getUnifiedMetaUrl(type: String, id: String): String {
        val context = CloudStreamApp.context ?: return "${mainUrl.cleanBaseUrl()}/meta/$type/${id.urlEncode()}.json"
        val prefs = context.getSharedPreferences("StremioX", Context.MODE_PRIVATE)
        val isSyncEnabled = prefs.getBoolean("history_sync_enabled", false)
        val syncUrl = prefs.getString("history_sync_url", "") ?: ""

        return if (isSyncEnabled && syncUrl.isNotBlank()) {
            val cleanBase = syncUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        } else {
            val cleanBase = mainUrl.cleanBaseUrl()
            "$cleanBase/meta/$type/${id.urlEncode()}.json"
        }
    }

    fun getTargetApiName(): String {
        val context = CloudStreamApp.context ?: return name
        val prefs = context.getSharedPreferences("StremioX", Context.MODE_PRIVATE)
        val isSyncEnabled = prefs.getBoolean("history_sync_enabled", false)
        return if (isSyncEnabled) "StremioX" else name
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

        val networkSemaphore = Semaphore(35)

        @Volatile private var cachedCineStreamProvider: MainAPI? = null
        @Volatile private var hasSearchedCineStream = false

        @Volatile private var cachedStreamPlayProvider: MainAPI? = null
        @Volatile private var hasSearchedStreamPlay = false

        @Volatile private var hasSyncedCineStreamSettings = false
        
        @Volatile private var cachedClassLoaderRef: WeakReference<ClassLoader>? = null

        val gson: Gson by lazy { Gson() }

        private val tmdbDetailsCache = lruCacheMap<String, Pair<TmdbDetailResponse, Long>>(100)
        private val tmdbSeasonCache = lruCacheMap<String, Pair<TmdbSeasonDetail, Long>>(150)
        private val kitsuMetaCache = lruCacheMap<String, Pair<String, Long>>(100)
        private val tmdbFindCache = lruCacheMap<String, Pair<Pair<String, String>?, Long>>(100)
        private val aniZipCache = lruCacheMap<String, Pair<Pair<Int?, Int?>?, Long>>(100)
        private const val METADATA_CACHE_TTL = 12 * 60 * 60 * 1000L

        // 🔥 [V4.1.3] 중복 제거 및 페이지네이션 skip 오프셋 추적 필드를 companion object(정적 영역)로 이관하여 안전하게 보존
        val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
        val catalogSkipState = ConcurrentHashMap<String, Int>()

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

        init {
            app.baseClient.dispatcher.maxRequestsPerHost = 40
            app.baseClient.dispatcher.maxRequests = 70 
        }

        fun loadProviderDynamically(pluginKeyword: String, className: String): MainAPI? {
            val loader = cachedClassLoaderRef?.get()
            if (loader != null) {
                try {
                    val clazz = loader.loadClass(className)
                    return clazz.newInstance() as? MainAPI
                } catch (e: Exception) { e.printStackTrace() }
            }
            try {
                val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val pmInstance = pmClass.getField("INSTANCE").get(null)

                var foundLoader: ClassLoader? = null
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
                                foundLoader = plugin!!.javaClass.classLoader
                                break
                            }
                        }
                    }
                    if (foundLoader != null) break
                }
                
                if (foundLoader != null) {
                    cachedClassLoaderRef = WeakReference(foundLoader)
                    val clazz = foundLoader.loadClass(className)
                    return clazz.newInstance() as? MainAPI
                }
            } catch (e: Exception) { e.printStackTrace() }
            return null
        }

        fun isIntegrationEnabled(prefKey: String): Boolean {
            val now = System.currentTimeMillis()
            val cached = settingsCache[prefKey]
            if (cached != null && (now - cached.second) < SETTINGS_CACHE_TTL) {
                return cached.first
            }

            val context = CloudStreamApp.context ?: return true
            val possibleNames = listOf("StremioX", "StremioX_preferences", "stremio_preferences", "com.hsp1020_preferences", context.packageName + "_preferences")
            
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
        val prefs = context?.getSharedPreferences("StremioX", Context.MODE_PRIVATE)
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

    private suspend fun getManifest(): Manifest? {
        val originalBase = mainUrl.substringBefore("?").trimEnd('/')
        val currentUrl = if (originalBase.endsWith("manifest.json")) originalBase else "$originalBase/manifest.json"
        
        val now = System.currentTimeMillis()
        val isExpired = (now - lastCacheTime) > CACHE_TTL_MS

        if (cachedManifest != null && lastManifestUrl == currentUrl && !isExpired && !cachedManifest?.catalogs.isNullOrEmpty()) {
            return cachedManifest
        }

        val resText = runSuspendCatching { 
            customSession.get(currentUrl, timeout = 15L).text
        }.onFailure { it.printStackTrace() }.getOrNull()

        if (!resText.isNullOrBlank()) {
            try {
                val parsed = gson.fromJson(resText, Manifest::class.java)
                if (parsed?.catalogs?.isNotEmpty() == true) {
                    cachedManifest = parsed
                    lastManifestUrl = currentUrl
                    lastCacheTime = now
                    
                    // 🔥 [V4.1.3 멀티테넌트 예방 조치] 공용 companion 영역에서 다른 프로바이더의 캐시 상태까지 다 날려버리지 않도록 prefix가 일치하는 고유 키들만 제거
                    val prefix = "${this@StremioC.name}_"
                    catalogSentIds.keys.filter { it.startsWith(prefix) }.forEach { catalogSentIds.remove(it) }
                    catalogSkipState.keys.filter { it.startsWith(prefix) }.forEach { catalogSkipState.remove(it) }
                    
                    val context = CloudStreamApp.context
                    context?.getSharedPreferences("StremioX_Manifest", Context.MODE_PRIVATE)
                        ?.edit()?.putString(currentUrl.toUniqueKey(), resText)?.apply()
                        
                    return parsed
                }
            } catch (e: Exception) { e.printStackTrace() }
        }
        
        return cachedManifest
    }


    override val mainPage: List<MainPageData>
        get() {
            val context = CloudStreamApp.context ?: return emptyList()
            val prefs = context.getSharedPreferences("StremioX", Context.MODE_PRIVATE)
            val manifestPrefs = context.getSharedPreferences("StremioX_Manifest", Context.MODE_PRIVATE)

            val originalBase = mainUrl.substringBefore("?").trimEnd('/')
            val currentUrl = if (originalBase.endsWith("manifest.json")) originalBase else "$originalBase/manifest.json"
            val uniqueKey = currentUrl.toUniqueKey()
            
            val diskCache = manifestPrefs.getString(uniqueKey, null)
            
            val rawCatalogs = if (cachedManifest != null) {
                cachedManifest?.catalogs ?: emptyList()
            } else if (!diskCache.isNullOrBlank()) {
                try {
                    gson.fromJson(diskCache, Manifest::class.java).catalogs ?: emptyList()
                } catch (e: Exception) { 
                    e.printStackTrace()
                    emptyList() 
                }
            } else {
                emptyList()
            }

            if (rawCatalogs.isEmpty()) return emptyList()
            
            val flatTabs = mutableListOf<FlatTab>()
            rawCatalogs.forEach { catalog ->
                val catalogId = catalog.id ?: ""
                val catalogType = catalog.type ?: "movie"
                val catalogName = catalog.name ?: catalogId
                val displayType = catalogType.replaceFirstChar { it.uppercase() }
                
                val requiresGenre = catalog.extra?.any { it.name == "genre" && it.isRequired == true } == true

                if (!requiresGenre) {
                    flatTabs.add(FlatTab(
                        key = "${catalogId}_${catalogType}_default",
                        displayName = "$catalogName - $displayType",
                        urlPath = "/catalog/$catalogType/$catalogId/skip=###.json"
                    ))
                }

                val genres = catalog.getGenresList()
                genres.forEach { genre ->
                    val capitalizedGenre = genre.replaceFirstChar { it.uppercase() }
                    flatTabs.add(FlatTab(
                        key = "${catalogId}_${catalogType}_genre_$genre",
                        displayName = "$catalogName : $capitalizedGenre  - $displayType",
                        urlPath = "/catalog/$catalogType/$catalogId/genre=${genre.urlEncodeLocal()}&skip=###.json"
                    ))
                }
            }

            val orderKey = "catalog_order_$uniqueKey"
            val orderJson = prefs.getString(orderKey, null)
            val orderedKeys = mutableListOf<String>()
            if (orderJson != null) {
                try {
                    val arr = org.json.JSONArray(orderJson)
                    for (i in 0 until arr.length()) {
                        orderedKeys.add(arr.getString(i))
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }

            val sortedTabs = flatTabs.sortedBy { tab ->
                val idx = orderedKeys.indexOf(tab.key)
                if (idx != -1) idx else Int.MAX_VALUE
            }

            return mainPageOf(
                *buildList<Pair<String, String>> {
                    sortedTabs.forEach { tab ->
                        val isEnabled = if (tab.key.contains("_genre_")) {
                            val catalogKey = tab.key.substringBefore("_genre_")
                            val isGenresMasterEnabled = prefs.getBoolean("genres_enabled_${uniqueKey}_$catalogKey", true)
                            val isIndividualGenreEnabled = prefs.getBoolean("tab_enabled_${uniqueKey}_${tab.key}", true)
                            isGenresMasterEnabled && isIndividualGenreEnabled
                        } else {
                            prefs.getBoolean("tab_enabled_${uniqueKey}_${tab.key}", true)
                        }
                        
                        if (isEnabled) {
                            add(tab.urlPath to tab.displayName)
                        }
                    }
                }.toTypedArray()
            )
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        // 🔥 [PAGINATION-DEBUG] 메인 페이지 요청 진입 로그
        println("[PAGINATION-DEBUG] ===== getMainPage START =====")
        println("[PAGINATION-DEBUG] Requested Page: $page, Tab Name: ${request.name}")
        println("[PAGINATION-DEBUG] Request Data: ${request.data}")

        launch(Dispatchers.IO) {
            runSuspendCatching { getManifest() }.onFailure { it.printStackTrace() }
        }

        var isNewRequest = false
        val stateKey = "${this@StremioC.name}_${request.name}" // 🔥 [V4.1.3] 동일 탭 이름의 다른 스트레미오 프로바이더간 상태 전이 및 꼬임 현상 원천 봉쇄

        val deferred = pageMutex.withLock {
            if (page <= 1) {
                // 🔥 [PAGINATION-DEBUG] 1페이지 요청 시 정적 메모리 내 대상 탭 초기화 진행
                println("[PAGINATION-DEBUG] Page <= 1 detected. Clearing states for Tab Key: $stateKey")
                catalogSentIds.remove(stateKey)
                activePageRequests.clear()
                catalogSkipState.remove(stateKey)
            }
            
            activePageRequests.getOrPut(page) {
                isNewRequest = true
                println("[PAGINATION-DEBUG] Creating new async request for Page: $page")
                async { fetchTabCatalogData(request, page) }
            }
        }
        
        return@coroutineScope try {
            val response = deferred.await()
            if (!isNewRequest && page > 1) {
                println("[PAGINATION-DEBUG] Returning empty list because it's not a new request for Page: $page")
                newHomePageResponse(emptyList(), hasNext = true)
            } else {
                val listSize = response.items.firstOrNull()?.list?.size ?: 0
                println("[PAGINATION-DEBUG] Returning response for Page: $page with $listSize items.")
                response
            }
        } finally {
            pageMutex.withLock {
                activePageRequests.remove(page)
            }
            println("[PAGINATION-DEBUG] ===== getMainPage END (Page: $page) =====")
        }
    }
    
    private suspend fun fetchTabCatalogData(request: MainPageRequest, page: Int): HomePageResponse {
        val stateKey = "${this@StremioC.name}_${request.name}"

        // 🔥 [V4.1.3] 콜드 스타트 구원 방어막 (Silent Pre-fetch)
        // 앱을 재시작했으나 Cloudstream 코어에서 1페이지를 로컬 DB 캐시로 불러와 플러그인의 page=1을 무시하고, 바로 2페이지를 요청할 경우
        // 백그라운드에서 동기적으로 1페이지(skip=0)의 실제 응답 크기와 중복제거 아이디 세트를 우선 채워놓도록 처리합니다.
        if (page > 1 && !catalogSkipState.containsKey(stateKey)) {
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] Cold start detected on Page $page. Silently pre-fetching Page 1 to restore state.")
            try {
                // page=1로 재귀 진입하여 무한 루프에 빠지지 않고 안전하게 1회만 실행됨
                fetchTabCatalogData(request, 1) 
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        val currentSkip = catalogSkipState[stateKey] ?: 0
        println("[PAGINATION-DEBUG] [fetchTabCatalogData] Current Skip State: $currentSkip for Tab Key: $stateKey")
        
        val targetPath = request.data.replace("###", currentSkip.toString())
        val targetUrl = buildUrl(targetPath)
        println("[PAGINATION-DEBUG] [fetchTabCatalogData] Target URL: $targetUrl")
        
        val cacheKey = "${this@StremioC.name}_${request.name}_$currentSkip"
        println("[PAGINATION-DEBUG] [fetchTabCatalogData] Cache Key: $cacheKey")

        val resultPair = getOrFetchCachedPage(cacheKey) {
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] Fetching from network or cache...")
            val jsonText = customSession.get(targetUrl).text
            val response = try {
                gson.fromJson(jsonText, CatalogResponse::class.java)
            } catch (e: Exception) { e.printStackTrace(); null }

            val metas = response?.metas ?: emptyList()
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] Network/JSON parsed ${metas.size} items.")
            
            val mappedItems = metas.map { it.toSearchResponse(this@StremioC) }
            Pair(mappedItems, metas.size)
        }

        if (resultPair != null) {
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] ResultPair acquired. Size: ${resultPair.first.size}, Returned Meta Size: ${resultPair.second}")
            catalogSkipState[stateKey] = currentSkip + resultPair.second
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] Next Skip State updated to: ${catalogSkipState[stateKey]}")
            
            var seenForThisCatalog = catalogSentIds[stateKey]
            if (seenForThisCatalog == null) {
                synchronized(catalogSentIds) {
                    seenForThisCatalog = catalogSentIds.getOrPut(stateKey) { 
                        lruSet(500) 
                    }
                }
            }
            
            var duplicateCount = 0
            val filteredItems = resultPair.first.filter { item -> 
                val pureId = item.extractPureId()
                val isNew = seenForThisCatalog!!.add(pureId)
                if (!isNew) {
                    duplicateCount++
                }
                isNew
            }
            
            println("[PAGINATION-DEBUG] [fetchTabCatalogData] Deduplication complete. Duplicates found: $duplicateCount. Final items to return: ${filteredItems.size}")
            
            return newHomePageResponse(listOf(HomePageList(request.name, filteredItems)), hasNext = resultPair.second > 0)
        }

        println("[PAGINATION-DEBUG] [fetchTabCatalogData] ResultPair is null. Returning empty.")
        return newHomePageResponse(emptyList(), hasNext = false)
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
        val res: CatalogEntry = if (url.startsWith("{")) {
            gson.fromJson(url, CatalogEntry::class.java)
        } else {
            val responseText = customSession.get(url).text
            try {
                val rootNode = JsonParser.parseString(responseText).asJsonObject
                val metaNode = if (rootNode.has("meta")) rootNode.get("meta") else rootNode
                gson.fromJson(metaNode, CatalogEntry::class.java) 
                    ?: throw IllegalStateException("Failed to bind JSON to CatalogEntry")
            } catch (e: Exception) {
                e.printStackTrace()
                throw IllegalStateException("Metadata not found or parsing failed: $url", e)
            }
        }
        
        val normalizedId = try { normalizeId(res.id ?: "") } catch (e: Exception) { e.printStackTrace(); res.id ?: "" }
        val encodedId = normalizedId.urlEncode()

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
                    val root = JsonParser.parseString(kitsuRes).asJsonObject
                    val metaObj = root.getAsJsonObject("meta")
                    if (metaObj != null) {
                        resolvedImdbId = metaObj.get("imdb_id").textOrNull()?.takeIf { it.startsWith("tt") }
                        resolvedTmdbId = metaObj.get("tmdb_id").textOrNull() 
                            ?: metaObj.get("moviedb_id").intOrNull()?.takeIf { it > 0 }?.toString()
                        
                        fetchedAniListId = metaObj.get("anilist_id").intOrNull()
                        fetchedMalId = metaObj.get("mal_id").intOrNull()

                        metaObj.get("videos")?.takeIf { it.isJsonArray }?.asJsonArray?.let { arr ->
                            val vids = mutableListOf<Video>()
                            for (i in 0 until arr.size()) {
                                val v = arr.get(i).asJsonObject
                                if (v != null) {
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

        val primaryMdbId = resolvedTmdbId ?: resolvedImdbId ?: when {
            res.id?.startsWith("tt") == true -> res.id
            res.id?.startsWith("tmdb:") == true -> res.id.removePrefix("tmdb:")
            res.moviedb_id != null -> res.moviedb_id.toString()
            res.tmdbIdExt != null -> res.tmdbIdExt
            else -> null
        }
        
        val primaryMdbIsTmdb = (primaryMdbId == resolvedTmdbId) || (primaryMdbId?.startsWith("tt") == false)

        val mdbDeferred: Deferred<List<String>>? = primaryMdbId?.let { id ->
            async(Dispatchers.IO) {
                runSuspendCatching { fetchMdbListRatings(id, primaryMdbIsTmdb) }.onFailure { it.printStackTrace() }.getOrDefault(emptyList())
            }
        }

        val addonDeferred = async(Dispatchers.IO) {
            runSuspendCatching {
                val syncMetaUrl = this@StremioC.getUnifiedMetaUrl(res.type ?: "movie", res.id ?: "")
                val text = customSession.get(syncMetaUrl).text
                val response = if (text.isNotBlank()) gson.fromJson(text, CatalogResponse::class.java) else null
                response?.meta ?: response?.metas?.firstOrNull { it.id == res.id } ?: response?.metas?.firstOrNull()
            }.onFailure { it.printStackTrace() }.getOrNull()
        }
             
        
        val tmdbIdFromRes = res.moviedb_id?.toString() ?: res.tmdbIdExt
        val targetTmdbId = resolvedTmdbId ?: if (res.id?.startsWith("tmdb:") == true) res.id.removePrefix("tmdb:") else tmdbIdFromRes
        
        val tmdbDeferred = targetTmdbId?.let { id ->
            async(Dispatchers.IO) {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val detailAppend = if (mediaType == "movie") "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = buildTmdbUrl("/$mediaType/$id", "append_to_response" to detailAppend)
                runSuspendCatching { 
                    val text = customSession.get(detailUrl).text
                    if (text.isNotBlank()) gson.fromJson(text, TmdbDetailResponse::class.java) else null
                }.onFailure { it.printStackTrace() }.getOrNull()
            }
        }

        val preFetchedTmdbDetail = tmdbDeferred?.await()
        
        var finalProcessedId = res.id
        var imdbId = resolvedImdbId ?: preFetchedTmdbDetail?.external_ids?.imdb_id

        if (!imdbId.isNullOrBlank() && (imdbId.startsWith("tt") || imdbId.startsWith("kitsu:"))) {
            finalProcessedId = imdbId
        }

        val loadRes = res.toLoadResponse(
            provider = this@StremioC, 
            imdbId = finalProcessedId, 
            addonDeferred = addonDeferred, 
            preFetchedDetail = preFetchedTmdbDetail, 
            fetchedAniListId = fetchedAniListId, 
            fetchedMalId = fetchedMalId, 
            resolvedTmdbId = resolvedTmdbId,
            kitsuVideos = kitsuVideos
        )
        
        val mdbRatings = mdbDeferred?.await() ?: emptyList()
        if (mdbRatings.isNotEmpty()) {
            val ratingStr = mdbRatings.joinToString("  ")
            val currentPlot = loadRes.plot 
            
            loadRes.plot = if (!currentPlot.isNullOrBlank()) "$ratingStr | $currentPlot" else ratingStr
            loadRes.score = null 
        }
        
        return@coroutineScope loadRes
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = try { gson.fromJson(data, LoadData::class.java) } catch (e: Exception) { e.printStackTrace(); null } ?: return false
        
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

        val seenLinks = Collections.synchronizedSet(mutableSetOf<String>())
        val wrappedCallback: (ExtractorLink) -> Unit = { link ->
            if (seenLinks.add(link.url)) {
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
                        this.quality = 2160
                    }
            )
        }

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
                                println("[STREMIOSUB] Setup Scan: Found 'stremio_addons' key in file: '$name' -> value = ${p.getString("stremio_addons", "")}")
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
                
                println("[STREMIOSUB] Attempt 1 (rebuild_preference/key) -> raw value length: ${rawValue.length}, data: $rawValue")
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
                    println("[STREMIOSUB] Attempt 2 (default SharedPreferences) -> length: ${v.length}, data: $v")
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
                
            println("[STREMIOSUB] Final parsed customHosts list: $hosts")
            cachedCustomHosts = hosts
            cachedCustomHostsTime = now
            hosts
        }
        val builtInHosts = setOf("opensubtitles.stremio.homes", "subsense.nepiraw.com", "opensubtitles-v3.strem.io")

        val subtitleInterceptor: (SubtitleFile) -> Unit = { sub ->
            println("[STREMIOSUB] Interceptor Triggered -> lang: '${sub.lang}', url: '${sub.url}'")
            try {
                val host = try {
                    sub.url.toHttpUrlOrNull()?.host ?: URI(sub.url).host ?: ""
                } catch (t: Throwable) {
                    val fallback = try {
                        sub.url.substringAfter("://").substringBefore("/").substringBefore(":")
                    } catch (ex: Exception) {
                        ""
                    }
                    println("[STREMIOSUB] Host Parse Error, Fallback parsing used: '$fallback'. Error: ${t.message}")
                    fallback
                }
                
                val isCustom = customHosts.contains(host)
                val isBuiltIn = builtInHosts.contains(host)
                val isContainsStrem = host.contains("strem", ignoreCase = true)
                
                println("[STREMIOSUB] Match Check -> host: '$host', isCustom: $isCustom, isBuiltIn: $isBuiltIn, isContainsStrem: $isContainsStrem")
                
                val isStremio = isCustom || isBuiltIn || isContainsStrem
                
                if (isStremio && !sub.lang.contains("(Stremio)", ignoreCase = true)) {
                    val newLang = "${sub.lang} (Stremio)"
                    println("[STREMIOSUB] Target Matched! Renaming subtitle label: '${sub.lang}' -> '$newLang'")
                    subtitleCallback.invoke(SubtitleFile(newLang, sub.url))
                } else {
                    println("[STREMIOSUB] Target Skipped -> Passing raw subtitle")
                    subtitleCallback.invoke(sub)
                }
            } catch (e: Exception) {
                println("[STREMIOSUB] Interceptor Crash: ${e.message}")
                e.printStackTrace()
                subtitleCallback.invoke(sub)
            }
        }

        runAllAsync(
            {
                runSuspendCatching {
                    val url = buildUrl("/stream/$targetType/$encodedPrimaryId.json")
                    println("[STREMIOSUB] Requesting primary streams from url: $url")
                    val text = customSession.get(url, timeout = 120L).text
                    val res = if (text.isNotBlank()) gson.fromJson(text, StreamsResponse::class.java) else null
                    println("[STREMIOSUB] Primary streams count returned: ${res?.streams?.size ?: 0}")
                    res?.streams?.forEach { stream -> 
                        stream.runCallback(this@StremioC, subtitleInterceptor, wrappedCallback) 
                    }
                }.onFailure { it.printStackTrace() }
            },
            { invokeWatchsomuch(loadData.imdbId, globalSeason, globalEpisode, subtitleCallback) },
            { invokeOpenSubs(loadData.imdbId, globalSeason, globalEpisode, subtitleCallback) },
            { 
                println("[STREMIOSUB] Initiating invokeCineStream")
                invokeCineStream(loadData, targetType, globalId, globalSeason, globalEpisode, isCasting, subtitleInterceptor, wrappedCallback) 
            },
            { 
                println("[STREMIOSUB] Initiating invokeStreamPlay")
                invokeStreamPlay(loadData, targetType, globalId, globalSeason, globalEpisode, isCasting, subtitleInterceptor, wrappedCallback) 
            },
            {
                if (isIntegrationEnabled("vixsrc_integration_enabled")) {
                    VixSrcExtractor.invokeVixSrc(loadData, targetType, globalSeason, globalEpisode, subtitleInterceptor, wrappedCallback)
                }
            }
        )
        return true
    }

    private suspend fun syncCineStreamSettings() = withContext(Dispatchers.IO) {
        if (hasSyncedCineStreamSettings) return@withContext
        try {
            val context = CloudStreamApp.context ?: return@withContext
            val possibleNames = listOf(
                "CineStream", "CineStream_preferences", "plugin_CineStream", "plugin_CineStream_preferences", 
                "com.megix", "com.megix_preferences", context.packageName + "_preferences"
            )
            
            var sourcePrefs: android.content.SharedPreferences? = null
            for (name in possibleNames) {
                val p = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (p.contains("seen_providers") || p.contains("provider_order") || p.contains("ScrapeConcurrency")) {
                    sourcePrefs = p
                    break
                }
            }
            val source = sourcePrefs ?: return@withContext

            source.all.forEach { (key, value) ->
                try {
                    val currentValue = try { CloudStreamApp.getKey<Any>(key) } catch (t: Throwable) { null }
                    if (currentValue != value) {
                        try {
                            when (value) {
                                is Boolean -> CloudStreamApp.setKey(key, value)
                                is String -> CloudStreamApp.setKey(key, value)
                                is Int -> CloudStreamApp.setKey(key, value)
                                is Long -> CloudStreamApp.setKey(key, value)
                                is Float -> CloudStreamApp.setKey(key, value)
                            }
                        } catch (t: Throwable) {}
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
            hasSyncedCineStreamSettings = true 
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun buildBasePayload(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        globalSeason: Int?,
        globalEpisode: Int?
    ): MutableMap<String, Any?> {
        val rawId = loadData.imdbId ?: globalId?.takeIf { it.startsWith("tt") || it.startsWith("tmdb:") } ?: ""
        val correctImdbId = if (rawId.startsWith("tmdb:")) {
            val parts = rawId.split(":")
            if (parts.size >= 2) "${parts[0]}:${parts[1]}" else rawId
        } else {
            rawId
        }

        val scrapingTitle = loadData.englishTitle?.takeIf { it.isNotBlank() } ?: loadData.originalTitle?.takeIf { it.isNotBlank() } ?: loadData.title ?: "Unknown"
        val isAnimeMedia = loadData.isAnime || globalId?.startsWith("kitsu:") == true || globalId?.startsWith("mal:") == true || loadData.anilistId != null || loadData.malId != null
        val isSeason2Plus = isAnimeMedia && (globalSeason ?: 0) > 1

        val finalTitle = if (isSeason2Plus) "$scrapingTitle Season $globalSeason" else scrapingTitle
        val finalYear = if (isSeason2Plus) loadData.airedYear?.toString() ?: loadData.year?.toString() else loadData.year?.toString()

        val actualIsMovie = targetType == "movie" || (loadData.season == null && loadData.episode == null)

        return mutableMapOf(
            "title" to scrapingTitle,
            "orgTitle" to loadData.originalTitle,
            "jpTitle" to loadData.originalTitle,
            "year" to finalYear,
            "airedYear" to loadData.airedYear,
            "date" to loadData.firstAired,
            "airedDate" to loadData.firstAired,
            "firstAired" to loadData.firstAired,
            "season" to globalSeason,
            "episode" to globalEpisode,
            "imdbSeason" to globalSeason,
            "imdbEpisode" to globalEpisode,
            "imdb_id" to loadData.imdbId,
            "imdbId" to correctImdbId,
            "tmdbId" to loadData.tmdbId,
            "type" to targetType,
            "tvtype" to targetType,
            "isMovie" to actualIsMovie,
            "isAnime" to isAnimeMedia,
            "isBollywood" to loadData.isBollywood,
            "isAsian" to loadData.isAsian,
            "isCartoon" to loadData.isCartoon,
            "malId" to loadData.malId
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

        try {
            val cineStreamProvider = getCineStreamProvider() ?: return
            syncCineStreamSettings()

            val payload = buildBasePayload(loadData, targetType, globalId, globalSeason, globalEpisode)
            
            val isKitsuMedia = globalId?.startsWith("kitsu:") == true || globalId?.startsWith("mal:") == true
            payload["id"] = payload["imdbId"] ?: ""
            payload["anilistId"] = loadData.anilistId
            payload["isKitsu"] = isKitsuMedia
            payload["kitsuId"] = if (isKitsuMedia) globalId?.substringAfter("kitsu:")?.substringAfter("mal:") else loadData.kitsuId

            val payloadJson = gson.toJson(payload)

            withTimeoutOrNull(25000L) {
                cineStreamProvider.loadLinks(payloadJson, isCasting, subtitleCallback, callback)
            }
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

        try {
            val streamPlayProvider = getStreamPlayProvider() ?: return

            val payload = buildBasePayload(loadData, targetType, globalId, globalSeason, globalEpisode)
            
            payload["id"] = loadData.tmdbId ?: loadData.imdbId ?: loadData.id
            payload["year"] = loadData.year ?: loadData.airedYear
            
            if (payload["title"] == null || payload["title"] == "Unknown") {
                payload["title"] = loadData.id ?: payload["id"]?.toString() ?: "Unknown Title"
            }
            payload["lastSeason"] = 1 
            payload["aniId"] = loadData.anilistId?.toString()

            val payloadJson = gson.toJson(payload)

            withTimeoutOrNull(25000L) {
                streamPlayProvider.loadLinks(payloadJson, isCasting, subtitleCallback, callback)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    data class LoadData(
        @SerializedName("type") val type: String? = null, 
        @SerializedName("id") val id: String? = null, 
        @SerializedName("season") val season: Int? = null, 
        @SerializedName("episode") val episode: Int? = null, 
        @SerializedName("imdbId") val imdbId: String? = null, 
        @SerializedName("year") val year: Int? = null, 
        @SerializedName("title") val title: String? = null, 
        @SerializedName("tmdbId") val tmdbId: Int? = null, 
        @SerializedName("isAnime") val isAnime: Boolean = false,
        @SerializedName("isBollywood") val isBollywood: Boolean = false,
        @SerializedName("isAsian") val isAsian: Boolean = false,
        @SerializedName("isCartoon") val isCartoon: Boolean = false,
        @SerializedName("firstAired") val firstAired: String? = null,
        @SerializedName("airedYear") val airedYear: Int? = null, 
        @SerializedName("originalTitle") val originalTitle: String? = null, 
        @SerializedName("englishTitle") val englishTitle: String? = null, 
        @SerializedName("imdbSeason") val imdbSeason: Int? = null,
        @SerializedName("imdbEpisode") val imdbEpisode: Int? = null,
        @SerializedName("anilistId") val anilistId: Int? = null,
        @SerializedName("malId") val malId: Int? = null,
        @SerializedName("kitsuId") val kitsuId: String? = null
    )
    
    data class Manifest(val catalogs: List<Catalog>)
    
    data class Extra(
        @SerializedName("name") val name: String?, 
        @SerializedName("isRequired") val isRequired: Boolean? = false,
        @SerializedName("options") val options: List<String>? = null
    )

    data class Catalog(
        @SerializedName("name") var name: String? = null, 
        @SerializedName("id") val id: String? = null, 
        @SerializedName("type") val type: String? = null, 
        @SerializedName("types") val parsedTypes: List<String>? = null, 
        @SerializedName("extra") val extra: List<Extra>? = null, 
        @SerializedName("extraSupported") val extraSupported: List<String>? = null,
        @SerializedName("genres") val genres: List<String>? = null
    ) {
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
                if (text.isNotBlank()) gson.fromJson(text, CatalogResponse::class.java).metas ?: emptyList() else emptyList()
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

    private data class CatalogResponse(val metas: List<CatalogEntry>?, val meta: CatalogEntry?)

    private data class Trailer(val source: String?, val type: String?)
    
    private data class TrailerStream(@SerializedName("ytId") val ytId: String?, @SerializedName("title") val title: String? = null)
    
    private data class Link(@SerializedName("name") val name: String? = null, @SerializedName("category") val category: String? = null, @SerializedName("url") val url: String? = null)

    private data class FetchedTmdbData(
        val fetchedTitle: String?, val fetchedOverview: String?, val fetchedLogo: String?,
        val fetchedTrailers: List<String>, val fetchedRecommendations: List<SearchResponse>?,
        val fetchedRuntime: Int?, val fetchedAgeRating: String?, val fetchedActors: List<ActorData>?,
        val fetchedYear: Int?, val fetchedStatus: ShowStatus?, val episodeTmdbMeta: Map<String, TmdbEpisode>,
        val fetchedOriginCountry: List<String>?,
        val fetchedOriginalTitle: String?,
        val fetchedEnglishTitle: String?
    )

    private data class CatalogEntry(
        @SerializedName("name") val name: String? = null, 
        @SerializedName("id") val id: String? = null, 
        @SerializedName("moviedb_id") val moviedb_id: Int? = null,
        @SerializedName("_tmdbId") val tmdbIdExt: String? = null, 
        @SerializedName("_malId") val malIdExt: String? = null,
        @SerializedName("_kitsuId") val kitsuIdExt: String? = null,
        @SerializedName("poster") val poster: String? = null, 
        @SerializedName("background") val background: String? = null, 
        @SerializedName("logo") val logo: String? = null,
        @SerializedName("description") val description: String? = null, 
        @SerializedName("imdbRating") val imdbRating: String? = null, 
        @SerializedName("type") val type: String? = null,
        @SerializedName("videos") val videos: List<Video>? = null, 
        @SerializedName("genre") val genre: List<String>? = null, 
        @SerializedName("genres") val genres: List<String>? = null,
        @SerializedName("cast") val cast: List<String>? = null, 
        @SerializedName("trailers") val trailersSources: List<Trailer>? = null,
        @SerializedName("trailerStreams") val trailerStreams: List<TrailerStream>? = null, 
        @SerializedName("links") val links: List<Link>? = null
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
            
            val cacheKey = "${tmdbMediaType}_${tmdbIdStr}"
            val cached = tmdbDetailsCache[cacheKey]
            val detailRes = preFetchedDetail ?: if (cached != null && System.currentTimeMillis() - cached.second < METADATA_CACHE_TTL) {
                cached.first
            } else {
                val detailAppend = if (isMovie) "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr", "append_to_response" to detailAppend)
                val text = StremioC.customSession.get(detailUrl).text
                val fetched = if (text.isNotBlank()) gson.fromJson(text, TmdbDetailResponse::class.java) else null
                if (fetched != null) {
                    tmdbDetailsCache[cacheKey] = Pair(fetched, System.currentTimeMillis())
                }
                fetched
            }
            
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null)
            }

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            val context = CloudStreamApp.context
            val prefs = context?.getSharedPreferences("StremioX", Context.MODE_PRIVATE)
            val langCode = prefs?.getString("tmdb_language_code", "ko-KR") ?: "ko-KR"
            val customPosterEnabled = prefs?.getBoolean("custom_poster_enabled", false) ?: false
            val customPosterBase = prefs?.getString("custom_poster_url", "") ?: ""
            
            coroutineScope {
                val shouldFocusEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType == "other" && !isMovie)
                
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
                                        val seasonCacheKey = "${tmdbIdStr}_$seasonNum"
                                        val cachedSeason = tmdbSeasonCache[seasonCacheKey]
                                        val seasonRes = if (cachedSeason != null && System.currentTimeMillis() - cachedSeason.second < METADATA_CACHE_TTL) {
                                            cachedSeason.first
                                        } else {
                                            val fetchedSeasonText = StremioC.customSession.get(provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")).text
                                            val fetchedSeason = if (fetchedSeasonText.isNotBlank()) gson.fromJson(fetchedSeasonText, TmdbSeasonDetail::class.java) else null
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
                            if (colText.isNotBlank()) gson.fromJson(colText, TmdbCollectionDetail::class.java).parts ?: emptyList() else emptyList()
                        }.getOrDefault(emptyList())
                    } else emptyList()
                }

                val collectionParts = collectionDeferred.await()
                episodeDeferred?.awaitAll()

                val tmdbRecs = detailRes.recommendations?.results ?: emptyList()
                val finalCombinedMedia = (collectionParts + tmdbRecs)
                    .distinctBy { it.id }
                    .filter { it.id != null && it.id != tmdbIdStr.toIntOrNull() }

                fetchedRecommendations = finalCombinedMedia.mapNotNull { media ->
                    val recTitle = media.title ?: media.name ?: media.originalTitle ?: return@mapNotNull null
                    val recTmdbId = media.id ?: return@mapNotNull null
                    val rawMediaType = media.mediaType ?: tmdbMediaType
                    val posterType = if (rawMediaType == "tv") "tv" else "movie"
                    val outType = if (rawMediaType == "tv") "series" else "movie"
                    
                    val resolvedPosterUrl = if (customPosterEnabled && customPosterBase.isNotBlank()) {
                        val rawUrl = customPosterBase.trim()
                        if (rawUrl.contains("posterType") || rawUrl.contains("recTmdbId") || rawUrl.contains("{type}") || rawUrl.contains("{tmdb_id}")) {
                            rawUrl.replace("\$posterType", posterType)
                                  .replace("posterType", posterType)
                                  .replace("{type}", posterType)
                                  .replace("\$recTmdbId", recTmdbId.toString())
                                  .replace("recTmdbId", recTmdbId.toString())
                                  .replace("{tmdb_id}", recTmdbId.toString())
                        } else {
                            val cleanBase = rawUrl.trimEnd('/')
                            "$cleanBase/tmdb:$posterType:$recTmdbId.jpg?config=aed081fd-73b1-460e-879d-377c5658691c"
                        }
                    } else {
                        val path = media.poster_path
                        if (!path.isNullOrBlank()) "https://image.tmdb.org/t/p/w500$path" else TRANSPARENT_PIXEL
                    }
                    
                    val thinData = LoadData(id = "tmdb:${media.id}", type = outType)
                    val thinDataJson = gson.toJson(thinData)
                    
                    provider.newMovieSearchResponse(recTitle, thinDataJson, if (outType == "movie") TvType.Movie else TvType.TvSeries) { 
                        this.posterUrl = resolvedPosterUrl 
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
                extractedEnglishTitle
            )
        }

        private fun processEpisodes(provider: StremioC, finalImdbId: String?, episodeTmdbMeta: Map<String, TmdbEpisode>, targetVideos: List<Video>?, type: String?, showTitle: String?, tmdbId: Int?, isAnime: Boolean, isBollywood: Boolean, isAsian: Boolean, isCartoon: Boolean, originalTitle: String?, englishTitle: String?, aniListId: Int?, malId: Int?, kitsuId: String?): List<Episode> {
            val videos = targetVideos ?: emptyList()
            val sortedVideos = videos.groupBy { Pair(it.seasonNumber ?: 0, it.episode ?: it.number ?: 0) }.flatMap { (_, group) -> group.sortedBy { it.title ?: it.name ?: "" } }
            val maxRegularSeason = sortedVideos.mapNotNull { it.seasonNumber }.filter { it > 0 }.maxOrNull() ?: 0
            val targetSeasonForZero = maxRegularSeason + 1
            
            val maxEpisodePerSeason = mutableMapOf<Int, Int>()
            val zeroEpCounterPerSeason = mutableMapOf<Int, Int>()
            val uniqueEpisodeChecker = mutableSetOf<String>()

            return sortedVideos.map { video ->
                val displaySeason = if ((video.seasonNumber ?: 0) == 0) targetSeasonForZero else (video.seasonNumber ?: 0)
                val originalEpisode = video.episode ?: video.number ?: 0
                
                var displayEpisode = if (originalEpisode == 0) {
                    val currentZeroCount = zeroEpCounterPerSeason.getOrDefault(displaySeason, 0) + 1
                    zeroEpCounterPerSeason[displaySeason] = currentZeroCount
                    maxEpisodePerSeason.getOrDefault(displaySeason, 0) + currentZeroCount
                } else {
                    val currentMax = maxEpisodePerSeason.getOrDefault(displaySeason, 0)
                    if (originalEpisode > currentMax) maxEpisodePerSeason[displaySeason] = originalEpisode
                    originalEpisode
                }
                
                var key = "${displaySeason}_${displayEpisode}"
                if (uniqueEpisodeChecker.contains(key)) {
                    val currentMax = maxEpisodePerSeason.getOrDefault(displaySeason, displayEpisode)
                    displayEpisode = maxOf(currentMax, displayEpisode) + 1
                    key = "${displaySeason}_${displayEpisode}"
                }
                uniqueEpisodeChecker.add(key)
                maxEpisodePerSeason[displaySeason] = maxOf(maxEpisodePerSeason.getOrDefault(displaySeason, 0), displayEpisode)

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
                
                video.toEpisode(provider, type, epImdbId, episodeTmdbMeta, displaySeason, displayEpisode, showTitle, tmdbId, isAnime, isBollywood, isAsian, isCartoon, imdbSeason, imdbEpisode, originalTitle, englishTitle, aniListId, malId, kitsuId)
            }
        }

        suspend fun toLoadResponse(
            provider: StremioC, 
            imdbId: String?, 
            addonDeferred: Deferred<CatalogEntry?>?, 
            preFetchedDetail: TmdbDetailResponse? = null, 
            fetchedAniListId: Int? = null, 
            fetchedMalId: Int? = null, 
            resolvedTmdbId: String? = null,
            kitsuVideos: List<Video>? = null
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

            val findApiDeferred = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    val cacheKey = finalImdbId
                    val cachedFind = tmdbFindCache[cacheKey]
                    if (cachedFind != null && System.currentTimeMillis() - cachedFind.second < METADATA_CACHE_TTL) {
                        cachedFind.first
                    } else {
                        val fetchedFind = runSuspendCatching {
                            val fetchedFindText = StremioC.customSession.get(provider.buildTmdbUrl("/find/$finalImdbId", "external_source" to "imdb_id")).text
                            val findRes = if (fetchedFindText.isNotBlank()) gson.fromJson(fetchedFindText, TmdbFindResponse::class.java) else null
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
            
            if (finalType == "other") {
                finalDescription = if (finalDescription.isNullOrBlank()) originalName else "$finalDescription | $originalName"
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

            val aniZipDeferred = if (isAnimeMedia && finalImdbId != null) {
                async(Dispatchers.IO) {
                    val cacheKey = finalImdbId
                    val cachedZip = aniZipCache[cacheKey]
                    if (cachedZip != null && System.currentTimeMillis() - cachedZip.second < METADATA_CACHE_TTL) {
                        cachedZip.first
                    } else {
                        val fetchedZip = runSuspendCatching {
                            val mappingRes = StremioC.customSession.get("https://api.ani.zip/mappings?imdb_id=$finalImdbId", timeout = 5L).text
                            val node = JsonParser.parseString(mappingRes).asJsonObject
                            val mappings = node.getAsJsonObject("mappings")
                            if (mappings != null) {
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

            if (isAnimeMedia && finalMalId == null && finalImdbId != null) {
                val aniZipResult = aniZipDeferred?.await()
                if (aniZipResult != null) {
                    finalMalId = aniZipResult.first ?: finalMalId
                    finalAniId = aniZipResult.second ?: finalAniId
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

            val hasAnimeTrackerIds = finalMalId != null || finalAniId != null || this@CatalogEntry.kitsuIdExt != null
            val finalMovieType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.AnimeMovie else TvType.Movie
            val finalSeriesType = if (isAnimeMedia && hasAnimeTrackerIds) TvType.Anime else TvType.TvSeries

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "movie", this@CatalogEntry.id ?: "")
                return@coroutineScope provider.newMovieLoadResponse(
                    finalName, metaUrl, finalMovieType, 
                    LoadData(
                        type = finalType, 
                        id = movieId, 
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
                        englishTitle = tmdbData?.fetchedEnglishTitle
                    )
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalImdbRating); year = tmdbData?.fetchedYear
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
                val finalEpisodes = processEpisodes(provider, cleanImdbId, tmdbData?.episodeTmdbMeta ?: emptyMap(), finalVideos, finalType, finalName, cleanTmdbId?.toIntOrNull(), isAnimeMedia, isBollywood, isAsian, isCartoon, tmdbData?.fetchedOriginalTitle, tmdbData?.fetchedEnglishTitle, finalAniId, finalMalId, this@CatalogEntry.kitsuIdExt)
                val metaUrl = provider.getUnifiedMetaUrl(finalType ?: "series", this@CatalogEntry.id ?: "")
                return@coroutineScope provider.newTvSeriesLoadResponse(
                    finalName, metaUrl, finalSeriesType, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalImdbRating); year = tmdbData?.fetchedYear
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
        @SerializedName("id") val id: String? = null,
        @SerializedName("title") val title: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("season") val seasonNumber: Int? = null,
        @SerializedName("number") val number: Int? = null,
        @SerializedName("episode") val episode: Int? = null,
        @SerializedName("thumbnail") val thumbnail: String? = null,
        @SerializedName("overview") val overview: String? = null,
        @SerializedName("description") val description: String? = null,
        @SerializedName("firstAired") val firstAired: String? = null,
        @SerializedName("released") val released: String? = null,
        @SerializedName("tvdb_id") val tvdb_id: Int? = null,
        @SerializedName("imdbSeason") val imdbSeason: Int? = null,
        @SerializedName("imdbEpisode") val imdbEpisode: Int? = null,
        @SerializedName("imdb_id") val imdb_id: String? = null
    ) {
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?, tmdbMetaMap: Map<String, TmdbEpisode>, displaySeason: Int, displayEpisode: Int, showTitle: String?, tmdbId: Int?, isAnime: Boolean, isBollywood: Boolean, isAsian: Boolean, isCartoon: Boolean, imdbSeason: Int?, imdbEpisode: Int?, originalTitle: String?, englishTitle: String?, aniListId: Int?, malId: Int?, kitsuId: String?): Episode {
            val tmdbEp = tmdbMetaMap["${displaySeason}_${displayEpisode}"] ?: tmdbMetaMap["${seasonNumber ?: 0}_${episode ?: number ?: 0}"]
            val finalAirDate = tmdbEp?.airDate?.takeIf { it.isNotBlank() } ?: this@Video.firstAired ?: this@Video.released
            val airedYearInt = finalAirDate?.split("-")?.first()?.toIntOrNull()
            
            return provider.newEpisode(LoadData(
                type = type, 
                id = id, 
                season = displaySeason, 
                episode = displayEpisode, 
                imdbId = imdbId, 
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
                kitsuId = kitsuId
            )) {
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) rawTitle.substringAfter("📄").replace("\n", "").trim() else rawTitle.replace("\n", "").trim()

                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL
                
                var finalEpDesc = (tmdbEp?.overview ?: this@Video.overview ?: this@Video.description)?.takeIf { it != "null" }
                if (type == "other") {
                    finalEpDesc = if (finalEpDesc.isNullOrBlank()) cleanOriginalTitle else "$finalEpDesc | $cleanOriginalTitle"
                }
                this.description = finalEpDesc                
                this.season = displaySeason
                this.episode = displayEpisode

                finalAirDate?.takeIf { it.isNotBlank() }?.let { this.addDate(it) }
            }
        }
    }

    private data class StreamsResponse(@SerializedName("streams") val streams: List<Stream>)    
    private data class Subtitle(@SerializedName("url") val url: String?, @SerializedName("lang") val lang: String?, @SerializedName("lang_code") val lang_code: String?, @SerializedName("id") val id: String?)    
    private data class ProxyHeaders(@SerializedName("request") val request: Map<String, String>?)   
    private data class BehaviorHints(@SerializedName("proxyHeaders") val proxyHeaders: ProxyHeaders?, @SerializedName("headers") val headers: Map<String, String>?)

    private data class Stream(
        @SerializedName("name") val name: String?,
        @SerializedName("title") val title: String?,
        @SerializedName("url") val url: String?,
        @SerializedName("description") val description: String?,
        @SerializedName("ytId") val ytId: String?,
        @SerializedName("externalUrl") val externalUrl: String?,
        @SerializedName("behaviorHints") val behaviorHints: BehaviorHints?,
        @SerializedName("infoHash") val infoHash: String?,
        @SerializedName("sources") val sources: List<String> = emptyList(),
        @SerializedName("subtitles") val subtitles: List<Subtitle> = emptyList()
    ) {
        suspend fun runCallback(provider: StremioC, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
            if (url != null) {
                val lowerUrl = url.lowercase()
                val linkType = when {
                    lowerUrl.contains(".m3u8") || name?.contains("m3u8", true) == true || title?.contains("m3u8", true) == true -> ExtractorLinkType.M3U8
                    lowerUrl.contains(".mpd") -> ExtractorLinkType.DASH
                    lowerUrl.contains(".torrent") -> ExtractorLinkType.TORRENT
                    else -> ExtractorLinkType.VIDEO 
                }

                callback.invoke(newExtractorLink(name ?: "", fixSourceName(name, title), url, linkType) {
                    this.quality = getQuality(listOf(description, title, name))
                    this.headers = behaviorHints?.proxyHeaders?.request ?: behaviorHints?.headers ?: mapOf()
                })
                
                println("[STREMIOSUB] Stream.runCallback matched subtitles list. Count: ${subtitles.size}")
                subtitles.map { sub -> 
                    val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                    val parsedLang = SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang
                    println("[STREMIOSUB] Stream.runCallback invoking subtitleCallback with parsedLang: '$parsedLang', url: '${sub.url}'")
                    subtitleCallback.invoke(newSubtitleFile(parsedLang, sub.url ?: return@map)) 
                }
            }
            if (ytId != null) loadExtractor("https://m.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            if (externalUrl != null) loadExtractor(externalUrl, subtitleCallback, callback)
            if (infoHash != null) callback.invoke(newExtractorLink(name ?: "", title ?: name ?: "", "magnet:?xt=urn:btih:${infoHash}") { this.quality = Qualities.Unknown.value })
        }
    }
}

private data class TraktRelatedIds(@SerializedName("tmdb") val tmdb: Int?)
private data class TraktRelatedItem(@SerializedName("ids") val ids: TraktRelatedIds?)
private data class SimklIds(@SerializedName("tmdb") val tmdb: Int?, @SerializedName("simkl") val simkl: Int?)
private data class SimklRecItem(@SerializedName("type") val type: String?, @SerializedName("ids") val ids: SimklIds?)
private data class SimklRecResponse(@SerializedName("users_recommendations") val users_recommendations: List<SimklRecItem>?)
private data class SimklDetailResponse(@SerializedName("ids") val ids: SimklIds?)

private data class TmdbFindResponse(
    @SerializedName("movie_results") val movie_results: List<TmdbFindResult>? = null,
    @SerializedName("tv_results") val tv_results: List<TmdbFindResult>? = null
)

private data class TmdbFindResult(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("media_type") val media_type: String? = null
)

private data class TmdbCollection(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("poster_path") val poster_path: String? = null,
    @SerializedName("backdrop_path") val backdrop_path: String? = null
)

private data class TmdbCollectionDetail(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("parts") val parts: List<TmdbMedia>? = null
)

private data class TmdbTranslations(@SerializedName("translations") val translations: List<TmdbTranslationItem>?)
private data class TmdbTranslationItem(@SerializedName("iso_3166_1") val iso_3166_1: String?, @SerializedName("iso_639_1") val iso_639_1: String?, @SerializedName("data") val data: TmdbTranslationData?)
private data class TmdbTranslationData(@SerializedName("title") val title: String?, @SerializedName("name") val name: String?, @SerializedName("overview") val overview: String?)

private data class TmdbDetailResponse(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("number_of_seasons") val number_of_seasons: Int? = null,
    @SerializedName("title") val title: String? = null, 
    @SerializedName("name") val name: String? = null, 
    @SerializedName("original_title") val original_title: String? = null, 
    @SerializedName("original_name") val original_name: String? = null, 
    @SerializedName("overview") val overview: String? = null, 
    @SerializedName("release_date") val releaseDate: String? = null, 
    @SerializedName("first_air_date") val firstAirDate: String? = null, 
    @SerializedName("status") val status: String? = null, 
    @SerializedName("poster_path") val posterPath: String? = null, 
    @SerializedName("origin_country") val origin_country: List<String>? = null, 
    @SerializedName("original_language") val original_language: String? = null, 
    @SerializedName("belongs_to_collection") val belongs_to_collection: TmdbCollection? = null, 
    @SerializedName("recommendations") val recommendations: TmdbRecommendations? = null,
    @SerializedName("runtime") val runtime: Int? = null, 
    @SerializedName("release_dates") val release_dates: TmdbReleaseDates? = null, 
    @SerializedName("content_ratings") val content_ratings: TmdbContentRatings? = null, 
    @SerializedName("credits") val credits: TmdbCredits? = null, 
    @SerializedName("images") val images: TmdbImages? = null, 
    @SerializedName("videos") val videos: ResultsTrailer? = null, 
    @SerializedName("external_ids") val external_ids: ExternalIds? = null, 
    @SerializedName("translations") val translations: TmdbTranslations? = null
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

private data class TmdbImages(@SerializedName("logos") val logos: List<TmdbLogo>? = null)
private data class TmdbLogo(@SerializedName("file_path") val file_path: String? = null)
private data class TmdbRecommendations(@SerializedName("results") val results: List<TmdbMedia>? = null)
private data class TmdbMedia(
    @SerializedName("id") val id: Int? = null,
    @SerializedName("name") val name: String? = null,
    @SerializedName("title") val title: String? = null,
    @SerializedName("original_title") val originalTitle: String? = null,
    @SerializedName("media_type") val mediaType: String? = null,
    @SerializedName("poster_path") val poster_path: String? = null,
    @SerializedName("overview") val overview: String? = null,
    @SerializedName("origin_country") val origin_country: List<String>? = null,
    @SerializedName("original_language") val original_language: String? = null,
    @SerializedName("release_date") val releaseDate: String? = null,
    @SerializedName("first_air_date") val firstAirDate: String? = null
)

private data class TmdbReleaseDates(@SerializedName("results") val results: List<TmdbReleaseDateResult>?)
private data class TmdbReleaseDateResult(@SerializedName("iso_3166_1") val iso_3166_1: String?, @SerializedName("release_dates") val release_dates: List<TmdbReleaseDate>?)
private data class TmdbReleaseDate(@SerializedName("certification") val certification: String?)
private data class TmdbContentRatings(@SerializedName("results") val results: List<TmdbContentRatingResult>?)
private data class TmdbContentRatingResult(@SerializedName("iso_3166_1") val iso_3166_1: String?, @SerializedName("rating") val rating: String?)
private data class TmdbCredits(@SerializedName("cast") val cast: List<TmdbCast>? = null, @SerializedName("crew") val crew: List<TmdbCrew>? = null)
private data class TmdbCast(@SerializedName("name") val name: String?, @SerializedName("original_name") val originalName: String?, @SerializedName("character") val character: String?, @SerializedName("profile_path") val profilePath: String?)
private data class TmdbCrew(@SerializedName("name") val name: String?, @SerializedName("original_name") val originalName: String?, @SerializedName("job") val job: String?, @SerializedName("profile_path") val profilePath: String?)
private data class TmdbSeasonDetail(@SerializedName("episodes") val episodes: List<TmdbEpisode>? = null)
private data class TmdbEpisode(@SerializedName("name") val name: String?, @SerializedName("overview") val overview: String?, @SerializedName("episode_number") val episodeNumber: Int?, @SerializedName("air_date") val airDate: String?, @SerializedName("vote_average") val voteAverage: Double?, @SerializedName("runtime") val runtime: Int?)

data class Trailers(@SerializedName("key") val key: String? = null, @SerializedName("type") val type: String? = null, @SerializedName("published_at") val publishedAt: String? = null)
data class ResultsTrailer(@SerializedName("results") val results: ArrayList<Trailers>? = arrayListOf())
data class ExternalIds(@SerializedName("imdb_id") val imdb_id: String? = null, @SerializedName("tvdb_id") val tvdb_id: String? = null)

private fun String.toUniqueKey(): String {
    return this.lowercase().filter { it.isLetter() }
}

private fun String.urlEncodeLocal(): String = try {
    java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
} catch (e: Exception) {
    this
}

private data class FlatTab(val key: String, val displayName: String, val urlPath: String)
