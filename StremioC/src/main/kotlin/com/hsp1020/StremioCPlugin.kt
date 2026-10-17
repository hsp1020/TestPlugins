// 파일명: hsp1020/StremioCPlugin.kt
package com.hsp1020

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.Build
import android.view.View
import android.view.inspector.WindowInspector
import com.lagradost.cloudstream3.R
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.plugins.PluginData
import com.lagradost.cloudstream3.plugins.PluginManager
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.CloudStreamApp.Companion.getActivity
import com.lagradost.cloudstream3.APIHolder
import com.lagradost.cloudstream3.utils.DataStore.getSharedPrefs
import com.lagradost.cloudstream3.ui.subtitles.SUBTITLE_AUTO_SELECT_KEY
import com.lagradost.cloudstream3.utils.DataStoreHelper
import com.lagradost.cloudstream3.utils.USER_PINNED_PROVIDERS
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

@CloudstreamPlugin
class StremioCPlugin : Plugin() {
    private val PREF_FILE = "StremioC"
    private val PREF_KEY_LINKS = "stremio_saved_links"
    private var pluginIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val reloadMutex = Mutex()
    private var reloadGeneration = 0
    private var isUnloaded = false
    private val ratingUiHandler = Handler(Looper.getMainLooper())
    private var ratingPlotHook: RatingPlotImageHook? = null
    private var activeSettingsDialog: SettingsBottomFragment? = null
    private var activeBrowser: CatalogBrowserDialog? = null
    private val registeredProviders = mutableListOf<StremioC>()
    private var pinSourcePrefs: SharedPreferences? = null
    private var pinStatePrefs: SharedPreferences? = null
    private var pinSourcesReady = false
    private val pinnedProviderListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == USER_PINNED_PROVIDERS) ratingUiHandler.post { syncPinnedSearchOrder() }
    }

    private fun syncPinnedSearchOrder() {
        if (!pinSourcesReady) return
        val state = pinStatePrefs ?: return
        val managedKey = "auto_pinned_search_providers"
        val previouslyManaged = state.getStringSet(managedKey, emptySet())?.toSet().orEmpty()
        val stored = DataStoreHelper.pinnedProviders.toList()
        val original = stored.filterNot { it in previouslyManaged }
        val originalNames = original.toHashSet()
        val searchByUrl = registeredProviders.filter { !it.hasMainPage && it.mainUrl.isNotBlank() }
            .groupBy { it.mainUrl }
        val homeByName = registeredProviders.filter { it.hasMainPage }
            .associateBy { it.name }
        val newManaged = mutableSetOf<String>()
        val target = mutableListOf<String>()
        for (providerName in original) {
            val home = homeByName[providerName]
            if (home != null) {
                // CloudStream ranks pinned names in reverse: keep manifest catalog order on screen.
                for (child in searchByUrl[home.mainUrl].orEmpty().asReversed()) {
                    if (child.name !in originalNames && newManaged.add(child.name)) {
                        target.add(child.name)
                    }
                }
            }
            target.add(providerName)
        }
        if (previouslyManaged != newManaged) {
            state.edit().putStringSet(managedKey, newManaged).apply()
        }
        if (stored != target) {
            DataStoreHelper.pinnedProviders = target.toTypedArray()
        }
    }

    private fun watchPinnedProviders(context: Context) {
        val source = context.getSharedPrefs()
        if (pinSourcePrefs === source) return
        pinSourcePrefs?.unregisterOnSharedPreferenceChangeListener(pinnedProviderListener)
        pinStatePrefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        pinSourcePrefs = source
        source.registerOnSharedPreferenceChangeListener(pinnedProviderListener)
        if (pinSourcesReady) syncPinnedSearchOrder()
    }

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
        }
    }

    override fun load(context: Context) {
        if (isUnloaded) {
            pluginIoScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            reloadScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        }
        isUnloaded = false
        val appContext = context.applicationContext
        // CloudStream stores this setting as a JSON string (e.g. "\"en\"").
        // Repair only legacy bare language tags; leave valid settings untouched.
        val subtitlePrefs = appContext.getSharedPrefs()
        val subtitleLanguage = subtitlePrefs.getString(SUBTITLE_AUTO_SELECT_KEY, null)?.trim()
        if (subtitleLanguage != null &&
            subtitleLanguage.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*"))) {
            subtitlePrefs.edit().putString(
                SUBTITLE_AUTO_SELECT_KEY, JSONObject.quote(subtitleLanguage)
            ).apply()
        }

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
        }

        // --- 3. Restore only missing manifests in the background. No catalog rows are prefetched. ---
        pluginIoScope.launch {
            try {
                val prefs = appContext.getSharedPreferences("StremioC", Context.MODE_PRIVATE)
                val json = prefs.getString("stremio_saved_links", null) ?: "[]"
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
                // Replace only providers registered by this instance, preserving the static StremioC router.
                registeredProviders.forEach { provider ->
                    APIHolder.allProviders.remove(provider)
                    APIHolder.removePluginMapping(provider)
                }
                registeredProviders.clear()
                val manifestPrefs = appContext.getSharedPreferences("StremioC_Manifest", Context.MODE_PRIVATE)
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
                        }
                    } 
                    
                    try {
                        val rawManifest = withContext(Dispatchers.IO) {
                            stremioReadManifest(manifestPrefs, item.link, requireFresh = true)
                                ?: (runCatching {
                                    StremioC.customSession.get(stremioManifestUrl(item.link), timeout = 15L)
                                }.onFailure { error ->
                                }.getOrNull()?.let { response ->
                                    val raw = response.text.takeIf { it.isNotBlank() }
                                        ?: return@let null
                                    if (!runCatching { JSONObject(raw).has("catalogs") }.getOrDefault(false)) {
                                        return@let null
                                    }
                                    val key = stremioCatalogKey(item.link)
                                    val cachePolicy = stremioManifestCachePolicy(response.headers)
                                    val noStore = cachePolicy.noStore
                                    val ttl = cachePolicy.ttlMs
                                    val newVersion = runCatching { JSONObject(raw).optString("version") }
                                        .getOrNull()?.takeIf { it.isNotBlank() }
                                    manifestPrefs.edit().apply {
                                        if (noStore) {
                                            putBoolean("manifest_no_store_" + key, true)
                                            remove(key)
                                            remove("manifest_saved_at_" + key)
                                            remove("manifest_ttl_ms_" + key)
                                        } else {
                                            putBoolean("manifest_no_store_" + key, false)
                                            putString(key, raw)
                                            putLong("manifest_saved_at_" + key, System.currentTimeMillis())
                                            putLong("manifest_ttl_ms_" + key, ttl)
                                        }
                                        if (newVersion != null) putString("manifest_version_" + key, newVersion)
                                    }.apply()
                                    raw
                                })
                                ?: stremioReadManifest(manifestPrefs, item.link)
                        }
                        currentCoroutineContext().ensureActive()
                        val manifest = rawManifest?.let {
                            runCatching { parseJson<StremioC.Manifest>(it) }.getOrNull()
                        }
                        val searchCatalogs = manifest?.catalogs.orEmpty()
                            .filter { it.supportsSearch() && !it.id.isNullOrBlank() && !it.type.isNullOrBlank() }
                            .distinctBy { it.id to it.type }

                        // The original addon owns Home and metadata only; it never contributes search results.
                        val homeProvider = StremioC(item.link, item.name, item.id, disableSearch = true)
                        manifest?.let {
                            val noStore = manifestPrefs.getBoolean(
                                "manifest_no_store_" + stremioCatalogKey(item.link), false)
                            val ttl = manifestPrefs.getLong(
                                "manifest_ttl_ms_" + stremioCatalogKey(item.link), 3_600_000L)
                            homeProvider.primeManifest(it, if (noStore) 0L else ttl)
                        }
                        registerMainAPI(homeProvider)
                        registeredProviders.add(homeProvider)

                        searchCatalogs.forEach { catalog ->
                            val catalogName = catalog.name?.trim().orEmpty()
                                .ifBlank { catalog.id.orEmpty() }
                            val typeName = catalog.type.orEmpty().replaceFirstChar { it.uppercase() }
                            val label = "${catalogName} - ${typeName}"
                            val occupied = APIHolder.allProviders.mapTo(HashSet()) { it.name }
                            var uniqueLabel = label
                            var suffix = 2
                            while (uniqueLabel in occupied) {
                                uniqueLabel = "${label} (${suffix++})"
                            }
                            val provider = StremioC(
                                item.link, uniqueLabel, item.id, catalog.id, catalog.type,
                                showHome = false
                            )
                            provider.primeManifest(requireNotNull(manifest))
                            registerMainAPI(provider)
                            registeredProviders.add(provider)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                    }
                }
                currentCoroutineContext().ensureActive()
                pinSourcesReady = true
                syncPinnedSearchOrder()
                try {
                    MainActivity.afterPluginsLoadedEvent.invoke(true)
                } catch (e: Throwable) {
                }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                }
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
