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
                        
                        // ★ 즉각성 극대화: 사용자가 앱을 켜거나 포그라운드로 진입하는 찰나에 즉각적으로 다른 기기의 MD5 해시를 당겨와 머지합니다.
                        restoreDevice() 
                    }
                    override fun onActivityResumed(activity: Activity) {}
                    override fun onActivityPaused(activity: Activity) {}
                    override fun onActivityStopped(activity: Activity) {
                        activeActivities = maxOf(0, activeActivities - 1) // 마이너스 오염 보호
                        if (activeActivities <= 0) {
                            // 저사양 기기 랙 방지 및 최종 백업 안전 보장용 10초 유예
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
                        
                        // 가변 대기 상태와 무관하게 시스템 타임스탬프 기준으로 최대 1분(60초)마다 클라우드 데이터 해시 대조
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
                        
                        // ★ 핵심 해결책: 무용지물인 updatedAt 시간 비교 대신, 실제 데이터의 MD5 해시값 변화를 직접 비교하여 100% 감지합니다.
                        val lastHashKey = "sync_last_hash_${device.deviceId}"
                        val lastHash = prefs.getString(lastHashKey, "")
                        val currentHash = UtilKt.md5(device.syncedData ?: "")
                        
                        if (lastHash != currentHash) {
                            Log.d("SYNC", "[SyncPlugin] New data found for device: ${device.name} (Hash changed). Merging...")
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.decompressJsonFromString(encoded)
                                if (decoded.isNotBlank()) {
                                    val backupFile = mapper.readValue<BackupFile>(decoded)
                                    BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                    
                                    // 복원 성공 시 새로운 해시값을 로컬 저장소에 적용
                                    prefs.edit().putString(lastHashKey, currentHash).apply()
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
