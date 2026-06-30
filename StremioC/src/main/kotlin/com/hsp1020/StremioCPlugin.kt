// 파일명: hsp1020/StremioCPlugin.kt
package com.hsp1020

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"

    override fun load(context: Context) {
        println("[StremioCPlugin v3.0-TRACKING] load() 함수 호출됨")
        
        // 🚨 [긴급 자가 복구 엔진 주입] 앱 구동 시 StremioC가 먼저 돌면서 StreamPlay의 오염된 데이터를 복구합니다.
        try {
            val streamPlayPrefs = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            val corruptedKeys = mutableListOf<String>()
            
            // 타입 오염 여부 전수 검사
            streamPlayPrefs.all.forEach { (key, value) ->
                if (key == "enabled_plugins_saved" || key == "disabled_providers" || key == "provider_profiles") {
                    if (value is String) {
                        corruptedKeys.add(key)
                    }
                }
            }
            
            if (corruptedKeys.isNotEmpty()) {
                val editorSP = streamPlayPrefs.edit()
                corruptedKeys.forEach { key ->
                    val strValue = streamPlayPrefs.getString(key, "") ?: ""
                    editorSP.remove(key) // 오염된 String 타입 데이터 제거
                    
                    val cleanedSet = mutableSetOf<String>()
                    var cleanStr = strValue.trim()
                    
                    if (cleanStr.startsWith("[") && cleanStr.endsWith("]")) {
                        cleanStr = cleanStr.substring(1, cleanStr.length - 1)
                    }
                    
                    // JSON 포맷 흔적을 말끔히 제거하고 List/Set 형태로 파싱
                    cleanStr.split(",").forEach {
                        val trimmed = it.trim().removeSurrounding("\"").removeSurrounding("'")
                        if (trimmed.isNotEmpty()) {
                            cleanedSet.add(trimmed)
                        }
                    }
                    
                    if (cleanedSet.isNotEmpty()) {
                        editorSP.putStringSet(key, cleanedSet) // 올바른 StringSet 규격으로 복구 후 이관
                    }
                }
                editorSP.commit() // 동기식 영구 저장 강제 진행
                println("[StremioCPlugin-Healing] StreamPlay SharedPreferences의 오염된 StringSet 복구 성공: $corruptedKeys")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
