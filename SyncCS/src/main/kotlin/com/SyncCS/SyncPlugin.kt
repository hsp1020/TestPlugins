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
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val owner = UUID.randomUUID().toString()
    private var trigger: Job? = null
    private var periodic: Job? = null
    private var callback: Application.ActivityLifecycleCallbacks? = null
    private var activityCount = 0
    private var ready = false
    private var foreground = true
    private var lastSyncAt = 0L
    private var currentActivity: WeakReference<Activity>? = null
    private val observed = mutableMapOf<String, SharedPreferences>()
    private val observedListener = SharedPreferences.OnSharedPreferenceChangeListener { prefs, key ->
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
            ?: return@OnSharedPreferenceChangeListener
        if (key == null || !isOwner(context)) return@OnSharedPreferenceChangeListener
        val type = when (prefs) {
            with(DataStore) { context.getSharedPrefs() } -> "d/"
            PreferenceManager.getDefaultSharedPreferences(context) -> "s/"
            else -> {
                val name = observed.entries.firstOrNull { it.value === prefs }?.key ?: ""
                if (name.isBlank()) "" else "p/" + name + "/"
            }
        }
        if (type == "d/" && key == "data_store_helper/account") {
            if (!SyncCoordinator.applying) {
                ProfileIdentity.observe(context)
                request(400)
            }
            return@OnSharedPreferenceChangeListener
        }
        if (type == "d/" && (key == PLUGINS_KEY || key == PLUGINS_KEY_LOCAL)) {
            if (SyncCoordinator.applying) return@OnSharedPreferenceChangeListener
            SyncCoordinator.markChanged(context, "x/plugins")
            request(700)
            return@OnSharedPreferenceChangeListener
        }
        val path = type + key
        if (!SyncData.isTracked(path)) return@OnSharedPreferenceChangeListener
        SyncCoordinator.markChanged(context, path)
        request(1800)
    }
    private val bookmarks = { _: Boolean -> request(2000) }
    private val loaded = { _: Boolean ->
        ready = true
        SyncRuntime.refreshLoadedState()
        watchPreferences()
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
        if (context != null && LocalSyncJournal.pending(context)) {
            scope.launch {
                val active = currentActivity?.get()?.takeIf {
                    !it.isFinishing && !it.isDestroyed
                }
                SyncCoordinator.synchronize(context, active)
            }
        } else request(4000)
        Unit
    }
    private val home = { _: Boolean -> request(3000) }
    private val accountRoster = { _: Boolean ->
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
        if (context != null && isOwner(context) && !SyncCoordinator.applying) {
            ProfileIdentity.observe(context)
        }
        request(3000)
    }

    private fun config(context: Context) = SyncConfiguration(context)
    private fun isOwner(context: Context): Boolean =
        config(context).preferences.getString("active_instance", "") == owner

    private fun watchPreferences() {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        val prefs = with(DataStore) { context.getSharedPrefs() }
        observe("core", prefs)
        observe("default", PreferenceManager.getDefaultSharedPreferences(context))
        val directory = java.io.File(context.applicationInfo.dataDir, "shared_prefs")
        directory.listFiles()?.forEach { file ->
            if (file.extension != "xml") return@forEach
            val name = file.nameWithoutExtension
            if (name == "rebuild_preference" || name == context.packageName + "_preferences" ||
                name.contains("syncCS", true) || name.contains("cache", true) ||
                name.contains("webview", true) || name.contains("firebase", true) ||
                name.contains("androidx", true) || name.contains("encrypted", true)) return@forEach
            observe(name, context.getSharedPreferences(name, Context.MODE_PRIVATE))
        }
    }
    private fun observe(name: String, pref: SharedPreferences) {
        if (observed.containsKey(name)) return
        observed[name] = pref
        pref.registerOnSharedPreferenceChangeListener(observedListener)
    }
    private fun request(delayMs: Long) {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        if (!ready || !isOwner(context) || !config(context).active()) return
        trigger?.cancel()
        trigger = scope.launch {
            delay(delayMs)
            if (isOwner(context)) {
                val active = currentActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                val report = SyncCoordinator.synchronize(context, active)
                if (report.startsWith("Synced:")) lastSyncAt = System.currentTimeMillis()
            }
        }
    }
    private fun start(context: Context) {
        val app = context.applicationContext as? Application ?: return
        callback = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, state: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                if (!isOwner(context)) return
                activityCount += 1
                foreground = true
                request(2500)
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                if (!isOwner(context) || !ready) return
                // An interrupted original restore may have disconnected the
                // account. Always finish its local journal before normal sync.
                if (LocalSyncJournal.pending(context)) {
                    scope.launch { SyncCoordinator.synchronize(context, activity) }
                    return
                }
                if (!config(context).active()) return
                scope.launch {
                    if (System.currentTimeMillis() - lastSyncAt > 120000L) {
                        val report = SyncCoordinator.synchronize(context, activity)
                        if (report.startsWith("Synced:")) lastSyncAt = System.currentTimeMillis()
                    }
                }
            }
            override fun onActivityPaused(activity: Activity) {
                request(1500)
            }
            override fun onActivityStopped(activity: Activity) {
                if (!isOwner(context)) return
                activityCount = maxOf(0, activityCount - 1)
                if (activityCount == 0) {
                    foreground = false
                    request(500)
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, bundle: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                if (currentActivity?.get() === activity) currentActivity = null
            }
        }
        app.registerActivityLifecycleCallbacks(callback!!)
        periodic = scope.launch {
            while (isActive) {
                delay(15 * 60 * 1000L)
                if (!isOwner(context)) break
                if (foreground && ready && config(context).active()) {
                    val active = currentActivity?.get()?.takeIf { !it.isFinishing && !it.isDestroyed }
                    val report = SyncCoordinator.synchronize(context, active)
                    if (report.startsWith("Synced:")) lastSyncAt = System.currentTimeMillis()
                }
            }
        }
    }
    override fun load(context: Context) {
        config(context).preferences.edit().putString("active_instance", owner).commit()
        ready = SyncRuntime.refreshLoadedState()
        ProfileIdentity.observe(context)
        MainActivity.bookmarksUpdatedEvent += bookmarks
        MainActivity.afterPluginsLoadedEvent += loaded
        MainActivity.mainPluginsLoadedEvent += loaded
        MainActivity.reloadHomeEvent += home
        MainActivity.reloadAccountEvent += accountRoster
        watchPreferences()
        start(context)
        openSettings = { selected ->
            (selected as? AppCompatActivity)?.let { activity ->
                SyncSettingsFragment().show(activity.supportFragmentManager, "SyncCS")
            }
        }
    }
    fun unload() {
        currentActivity = null
        scope.cancel()
        trigger?.cancel()
        periodic?.cancel()
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
        if (context != null) {
            (context.applicationContext as? Application)?.let { app ->
                callback?.let { app.unregisterActivityLifecycleCallbacks(it) }
            }
            observed.values.forEach { it.unregisterOnSharedPreferenceChangeListener(observedListener) }
            observed.clear()
            if (isOwner(context)) config(context).preferences.edit().remove("active_instance").apply()
        }
        MainActivity.bookmarksUpdatedEvent -= bookmarks
        MainActivity.afterPluginsLoadedEvent -= loaded
        MainActivity.mainPluginsLoadedEvent -= loaded
        MainActivity.reloadHomeEvent -= home
        MainActivity.reloadAccountEvent -= accountRoster
    }
    fun onUnload() = unload()
}
