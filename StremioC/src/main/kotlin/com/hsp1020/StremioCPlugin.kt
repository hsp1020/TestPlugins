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

                    if (!manifestPrefs.contains(uniqueKey)) {
                        try {
                            val responseText = com.lagradost.cloudstream3.app.get(currentUrl, timeout = 15L).text
                            if (responseText.isNotBlank()) {
                                val parsed = JSONObject(responseText)
                                if (parsed.has("catalogs")) {
                                    manifestPrefs.edit().putString(uniqueKey, responseText).commit()
                                }
                            }
                        } catch (e: Throwable) {
                            Log.w("StremioCPlugin", "boot manifest prefetch failed: ${e.message}")
                        }
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
        reload(appContext)
        
        val activity = context as? AppCompatActivity
        openSettings = {
            val frag = SettingsBottomFragment(this, appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE))
            activity?.supportFragmentManager?.let { fm -> frag.show(fm, "Frag") }
        }
    }

    fun reload(context: Context) {
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
                    runCatching { provider.cancelOneShotCatalogPrefetch() }
                }
            }
        }
        
        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
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
                val registeredProviders = mutableListOf<StremioC>()
                
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
                            Log.e("StremioCPlugin", "unload failed ${e.message}")
                            e.printStackTrace()
                        }
                    } 
                    
                    try {
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
                    } catch (e: Throwable) {
                        Log.e("StremioCPlugin", "register failed ${e.message}")
                        e.printStackTrace()
                    }
                }
                try {
                    MainActivity.afterPluginsLoadedEvent.invoke(true)
                } catch (e: Throwable) {
                    Log.w("StremioCPlugin", "afterPluginsLoaded invoke failed ${e.message}")
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
                    while (isActive) {
                        delay(homeSelectionPollMs)
                        val currentHome = DataStoreHelper.currentHomePage
                        if (currentHome != lastHome) {
                            val previousHome = lastHome
                            lastHome = currentHome

                            // If the user returns, cancel the debounce/refill coordinator. Rows that have
                            // already started are owned by StremioC's independent one-shot scope, so cancellation
                            // stops only future row starts; foreground can consume READY/IN_FLIGHT entries.
                            currentHome?.let { selectedName ->
                                val key = selectedName.lowercase()
                                val job = synchronized(rearmJobsLock) { rearmJobs.remove(key) }
                                if (job != null) {
                                    job.cancel()
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
                                        delay(homeSwitchRearmDebounceMs)
                                        if (DataStoreHelper.currentHomePage?.equals(previousProvider.name, ignoreCase = true) == true) {
                                            return@launch
                                        }
                                        // The coordinator itself remains cancellable so returning to this provider
                                        // stops the refill loop immediately. StremioC.prefetchOnePage() owns each
                                        // started row in an independent scope and keeps that exact IN_FLIGHT row
                                        // alive until foreground can consume it.
                                        previousProvider.rearmOneShotCatalogPrefetch()
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (t: Throwable) {
                                        Log.w("StremioCPlugin", "catalog rearm failed for ${previousProvider.name}: ${t.message}")
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
                    try {
                        coroutineScope {
                            registeredProviders.map { provider ->
                                launch { provider.warmMainPageManifest() }
                            }.joinAll()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("StremioCPlugin", "manifest warm-up failed: ${t.message}")
                    }

                    delay(catalogPrefetchStartupDelayMs)
                    try {
                        coroutineScope {
                            registeredProviders.map { provider ->
                                launch { provider.prefetchEnabledMainPageCatalogs() }
                            }.joinAll()
                        }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (t: Throwable) {
                        Log.w("StremioCPlugin", "catalog prefetch failed: ${t.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.e("StremioCPlugin", "reload error ${e.message}")
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
