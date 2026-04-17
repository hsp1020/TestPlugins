// v1.15
package com.ultimaevo

import android.preference.PreferenceManager
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageData
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.newHomePageResponse

class UltimaEvoProvider : MainAPI() {
    override var mainUrl = "ultima_evo"
    override var name = "Ultima Evo"
    override val hasMainPage = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.Live)

    private fun getUserSettings(): List<SavedCatalog> {
        val context = UltimaEvoPlugin.pluginContext ?: return emptyList()
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val settings = mutableListOf<SavedCatalog>()
        val allProviders = APIHolder.allProviders.filter { it.hasMainPage && it.name != "Ultima Evo" }

        for (provider in allProviders) {
            val providerEnabled = prefs.getBoolean("ext_toggle_${provider.name}", false)
            if (!providerEnabled) continue

            val staticCats = provider.mainPage.map { it.name to it.data }
            val cachedDynStr = prefs.getString("dynamic_catalogs_${provider.name}", "") ?: ""
            val dynamicCats = if (cachedDynStr.isNotBlank()) cachedDynStr.split("|||").map { it to it } else emptyList()
            val allCats = (staticCats + dynamicCats).distinctBy { it.first }

            for (catPair in allCats) {
                val catalogName = catPair.first
                val catalogData = catPair.second

                val catalogEnabled = prefs.getBoolean("cat_toggle_${provider.name}_${catalogName}", false)
                if (!catalogEnabled) continue

                val customName = prefs.getString("cat_rename_${provider.name}_${catalogName}", catalogName) ?: catalogName
                val orderStr = prefs.getString("cat_order_${provider.name}_${catalogName}", "99") ?: "99"
                val orderIndex = orderStr.toIntOrNull() ?: 99

                settings.add(
                    SavedCatalog(
                        providerName = provider.name,
                        originalCatalogName = catalogName,
                        originalCatalogData = catalogData,
                        customCatalogName = customName,
                        isEnabled = true,
                        orderIndex = orderIndex
                    )
                )
            }
        }
        return settings.sortedBy { it.orderIndex }
    }

    override val mainPage: List<MainPageData>
        get() {
            return getUserSettings().map { catalog ->
                val combinedData = "${catalog.providerName}|||${catalog.originalCatalogData}|||${catalog.customCatalogName}|||${catalog.originalCatalogName}"
                MainPageData(catalog.customCatalogName, combinedData)
            }
        }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse? {
        try {
            val parts = request.data.split("|||")
            if (parts.size < 3) {
                return newHomePageResponse(emptyList(), false)
            }

            val targetProviderName = parts[0]
            val originalData = parts[1]
            val customName = parts[2]
            val originalCatalogName = parts.getOrNull(3) ?: ""

            val targetProvider = APIHolder.allProviders.find { it.name == targetProviderName } ?: return newHomePageResponse(emptyList(), false)

            val originalRequest = MainPageRequest(
                name = request.name,
                data = originalData,
                horizontalImages = request.horizontalImages
            )

            val response = targetProvider.getMainPage(page, originalRequest) ?: return newHomePageResponse(emptyList(), false)
            
            val filteredItems = if (originalCatalogName.isNotEmpty() && response.items.size > 1) {
                response.items.filter { it.name == originalCatalogName }
            } else {
                response.items
            }

            val modifiedItems = filteredItems.map { homePageList ->
                homePageList.copy(name = customName)
            }

            return newHomePageResponse(modifiedItems, response.hasNext)
        } catch (e: Exception) {
            return newHomePageResponse(emptyList(), false)
        }
    }
}
