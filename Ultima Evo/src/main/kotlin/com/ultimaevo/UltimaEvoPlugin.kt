// 파일명: hsp1020/ultimaevo/UltimaEvoPlugin.kt
package com.ultimaevo

import android.content.Context
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

// 시한폭탄 텍스트 파싱을 대체할 견고한 네이티브 데이터 클래스
data class EvoCatalogConfig(
    @JsonProperty("providerName") val providerName: String,
    @JsonProperty("originalCatalogName") val originalCatalogName: String,
    @JsonProperty("customCatalogName") val customCatalogName: String,
    @JsonProperty("originalData") val originalData: String,
    @JsonProperty("orderIndex") val orderIndex: Int
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
        
        try {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
