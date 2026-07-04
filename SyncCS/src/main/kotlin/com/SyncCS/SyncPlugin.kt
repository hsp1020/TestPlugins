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

    // 램 리소스 누수 방지용 Activity Lifecycle Callback 참조 변수
    private var lifecycleCallbacks: Application.ActivityLifecycleCallbacks? = null

    // 빌드 에러 해결: Boolean 파라미터를 수용하도록 함수 타입을 (Boolean) -> Unit 규격으로 정밀 매핑합니다.
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

                // ★ 다른 기기의 백업 데이터가 성공적으로 내 로컬 DB로 머지되었는지 감지하는 플래그
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
                                
                                // ★ 다른 기기 데이터 머지 성공 시 플래그 갱신
                                dataRestored = true
                                Log.d("SYNC", "[SyncPlugin] Restore merge successful for ${device.name}")
                            }
                        }
                    } else {
                        Log.d("SYNC", "[SyncPlugin] Device ${device.name} is already up-to-date.")
                    }
                }

                // ★ [동기화 핵심 복원] 다른 기기의 새로운 데이터가 내 로컬에 안전하게 머지되었다면,
                // 내 기기도 병합된 최신 통합 데이터를 Gist 원격지에 즉각 재백업하여 Gist 상에서도 파일 내용을 일치시킵니다.
                if (dataRestored) {
                    Log.d("SYNC", "[SyncPlugin] Data restored successfully. Relying on native UI binding. Skipping manual reload.")
                    backupDevice()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // 빌드 에러 해결: 베이스 클래스 Plugin에 해당 lifecycle 메서드가 없으므로 override 키워드를 해제합니다.
    fun onUnload() {
        unload()
    }

    fun unload() {
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
