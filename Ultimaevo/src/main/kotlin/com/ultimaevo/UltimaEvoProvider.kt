package com.ultimaevo

import android.content.Context
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    override val mainPage: List<MainPageData>
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) return emptyList()

            val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
            if (activeCatsStr.isBlank()) return emptyList()

            val pages = activeCatsStr.split("|||").mapNotNull {
                val parts = it.split("|~|")
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
            
            return pages.sortedBy { it.second }.map { it.first }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val parts = request.data.split("|||")
            if (parts.size < 3) return newHomePageResponse(emptyList(), false)

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customCatalogTitle = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""

            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName } ?: return newHomePageResponse(emptyList(), false)

            val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
            val response = targetProvider.getMainPage(page, originalRequest) ?: return newHomePageResponse(emptyList(), false)
            
            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                response.items.filter { it.name == originalCatalogName }
            } else {
                response.items
            }

            val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
    }

    // [전문가 피드백 적용] 순차적 for문 제거 -> async/awaitAll을 이용한 완벽한 병렬(Concurrent) 비동기 검색
    override suspend fun search(query: String): List<SearchResponse> = coroutineScope {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 요청됨: $query")
        val context = UltimaEvoPlugin.pluginContext ?: return@coroutineScope emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return@coroutineScope emptyList()

        val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
        if (activeCatsStr.isBlank()) return@coroutineScope emptyList()

        val providersToSearch = activeCatsStr.split("|||").mapNotNull {
            val parts = it.split("|~|")
            if (parts.isNotEmpty()) parts[0] else null
        }.distinct()

        // 여러 프로바이더에 동시에 검색 요청을 던집니다 (Non-blocking)
        val deferredResults = providersToSearch.mapNotNull { providerName ->
            val provider = APIHolder.allProviders.find { it.name == providerName } ?: return@mapNotNull null
            async(Dispatchers.IO) {
                try {
                    provider.search(query) ?: emptyList()
                } catch (e: Exception) {
                    emptyList() // 개별 프로바이더 에러가 전체 검색을 망치지 않도록 안전망 구성
                }
            }
        }
        
        // 모든 병렬 요청이 끝날 때까지 기다린 후 결과를 하나로 합칩니다.
        val results = deferredResults.awaitAll().flatten()
        val finalRes = results.distinctBy { it.url }
        println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 완료. 결과: ${finalRes.size}개")
        return@coroutineScope finalRes
    }
}
