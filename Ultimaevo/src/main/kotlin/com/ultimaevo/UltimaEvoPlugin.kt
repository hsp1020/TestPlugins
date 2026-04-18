// v1.32
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
        println("[UltimaEvo-Debug] [Plugin] load() 함수 시작됨")
        pluginContext = context
        println("[UltimaEvo-Debug] [Plugin] pluginContext 할당 완료: $pluginContext")
        
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val instanceCountStr = prefs.getString("evo_instance_count", "1") ?: "1"
        val instanceCount = instanceCountStr.toIntOrNull() ?: 1
        println("[UltimaEvo-Debug] [Plugin] 읽어온 생성할 인스턴스 수: $instanceCount")

        for (i in 1..instanceCount) {
            val providerName = prefs.getString("evo_name_$i", "Ultima Evo $i") ?: "Ultima Evo $i"
            println("[UltimaEvo-Debug] [Plugin] 인스턴스 $i 등록 준비 중 - 이름: $providerName")
            registerMainAPI(UltimaEvoProvider(i, providerName))
            println("[UltimaEvo-Debug] [Plugin] 인스턴스 $i 등록 완료")
        }

        openSettings = { ctx ->
            println("[UltimaEvo-Debug] [Plugin] openSettings 람다 호출됨. Context: $ctx")
            val act = ctx as? FragmentActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                println("[UltimaEvo-Debug] [Plugin] 다이얼로그 띄우기 시도")
                val dialog = UltimaEvoSettingsDialog()
                dialog.show(act.supportFragmentManager, "UltimaEvoSettingsDialog")
            } else {
                println("[UltimaEvo-Debug] [Plugin] 에러: FragmentActivity 캐스팅 실패 또는 액티비티 파괴 상태")
            }
        }
        println("[UltimaEvo-Debug] [Plugin] load() 함수 종료됨")
    }
}
