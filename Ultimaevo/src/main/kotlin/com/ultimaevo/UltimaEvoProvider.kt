// v1.47
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    override val hasMainPage: Boolean
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return true
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
        }

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 메모리 할당 완료: $customName")
    }

    override val mainPage: List<MainPageData>
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) return emptyList()

            val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
            if (activeCatsStr.isBlank()) return emptyList()

            // [핵심 픽스] 충돌하지 않는 안전한 구분자로 데이터 추출 (구버전 호환성 포함)
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
                } else {
                    println("[UltimaEvo-Debug] [Provider-$instanceId] ❌ 매핑 실패! (파트 개수 부족: ${parts.size}) -> $chunk")
                    null
                }
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

    // [핵심 픽스] NotImplementedError 크래시 방지를 위한 미리보기(load) 우회 라우터
    override suspend fun load(url: String): LoadResponse? {
        println("[UltimaEvo-Debug] [Provider-$instanceId] load() 우회 라우팅 요청됨: $url")
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        
        // 1순위: URL이 정확히 일치하는 프로바이더 탐색
        val exactProvider = allProviders.find { url.startsWith(it.mainUrl) }
        if (exactProvider != null) {
            try {
                val res = exactProvider.load(url)
                if (res != null) return res
            } catch (e: Exception) { }
        }
        
        // 2순위: 도메인이 포함된 프로바이더 탐색 (정확도 높음)
        val domainMatchProviders = allProviders.filter { p -> 
            val domain = p.mainUrl.replace("https://", "").replace("http://", "").substringBefore("/")
            domain.isNotBlank() && url.contains(domain) 
        }
        for (p in domainMatchProviders) {
            if (p == exactProvider) continue
            try {
                val res = p.load(url)
                if (res != null) return res
            } catch (e: Exception) { }
        }
        return null
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

        return coroutineScope {
            val deferredResults = providersToSearch.mapNotNull { providerName ->
                val provider = APIHolder.allProviders.find { it.name == providerName } ?: return@mapNotNull null
                async<List<SearchResponse>>(Dispatchers.IO) {
                    try {
                        val res = provider.search(query)
                        res ?: emptyList()
                    } catch (e: Exception) {
                        emptyList()
                    }
                }
            }
            
            val results: List<SearchResponse> = deferredResults.awaitAll().flatten()
            val finalRes: List<SearchResponse> = results.distinctBy { it.url }
            finalRes
        }
    }
}
