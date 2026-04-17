// v1.2
package com.example.ultimaevo

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

data class SavedCatalog(
    val providerName: String,
    val originalCatalogName: String,
    val originalCatalogData: String,
    val customCatalogName: String,
    val isEnabled: Boolean,
    val orderIndex: Int
)

class UltimaEvoPlugin : CloudstreamPlugin() {
    companion object {
        var pluginContext: Context? = null
    }

    override fun load(context: Context) {
        pluginContext = context
        registerMainAPI(UltimaEvoProvider())
    }
}
