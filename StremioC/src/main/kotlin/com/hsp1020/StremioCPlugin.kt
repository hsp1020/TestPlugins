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

    // ── [순정 백업 연동 엔진] SharedPreferences <-> JSON 변환 헬퍼 ──
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

    // ── [순정 백업 연동 엔진] 자동 동기화 로직 ──
    private fun autoSyncNativeBackup(context: Context) {
        try {
            // 1. StremioC 순정 백업 연동
            val stremioCPrefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            val nativeStremioC = CloudStreamApp.getKey<String>("native_backup_stremioc")
            val localStremioCHasData = stremioCPrefs.all.isNotEmpty()

            if (!localStremioCHasData && !nativeStremioC.isNullOrEmpty()) {
                val editor = stremioCPrefs.edit()
                jsonToPrefs(JSONObject(nativeStremioC), editor)
                editor.commit()
                println("[StremioC-AutoSync] 순정 백업에서 StremioC 데이터 복원 완료")
            } else if (localStremioCHasData) {
                CloudStreamApp.setKey("native_backup_stremioc", prefsToJson(stremioCPrefs).toString())
                println("[StremioC-AutoSync] StremioC 데이터를 순정 백업(DataStore)에 기생 저장 완료")
            }

            // 2. StreamPlay 순정 백업 기생 연동
            val streamPlayPrefs = context.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
            val nativeStreamPlay = CloudStreamApp.getKey<String>("native_backup_streamplay")
            val localStreamPlayHasData = streamPlayPrefs.all.isNotEmpty()

            if (!localStreamPlayHasData && !nativeStreamPlay.isNullOrEmpty()) {
                val editor = streamPlayPrefs.edit()
                jsonToPrefs(JSONObject(nativeStreamPlay), editor)
                editor.commit()
                println("[StremioC-AutoSync] 순정 백업에서 StreamPlay 데이터 복원 완료")
            } else if (localStreamPlayHasData) {
                CloudStreamApp.setKey("native_backup_streamplay", prefsToJson(streamPlayPrefs).toString())
                println("[StremioC-AutoSync] StreamPlay 데이터를 순정 백업(DataStore)에 기생 저장 완료")
            }
        } catch (e: Exception) {
            e.printStackTrace()
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
                
                for (item in links) {
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
                            PluginManager.unloadPlugin(found.filePath)
                            delay(250) 
                        } catch (e: Throwable) {
                            e.printStackTrace()
                        }
                    } 
                    
                    try {
                        when (item.type) {
                            "StremioC" -> {
                                try {
                                    registerMainAPI(StremioC(item.link, item.name))
                                } catch (e: Throwable) {
                                    try { registerMainAPI(StremioC("", item.name)) } catch (e2: Throwable) { e2.printStackTrace() }
                                }
                            }
                            else -> {
                                try { registerMainAPI(StremioC(item.link, item.name)) } catch (e: Throwable) { e.printStackTrace() }
                            }
                        }
                    } catch (e: Throwable) {
                        e.printStackTrace()
                    }
                }
                try {
                    MainActivity.afterPluginsLoadedEvent.invoke(true)
                } catch (e: Throwable) {
                    e.printStackTrace()
                }
            } catch (e: Throwable) {
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
