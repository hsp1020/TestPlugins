// v1.169 (Performance & Thread-Safety Refactored: 모든 println I/O 블로킹 제거, CineStream 리플렉션 락/루프 최적화, 보일러플레이트 압축)
package com.hsp1020

import android.content.Context
import com.fasterxml.jackson.annotation.JsonProperty
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
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
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
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Protocol
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

inline fun <T> runSuspendCatching(block: () -> T): Result<T> {
    return try {
        Result.success(block())
    } catch (c: CancellationException) {
        throw c 
    } catch (e: Exception) {
        Result.failure(e)
    }
}

private fun String.urlEncode(): String = try { URLEncoder.encode(this, "UTF-8").replace("+", "%20") } catch (e: Exception) { this }

private fun String.cleanBaseUrl(): String {
    return this.substringBefore("?").replace("/manifest.json", "").trimEnd('/')
}

private inline fun <T> lruSet(maxSize: Int): MutableSet<T> {
    return Collections.newSetFromMap(object : java.util.LinkedHashMap<T, Boolean>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<T, Boolean>): Boolean {
            return size > maxSize
        }
    })
}

class StremioC(override var mainUrl: String, override var name: String) : MainAPI() {
    override val supportedTypes = setOf(TvType.Others)
    override val hasMainPage = true
     
    private var cachedManifest: Manifest? = null
    private var lastManifestUrl: String = ""
    private var lastCacheTime: Long = 0
    
    private val catalogSentIds = ConcurrentHashMap<String, MutableSet<String>>()
    private val catalogSkipState = ConcurrentHashMap<String, Int>()
    
    private val pageMutex = Mutex()
    private val activePageRequests = mutableMapOf<Int, Deferred<HomePageResponse>>()

    companion object {
        private const val tmdbAPI = "https://api.themoviedb.org/3"
        private const val apiKey = "cc9982c4801545a1481d167137ea7b53"
        private const val TRANSPARENT_PIXEL = "https://upload.wikimedia.org/wikipedia/commons/c/ca/1x1.png"
        private const val TRAKT_CLIENT_ID = "6d8668915ed1953f5023ea090e206facc6261813243f567dea15a9a678783b6d" 
        private const val SIMKL_CLIENT_ID = "f392628a1235f474859905f5453239c57715d9a197a89bd71cac975ddd9c4d39" 
        
        private val IMDB_ID_REGEX = "tt[0-9]+".toRegex()
        private val JSON_ID_EXTRACT_REGEX = "\"id\":\"([^\"]+)\"".toRegex()
        
        private val globalPageCache = ConcurrentHashMap<String, Triple<List<SearchResponse>, Int, Long>>()
        private val cacheMutexes = ConcurrentHashMap<String, Mutex>()
        private val CACHE_TTL_MS = TimeUnit.HOURS.toMillis(1)
        
        private var cachedCineStreamProvider: MainAPI? = null
        private var hasSearchedCineStream = false
        private var hasSyncedCineStreamSettings = false
        
        @Volatile private var cachedCineClassLoader: ClassLoader? = null

        val networkSemaphore = Semaphore(20)

        val customSession by lazy {
            val newClient = app.baseClient.newBuilder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .dispatcher(Dispatcher().apply {
                    maxRequests = 100
                    maxRequestsPerHost = 100
                })
                .connectionPool(ConnectionPool(30, 1, TimeUnit.MINUTES))
                .build()
                
            Requests(newClient).apply {
                this.responseParser = app.responseParser
                this.defaultHeaders = app.defaultHeaders
            }
        }

        @Synchronized
        fun getCineStreamProvider(forceSearch: Boolean = false): MainAPI? {
            if (cachedCineStreamProvider != null) return cachedCineStreamProvider
            if (!forceSearch && hasSearchedCineStream) return null

            hasSearchedCineStream = true

            var provider = APIHolder.allProviders.find {
                it.name.equals("CineStream", ignoreCase = true)
            }

            if (provider != null) {
                cachedCineStreamProvider = provider
                return provider
            }

            try {
                val targetClassLoader = cachedCineClassLoader ?: run {
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
                                if (pName.contains("megix", true) || pName.contains("CineStream", true)) {
                                    foundLoader = plugin!!.javaClass.classLoader
                                    break
                                }
                            }
                        }
                        if (foundLoader != null) break
                    }
                    foundLoader
                }

