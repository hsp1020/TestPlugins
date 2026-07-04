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
    private var backgroundDelayJob: Job? = null
    
    // ★ 업로드 중간 네트워크 중단(Cancellation) 버그 수정을 위한 비동기 상태 제어자 분리
    private var debounceJob: Job? = null
    private val backupMutex = kotlinx.coroutines.sync.Mutex()
    
    private var isAppInForeground = true
    private var currentDelay = 5000L
    private var lastRestoreTime = 0L

    // 램 리소스 누수 방지용 Activity Lifecycle Callback 참조 변수
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    // 비동기 빌드 규격 매핑
    private val bookmarksListener = { _: Boolean -> backupDevice() }
    private val afterPluginsListener = { _: Boolean -> backupDevice() }
    private val mainPluginsListener = { _: Boolean -> backupDevice() }
    private val reloadHomeListener = { _: Boolean -> backupDevice() }
    private val reloadAccountListener = { _: Boolean -> backupDevice() }

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
        debounceJob?.cancel() // 오직 대기 중인 3초 디바운스 타이머만 리셋 취소합니다.
        debounceJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000L) 
            
            // ★ 이미 깃허브 네트워크 전송이 시작된 인스턴스는 취소하지 않고 안전하게 뮤텍스 동시성 대기열에 진입시킵니다.
            backupMutex.withLock {
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

                var dataRestored = false

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
                                
                                dataRestored = true
                                Log.d("SYNC", "[SyncPlugin] Restore merge successful for ${device.name}")
                            }
                        }
                    } else {
                        Log.d("SYNC", "[SyncPlugin] Device ${device.name} is already up-to-date.")
                    }
                }

                if (dataRestored) {
                    Log.d("SYNC", "[SyncPlugin] Data restored successfully. Triggering UI refresh and auto backup.")
                    
                    withContext(Dispatchers.Main) {
                        try {
                            MainActivity.reloadHomeEvent.invoke(true)
                            MainActivity.bookmarksUpdatedEvent.invoke(true)
                        } catch (e: Exception) {
                            Log.e("SYNC", "Failed to trigger UI reload events: ${e.message}")
                        }
                    }

                    backupDevice()
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
        
        // 1. 코루틴 작업 해제
        syncJob?.cancel()
        debounceJob?.cancel()
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
