// 파일명: hsp1020/StremioCPlugin.kt
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
import com.lagradost.cloudstream3.utils.DataStoreHelper
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.isActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"
    private val pluginIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var manifestPrefetchJob: Job? = null
    private var catalogPrefetchJob: Job? = null
    private var homeSelectionMonitorJob: Job? = null
    private val activeStremioProviders = mutableListOf<StremioC>()
    private val rearmJobs = mutableMapOf<String, Job>()
    private val rearmJobsLock = Any()
    private val catalogPrefetchStartupDelayMs = 1000L
    private val homeSelectionPollMs = 500L
    // 15s filters quick provider peeks without delaying large-provider refill by 30~60s.
    // Baseline logs showed IMDB needed ~11s to prefetch only 13/46 rows at BG=2; with BG=4
    // a 30s debounce would make full readiness too late for normal revisits.
    private val homeSwitchRearmDebounceMs = 15_000L

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

    override fun load(context: Context) {
        println("[StremioCPlugin v3.3-TUNING-B] load() 함수 호출됨")
        println("[StremioC-Home][TUNING] profile=B foreground=${StremioC.HOME_FOREGROUND_CONCURRENCY} background=${StremioC.HOME_PREFETCH_CONCURRENCY} rearmDebounceMs=$homeSwitchRearmDebounceMs manifestWarm=true")
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
                    val editor = stremioCPrefs.edit()
                    editor.clear() 
                    jsonToPrefs(JSONObject(stremioCBackupStr), editor)
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
                    val editor = streamPlayPrefs.edit()
                    editor.clear()
                    jsonToPrefs(JSONObject(streamPlayBackupStr), editor)
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

        // --- 3. Restored Links Manifest Auto-Prefetch Engine (부팅 비동기 프리페처) ---
        manifestPrefetchJob = pluginIoScope.launch {
            try {
                val prefs = appContext.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val json = prefs.getString("stremio_saved_links", null) ?: "[]"
                val arr = JSONArray(json)
                val links = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val link = obj.optString("link", "")
                    if (link.isNotBlank()) {
                        links.add(link)
                    }
                }

                val manifestPrefs = appContext.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                
                links.forEach { link ->
                    // Use the exact same query-aware URL/key functions as StremioC.kt and SettingsBottomFragment.kt.
                    val currentUrl = stremioManifestUrl(link)
                    val uniqueKey = stremioCatalogKey(link)
                    val keyLabel = uniqueKey.take(14)

                    if (!manifestPrefs.contains(uniqueKey)) {
                        val totalStarted = System.nanoTime()
                        println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=START")
                        try {
                            val httpStarted = System.nanoTime()
                            val responseText = com.lagradost.cloudstream3.app.get(currentUrl, timeout = 15L).text
                            val httpMs = (System.nanoTime() - httpStarted) / 1_000_000
                            if (responseText.isNotBlank()) {
                                val parseStarted = System.nanoTime()
                                val parsed = JSONObject(responseText)
                                val parseMs = (System.nanoTime() - parseStarted) / 1_000_000
                                if (parsed.has("catalogs")) {
                                    val writeStarted = System.nanoTime()
                                    manifestPrefs.edit().putString(uniqueKey, responseText).commit()
                                    val writeMs = (System.nanoTime() - writeStarted) / 1_000_000
                                    println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=DONE httpMs=$httpMs parseMs=$parseMs prefsCommitMs=$writeMs bytes=${responseText.length} totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
                                } else {
                                    println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=NO_CATALOGS httpMs=$httpMs parseMs=$parseMs totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
                                }
                            } else {
                                println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=EMPTY httpMs=$httpMs totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
                            }
                        } catch (e: Throwable) {
                            println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=FAILED errorClass=${e.javaClass.simpleName} totalMs=${(System.nanoTime() - totalStarted) / 1_000_000}")
                        }
                    } else {
                        println("[StremioC-Home][BOOT_MANIFEST] key=$keyLabel stage=HIT")
                    }
                }
            } catch (e: Throwable) {
                e.printStackTrace()
            }
        }

        try {
            registerMainAPI(StremioC("", "StremioC"))
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        println("[StremioCPlugin v3.3-TUNING-B] reload(context) 실행 시도")
        reload(appContext)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            println("[StremioCPlugin v3.3-TUNING-B] openSettings 클릭됨, SettingsBottomFragment 표시 시도")
            val frag = SettingsBottomFragment(this, appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
        println("[StremioCPlugin v3.3-TUNING-B] reload() 함수 호출됨")
        val appContext = context.applicationContext

        catalogPrefetchJob?.cancel()
        homeSelectionMonitorJob?.cancel()
        synchronized(rearmJobsLock) {
            rearmJobs.values.forEach { it.cancel() }
            rearmJobs.clear()
        }
        val oldProviders = synchronized(activeStremioProviders) {
            activeStremioProviders.toList().also { activeStremioProviders.clear() }
        }
        if (oldProviders.isNotEmpty()) {
            pluginIoScope.launch {
                oldProviders.forEach { provider ->
                    runCatching { provider.cancelOneShotCatalogPrefetch("PLUGIN_RELOAD") }
                }
            }
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val json = prefs.getString(PREF_KEY_LINKS, null) ?: "[]"
                println("[StremioCPlugin v3.3-TUNING-B] 저장된 링크 JSON 로드 완료 (bytes=${json.length})")
                
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
                println("[StremioCPlugin v3.3-TUNING-B] 파싱된 커스텀 링크 개수: ${links.size}")
                val registeredProviders = mutableListOf<StremioC>()
                
                for (item in links) {
                    println("[StremioCPlugin v3.3-TUNING-B] 링크 처리 중: ${item.name} (${item.type})")
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
                            println("[StremioCPlugin v3.3-TUNING-B] 기존 플러그인 발견, 언로드 시도: ${found.internalName}")
                            PluginManager.unloadPlugin(found.filePath)
                            delay(250) 
                        } catch (e: Throwable) {
                            Log.e("StremioCPlugin", "unload failed ${e.message}")
                            println("[StremioCPlugin v3.3-TUNING-B] 플러그인 언로드 실패: ${e.message}")
                            e.printStackTrace()
                        }
                    } 
                    
                    try {
                        println("[StremioCPlugin v3.3-TUNING-B] 새로운 확장 등록 시도: ${item.name} 타입: ${item.type}")
                        when (item.type) {
                            "StremioC" -> {
                                try {
                                    val provider = StremioC(item.link, item.name)
                                    registerMainAPI(provider)
                                    if (item.link.isNotBlank()) registeredProviders.add(provider)
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    try { registerMainAPI(StremioC("", item.name)) } catch (e2: Throwable) { e2.printStackTrace() }
                                }
                            }
                            else -> {
                                try {
                                    val provider = StremioC(item.link, item.name)
                                    registerMainAPI(provider)
                                    if (item.link.isNotBlank()) registeredProviders.add(provider)
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                }
                            }
                        }
                        println("[StremioCPlugin v3.3-TUNING-B] 확장 등록 완료 (StremioC 강제): ${item.name}")
                    } catch (e: Throwable) {
                        Log.e("StremioCPlugin", "register failed ${e.message}")
                        println("[StremioCPlugin v3.3-TUNING-B] 확장 등록 실패: ${e.message}")
                        e.printStackTrace()
                    }
                }
                try {
                    println("[StremioCPlugin v3.3-TUNING-B] afterPluginsLoadedEvent 호출 시도")
                    MainActivity.afterPluginsLoadedEvent.invoke(true)
                } catch (e: Throwable) {
                    Log.w("StremioCPlugin", "afterPluginsLoaded invoke failed ${e.message}")
                    println("[StremioCPlugin v3.3-TUNING-B] afterPluginsLoadedEvent 호출 실패: ${e.message}")
                    e.printStackTrace()
                }

                synchronized(activeStremioProviders) {
                    activeStremioProviders.clear()
                    activeStremioProviders.addAll(registeredProviders)
                }

                // Observe CloudStream's selected homepage provider. A provider that was just left is
                // re-armed only after a 15s grace period. Returning before that cancels the scheduled
                // refill, preventing rapid A↔B switching from repeatedly downloading page 1.
                homeSelectionMonitorJob?.cancel()
                homeSelectionMonitorJob = pluginIoScope.launch {
                    var lastHome = DataStoreHelper.currentHomePage
                    println("[StremioC-Home][PREFETCH_REFRESH] stage=MONITOR_START current=${lastHome ?: "none"} debounceMs=$homeSwitchRearmDebounceMs")
                    while (isActive) {
                        delay(homeSelectionPollMs)
                        val currentHome = DataStoreHelper.currentHomePage
                        if (currentHome != lastHome) {
                            val previousHome = lastHome
                            lastHome = currentHome
                            println("[StremioC-Home][PREFETCH_REFRESH] stage=HOME_SWITCH from=${previousHome ?: "none"} to=${currentHome ?: "none"}")

                            // If the user returns to a provider before its delayed refill fires (or
                            // while it is running), cancel that refill immediately. Foreground can
                            // consume any READY/IN_FLIGHT entries that already exist.
                            currentHome?.let { selectedName ->
                                val key = selectedName.lowercase()
                                val job = synchronized(rearmJobsLock) { rearmJobs.remove(key) }
                                if (job != null) {
                                    job.cancel()
                                    println("[StremioC-Home][PREFETCH_REFRESH] provider=$selectedName stage=REARM_CANCEL_RETURNED")
                                }
                            }

                            val previousProvider = synchronized(activeStremioProviders) {
                                activeStremioProviders.firstOrNull {
                                    previousHome != null && it.name.equals(previousHome, ignoreCase = true)
                                }
                            }
                            if (previousProvider != null) {
                                val key = previousProvider.name.lowercase()
                                val job = launch {
                                    try {
                                        println("[StremioC-Home][PREFETCH_REFRESH] provider=${previousProvider.name} stage=REARM_SCHEDULED debounceMs=$homeSwitchRearmDebounceMs")
                                        delay(homeSwitchRearmDebounceMs)
                                        if (DataStoreHelper.currentHomePage?.equals(previousProvider.name, ignoreCase = true) == true) {
                                            println("[StremioC-Home][PREFETCH_REFRESH] provider=${previousProvider.name} stage=REARM_SKIP reason=RETURNED")
                                            return@launch
                                        }
                                        println("[StremioC-Home][PREFETCH_REFRESH] provider=${previousProvider.name} stage=REARM_FIRE")
                                        previousProvider.rearmOneShotCatalogPrefetch("HOME_SWITCH_${homeSwitchRearmDebounceMs}MS")
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        println("[StremioC-Home][PREFETCH_REFRESH] provider=${previousProvider.name} stage=REARM_CANCELLED")
                                        throw e
                                    } catch (t: Throwable) {
                                        println("[StremioC-Home][PREFETCH_REFRESH] provider=${previousProvider.name} stage=REARM_FAILED errorClass=${t.javaClass.simpleName}")
                                    }
                                }
                                val replaced = synchronized(rearmJobsLock) {
                                    rearmJobs.put(key, job)
                                }
                                replaced?.cancel()
                                job.invokeOnCompletion {
                                    synchronized(rearmJobsLock) {
                                        if (rearmJobs[key] === job) rearmJobs.remove(key)
                                    }
                                }
                            }
                        }
                    }
                }

                catalogPrefetchJob = pluginIoScope.launch {
                    // Catalog definitions must be ready first. Then parse the authoritative stored
                    // manifests into typed Manifest objects before the first homepage build.
                    manifestPrefetchJob?.join()
                    val warmStarted = System.nanoTime()
                    try {
                        coroutineScope {
                            registeredProviders.map { provider ->
                                launch { provider.warmMainPageManifest("BOOT") }
                            }.joinAll()
                        }
                        println("[StremioC-Home][MANIFEST_WARM] stage=BOOT_DONE providers=${registeredProviders.size} totalMs=${(System.nanoTime() - warmStarted) / 1_000_000}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        println("[StremioC-Home][MANIFEST_WARM] stage=BOOT_CANCELLED")
                        throw e
                    } catch (t: Throwable) {
                        println("[StremioC-Home][MANIFEST_WARM] stage=BOOT_FAILED errorClass=${t.javaClass.simpleName}")
                    }

                    delay(catalogPrefetchStartupDelayMs)
                    println("[StremioC-Home][PREFETCH_BOOT] stage=START providers=${registeredProviders.size} startupDelayMs=$catalogPrefetchStartupDelayMs bgConcurrency=${StremioC.HOME_PREFETCH_CONCURRENCY} fgConcurrency=${StremioC.HOME_FOREGROUND_CONCURRENCY}")
                    try {
                        coroutineScope {
                            registeredProviders.map { provider ->
                                launch { provider.prefetchEnabledMainPageCatalogs() }
                            }.joinAll()
                        }
                        println("[StremioC-Home][PREFETCH_BOOT] stage=DONE providers=${registeredProviders.size}")
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        println("[StremioC-Home][PREFETCH_BOOT] stage=CANCELLED")
                        throw e
                    } catch (t: Throwable) {
                        println("[StremioC-Home][PREFETCH_BOOT] stage=FAILED errorClass=${t.javaClass.simpleName}")
                    }
                }
            } catch (e: Throwable) {
                Log.e("StremioCPlugin", "reload error ${e.message}")
                println("[StremioCPlugin v3.3-TUNING-B] reload 함수 에러: ${e.message}")
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