                if (targetClassLoader != null) {
                    cachedCineClassLoader = targetClassLoader
                    val clazz = targetClassLoader.loadClass("com.megix.CineStreamProvider")
                    provider = clazz.newInstance() as? MainAPI
                }
            } catch (e: Exception) {
                cachedCineClassLoader = null
            }

            cachedCineStreamProvider = provider
            return provider
        }
        
        suspend fun getOrFetchCachedPage(
            key: String, 
            fetchBlock: suspend () -> Pair<List<SearchResponse>, Int>?
        ): Pair<List<SearchResponse>, Int>? {
            globalPageCache[key]?.let { entry ->
                if (System.currentTimeMillis() - entry.third < CACHE_TTL_MS) {
                    return Pair(entry.first, entry.second)
                }
            }

            val mutex = cacheMutexes.getOrPut(key) { Mutex() }
            return mutex.withLock {
                globalPageCache[key]?.let { entry ->
                    if (System.currentTimeMillis() - entry.third < CACHE_TTL_MS) {
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

    private fun getValidStremioAddons(): List<CustomSite> {
        val sites = CloudStreamApp.getKey<Array<CustomSite>>(USER_PROVIDER_API)?.toList() ?: emptyList()
        return sites.filter { it.parentJavaClass == "StremioX" || it.parentJavaClass == "StremioC" }
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
    
    private fun mergeOverview(original: String?, newOverview: String?): String? {
        return if (!newOverview.isNullOrBlank()) newOverview else original
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

        val res = runSuspendCatching { customSession.get(currentUrl, timeout = 120L).parsedSafe<Manifest>() }.getOrNull()

        if (res != null && res.catalogs.isNotEmpty()) {
            cachedManifest = res
            lastManifestUrl = currentUrl
            lastCacheTime = now
            catalogSentIds.clear()
            catalogSkipState.clear()
        }      
        return res ?: cachedManifest
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse = coroutineScope {
        var isNewRequest = false
        
        val deferred = pageMutex.withLock {
            if (page <= 1) {
                catalogSentIds.clear()
                activePageRequests.clear()
                val now = System.currentTimeMillis()
                globalPageCache.entries.removeIf { now - it.value.third > CACHE_TTL_MS }
                catalogSkipState.clear()
            }
            
            activePageRequests.getOrPut(page) {
                isNewRequest = true
                async { fetchMainPageData(page, request) }
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

    private suspend fun fetchMainPageData(page: Int, request: MainPageRequest): HomePageResponse {
        mainUrl = mainUrl.fixSourceUrl()
        val manifest = getManifest()
        val targetCatalogs = manifest?.catalogs?.filter { !it.isSearchRequired() } ?: emptyList()
        val lists = mutableListOf<HomePageList>()
        
        val chunkResults = coroutineScope {
            targetCatalogs.map { catalog ->
                async(Dispatchers.IO) {
                    try {
                        val catalogKey = "${catalog.id}-${catalog.type}"
                        val currentSkip = catalogSkipState[catalogKey] ?: 0
                        val cacheKey = "${this@StremioC.name}_${catalogKey}_$currentSkip"
                        
                        val resultPair = getOrFetchCachedPage(cacheKey) {
                            val rawResult = catalog.toHomePageList(provider = this@StremioC, skip = currentSkip)
                            Pair(rawResult.first.list, rawResult.second)
                        }
                        
                        if (resultPair != null) {
                            val displayType = catalog.type?.replaceFirstChar { it.uppercase() } ?: ""
                            catalogSkipState[catalogKey] = currentSkip + resultPair.second
                            val rowName = "${catalog.name ?: catalog.id} - $displayType"
                            
                            val seenForThisCatalog = catalogSentIds.getOrPut(catalogKey) { 
                                Collections.synchronizedSet(lruSet<String>(500))
                            }
                            val filteredItems = resultPair.first.filter { item -> seenForThisCatalog.add(item.extractPureId()) }
                            
                            HomePageList(rowName, filteredItems)
                        } else null
                    } catch (e: Exception) { null }
                }
            }.awaitAll()
        }.filterNotNull().filter { it.list.isNotEmpty() }
        
        lists.addAll(chunkResults)
        return newHomePageResponse(lists, hasNext = true)
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

    override suspend fun load(url: String): LoadResponse = coroutineScope {
        val res: CatalogEntry = if (url.startsWith("{")) {
            parseJson(url)
        } else {
            val responseText = customSession.get(url).text
            try {
                parseJson<CatalogResponse>(responseText).meta ?: throw Exception()
            } catch (e: Exception) {
                JSONObject(responseText).optJSONObject("meta")?.toString()?.let { parseJson(it) }
                    ?: throw IllegalStateException("유효한 메타데이터를 찾을 수 없습니다: $url")
            }
        }
        
        val normalizedId = try { normalizeId(res.id) } catch (e: Exception) { res.id }
        val encodedId = normalizedId.urlEncode()

        // [MDBList 병렬 호출 및 명시적 타입 선언]
        var mdbEarlyId: String? = null
        var isMdbEarlyTmdb = false
        if (res.id.startsWith("tt")) {
            mdbEarlyId = res.id
        } else if (res.id.startsWith("tmdb:")) {
            mdbEarlyId = res.id.removePrefix("tmdb:")
            isMdbEarlyTmdb = true
        } else if (res.moviedb_id != null) {
            mdbEarlyId = res.moviedb_id.toString()
            isMdbEarlyTmdb = true
        } else if (res.tmdbIdExt != null) {
            mdbEarlyId = res.tmdbIdExt
            isMdbEarlyTmdb = true
        }

        // 컴파일러 추론을 돕기 위한 명시적 타입 선언 (Deferred<List<String>>?)
        val mdbDeferred: Deferred<List<String>>? = mdbEarlyId?.let { id ->
            async(Dispatchers.IO) { fetchMdbListRatings(id, isMdbEarlyTmdb) }
        }

        val addonDeferred = async(Dispatchers.IO) {
            runSuspendCatching {
                val response = customSession.get(buildUrl("/meta/${res.type}/$encodedId.json")).parsedSafe<CatalogResponse>()
                response?.meta ?: response?.metas?.firstOrNull { it.id == res.id } ?: response?.metas?.firstOrNull()
            }.getOrNull()
        }

        val tmdbIdFromRes = res.moviedb_id?.toString() ?: res.tmdbIdExt
        val targetTmdbId = if (res.id.startsWith("tmdb:")) res.id.removePrefix("tmdb:") else tmdbIdFromRes
        
        val tmdbDeferred = targetTmdbId?.let { id ->
            async(Dispatchers.IO) {
                val mediaType = if (res.type == "movie") "movie" else "tv"
                val detailAppend = if (mediaType == "movie") "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = buildTmdbUrl("/$mediaType/$id", "append_to_response" to detailAppend)
                runSuspendCatching { customSession.get(detailUrl).parsedSafe<TmdbDetailResponse>() }.getOrNull()
            }
        }

        val kitsuDeferred = if (res.id.startsWith("kitsu:") || res.id.startsWith("mal:")) {
            async(Dispatchers.IO) {
                runSuspendCatching {
                    val kitsuRes = customSession.get("https://anime-kitsu.strem.fun/meta/${res.type}/${res.id}.json", timeout = 15L).parsedSafe<KitsuMetaResponse>()
                    kitsuRes?.meta
                }.getOrNull()
            }
        } else null

        val preFetchedTmdbDetail = tmdbDeferred?.await()
        val kitsuMeta = kitsuDeferred?.await()
        
        var finalProcessedId = res.id
        var imdbId = preFetchedTmdbDetail?.external_ids?.imdb_id
        
        if (imdbId.isNullOrBlank()) {
            val fetchedKitsuImdb = kitsuMeta?.imdb_id?.takeIf { it.startsWith("tt") }
            if (!fetchedKitsuImdb.isNullOrBlank()) {
                imdbId = fetchedKitsuImdb
            } else if (res.id.startsWith("mal:") && !kitsuMeta?.id.isNullOrBlank()) {
                imdbId = kitsuMeta?.id
            }
        }

        if (!imdbId.isNullOrBlank() && (imdbId.startsWith("tt") || imdbId.startsWith("kitsu:"))) {
            finalProcessedId = imdbId
        }

        // provider 참조 에러를 발생시키던 로직 원상복구 처리됨
        val loadRes = res.toLoadResponse(this@StremioC, finalProcessedId, addonDeferred, preFetchedTmdbDetail, kitsuMeta?.id)

        // [평점 플롯 병합 처리]
        val mdbRatings = mdbDeferred?.await() ?: emptyList()
        if (mdbRatings.isNotEmpty()) {
            // 두 칸 공백 띄움으로 아이콘 사이 여백 확보
            val ratingStr = mdbRatings.joinToString("  ")
            loadRes.plot = "$ratingStr | ${loadRes.plot?.trim() ?: ""}"
        }

        return@coroutineScope loadRes
    }

    
    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        val loadData = try { parseJson<LoadData>(data) } catch (e: Exception) { null } ?: return false
        
        val primaryId = if (loadData.id?.startsWith("tmdb:") == true && !loadData.imdbId.isNullOrBlank()) {
            loadData.imdbId
        } else {
            loadData.id ?: ""
        }

        val globalId = if (!loadData.imdbId.isNullOrBlank()) loadData.imdbId else primaryId
        val targetType = loadData.type ?: if ((loadData.season ?: 0) > 0 || (loadData.episode ?: 0) > 0) "series" else "movie"
        
        val primaryStremioId = buildStremioId(targetType, primaryId, loadData.season, loadData.episode) ?: primaryId
        val encodedPrimaryId = primaryStremioId.urlEncode()
        val globalStremioId = buildStremioId(targetType, globalId, loadData.season, loadData.episode) ?: globalId

        runAllAsync(
            {
                runSuspendCatching {
                    val url = buildUrl("/stream/$targetType/$encodedPrimaryId.json")
                    val res = customSession.get(url, timeout = 120L).parsedSafe<StreamsResponse>()
                    res?.streams?.forEach { stream -> stream.runCallback(this@StremioC, subtitleCallback, callback) }
                }
            },
            { invokeStremioX(targetType, globalId, loadData.season, loadData.episode, subtitleCallback, callback) },
            { invokeWatchsomuch(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback) },
            { invokeOpenSubs(loadData.imdbId, loadData.season, loadData.episode, subtitleCallback) },
            { invokeStremioSubtitles(targetType, globalId, loadData.season, loadData.episode, subtitleCallback) },
            { invokeCineStream(loadData, targetType, globalId, isCasting, subtitleCallback, callback) }
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
                    val currentValue = CloudStreamApp.getKey<Any>(key)
                    if (currentValue != value) {
                        when (value) {
                            is Boolean -> CloudStreamApp.setKey(key, value)
                            is String -> CloudStreamApp.setKey(key, value)
                            is Int -> CloudStreamApp.setKey(key, value)
                            is Long -> CloudStreamApp.setKey(key, value)
                            is Float -> CloudStreamApp.setKey(key, value)
                        }
                    }
                } catch (e: Exception) {}
            }
            hasSyncedCineStreamSettings = true
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun invokeCineStream(
        loadData: LoadData,
        targetType: String,
        globalId: String?,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val context = CloudStreamApp.context
        var isEnabled = true
        if (context != null) {
            val possiblePrefNames = listOf("StremioX", "StremioX_preferences", "stremio_preferences", "com.hsp1020_preferences", context.packageName + "_preferences")
            for (name in possiblePrefNames) {
                val prefs = context.getSharedPreferences(name, Context.MODE_PRIVATE)
                if (prefs.contains("cinestream_integration_enabled")) {
                    isEnabled = prefs.getBoolean("cinestream_integration_enabled", true)
                    break
                }
            }
        }

        if (!isEnabled) {
            return
        }

        try {
            val cineStreamProvider = getCineStreamProvider() ?: return
            syncCineStreamSettings()

            val subtitleInterceptor: (SubtitleFile) -> Unit = { sub ->
                try {
                    val host = java.net.URI(sub.url).host ?: ""
                    val customAddonsRaw = CloudStreamApp.getKey<String>("stremio_addons") ?: ""
                    val customHosts = customAddonsRaw.lines()
                        .filter { it.isNotBlank() }
                        .mapNotNull { 
                            val parts = it.split("|")
                            if (parts.size >= 2) java.net.URI(parts[1]).host else null
                        }
                        
                    val builtInHosts = listOf("opensubtitles.stremio.homes", "subsense.nepiraw.com", "opensubtitles-v3.strem.io")
                    val isStremio = customHosts.contains(host) || builtInHosts.contains(host) || host.contains("strem", ignoreCase = true)
                    
                    if (isStremio && !sub.lang.contains("(Stremio)", ignoreCase = true)) {
                        subtitleCallback.invoke(SubtitleFile("${sub.lang} (Stremio)", sub.url))
                    } else {
                        subtitleCallback.invoke(sub)
                    }
                } catch (e: Exception) {
                    subtitleCallback.invoke(sub)
                }
            }

            val isKitsuMedia = globalId?.startsWith("kitsu:") == true || globalId?.startsWith("mal:") == true
            val kitsuIdExt = if (isKitsuMedia) globalId?.substringAfter("kitsu:")?.substringAfter("mal:") else loadData.kitsuId
            
            val rawIdForCineStream = loadData.imdbId ?: globalId?.takeIf { it.startsWith("tt") || it.startsWith("tmdb:") } ?: ""
            val correctImdbIdForCineStream = if (rawIdForCineStream.startsWith("tmdb:")) {
                val parts = rawIdForCineStream.split(":")
                if (parts.size >= 2) "${parts[0]}:${parts[1]}" else rawIdForCineStream
            } else {
                rawIdForCineStream
            }

            val scrapingTitle = loadData.englishTitle ?: loadData.originalTitle ?: loadData.title ?: "Unknown"

            val isAnimeMedia = (loadData.isAnime || isKitsuMedia)
            val isSeason2Plus = isAnimeMedia && (loadData.season ?: 0) > 1

            val finalTitleForCineStream = if (isSeason2Plus) "$scrapingTitle Season ${loadData.season}" else scrapingTitle
            val finalYearForCineStream = if (isSeason2Plus) loadData.airedYear?.toString() ?: loadData.year?.toString() else loadData.year?.toString()

            val cineData = mapOf(
                "title" to finalTitleForCineStream, 
                "id" to correctImdbIdForCineStream,
                "tmdbId" to loadData.tmdbId,
                "tvtype" to targetType,
                "year" to finalYearForCineStream,
                "season" to loadData.season,
                "episode" to loadData.episode,
                "imdb_id" to loadData.imdbId,
                "original_title" to loadData.originalTitle,
                "airedYear" to loadData.airedYear,
                "firstAired" to loadData.firstAired,
                "imdbSeason" to loadData.imdbSeason,
                "imdbEpisode" to loadData.imdbEpisode,
                "anilistId" to loadData.anilistId,
                "malId" to loadData.malId,
                "isAnime" to isAnimeMedia,
                "isKitsu" to false, 
                "kitsuId" to kitsuIdExt,
                "isBollywood" to loadData.isBollywood,
                "isAsian" to loadData.isAsian,
                "isCartoon" to loadData.isCartoon
            )

            cineStreamProvider.loadLinks(cineData.toJson(), isCasting, subtitleInterceptor, callback)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun invokeStremioX(type: String?, id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val filteredSites = getValidStremioAddons()
        val stremioId = buildStremioId(type, id, season, episode) ?: return

        coroutineScope {
            filteredSites.map { site ->
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val url = "${site.url.cleanBaseUrl()}/stream/$type/$stremioId.json"
                        val res = customSession.get(url, timeout = 120L).parsedSafe<StreamsResponse>() 
                        res?.streams?.forEach { stream -> stream.runCallback(this@StremioC, subtitleCallback, callback) }
                    }
                }
            }.awaitAll()
        }
    }

    private data class StremioSubtitleResponse(@JsonProperty("subtitles") val subtitles: List<Subtitle>?)
    
    private suspend fun invokeStremioSubtitles(type: String?, id: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit) {
        val addonUrls = mutableSetOf(baseUrl())
        getValidStremioAddons().forEach { addonUrls.add(it.url.cleanBaseUrl()) }

        val stremioId = buildStremioId(type, id, season, episode, isSubtitle = true) ?: return

        coroutineScope {
            addonUrls.toList().map { api ->
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val url = "$api/subtitles/$type/$stremioId.json"
                        val subtitleResponse = customSession.get(url, timeout = 120L).parsedSafe<StremioSubtitleResponse>()

                        subtitleResponse?.subtitles?.forEach { sub ->
                            val lang = sub.lang ?: sub.lang_code ?: "Unknown"
                            val fileUrl = sub.url
                            if (!fileUrl.isNullOrBlank()) {
                                val baseLangName = SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang
                                subtitleCallback.invoke(newSubtitleFile("$baseLangName (Stremio)", fileUrl))
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    data class LoadData(
        val type: String? = null, 
        val id: String? = null, 
        val season: Int? = null, 
        val episode: Int? = null, 
        val imdbId: String? = null, 
        val year: Int? = null, 
        val title: String? = null, 
        val tmdbId: Int? = null, 
        val isAnime: Boolean = false,
        val isBollywood: Boolean = false,
        val isAsian: Boolean = false,
        val isCartoon: Boolean = false,
        val firstAired: String? = null,
        val airedYear: Int? = null, 
        val originalTitle: String? = null, 
        val englishTitle: String? = null, 
        val imdbSeason: Int? = null,
        val imdbEpisode: Int? = null,
        val anilistId: Int? = null,
        val malId: Int? = null,
        val kitsuId: String? = null
    )
    
    data class CustomSite(@JsonProperty("parentJavaClass") val parentJavaClass: String, @JsonProperty("name") val name: String, @JsonProperty("url") val url: String, @JsonProperty("lang") val lang: String)

    private fun isImdborTmdb(url: String?): Boolean {
        return imdbUrlToIdNullable(url) != null || url?.startsWith("tmdb:") == true
    }

    private data class Manifest(val catalogs: List<Catalog>)
    private data class Extra(@JsonProperty("name") val name: String?, @JsonProperty("isRequired") val isRequired: Boolean? = false)

    private data class Catalog(var name: String?, val id: String, val type: String?, val types: MutableList<String> = mutableListOf(), @JsonProperty("extra") val extra: List<Extra>? = null, @JsonProperty("extraSupported") val extraSupported: List<String>? = null) {
        init { if (type != null) types.add(type) }

        fun isSearchRequired(): Boolean = extra?.any { it.name == "search" && it.isRequired == true } == true
        fun supportsSearch(): Boolean = extra?.any { it.name == "search" } == true || extraSupported?.contains("search") == true

        private suspend fun fetchMetas(provider: StremioC, path: String): List<CatalogEntry> {
            return try {
                val req = StremioC.customSession.get(provider.buildUrl(path), timeout = 120L)
                if (req.isSuccessful && req.text.isNotBlank()) parseJson<CatalogResponse>(req.text).metas ?: emptyList() else emptyList()
            } catch (e: Exception) { emptyList() }
        }

        suspend fun search(query: String, provider: StremioC): List<SearchResponse> {
            val allMetas = coroutineScope {
                types.map { type ->
                    async(Dispatchers.IO) {
                        val path = "/catalog/${type}/${id}/search=${query.urlEncode()}.json"
                        fetchMetas(provider, path)
                    }
                }.awaitAll().flatten()
            }
            return allMetas.distinctBy { it.id }.map { it.toSearchResponse(provider) }
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
        @JsonProperty("name") val name: String, 
        @JsonProperty("id") val id: String, 
        @JsonProperty("moviedb_id") val moviedb_id: Int? = null,
        @JsonProperty("_tmdbId") val tmdbIdExt: String? = null, 
        @JsonProperty("_malId") val malIdExt: String? = null,
        @JsonProperty("_kitsuId") val kitsuIdExt: String? = null,
        @JsonProperty("poster") val poster: String?, 
        @JsonProperty("background") val background: String?, 
        @JsonProperty("logo") val logo: String? = null,
        @JsonProperty("description") val description: String?, 
        @JsonProperty("imdbRating") val imdbRating: String?, 
        @JsonProperty("type") val type: String?,
        @JsonProperty("videos") val videos: List<Video>?, 
        @JsonProperty("genre") val genre: List<String>?, 
        @JsonProperty("genres") val genres: List<String> = emptyList(),
        @JsonProperty("cast") val cast: List<String> = emptyList(), 
        @JsonProperty("trailers") val trailersSources: List<Trailer> = emptyList(),
        @JsonProperty("trailerStreams") val trailerStreams: List<TrailerStream> = emptyList(), 
        @JsonProperty("links") val links: List<Link> = emptyList()
    ) {
        fun toSearchResponse(provider: StremioC): SearchResponse {
            return provider.newMovieSearchResponse(name, this.toJson(), TvType.Others) { posterUrl = poster }
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
            
            val detailRes = preFetchedDetail ?: run {
                val detailAppend = if (isMovie) "release_dates,credits,images,videos,external_ids,recommendations,translations" else "content_ratings,credits,images,videos,external_ids,recommendations,translations"
                val detailUrl = provider.buildTmdbUrl("/$tmdbMediaType/$tmdbIdStr", "append_to_response" to detailAppend)
                StremioC.customSession.get(detailUrl).parsedSafe<TmdbDetailResponse>()
            }
            
            if (detailRes == null) {
                return FetchedTmdbData(null, null, null, emptyList(), null, null, null, null, null, null, emptyMap(), emptyList(), null, null)
            }

            val validImdbId = (detailRes.external_ids?.imdb_id ?: finalImdbId)?.takeIf { it.startsWith("tt") }
            val targetCountry = detailRes.origin_country?.firstOrNull()
            val targetLanguage = detailRes.original_language
            val isSameOrigin: (TmdbMedia?) -> Boolean = { media -> 
                media != null && ((targetCountry != null && media.origin_country?.contains(targetCountry) == true) || (targetLanguage != null && media.original_language == targetLanguage)) 
            }

            coroutineScope {
                val traktDeferred = async(Dispatchers.IO) {
                    if (validImdbId == null || TRAKT_CLIENT_ID.isEmpty()) return@async emptyList<Pair<Int, String>>()
                    val traktMediaType = if (tmdbMediaType == "movie") "movies" else "shows"
                    val expectedType = if (tmdbMediaType == "movie") "movie" else "tv"
                    runSuspendCatching {
                        val req = StremioC.customSession.get("https://api.trakt.tv/$traktMediaType/$validImdbId/related?limit=30", headers = mapOf("Content-Type" to "application/json", "trakt-api-version" to "2", "trakt-api-key" to TRAKT_CLIENT_ID), timeout = 15)
                        if (req.isSuccessful && req.text.isNotBlank()) {
                            parseJson<List<TraktRelatedItem>>(req.text).mapNotNull {
                                val tmdbId = it.ids?.tmdb ?: 0
                                if (tmdbId > 0) Pair(tmdbId, expectedType) else null
                            }
                        } else emptyList()
                    }.getOrDefault(emptyList())
                }

                val simklDeferred = async(Dispatchers.IO) {
                    if (validImdbId == null || SIMKL_CLIENT_ID.isEmpty()) return@async emptyList<Pair<Int, String>>()
                    val simklMediaType = if (tmdbMediaType == "movie") "movies" else "tv"
                    val parentExpectedType = if (tmdbMediaType == "movie") "movie" else "tv"
                    
                    runSuspendCatching {
                        val req = StremioC.customSession.get("https://api.simkl.com/$simklMediaType/$validImdbId?extended=full&client_id=$SIMKL_CLIENT_ID", timeout = 15)
                        if (req.isSuccessful && req.text.isNotBlank()) {
                            val simklRes = parseJson<SimklRecResponse>(req.text)
                            val rawSimklRecs = mutableListOf<Triple<Int, Int, String>>()
                            
                            simklRes.users_recommendations?.take(30)?.forEach { recItem ->
                                val itemTypeRaw = recItem.type ?: parentExpectedType
                                val itemTmdbType = if (itemTypeRaw.contains("movie")) "movie" else "tv"
                                val tmdb = recItem.ids?.tmdb ?: 0
                                val simkl = recItem.ids?.simkl ?: 0
                                if (tmdb > 0 || simkl > 0) rawSimklRecs.add(Triple(tmdb, simkl, itemTmdbType))
                            }
                            
                            coroutineScope {
                                rawSimklRecs.map { (tmdbId, simklId, itemTmdbType) ->
                                    async(Dispatchers.IO) {
                                        if (tmdbId > 0) Pair(tmdbId, itemTmdbType) 
                                        else if (simklId > 0) {
                                            try {
                                                val simklReqType = if (itemTmdbType == "movie") "movies" else "tv"
                                                val dReq = StremioC.customSession.get("https://api.simkl.com/$simklReqType/$simklId?client_id=$SIMKL_CLIENT_ID", timeout = 15)
                                                if (dReq.isSuccessful && dReq.text.isNotBlank()) {
                                                    val fetchedTmdbId = parseJson<SimklDetailResponse>(dReq.text).ids?.tmdb ?: 0
                                                    if (fetchedTmdbId > 0) Pair(fetchedTmdbId, itemTmdbType) else null
                                                } else null
                                            } catch(e: Exception) { null }
                                        } else null
                                    }
                                }
                                .awaitAll().filterNotNull()
                            }
                        } else emptyList()
                    }.getOrDefault(emptyList())
                }

                val collectionParts = if (isMovie && detailRes.belongs_to_collection?.id != null) {
                    runSuspendCatching {
                        val colUrl = provider.buildTmdbUrl("/collection/${detailRes.belongs_to_collection.id}")
                        StremioC.customSession.get(colUrl).parsedSafe<TmdbCollectionDetail>()?.parts ?: emptyList()
                    }.getOrDefault(emptyList())
                } else emptyList()

                val traktResults = traktDeferred.await()
                val simklResults = simklDeferred.await()
                
                val tmdbRecs = detailRes.recommendations?.results ?: emptyList()
                val tmdbRecsIds = tmdbRecs.mapNotNull { it.id }.toSet()

                val existingTmdbIds = collectionParts.mapNotNull { it.id }.toSet() + tmdbRecsIds
                
                val missingPairs = sequenceOf(traktResults, simklResults)
                    .flatten()
                    .distinctBy { it.first }
                    .filter { it.first !in existingTmdbIds && it.first != tmdbIdStr.toIntOrNull() }
                    .toList()

                val missingMediaDeferred = missingPairs.map { pair ->
                    async(Dispatchers.IO) {
                        runSuspendCatching {
                            val res = StremioC.customSession.get(provider.buildTmdbUrl("/${pair.second}/${pair.first}"), timeout = 10).parsedSafe<TmdbDetailResponse>()
                            res?.toTmdbMedia(pair.second)
                        }.getOrNull()
                    }
                }
                val fetchedMissingMedia = missingMediaDeferred.awaitAll().filterNotNull()

                val allCandidates = (collectionParts + tmdbRecs + fetchedMissingMedia).distinctBy { it.id }.filter { it.id != null && it.id != tmdbIdStr.toIntOrNull() }
                
                val traktIds = traktResults.map { it.first }.toSet()
                val simklIds = simklResults.map { it.first }.toSet()

                val finalCombinedMedia = allCandidates.map { media ->
                    var score = 0.0 
                    if (collectionParts.any { it.id == media.id }) score += 10.0 
                    if (isSameOrigin(media)) score += 1.0 
                    if (media.id in traktIds) score += 2.5 
                    if (media.id in simklIds) score += 2.0 
                    if (media.id in tmdbRecsIds) score += 1.7 
                    
                    if (score >= 10.0) score = 10.0
                    Pair(media, score)
                }.sortedWith(compareByDescending<Pair<TmdbMedia, Double>> { it.second }.thenBy { 
                    if (it.second >= 10.0) {
                        it.first.releaseDate?.takeIf { d -> d.isNotBlank() } ?: it.first.firstAirDate?.takeIf { d -> d.isNotBlank() } ?: "9999-12-31"
                    } else {
                        "" 
                    }
                }).map { it.first }.take(50) 

                fetchedRecommendations = finalCombinedMedia.mapNotNull { media ->
                    val recTitle = media.title ?: media.name ?: media.originalTitle ?: return@mapNotNull null
                    val recTmdbId = media.id ?: return@mapNotNull null
                    val rawMediaType = media.mediaType ?: tmdbMediaType
                    val posterType = if (rawMediaType == "tv") "tv" else "movie"
                    val outType = if (rawMediaType == "tv") "series" else "movie"
                    
                    val customPosterUrl = "https://xrdb.ibbylabs.dev/poster/tmdb:$posterType:$recTmdbId.jpg?idSource=tmdb&tmdbKey=cc9982c4801545a1481d167137ea7b53&mdblistKey=d2tmbmhnrgdz7ft9re7j2gjr3&posterRatings=myanimelist%2Cimdb%2Cletterboxd%2Cmetacriticuser%2Ctomatoesaudience%2Ctrakt%2Ctmdb%2Cmetacritic%2Ctomatoes%2Crogerebert&lang=ko&ratingValueMode=normalized&posterGenreBadgeStyle=plain&posterGenreBadgePosition=bottomLeft&posterStreamBadges=on&ageRatingBadgePosition=left-top&posterQualityBadges=&posterRatingStyle=plain&posterRatingBadgeScale=115&imageText=original&posterRatingsLayout=right&posterNoBackgroundBadgeOutlineColor=%23ff0000&posterEdgeOffset=3&providerAppearance=eyJ0b21hdG9lcyI6eyJzdGFja2VkQWNjZW50TW9kZSI6ImxvZ28ifX0"
                    val recommendationEntry = CatalogEntry(name = recTitle, id = "tmdb:${media.id}", type = outType, poster = customPosterUrl, background = null, description = media.overview, imdbRating = null, videos = null, genre = null)
                    provider.newMovieSearchResponse(recTitle, recommendationEntry.toJson(), if (outType == "movie") TvType.Movie else TvType.TvSeries) { this.posterUrl = customPosterUrl }
                }
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

            val episodeTmdbMeta = mutableMapOf<String, TmdbEpisode>()
            val shouldFetchEpisodes = (!isMovie && targetVideos.isNullOrEmpty()) || (stremioType == "other" && !isMovie)
            
            if (shouldFetchEpisodes) {
                var requiredSeasons = targetVideos?.mapNotNull { it.seasonNumber }?.filter { it > 0 }?.distinct() ?: emptyList()
                if (requiredSeasons.isEmpty()) {
                    val totalSeasons = detailRes.number_of_seasons ?: 1
                    requiredSeasons = if (totalSeasons > 0) (1..totalSeasons).toList() else listOf(1)
                }
                
                if (requiredSeasons.isNotEmpty()) {
                    coroutineScope {
                        requiredSeasons.map { seasonNum ->
                            async(Dispatchers.IO) {
                                StremioC.networkSemaphore.withPermit {
                                    runSuspendCatching {
                                        val seasonRes = StremioC.customSession.get(provider.buildTmdbUrl("/tv/$tmdbIdStr/season/$seasonNum")).parsedSafe<TmdbSeasonDetail>()
                                        seasonRes?.episodes?.forEach { ep -> if (ep.episodeNumber != null) episodeTmdbMeta["${seasonNum}_${ep.episodeNumber}"] = ep }
                                    }
                                }
                            }
                        }.awaitAll()
                    }
                }
            }

            val extractedEnglishTitle = detailRes.translations?.translations?.find { 
                it.iso_3166_1 == "US" || it.iso_639_1 == "en" 
            }?.data?.let { it.title ?: it.name } ?: detailRes.original_title ?: detailRes.original_name

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

        private fun processEpisodes(provider: StremioC, finalImdbId: String?, episodeTmdbMeta: Map<String, TmdbEpisode>, targetVideos: List<Video>, type: String?, showTitle: String?, tmdbId: Int?, isAnime: Boolean, isBollywood: Boolean, isAsian: Boolean, isCartoon: Boolean, originalTitle: String?, englishTitle: String?): List<Episode> {
            val sortedVideos = targetVideos.groupBy { Pair(it.seasonNumber ?: 0, it.episode ?: it.number ?: 0) }.flatMap { (_, group) -> group.sortedBy { it.title ?: it.name ?: "" } }
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
                    displayEpisode = (uniqueEpisodeChecker.filter { it.startsWith("${displaySeason}_") }.maxOfOrNull { it.split("_")[1].toInt() } ?: 0) + 1
                    key = "${displaySeason}_${displayEpisode}"
                }
                uniqueEpisodeChecker.add(key)
                
                val imdbSeason = video.seasonNumber ?: 0
                val imdbEpisode = video.episode ?: video.number ?: 0
                
                video.toEpisode(provider, type, finalImdbId, episodeTmdbMeta, displaySeason, displayEpisode, showTitle, tmdbId, isAnime, isBollywood, isAsian, isCartoon, imdbSeason, imdbEpisode, originalTitle, englishTitle)
            }
        }

        suspend fun toLoadResponse(provider: StremioC, imdbId: String?, addonDeferred: Deferred<CatalogEntry?>?, preFetchedDetail: TmdbDetailResponse? = null, fetchedKitsuId: String? = null): LoadResponse = coroutineScope {
            var finalImdbId = if (this@CatalogEntry.id.startsWith("tt")) this@CatalogEntry.id 
                              else imdbId?.takeIf { it.startsWith("tt") || it.startsWith("kitsu:") || it.startsWith("mal:") }
            
            if (finalImdbId == null && this@CatalogEntry.links.isNotEmpty()) {
                finalImdbId = this@CatalogEntry.links.firstOrNull { it.category == "imdb" }?.url?.substringAfterLast("/")?.takeIf { it.startsWith("tt") }
            }
            if (finalImdbId == null) {
                finalImdbId = provider.extractImdbId(logo) ?: provider.extractImdbId(poster) ?: provider.extractImdbId(background)
            }

            val detailedEntry = addonDeferred?.await() ?: this@CatalogEntry
            var tmdbIdStr: String? = if (this@CatalogEntry.id.startsWith("tmdb:")) this@CatalogEntry.id.removePrefix("tmdb:") else this@CatalogEntry.moviedb_id?.toString() ?: this@CatalogEntry.tmdbIdExt ?: detailedEntry.tmdbIdExt
            
            val findApiDeferred = if (tmdbIdStr == null && finalImdbId?.startsWith("tt") == true) {
                async(Dispatchers.IO) {
                    runSuspendCatching {
                        val findRes = StremioC.customSession.get(provider.buildTmdbUrl("/find/$finalImdbId", "external_source" to "imdb_id")).parsedSafe<TmdbFindResponse>()
                        val movieMatch = findRes?.movie_results?.firstOrNull()
                        if (movieMatch?.id != null) Pair(movieMatch.id.toString(), "movie") else findRes?.tv_results?.firstOrNull()?.id?.let { Pair(it.toString(), "tv") }
                    }.getOrNull()
                }
            } else null

            var finalVideos = detailedEntry.videos ?: this@CatalogEntry.videos
            val finalTrailersSources = detailedEntry.trailersSources ?: this@CatalogEntry.trailersSources
            val finalTrailerStreams = detailedEntry.trailerStreams ?: this@CatalogEntry.trailerStreams
            val originalName = detailedEntry.name ?: this@CatalogEntry.name
            var finalDescription = detailedEntry.description ?: this@CatalogEntry.description
            val finalGenre = detailedEntry.genre ?: detailedEntry.genres ?: this@CatalogEntry.genre ?: this@CatalogEntry.genres
            val finalCast = detailedEntry.cast.ifEmpty { this@CatalogEntry.cast }
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

            var finalName = originalName ?: this@CatalogEntry.name
            if (tmdbData != null) {
                if (!tmdbData.fetchedTitle.isNullOrBlank()) finalName = tmdbData.fetchedTitle
                finalDescription = provider.mergeOverview(finalDescription, tmdbData.fetchedOverview)
                if (finalType == "other") finalDescription = if (finalDescription.isNullOrBlank()) originalName else "$finalDescription | $originalName" 
            }

            val fallbackMovieId = fetchedKitsuId ?: this@CatalogEntry.kitsuIdExt?.let { "kitsu:$it" } ?: this@CatalogEntry.id
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

            val finalActors = if (tmdbData?.fetchedActors?.isNotEmpty() == true) tmdbData.fetchedActors else null

            val countries = tmdbData?.fetchedOriginCountry ?: emptyList()
            val isBollywood = countries.any { it.contains("IN", true) || it.contains("India", true) }
            val isAsian = countries.any { it.contains("KR", true) || it.contains("JP", true) || it.contains("CN", true) || it.contains("TH", true) || it.contains("TW", true) }
            val isCartoon = finalGenre?.any { it.contains("Animation", true) } == true
            val isAnimeMedia = fetchedKitsuId != null || finalImdbId?.startsWith("mal:") == true || finalImdbId?.startsWith("kitsu:") == true || finalGenre?.contains("Anime") == true || (countries.any { it.contains("JP", true) } && isCartoon)
            
            var fetchedAniListId: Int? = null
            var fetchedMalId: Int? = null
            if (isAnimeMedia && finalImdbId != null) {
                try {
                    val rawIdForAnizip = finalImdbId.substringAfter("kitsu:").substringAfter("mal:")
                    val anizipType = if (finalImdbId.startsWith("kitsu:")) "kitsu_id" else if (finalImdbId.startsWith("mal:")) "mal_id" else "imdb_id"
                    val anizipRes = StremioC.customSession.get("https://api.ani.zip/mappings?$anizipType=$rawIdForAnizip").text
                    val anizipJson = JSONObject(anizipRes)
                    fetchedAniListId = anizipJson.getJSONObject("mappings").optInt("anilist_id").takeIf { it != 0 }
                    fetchedMalId = anizipJson.getJSONObject("mappings").optInt("mal_id").takeIf { it != 0 }
                } catch (e: Exception) {}
            }

            if (finalVideos.isNullOrEmpty() || isSingleMovie(finalType, finalVideos)) {
                return@coroutineScope provider.newMovieLoadResponse(
                    finalName, "${provider.mainUrl}/meta/${finalType}/${this@CatalogEntry.id}.json", TvType.Movie, 
                    LoadData(
                        type = finalType, 
                        id = movieId, 
                        imdbId = finalImdbId, 
                        year = tmdbData?.fetchedYear, 
                        title = finalName, 
                        tmdbId = tmdbIdStr?.toIntOrNull(), 
                        isAnime = isAnimeMedia,
                        isBollywood = isBollywood,
                        isAsian = isAsian,
                        isCartoon = isCartoon,
                        anilistId = fetchedAniListId,
                        malId = fetchedMalId,
                        kitsuId = fetchedKitsuId ?: this@CatalogEntry.kitsuIdExt,
                        originalTitle = tmdbData?.fetchedOriginalTitle,
                        englishTitle = tmdbData?.fetchedEnglishTitle
                    )
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalImdbRating); plot = finalDescription; year = tmdbData?.fetchedYear; tags = finalGenre
                    if (finalActors != null) this.actors = finalActors else addActors(finalCast)
                    addTrailer(finalTrailers); this.recommendations = tmdbData?.fetchedRecommendations; this.duration = tmdbData?.fetchedRuntime; this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }; tmdbIdStr?.let { addTMDbId(it) }; finalImdbId?.let { if (it.startsWith("tt")) addImdbId(it) }
                }
            } else {
                val episodesList = processEpisodes(provider, finalImdbId, tmdbData?.episodeTmdbMeta ?: emptyMap(), finalVideos, finalType, finalName, tmdbIdStr?.toIntOrNull(), isAnimeMedia, isBollywood, isAsian, isCartoon, tmdbData?.fetchedOriginalTitle, tmdbData?.fetchedEnglishTitle)
                
                val finalEpisodes = episodesList.map { ep ->
                    val oldData = parseJson<LoadData>(ep.data)
                    val newData = oldData.copy(anilistId = fetchedAniListId, malId = fetchedMalId, kitsuId = fetchedKitsuId ?: this@CatalogEntry.kitsuIdExt)
                    ep.apply { data = newData.toJson() }
                }

                return@coroutineScope provider.newTvSeriesLoadResponse(
                    finalName, "${provider.mainUrl}/meta/${finalType}/${this@CatalogEntry.id}.json", TvType.TvSeries, finalEpisodes
                ) {
                    posterUrl = finalPoster; backgroundPosterUrl = finalBackground; score = Score.from10(finalImdbRating); plot = finalDescription; year = tmdbData?.fetchedYear; tags = finalGenre
                    this.showStatus = tmdbData?.fetchedStatus 
                    if (finalActors != null) this.actors = finalActors else addActors(finalCast)
                    addTrailer(finalTrailers.firstOrNull()); this.recommendations = tmdbData?.fetchedRecommendations; this.contentRating = tmdbData?.fetchedAgeRating
                    tmdbData?.fetchedLogo?.let { this.logoUrl = it }; tmdbIdStr?.let { addTMDbId(it) }; finalImdbId?.let { if (it.startsWith("tt")) addImdbId(it) }
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
        @JsonProperty("tvdb_id") val tvdb_id: Int? = null
    ) {
        fun toEpisode(provider: StremioC, type: String?, imdbId: String?, tmdbMetaMap: Map<String, TmdbEpisode>, displaySeason: Int, displayEpisode: Int, showTitle: String?, tmdbId: Int?, isAnime: Boolean, isBollywood: Boolean, isAsian: Boolean, isCartoon: Boolean, imdbSeason: Int?, imdbEpisode: Int?, originalTitle: String?, englishTitle: String?): Episode {
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
                englishTitle = englishTitle 
            )) {
                val rawTitle = this@Video.name ?: this@Video.title ?: "Episode $displayEpisode"
                val cleanOriginalTitle = if (rawTitle.contains("📄")) rawTitle.substringAfter("📄").replace("\n", "").trim() else rawTitle.replace("\n", "").trim()
                
                this.name = if (tmdbEp?.name != null) tmdbEp.name else cleanOriginalTitle
                this.posterUrl = thumbnail ?: TRANSPARENT_PIXEL
                var finalEpDesc = provider.mergeOverview(this@Video.overview ?: this@Video.description, tmdbEp?.overview)
                if (type == "other") finalEpDesc = if (finalEpDesc.isNullOrBlank()) cleanOriginalTitle else "$finalEpDesc | $cleanOriginalTitle"
                
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
                subtitles.map { sub -> val lang = sub.lang ?: sub.lang_code ?: "Unknown"; subtitleCallback.invoke(newSubtitleFile(SubtitleHelper.fromTagToEnglishLanguageName(lang) ?: lang, sub.url ?: return@map)) }
            }
            if (ytId != null) loadExtractor("https://m.youtube.com/watch?v=$ytId", subtitleCallback, callback)
            if (externalUrl != null) loadExtractor(externalUrl, subtitleCallback, callback)
            if (infoHash != null) callback.invoke(newExtractorLink(name ?: "", title ?: name ?: "", "magnet:?xt=urn:btih:${infoHash}") { this.quality = Qualities.Unknown.value })
        }
    }
}

private data class KitsuMetaResponse(@JsonProperty("meta") val meta: KitsuMeta?)
private data class KitsuMeta(@JsonProperty("imdb_id") val imdb_id: String?, @JsonProperty("id") val id: String?)
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
