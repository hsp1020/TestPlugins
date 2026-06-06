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
import kotlinx.coroutines.withContext

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private var counterBackup = 0
    private var lastResumeWatching: String? = null
    
    private var syncJob: Job? = null
    private var backupJob: Job? = null
    
    private var isAppInForeground = true
    private var currentDelay = 5000L

    private fun startSyncLoop(context: Context) {
        Log.d("SYNC", "[SyncPlugin] startSyncLoop() initiated.")
        
        // 안드로이드 애플리케이션 라이프사이클 콜백을 등록하여 백그라운드 및 Doze 진입 시 코루틴 일시정지 제어
        (context.applicationContext as? Application)?.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                private var activeActivities = 0
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
                override fun onActivityStarted(activity: Activity) {
                    activeActivities++
                    isAppInForeground = true
                    currentDelay = 5000L // 포그라운드로 복귀 시 즉시 5초 가동 주기로 초기화
                }
                override fun onActivityResumed(activity: Activity) {}
                override fun onActivityPaused(activity: Activity) {}
                override fun onActivityStopped(activity: Activity) {
                    activeActivities--
                    if (activeActivities <= 0) {
                        isAppInForeground = false
                        Log.d("SYNC", "[SyncPlugin] App entered background. Suspending active sync loop to save battery.")
                    }
                }
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
                override fun onActivityDestroyed(activity: Activity) {}
            }
        )

        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    if (isAppInForeground) {
                        val currentResumeState = HomeViewModel.getResumeWatching()?.take(10).toString()
                        if (currentResumeState != lastResumeWatching) {
                            Log.d("SYNC", "[SyncPlugin] Watch history changed. Triggering backupDevice().")
                            backupDevice()
                            lastResumeWatching = currentResumeState
                            currentDelay = 5000L // 데이터 변경 시 주기를 즉시 5초 가속 상태로 갱신
                        } else {
                            // 적응형 백오프(Adaptive Back-off): 변경이 없을 경우 동기화 감지 주기를 최대 5분(300,000ms)까지 동적으로 늘려 무의미한 CPU 작동을 방지
                            currentDelay = minOf(currentDelay + 5000L, 300000L)
                        }
                        
                        Log.d("SYNC", "[SyncPlugin] Sync loop tick. counterBackup: $counterBackup, currentDelay: $currentDelay ms")
                        if (counterBackup >= 15) {
                            Log.d("SYNC", "[SyncPlugin] counterBackup reached. Triggering restoreDevice().")
                            restoreDevice()
                            counterBackup = 0
                        }
                        counterBackup++
                    } else {
                        // 백그라운드 상태일 때는 대기 전류 및 CPU 보호를 위해 체크 주기를 강제로 5분으로 늘려 동면 상태로 유도
                        currentDelay = 300000L
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isRestoreEnabled = prefs.getString("restore_device", null)?.let {
                    mapper.readValue<String>(it)
                }
                
                if (isRestoreEnabled == "true") {
                    val devices = ApiUtils.fetchDevices()
                    val currentDeviceId = UtilKt.getDeviceId(context.packageName, context)
                    var dataRestored = false
                    
                    devices.forEach { device ->
                        if (device.deviceId == currentDeviceId) {
                            Log.d("SYNC", "[SyncPlugin] Skipping own device: ${device.name}")
                            return@forEach
                        }
                        
                        val lastUpdatedKey = "sync_last_updated_${device.deviceId}"
                        val lastUpdated = prefs.getString(lastUpdatedKey, "")
                        
                        if (lastUpdated != device.updatedAt) {
                            Log.d("SYNC", "[SyncPlugin] New data found for device: ${device.name} (${device.updatedAt}). Merging...")
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.decompressJsonFromString(encoded)
                                if (decoded.isNotBlank()) {
                                    val backupFile = mapper.readValue<BackupFile>(decoded)
                                    BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                    
                                    prefs.edit().putString(lastUpdatedKey, device.updatedAt ?: "").apply()
                                    dataRestored = true
                                    Log.d("SYNC", "[SyncPlugin] Merge successful for device: ${device.name}")
                                }
                            }
                        } else {
                            Log.d("SYNC", "[SyncPlugin] Device ${device.name} is already up-to-date.")
                        }
                    }

                    if (dataRestored) {
                        Log.d("SYNC", "[SyncPlugin] Data restored. Triggering UI reload and backupDevice().")
                        withContext(Dispatchers.Main) {
                            try {
                                MainActivity.reloadHomeEvent.invoke(true)
                                MainActivity.bookmarksUpdatedEvent.invoke(true)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        backupDevice()
                    }
                } else {
                    Log.d("SYNC", "[SyncPlugin] Restore is disabled in settings.")
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
