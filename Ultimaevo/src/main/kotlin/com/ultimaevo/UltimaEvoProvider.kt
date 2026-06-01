// v1.53
package com.ultimaevo

import android.content.Context
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

/**
 * 백그라운드 및 설정 창 수동 싱크를 공통으로 담당하는 주기적 백그라운드 자동 싱크 엔진
 */
object UltimaEvoSyncManager {
    private var isSyncing = false
    private const val SYNC_THROTTLE_MS = 6 * 60 * 60 * 1000L // 6시간 주기 제한

    fun triggerAutoSync(context: Context) {
        if (isSyncing) return

        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val lastSync = prefs.getLong("evo_last_sync_time", 0L)
        val now = System.currentTimeMillis()

        if (now - lastSync < SYNC_THROTTLE_MS) return

        isSyncing = true
        CoroutineScope(Dispatchers.IO).launch {
            try {
                performSync(context, null)
            } catch (e: Exception) {
                // 백그라운드 작동 시 예외 무시
            } finally {
                isSyncing = false
            }
        }
    }

    suspend fun performSync(
        context: Context,
        onProgress: (suspend (current: Int, total: Int) -> Unit)? = null
    ) {
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val instanceCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        // 활성화되어 있거나 혹은 자동 추가가 On으로 켜진 프로바이더만 선별하여 동기화
        val enabledProviders = APIHolder.allProviders.filter { provider ->
            !provider.mainUrl.startsWith("ultima_evo") && provider.hasMainPage &&
            (1..instanceCount).any { instId ->
                prefs.getBoolean("ext_toggle_${instId}_${provider.name}", false) ||
                prefs.getBoolean("auto_add_${instId}_${provider.name}", false)
            }
        }

        if (enabledProviders.isEmpty()) return

        val total = enabledProviders.size
        var current = 0
        val semaphore = Semaphore(3)
        var anyChange = false

        coroutineScope {
            val syncJobs = enabledProviders.map { provider ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
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
                        } catch (e: Exception) {
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

    // 동적 생성에 최적화된 메모리 캐시 저장소
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

            // 백그라운드 자동 싱크 트리거 검사 (6시간 주기 검사)
            UltimaEvoSyncManager.triggerAutoSync(context)

            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) return emptyList()

            // 유효한 메모리 캐시가 존재하는 경우 빌드 생략 및 즉시 반환
            if (cachedMainPageList.isNotEmpty()) {
                return cachedMainPageList
            }

            // 인스턴스 내에서 활성화되어 있거나 개별 Auto-add 설정이 보장된 모든 프로바이더 수집
            val targetProviders = APIHolder.allProviders.filter { provider ->
                !provider.mainUrl.startsWith("ultima_evo") && (
                    prefs.getBoolean("ext_toggle_${instanceId}_${provider.name}", false) ||
                    prefs.getBoolean("auto_add_${instanceId}_${provider.name}", false)
                )
            }

            val pages = mutableListOf<Pair<MainPageData, Int>>()

            for (provider in targetProviders) {
                val isAutoAddEnabled = prefs.getBoolean("auto_add_${instanceId}_${provider.name}", false)

                val staticCats = provider.mainPage.map { it.name }
                val dynamicCats = prefs.getString("dynamic_catalogs_${provider.name}", "")?.split("|||") ?: emptyList()
                val combinedCats = (staticCats + dynamicCats).filter { it.isNotBlank() }.distinct()

                for (cName in combinedCats) {
                    val tKey = "cat_toggle_${instanceId}_${provider.name}_$cName"
                    val rKey = "cat_rename_${instanceId}_${provider.name}_$cName"
                    val oKey = "cat_order_${instanceId}_${provider.name}_$cName"

                    // 명시적 설정이 없을 때는 프로바이더 개별 Auto-add를 기본 필터 플래그로 활용 (블랙/화이트 통합 처리)
                    val isEnabled = prefs.getBoolean(tKey, isAutoAddEnabled)

                    if (isEnabled) {
                        val customCatalogName = prefs.getString(rKey, cName)?.ifBlank { cName } ?: cName
                        val orderIndex = prefs.getString(oKey, "999")?.toIntOrNull() ?: 999
                        
                        val originalData = provider.mainPage.find { it.name == cName }?.data 
                            ?: provider.mainPage.firstOrNull()?.data 
                            ?: ""

                        val combinedData = "${provider.name}|||$originalData|||$customCatalogName|||$cName"
                        pages.add(Pair(MainPageData(customCatalogName, combinedData), orderIndex))
                    }
                }
            }
            
            cachedMainPageList = pages.sortedBy { it.second }.map { it.first }
            return cachedMainPageList
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        return withContext(Dispatchers.IO) {
            try {
                val parts = request.data.split("|||")
                if (parts.size < 3) return@withContext newHomePageResponse(emptyList(), false)

                val targetProviderName = parts[0]
                val originalData = parts[1]
                val customCatalogTitle = parts[2]
                val originalCatalogName = parts.getOrNull(3) ?: ""

                val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName } ?: return@withContext newHomePageResponse(emptyList(), false)

                val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
                val response = targetProvider.getMainPage(page, originalRequest) ?: return@withContext newHomePageResponse(emptyList(), false)
                
                val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                    response.items.filter { it.name == originalCatalogName }
                } else {
                    response.items
                }

                val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
                newHomePageResponse(modifiedItems, response.hasNext)
            } catch (e: Exception) {
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
                    if (e is CancellationException) throw e
                }
            }
            
            val domainMatchProviders = allProviders.filter { p -> 
                val domain = p.mainUrl.replace("https://", "").replace("http://", "").substringBefore("/")
                domain.isNotBlank() && url.contains(domain) 
            }
            for (p in domainMatchProviders) {
                if (p == exactProvider) continue
                try {
                    val res = p.load(url)
                    if (res != null) return@withContext res
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
            null
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return emptyList()

        val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
        if (activeCatsStr.isBlank()) return emptyList()

        val chunkSeparator = if (activeCatsStr.contains("%%%")) "%%%" else "|||"
        val fieldSeparator = if (activeCatsStr.contains("%%%")) ";;;" else "|~|"

        val providersToSearch = activeCatsStr.split(chunkSeparator).mapNotNull { chunk ->
            val parts = chunk.split(fieldSeparator, limit = 5)
            if (parts.isNotEmpty()) parts[0] else null
        }.distinct()

        val semaphore = Semaphore(5)

        return coroutineScope {
            val deferredResults = providersToSearch.mapNotNull { providerName ->
                val provider = APIHolder.allProviders.find { it.name == providerName } ?: return@mapNotNull null
                async<List<SearchResponse>>(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            println("[UltimaEvo-Debug] [Provider-$instanceId] ${provider.name} 검색 시도")
                            provider.search(query) ?: emptyList()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            println("[UltimaEvo-Debug] [Provider-$instanceId] ${provider.name} 에러 무시: ${e.message}")
                            emptyList()
                        }
                    }
                }
            }
            
            val results: List<SearchResponse> = deferredResults.awaitAll().flatten()
            val finalRes: List<SearchResponse> = results.distinctBy { it.url }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 최종 결과: ${finalRes.size}개")
            finalRes
        }
    }
}
