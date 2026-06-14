// 버전 정보: v2.0.0 - 동적 탭 변환, 순서 재배치, 장르 필터링 및 AIO 메타데이터 폴백 통합 코어
package com.hsp1020

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.JsonNode
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

private fun JsonNode?.textOrNull(): String? {
    if (this == null || this.isNull) return null
    return this.asText().takeIf { it.isNotBlank() && it != "null" }
}

private fun JsonNode?.intOrNull(): Int? {
    if (this == null || this.isNull) return null
    return this.asInt()
}

open class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    
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

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "cc9982c4801545a1481d167137ea7b53"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
        private const val TRAKT_CLIENT_ID = "6d8668915ed1953f5023ea090e206facc6261813243f567dea15a9a678783b6d" 
        private const val SIMKL_CLIENT_ID = "f392628a1235f474859905f5453239c57715d9a197a89bd71cac975ddd9c4d39" 
        
        private val IMDB_ID_REGEX = "tt[0-9]+".toRegex()
        private val JSON_ID_EXTRACT_REGEX = "\"id\":\"([^\"]+)\"".toRegex()
        
        val globalPageCache = ConcurrentHashMap<String, Triple<List<SearchResponse>, Int, Long>>()
        val cacheMutexes = ConcurrentHashMap<String, Mutex>()
        const val CACHE_TTL_MS = 3600000L // 1 hour
        
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
        
        @Volatile private var cachedClassLoader: ClassLoader? = null

        val jsonMapper: ObjectMapper by lazy {
            com.fasterxml.jackson.module.kotlin.jacksonObjectMapper()
                .configure(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        }

        private val tmdbDetailsCache = ConcurrentHashMap<String, Pair<TmdbDetailResponse, Long>>()
        private val tmdbSeasonCache = ConcurrentHashMap<String, Pair<TmdbSeasonDetail, Long>>()
        private val kitsuMetaCache = ConcurrentHashMap<String, Pair<String, Long>>()
        private val tmdbFindCache = ConcurrentHashMap<String, Pair<Pair<String, String>?, Long>>()
        private val aniZipCache = ConcurrentHashMap<String, Pair<Pair<Int?, Int?>?, Long>>()
        private const val METADATA_CACHE_TTL = 12 * 60 * 60 * 1000L

        suspend fun getOrFetchKitsu(url: String): String? {
            val now = System.currentTimeMillis()
            kitsuMetaCache[url]?.let { (res, time) ->
                if (now - time < METADATA_CACHE_TTL) return res
            }
            return runSuspendCatching {
                val res = customSession.get(url, timeout = 10L).text
                if (res.isNotBlank()) kitsuMetaCache[url] = Pair(res, now)
                res
            }.getOrNull()
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
            val loader = cachedClassLoader
            if (loader != null) {
                try {
                    val clazz = loader.loadClass(className)
                    return clazz.newInstance() as? MainAPI
                } catch (e: Exception) {}
            }
            try {
                val pmClass = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val pmInstance = pmClass.getField("INSTANCE").get(null)

                var foundLoader: ClassLoader? = null
                for (field in pmClass.declaredFields) {
                    field.isAccessible = true
                    val obj = runSuspendCatching { field.get(pmInstance) }.getOrNull()
                    val items = when (obj) {
                        is Map<*, *> -> obj.values
                        is Collection<*> -> obj
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
                    cachedClassLoader = foundLoader
                    val clazz = foundLoader.loadClass(className)
                    return clazz.newInstance() as? MainAPI
                }
            } catch (e: Exception) {}
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
            globalPageCache.entries.removeIf { now - it.value.third > CACHE_TTL_MS }

            globalPageCache[key]?.let { entry ->
                return Pair(entry.first, entry.second)
            }

            if (cacheMutexes.size > 200) {
                val activeKeys = globalPageCache.keys().asSequence().toSet()
                cacheMutexes.entries.removeIf { (k, mutex) ->
                    !activeKeys.contains(k) && !mutex.isLocked
                }
            }

            val mutex = cacheMutexes.getOrPut(key) { Mutex() }

            return mutex.withLock {
                globalPageCache[key]?.let { entry ->
                    return@withLock Pair(entry.first, entry.second)
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
            this.url
        }
    }

    private fun buildTmdbUrl(endpoint: String, vararg params: Pair<String, String>): String {
        val builder = "$tmdbAPI$endpoint".toHttpUrlOrNull()?.newBuilder() ?: return ""
        builder.addQueryParameter("api_key", apiKey)
        builder.addQueryParameter("language", "ko-KR")
        params.forEach { (key, value) ->
            builder.addQueryParameter(key, value)
        }
        return builder.build().toString()
    }

    private fun extractImdbId(url: String?): String? {
        if (url.isNullOrBlank() || !url.contains("tt")) return null
        return runSuspendCatching { IMDB_ID_REGEX.find(url)?.value }.getOrNull()
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

        val res = runSuspendCatching { 
            val text = customSession.get(currentUrl, timeout = 120L).text
            if (text.isNotBlank()) jsonMapper.readValue(text, Manifest::class.java) else null
        }.getOrNull()

        if (res != null && res.catalogs.isNotEmpty()) {
            cachedManifest = res
            lastManifestUrl = currentUrl
            lastCacheTime = now
            catalogSentIds.clear()
            catalogSkipState.clear()
        }       
        return res ?: cachedManifest
    }

    // ==========================================
    // [v2.0.0] 설정값 동적 탭 구성 (mainPage)
    // ==========================================
    override val mainPage: List<MainPageData>
        get() {
            val manifest = runBlocking {
                try { getManifest() } catch (e: Exception) { null }
            } ?: return emptyList()

            val context = CloudStreamApp.context ?: return emptyList()
            val prefs = context.getSharedPreferences("StremioX", Context.MODE_PRIVATE)

            val rawCatalogs = manifest.catalogs ?: emptyList()
            
            // 정렬 정보 로드
            val orderKey = "catalog_order_$name"
            val orderJson = prefs.getString(orderKey, null)
            val orderedKeys = mutableListOf<String>()
            if (orderJson != null) {
                try {
                    val arr = org.json.JSONArray(orderJson)
                    for (i in 0 until arr.length()) {
                        orderedKeys.add(arr.getString(i))
                    }
                } catch (e: Exception) {}
            }

            val sortedCatalogs = rawCatalogs.sortedBy { cat ->
                val key = "${cat.id}_${cat.type}"
                val idx = orderedKeys.indexOf(key)
                if (idx != -1) idx else Int.MAX_VALUE
            }

            return mainPageOf(
                *buildList {
                    sortedCatalogs.forEach { catalog ->
                        val catalogKey = "${catalog.id}_${catalog.type}"
                        val isCatalogEnabled = prefs.getBoolean("catalog_enabled_${name}_$catalogKey", true)
                        if (!isCatalogEnabled) return@forEach

                        val catalogName = catalog.name ?: catalog.id
                        val displayType = catalog.type?.replaceFirstChar { it.uppercase() } ?: ""
                        val requiresGenre = catalog.extra?.any { it.name == "genre" && it.isRequired == true } == true

                        if (!requiresGenre) {
                            add("/catalog/${catalog.type}/${catalog.id}/skip=###.json" to "$catalogName - $displayType")
                        }

                        val genres = catalog.genres ?: emptyList()
                        genres.forEach { genre ->
                            val isGenreEnabled = prefs.getBoolean("genre_enabled_${name}_${catalogKey}_$genre", true)
                            if (isGenreEnabled) {
                                add("/catalog/${catalog.type}/${catalog.id}/genre=${genre.urlEncodeLocal()}&skip=###.json" to "$genre - $displayType")
                            }
                        }
                    }
                }.toTypedArray()
            )
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        var isNewRequest = false
        
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                catalogSentIds.remove(request.name)
                activePageRequests.clear()
                catalogSkipState.remove(request.name)
            }
            
            activePageRequests.getOrPut(page) {
                isNewRequest = true
                async { fetchTabCatalogData(request) }
            }
        }
        
        return@coroutineScope try {
            val response = deferred.await()
            if (!isNewRequest && page > 1) {
                newHomePageResponse(emptyList(), hasNext = true)
            } else {
                response
            }
        } finally {
            pageMutex.withLock {
                activePageRequests.remove(page)
            }
        }
    }

    private suspend fun fetchTabCatalogData(request: MainPageRequest): HomePageResponse {
        val currentSkip = catalogSkipState[request.name] ?: 0
        val targetPath = request.data.replace("###", currentSkip.toString())
        val targetUrl = buildUrl(targetPath)
        val cacheKey = "${this@StremioC.name}_${request.name}_$currentSkip"

        val resultPair = getOrFetchCachedPage(cacheKey) {
            val jsonText = customSession.get(targetUrl).text
            val response = try {
                jsonMapper.readValue(jsonText, CatalogResponse::class.java)
            } catch (e: Exception) { null }

            val metas = response?.metas ?: emptyList()
            val mappedItems = metas.map { it.toSearchResponse(this@StremioC) }
            Pair(mappedItems, metas.size)
        }

        if (resultPair != null) {
            catalogSkipState[request.name] = currentSkip + resultPair.second
            
            var seenForThisCatalog = catalogSentIds[request.name]
            if (seenForThisCatalog == null) {
                synchronized(catalogSentIds) {
                    seenForThisCatalog = catalogSentIds.getOrPut(request.name) { 
                        lruSet(500) 
                    }
                }
            }
            
            val filteredItems = resultPair.first.filter { item -> seenForThisCatalog!!.add(item.extractPureId()) }
            return newHomePageResponse(listOf(HomePageList(request.name, filteredItems)), hasNext = resultPair.second > 0)
        }

        return newHomePageResponse(emptyList(), hasNext = false)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val supportedCatalogs = manifest?.catalogs?.filter { it.supportsSearch() } ?: emptyList()
        
        val searchResults = coroutineScope {
            supportedCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try { catalog.search(query, this@StremioC) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
                }
            }.awaitAll().flatten()
        }

        val seenIds = mutableSetOf<String>()
        return searchResults.filter { item ->
            val extractedId = item.extractPureId()
            if (seenIds.contains(extractedId)) false else { seenIds.add(extractedId); true }
        }
    }

    // ==========================================
    // [v2.0.0] 모든 경우(All cases)에 지정된 AIO Metadata 주소로 직접 연동하여 로드 (폴백 구현)
    // ==========================================
    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val parsed = parseIdAndType(url)
        if (parsed != null) {
            val (id, type) = parsed
            // 영화나 시리즈에 상관없이 규격 외 ID나 매치 실패 시 로드를 위해 항상 AIO Metadata 프록시 경로로 연동
            val isNative = id.startsWith("tt") || id.startsWith("kitsu:") || id.startsWith("mal:")
            if (!isNative) {
                val aioUrl = "https://aiometadata.elfhosted.com/stremio/b345b590-872c-4e13-bc4f-852f4e70c9ef/meta/$type/${id.urlEncodeLocal()}.json"
                return@coroutineScope loadOriginal(aioUrl)
            }
        }

        try {
            loadOriginal(url)
        } catch (e: Exception) {
            if (parsed != null) {
                val (id, type) = parsed
                val aioUrl = "https://aiometadata.elfhosted.com/stremio/b345b590-872c-4e13-bc4f-852f4e70c9ef/meta/$type/${id.urlEncodeLocal()}.json"
                loadOriginal(aioUrl)
            } else {
                throw e
            }
        }
    }

    private suspend fun loadOriginal(url: String): LoadResponse = coroutineScope {
        val res: CatalogEntry = if (url.startsWith("{")) {
            jsonMapper.readValue(url, CatalogEntry::class.java)
        } else {
            val responseText = customSession.get(url).text
            try {
                val rootNode = jsonMapper.readTree(responseText)
                val metaNode = rootNode.get("meta") ?: rootNode
                jsonMapper.treeToValue(metaNode, CatalogEntry::class.java) 
                    ?: throw IllegalStateException("Failed to bind JSON to CatalogEntry")
            } catch (e: Exception) {
                throw IllegalStateException("Metadata not found or parsing failed: $url", e)
            }
        }
        
        val normalizedId = try { normalizeId(res.id) } catch (e: Exception) { res.id }
        val encodedId = normalizedId.urlEncode()

        val isKitsuOrMal = res.id.startsWith("kitsu:") || res.id.startsWith("mal:")
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
                    val root = jsonMapper.readTree(kitsuRes)
                    val metaObj = root.get("meta")
                    if (metaObj != null) {
                        resolvedImdbId = metaObj.get("imdb_id").textOrNull()?.takeIf { it.startsWith("tt") }
                        resolvedTmdbId = metaObj.get("tmdb_id").textOrNull() 
                            ?: metaObj.get("moviedb_id").intOrNull()?.takeIf { it > 0 }?.toString()
                        
                        fetchedAniListId = metaObj.get("anilist_id").intOrNull()
                        fetchedMalId = metaObj.get("mal_id").intOrNull()

                        metaObj.get("videos")?.let { arr ->
                            if (arr.isArray) {
                                val vids = mutableListOf<Video>()
                                for (i in 0 until arr.size()) {
                                    val v = arr.get(i)
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
                                        }
                                    }
                                }
                                if (vids.isNotEmpty()) kitsuVideos = vids
                            }
                        }
                    }
                }
            }
        }

        val primaryMdbId = resolvedTmdbId ?: resolvedImdbId ?: when {
            res.id.startsWith("tt") -> res.id
            res.id.startsWith("tmdb:") -> res.id.removePrefix("tmdb:")
            res.moviedb_id != null -> res.moviedb_id.toString()
            res.tmdbIdExt != null -> res.tmdbIdExt
            else -> null
        }
        
        val primaryMdbIsTmdb = (primaryMdbId == resolvedTmdbId) || (primaryMdbId?.startsWith("tt") == false)

        val mdbDeferred: Deferred<List<String>>? = primaryMdbId?.let { id ->
            async(Dispatchers.IO) {
                runSuspendCatching { fetchMdbListRatings(id, primaryMdbIsTmdb) }.getOrDefault(emptyList())
            }
        }

        val addonDeferred = async(Dispatchers.IO) {
            runSuspendCatching {
                val text = customSession.get(buildUrl("/meta/${res.type}/$encodedId.json")).text
                val response = if (text.isNotBlank()) jsonMapper.readValue(text, CatalogResponse::class.java) else null
                response?.meta ?: response?.metas?.firstOrNull { it.id == res.id } ?: response?.metas?.firstOrNull()
            }.getOrNull()
        }

        val tmdbIdFromRes = res.moviedb_id?.toString() ?: res.tmdbIdExt
        val targetTmdbId = resolvedTmdbId ?: if (res.id.startsWith("tmdb:")) res.id.removePrefix("tmdb:") else tmdbIdFromRes
        
        val tmdbDeferred = targetTmdbId?.let { id ->
            async(Dispatchers.IO) {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val detailAppend = if (mediaType == "movie") "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = buildTmdbUrl("/$mediaType/$id", "append_to_response" to detailAppend)
                runSuspendCatching { 
                    val text = customSession.get(detailUrl).text
                    if (text.isNotBlank()) jsonMapper.readValue(text, TmdbDetailResponse::class.java) else null
                }.getOrNull()
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
        }
        
        return@coroutineScope loadRes
    }

    private fun parseIdAndType(url: String): Pair<String, String>? {
        return try {
            if (url.startsWith("{")) {
                val root = jsonMapper.readTree(url)
                val id = root.get("id")?.asText() ?: root.get("imdbId")?.asText()
                val type = root.get("type")?.asText() ?: "movie"
                if (id != null) Pair(id, type) else null
            } else {
                val cleanUrl = url.substringBefore("?")
                if (cleanUrl.contains("/meta/")) {
                    val parts = cleanUrl.substringAfter("/meta/").split("/")
                    if (parts.size >= 2) {
                        val type = parts[0]
                        val id = parts[1].removeSuffix(".json")
                        Pair(id, type)
                    } else null
                } else null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun String.urlEncodeLocal(): String = try {
        java.net.URLEncoder.encode(this, "UTF-8").replace("+", "%20")
    } catch (e: Exception) {
        this
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = try { jsonMapper.readValue(data, LoadData::class.java) } catch (e: Exception) { null } ?: return false
        
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
                try {
                    val prefs = CloudStreamApp.context?.let { androidx.preference.PreferenceManager.getDefaultSharedPreferences(it) }
                    val v = prefs?.getString("stremio_addons", "") ?: ""
                    println("[STREMIOSUB] Attempt 2 (default SharedPreferences) -> length: ${v.length}, data: $v")
                    if (v.startsWith("\"") && v.endsWith("\"") && v.length >= 2) v.substring(1, v.length - 1) else v
                } catch (e: Exception) {
                    println("[STREMIOSUB] Attempt 2 (default SharedPreferences) Failed: ${e.message}")
                    ""
                }
            }
            
            val hosts = raw.lines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { URI(it.split("|").getOrElse(1) { "" }).host }.getOrNull() }
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
                subtitleCallback.invoke(sub)
            }
        }

        runAllAsync(
            {
                runSuspendCatching {
                    val url = buildUrl("/stream/$targetType/$encodedPrimaryId.json")
                    println("[STREMIOSUB] Requesting primary streams from url: $url")
                    val text = customSession.get(url, timeout = 120L).text
                    val res = if (text.isNotBlank()) jsonMapper.readValue(text, StreamsResponse::class.java) else null
                    println("[STREMIOSUB] Primary streams count returned: ${res?.streams?.size ?: 0}")
                    res?.streams?.forEach { stream -> 
                        stream.runCallback(this@StremioC, subtitleInterceptor, wrappedCallback) 
                    }
                }
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
                } catch (e: Exception) {}
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

            val payloadJson = jsonMapper.writeValueAsString(payload)

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

            val payloadJson = jsonMapper.writeValueAsString(payload)

            withTimeoutOrNull(25000L) {
                streamPlayProvider.loadLinks(payloadJson, isCasting, subtitleCallback, callback)
            }
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
        @JsonProperty("kitsuId") val kitsuId: String? = null
    )
    
    private data class Manifest(val catalogs: List<Catalog>)
    
    private data class Extra(@JsonProperty("name") val name: String?, @JsonProperty("isRequired") val isRequired: Boolean? = false)

    private data class Catalog(
        var name: String?, 
        val id: String, 
        val type: String?, 
        val types: MutableList<String> = mutableListOf(), 
        @JsonProperty("extra") val extra: List<Extra>? = null, 
        @JsonProperty("extraSupported") val extraSupported: List<String>? = null,
        @JsonProperty("genres") val genres: List<String>? = null // 🔥 추가
    ) {
        init { if (type != null) types.add(type) }

        fun isSearchRequired(): Boolean = extra?.any { it.name == "search" && it.isRequired == true } == true
        fun supportsSearch(): Boolean = extra?.any { it.name == "search" } == true || extraSupported?.contains("search") == true

        private suspend fun fetchMetas(provider: StremioC, path: String): List<CatalogEntry> {
            return try {
                val text = StremioC.customSession.get(provider.buildUrl(path), timeout = 120L).text
                if (text.isNotBlank()) jsonMapper.readValue(text, CatalogResponse::class.java).metas ?: emptyList() else emptyList()
            } catch (e: Throwable) { emptyList() }
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetasWithType = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        val path = "/catalog/${type}/${id}/search=${query.urlEncode()}.json"
                        fetchMetas(provider, path).map { it to type }
                    }
                }.awaitAll().flatten()
            }
            return allMetasWithType.distinctBy { it.first.id }.map { (meta, type) ->
                meta.toSearchResponse(provider, catalogName = name ?: id, typeName = type)
            }
        }

        suspend fun toHomePageList(provider: StremioC, skip: Int): Pair<HomePageList, Int> {
            val allMetas = coroutineScope {
                types.map { type ->
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
    
    private data class TrailerStream(@JsonProperty("ytId") val ytId: String?, @JsonProperty("title") val title: String? = null)
    
    private data class Link(@JsonProperty("name") val name: String? = null, @JsonProperty("category") val category: String? = null, @JsonProperty("url") val url: String? = null)

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
        @JsonProperty("name") val name: String? = null, 
        @JsonProperty("id") val id: String, 
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
        @JsonProperty("genres") val genres: List<String> = emptyList(),
        @JsonProperty("cast") val cast: List<String> = emptyList(), 
        @JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream> = emptyList(), 
        @JsonProperty("links") val links: List<Link> = emptyList()
    ) {

        fun toSearchResponse(
            provider: StremioC, 
            catalogName: String? = null, 
            typeName: String? = null
        ): SearchResponse {
            val targetType = this.type ?: typeName ?: "movie"
            val metaUrl = "${provider.mainUrl.substringBefore("?")}/meta/$targetType/${this.id}.json"

            val baseName = if (!name.isNullOrBlank()) {
                name
            } else {
                id
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
                val fetched = if (text.isNotBlank()) jsonMapper.readValue(text, TmdbDetailResponse::class.java) else null
                if (fetched != null) {
                    tmdbDetailsCache[cacheKey] = Pair(fetched, System.currentTimeMillis())
                }
                fetched
            }
            
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null)
            }

            val episodeTmdbMeta = ConcurrentHashMap<String, TmdbEpisode>()

            coroutineScope {
                val shouldFetchEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType == "other" && !isMovie)
                
                val episodeDeferred = if (shouldFetchEpisodes) {
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
                                            val fetchedSeason = if (fetchedSeasonText.isNotBlank()) jsonMapper.readValue(fetchedSeasonText, TmdbSeasonDetail::class.java) else null
                                            if (fetchedSeason != null) {
                                                tmdbSeasonCache[seasonCacheKey] = Pair(fetchedSeason, System.currentTimeMillis())
                                            }
                                            fetchedSeason
                                        }
                                        seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                    }
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
                            if (colText.isNotBlank()) jsonMapper.readValue(colText, TmdbCollectionDetail::class.java).parts ?: emptyList() else emptyList()
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
                    val customPosterUrl = "https://extendedratings.com/poster/tmdb:$posterType:$recTmdbId.jpg?config=aed081fd-73b1-460e-879d-377c5658691c"
                    
                    val thinData = LoadData(id = "tmdb:${media.id}", type = outType)
                    val thinDataJson = jsonMapper.writeValueAsString(thinData)
                    
                    provider.newMovieSearchResponse(recTitle, thinDataJson, if (outType == "movie") TvType.Movie else TvType.TvSeries) { 
                        this.posterUrl = customPosterUrl 
                    }
                }.take(50)
            }

            val fetchedAgeRating = if (isMovie) {
                detailRes.release_dates?.results?.firstOrNull { it.iso_3166_1 == "KR" }?.release_dates?.firstOrNull { !it.certification.isNullOrEmpty() }?.certification
            } else {
                detailRes.content_ratings?.results?.firstOrNull { it.iso_3166_1 == "KR" }?.rating
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

            var finalImdbId = if (this@CatalogEntry.id.startsWith("tt")) this@CatalogEntry.id 
                              else imdbId?.takeIf { it.startsWith("tt") || it.startsWith("kitsu:") || it.startsWith("mal:") }
            
            if (finalImdbId == null && detailedEntry.links.isNotEmpty()) {
                finalImdbId = detailedEntry.links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }
            if (finalImdbId == null) {
                finalImdbId = provider.extractImdbId(detailedEntry.logo) ?: provider.extractImdbId(detailedEntry.poster) ?: provider.extractImdbId(detailedEntry.background)
            }

            var tmdbIdStr: String? = resolvedTmdbId ?: if (this@CatalogEntry.id.startsWith("tmdb:")) this@CatalogEntry.id.removePrefix("tmdb:") else this@CatalogEntry.moviedb_id?.toString() ?: this@CatalogEntry.tmdbIdExt ?: detailedEntry.tmdbIdExt

            val findApiDeferred = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    val cacheKey = finalImdbId
                    val cachedFind = tmdbFindCache[cacheKey]
                    if (cachedFind != null && System.currentTimeMillis() - cachedFind.second < METADATA_CACHE_TTL) {
                        cachedFind.first
                    } else {
                        val fetchedFind = runSuspendCatching {
                            val fetchedFindText = StremioC.customSession.get(provider.buildTmdbUrl("/find/$finalImdbId", "external_source" to "imdb_id")).text
                            val findRes = if (fetchedFindText.isNotBlank()) jsonMapper.readValue(fetchedFindText, TmdbFindResponse::class.java) else null
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
                } catch (e: Exception) {}
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

            val fallbackMovieId = this@CatalogEntry.kitsuIdExt?.let { "kitsu:$it" } ?: this@CatalogEntry.id
            if (tmdbData != null && finalVideos.isNullOrEmpty() && tmdbData.episodeTmdbMeta.isNotEmpty()) {
                val generatedVideos = tmdbData.episodeTmdbMeta.mapNotNull { (key, tmdbEp) ->
                    val parts = key.split("_")
                    if (parts.size == 2) {
                        val s = parts[0].toIntOrNull() ?: return@mapNotNull null
                        val e = parts[1].toIntOrNull() ?: return@mapNotNull null
                        Video(
                            id = fallbackMovieId, 
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

            val movieId = if (!finalVideos.isNullOrEmpty() && !finalVideos[0].id.isNullOrBlank()) finalVideos[0].id else fallbackMovieId

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
                            val node = jsonMapper.readTree(mappingRes)
                            val mappings = node.get("mappings")
                            if (mappings != null) {
                                val mal = mappings.get("mal_id").intOrNull()?.takeIf { it > 0 }
                                val ani = mappings.get("anilist_id").intOrNull()?.takeIf { it > 0 }
                                Pair(mal, ani)
                            } else null
                        }.getOrNull()
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
                return@coroutineScope provider.newMovieLoadResponse(
                    finalName, "${provider.mainUrl}/meta/${finalType ?: "movie"}/${this@CatalogEntry.id}.json", finalMovieType, 
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
                }
            } else {
                val finalEpisodes = processEpisodes(provider, cleanImdbId, tmdbData?.episodeTmdbMeta ?: emptyMap(), finalVideos, finalType, finalName, cleanTmdbId?.toIntOrNull(), isAnimeMedia, isBollywood, isAsian, isCartoon, tmdbData?.fetchedOriginalTitle, tmdbData?.fetchedEnglishTitle, finalAniId, finalMalId, this@CatalogEntry.kitsuIdExt)
                return@coroutineScope provider.newTvSeriesLoadResponse(
                    finalName, "${provider.mainUrl}/meta/${finalType ?: "series"}/${this@CatalogEntry.id}.json", finalSeriesType, finalEpisodes
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

    private data class StreamsResponse(@JsonProperty("streams") val streams: List<Stream>)    
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

private data class TraktRelatedIds(@JsonProperty("tmdb") val tmdb: Int?)
private data class TraktRelatedItem(@JsonProperty("ids") val ids: TraktRelatedIds?)
private data class SimklIds(@JsonProperty("tmdb") val tmdb: Int?, @JsonProperty("simkl") val simkl: Int?)
private data class SimklRecItem(@JsonProperty("type") val type: String?, @JsonProperty("ids") val ids: SimklIds?)
private data class SimklRecResponse(@JsonProperty("users_recommendations") val users_recommendations: List<SimklRecItem>?)
private data class SimklDetailResponse(@JsonProperty("ids") val ids: SimklIds?)

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
    @JsonProperty("translations") val translations: TmdbTranslations? = null
) {
    fun toTmdbMedia(mediaType: String): TmdbMedia {
        return TmdbMedia(
            id = this.id,
            name = this.name ?: this.title,
            title = this.title ?: this.name,
            originalTitle = this.original_title ?: this.original_name,
            mediaType = mediaType,
            posterPath = this.posterPath,
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
    @JsonProperty("poster_path") val posterPath: String? = null,
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
