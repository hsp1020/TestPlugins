package com.SyncCS

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lagradost.cloudstream3.AcraApplication
import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.MainActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import com.lagradost.cloudstream3.ui.home.HomeViewModel
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.*

@CloudstreamPlugin
class SyncPlugin : Plugin() {

    private val handler = Handler(Looper.getMainLooper())
    private val pluginScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mapper = jacksonObjectMapper()

    private var lastResumeWatching: List<Any>? = null
    private var counterBackup = 0
    private var backupDebounceJob: Job? = null

    private var currentPollingInterval = 5000L
    private val minPollingInterval = 5000L
    private val maxPollingInterval = 60000L

    private val runnable = object : Runnable {
        override fun run() {
            pluginScope.launch {
                try {
                    val list = HomeViewModel.getResumeWatching() as? List<Any>
                    val listTake = list?.take(10)
                    if (listTake != lastResumeWatching) {
                        Log.d("SyncCS", "Watch history change detected. Triggering Debounce Backup...")
                        triggerBackupDebounced()
                        lastResumeWatching = listTake
                    }

                    if (counterBackup >= 15) {
                        val success = restoreDevice()
                        if (success) {
                            currentPollingInterval = minPollingInterval
                        } else {
                            currentPollingInterval = (currentPollingInterval * 2).coerceAtMost(maxPollingInterval)
                        }
                        counterBackup = 0
                    } else {
                        counterBackup++
                    }
                } catch (e: Exception) {
                    currentPollingInterval = (currentPollingInterval * 2).coerceAtMost(maxPollingInterval)
                }
            }
            handler.postDelayed(this, currentPollingInterval)
        }
    }

    override fun load(context: Context) {
        val deviceId = Util.getDeviceId(context.packageName, context)
        SharedPrefsHelper.getPrefs(context).edit().putString("device_id", deviceId).apply()
        Log.d("SyncCS", "Plugin Loaded. This Device ID: $deviceId")

        pluginScope.launch { restoreDevice() }

        handler.post(runnable)

        val backupTrigger = { _: Boolean -> triggerBackupDebounced() }
        MainActivity.bookmarksUpdatedEvent.plusAssign(backupTrigger)
        MainActivity.afterPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.mainPluginsLoadedEvent.plusAssign(backupTrigger)
        MainActivity.reloadHomeEvent.plusAssign(backupTrigger)
        MainActivity.reloadAccountEvent.plusAssign(backupTrigger)

        openSettings = { ctx ->
            val currentActivity = ctx as? AppCompatActivity 
                ?: AcraApplication.context as? AppCompatActivity 
                ?: CloudStreamApp.context as? AppCompatActivity

            if (currentActivity != null && !currentActivity.isDestroyed && !currentActivity.isFinishing) {
                SyncSettingsFragment(this@SyncPlugin).show(currentActivity.supportFragmentManager, "Sync")
            } else {
                Log.e("SyncCS", "Failed to open settings: Activity context is invalid.")
            }
        }
    }

    fun triggerBackupDebounced() {
        backupDebounceJob?.cancel()
        backupDebounceJob = pluginScope.launch {
            delay(15000L)
            backupDevice()
        }
    }

    // [수정됨] 비동기로 흐르지 않고 확실하게 완료를 기다리도록 suspend fun으로 변경
    suspend fun forceBackupNow() {
        backupDebounceJob?.cancel()
        Log.d("SyncCS", "Manual Push (Backup) Triggered!")
        backupDevice()
    }

    private suspend fun backupDevice() = withContext(Dispatchers.IO) {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return@withContext
        val pref = SharedPrefsHelper.getPrefs(context)
        val backupEnabled = pref.getString("backup_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (backupEnabled && ApiUtils.isLoggedIn()) {
            val backupFile = BackupUtils.getFullBackup(context, syncPluginsEnabled)
            if (backupFile == null) return@withContext
            
            val json = mapper.writeValueAsString(backupFile)

            if (json.isNotEmpty()) {
                val compressed = Util.compressJsonToString(Util.cleanedSyncData(json))
                Log.d("SyncCS", "Compressed Payload Size: ${compressed.length} characters")
                val result = ApiUtils.syncThisDevice(compressed, context)
                if (result.first) {
                    pref.edit().putLong("last_sync_timestamp", System.currentTimeMillis()).apply()
                    Log.d("SyncCS", "Backup Complete. Timestamp updated.")
                }
            }
        }
    }

    suspend fun restoreDevice(): Boolean = withContext(Dispatchers.IO) {
        val context = AcraApplication.context ?: CloudStreamApp.context ?: return@withContext false
        val pref = SharedPrefsHelper.getPrefs(context)
        val restoreEnabled = pref.getString("restore_device", "false") == "true"
        val syncPluginsEnabled = pref.getString("sync_plugins_enabled", "false") == "true"

        if (restoreEnabled && ApiUtils.isLoggedIn()) {
            Log.d("SyncCS", "--- Initiating Restore Process ---")
            try {
                val devices = ApiUtils.fetchDevices(context)
                val thisDeviceId = Util.getDeviceId(context.packageName, context)

                for (device in devices) {
                    if (device.deviceId == thisDeviceId) continue

                    val syncedData = device.syncedData ?: continue
                    if (syncedData.isNotEmpty()) {
                        val decompressed = Util.restoreText(Util.decompressJsonFromString(syncedData))
                        val backupFile = mapper.readValue(decompressed, BackupFile::class.java)

                        if (backupFile != null) {
                            withContext(Dispatchers.Main) {
                                BackupUtils.restore(context, backupFile, true, true, syncPluginsEnabled)
                            }
                        }
                    }
                }
                return@withContext true
            } catch (e: Exception) {
                Log.e("SyncCS", "Restore Process Failed", e)
                return@withContext false
            }
        }
        return@withContext true
    }
}
