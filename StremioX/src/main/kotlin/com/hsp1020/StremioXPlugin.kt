// v1.4 (Feature: Cloudstream 백업 지원을 위해 메인 SharedPreferences로 저장소 변경 및 디버깅 로그 추가)
package com.hsp1020

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import org.json.JSONArray

@CloudstreamPlugin
class StremioXPlugin : Plugin() {
    private val PREF_KEY_LINKS = "stremio_saved_links"

    override fun load(context: Context) {
        println("[StremioXPlugin v1.4-TRACKING] load() 함수 호출됨")
        
        println("[StremioXPlugin v1.4-TRACKING] 기본 StremioX, StremioC 카탈로그 등록 건너뜀")

        println("[StremioXPlugin v1.4-TRACKING] reload(context) 실행 시도")
        reload(context)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            println("[StremioXPlugin v1.4-TRACKING] openSettings 클릭됨, SettingsBottomFragment 표시 시도")
            
            // [v1.4 수정] 플러그인 전용 저장소 대신 Cloudstream 앱 메인 저장소를 넘겨주어 백업 지원
            val mainPrefs = PreferenceManager.getDefaultSharedPreferences(context)
            println("[StremioXPlugin v1.4-TRACKING] 글로벌 메인 SharedPreferences 로드 완료")
            
            val frag = SettingsBottomFragment(this, mainPrefs)
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
        println("[StremioXPlugin v1.4-TRACKING] reload() 함수 호출됨")
        try {
            // [v1.4 수정] 플러그인 전용 저장소 대신 Cloudstream 앱 메인 저장소 참조
            val prefs = PreferenceManager.getDefaultSharedPreferences(context)
            val json = prefs.getString(PREF_KEY_LINKS, null) ?: "[]"
            println("[StremioXPlugin v1.4-TRACKING] 저장된 링크 메인 DB에서 로드 완료: $json")
            
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
            println("[StremioXPlugin v1.4-TRACKING] 파싱된 커스텀 링크 개수: ${links.size}")
            
            for (item in links) {
                println("[StremioXPlugin v1.4-TRACKING] 링크 처리 중: ${item.name} (${item.type})")
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
                        println("[StremioXPlugin v1.4-TRACKING] 기존 플러그인 발견, 언로드 시도: ${found.internalName}")
                        PluginManager.unloadPlugin(found.filePath)
                    } catch (e: Throwable) {
                        Log.e("StremioXPlugin", "unload failed ${e.message}")
                        println("[StremioXPlugin v1.4-TRACKING] 플러그인 언로드 실패: ${e.message}")
                    }
                } else {
                    try {
                        println("[StremioXPlugin v1.4-TRACKING] 새로운 확장 등록 시도: ${item.name} 타입: ${item.type}")
                        when (item.type) {
                            "StremioX", "StremioC" -> {
                                try {
                                    registerMainAPI(StremioC(item.link, item.name))
                                } catch (_: Throwable) {
                                    try { registerMainAPI(StremioC("", item.name)) } catch (_: Throwable) {}
                                }
                            }
                            else -> {
                                try { registerMainAPI(StremioC(item.link, item.name)) } catch (_: Throwable) {}
                            }
                        }
                        println("[StremioXPlugin v1.4-TRACKING] 확장 등록 완료 (StremioC 강제): ${item.name}")
                    } catch (e: Throwable) {
                        Log.e("StremioXPlugin", "register failed ${e.message}")
                        println("[StremioXPlugin v1.4-TRACKING] 확장 등록 실패: ${e.message}")
                    }
                }
            }
            try {
                println("[StremioXPlugin v1.4-TRACKING] afterPluginsLoadedEvent 호출 시도")
                MainActivity.afterPluginsLoadedEvent.invoke(true)
            } catch (e: Throwable) {
                Log.w("StremioXPlugin", "afterPluginsLoaded invoke failed ${e.message}")
                println("[StremioXPlugin v1.4-TRACKING] afterPluginsLoadedEvent 호출 실패: ${e.message}")
            }
        } catch (e: Throwable) {
            Log.e("StremioXPlugin", "reload error ${e.message}")
            println("[StremioXPlugin v1.4-TRACKING] reload 함수 에러: ${e.message}")
        }
    }

    data class LinkEntry(
        val id: Long,
        val name: String,
        val link: String,
        val type: String
    )
}
