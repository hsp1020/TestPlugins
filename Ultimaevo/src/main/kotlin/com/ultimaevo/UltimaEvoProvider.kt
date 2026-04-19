// v1.45
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
import kotlinx.coroutines.Dispatchers // [수정] 누락된 Dispatchers Import 추가
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 메모리 할당 완료: $customName")
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage(홈 탭 렌더링) 접근됨")
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
        println("[UltimaEvo-Debug] [Provider-$instanceId] getMainPage() 통신 요청됨 - Page: $page, Name: ${request.name}")
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

    override suspend fun search(query: String): List<SearchResponse> {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 요청됨: $query")
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return emptyList()

        val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
        if (activeCatsStr.isBlank()) return emptyList()

        val providersToSearch = activeCatsStr.split("|||").mapNotNull {
            val parts = it.split("|~|")
            if (parts.isNotEmpty()) parts[0] else null
        }.distinct()

        // [수정] 컴파일러 에러 방지를 위해 명시적 타입 선언 및 블록 지정
        return coroutineScope {
            val deferredResults = providersToSearch.mapNotNull { providerName ->
                val provider = APIHolder.allProviders.find { it.name == providerName } ?: return@mapNotNull null
                
                // async 블록의 리턴 타입을 <List<SearchResponse>>로 명시적으로 고정
                async<List<SearchResponse>>(Dispatchers.IO) {
                    try {
                        val res = provider.search(query)
                        res ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            
            // 타입 추론 에러를 막기 위해 변수 타입 명시
            val results: List<SearchResponse> = deferredResults.awaitAll().flatten()
            val finalRes: List<SearchResponse> = results.distinctBy { it.url }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 완료. 결과: ${finalRes.size}개")
            finalRes
        }
    }
}
