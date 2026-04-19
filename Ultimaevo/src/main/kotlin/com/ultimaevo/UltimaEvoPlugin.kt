// v1.46
package com.ultimaevo

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

data class SavedCatalog(
    val providerName: String,
    val originalCatalogName: String,
    val originalCatalogData: String,
    val customCatalogName: String,
    val isEnabled: Boolean,
    val orderIndex: Int
)

@CloudstreamPlugin
class UltimaEvoPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
        const val PREFS_NAME = "ultima_evo_settings"
    }

    override fun load(context: Context) {
        println("[UltimaEvo-Debug] [Plugin] load() 함수 진입.")
        pluginContext = context.applicationContext
        
        val prefs = pluginContext!!.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val instanceCountStr = prefs.getString("evo_instance_count", "1") ?: "1"
        val instanceCount = instanceCountStr.toIntOrNull() ?: 1
        println("[UltimaEvo-Debug] [Plugin] 생성할 카탈로그 인스턴스 수: $instanceCount")

        for (i in 1..instanceCount) {
            val providerName = prefs.getString("evo_name_$i", "Ultima Evo $i") ?: "Ultima Evo $i"
            registerMainAPI(UltimaEvoProvider(i, providerName))
        }

        openSettings = { ctx ->
            val act = ctx as? FragmentActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val dialog = UltimaEvoSettingsDialog()
                dialog.show(act.supportFragmentManager, "UltimaEvoSettingsDialog")
            }
        }
    }
}
