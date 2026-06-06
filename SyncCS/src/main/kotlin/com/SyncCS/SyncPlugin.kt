package com.SyncCS

import android.content.Context
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
    private var lastResumeWatching: String? = null
    
    private var syncJob: Job? = null
    private var backupJob: Job? = null
    
    private val restoredDeviceTimestamps = mutableMapOf<String, String>()

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

    @Synchronized
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
        // ★ 핵심 추가: 태블릿과 폰이 실제로 다른 기기 데이터를 확인하러 들어가는 시점을 로깅
        Log.d("SYNC", "[Sync] Checking for remote updates on GitHub Project...")
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
                        if (device.deviceId == currentDeviceId) return@forEach
                        
                        val lastUpdatedKey = "sync_last_updated_${device.deviceId}"
                        val lastUpdated = prefs.getString(lastUpdatedKey, "")
                        
                        if (lastUpdated != device.updatedAt) {
                            // ★ 핵심 추가: 상대방의 새로운 시청 기록 변경점을 정확히 감지한 순간 로깅
                            Log.d("SYNC", "[Sync] New update detected from other device: ${device.name} (Last Updated: ${device.updatedAt}). Merging...")
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
                                if (decoded.isNotBlank()) {
                                    val backupFile = mapper.readValue<BackupFile>(decoded)
                                    BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                                    
                                    prefs.edit().putString(lastUpdatedKey, device.updatedAt ?: "").apply()
                                    dataRestored = true
                                    // ★ 핵심 추가: 상대방의 데이터가 내 저장소에 안전하게 합쳐진 순간 로깅
                                    Log.d("SYNC", "[Sync] Successfully merged ${device.name}'s data with local storage.")
                                }
                            }
                        }
                    }

                    if (dataRestored) {
                        // ★ 핵심 추가: 병합이 끝나고 UI 새로고침 및 내 카드 재생성이 예약되는 최종 순간 로깅
                        Log.d("SYNC", "[Sync] Merge complete! Triggering UI reload and final upload...")
                        withContext(Dispatchers.Main) {
                            try {
                                MainActivity.reloadHomeEvent.invoke(true)
                                MainActivity.bookmarksUpdatedEvent.invoke(true)
                            } catch (e: Exception) {
                                e.printStackTrace()
                            }
                        }
                        backupDevice()
                    } else {
                        Log.d("SYNC", "[Sync] All other devices are already up-to-date. No merge needed.")
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
