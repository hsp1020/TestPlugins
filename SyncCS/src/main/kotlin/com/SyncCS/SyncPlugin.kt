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

    companion object {
        private var isLifecycleRegistered = false 
    }

    private fun startSyncLoop(context: Context) {
        Log.d("SYNC", "[SyncPlugin] startSyncLoop() initiated.")
        
        val app = context.applicationContext as? Application
        if (app != null && !isLifecycleRegistered) {
            isLifecycleRegistered = true
            app.registerActivityLifecycleCallbacks(
                object : Application.ActivityLifecycleCallbacks {
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
            )
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
            val backupFile = BackupUtils.getBackup(AcraApplication.context ?: return@launch)
            val backupJson = mapper.writeValueAsString(backupFile)
            val (syncSuccess, syncMsg) = ApiUtils.syncThisDevice(backupJson, AcraApplication.context)
            if (syncSuccess) {
                Log.d("SYNC", "[SyncPlugin] Auto Backup successful.")
            } else {
                Log.d("SYNC", "[SyncPlugin] Auto Backup failed: $syncMsg")
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

    override fun load(context: Context) {
        AcraApplication.setKey("device_id", UtilKt.getDeviceId(context.packageName, context))
        restoreDevice()
        startSyncLoop(context)

        MainActivity.bookmarksUpdatedEvent += { backupDevice() }
        MainActivity.afterPluginsLoadedEvent += { backupDevice() }
        MainActivity.mainPluginsLoadedEvent += { backupDevice() }
        MainActivity.reloadHomeEvent += { backupDevice() }
        MainActivity.reloadAccountEvent += { backupDevice() }

        openSettings = { activityContext ->
            val appCompatActivity = activityContext as? AppCompatActivity
            appCompatActivity?.let {
                SyncSettingsFragment(this).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
