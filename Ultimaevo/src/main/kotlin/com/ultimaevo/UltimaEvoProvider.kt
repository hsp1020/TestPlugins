// v1.30
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

    private fun getUserSettings(): List<SavedCatalog> {
        println("[UltimaEvo-Debug] Provider-$instanceId getUserSettings() 호출됨")
        val context = UltimaEvoPlugin.pluginContext
        if (context == null) {
            println("[UltimaEvo-Debug] Provider-$instanceId 에러: pluginContext가 null입니다!")
            return emptyList()
        }
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val settings = mutableListOf<SavedCatalog>()
        
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        println("[UltimaEvo-Debug] Provider-$instanceId 현재 로드된 타 확장프로그램 수: ${allProviders.size}")

        if (allProviders.isEmpty()) {
            println("[UltimaEvo-Debug] Provider-$instanceId 경고: 타 확장프로그램이 아직 로드되지 않았거나 없습니다!")
        }

        for (provider in allProviders) {
            val providerEnabled = prefs.getBoolean("ext_toggle_${instanceId}_${provider.name}", false)
            if (!providerEnabled) continue

            val staticCats = provider.mainPage.map { it.name to it.data }
            val cachedDynStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""
            val dynamicCats = if (cachedDynStr.isNotBlank()) cachedDynStr.split("|||").map { it to it } else emptyList()
            val allCats = (staticCats + dynamicCats).distinctBy { it.first }

            for (catPair in allCats) {
                val catalogName = catPair.first
                val catalogData = catPair.second

                val catalogEnabled = prefs.getBoolean("cat_toggle_${instanceId}_${provider.name}_$catalogName", false)
                if (!catalogEnabled) continue

                val renamed = prefs.getString("cat_rename_${instanceId}_${provider.name}_$catalogName", catalogName) ?: catalogName
                val orderStr = prefs.getString("cat_order_${instanceId}_${provider.name}_$catalogName", "99") ?: "99"
                val orderIndex = orderStr.toIntOrNull() ?: 99

                settings.add(
                    SavedCatalog(
                        providerName = provider.name,
                        originalCatalogName = catalogName,
                        originalCatalogData = catalogData,
                        customCatalogName = renamed,
                        isEnabled = true,
                        orderIndex = orderIndex
                    )
                )
            }
        }
        val sortedSettings = settings.sortedBy { it.orderIndex }
        println("[UltimaEvo-Debug] Provider-$instanceId 최종 반환될 카탈로그 개수: ${sortedSettings.size}")
        return sortedSettings
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug] Provider-$instanceId mainPage(홈 화면 리스트) 접근 시작")
            val settings = getUserSettings()
            val pages = settings.map { catalog ->
                val combinedData = "${catalog.providerName}|||${catalog.originalCatalogData}|||${catalog.customCatalogName}|||${catalog.originalCatalogName}"
                MainPageData(catalog.customCatalogName, combinedData)
            }
            println("[UltimaEvo-Debug] Provider-$instanceId mainPage 접근 완료. 매핑된 카탈로그 수: ${pages.size}")
            return pages
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        println("[UltimaEvo-Debug] Provider-$instanceId getMainPage() 호출됨 - Page: $page, RequestName: ${request.name}")
        try {
            val parts = request.data.split("|||")
            if (parts.size < 3) {
                println("[UltimaEvo-Debug] Provider-$instanceId 에러: 데이터 파싱 실패 (parts size < 3)")
                return newHomePageResponse(emptyList(), false)
            }

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customCatalogTitle = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""

            println("[UltimaEvo-Debug] Provider-$instanceId 타겟 프로바이더 탐색: $targetProviderName")
            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName }
            if (targetProvider == null) {
                println("[UltimaEvo-Debug] Provider-$instanceId 에러: 타겟 프로바이더($targetProviderName)를 찾을 수 없음!")
                return newHomePageResponse(emptyList(), false)
            }

            val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
            println("[UltimaEvo-Debug] Provider-$instanceId $targetProviderName 통신 요청 시작")
            val response = targetProvider.getMainPage(page, originalRequest)
            
            if (response == null) {
                println("[UltimaEvo-Debug] Provider-$instanceId 경고: $targetProviderName 로부터 응답(null)을 받음")
                return newHomePageResponse(emptyList(), false)
            }
            
            println("[UltimaEvo-Debug] Provider-$instanceId $targetProviderName 통신 성공. 아이템 수: ${response.items.size}")
            
            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                response.items.filter { it.name == originalCatalogName }
            } else response.items

            val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            println("[UltimaEvo-Debug] Provider-$instanceId 예외 발생: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[UltimaEvo-Debug] Provider-$instanceId 검색 호출됨 - Query: $query")
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) {
            println("[UltimaEvo-Debug] Provider-$instanceId 검색 기능이 비활성화되어 있음")
            return emptyList()
        }

        val settings = getUserSettings()
        val providersToSearch = settings.map { it.providerName }.distinct()
        println("[UltimaEvo-Debug] Provider-$instanceId 검색할 프로바이더 목록: $providersToSearch")
        
        val results = mutableListOf<SearchResponse>()

        for (providerName in providersToSearch) {
            val provider = APIHolder.allProviders.find { it.name == providerName } ?: continue
            try {
                println("[UltimaEvo-Debug] Provider-$instanceId $providerName 에 검색 요청 중...")
                val searchRes = provider.search(query)
                if (searchRes != null) {
                    println("[UltimaEvo-Debug] Provider-$instanceId $providerName 검색 성공. 아이템: ${searchRes.size}개")
                    results.addAll(searchRes)
                }
            } catch (e: Exception) { 
                println("[UltimaEvo-Debug] Provider-$instanceId $providerName 검색 중 예외 발생: ${e.message}")
            }
        }
        return results.distinctBy { it.url }
    }
}
