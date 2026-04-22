// v1.53 (Fix: PreferenceManager 의존성 에러 해결을 위해 네이티브 방식으로 글로벌 설정 로드)
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
    }

    override fun load(context: Context) {
        println("[UltimaEvo-Debug v1.53] [Plugin] load() 함수 진입.")
        pluginContext = context.applicationContext
        
        // [v1.53 수정] 네이티브 SharedPreferences 패키지명 기반 로딩
        val mainPrefName = "${pluginContext!!.packageName}_preferences"
        val prefs = pluginContext!!.getSharedPreferences(mainPrefName, Context.MODE_PRIVATE)
        println("[UltimaEvo-Debug v1.53] 글로벌 메인 SharedPreferences 연동 성공 (이름: $mainPrefName)")

        val instanceCountStr = prefs.getString("evo_instance_count", "1") ?: "1"
        val instanceCount = instanceCountStr.toIntOrNull() ?: 1
        println("[UltimaEvo-Debug v1.53] [Plugin] 생성할 카탈로그 인스턴스 수: $instanceCount")

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
