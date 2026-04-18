// v1.29
package com.ultimaevo

import android.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    override val mainPage: List<MainPageData>
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            
            // [핵심 픽스] 다른 플러그인을 뒤적거리지 않고, 미리 구워진(Baked) 문자열만 즉시 읽어옵니다.
            val bakedStr = prefs.getString("evo_baked_catalogs_$instanceId", "") ?: ""
            if (bakedStr.isBlank()) return emptyList()

            return bakedStr.split("|||").mapNotNull {
                val parts = it.split("|~|")
                if (parts.size >= 4) {
                    val pName = parts[0]
                    val oData = parts[1]
                    val cName = parts[2]
                    val oName = parts[3]
                    val combinedData = "$pName|||$oData|||$cName|||$oName"
                    MainPageData(cName, combinedData)
                } else null
            }
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        try {
            val parts = request.data.split("|||")
            if (parts.size < 3) return newHomePageResponse(emptyList(), false)

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customCatalogTitle = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""

            // 이 시점(사용자가 탭을 누른 후)에는 모든 플러그인이 100% 로드되어 있으므로 안전합니다.
            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName } ?: return newHomePageResponse(emptyList(), false)

            val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
            val response = targetProvider.getMainPage(page, originalRequest) ?: return newHomePageResponse(emptyList(), false)
            
            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                response.items.filter { it.name == originalCatalogName }
            } else response.items

            val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) return emptyList()

        val bakedStr = prefs.getString("evo_baked_catalogs_$instanceId", "") ?: ""
        if (bakedStr.isBlank()) return emptyList()

        // 구워진 데이터에서 활성화된 프로바이더 이름만 추출하여 검색을 수행합니다.
        val activeProviders = bakedStr.split("|||").mapNotNull { 
            val parts = it.split("|~|")
            if (parts.isNotEmpty()) parts[0] else null
        }.distinct()

        val results = mutableListOf<SearchResponse>()

        for (providerName in activeProviders) {
            val provider = APIHolder.allProviders.find { it.name == providerName } ?: continue
            try {
                val searchRes = provider.search(query)
                if (searchRes != null) {
                    results.addAll(searchRes)
                }
            } catch (e: Exception) { }
        }
        return results.distinctBy { it.url }
    }
}
