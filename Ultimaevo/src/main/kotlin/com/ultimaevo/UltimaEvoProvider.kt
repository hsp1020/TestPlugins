// v1.32
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

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 인스턴스 생성됨. 이름: $customName")
    }

    private fun getUserSettings(): List<SavedCatalog> {
        println("[UltimaEvo-Debug] [Provider-$instanceId] getUserSettings() 호출됨")
        val context = UltimaEvoPlugin.pluginContext
        if (context == null) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 치명적 에러: pluginContext가 널입니다!")
            return emptyList()
        }
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val settings = mutableListOf<SavedCatalog>()
        
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && !it.mainUrl.startsWith("ultima_evo") }
        println("[UltimaEvo-Debug] [Provider-$instanceId] APIHolder 타 플러그인 개수: ${allProviders.size}")
        if (allProviders.isEmpty()) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 경고: APIHolder가 비어있습니다. 로딩 타이밍 이슈 가능성!")
        }

        for (provider in allProviders) {
            val toggleKey = "ext_toggle_${instanceId}_${provider.name}"
            val providerEnabled = prefs.getBoolean(toggleKey, false)
            println("[UltimaEvo-Debug] [Provider-$instanceId] 체크: ${provider.name} 전체 활성화 상태=$providerEnabled (Key: $toggleKey)")
            
            if (!providerEnabled) continue

            val staticCats = provider.mainPage.map { it.name to it.data }
            val cachedDynStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""
            val dynamicCats = if (cachedDynStr.isNotBlank()) cachedDynStr.split("|||").map { it to it } else emptyList()
            val allCats = (staticCats + dynamicCats).distinctBy { it.first }

            println("[UltimaEvo-Debug] [Provider-$instanceId] -> ${provider.name}의 총 감지된 카탈로그 수: ${allCats.size}")

            for (catPair in allCats) {
                val catalogName = catPair.first
                val catalogData = catPair.second

                val catToggleKey = "cat_toggle_${instanceId}_${provider.name}_$catalogName"
                val catalogEnabled = prefs.getBoolean(catToggleKey, false)
                println("[UltimaEvo-Debug] [Provider-$instanceId]   -> 체크: [$catalogName] 활성화=$catalogEnabled (Key: $catToggleKey)")
                
                if (!catalogEnabled) continue

                val renameKey = "cat_rename_${instanceId}_${provider.name}_$catalogName"
                val renamed = prefs.getString(renameKey, catalogName) ?: catalogName
                
                val orderKey = "cat_order_${instanceId}_${provider.name}_$catalogName"
                val orderStr = prefs.getString(orderKey, "99") ?: "99"
                val orderIndex = orderStr.toIntOrNull() ?: 99

                println("[UltimaEvo-Debug] [Provider-$instanceId]   -> 추가됨: 원래이름=$catalogName, 새이름=$renamed, 순서=$orderIndex")

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
        val finalSettings = settings.sortedBy { it.orderIndex }
        println("[UltimaEvo-Debug] [Provider-$instanceId] getUserSettings() 종료. 최종 반환 목록 수: ${finalSettings.size}")
        return finalSettings
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage(홈 리스트 렌더링) 접근됨")
            val settings = getUserSettings()
            val pages = settings.map { catalog ->
                val combinedData = "${catalog.providerName}|||${catalog.originalCatalogData}|||${catalog.customCatalogName}|||${catalog.originalCatalogName}"
                println("[UltimaEvo-Debug] [Provider-$instanceId] MainPageData 생성 -> 이름: ${catalog.customCatalogName}, 데이터길이: ${combinedData.length}")
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
                println("[UltimaEvo-Debug] [Provider-$instanceId] 에러: request.data 파싱 실패 (요소 부족)")
                return newHomePageResponse(emptyList(), false)
            }

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customCatalogTitle = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""
            println("[UltimaEvo-Debug] [Provider-$instanceId] 타겟 프로바이더: $targetProviderName, 원본카탈로그: $originalCatalogName")

            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName }
            if (targetProvider == null) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 에러: 타겟 프로바이더를 APIHolder에서 찾지 못함!")
                return newHomePageResponse(emptyList(), false)
            }

            val originalRequest = MainPageRequest(request.name, originalData, request.horizontalImages)
            println("[UltimaEvo-Debug] [Provider-$instanceId] $targetProviderName 에 getMainPage 패스스루 실행")
            val response = targetProvider.getMainPage(page, originalRequest)
            
            if (response == null) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 경고: 타겟 프로바이더로부터 null 반환됨")
                return newHomePageResponse(emptyList(), false)
            }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 응답 성공. 아이템 수: ${response.items.size}")

            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 필터링 수행: $originalCatalogName 만 추출")
                response.items.filter { it.name == originalCatalogName }
            } else {
                response.items
            }

            val modifiedItems = filteredItems.map { it.copy(name = customCatalogTitle) }
            println("[UltimaEvo-Debug] [Provider-$instanceId] 최종 반환될 필터링된 아이템 수: ${modifiedItems.size}")
            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] getMainPage 중 예외 폭발: ${e.message}")
            e.printStackTrace()
            return newHomePageResponse(emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        println("[UltimaEvo-Debug] [Provider-$instanceId] search() 호출됨 - 쿼리: $query")
        val context = UltimaEvoPlugin.pluginContext
        if (context == null) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 에러: search() 중 pluginContext가 널임")
            return emptyList()
        }
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val isSearchEnabled = prefs.getBoolean("evo_search_enabled_$instanceId", true)
        if (!isSearchEnabled) {
            println("[UltimaEvo-Debug] [Provider-$instanceId] 검색 기능 꺼져있음. 빈 리스트 반환")
            return emptyList()
        }

        val settings = getUserSettings()
        val providersToSearch = settings.map { it.providerName }.distinct()
        println("[UltimaEvo-Debug] [Provider-$instanceId] 검색을 위임할 프로바이더: $providersToSearch")
        
        val results = mutableListOf<SearchResponse>()

        for (providerName in providersToSearch) {
            val provider = APIHolder.allProviders.find { it.name == providerName } ?: continue
            try {
                println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 에 검색 지시")
                val searchRes = provider.search(query)
                if (searchRes != null) {
                    println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 검색 결과: ${searchRes.size}개 획득")
                    results.addAll(searchRes)
                }
            } catch (e: Exception) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] $providerName 검색 중 에러: ${e.message}")
            }
        }
        val finalRes = results.distinctBy { it.url }
        println("[UltimaEvo-Debug] [Provider-$instanceId] 총 중복제거된 검색 결과: ${finalRes.size}개")
        return finalRes
    }
}
