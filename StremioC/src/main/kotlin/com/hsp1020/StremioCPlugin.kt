package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"

    // SharedPreferences 직렬화 유틸리티 (타입 안전성 확보)
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

    // SharedPreferences 역직렬화 유틸리티 (ClassCastException 원천 방지)
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

    override fun load(context: Context) {
        println("[StremioCPlugin v3.0-TRACKING] load() 함수 호출됨")
        val appContext = context.applicationContext

        // --- 1. StremioC 자체 부팅 복원 엔진 (Flag Guard + Checksum 검증) ---
        try {
            val stremioCBackupStr = try {
                CloudStreamApp.getKey<String>("native_backup_stremioc")
            } catch (e: Throwable) {
                null
            }

            if (!stremioCBackupStr.isNullOrBlank()) {
                val stremioCPrefs = appContext.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val localComplete = stremioCPrefs.contains("stremio_c_restore_complete")
                val localHash = stremioCPrefs.getString("stremio_c_last_applied_backup_hash", "") ?: ""
                val incomingHash = stremioCBackupStr.hashCode().toString()

                if (!localComplete || localHash != incomingHash) {
                    val json = JSONObject(stremioCBackupStr)
                    val editor = stremioCPrefs.edit()
                    editor.clear() // 무손실 덮어쓰기 보장
                    jsonToPrefs(json, editor)
                    editor.putBoolean("stremio_c_restore_complete", true)
                    editor.putString("stremio_c_last_applied_backup_hash", incomingHash)
                    editor.commit()
                    println("[StremioCPlugin] StremioC preferences restored successfully on load.")
                }
            }
        } catch (e: Throwable) {
            println("[StremioCPlugin] Error during StremioC restore on boot: ${e.message}")
            e.printStackTrace()
        }

        // --- 2. StreamPlay 대리 부팅 복원 엔진 (Read-Only 플러그인 기생 복구) ---
        try {
            val streamPlayBackupStr = try {
                CloudStreamApp.getKey<String>("native_backup_streamplay")
            } catch (e: Throwable) {
                null
            }

            if (!streamPlayBackupStr.isNullOrBlank()) {
                val streamPlayPrefs = appContext.getSharedPreferences("StreamPlay", Context.MODE_PRIVATE)
                val localComplete = streamPlayPrefs.contains("streamplay_restore_complete")
                val localHash = streamPlayPrefs.getString("streamplay_last_applied_backup_hash", "") ?: ""
                val incomingHash = streamPlayBackupStr.hashCode().toString()

                if (!localComplete || localHash != incomingHash) {
                    val json = JSONObject(streamPlayBackupStr)
                    val editor = streamPlayPrefs.edit()
                    editor.clear()
                    jsonToPrefs(json, editor)
                    editor.putBoolean("streamplay_restore_complete", true)
                    editor.putString("streamplay_last_applied_backup_hash", incomingHash)
                    editor.commit()
                    println("[StremioCPlugin] StreamPlay preferences restored successfully via proxy on load.")
                }
            }
        } catch (e: Throwable) {
            println("[StremioCPlugin] Error during StreamPlay proxy restore on boot: ${e.message}")
            e.printStackTrace()
        }

        // 기본 API 로드 동작 개시
        try {
            registerMainAPI(StremioC("", "StremioC"))
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        println("[StremioCPlugin v3.0-TRACKING] reload(context) 실행 시도")
        reload(appContext)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            println("[StremioCPlugin v3.0-TRACKING] openSettings 클릭됨, SettingsBottomFragment 표시 시도")
            val frag = SettingsBottomFragment(this, appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
        println("[StremioCPlugin v3.0-TRACKING] reload() 함수 호출됨")
        val appContext = context.applicationContext
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
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

