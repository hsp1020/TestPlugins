// 파일명: hsp1020/ultimaevo/UltimaEvoProvider.kt
package com.ultimaevo

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

// --- [리플렉션 안전 우회 프록시 엔진] ---
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

object UltimaEvoSyncManager {
    private var isSyncing = false
    private const val SYNC_THROTTLE_MS = 6 * 60 * 60 * 1000L 

    fun triggerAutoSync(context: Context) {
        if (isSyncing) return

        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("evo_last_sync_time", 0L)
        val now = System.currentTimeMillis()

        if (now - lastSync < SYNC_THROTTLE_MS) return

        isSyncing = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performSync(context, forceSyncAll = false, null)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isSyncing = false
            }
        }
    }

    suspend fun performSync(
        context: Context,
        forceSyncAll: Boolean = false,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val instanceCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        val targetProviders = if (forceSyncAll) {
            getSafeAllProviders().filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        } else {
            getSafeAllProviders().filter { provider ->
                !provider.mainUrl.startsWith("ultima_evo") && provider.hasMainPage &&
                (1..instanceCount).any { instId ->
                    prefs.getBoolean("provider_enabled_${instId}_${provider.name}", true) &&
                        (prefs.getBoolean("ext_toggle_${instId}_${provider.name}", false) ||
                            prefs.getBoolean("auto_add_${instId}_${provider.name}", false))
                }
            }
        }

        if (targetProviders.isEmpty()) return

        val total = targetProviders.size
        var current = 0
        val semaphore = Semaphore(3)
        var anyChange = false

        coroutineScope {
            val syncJobs = targetProviders.map { provider ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(30000L) {
                                val reqName = provider.mainPage.firstOrNull()?.name ?: "Home"
                                val reqData = provider.mainPage.firstOrNull()?.data ?: ""
                                val response = provider.getMainPage(1, MainPageRequest(reqName, reqData, false))

                                if (response != null && response.items.isNotEmpty()) {
                                    val names = response.items.map { it.name }.filter { it.isNotBlank() }.distinct()
                                    val namesStr = names.joinToString("|||")

                                    val oldNamesStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""

                                    if (namesStr != oldNamesStr) {
                                        prefs.edit().putString("dynamic_catalogs_${provider.name}", namesStr).apply()
                                        anyChange = true
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                            if (e is CancellationException) throw e
                        }
                        current++
                        onProgress?.invoke(current, total)
                    }
                }
            }
            syncJobs.awaitAll()
        }

        prefs.edit().putLong("evo_last_sync_time", System.currentTimeMillis()).apply()

        if (anyChange) {
            getSafeAllProviders().forEach { p ->
                if (p is UltimaEvoProvider) {
                    p.invalidateCache()
                }
            }
        }
    }
}

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName

    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)    
    private var cachedMainPageList: List<MainPageData> = emptyList()

    fun invalidateCache() {
        cachedMainPageList = emptyList()
    }

    override val hasMainPage: Boolean
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return true
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
        }

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 인스턴스 할당됨: $customName")
        applyAdBlockPatch() 
    }

    private fun applyAdBlockPatch() {
        try {
            val cncverseProviders = getSafeAllProviders().filter { 
                it::class.java.name.startsWith("com.cncverse.") 
            }
            
            for (provider in cncverseProviders) {
                try {
                    val clazz = provider::class.java
                    val field = clazz.getDeclaredField("context")
                    field.isAccessible = true
                    val targetContext = field.get(null)

                    if (targetContext is Context && targetContext !is PreventAdContextWrapper) {
                        field.set(null, PreventAdContextWrapper(targetContext))
                        println("[UltimaEvo_AdBlock] ${clazz.simpleName} 광고 가로채기 차단 패치 주입 완료")
                    }
                } catch (e: NoSuchFieldException) {
                    // context 변수가 없는 경우 안전히 생략
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override val mainPage: List<MainPageData>
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()

            UltimaEvoSyncManager.triggerAutoSync(context)

            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) return emptyList()

            if (cachedMainPageList.isNotEmpty()) {
                return cachedMainPageList
            }

            val pages = mutableListOf<Pair<MainPageData, Int>>()

            val activeCatsJson = prefs.getString("evo_active_cats_json_$instanceId", "") ?: ""
            val savedConfigs: List<EvoCatalogConfig> = if (activeCatsJson.isNotBlank()) {
                try { parseJson<List<EvoCatalogConfig>>(activeCatsJson) } catch (e: Exception) { emptyList() }
            } else emptyList()

            val savedSet = savedConfigs.map { "${it.providerName}:::${it.originalCatalogName}" }.toSet()

            savedConfigs.forEach { config ->
                if (!prefs.getBoolean("provider_enabled_${instanceId}_${config.providerName}", true)) return@forEach
                val combinedData = config.toJson()
                pages.add(Pair(MainPageData(config.customCatalogName, combinedData), config.orderIndex))
            }

            var dynamicOrder = 10000 
            val targetProviders = getSafeAllProviders().filter { !it.mainUrl.startsWith("ultima_evo") }

            for (p in targetProviders) {
                val isAutoAdd = prefs.getBoolean("auto_add_${instanceId}_${p.name}", false)
                if (isAutoAdd && prefs.getBoolean("provider_enabled_${instanceId}_${p.name}", true)) {
                    val staticCats = p.mainPage.map { it.name }
                    val dynamicCats = prefs.getString("dynamic_catalogs_${p.name}", "")?.split("|||") ?: emptyList()
                    val combinedCats = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()

                    for (cName in combinedCats) {
                        if (!savedSet.contains("${p.name}:::$cName")) {
                            val isEnabled = prefs.getBoolean("cat_toggle_${instanceId}_${p.name}_$cName", true)
                            if (isEnabled) {
                                val customName = prefs.getString("cat_rename_${instanceId}_${p.name}_$cName", cName) ?: cName
                                val origData = p.mainPage.find { it.name == cName }?.data ?: ""
                                val newConfig = EvoCatalogConfig(p.name, cName, customName, origData, dynamicOrder)
                                val combinedData = newConfig.toJson()
                                pages.add(Pair(MainPageData(customName, combinedData), dynamicOrder))
                                dynamicOrder++
                            }
                        }
                    }
                }
            }

            cachedMainPageList = pages.sortedBy { it.second }.map { it.first }
            return cachedMainPageList
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        applyAdBlockPatch() 
        return withContext(Dispatchers.IO) {
            try {
                val config = parseJson<EvoCatalogConfig>(request.data)
                if (config == null) return@withContext newHomePageResponse(emptyList(), false)
                val prefs = UltimaEvoPlugin.pluginContext?.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
                if (prefs?.getBoolean("provider_enabled_${instanceId}_${config.providerName}", true) == false)
                    return@withContext newHomePageResponse(emptyList(), false)

                val targetProvider = getSafeAllProviders().find { it.name == config.providerName } 
                    ?: return@withContext newHomePageResponse(emptyList(), false)

                val originalRequest = MainPageRequest(request.name, config.originalData, request.horizontalImages)
                
                val response = withTimeoutOrNull(30000L) {
                    targetProvider.getMainPage(page, originalRequest)
                } ?: return@withContext newHomePageResponse(emptyList(), false)
                
                val filteredItems = if (config.originalCatalogName.isNotEmpty() && response.items.size > 1) {
                    response.items.filter { it.name == config.originalCatalogName }
                } else {
                    response.items
                }

                val modifiedItems = filteredItems.map { it.copy(name = config.customCatalogName) }
                newHomePageResponse(modifiedItems, response.hasNext)
            } catch (e: Exception) {
                e.printStackTrace()
                if (e is CancellationException) throw e
                newHomePageResponse(emptyList(), false)
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        applyAdBlockPatch() 
        return withContext(Dispatchers.IO) {
            val allProviders = getSafeAllProviders().filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
            val exactProvider = allProviders.find { url.startsWith(it.mainUrl) }
            
            if (exactProvider != null) {
                try {
                    val res = exactProvider.load(url)
                    if (res != null) return@withContext res
                } catch (e: Exception) {
                    e.printStackTrace()
                    if (e is CancellationException) throw e
                }
            }
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        applyAdBlockPatch()
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return emptyList()

        val activeCatsJson = prefs.getString("evo_active_cats_json_$instanceId", "") ?: ""
        if (activeCatsJson.isBlank()) return emptyList()

        val providersToSearch = try {
            val configs: List<EvoCatalogConfig> = parseJson<List<EvoCatalogConfig>>(activeCatsJson)
            configs.map { it.providerName }
                .filter { prefs.getBoolean("provider_enabled_${instanceId}_$it", true) }
                .distinct()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        val semaphore = Semaphore(5)

        return coroutineScope {
            val deferredResults = providersToSearch.mapNotNull { providerName ->
                val provider = getSafeAllProviders().find { it.name == providerName } ?: return@mapNotNull null
                async<List<SearchResponse>>(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(30000L) {
                                provider.search(query) ?: emptyList()
                            } ?: emptyList()
                        } catch (e: Exception) {
                            e.printStackTrace()
                            if (e is CancellationException) throw e
                            emptyList()
                        }
                    }
                }
            }
            
            val results: List<SearchResponse> = deferredResults.awaitAll().flatten()
            val finalRes: List<SearchResponse> = results.distinctBy { it.url }
            finalRes
        }
    }
}

class PreventAdContextWrapper(base: Context) : ContextWrapper(base) {
    override fun startActivity(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) {
            val uri = intent.data
            val scheme = uri?.scheme
            val host = uri?.host

            if (scheme == "http" || scheme == "https") {
                if (host != "t.me") {
                    println("[UltimaEvo_AdBlock] 외부 브라우저 광고 강제 호출 정상 가로채기 차단 완료 (차단된 URL: $uri)")
                    return 
                }
            }
        }
        try {
            super.startActivity(intent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
