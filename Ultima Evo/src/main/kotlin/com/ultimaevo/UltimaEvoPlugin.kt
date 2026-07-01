package com.ultimaevo

import android.content.Context
import android.content.SharedPreferences
import androidx.fragment.app.FragmentActivity
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.CloudStreamApp
import org.json.JSONArray
import org.json.JSONObject

// 세부 설정 데이터 전송 포맷
data class EvoCatalogConfig(
    @JsonProperty("providerName") val providerName: String,
    @JsonProperty("originalCatalogName") val originalCatalogName: String,
    @JsonProperty("customCatalogName") val customCatalogName: String,
    @JsonProperty("originalData") val originalData: String,
    @JsonProperty("orderIndex") val orderIndex: Int
)

@CloudstreamPlugin
class UltimaEvoPlugin : Plugin() {
    companion object {
        var pluginContext: Context? = null
        const val PREFS_NAME = "ultima_evo_settings"
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

    override fun load(context: Context) {
        println("[UltimaEvo-Debug] [Plugin] load() 함수 진입.")
        val appContext = context.applicationContext
        pluginContext = appContext
        
        // --- Ultima Evo 자체 부팅 복원 엔진 (Flag Guard + Checksum 검증) ---
        try {
            val ultimaEvoBackupStr = try {
                CloudStreamApp.getKey<String>("native_backup_ultimaevo")
            } catch (e: Throwable) {
                null
            }

            if (!ultimaEvoBackupStr.isNullOrBlank()) {
                val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val localComplete = prefs.contains("ultima_evo_restore_complete")
                val localHash = prefs.getString("ultima_evo_last_applied_backup_hash", "") ?: ""
                val incomingHash = ultimaEvoBackupStr.hashCode().toString()

                if (!localComplete || localHash != incomingHash) {
                    val json = JSONObject(ultimaEvoBackupStr)
                    val editor = prefs.edit()
                    editor.clear()
                    jsonToPrefs(json, editor)
                    editor.putBoolean("ultima_evo_restore_complete", true)
                    editor.putString("ultima_evo_last_applied_backup_hash", incomingHash)
                    editor.commit()
                    println("[UltimaEvoPlugin] Ultima Evo preferences restored successfully on load.")
                }
            }
        } catch (e: Throwable) {
            println("[UltimaEvoPlugin] Error during UltimaEvo restore on boot: ${e.message}")
            e.printStackTrace()
        }

        try {
            val prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
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


// hsp1020/ultimaevo/UltimaEvoSettingsDialog.kt
