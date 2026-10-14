// 파일명: hsp1020/StremioCPlugin.kt
package com.hsp1020

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector
import com.lagradost.cloudstream3.R
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.api.Log
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.APIHolder
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"
    private val pluginIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ratingUiHandler = Handler(Looper.getMainLooper())
    private var ratingPlotHook: RatingPlotImageHook? = null
    private var activeSettingsDialog: SettingsBottomFragment? = null
    private var activeBrowser: CatalogBrowserDialog? = null

    private fun dismissProviderPicker(host: AppCompatActivity) {
        // selectHomepage keeps its BottomSheetDialog local and passes only the Activity
        // to openSettings. Invoke that specific sheet's existing Cancel action once.
        // This installs no window/lifecycle/navigation hooks and never applies a provider.
        runCatching {
            val roots = if (Build.VERSION.SDK_INT >= 29) WindowInspector.getGlobalWindowViews() else {
                // WindowInspector's equivalent on Android 23-28 (no public API there).
                val manager = Class.forName("android.view.WindowManagerGlobal")
                val instance = manager.getMethod("getInstance").invoke(null)
                @Suppress("UNCHECKED_CAST")
                (manager.getDeclaredField("mViews").apply { isAccessible = true }
                    .get(instance) as List<View>).toList()
            }
            roots.firstOrNull { root ->
                root.context.getActivity() === host &&
                    root.findViewById<View>(R.id.listview1) != null &&
                    root.findViewById<View>(R.id.tvtypes_chips_scroll) != null
            }?.findViewById<View>(R.id.cancel_btt)?.performClick()
        }.onFailure { Log.w("StremioCPlugin", "Provider picker dismissal failed: ${it.message}") }
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
                }
            }
        } catch (e: Throwable) {
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
                }
            }
        } catch (e: Throwable) {
            e.printStackTrace()
        }

        // --- 3. Restore only missing manifests in the background. No catalog rows are prefetched. ---
        pluginIoScope.launch {
            try {
                val prefs = appContext.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val json = prefs.getString("stremio_saved_links", null) ?: "[]"
                val arr = JSONArray(json)
                val links = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.optBoolean("enabled", true)) continue
                    val link = obj.optString("link", "")
                    if (link.isNotBlank()) {
                        links.add(link)
                    }
                }

                val manifestPrefs = appContext.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
                
                val manifestFetchSemaphore = Semaphore(4)
                coroutineScope {
                    links.distinct().map { link ->
                        launch {
                            val currentUrl = stremioManifestUrl(link)
                            val uniqueKey = stremioCatalogKey(link)
                            if (manifestPrefs.contains(uniqueKey)) return@launch

                            manifestFetchSemaphore.withPermit {
                                try {
                                    val responseText = com.lagradost.cloudstream3.app.get(currentUrl, timeout = 15L).text
                                    if (responseText.isNotBlank()) {
                                        val parsed = JSONObject(responseText)
                                        if (parsed.has("catalogs")) {
                                            synchronized(manifestPrefs) {
                                                manifestPrefs.edit().putString(uniqueKey, responseText).commit()
                                            }
                                        }
                                    }
                                } catch (e: Throwable) {
                                    Log.w("StremioCPlugin", "boot manifest fetch failed: ${e.message}")
                                }
                            }
                        }
                    }.joinAll()
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

        // Only overlays existing MDBList emojis with bundled logos.
        // No changes to MDBList fetching, metadata, stream URLs, subtitles or ranking.
        ratingUiHandler.post {
            runCatching { ratingPlotHook?.stop() }
            ratingPlotHook = resources?.let { pluginResources ->
                runCatching {
                    RatingPlotImageHook(appContext, pluginResources) { target, snapshot ->
                        (CommonActivity.activity as? AppCompatActivity)?.let { host ->
                            openCatalogBrowser(host, target, snapshot)
                        }
                    }.also { it.start() }
                }.onFailure { Log.w("StremioCPlugin", "Rating image hook unavailable: ${it.message}") }.getOrNull()
            }
        }
        
        openSettings = { currentContext ->
            (currentContext.getActivity() as? AppCompatActivity)?.let { host ->
                if (host.isFinishing || host.isDestroyed) return@let
                activeBrowser?.dismiss()
                activeSettingsDialog?.dismiss()
                dismissProviderPicker(host)
                val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                lateinit var settings: SettingsBottomFragment
                settings = SettingsBottomFragment(this, prefs, host)
                activeSettingsDialog = settings
                settings.show {
                    if (activeSettingsDialog === settings) activeSettingsDialog = null
                }
            }
        }
    }

    private fun openCatalogBrowser(
        host: AppCompatActivity,
        target: CatalogShortcutTarget? = null,
        snapshot: CatalogHomeSnapshot? = null,
        closeProviderPicker: Boolean = false
    ) {
        if (host.isFinishing || host.isDestroyed) return
        val pluginResources = resources ?: return
        activeBrowser?.dismiss()
        activeSettingsDialog?.dismiss()
        if (closeProviderPicker) dismissProviderPicker(host)
        val prefs = host.applicationContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        lateinit var browser: CatalogBrowserDialog
        // Cinemeta's built-in "top" catalog is not an AIOMetadata catalog.
        val catalogTarget = if (target?.catalogId == "top" && !target.genre.isNullOrBlank())
            target.copy(addonUrl = "https://v3-cinemeta.strem.io/manifest.json")
        else target
        browser = CatalogBrowserDialog(host, pluginResources, prefs, { url ->
            APIHolder.allProviders.filterIsInstance<StremioC>().firstOrNull {
                stremioManifestUrl(it.mainUrl.fixSourceUrl()) == stremioManifestUrl(url.fixSourceUrl())
            }
        }, catalogTarget, snapshot)
        activeBrowser = browser
        browser.show {
            if (activeBrowser === browser) {
                activeBrowser = null
                activeSettingsDialog?.dismiss()
                activeSettingsDialog = null
            }
        }
    }

    override fun beforeUnload() {
        ratingUiHandler.post {
            activeBrowser?.dismiss()
            activeBrowser = null
            activeSettingsDialog?.dismiss()
            activeSettingsDialog = null
            runCatching { ratingPlotHook?.stop() }
            ratingPlotHook = null
        }
        super.beforeUnload()
    }

    fun reload(context: Context) {
        val appContext = context.applicationContext

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
                val json = prefs.getString(PREF_KEY_LINKS, null) ?: "[]"
                
                val arr = JSONArray(json)
                val links = mutableListOf<LinkEntry>()
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    if (!obj.optBoolean("enabled", true)) continue
                    links.add(
                        LinkEntry(
                            id = obj.optLong("id", System.currentTimeMillis()),
                            name = obj.optString("name", ""),
                            link = obj.optString("link", ""),
                            type = obj.optString("type", "StremioC")
                        )
                    )
                }
                val pluginsOnline: Array<PluginData> = PluginManager.getPluginsOnline()
                val unloadedPluginPaths = mutableSetOf<String>()
                
                for (item in links) {
                    var found: PluginData? = null
                    for (p in pluginsOnline) {
                        if (p.internalName.contains(item.name, ignoreCase = true)) {
                            found = p
                            break
                        }
                    }
                    if (found != null && unloadedPluginPaths.add(found.filePath)) {
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
                                    val provider = StremioC(item.link, item.name, item.id)
                                    registerMainAPI(provider)
                                } catch (e: Throwable) {
                                    e.printStackTrace()
                                    try { registerMainAPI(StremioC("", item.name, item.id)) } catch (e2: Throwable) { e2.printStackTrace() }
                                }
                            }
                            else -> {
                                try {
                                    val provider = StremioC(item.link, item.name, item.id)
                                    registerMainAPI(provider)
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
