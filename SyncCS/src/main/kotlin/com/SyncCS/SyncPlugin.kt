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

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private var counterBackup = 0
    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null
    
    // Java의 Handler, Looper, Runnable을 완전히 폐기하고 Kotlin Coroutine Job으로 대체
    private var syncJob: Job? = null

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
                    if (counterBackup >= 15) {
                        restoreDevice()
                    }
                    counterBackup++
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                delay(5000L) // Kotlin Coroutine의 비동기 지연 함수 사용
            }
        }
    }

    private fun backupDevice() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                counterBackup = 0
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                
                // Java의 String::class.java 대신 Kotlin Reified Type 사용
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
                    val currentDeviceId = UtilKt.getDeviceId(context.packageName, context)
                    val targetDevice = devices.firstOrNull { it.deviceId == currentDeviceId } ?: devices.firstOrNull()
                    
                    targetDevice?.syncedData?.let { encoded ->
                        val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
                        if (decoded.isNotBlank()) {
                            val backupFile = mapper.readValue<BackupFile>(decoded)
                            BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
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
                SyncSettingsFragment(this, context).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
