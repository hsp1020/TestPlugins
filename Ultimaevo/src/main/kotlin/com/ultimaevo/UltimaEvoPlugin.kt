// v1.19
package com.ultimaevo

import android.content.Context
import android.preference.PreferenceManager
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
    }

    override fun load(context: Context) {
        pluginContext = context
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        
        val instanceCount = prefs.getInt("evo_instance_count", 3)

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
