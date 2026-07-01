// 파일명: hsp1020/StremioCPlugin.kt
package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"

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

    private fun autoSyncNativeBackup(context: Context) {
        val appContext = context.applicationContext
        
        CoroutineScope(Dispatchers.IO).launch {
            try {
                println("🚨🚨🚨 [StremioC-AutoSync] V2 기생 엔진 백그라운드 시작됨! 🚨🚨🚨")
                
                val stremioCPrefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                if (stremioCPrefs.all.isEmpty()) {
                    println("🚨🚨🚨 [StremioC-AutoSync] 로컬 데이터 없음. DataStore 복원 시도... 🚨🚨🚨")
                    val nativeStremioC = try { CloudStreamApp.getKey<String>("native_backup_stremioc") } catch (e: Exception) { null }
                    if (!nativeStremioC.isNullOrEmpty()) {
                        val editor = stremioCPrefs.edit()
                        jsonToPrefs(JSONObject(nativeStremioC), editor)
                        editor.commit()
                        println("🚨🚨🚨 [StremioC-AutoSync] 순정 백업에서 StremioC 데이터 복원 완료! 🚨🚨🚨")
                    } else {
                        println("🚨🚨🚨 [StremioC-AutoSync] DataStore에도 백업 데이터가 없습니다. 🚨🚨🚨")
                    }
                } else {
                    println("🚨🚨🚨 [StremioC-AutoSync] 로컬 데이터가 이미 존재하여 복원을 스킵합니다. 🚨🚨🚨")
                }

                val streamPlayPrefs = appContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                if (streamPlayPrefs.all.isEmpty()) {
                    val nativeStreamPlay = try { CloudStreamApp.getKey<String>("native_backup_streamplay") } catch (e: Exception) { null }
                    if (!nativeStreamPlay.isNullOrEmpty()) {
                        val editor = streamPlayPrefs.edit()
                        jsonToPrefs(JSONObject(nativeStreamPlay), editor)
                        editor.commit()
                        println("🚨🚨🚨 [StremioC-AutoSync] 순정 백업에서 StreamPlay 데이터 복원 완료! 🚨🚨🚨")
                    }
                }
            } catch (e: Exception) {
                println("🚨🚨🚨 [StremioC-AutoSync] 동기화 중 오류 발생: ${e.message} 🚨🚨🚨")
            }
        }
    }

    override fun load(context: Context) {
        println("[StremioCPlugin v3.0-TRACKING] load() 함수 호출됨")
        
        // 🚨 앱 구동 시 순정 백업 연동 엔진 실행
        autoSyncNativeBackup(context)

        try {
            registerMainAPI(StremioC("", "StremioC"))
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        println("[StremioCPlugin v3.0-TRACKING] reload(context) 실행 시도")
        reload(context)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            println("[StremioCPlugin v3.0-TRACKING] openSettings 클릭됨, SettingsBottomFragment 표시 시도")
            val frag = SettingsBottomFragment(this, context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
        println("[StremioCPlugin v3.0-TRACKING] reload() 함수 호출됨")
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val json = prefs.getString(PREF_KEY_LINKS, null) ?: "[]"
                println("[StremioCPlugin v3.0-TRACKING] 저장된 링크 JSON 로드 완료: $json")
                
                val arr = JSONArray(json)
                val links = mutableListOf<LinkEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    links.add(
                        LinkEntry(
                            id = obj.optLong("id", System.currentTimeMillis()),
                            name = obj.optString("name", ""),
                            link = obj.optString("link", ""),
                            type = obj.optString("type", "StremioC")
                        )
                    )
                }
                println("[StremioCPlugin v3.0-TRACKING] 파싱된 커스텀 링크 개수: ${links.size}")
                
                for (item in links) {
                    println("[StremioCPlugin v3.0-TRACKING] 링크 처리 중: ${item.name} (${item.type})")
                    val pluginsOnline: Array<PluginData> = PluginManager.getPluginsOnline()
                    var found: PluginData? = null
                    for (p in pluginsOnline) {
                        if (p.internalName.contains(item.name, ignoreCase = true)) {
                            found = p
                            break
                        }
                    }
                    if (found != null) {
                        try {
                            println("[StremioCPlugin v3.0-TRACKING] 기존 플러그인 발견, 언로드 시도: ${found.internalName}")
                            PluginManager.unloadPlugin(found.filePath)
                            delay(250) 
                        } catch (e: Throwable) {
                            Log.e("StremioCPlugin", "unload failed ${e.message}")
                            println("[StremioCPlugin v3.0-TRACKING] 플러그인 언로드 실패: ${e.message}")
                            e.printStackTrace()
                        }
                    } 
                    
                    try {
                        println("[StremioCPlugin v3.0-TRACKING] 새로운 확장 등록 시도: ${item.name} 타입: ${item.type}")
                        when (item.type) {
                            "StremioC" -> {
                                try {
                                    registerMainAPI(StremioC(item.link, item.name))
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    try { registerMainAPI(StremioC("", item.name)) } catch (e2: Throwable) { e2.printStackTrace() }
                                }
                            }
                            else -> {
                                try { registerMainAPI(StremioC(item.link, item.name)) } catch (e: Throwable) { e.printStackTrace() }
                            }
                        }
                        println("[StremioCPlugin v3.0-TRACKING] 확장 등록 완료 (StremioC 강제): ${item.name}")
                    } catch (e: Throwable) {
                        Log.e("StremioCPlugin", "register failed ${e.message}")
                        println("[StremioCPlugin v3.0-TRACKING] 확장 등록 실패: ${e.message}")
                        e.printStackTrace()
                    }
                }
                try {
                    println("[StremioCPlugin v3.0-TRACKING] afterPluginsLoadedEvent 호출 시도")
                    MainActivity.afterPluginsLoadedEvent.invoke(true)
                } catch (e: Throwable) {
                    Log.w("StremioCPlugin", "afterPluginsLoaded invoke failed ${e.message}")
                    println("[StremioCPlugin v3.0-TRACKING] afterPluginsLoadedEvent 호출 실패: ${e.message}")
                    e.printStackTrace()
                }
            } catch (e: Throwable) {
                Log.e("StremioCPlugin", "reload error ${e.message}")
                println("[StremioCPlugin v3.0-TRACKING] reload 함수 에러: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    data class LinkEntry(
        val id: Long,
        val name: String,
        val link: String,
        val type: String
    )
}
