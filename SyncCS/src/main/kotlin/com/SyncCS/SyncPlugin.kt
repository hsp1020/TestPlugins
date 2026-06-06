package com.SyncCS

import android.content.Context
import androidx.appcompat.app.AppCompatActivity
import com.fasterxml.jackson.module.kotlin.readValue
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.mapper
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.lagradost.cloudstream3.utils.DataStore
import com.lagradost.cloudstream3.utils.DataStoreHelper
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
    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null
    
    private var syncJob: Job? = null
    private var backupJob: Job? = null
    
    // ★ 추가됨: 무한 새로고침을 막기 위해 각 기기별 마지막 동기화 시간을 기억합니다.
    private val restoredDeviceTimestamps = mutableMapOf<String, String>()

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val resumeWatching = HomeViewModel.getResumeWatching()
                    val listTake = resumeWatching?.take(10)
                    if (listTake != lastResumeWatching) {
                        backupDevice()
                        lastResumeWatching = listTake
                    }
                    // 75초(5초 * 15번)마다 다른 기기의 데이터를 확인하고 다운로드합니다.
                    if (counterBackup >= 15) {
                        restoreDevice()
                        counterBackup = 0
                    }
                    counterBackup++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5000L)
            }
        }
    }

    private fun backupDevice() {
        backupJob?.cancel()
        backupJob = CoroutineScope(Dispatchers.IO).launch {
            delay(3000L) // 3초 디바운스 (동시 실행 방지)
            
            try {
                counterBackup = 0
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    mapper.readValue<String>(it)
                }
                
                if (isBackupEnabled == "true") {
                    val resumeWatching = HomeViewModel.getResumeWatching()
                    val backupFile = BackupUtils.getBackup(context, resumeWatching)
                    val backupJson = mapper.writeValueAsString(backupFile)
                    ApiUtils.syncThisDevice(backupJson, context)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun restoreDevice() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isRestoreEnabled = prefs.getString("restore_device", null)?.let {
                    mapper.readValue<String>(it)
                }
                
                if (isRestoreEnabled == "true") {
                    val devices = ApiUtils.fetchDevices()
                    var dataRestored = false
                    
                    devices.forEach { device ->
                        val lastUpdated = restoredDeviceTimestamps[device.deviceId]
                        
                        // ★ 핵심: GitHub의 데이터가 내가 마지막으로 받은 데이터보다 최신일 때만 병합 진행
                        if (lastUpdated != device.updatedAt) {
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
                                if (decoded.isNotBlank()) {
                                    val backupFile = mapper.readValue<BackupFile>(decoded)
                                    BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                    
                                    restoredDeviceTimestamps[device.deviceId] = device.updatedAt ?: ""
                                    dataRestored = true
                                }
                            }
                        }
                    }

                    // ★ 가장 중요한 부분: 디스크에 병합이 끝났으면 앱의 메모리를 새로고침하여 병합된 데이터를 인식시킵니다.
                    // 이 새로고침 이벤트가 발생하면 앱은 즉시 병합된 데이터를 GitHub에 다시 업로드합니다.
                    if (dataRestored) {
                        withContext(Dispatchers.Main) {
                            try {
                                MainActivity.reloadHomeEvent.invoke(true)
                                MainActivity.bookmarksUpdatedEvent.invoke(true)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
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
        startSyncLoop()

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
