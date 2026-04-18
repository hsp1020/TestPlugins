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
        // [핵심] 파편화 방지를 위한 단일 독립 저장소 이름
        const val PREFS_NAME = "ultima_evo_settings"
    }

    override fun load(context: Context) {
        println("[UltimaEvo-Debug] [Plugin] load() 시작됨")
        pluginContext = context
        
        // 캐싱 버그를 피하기 위해 전용 파일 명시적 호출
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        
        val instanceCountStr = prefs.getString("evo_instance_count", "1") ?: "1"
        val instanceCount = instanceCountStr.toIntOrNull() ?: 1
        println("[UltimaEvo-Debug] [Plugin] 생성할 카탈로그 수: $instanceCount")

        for (i in 1..instanceCount) {
            val providerName = prefs.getString("evo_name_$i", "Ultima Evo $i") ?: "Ultima Evo $i"
            println("[UltimaEvo-Debug] [Plugin] 인스턴스 $i 등록 준비 - 이름: $providerName")
            registerMainAPI(UltimaEvoProvider(i, providerName))
        }

        openSettings = { ctx ->
            println("[UltimaEvo-Debug] [Plugin] 설정창 오픈 요청됨")
            val act = ctx as? FragmentActivity
            if (act != null && !act.isFinishing && !act.isDestroyed) {
                val dialog = UltimaEvoSettingsDialog()
                dialog.show(act.supportFragmentManager, "UltimaEvoSettingsDialog")
            } else {
                println("[UltimaEvo-Debug] [Plugin] 에러: 설정창을 열 수 없는 상태 (Activity Null 또는 종료됨)")
            }
        }
    }
}
