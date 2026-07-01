// 파일명: hsp1020/ultimaevo/UltimaEvoPlugin.kt
package com.ultimaevo

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// 🔥 [V2 패치] 시한폭탄 텍스트 파싱을 대체할 견고한 네이티브 Gson 데이터 클래스
data class EvoCatalogConfig(
    @SerializedName("providerName") val providerName: String,
    @SerializedName("originalCatalogName") val originalCatalogName: String,
    @SerializedName("customCatalogName") val customCatalogName: String,
    @SerializedName("originalData") val originalData: String,
    @SerializedName("orderIndex") val orderIndex: Int
)

@CloudstreamPlugin
class UltimaEvoPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
        const val PREFS_NAME = "ultima_evo_settings"
        val gson = Gson() // 전역 Gson 인스턴스
    }

    // ── [V2 최적화 기생 엔진] JSONObject -> SharedPreferences 정밀 복원 ──
    private fun jsonToPrefs(json: JSONObject, editor: SharedPreferences.Editor) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            when (val value = json.get(key)) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is Double -> editor.putFloat(key, value.toFloat())
                is JSONArray -> {
                    val set = mutableSetOf<String>()
                    for (i in 0 until value.length()) {
                        val item = value.optString(i)
                        if (item != null) set.add(item)
                    }
                    editor.putStringSet(key, set)
                }
            }
        }
    }

    // ── [V2 최적화 기생 엔진] 자동 동기화 로직 (복원 전용, 백그라운드 처리) ──
    private fun autoSyncNativeBackup(context: Context) {
        val appContext = context.applicationContext
        
        // 🚨 메인 스레드 렉(프리징) 방지를 위해 백그라운드 IO 스레드로 완전히 격리
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                
                // 로컬 데이터가 비어있을 때만 DataStore에서 꺼내와서 복원함
                if (prefs.all.isEmpty()) {
                    val nativeBackup = try { CloudStreamApp.getKey<String>("native_backup_ultimaevo") } catch (e: Exception) { null }
                    if (!nativeBackup.isNullOrEmpty()) {
                        val editor = prefs.edit()
                        jsonToPrefs(JSONObject(nativeBackup), editor)
                        editor.commit()
                        println("[UltimaEvo-AutoSync] 순정 백업에서 UltimaEvo 데이터 복원 완료")
                    }
                }
            } catch (e: Exception) {
                println("[UltimaEvo-AutoSync] 동기화 중 오류 발생: ${e.message}")
            }
        }
    }

    override fun load(context: Context) {
        println("[UltimaEvo-Debug] [Plugin] load() 함수 진입.")
        pluginContext = context.applicationContext
        
        // 🚨 앱 구동 시 순정 백업 연동 엔진 실행
        autoSyncNativeBackup(context)
        
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
