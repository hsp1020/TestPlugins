// 파일명: hsp1020/ultimaevo/UltimaEvoPlugin.kt
package com.ultimaevo

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.json.JSONArray
import org.json.JSONObject

@CloudstreamPlugin
class UltimaEvoPlugin : Plugin() {

    companion object {
        const val PREFS_NAME = "UltimaEvo"
        lateinit var gson: com.google.gson.Gson
        var pluginContext: Context? = null
    }

    // ── [순정 백업 연동 엔진] ──
    private fun prefsToJson(prefs: SharedPreferences): JSONObject {
        val json = JSONObject()
        prefs.all.forEach { (key, value) ->
            if (value != null) {
                if (value is Set<*>) {
                    val arr = JSONArray()
                    value.forEach { item -> if (item != null) arr.put(item) }
                    json.put(key, arr)
                } else {
                    json.put(key, value)
                }
            }
        }
        return json
    }

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

    // ── [최적화된 순정 백업 연동 엔진] 자동 동기화 로직 ──
    private fun autoSyncNativeBackup(context: Context) {
        val appContext = context.applicationContext
        
        // 🚨 [최적화] 메인 스레드 렉(프리징) 방지를 위해 백그라운드 IO 스레드로 완전히 격리
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val localHasData = prefs.all.isNotEmpty()

                // 로컬 데이터가 비어있을 때만 DataStore에서 꺼내와서 복원함
                if (!localHasData) {
                    val nativeBackup = try { CloudStreamApp.getKey<String>("native_backup_ultimaevo") } catch (e: Exception) { null }
                    if (!nativeBackup.isNullOrEmpty()) {
                        val editor = prefs.edit()
                        jsonToPrefs(JSONObject(nativeBackup), editor)
                        editor.commit()
                        println("[UltimaEvo-AutoSync] 순정 백업에서 UltimaEvo 데이터 복원 완료")
                    }
                }
                // (주의: 앱 구동 시의 무의미한 덮어쓰기 백업 로직은 리소스 낭비 방지를 위해 삭제함. 백업은 설정창 Save 시에만 작동)
                
            } catch (e: Exception) {
                println("[UltimaEvo-AutoSync] 동기화 중 오류 발생: ${e.message}")
            }
        }
    }
    
    override fun load(context: Context) {
        gson = com.google.gson.Gson()
        pluginContext = context
        
        // 🚨 앱 구동 시 순정 백업 연동 엔진 실행
        autoSyncNativeBackup(context)

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val instanceCount = prefs.getString("evo_instance_count", "1")?.toIntOrNull() ?: 1

        for (i in 1..instanceCount) {
            val isVisible = prefs.getBoolean("evo_visibility_enabled_$i", true)
            val customName = prefs.getString("evo_name_$i", "Ultima Evo $i") ?: "Ultima Evo $i"
            
            if (isVisible) {
                registerMainAPI(UltimaEvoProvider(i, customName))
            }
        }

        val activity = context as? AppCompatActivity
        openSettings = {
            val frag = UltimaEvoSettingsDialog()
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "UltimaEvoSettings") }
        }
    }
}
