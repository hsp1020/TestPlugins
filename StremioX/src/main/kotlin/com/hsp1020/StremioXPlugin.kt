// 파일명: hsp1020/StremioXPlugin.kt
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

@CloudstreamPlugin
class StremioXPlugin : Plugin() {
    private val PREF_FILE = "StremioX"
    private val PREF_KEY_LINKS = "stremio_saved_links"

    override fun load(context: Context) {
        println("[StremioXPlugin v1.2-TRACKING] load() 함수 호출됨")
        
        // [v2.2.1] 기록/즐겨찾기 단일 네임스페이스 격리 및 복원을 위해 백그라운드 공유 프로바이더를 조용히 상시 등록 [1]
        try {
            registerMainAPI(StremioC("", "StremioX"))
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        println("[StremioXPlugin v1.2-TRACKING] 기본 StremioX, StremioC 카탈로그 등록 건너뜀")

        println("[StremioXPlugin v1.2-TRACKING] reload(context) 실행 시도")
        reload(context)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            println("[StremioXPlugin v1.2-TRACKING] openSettings 클릭됨, SettingsBottomFragment 표시 시도")
            val frag = SettingsBottomFragment(this, context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
        println("[StremioXPlugin v1.2-TRACKING] reload() 함수 호출됨")
        try {
            val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val json = prefs.getString(PREF_KEY_LINKS, null) ?: "[]"
            println("[StremioXPlugin v1.2-TRACKING] 저장된 링크 JSON 로드 완료: $json")
            
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
            println("[StremioXPlugin v1.2-TRACKING] 파싱된 커스텀 링크 개수: ${links.size}")
            
            for (item in links) {
                println("[StremioXPlugin v1.2-TRACKING] 링크 처리 중: ${item.name} (${item.type})")
                val pluginsOnline: Array<PluginData> = PluginManager.getPluginsOnline()
                var found: PluginData? = null
                for (p in pluginsOnline) {
                    if (p.internalName.contains(item.name, ignoreCase = true)) {
                        found = p
                        break
                    }
                }
                
                // 🔥 [위험한 플러그인 교체 타이밍 수정] Unload 후 명확한 딜레이를 부여하고 독립적으로 다시 Register 하도록 개선
                if (found != null) {
                    try {
                        println("[StremioXPlugin v1.2-TRACKING] 기존 플러그인 발견, 언로드 시도: ${found.internalName}")
                        PluginManager.unloadPlugin(found.filePath)
                        Thread.sleep(250) // 비동기 언로드 처리 꼬임 방지를 위한 안전 딜레이 확보
                    } catch (e: Throwable) {
                        Log.e("StremioXPlugin", "unload failed ${e.message}")
                        println("[StremioXPlugin v1.2-TRACKING] 플러그인 언로드 실패: ${e.message}")
                        e.printStackTrace()
                    }
                } 
                
                try {
                    println("[StremioXPlugin v1.2-TRACKING] 새로운 확장 등록 시도: ${item.name} 타입: ${item.type}")
                    when (item.type) {
                        "StremioX", "StremioC" -> {
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
                    println("[StremioXPlugin v1.2-TRACKING] 확장 등록 완료 (StremioC 강제): ${item.name}")
                } catch (e: Throwable) {
                    Log.e("StremioXPlugin", "register failed ${e.message}")
                    println("[StremioXPlugin v1.2-TRACKING] 확장 등록 실패: ${e.message}")
                    e.printStackTrace()
                }
            }
            try {
                println("[StremioXPlugin v1.2-TRACKING] afterPluginsLoadedEvent 호출 시도")
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            } catch (e: Throwable) {
                Log.w("StremioXPlugin", "afterPluginsLoaded invoke failed ${e.message}")
                println("[StremioXPlugin v1.2-TRACKING] afterPluginsLoadedEvent 호출 실패: ${e.message}")
                e.printStackTrace()
            }
        } catch (e: Throwable) {
            Log.e("StremioXPlugin", "reload error ${e.message}")
            println("[StremioXPlugin v1.2-TRACKING] reload 함수 에러: ${e.message}")
            e.printStackTrace()
        }
    }

    data class LinkEntry(
        val id: Long,
        val name: String,
        val link: String,
        val type: String
    )
}
