// 파일명: hsp1020/ultimaevo/UltimaEvoProvider.kt
package com.ultimaevo

import android.content.Context
import com.google.gson.reflect.TypeToken
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
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
            APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        } else {
            APIHolder.allProviders.filter { provider ->
                !provider.mainUrl.startsWith("ultima_evo") && provider.hasMainPage &&
                (1..instanceCount).any { instId ->
                    prefs.getBoolean("ext_toggle_${instId}_${provider.name}", false) ||
                    prefs.getBoolean("auto_add_${instId}_${provider.name}", false)
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
                            withTimeoutOrNull(15000L) {
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
            APIHolder.allProviders.forEach { p ->
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

            // 1. JSON에 수동으로 예쁘게 저장된 설정 로드 (O(1) 속도)
            val activeCatsJson = prefs.getString("evo_active_cats_json_$instanceId", "") ?: ""
            val listType = object : TypeToken<List<EvoCatalogConfig>>() {}.type
            val savedConfigs: List<EvoCatalogConfig> = if (activeCatsJson.isNotBlank()) {
                try { UltimaEvoPlugin.gson.fromJson(activeCatsJson, listType) ?: emptyList() } catch (e: Exception) { emptyList() }
            } else emptyList()

            // 식별용 셋(Set) 생성
            val savedSet = savedConfigs.map { "${it.providerName}:::${it.originalCatalogName}" }.toSet()

            savedConfigs.forEach { config ->
                val combinedData = UltimaEvoPlugin.gson.toJson(config)
                pages.add(Pair(MainPageData(config.customCatalogName, combinedData), config.orderIndex))
            }

            // 2. 🔥 [버그 픽스] 수석님 지시 반영: Auto-Add 동적(Dynamic) 로드 로직 완벽 부활!
            // JSON에 아직 저장되지 않은 새로운 카탈로그가 백그라운드에서 감지되었을 때 자동으로 화면 맨 밑에 붙여줍니다.
            var dynamicOrder = 10000 
            val targetProviders = APIHolder.allProviders.filter { !it.mainUrl.startsWith("ultima_evo") }

            for (p in targetProviders) {
                val isAutoAdd = prefs.getBoolean("auto_add_${instanceId}_${p.name}", false)
                if (isAutoAdd) {
                    val staticCats = p.mainPage.map { it.name }
                    val dynamicCats = prefs.getString("dynamic_catalogs_${p.name}", "")?.split("|||") ?: emptyList()
                    val combinedCats = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()

                    for (cName in combinedCats) {
                        // JSON 저장소에 없는 완전히 새로운 카탈로그인 경우만 검사
                        if (!savedSet.contains("${p.name}:::$cName")) {
                            val isEnabled = prefs.getBoolean("cat_toggle_${instanceId}_${p.name}_$cName", true)
                            if (isEnabled) {
                                val customName = prefs.getString("cat_rename_${instanceId}_${p.name}_$cName", cName) ?: cName
                                val origData = p.mainPage.find { it.name == cName }?.data ?: ""
                                val newConfig = EvoCatalogConfig(p.name, cName, customName, origData, dynamicOrder)
                                val combinedData = UltimaEvoPlugin.gson.toJson(newConfig)
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
        return withContext(Dispatchers.IO) {
            try {
                val config = UltimaEvoPlugin.gson.fromJson(request.data, EvoCatalogConfig::class.java)
                if (config == null) return@withContext newHomePageResponse(emptyList(), false)

                val targetProvider = APIHolder.allProviders.find { it.name == config.providerName } 
                    ?: return@withContext newHomePageResponse(emptyList(), false)

                val originalRequest = MainPageRequest(request.name, config.originalData, request.horizontalImages)
                
                val response = withTimeoutOrNull(15000L) {
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
        return withContext(Dispatchers.IO) {
            val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
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
            // 1:1 매칭 Fail-Fast! 쓰레기 도메인 폴백 싹 다 날림.
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return emptyList()

        val activeCatsJson = prefs.getString("evo_active_cats_json_$instanceId", "") ?: ""
        if (activeCatsJson.isBlank()) return emptyList()

        val providersToSearch = try {
            val listType = object : TypeToken<List<EvoCatalogConfig>>() {}.type
            val configs: List<EvoCatalogConfig> = UltimaEvoPlugin.gson.fromJson(activeCatsJson, listType)
            configs.map { it.providerName }.distinct()
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }

        val semaphore = Semaphore(5)

        return coroutineScope {
            val deferredResults = providersToSearch.mapNotNull { providerName ->
                val provider = APIHolder.allProviders.find { it.name == providerName } ?: return@mapNotNull null
                async<List<SearchResponse>>(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            withTimeoutOrNull(15000L) {
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
