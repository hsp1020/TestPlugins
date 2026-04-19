// v1.46
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class UltimaEvoProvider(private val instanceId: Int, private val customName: String) : MainAPI() {
    override var mainUrl = "ultima_evo_$instanceId"
    override var name = customName
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    // [핵심 픽스] 고정값(true)이었던 hasMainPage를 실시간 토글 값으로 연동(Dynamic Getter)
    override val hasMainPage: Boolean
        get() {
            val context = UltimaEvoPlugin.pluginContext ?: return true
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            println("[UltimaEvo-Debug] [Provider-$instanceId] hasMainPage 호출됨. 표시 여부: $isVisible")
            return isVisible
        }

    init {
        println("[UltimaEvo-Debug] [Provider-$instanceId] 메모리 할당 완료: $customName")
    }

    override val mainPage: List<MainPageData>
        get() {
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage(홈 탭 렌더링) 접근됨")
            val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
            val prefs = context.getSharedPreferences(UltimaEvoPlugin.PREFS_NAME, Context.MODE_PRIVATE)
            
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$instanceId", true)
            if (!isVisible) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 인스턴스 표시(Show Catalog)가 OFF 상태입니다. 홈에 그리지 않습니다.")
                return emptyList()
            }

            val activeCatsStr = prefs.getString("evo_active_cats_$instanceId", "") ?: ""
            println("[UltimaEvo-Debug] [Provider-$instanceId] 저장소에서 읽어온 카탈로그 Raw Text: $activeCatsStr")
            if (activeCatsStr.isBlank()) {
                println("[UltimaEvo-Debug] [Provider-$instanceId] 활성화된 카탈로그가 없습니다.")
                return emptyList()
            }

            val rawList = activeCatsStr.split("|||")
            println("[UltimaEvo-Debug] [Provider-$instanceId] 파싱할 카탈로그 청크 총 개수: ${rawList.size}")

            val pages = rawList.mapNotNull { chunk ->
                // [핵심 픽스] limit = 5 옵션을 부여하여 마지막 항목(Data)이 비어있어도 무시하고 살려냅니다.
                val parts = chunk.split("|~|", limit = 5)
                println("[UltimaEvo-Debug] [Provider-$instanceId] 데이터 파싱 중... 쪼개진 파트 개수: ${parts.size}, 원본 텍스트: $chunk")
                
                if (parts.size >= 5) {
                    val pName = parts[0]
                    val cName = parts[1]
                    val customCatalogName = parts[2]
                    val orderIndex = parts[3].toIntOrNull() ?: 99
                    val originalData = parts[4] // 여기가 빈칸("")일 경우 기존에는 크래시가 났음
                    
                    val combinedData = "$pName|||$originalData|||$customCatalogName|||$cName"
                    println("[UltimaEvo-Debug] [Provider-$instanceId] ✔ 매핑 성공 -> 순서: $orderIndex | 이름: $customCatalogName | Provider: $pName | URL/Data: '$originalData'")
                    Pair(MainPageData(customCatalogName, combinedData), orderIndex)
                } else {
                    println("[UltimaEvo-Debug] [Provider-$instanceId] ❌ 매핑 실패! (파트 개수 부족: ${parts.size} < 5) -> $chunk")
                    null
                }
            }
            
            val sortedPages = pages.sortedBy { it.second }.map { it.first }
            println("[UltimaEvo-Debug] [Provider-$instanceId] mainPage 반환 완료. 렌더링될 탭 개수: ${sortedPages.size}")
            return sortedPages
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

        val providersToSearch = activeCatsStr.split("|||").mapNotNull { chunk ->
            // 여기도 limit = 5 추가 적용
            val parts = chunk.split("|~|", limit = 5)
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
            println("[UltimaEvo-Debug] [Provider-$instanceId] 병렬 검색 완료. 결과: ${finalRes.size}개")
            finalRes
        }
    }
}
