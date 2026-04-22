// v1.53 (Fix: PreferenceManager 의존성 에러 해결을 위해 네이티브 방식으로 글로벌 설정 로드)
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    private var cachedActiveCatsStr: String? = null
    private var cachedMainPageList: List<MainPageData> = emptyList()

    override val hasMainPage: Boolean
        get() {
            println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] hasMainPage 호출됨")
            val context = UltimaEvoPlugin.pluginContext ?: return true
            // [v1.53 수정] 네이티브 SharedPreferences 패키지명 직접 참조
            val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            return prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
        }

    init {
        println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] 인스턴스 할당됨: $customName")
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] mainPage 호출됨")
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
            // [v1.53 수정] 네이티브 SharedPreferences 패키지명 직접 참조
            val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
            
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) return emptyList()

            val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
            if (activeCatsStr.isBlank()) return emptyList()

            if (activeCatsStr == cachedActiveCatsStr) {
                return cachedMainPageList
            }

            val chunkSeparator = if (activeCatsStr.contains("%%%")) "%%%" else "|||"
            val fieldSeparator = if (activeCatsStr.contains("%%%")) ";;;" else "|~|"

            val rawList = activeCatsStr.split(chunkSeparator)
            val pages = rawList.mapNotNull { chunk ->
                val parts = chunk.split(fieldSeparator, limit = 5)
                
                if (parts.size >= 5) {
                    val pName = parts[0]
                    val cName = parts[1]
                    val customCatalogName = parts[2]
                    val orderIndex = parts[3].toIntOrNull() ?: 99
                    val originalData = parts[4]
                    
                    val combinedData = "$pName|||$originalData|||$customCatalogName|||$cName"
                    Pair(MainPageData(customCatalogName, combinedData), orderIndex)
                } else null
            }
            
            cachedMainPageList = pages.sortedBy { it.second }.map { it.first }
            cachedActiveCatsStr = activeCatsStr
            
            println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] 메인 카탈로그 파싱 완료")
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
        // [v1.53 수정] 네이티브 SharedPreferences 패키지명 직접 참조
        val prefs = context.getSharedPreferences("${context.packageName}_preferences", Context.MODE_PRIVATE)
        
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
                            println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] ${provider.name} 검색 시도")
                            provider.search(query) ?: emptyList()
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e 
                            emptyList()
                        }
                    }
                }
            }
            
            val results: List<SearchResponse> = deferredResults.awaitAll().flatten()
            val finalRes: List<SearchResponse> = results.distinctBy { it.url }
            println("[UltimaEvo-Debug v1.53] [Provider-$instanceId] 병렬 검색 최종 결과: ${finalRes.size}개")
            finalRes
        }
    }
}
