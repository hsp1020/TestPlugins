package com.SyncCS

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.UUID
import java.lang.ref.WeakReference
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY
import com.lagradost.cloudstream3.plugins.PLUGINS_KEY_LOCAL

internal object SyncRuntime {
    @Volatile var pluginsLoaded = false
        private set

    fun refreshLoadedState(): Boolean {
        fun loadedFlag(name: String): Boolean {
            return try {
                val type = Class.forName("com.lagradost.cloudstream3.plugins.PluginManager")
                val owner = type.getField("INSTANCE").get(null)
                val getter = type.methods.firstOrNull {
                    it.name == "get" + name && it.parameterCount == 0
                }
                if (getter != null) {
                    getter.invoke(owner) == true
                } else {
                    val field = type.declaredFields.firstOrNull {
                        it.name == name.replaceFirstChar { c -> c.lowercaseChar() }
                    } ?: return false
                    field.isAccessible = true
                    field.get(if (java.lang.reflect.Modifier.isStatic(field.modifiers)) null else owner) == true
                }
            } catch (_: Exception) {
                false
            } catch (_: LinkageError) {
                false
            }
        }
        pluginsLoaded = loadedFlag("LoadedLocalPlugins") && loadedFlag("LoadedOnlinePlugins")
        return pluginsLoaded
    }
}


@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private val watched = mutableMapOf<String, SharedPreferences>()
    private var loaded = false
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        if (key == null) return@OnSharedPreferenceChangeListener
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
            ?: return@OnSharedPreferenceChangeListener
        val prefix = when (prefs) {
            with(DataStore) { context.getSharedPrefs() } -> "d/"
            PreferenceManager.getDefaultSharedPreferences(context) -> "s/"
            else -> {
                val file = watched.entries.firstOrNull { it.value === prefs }?.key
                    ?: return@OnSharedPreferenceChangeListener
                "p/" + file + "/"
            }
        }
        // No automatic network actions or Supabase writes on preference changes.
        // A removal is only eligible for manual sync if this listener observed it.
        val path = prefix + key
        GitHubSyncEngine.markChanged(context, path)
    }
    private val afterLoaded: (Boolean) -> Unit = {
        SyncRuntime.refreshLoadedState()
        watchPreferences()
    }

    private fun watchPreferences() {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        watch("cloudstream", with(DataStore) { context.getSharedPrefs() })
        watch("default", PreferenceManager.getDefaultSharedPreferences(context))
        val dir = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        dir.listFiles()?.forEach { file ->
            if (file.extension != "xml") return@forEach
            val name = file.nameWithoutExtension
            if (name.equals("StremioC_Manifest", true) ||
                name.equals("AwOriginVisitLoggerPrefs", true) ||
                name.equals("SyncCS_GitHub", true) ||
                name.contains("synccs", true) || name.contains("cache", true) ||
                name.contains("webview", true) || name.contains("encrypted", true) ||
                name.contains("androidx", true)) return@forEach
            watch(name, context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }
    }
    private fun watch(name: String, preferences: SharedPreferences) {
        if (watched.containsKey(name)) return
        watched[name] = preferences
        preferences.registerOnSharedPreferenceChangeListener(listener)
    }
    override fun load(context: Context) {
        if (loaded) return
        loaded = true
        // Critical safety break: previous Supabase background synchronization is never started.
        MainActivity.afterPluginsLoadedEvent += afterLoaded
        MainActivity.mainPluginsLoadedEvent += afterLoaded
        watchPreferences()
        SyncRuntime.refreshLoadedState()
        openSettings = { selected ->
            (selected as? AppCompatActivity)?.let { activity ->
                GitHubSyncSettingsFragment().show(activity.supportFragmentManager, "SyncCS-GitHub")
            }
        }
    }
    fun onUnload() {
        loaded = false
        MainActivity.afterPluginsLoadedEvent -= afterLoaded
        MainActivity.mainPluginsLoadedEvent -= afterLoaded
        watched.values.forEach { it.unregisterOnSharedPreferenceChangeListener(listener) }
        watched.clear()
    }
}
