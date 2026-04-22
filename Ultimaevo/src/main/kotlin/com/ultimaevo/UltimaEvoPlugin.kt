// v1.52 (Feature: Cloudstream 메인 SharedPreferences 연동으로 인한 설정 백업 지원)
package com.ultimaevo

import android.content.Context
import androidx.fragment.app.FragmentActivity
import androidx.preference.PreferenceManager
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
        println("[UltimaEvo-Debug v1.52] [Plugin] load() 함수 진입.")
        pluginContext = context.applicationContext
        
        // [v1.52 수정] 백업 호환성을 위해 별도 저장소 대신 글로벌 SharedPreferences 참조
        val prefs = PreferenceManager.getDefaultSharedPreferences(pluginContext!!)
        println("[UltimaEvo-Debug v1.52] 글로벌 메인 SharedPreferences 연동 성공")

        val instanceCountStr = prefs.getString("evo_instance_count", "1") ?: "1"
        val instanceCount = instanceCountStr.toIntOrNull() ?: 1
        println("[UltimaEvo-Debug v1.52] [Plugin] 생성할 카탈로그 인스턴스 수: $instanceCount")

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
