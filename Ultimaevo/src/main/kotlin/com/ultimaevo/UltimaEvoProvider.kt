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

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 메모리 할당 완료: $customName")
    }

    private fun getUserSettings(): List<SavedCatalog> {
        println("[UltimaEvo-Debug] [Provider-$instanceId] getUserSettings() 호출됨")
        val context = UltimaEvoPlugin.pluginContext
        if (context == null) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 치명적 에러: pluginContext Null")
            return emptyList()
        }
        
        // [핵심] 베이킹 없이, 매번 디스크에서 최신 설정을 직접 긁어옵니다.
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        val settings = mutableListOf<SavedCatalog>()
        
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        println("[UltimaEvo-Debug] [Provider-$instanceId] 현재 메모리에 로드된 타 플러그인 수: ${allProviders.size}")

        for (provider in allProviders) {
            val providerEnabled = prefs.getBoolean("ext_toggle_${instanceId}_${provider.name}", false)
            println("[UltimaEvo-Debug] [Provider-$instanceId] 플러그인 확인: ${provider.name} 활성화 상태=$providerEnabled")
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

                println("[UltimaEvo-Debug] [Provider-$instanceId] -> 통과된 카탈로그: $renamed (원본: $catalogName, 순서: $orderIndex)")

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
        val sorted = settings.sortedBy { it.orderIndex }
        println("[UltimaEvo-Debug] [Provider-$instanceId] getUserSettings() 완료. 총 추출된 카탈로그 수: ${sorted.size}")
        return sorted
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage(홈 탭 렌더링) 접근됨")
            val settings = getUserSettings()
            val pages = settings.map { catalog ->
                val combinedData = "${catalog.providerName}|||${catalog.originalCatalogData}|||${catalog.customCatalogName}|||${catalog.originalCatalogName}"
                MainPageData(catalog.customCatalogName, combinedData)
            }
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage 반환 완료. 총 페이지 수: ${pages.size}")
            return pages
        }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse? {
        println("[UltimaEvo-Debug] [Provider-$instanceId] getMainPage() 통신 요청됨 - Page: $page, Name: ${request.name}")
        try {
            val parts = request.data.split("|||")
            if (parts.size < 3) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 에러: request.data 분리 실패")
                return newHomePageResponse(emptyList(), false)
            }

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customCatalogTitle = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""
            println("[UltimaEvo-Debug] [Provider-$instanceId] 타겟 프로바이더: $targetProviderName, 카탈로그: $originalCatalogName")

            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName }
            if (targetProvider == null) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 에러: 타겟 프로바이더를 APIHolder에서 찾지 못함!")
                return newHomePageResponse(emptyList(), false)
            }

            val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
            println("[UltimaEvo-Debug] [Provider-$instanceId] $targetProviderName 에 라이브 패스스루 실행")
            val response = targetProvider.getMainPage(page, originalRequest)
            
            if (response == null) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 경고: 프로바이더로부터 Null 반환됨")
                return newHomePageResponse(emptyList(), false)
            }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 통신 성공. 아이템: ${response.items.size}개")
            
            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                response.items.filter { it.name == originalCatalogName }
            } else {
                response.items
            }

            val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 필터링 적용 완료. 최종 반환 아이템: ${modifiedItems.size}개")
            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 통신 중 예외 발생: ${e.message}")
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 검색 요청됨: $query")
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
        
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 검색 비활성화 됨")
            return emptyList()
        }

        val settings = getUserSettings()
        val providersToSearch = settings.map { it.providerName }.distinct()
        println("[UltimaEvo-Debug] [Provider-$instanceId] 검색 위임 대상: $providersToSearch")
        
        val results = mutableListOf<SearchResponse>()

        for (providerName in providersToSearch) {
            val provider = APIHolder.allProviders.find { it.name == providerName } ?: continue
            try {
                println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 에 검색 지시")
                val searchRes = provider.search(query)
                if (searchRes != null) {
                    println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 검색 성공: ${searchRes.size}개")
                    results.addAll(searchRes)
                }
            } catch (e: Exception) { 
                println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 검색 중 에러: ${e.message}")
            }
        }
        val finalRes = results.distinctBy { it.url }
        println("[UltimaEvo-Debug] [Provider-$instanceId] 총 검색 완료. 최종 반환: ${finalRes.size}개")
        return finalRes
    }
}
