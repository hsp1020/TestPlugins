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
    private val workerGuard = Any()
    private var worker: Job? = null
    private var rerunRequested = false
    private var periodic: Job? = null
    private var callback: Application.ActivityLifecycleCallbacks? = null
    private var activityCount = 0
    private var ready = false
    private var foreground = true
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
                request(400, "profile-switch")
            }
            return@OnSharedPreferenceChangeListener
        }
        if (type == "d/" && (key == PLUGINS_KEY || key == PLUGINS_KEY_LOCAL)) {
            if (SyncCoordinator.applying) return@OnSharedPreferenceChangeListener
            SyncCoordinator.markChanged(context, "x/plugins")
            request(700, "plugin-inventory")
            return@OnSharedPreferenceChangeListener
        }
        val path = type + key
        if (!SyncData.isTracked(path)) return@OnSharedPreferenceChangeListener
        SyncCoordinator.markChanged(context, path)
        SyncTrace.keys("local-change", listOf(path))
        request(1800, "preference-change")
    }
    private val bookmarks = { _: Boolean -> request(2000, "bookmarks") }
    private val loaded = { _: Boolean ->
        ready = true
        SyncRuntime.refreshLoadedState()
        watchPreferences()
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
        if (context != null && LocalSyncJournal.pending(context)) {
            queueSync("unfinished-local-journal")
        } else request(4000, "plugins-loaded")
        Unit
    }
    private val home = { _: Boolean -> request(3000, "home-reload") }
    private val accountRoster = { _: Boolean ->
        val context = com.lagradost.cloudstream3.CloudStreamApp.context
        if (context != null && isOwner(context) && !SyncCoordinator.applying) {
            ProfileIdentity.observe(context)
        }
        request(3000, "account-reload")
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
    private fun queueSync(reason: String) {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        if (!isOwner(context) || !config(context).active()) return
        synchronized(workerGuard) {
            rerunRequested = true
            if (worker?.isActive == true) {
                SyncTrace.info("coalesce", "reason=$reason pending=1")
                return
            }
            worker = scope.launch {
                while (true) {
                    val next = synchronized(workerGuard) {
                        if (rerunRequested) {
                            rerunRequested = false
                            true
                        } else {
                            worker = null
                            false
                        }
                    }
                    if (!next) break
                    if (!isOwner(context) || !config(context).active()) {
                        synchronized(workerGuard) { worker = null }
                        break
                    }
                    val active = currentActivity?.get()?.takeIf {
                        !it.isFinishing && !it.isDestroyed
                    }
                    SyncTrace.info("worker-dispatch", "reason=$reason")
                    SyncTrace.memory("scheduled-sync")
                    SyncCoordinator.synchronize(context, active)
                }
            }
        }
    }

    private fun request(delayMs: Long, reason: String) {
        val context = com.lagradost.cloudstream3.CloudStreamApp.context ?: return
        if (!ready || !isOwner(context) || !config(context).active()) return
        SyncTrace.info("trigger", "reason=$reason delayMs=$delayMs")
        trigger?.cancel()
        trigger = scope.launch {
            delay(delayMs)
            if (isOwner(context)) {
                // Debounce only the pending timer; collapse in-flight events
                // into one follow-up pass without discarding any new changes.
                queueSync(reason)
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
                // onResumed performs the single HEAD check for this transition.
            }
            override fun onActivityResumed(activity: Activity) {
                currentActivity = WeakReference(activity)
                if (!isOwner(context) || !ready) return
                // An interrupted original restore may have disconnected the
                // account. Always finish its local journal before normal sync.
                if (LocalSyncJournal.pending(context)) {
                    queueSync("local-journal-on-resume")
                    return
                }
                if (!config(context).active()) return
                request(750, "activity-resumed")
            }
            override fun onActivityPaused(activity: Activity) {
                request(1500, "activity-paused")
            }
            override fun onActivityStopped(activity: Activity) {
                if (!isOwner(context)) return
                activityCount = maxOf(0, activityCount - 1)
                if (activityCount == 0) {
                    foreground = false
                    request(500, "activity-stopped")
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
                // Poll only the remote Git revision during steady state.
                // A changed HEAD (or local edits) triggers a complete verified merge.
                delay(20 * 1000L)
                if (!isOwner(context)) break
                if (foreground && ready && config(context).active()) {
                    SyncTrace.info("trigger", "reason=remote-head-poll-20s")
                    queueSync("remote-head-poll-20s")
                }
            }
        }
    }
    override fun load(context: Context) {
        SyncTrace.info("plugin-loaded", "version=P0P2-auto-sync-headpoll-20261022 " +
            "classloader=${javaClass.classLoader?.javaClass?.simpleName}")
        SyncTrace.memory("plugin-load")
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
