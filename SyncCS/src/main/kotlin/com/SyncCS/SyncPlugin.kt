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
    
    private val restoredDeviceTimestamps = mutableMapOf<String, String>()
    
    // ★★★ 핵심 수정: Fragment와 동일하게 패키지명을 고정하여 기기 ID 불일치(자폭 버그)를 원천 차단합니다.
    private val pluginPackageName = "com.SyncCS"

    private fun startSyncLoop() {
        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val currentResumeState = HomeViewModel.getResumeWatching()?.take(10).toString()
                    if (currentResumeState != lastResumeWatching) {
                        backupDevice()
                        lastResumeWatching = currentResumeState
                    }
                    
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
            delay(3000L) // 3초 디바운스
            
            try {
                counterBackup = 0
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    mapper.readValue<String>(it)
                }
                
                if (isBackupEnabled == "true") {
                    val backupFile = BackupUtils.getBackup(context)
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
                    // ★ 수정됨: context.packageName 대신 pluginPackageName("com.SyncCS") 사용
                    val currentDeviceId = UtilKt.getDeviceId(pluginPackageName, context)
                    var dataRestored = false
                    
                    devices.forEach { device ->
                        // 내 기기의 옛날 데이터는 절대 다운로드하지 않고 무시합니다.
                        if (device.deviceId == currentDeviceId) return@forEach
                        
                        val lastUpdatedKey = "sync_last_updated_${device.deviceId}"
                        val lastUpdated = prefs.getString(lastUpdatedKey, "")
                        
                        if (lastUpdated != device.updatedAt) {
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
                                if (decoded.isNotBlank()) {
                                    val backupFile = mapper.readValue<BackupFile>(decoded)
                                    BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                    
                                    prefs.edit().putString(lastUpdatedKey, device.updatedAt ?: "").apply()
                                    dataRestored = true
                                }
                            }
                        }
                    }

                    if (dataRestored) {
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
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    override fun load(context: Context) {
        // ★ 수정됨: context.packageName 대신 pluginPackageName("com.SyncCS") 사용
        AcraApplication.setKey("device_id", UtilKt.getDeviceId(pluginPackageName, context))
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
                SyncSettingsFragment(this, context).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
