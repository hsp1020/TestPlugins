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
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private var lastResumeWatching: String? = null
    
    private var syncJob: Job? = null
    private var backupJob: Job? = null
    private var backgroundDelayJob: Job? = null
    
    private var isAppInForeground = true
    private var currentDelay = 5000L
    private var lastRestoreTime = 0L

    // ★ 램 리소스 누수 해결: Activity Lifecycle Callback과 글로벌 리스너를 보존하여 정밀 해제합니다.
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    private val bookmarksListener = { backupDevice() }
    private val afterPluginsListener = { backupDevice() }
    private val mainPluginsListener = { backupDevice() }
    private val reloadHomeListener = { backupDevice() }
    private val reloadAccountListener = { backupDevice() }

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
                    currentDelay = 5000L 
                    restoreDevice() 
                }
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {
                    activeActivities = maxOf(0, activeActivities - 1)
                    if (activeActivities <= 0) {
                        backgroundDelayJob?.cancel()
                        backgroundDelayJob = CoroutineScope(Dispatchers.Main).launch {
                            delay(10000L)
                            isAppInForeground = false
                            currentDelay = 60000L 
                            Log.d("SYNC", "[SyncPlugin] App entered background. Suspending active sync loop to save battery.")
                        }
                    }
                }
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
            app.registerActivityLifecycleCallbacks(lifecycleCallbacks!!)
        }

        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            lastRestoreTime = System.currentTimeMillis()
            while (isActive) {
                try {
                    if (isAppInForeground) {
                        val currentResumeState = HomeViewModel.getResumeWatching()?.take(10).toString()
                        if (currentResumeState != lastResumeWatching) {
                            Log.d("SYNC", "[SyncPlugin] Watch history changed. Triggering backupDevice().")
                            backupDevice()
                            lastResumeWatching = currentResumeState
                            currentDelay = 5000L 
                        } else {
                            currentDelay = minOf(currentDelay + 5000L, 60000L)
                        }
                        
                        val now = System.currentTimeMillis()
                        Log.d("SYNC", "[SyncPlugin] Sync loop tick. Delay: $currentDelay ms, Elapsed since last restore: ${(now - lastRestoreTime) / 1000}s")
                        
                        if (now - lastRestoreTime >= 60000L) {
                            Log.d("SYNC", "[SyncPlugin] 60 seconds elapsed. Triggering restoreDevice().")
                            restoreDevice()
                            lastRestoreTime = now
                        }
                    } else {
                        currentDelay = 60000L
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(currentDelay)
            }
        }
    }

    @Synchronized
    private fun backupDevice() {
        Log.d("SYNC", "[SyncPlugin] backupDevice() called. Debouncing for 3 seconds...")
        backupJob?.cancel()
        backupJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000L) 
            
            try {
                Log.d("SYNC", "[SyncPlugin] Executing backupDevice() logic...")
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    mapper.readValue<String>(it)
                }
                
                if (isBackupEnabled == "true") {
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

        CoroutineScope(Dispatchers.IO).launch {
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
                                val backupFile = mapper.readValue<BackupFile>(decoded)
                                BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                prefs.edit().putString(lastHashKey, currentHash).apply()
                                Log.d("SYNC", "[SyncPlugin] Restore merge successful for ${device.name}")
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

    // ★ 언로드 생명주기 완벽 지원 (가장 극적인 RAM 메모리 누수 원천 진압)
    override fun onUnload() {
        unload()
    }

    override fun unload() {
        Log.d("SYNC", "[SyncPlugin] Unloading plugin. Cancelling active coroutine jobs and unregistering callbacks...")
        
        // 1. 코루틴 작업 해제
        syncJob?.cancel()
        backupJob?.cancel()
        backgroundDelayJob?.cancel()

        // 2. Activity Lifecycle Callback 해제
        val app = AcraApplication.context?.applicationContext as? Application
        lifecycleCallbacks?.let { callbacks ->
            app?.unregisterActivityLifecycleCallbacks(callbacks)
            lifecycleCallbacks = null
            isLifecycleRegistered = false
        }

        // 3. 글로벌 이벤트 버스 구독 해제
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
