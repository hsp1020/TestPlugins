package com.SyncCS

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.appcompat.app.AppCompatActivity
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
import kotlinx.coroutines.launch

@CloudstreamPlugin
class SyncPlugin : Plugin() {
    private var counterBackup = 0
    private var lastResumeWatching: List<DataStoreHelper.ResumeWatchingResult>? = null
    private val handler = Handler(Looper.getMainLooper())

    private val runnable = object : Runnable {
        override fun run() {
            try {
                CoroutineScope(Dispatchers.IO).launch {
                    val resumeWatching = HomeViewModel.getResumeWatching()
                    // 수정됨: Nullable 반환이므로 ?. 안전 호출 연산자 사용
                    val listTake = resumeWatching?.take(10)
                    if (listTake != lastResumeWatching) {
                        backupDevice(true)
                        lastResumeWatching = listTake
                    }
                    if (counterBackup >= 15) {
                        restoreDevice(false)
                    }
                    counterBackup++
                }
                handler.postDelayed(this, 5000L)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun backupDevice(unused: Boolean) {
        try {
            counterBackup = 0
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                val isBackupEnabled = prefs.getString("backup_device", null)?.let {
                    mapper.readValue(it, String::class.java)
                }
                
                if (isBackupEnabled == "true") {
                    val resumeWatching = HomeViewModel.getResumeWatching()
                    val backupFile = BackupUtils.getBackup(context, resumeWatching)
                    val backupJson = backupFile?.let { mapper.writeValueAsString(it) } ?: ""
                    ApiUtils.syncThisDevice(backupJson, context)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun restoreDevice(unused: Boolean) {
        try {
            CoroutineScope(Dispatchers.IO).launch {
                val context = AcraApplication.context ?: return@launch
                val prefs = with(DataStore) { context.getSharedPrefs() }
                val isRestoreEnabled = prefs.getString("restore_device", null)?.let {
                    mapper.readValue(it, String::class.java)
                }
                
                if (isRestoreEnabled == "true") {
                    val devices = ApiUtils.fetchDevices()
                    val currentDeviceId = UtilKt.getDeviceId(context.packageName, context)
                    val targetDevice = devices.firstOrNull { it.deviceId == currentDeviceId } ?: devices.firstOrNull()
                    
                    targetDevice?.syncedData?.let { encoded ->
                        val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
                        if (decoded.isNotBlank()) {
                            val backupFile = mapper.readValue(decoded, BackupFile::class.java)
                            BackupUtils.restore(context, backupFile, restoreSettings = true, restoreDataStore = true)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun load(context: Context) {
        AcraApplication.setKey("device_id", UtilKt.getDeviceId(context.packageName, context))
        restoreDevice(true)
        handler.post(runnable)

        MainActivity.bookmarksUpdatedEvent += { backupDevice(true) }
        MainActivity.afterPluginsLoadedEvent += { backupDevice(true) }
        MainActivity.mainPluginsLoadedEvent += { backupDevice(true) }
        MainActivity.reloadHomeEvent += { backupDevice(true) }
        MainActivity.reloadAccountEvent += { backupDevice(true) }

        val appCompatActivity = context as AppCompatActivity
        openSettings = { _ ->
            SyncSettingsFragment(this).show(appCompatActivity.supportFragmentManager, "Sync")
        }
    }
}
