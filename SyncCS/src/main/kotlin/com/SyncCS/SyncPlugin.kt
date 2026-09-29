package com.SyncCS

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mapper
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var syncJob: Job? = null
    private var backgroundDelayJob: Job? = null
    private var debounceJob: Job? = null
    private val backupMutex = Mutex()
    private val restoreInFlight = AtomicBoolean(false)
    private val instanceToken = UUID.randomUUID().toString()

    @Volatile private var isAppInForeground = true
    @Volatile private var isRestoring = false
    @Volatile private var lastRestoreTime = 0L

    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null
    private var listenersRegistered = false

    private val bookmarksListener = { _: Boolean ->
        if (isCurrentInstance()) triggerBackup(2000L)
    }
    private val afterPluginsListener = { _: Boolean ->
        if (isCurrentInstance()) triggerBackup(5000L)
    }
    private val mainPluginsListener = { _: Boolean ->
        if (isCurrentInstance()) triggerBackup(5000L)
    }
    private val reloadHomeListener = { _: Boolean ->
        if (isCurrentInstance() && !isRestoring) triggerBackup(3000L)
    }
    private val reloadAccountListener = { _: Boolean ->
        if (isCurrentInstance() && !isRestoring) triggerBackup(3000L)
    }

    companion object {
        private const val ACTIVE_INSTANCE_KEY = "synccs_active_instance_token"
    }

    /**
     * CloudStream can recreate/reload a plugin without the previous plugin instance having
     * completed unload yet. Event listeners and lifecycle callbacks from those old instances
     * may therefore still fire. Keep one process-wide logical owner in CloudStream's shared
     * preferences so stale instances become harmless no-ops even across plugin classloaders.
     */
    private fun claimActiveInstance(context: Context) {
        synchronized(CloudStreamApp::class.java) {
            val prefs = with(DataStore) { context.getSharedPrefs() }
            prefs.edit().putString(ACTIVE_INSTANCE_KEY, instanceToken).commit()
        }
        Log.d("SYNC", "[SyncPlugin] Active instance claimed: ${instanceToken.take(8)}")
    }

    private fun isCurrentInstance(context: Context? = CloudStreamApp.context): Boolean {
        val ctx = context ?: return false
        val prefs = with(DataStore) { ctx.getSharedPrefs() }
        return prefs.getString(ACTIVE_INSTANCE_KEY, null) == instanceToken
    }

    private fun releaseActiveInstance() {
        val context = CloudStreamApp.context ?: return
        synchronized(CloudStreamApp::class.java) {
            val prefs = with(DataStore) { context.getSharedPrefs() }
            if (prefs.getString(ACTIVE_INSTANCE_KEY, null) == instanceToken) {
                prefs.edit().remove(ACTIVE_INSTANCE_KEY).commit()
            }
        }
    }

    private fun startSyncLoop(context: Context) {
        Log.d("SYNC", "[SyncPlugin] startSyncLoop() initiated.")

        val app = context.applicationContext as? Application
        if (app != null && lifecycleCallbacks == null) {
            lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                private var activeActivities = 0

                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

                override fun onActivityStarted(activity: Activity) {
                    if (!isCurrentInstance()) return
                    backgroundDelayJob?.cancel()
                    activeActivities++
                    isAppInForeground = true
                    restoreDevice()
                }

                override fun onActivityResumed(activity: Activity) {}

                override fun onActivityPaused(activity: Activity) {
                    if (!isCurrentInstance()) return
                    // 일시정지/화면이탈 시 시청 중이던 위치를 1회 백업
                    triggerBackup(500L)
                }

                override fun onActivityStopped(activity: Activity) {
                    if (!isCurrentInstance()) return
                    activeActivities = maxOf(0, activeActivities - 1)
                    if (activeActivities <= 0) {
                        backgroundDelayJob?.cancel()
                        backgroundDelayJob = pluginScope.launch {
                            delay(2000L)
                            if (!isCurrentInstance()) return@launch
                            isAppInForeground = false
                            executeBackup()
                        }
                    }
                }

                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks!!)
        }

        syncJob?.cancel()
        syncJob = pluginScope.launch {
            lastRestoreTime = System.currentTimeMillis()
            while (isActive) {
                try {
                    if (!isCurrentInstance()) {
                        Log.d("SYNC", "[SyncPlugin] Stale instance sync loop stopped: ${instanceToken.take(8)}")
                        break
                    }
                    if (isAppInForeground) {
                        val now = System.currentTimeMillis()
                        if (now - lastRestoreTime >= 300000L) { // 5분 주기 복원
                            restoreDevice()
                            lastRestoreTime = now
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(60000L)
            }
        }
    }

    private fun triggerBackup(delayMs: Long = 3000L) {
        if (!isCurrentInstance() || isRestoring) return
        debounceJob?.cancel()
        debounceJob = pluginScope.launch {
            delay(delayMs)
            if (!isCurrentInstance() || isRestoring) return@launch
            executeBackup()
        }
    }

    private suspend fun executeBackup() {
        if (!isCurrentInstance() || isRestoring) return
        backupMutex.withLock {
            if (!isCurrentInstance() || isRestoring) return@withLock
            try {
                Log.d("SYNC", "[SyncPlugin] Executing backupDevice() logic...")
                val context = CloudStreamApp.context ?: return@withLock
                val prefs = with(DataStore) { context.getSharedPrefs() }

                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    try { mapper.readValue<String>(it) } catch(e: Exception) { "false" }
                } == "true"

                if (isBackupEnabled) {
                    val backupFile = BackupUtils.getBackup(context)
                    if (!isCurrentInstance(context)) return@withLock
                    val backupJson = mapper.writeValueAsString(backupFile)
                    Log.d("SYNC", "[SyncPlugin] Backup JSON created. Calling ApiUtils.syncThisDevice().")
                    ApiUtils.syncThisDevice(backupJson, context)
                } else {
                    Log.d("SYNC", "[SyncPlugin] Backup is disabled in settings.")
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restoreDevice() {
        if (!isCurrentInstance()) return
        Log.d("SYNC", "[SyncPlugin] restoreDevice() called.")
        val context = CloudStreamApp.context ?: return
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val restoreEnabled = prefs.getString("restore_device", null)?.let {
            try { mapper.readValue<String>(it) } catch (e: Exception) { "false" }
        } == "true"

        if (!restoreEnabled) {
            Log.d("SYNC", "[SyncPlugin] Restore disabled. Skipping.")
            return
        }

        if (!restoreInFlight.compareAndSet(false, true)) {
            Log.d("SYNC", "[SyncPlugin] Restore already in progress, skipping duplicate request.")
            return
        }

        pluginScope.launch {
            try {
                // Serialize the entire remote restore transaction with backups.
                // This prevents a stale local backup PATCH from racing a foreground restore GET/merge.
                backupMutex.withLock {
                    if (!isCurrentInstance(context)) return@withLock

                    val devices = ApiUtils.fetchDevices()
                    if (!isCurrentInstance(context)) return@withLock

                    val currentDeviceId = try {
                        prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) }
                    } catch (e: Exception) {
                        null
                    } ?: UtilKt.getDeviceId(context.packageName, context)

                    for (device in devices) {
                        if (!isCurrentInstance(context)) return@withLock

                        if (device.deviceId == currentDeviceId) {
                            Log.d("SYNC", "[SyncPlugin] Skipping own device: ${device.name}")
                            continue
                        }

                        val lastHashKey = "sync_last_hash_${device.deviceId}"
                        val lastHash = prefs.getString(lastHashKey, "")
                        val currentHash = UtilKt.md5(device.syncedData ?: "")

                        if (lastHash != currentHash) {
                            Log.d("SYNC", "[SyncPlugin] New data found for device: ${device.name}. Merging...")
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.decompressJsonFromString(encoded)
                                if (decoded.isNotBlank()) {
                                    isRestoring = true
                                    try {
                                        val backupFile = mapper.readValue<BackupFile>(decoded)
                                        BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                        prefs.edit().putString(lastHashKey, currentHash).apply()
                                        Log.d("SYNC", "[SyncPlugin] Restore merge successful for ${device.name}")
                                    } finally {
                                        delay(1000L)
                                        isRestoring = false
                                    }
                                }
                            }
                        } else {
                            Log.d("SYNC", "[SyncPlugin] Device ${device.name} is already up-to-date.")
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                restoreInFlight.set(false)
            }
        }
    }

    fun onUnload() {
        unload()
    }

    fun unload() {
        Log.d("SYNC", "[SyncPlugin] Unloading plugin. Cancelling active coroutine jobs and unregistering callbacks...")
        pluginScope.cancel()

        val app = CloudStreamApp.context?.applicationContext as? Application
        lifecycleCallbacks?.let { callbacks ->
            app?.unregisterActivityLifecycleCallbacks(callbacks)
            lifecycleCallbacks = null
        }

        if (listenersRegistered) {
            MainActivity.bookmarksUpdatedEvent -= bookmarksListener
            MainActivity.afterPluginsLoadedEvent -= afterPluginsListener
            MainActivity.mainPluginsLoadedEvent -= mainPluginsListener
            MainActivity.reloadHomeEvent -= reloadHomeListener
            MainActivity.reloadAccountEvent -= reloadAccountListener
            listenersRegistered = false
        }

        releaseActiveInstance()
    }

    override fun load(context: Context) {
        // Claim ownership before starting any lifecycle/event work. Any previous plugin
        // instance that survived a reload will immediately become a no-op.
        claimActiveInstance(context)

        CloudStreamApp.setKey("device_id", UtilKt.getDeviceId(context.packageName, context))
        restoreDevice()
        startSyncLoop(context)

        if (!listenersRegistered) {
            MainActivity.bookmarksUpdatedEvent += bookmarksListener
            MainActivity.afterPluginsLoadedEvent += afterPluginsListener
            MainActivity.mainPluginsLoadedEvent += mainPluginsListener
            MainActivity.reloadHomeEvent += reloadHomeListener
            MainActivity.reloadAccountEvent += reloadAccountListener
            listenersRegistered = true
        }

        openSettings = { activityContext ->
            val appCompatActivity = activityContext as? AppCompatActivity
            appCompatActivity?.let {
                SyncSettingsFragment(this).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
