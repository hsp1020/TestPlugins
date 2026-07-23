package com.SyncCS

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.AcraApplication
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

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    private var syncJob: Job? = null
    private var backgroundDelayJob: Job? = null
    private var debounceJob: Job? = null
    private val backupMutex = Mutex()
    
    private var isAppInForeground = true
    private var isRestoring = false
    private var lastRestoreTime = 0L

    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    private val bookmarksListener = { _: Boolean -> triggerBackup(2000L) }
    private val afterPluginsListener = { _: Boolean -> triggerBackup(5000L) }
    private val mainPluginsListener = { _: Boolean -> triggerBackup(5000L) }
    private val reloadHomeListener = { _: Boolean -> 
        if (!isRestoring) triggerBackup(3000L) 
    }
    private val reloadAccountListener = { _: Boolean -> 
        if (!isRestoring) triggerBackup(3000L) 
    }

    companion object {
        private var isLifecycleRegistered = false 
    }

    private fun startSyncLoop(context: Context) {
        Log.d("SYNC", "[SyncPlugin] startSyncLoop() initiated.")
        
        val app = context.applicationContext as? Application
        if (app != null && !isLifecycleRegistered) {
            isLifecycleRegistered = true
            lifecycleCallbacks = object : Application.ActivityLifecycleCallbacks {
                private var activeActivities = 0
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {
                    backgroundDelayJob?.cancel() 
                    activeActivities++
                    isAppInForeground = true
                    restoreDevice() 
                }
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {
                    // 일시정지/화면이탈 시 시청 중이던 위치를 1회 백업
                    triggerBackup(500L)
                }
                override fun onActivityStopped(activity: Activity) {
                    activeActivities = maxOf(0, activeActivities - 1)
                    if (activeActivities <= 0) {
                        backgroundDelayJob?.cancel()
                        backgroundDelayJob = pluginScope.launch {
                            delay(2000L)
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
        if (isRestoring) return
        debounceJob?.cancel()
        debounceJob = pluginScope.launch {
            delay(delayMs)
            executeBackup()
        }
    }

    private suspend fun executeBackup() {
        if (isRestoring) return
        backupMutex.withLock {
            try {
                Log.d("SYNC", "[SyncPlugin] Executing backupDevice() logic...")
                val context = AcraApplication.context ?: return@withLock
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    try { mapper.readValue<String>(it) } catch(e: Exception) { "false" }
                } == "true"
                
                if (isBackupEnabled) {
                    val backupFile = BackupUtils.getBackup(context)
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
        Log.d("SYNC", "[SyncPlugin] restoreDevice() called.")
        val context = AcraApplication.context ?: return
        val prefs = with(DataStore) { context.getSharedPrefs() }
        val restoreEnabled = prefs.getString("restore_device", null)?.let {
            try { mapper.readValue<String>(it) } catch (e: Exception) { "false" }
        } == "true"

        if (!restoreEnabled) {
            Log.d("SYNC", "[SyncPlugin] Restore disabled. Skipping.")
            return
        }

        pluginScope.launch {
            try {
                val devices = ApiUtils.fetchDevices()
                val currentDeviceId = try {
                    prefs.getString("device_id", null)?.let { mapper.readValue<String>(it) }
                } catch (e: Exception) {
                    null
                } ?: UtilKt.getDeviceId(context.packageName, context)

                for (device in devices) {
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
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun onUnload() {
        unload()
    }

    fun unload() {
        Log.d("SYNC", "[SyncPlugin] Unloading plugin. Cancelling active coroutine jobs and unregistering callbacks...")
        pluginScope.cancel()

        val app = AcraApplication.context?.applicationContext as? Application
        lifecycleCallbacks?.let { callbacks ->
            app?.unregisterActivityLifecycleCallbacks(callbacks)
            lifecycleCallbacks = null
            isLifecycleRegistered = false
        }

        MainActivity.bookmarksUpdatedEvent -= bookmarksListener
        MainActivity.afterPluginsLoadedEvent -= afterPluginsListener
        MainActivity.mainPluginsLoadedEvent -= mainPluginsListener
        MainActivity.reloadHomeEvent -= reloadHomeListener
        MainActivity.reloadAccountEvent -= reloadAccountListener
    }

    override fun load(context: Context) {
        AcraApplication.setKey("device_id", UtilKt.getDeviceId(context.packageName, context))
        restoreDevice()
        startSyncLoop(context)

        MainActivity.bookmarksUpdatedEvent += bookmarksListener
        MainActivity.afterPluginsLoadedEvent += afterPluginsListener
        MainActivity.mainPluginsLoadedEvent += mainPluginsListener
        MainActivity.reloadHomeEvent += reloadHomeListener
        MainActivity.reloadAccountEvent += reloadAccountListener

        openSettings = { activityContext ->
            val appCompatActivity = activityContext as? AppCompatActivity
            appCompatActivity?.let {
                SyncSettingsFragment(this).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
