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

    private fun startSyncLoop() {
        Log.d("SYNC", "[SyncPlugin] startSyncLoop() initiated.")
        syncJob?.cancel()
        syncJob = CoroutineScope(Dispatchers.IO).launch {
            while (isActive) {
                try {
                    val currentResumeState = HomeViewModel.getResumeWatching()?.take(10).toString()
                    if (currentResumeState != lastResumeWatching) {
                        Log.d("SYNC", "[SyncPlugin] Watch history changed. Triggering backupDevice().")
                        backupDevice()
                        lastResumeWatching = currentResumeState
                    }
                    
                    Log.d("SYNC", "[SyncPlugin] Sync loop tick. counterBackup: $counterBackup")
                    if (counterBackup >= 15) {
                        Log.d("SYNC", "[SyncPlugin] counterBackup reached 15. Triggering restoreDevice().")
                        restoreDevice()
                        counterBackup = 0
                    }
                    counterBackup++
                } catch (e: Exception) {
                    Log.d("SYNC", "[SyncPlugin] Exception in sync loop: ${e.message}")
                    e.printStackTrace()
                }
                delay(5000L)
            }
        }
    }

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
                Log.d("SYNC", "[SyncPlugin] Exception in backupDevice: ${e.message}")
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
                            Log.d("SYNC", "[SyncPlugin] New data found for device: ${device.name}. Merging...")
                            device.syncedData?.let { encoded ->
                                val decoded = UtilKt.restoreText(UtilKt.decompressJsonFromString(encoded))
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
                                Log.d("SYNC", "[SyncPlugin] Exception during UI reload: ${e.message}")
                                e.printStackTrace()
                            }
                        }
                        backupDevice()
                    }
                } else {
                    Log.d("SYNC", "[SyncPlugin] Restore is disabled in settings.")
                }
            } catch (e: Exception) {
                Log.d("SYNC", "[SyncPlugin] Exception in restoreDevice: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    override fun load(context: Context) {
        Log.d("SYNC", "[SyncPlugin] load() called.")
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
                Log.d("SYNC", "[SyncPlugin] Opening SyncSettingsFragment.")
                SyncSettingsFragment(this, context).show(it.supportFragmentManager, "Sync")
            }
        }
    }
}
